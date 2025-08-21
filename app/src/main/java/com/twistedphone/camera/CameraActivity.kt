package com.twistedphone.camera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.util.Size
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.twistedphone.util.Logger
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.*

/**
 * CameraActivity - displays a warped, nightmarish live camera feed.
 *
 * Key components:
 *  - Uses CameraX Preview + ImageAnalysis
 *  - Converts ImageProxy to Bitmap
 *  - Produces a warping effect using an inexpensive depth-row-shift algorithm
 *  - Optionally integrates with a Midas TFLite interpreter (downscaled inference + upsample)
 *
 * Drop-in replacement: overwrite existing CameraActivity.kt with this file.
 *
 * Notes on optimization:
 *  - Depth inference is run on a downscaled version (targetDownscaleHeight).
 *  - We compute per-row depth values (one float per row) then use those to shift entire rows
 *    in the full-res bitmap. The result is visually compelling and efficient.
 */
class CameraActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "CameraActivity"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    private lateinit var textureView: TextureView
    private val analysisExecutor = Executors.newFixedThreadPool(2)
    private var cameraProvider: ProcessCameraProvider? = null

    // Optional: plug in your TFLite Midas interpreter instance here (null by default).
    // If you have a tflite.Interpreter instance you should call `estimateDepthForDownscaledBitmap`
    // with it; here we'll use a null-check and a fallback algorithm.
    // Example: private var midasInterpreter: Interpreter? = null
    private var midasInterpreter: Any? = null // type kept Any to avoid requiring tflite dep in compile

    // For temporal smoothing:
    private var previousRowDepth: FloatArray? = null

    // Controls (tweak for performance/appearance)
    private val targetDownscaleHeight = 128          // Midas runs on this height (tiny)
    private val rowShiftStrength = 0.08f             // per-row multiplier for shifts (tweak)
    private val colorDarkenFactor = 0.75f            // darker output
    private val smoothingAlpha = 0.25f               // temporal smoothing of depth rows

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Create a simple full-screen TextureView programmatically so this will replace layouts reliably.
        textureView = TextureView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            // keep screen on while previewing
            keepScreenOn = true
        }
        setContentView(FrameLayout(this).apply {
            addView(textureView)
        })

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdownNow()
        cameraProvider?.unbindAll()
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Permissions not granted.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return

        val preview = Preview.Builder()
            .setTargetResolution(Size(1280, 720)) // target a common resolution; CameraX will negotiate best.
            .build()

        // We do no direct PreviewView; we attach preview to a SurfaceTexture later in onSurfaceTextureAvailable.
        preview.setSurfaceProvider { request ->
            val surfaceTexture = textureView.surfaceTexture ?: run {
                request.willNotProvideSurface()
                return@setSurfaceProvider
            }
            val surface = Surface(surfaceTexture)
            request.provideSurface(surface, ContextCompat.getMainExecutor(this)) { }
        }

        val analysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(640, 480))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(analysisExecutor) { imageProxy ->
                    try {
                        handleFrame(imageProxy)
                    } finally {
                        imageProxy.close()
                    }
                }
            }

        try {
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            Logger.d(TAG, "Camera bound successfully")
        } catch (e: Exception) {
            Logger.e(TAG, "Camera bind failed: ${e.message}")
        }
    }

    /**
     * Convert an ImageProxy (YUV) to ARGB_8888 bitmap.
     * This is a fairly optimized conversion using the byte buffers directly.
     */
    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        val format = image.format
        val planeY = image.planes[0]
        val planeU = image.planes[1]
        val planeV = image.planes[2]

        val width = image.width
        val height = image.height

        // Flatten YUV to NV21 byte array then decode to bitmap via RenderScript-like algorithm.
        try {
            val ySize = planeY.buffer.remaining()
            val uSize = planeU.buffer.remaining()
            val vSize = planeV.buffer.remaining()
            val nv21 = ByteArray(ySize + uSize + vSize)

            planeY.buffer.get(nv21, 0, ySize)
            val chromaRowStride = planeU.rowStride
            val chromaPixelStride = planeU.pixelStride

            // Convert UV planes into interleaved VU for NV21
            var offset = ySize
            val uBuffer = planeU.buffer
            val vBuffer = planeV.buffer

            // naive but compatible conversion:
            uBuffer.rewind()
            vBuffer.rewind()
            val cols = width / 2
            val rows = height / 2
            val uRowStride = planeU.rowStride
            val vRowStride = planeV.rowStride

            // iterate subsampled chroma
            for (row in 0 until rows) {
                var uPos = row * uRowStride
                var vPos = row * vRowStride
                for (col in 0 until cols) {
                    val v = vBuffer.get(vPos).toInt() and 0xFF
                    val u = uBuffer.get(uPos).toInt() and 0xFF
                    nv21[offset++] = v.toByte()
                    nv21[offset++] = u.toByte()
                    uPos += chromaPixelStride
                    vPos += chromaPixelStride
                }
            }

            // decode NV21 into Bitmap using YuvImage -> JPEG -> BitmapFactory (fast and simple)
            val yuvImage = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null)
            val out = java.io.ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 85, out)
            val jpegBytes = out.toByteArray()
            out.close()
            return android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        } catch (e: Exception) {
            Logger.e(TAG, "imageProxyToBitmap conversion failed: ${e.message}")
            return null
        }
    }

    private var lastWarped: Bitmap? = null

    private fun handleFrame(image: ImageProxy) {
        // Convert frame to bitmap quickly
        val bmp = imageProxyToBitmap(image) ?: return

        // Run warp pipeline on worker thread (we're already on analysis thread)
        val warped = if (midasInterpreter != null) {
            enhancedWarpUsingMidas(bmp)
        } else {
            simpleWarp(bmp)
        }

        lastWarped = warped

        // Post to texture view
        postBitmapToTextureView(warped)
    }

    // Simple fallback warp (no external models): luma-based row depth estimation + row shift
    private fun simpleWarp(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height

        val scaledDown = Bitmap.createScaledBitmap(src, max(64, width / 8), max(64, height / 8), true)
        val rowDepths = FloatArray(scaledDown.height)
        // compute average luminance per row (0..1)
        val px = IntArray(scaledDown.width)
        for (y in 0 until scaledDown.height) {
            scaledDown.getPixels(px, 0, scaledDown.width, 0, y, scaledDown.width, 1)
            var sum = 0L
            for (x in px.indices) {
                val c = px[x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val lum = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                sum += lum
            }
            rowDepths[y] = 1f - (sum.toFloat() / (scaledDown.width * 255f)) // brighter => farther
        }

        // apply smoothing to previous
        val fullRowDepth = upsampleRowDepth(rowDepths, height)
        val smooth = smoothRows(fullRowDepth)

        // apply warp by shifting each row horizontally proportional to depth
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = Rect()
        val srcRect = Rect()
        val destRect = Rect()

        for (y in 0 until height) {
            val shift = ((smooth[y] - 0.5f) * rowShiftStrength * width).toInt()
            srcRect.set(0, y, width, y + 1)
            val left = -shift
            val right = width - shift
            destRect.set(left, y, right, y + 1)
            canvas.drawBitmap(src, srcRect, destRect, paint)
        }

        // color grade: darker + slight saturation reduction
        applyColorGrade(out, colorDarkenFactor)
        return out
    }

    /**
     * Enhanced warp using Midas depth maps on a downscaled frame.
     * Expected behavior:
     *  - Downscale src to small height (targetDownscaleHeight)
     *  - Run depth estimator on the downscaled image (midasInterpreter must be supplied externally)
     *  - Extract per-row depth mean and upsample to full height
     *  - Apply row-shift warp to original resolution
     */
    private fun enhancedWarpUsingMidas(src: Bitmap): Bitmap {
        try {
            val width = src.width
            val height = src.height
            // downscale to target height while preserving aspect ratio
            val downscale = max(1, height / targetDownscaleHeight)
            val smallW = max(32, width / downscale)
            val smallH = max(32, height / downscale)
            val small = Bitmap.createScaledBitmap(src, smallW, smallH, true)

            // If you have a real interpreter: run it here.
            // For example, integrate a tflite.Interpreter instance, preprocess small bitmap to float input,
            // call interpreter.run(input, output) and convert output to depth floats.
            // Because this module avoids bundling tflite dep here, we call a helper stub that you should implement
            // if you wire in a real interpreter.
            val depthRows = estimateDepthForDownscaledBitmap(small) // returns FloatArray of length smallH

            // upsample to full height
            val fullRowDepth = upsampleRowDepth(depthRows, height)
            val smooth = smoothRows(fullRowDepth)

            // create output by shifting rows proportionally to depth
            val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val srcRect = Rect()
            val destRect = Rect()

            for (y in 0 until height) {
                val shift = ((smooth[y] - 0.5f) * rowShiftStrength * width).toInt()
                srcRect.set(0, y, width, y + 1)
                val left = -shift
                val right = width - shift
                destRect.set(left, y, right, y + 1)
                canvas.drawBitmap(src, srcRect, destRect, paint)
            }

            // subtle additional transforms to feel "uncanny"
            applyColorGrade(out, colorDarkenFactor)
            addSubtleVibrance(out)
            return out
        } catch (e: Exception) {
            Logger.e(TAG, "enhancedWarpUsingMidas failed: ${e.message}")
            return simpleWarp(src)
        }
    }

    /**
     * Placeholder for integrating an actual Midas/TFLite interpreter.
     * If you plug a tflite.Interpreter instance, replace this implementation with one that:
     *  - Preprocesses `small` to float input (normalize, resize)
     *  - Runs interpreter -> produces a (smallH x smallW) depth map
     *  - Returns FloatArray of length smallH where each entry is the average depth for that row (0..1)
     *
     * For robustness, here we compute a cheap heuristic: for each row compute average luminance and invert it
     * (brighter => farther) — this produces usable depth-like variation where a model is absent.
     */
    private fun estimateDepthForDownscaledBitmap(small: Bitmap): FloatArray {
        // If you have a midasInterpreter and know how to feed it, do that here.
        // Otherwise, fallback to luminance heuristic per-row.
        val h = small.height
        val w = small.width
        val out = FloatArray(h)
        val row = IntArray(w)

        for (y in 0 until h) {
            small.getPixels(row, 0, w, 0, y, w, 1)
            var sum = 0L
            for (x in 0 until w) {
                val c = row[x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val lum = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                sum += lum
            }
            val avg = sum.toFloat() / (w * 255f)
            // produce depth-like metric in [0,1]
            out[y] = 1f - avg
        }
        return out
    }

    /**
     * Upsample a per-row depth array (smallH) to targetHeight rows using linear interpolation.
     */
    private fun upsampleRowDepth(small: FloatArray, targetHeight: Int): FloatArray {
        val inH = small.size
        val out = FloatArray(targetHeight)
        if (inH == targetHeight) {
            System.arraycopy(small, 0, out, 0, inH)
            return out
        }
        for (y in 0 until targetHeight) {
            val pos = y.toFloat() * (inH - 1) / (targetHeight - 1).coerceAtLeast(1)
            val lo = pos.toInt().coerceIn(0, inH - 1)
            val hi = (lo + 1).coerceAtMost(inH - 1)
            val frac = pos - lo
            out[y] = small[lo] * (1 - frac) + small[hi] * frac
        }
        return out
    }

    /**
     * Temporal smoothing: blend previous row depth with current
     */
    private fun smoothRows(rows: FloatArray): FloatArray {
        val prev = previousRowDepth
        val out = FloatArray(rows.size)
        if (prev == null || prev.size != rows.size) {
            for (i in rows.indices) out[i] = rows[i]
        } else {
            val a = smoothingAlpha
            for (i in rows.indices) out[i] = prev[i] * (1 - a) + rows[i] * a
        }
        previousRowDepth = out
        return out
    }

    /**
     * Color grading helper: darken and reduce saturation slightly.
     */
    private fun applyColorGrade(bmp: Bitmap, darken: Float) {
        val canvas = Canvas(bmp)
        val paint = Paint()
        val cm = ColorMatrix()
        cm.setScale(darken, darken * 0.95f, darken * 0.9f, 1f)
        val sat = 0.9f
        val satMatrix = ColorMatrix()
        satMatrix.setSaturation(sat)
        cm.postConcat(satMatrix)
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(bmp, 0f, 0f, paint)
    }

    /**
     * Add a very subtle local contrast bump / vibrance via a cheap overlay
     */
    private fun addSubtleVibrance(bmp: Bitmap) {
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.alpha = 16
        // tiny radial spotlight to create depth
        val shader = RadialGradient(
            bmp.width * 0.55f, bmp.height * 0.35f,
            max(bmp.width, bmp.height) * 0.9f,
            intArrayOf(Color.TRANSPARENT, 0x11000000),
            floatArrayOf(0.6f, 1f),
            Shader.TileMode.CLAMP
        )
        paint.shader = shader
        canvas.drawRect(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat(), paint)
    }

    /**
     * Post the final bitmap to TextureView as fast as possible.
     */
    private fun postBitmapToTextureView(bmp: Bitmap) {
        if (!textureView.isAvailable) return
        val canvas = textureView.lockCanvas()
        if (canvas == null) return
        try {
            // draw centered, preserving aspect
            canvas.drawColor(Color.BLACK)
            val src = Rect(0, 0, bmp.width, bmp.height)
            val dst = getDstRectForCanvas(canvas.width, canvas.height, bmp.width, bmp.height)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(bmp, src, dst, paint)
        } finally {
            textureView.unlockCanvasAndPost(canvas)
        }
    }

    private fun getDstRectForCanvas(cvW: Int, cvH: Int, bmpW: Int, bmpH: Int): Rect {
        val canvasRatio = cvW.toFloat() / cvH
        val bmpRatio = bmpW.toFloat() / bmpH
        return if (bmpRatio > canvasRatio) {
            // bitmap fills width
            val dstW = cvW
            val dstH = (cvW / bmpRatio).toInt()
            Rect(0, (cvH - dstH) / 2, dstW, (cvH + dstH) / 2)
        } else {
            val dstH = cvH
            val dstW = (cvH * bmpRatio).toInt()
            Rect((cvW - dstW) / 2, 0, (cvW + dstW) / 2, dstH)
        }
    }
}
