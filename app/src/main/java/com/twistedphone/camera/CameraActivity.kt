package com.twistedphone.camera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.twistedphone.TwistedApp
import com.twistedphone.util.Logger
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.*

/**
 * CameraActivity: displays a warped, nightmarish real-time camera feed.
 *
 * Optimization approach:
 *  - We run Midas (if available) on a small downscaled version (targetDownscaleHeight).
 *  - We compute per-row average depth from that small depth map and upsample to full height.
 *  - Warp is applied per-row (horizontal shift proportional to depth), which is extremely fast on CPU yet visually convincing.
 *
 * Requirements:
 *  - If you want true Midas, place a Midas model file at:
 *      - internal storage: <filesDir>/models/midas_small.tflite
 *    OR
 *      - app assets: assets/midas_small.tflite
 *
 * If no model present, the code falls back to a luminance heuristic.
 */
class CameraActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "CameraActivity"
        private const val REQ_CODE = 101
        private val REQUIRED = arrayOf(Manifest.permission.CAMERA)
    }

    private lateinit var textureView: TextureView
    private lateinit var captureBtn: Button
    private val analysisExecutor = Executors.newFixedThreadPool(2)
    private var cameraProvider: ProcessCameraProvider? = null

    // Optional interpreter (null if model not loaded)
    private var midasInterpreter: Interpreter? = null
    private var midasInputWidth = 0
    private var midasInputHeight = 0

    // Temporal smoothing for row depths
    private var previousRowDepth: FloatArray? = null

    // Controls
    private val targetDownscaleHeight = 128
    private val rowShiftStrength = 0.08f
    private val colorDarkenFactor = 0.78f
    private val smoothingAlpha = 0.22f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // layout programmatically - TextureView + capture button
        textureView = TextureView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            keepScreenOn = true
        }

        captureBtn = Button(this).apply {
            text = "Capture"
            val p = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            p.marginStart = 24
            p.topMargin = 24
            layoutParams = p
            setOnClickListener { lastWarped?.let { saveBitmapToGallery(it) } ?: run { Toast.makeText(this@CameraActivity, "No frame ready", Toast.LENGTH_SHORT).show() } }
        }

        val root = FrameLayout(this)
        root.addView(textureView)
        root.addView(captureBtn)
        setContentView(root)

        // Try to load a Midas model if present
        try {
            midasInterpreter = loadMidasInterpreterIfPresent()
            if (midasInterpreter != null) Logger.d(TAG, "Midas interpreter loaded")
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to load Midas interpreter: ${e.message}")
            midasInterpreter = null
        }

        // Permissions
        if (!allPermissions()) {
            ActivityCompat.requestPermissions(this, REQUIRED, REQ_CODE)
        } else {
            startCamera()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdownNow()
        midasInterpreter?.close()
        cameraProvider?.unbindAll()
    }

    private fun allPermissions() = REQUIRED.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode == REQ_CODE) {
            if (allPermissions()) startCamera() else { Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show(); finish() }
        } else super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun startCamera() {
        val cxFuture = ProcessCameraProvider.getInstance(this)
        cxFuture.addListener({
            cameraProvider = cxFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return

        val preview = Preview.Builder()
            .setTargetResolution(Size(1280, 720))
            .build()

        preview.setSurfaceProvider { request ->
            val surfaceTexture = textureView.surfaceTexture
            if (surfaceTexture == null) {
                request.willNotProvideSurface()
                return@setSurfaceProvider
            }
            // ensure buffer size matches requested resolution
            surfaceTexture.setDefaultBufferSize(1280, 720)
            val surface = Surface(surfaceTexture)
            request.provideSurface(surface, ContextCompat.getMainExecutor(this)) {}
        }

        val analysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(640, 480))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build().also { ia ->
                ia.setAnalyzer(analysisExecutor) { imageProxy ->
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
            Logger.d(TAG, "Camera bound")
        } catch (e: Exception) {
            Logger.e(TAG, "bindCameraUseCases failed: ${e.message}")
        }
    }

    // Convert ImageProxy (YUV) -> Bitmap (ARGB_8888)
    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        try {
            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]
            val width = image.width
            val height = image.height

            val ySize = yPlane.buffer.remaining()
            val uSize = uPlane.buffer.remaining()
            val vSize = vPlane.buffer.remaining()
            val nv21 = ByteArray(ySize + uSize + vSize)

            yPlane.buffer.get(nv21, 0, ySize)

            // interleave VU for NV21
            var offset = ySize
            val uBuffer = uPlane.buffer
            val vBuffer = vPlane.buffer
            uBuffer.rewind(); vBuffer.rewind()

            val chromaRowStride = uPlane.rowStride
            val chromaPixelStride = uPlane.pixelStride
            val rows = height / 2
            val cols = width / 2

            for (r in 0 until rows) {
                var uPos = r * chromaRowStride
                var vPos = r * vPlane.rowStride
                for (c in 0 until cols) {
                    val v = vBuffer.get(vPos).toInt() and 0xFF
                    val u = uBuffer.get(uPos).toInt() and 0xFF
                    nv21[offset++] = v.toByte()
                    nv21[offset++] = u.toByte()
                    uPos += chromaPixelStride
                    vPos += chromaPixelStride
                }
            }

            val yuvImage = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null)
            val baos = java.io.ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 85, baos)
            val jpeg = baos.toByteArray()
            baos.close()
            return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        } catch (e: Exception) {
            Logger.e(TAG, "imageProxyToBitmap error: ${e.message}")
            return null
        }
    }

    private var lastWarped: Bitmap? = null

    private fun handleFrame(image: ImageProxy) {
        val bmp = imageProxyToBitmap(image) ?: return
        val warped = try {
            if (midasInterpreter != null) enhancedWarpUsingMidas(bmp) else simpleWarp(bmp)
        } catch (e: Exception) {
            Logger.e(TAG, "handleFrame warp exception: ${e.message}")
            simpleWarp(bmp)
        }
        lastWarped = warped
        postBitmapToTextureView(warped)
    }

    /**
     * Simple (non-model) warp: compute per-row luminance depth on a small downscale and apply row shifts.
     */
    private fun simpleWarp(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val smallW = max(48, width / 8)
        val smallH = max(48, height / 8)
        val small = Bitmap.createScaledBitmap(src, smallW, smallH, true)

        val rowDepths = FloatArray(smallH)
        val rowPixels = IntArray(smallW)
        for (y in 0 until smallH) {
            small.getPixels(rowPixels, 0, smallW, 0, y, smallW, 1)
            var sum = 0L
            for (x in 0 until smallW) {
                val c = rowPixels[x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val lum = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                sum += lum
            }
            val avg = sum.toFloat() / (smallW * 255f)
            // invert: brighter => farther
            rowDepths[y] = 1f - avg
        }

        val fullRowDepth = upsampleRowDepth(rowDepths, height)
        val smooth = smoothRows(fullRowDepth)

        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val srcR = Rect()
        val dstR = Rect()

        for (y in 0 until height) {
            val shift = ((smooth[y] - 0.5f) * rowShiftStrength * width).toInt()
            srcR.set(0, y, width, y + 1)
            val left = -shift
            val right = width - shift
            dstR.set(left, y, right, y + 1)
            canvas.drawBitmap(src, srcR, dstR, paint)
        }

        applyColorGrade(out, colorDarkenFactor)
        return out
    }

    /**
     * Enhanced warp when Midas interpreter is available.
     * Executes model inference on a downscaled frame, extracts per-row means, upsamples and applies shift.
     */
    private fun enhancedWarpUsingMidas(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height

        // downscale preserving aspect ratio such that smallH ~= targetDownscaleHeight
        val downscale = max(1, height / targetDownscaleHeight)
        val smallW = max(32, width / downscale)
        val smallH = max(32, height / downscale)
        val small = Bitmap.createScaledBitmap(src, smallW, smallH, true)

        // try to run interpreter; if it fails, fallback to luminance heuristic
        val depthRows = try {
            estimateDepthForDownscaledBitmap(small)
        } catch (e: Exception) {
            Logger.e(TAG, "Midas inference failed: ${e.message}")
            estimateDepthForDownscaledBitmapFallback(small)
        }

        val fullRowDepth = upsampleRowDepth(depthRows, height)
        val smooth = smoothRows(fullRowDepth)

        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val srcR = Rect()
        val dstR = Rect()

        for (y in 0 until height) {
            val shift = ((smooth[y] - 0.5f) * rowShiftStrength * width).toInt()
            srcR.set(0, y, width, y + 1)
            val left = -shift
            val right = width - shift
            dstR.set(left, y, right, y + 1)
            canvas.drawBitmap(src, srcR, dstR, paint)
        }

        applyColorGrade(out, colorDarkenFactor)
        addSubtleVibrance(out)
        return out
    }

    /**
     * estimateDepthForDownscaledBitmap — uses loaded TFLite interpreter if available.
     * Returns per-row mean depth in [0,1] for small.height rows.
     */
    private fun estimateDepthForDownscaledBitmap(small: Bitmap): FloatArray {
        val interp = midasInterpreter ?: throw IllegalStateException("Interpreter null")
        // Preprocessing: resize to model expected size if we can deduce it or use small itself.
        // For many Midas small models input is flexible; we'll feed the small bitmap as [1,H,W,3] float32 normalized to [0,1].
        val h = small.height
        val w = small.width

        // Build input array
        val input = Array(1) { Array(h) { Array(w) { FloatArray(3) } } }
        val px = IntArray(w)
        for (y in 0 until h) {
            small.getPixels(px, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val c = px[x]
                val r = ((c shr 16) and 0xFF) / 255.0f
                val g = ((c shr 8) and 0xFF) / 255.0f
                val b = (c and 0xFF) / 255.0f
                input[0][y][x][0] = r
                input[0][y][x][1] = g
                input[0][y][x][2] = b
            }
        }

        // Output: attempt to capture the common output shape as float32 [1, h, w, 1] or [1, h, w]
        // Prepare a backing array big enough
        val outputMap = HashMap<Int, Any>()
        val outBuffer = Array(1) { Array(h) { FloatArray(w) } }
        try {
            outputMap[0] = outBuffer
            interp.runForMultipleInputsOutputs(arrayOf(input), outputMap)
        } catch (e: Exception) {
            // Some interpreters expect direct run(input, output)
            val flatOut = Array(h) { FloatArray(w) }
            try {
                interp.run(input, flatOut)
                for (y in 0 until h) {
                    outBuffer[0][y] = flatOut[y]
                }
            } catch (e2: Exception) {
                Logger.e(TAG, "Interpreter run failed: ${e2.message}")
                throw e2
            }
        }

        // outBuffer[0][y][x] is depth value (likely unnormalized). Normalize per-row mean to [0,1].
        val rowMeans = FloatArray(h)
        var minV = Float.MAX_VALUE
        var maxV = -Float.MAX_VALUE
        for (y in 0 until h) {
            var sum = 0f
            for (x in 0 until w) {
                val v = outBuffer[0][y][x]
                sum += v
                if (v < minV) minV = v
                if (v > maxV) maxV = v
            }
            rowMeans[y] = sum / w
        }
        // Normalize rowMeans
        val range = (maxV - minV).takeIf { it > 1e-6f } ?: 1f
        for (y in 0 until h) rowMeans[y] = (rowMeans[y] - minV) / range

        // Convert so that larger => farther (we prefer brighter => farther), but Midas semantics vary; invert if necessary.
        // We'll keep as-is (0..1) and allow app-level tuning.
        return rowMeans
    }

    /**
     * Fallback depth estimator (fast luminance heuristic) used if interpreter errors.
     */
    private fun estimateDepthForDownscaledBitmapFallback(small: Bitmap): FloatArray {
        val h = small.height
        val w = small.width
        val out = FloatArray(h)
        val px = IntArray(w)
        for (y in 0 until h) {
            small.getPixels(px, 0, w, 0, y, w, 1)
            var sum = 0L
            for (x in 0 until w) {
                val c = px[x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val lum = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                sum += lum
            }
            val avg = sum.toFloat() / (w * 255f)
            out[y] = 1f - avg
        }
        return out
    }

    /**
     * Upsample a small per-row depth to the full targetHeight using linear interpolation.
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
     * Temporal smoothing between frames.
     */
    private fun smoothRows(rows: FloatArray): FloatArray {
        val prev = previousRowDepth
        val out = FloatArray(rows.size)
        if (prev == null || prev.size != rows.size) {
            System.arraycopy(rows, 0, out, 0, rows.size)
        } else {
            val a = smoothingAlpha
            for (i in rows.indices) out[i] = prev[i] * (1 - a) + rows[i] * a
        }
        previousRowDepth = out
        return out
    }

    /**
     * Small color grade to make result darker / moodier.
     */
    private fun applyColorGrade(bmp: Bitmap, darken: Float) {
        val canvas = Canvas(bmp)
        val paint = Paint()
        val cm = ColorMatrix()
        cm.setScale(darken, darken * 0.96f, darken * 0.92f, 1f)
        val sat = 0.92f
        val satM = ColorMatrix()
        satM.setSaturation(sat)
        cm.postConcat(satM)
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(bmp, 0f, 0f, paint)
    }

    /**
     * Very subtle overlay to increase eerie feeling.
     */
    private fun addSubtleVibrance(bmp: Bitmap) {
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.alpha = 18
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

    private fun postBitmapToTextureView(bmp: Bitmap) {
        if (!textureView.isAvailable) return
        val canvas = textureView.lockCanvas()
        if (canvas == null) return
        try {
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
            val dstW = cvW
            val dstH = (cvW / bmpRatio).toInt()
            Rect(0, (cvH - dstH) / 2, dstW, (cvH + dstH) / 2)
        } else {
            val dstH = cvH
            val dstW = (cvH * bmpRatio).toInt()
            Rect((cvW - dstW) / 2, 0, (cvW + dstW) / 2, dstH)
        }
    }

    /**
     * Try to load a Midas model from internal files or assets. Returns Interpreter or null.
     * Looks for filesDir/models/midas_small.tflite or assets/midas_small.tflite.
     */
    private fun loadMidasInterpreterIfPresent(): Interpreter? {
        try {
            val candidate1 = File(filesDir, "models/midas_small.tflite")
            val modelBuffer = when {
                candidate1.exists() && candidate1.canRead() -> {
                    FileUtil.loadMappedFile(this, candidate1.absolutePath)
                }
                else -> {
                    // try assets
                    try {
                        FileUtil.loadMappedFile(this, "midas_small.tflite")
                    } catch (e: Exception) {
                        null
                    }
                }
            }
            if (modelBuffer != null) {
                val options = Interpreter.Options()
                // options.setNumThreads(2) // tune if desired
                val interpreter = Interpreter(modelBuffer, options)
                // If we wish, attempt to deduce input size by invoking getInputTensor(0) properties,
                // but keep simple: trust flexible shapes or small input; Midas small tends to accept variable sizes.
                return interpreter
            }
        } catch (e: Exception) {
            Logger.e(TAG, "loadMidasInterpreterIfPresent error: ${e.message}")
        }
        return null
    }

    /**
     * Save final warped bitmap into user's gallery (MediaStore).
     */
    private fun saveBitmapToGallery(bmp: Bitmap) {
        val filename = "twisted-${System.currentTimeMillis()}.jpg"
        try {
            val resolver = contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/TwistedPhone")
                }
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                // SAFE CHANGE: openOutputStream returns OutputStream? — use safe-call and handle failure
                val written = resolver.openOutputStream(uri)?.use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
                } != null

                if (written) {
                    Toast.makeText(this, "Saved to gallery", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Failed to save image", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Failed to save image", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Logger.e(TAG, "saveBitmapToGallery failed: ${e.message}")
            Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show()
        }
    }
}
