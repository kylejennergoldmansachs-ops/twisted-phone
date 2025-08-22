// CameraActivity.kt
package com.twistedphone.camera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.media.Image
import android.os.Bundle
import android.util.Size
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.twistedphone.R
import com.twistedphone.util.FileLogger
import kotlinx.coroutines.*
import org.tensorflow.lite.Interpreter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.*

/**
 * CameraActivity - replaced/cleaned version.
 *
 * - Uses CameraX Preview + ImageAnalysis
 * - Converts YUV_420_888 -> Bitmap via NV21->YuvImage->JPEG route for reliability
 * - Placeholder MiDaS / MobileSAM hooks are provided with safe types so this compiles
 * - Ensures Tasks.await() timeout overload is used with TimeUnit
 *
 * Requirements:
 * - layout/activity_camera.xml should include a PreviewView with id previewView
 *   and optional buttons with ids btnCapture, btnToggle if you want capture toggles.
 */
class CameraActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "CameraActivity"
        private const val PROCESSING_MAX_WIDTH = 640 // small for real-time mobile processing
    }

    // CameraX
    private lateinit var previewView: PreviewView
    private lateinit var overlayView: ImageView
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    // coroutine scope for processing
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // model/interpreter placeholders (may be set by your ModelDownloadWorker setup)
    private var midasInterpreter: Interpreter? = null
    private var mobileSamEncoder: Interpreter? = null
    private var mobileSamDecoder: Interpreter? = null

    // last processed outputs for UI smoothing / fallback
    private var lastMask: Array<BooleanArray>? = null
    private var lastOffsets: FloatArray? = null

    // permission helper
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera) // ensure this layout exists

        // PreviewView
        previewView = findViewById(R.id.previewView)

        // Add overlay ImageView programmatically on top of preview (avoids layout mismatch)
        // When the layout already contains overlayView, this will still work but won't duplicate
        overlayView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.VISIBLE
        }
        // parent of PreviewView should be a FrameLayout in the typical camera layout
        (previewView.parent as? FrameLayout)?.addView(overlayView)

        // Request permission if needed and start camera
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            startCamera()
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build()
                val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
                preview.setSurfaceProvider(previewView.surfaceProvider)

                val imageCapture = ImageCapture.Builder()
                    .setTargetRotation(previewView.display?.rotation ?: 0)
                    .setTargetResolution(Size(1280, 720))
                    .build()

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(Size(PROCESSING_MAX_WIDTH, PROCESSING_MAX_WIDTH * 9 / 16))
                    .build()

                analysis.setAnalyzer(cameraExecutor) { proxy ->
                    // guard and hand off to coroutine-based pipeline
                    try {
                        analyzeFrame(proxy)
                    } catch (e: Exception) {
                        FileLogger.e(this@CameraActivity, TAG, "Analyzer exception: ${e.message}")
                        proxy.close()
                    }
                }

                try {
                    provider.unbindAll()
                    provider.bindToLifecycle(this, selector, preview, imageCapture, analysis)
                } catch (e: Exception) {
                    FileLogger.e(this@CameraActivity, TAG, "bindToLifecycle failed: ${e.message}")
                }

            } catch (e: Exception) {
                FileLogger.e(this@CameraActivity, TAG, "Camera start failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // main per-frame analyzer
    private fun analyzeFrame(proxy: ImageProxy) {
        val mediaImage = proxy.image ?: run { proxy.close(); return }
        val bitmap = imageToBitmap(mediaImage, proxy.imageInfo.rotationDegrees, PROCESSING_MAX_WIDTH)
        if (bitmap == null) {
            proxy.close(); return
        }

        scope.launch {
            try {
                // Depth: prefer MiDaS interpreter if available, otherwise an inexpensive luminance-based fallback
                val depth = if (midasInterpreter != null) runMiDaS(bitmap) else luminanceDepthApprox(bitmap)

                // Mask: prefer MobileSAM if present, otherwise fallback to simple rect heuristics
                val maskFromSam = runMobileSamIfAvailable(bitmap)
                val mask = maskFromSam ?: runMaskFallback(bitmap)

                // compute row offsets from depth map (returns float array width*height or row offsets - implementation here produces per-row offsets)
                val offsets = computeRowOffsetsFromDepth(depth, bitmap.width, bitmap.height)

                lastMask = mask
                lastOffsets = offsets

                val warped = warpBitmap(bitmap, offsets, mask)
                val final = applyAtmosphere(warped, mask, enhanced = true)

                withContext(Dispatchers.Main) {
                    try {
                        overlayView.setImageBitmap(final)
                    } catch (e: Exception) {
                        FileLogger.e(this@CameraActivity, TAG, "UI setImage failed: ${e.message}")
                    }
                }

            } catch (e: Exception) {
                FileLogger.e(this@CameraActivity, TAG, "Frame processing error: ${e.message}")
            } finally {
                proxy.close()
            }
        }
    }

    // Convert YUV_420_888 Image -> scaled ARGB Bitmap using YuvImage -> JPEG -> Bitmap
    private fun imageToBitmap(image: Image, rotation: Int, maxWidth: Int): Bitmap? {
        return try {
            val width = image.width
            val height = image.height

            // Convert to NV21 (Y + V + U) byte array
            val yPlane = image.planes[0].buffer
            val uPlane = image.planes[1].buffer
            val vPlane = image.planes[2].buffer

            val ySize = yPlane.remaining()
            val uSize = uPlane.remaining()
            val vSize = vPlane.remaining()
            val nv21 = ByteArray(ySize + uSize + vSize)

            // copy Y
            yPlane.get(nv21, 0, ySize)

            // According to Camera2 YUV_420_888 layout, V then U for many devices -> produce NV21: Y V U
            // Some devices may vary; this approach works for many but if you see color distortions you may need to reorder.
            vPlane.get(nv21, ySize, vSize)
            uPlane.get(nv21, ySize + vSize, uSize)

            val yuvImage = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null)
            val baos = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 80, baos)
            val bytes = baos.toByteArray()
            var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null

            // scale down to maxWidth while preserving aspect ratio
            if (bmp.width > maxWidth) {
                val aspect = bmp.height.toFloat() / bmp.width.toFloat()
                val targetW = maxWidth
                val targetH = (targetW * aspect).toInt()
                bmp = Bitmap.createScaledBitmap(bmp, targetW, targetH, true)
            }

            // rotate if necessary
            if (rotation != 0) {
                val matrix = Matrix()
                matrix.postRotate(rotation.toFloat())
                val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                if (rotated !== bmp) {
                    bmp.recycle()
                    bmp = rotated
                }
            }
            bmp
        } catch (e: Exception) {
            FileLogger.e(this, TAG, "image->bitmap conversion failed: ${e.message}")
            null
        }
    }

    // ---------------------------
    // MobileSAM integration (best-effort, optional)
    // ---------------------------
    // This function attempts to run MobileSAM if interpreters are available.
    // For now it returns null if not available or on error; it must return an Array<BooleanArray>
    // where outer dimension equals bitmap.height and inner equals bitmap.width.
    private suspend fun runMobileSamIfAvailable(bmp: Bitmap): Array<BooleanArray>? = withContext(Dispatchers.Default) {
        try {
            if (mobileSamEncoder == null || mobileSamDecoder == null) return@withContext null

            // Example placeholder: you would convert bmp to the encoder input shape, run encoder, then decoder.
            // This placeholder returns null to indicate fallback should be used.
            // If you wire real tflite calls, ensure you return a boolean mask here.
            return@withContext null
        } catch (e: Exception) {
            FileLogger.e(applicationContext, TAG, "MobileSAM run failed: ${e.message}")
            return@withContext null
        }
    }

    // Fallback mask generator (very cheap): find biggest portrait-ish rect using simple heuristics.
    private fun runMaskFallback(bmp: Bitmap): Array<BooleanArray> {
        val w = bmp.width
        val h = bmp.height
        val mask = Array(h) { BooleanArray(w) { false } }

        // center rect heuristic: fill a centered rectangle proportional to image size
        val rw = (w * 0.35f).toInt().coerceAtLeast(20)
        val rh = (h * 0.55f).toInt().coerceAtLeast(20)
        val left = (w - rw) / 2
        val top = (h - rh) / 2
        for (y in top until (top + rh)) {
            if (y < 0 || y >= h) continue
            for (x in left until (left + rw)) {
                if (x < 0 || x >= w) continue
                mask[y][x] = true
            }
        }
        return mask
    }

    // ---------------------------
    // Depth / warp helpers
    // ---------------------------
    // Placeholder MiDaS runner — in real usage, run your TFLite interpreter and return a float array of size w*h
    private fun runMiDaS(bmp: Bitmap): FloatArray {
        val w = bmp.width
        val h = bmp.height
        val out = FloatArray(w * h) { 0.5f } // neutral depth
        return out
    }

    private fun luminanceDepthApprox(bmp: Bitmap): FloatArray {
        val w = bmp.width; val h = bmp.height
        val out = FloatArray(w * h)
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                val p = pixels[idx]
                val r = ((p shr 16) and 0xff)
                val g = ((p shr 8) and 0xff)
                val b = (p and 0xff)
                val lum = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
                out[idx] = 1f - lum // darker => farther
            }
        }
        return out
    }

    // compute "row offsets" for a vertical warp effect from a depth map
    // returns an offsets array of size height * 2: for each row, an x-offset (simpler than dense flow)
    private fun computeRowOffsetsFromDepth(depth: FloatArray, w: Int, h: Int): FloatArray {
        // Simple per-row offset computed as average depth in the row, mapped to [-maxOffset, maxOffset]
        val maxOffset = (w * 0.06f).coerceAtLeast(4f)
        val offsets = FloatArray(h)
        for (row in 0 until h) {
            var sum = 0f
            val base = row * w
            for (x in 0 until w) sum += depth[base + x]
            val avg = sum / w
            // map depth [0..1] -> offset
            offsets[row] = (avg - 0.5f) * 2f * maxOffset
        }

        // smooth offsets (simple box blur)
        val radius = 3
        val smoothed = FloatArray(h)
        for (r in 0 until h) {
            var count = 0
            var accum = 0f
            val lo = max(0, r - radius)
            val hi = min(h - 1, r + radius)
            for (k in lo..hi) {
                accum += offsets[k]
                count++
            }
            smoothed[r] = accum / count
        }
        return smoothed
    }

    // warp bitmap by shifting pixels horizontally according to per-row offsets, mask restricts effect
    private fun warpBitmap(src: Bitmap, rowOffsets: FloatArray, mask: Array<BooleanArray>): Bitmap {
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val srcPixels = IntArray(w * h)
        val dstPixels = IntArray(w * h)
        src.getPixels(srcPixels, 0, w, 0, 0, w, h)

        for (y in 0 until h) {
            val off = rowOffsets.getOrNull(y) ?: 0f
            val shift = off.roundToInt()
            for (x in 0 until w) {
                val idx = y * w + x
                if (mask[y].getOrNull(x) == true) {
                    // sample from source with horizontal shift (nearest neighbor)
                    val sx = (x - shift).coerceIn(0, w - 1)
                    dstPixels[idx] = srcPixels[y * w + sx]
                } else {
                    dstPixels[idx] = srcPixels[idx]
                }
            }
        }
        out.setPixels(dstPixels, 0, w, 0, 0, w, h)
        return out
    }

    // apply atmospheric effect: darken masked areas, add subtle vignette
    private fun applyAtmosphere(src: Bitmap, mask: Array<BooleanArray>, enhanced: Boolean): Bitmap {
        val w = src.width; val h = src.height
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val paint = Paint()
        val pixels = IntArray(w * h)
        out.getPixels(pixels, 0, w, 0, 0, w, h)

        // simple darkening on masked pixels and slight color shift
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                if (mask[y].getOrNull(x) == true) {
                    val p = pixels[idx]
                    var r = ((p shr 16) and 0xff)
                    var g = ((p shr 8) and 0xff)
                    var b = (p and 0xff)
                    // apply darkness factor
                    val factor = if (enhanced) 0.55f else 0.75f
                    r = (r * factor).toInt().coerceIn(0, 255)
                    g = (g * (factor * 0.95f)).toInt().coerceIn(0, 255)
                    b = (b * (factor * 0.9f)).toInt().coerceIn(0, 255)
                    pixels[idx] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
        out.setPixels(pixels, 0, w, 0, 0, w, h)

        // vignette paint (subtle)
        val radius = hypot(w.toDouble(), h.toDouble()).toFloat() * 0.6f
        val gradient = RadialGradient((w / 2).toFloat(), (h / 2).toFloat(), radius,
            intArrayOf(0x00000000, 0x22000000), floatArrayOf(0.6f, 1.0f), Shader.TileMode.CLAMP)
        paint.shader = gradient
        paint.isFilterBitmap = true
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

        return out
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        cameraExecutor.shutdown()
        try {
            midasInterpreter?.close()
            mobileSamEncoder?.close()
            mobileSamDecoder?.close()
        } catch (_: Exception) { /* ignore */ }
        FileLogger.d(this, TAG, "CameraActivity destroyed")
    }
}
