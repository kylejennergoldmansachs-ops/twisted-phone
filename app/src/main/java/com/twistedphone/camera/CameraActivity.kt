package com.twistedphone.camera

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
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
import com.twistedphone.util.FileLogger
import com.twistedphone.util.Logger
import kotlinx.coroutines.*
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * CameraActivity replacement:
 * - Uses CameraX texture preview
 * - Applies local atmospheric/depth warp effects in real-time
 * - Save capture to MediaStore (so Gallery sees images)
 */
class CameraActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "CameraActivity"
        private const val REQ_CODE = 101
        private val REQUIRED = arrayOf(Manifest.permission.CAMERA)
    }

    private lateinit var textureView: TextureView
    private lateinit var captureBtn: Button
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val analysisExecutor = Executors.newFixedThreadPool(2)
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null

    // Optional midas interpreter if you shipped model; else null (we have a fallback)
    private var midasInterpreter: Interpreter? = null

    // last warped frame for capture
    @Volatile private var lastWarped: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // programmatic simple layout (TextureView + capture button)
        textureView = TextureView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            keepScreenOn = true
        }
        captureBtn = Button(this).apply {
            text = "Capture"
            val p = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            p.marginStart = 24
            p.topMargin = 24
            layoutParams = p
            setOnClickListener {
                val b = lastWarped
                if (b != null) {
                    saveBitmapToGallery(b)
                } else {
                    Toast.makeText(this@CameraActivity, "No frame ready", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val root = FrameLayout(this)
        root.addView(textureView)
        root.addView(captureBtn)
        setContentView(root)

        // Try to load candidate midas.tflite if present
        try {
            midasInterpreter = loadMidasInterpreterIfPresent()
            if (midasInterpreter != null) Logger.d(TAG, "Midas interpreter loaded")
        } catch (e: Exception) {
            Logger.e(TAG, "Midas load failed: ${e.message}")
            midasInterpreter = null
        }

        if (!allPermissions()) {
            ActivityCompat.requestPermissions(this, REQUIRED, REQ_CODE)
        } else {
            startCamera()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdownNow()
        analysisExecutor.shutdownNow()
        cameraProvider?.unbindAll()
        midasInterpreter?.close()
    }

    private fun allPermissions() = REQUIRED.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode == REQ_CODE) {
            if (allPermissions()) startCamera() else { Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show(); finish() }
        } else super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build()
            imageCapture = ImageCapture.Builder().build()

            val analyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analyzer.setAnalyzer(analysisExecutor) { imageProxy ->
                try {
                    val bmp = imageProxy.toBitmap() ?: run { imageProxy.close(); return@setAnalyzer }
                    // apply warp asynchronously and store lastWarped
                    val enhanced = TwistedApp.instance.settingsPrefs.getBoolean("enhanced_camera_mode", false)
                    val warped = applyAtmosphericWarp(bmp, enhanced)
                    lastWarped = warped
                    // display warped on textureView (small conversion)
                    runOnUiThread {
                        try {
                            textureView.bitmap?.let {
                                // simply draw on the TextureView canvas
                                val canvas = textureView.lockCanvas()
                                if (canvas != null) {
                                    canvas.drawBitmap(warped, null, android.graphics.Rect(0,0,canvas.width, canvas.height), null)
                                    textureView.unlockCanvasAndPost(canvas)
                                }
                            } ?: run {
                                val canvas = textureView.lockCanvas()
                                if (canvas != null) {
                                    canvas.drawBitmap(warped, null, android.graphics.Rect(0,0,canvas.width, canvas.height), null)
                                    textureView.unlockCanvasAndPost(canvas)
                                }
                            }
                        } catch (t: Throwable) {
                            // ignore draw errors
                        }
                    }
                } catch (e: Exception) {
                    Logger.e(TAG, "analyzer failed: ${e.message}")
                } finally {
                    imageProxy.close()
                }
            }

            try {
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(this, cameraSelector, preview, imageCapture, analyzer)
                preview.setSurfaceProvider { request ->
                    val surface = android.view.Surface(textureView.surfaceTexture)
                    request.provideSurface(surface, cameraExecutor) { /*release*/ }
                }
            } catch (e: Exception) {
                Logger.e(TAG, "camera bind failed: ${e.message}")
                Toast.makeText(this, "Camera start failed", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Apply a believable "other world" warp.
     * - Uses Midas if available for a depth-like map (expensive); else uses fast luminance heuristic.
     * - Enhanced flag strongly increases row-shift strength and color darkening.
     *
     * This intentionally avoids cheap overlays (no vignette) and instead shifts rows & color to feel uncanny.
     */
    private fun applyAtmosphericWarp(src: Bitmap, enhanced: Boolean): Bitmap {
        // parameters
        val baseRowShift = 0.06f
        val baseDarken = 0.82f
        val enhancedMul = if (enhanced) 2.8f else 1.0f
        val rowShiftStrength = baseRowShift * enhancedMul
        val darkenFactor = (baseDarken).toDouble().toFloat().let { if (enhanced) it * 0.88f else it }

        try {
            val w = src.width
            val h = src.height
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            // Create a depth-like row offsets array. Prefer Midas if available.
            val rowOffsets = FloatArray(h)
            if (midasInterpreter != null) {
                // fallback: compute a gentle slope shape if model fails
                try {
                    val small = Bitmap.createScaledBitmap(src, max(64, w/8), max(64, h/8), true)
                    val input = convertBitmapToFloatBuffer(small)
                    // model invocation - this code expects flexible shape model; if it fails, fallback
                    // (do defensive try/catch)
                    // WARNING: exact tensor shapes depend on model — this is a best-effort attempt; if it fails we fallback.
                    val outArr = Array(1) { Array(small.height) { FloatArray(small.width) } }
                    midasInterpreter?.run(input, outArr)
                    // generate per-row average depth
                    for (y in 0 until small.height) {
                        var s = 0f
                        for (x in 0 until small.width) s += outArr[0][y][x]
                        val avg = s / small.width
                        val scaled = ((avg - 0.5f) * 2.0f) // center
                        val row = (y.toFloat() / small.height.toFloat() * h).toInt().coerceIn(0, h-1)
                        rowOffsets[row] = scaled * rowShiftStrength * w
                    }
                } catch (e: Exception) {
                    Logger.e(TAG, "Midas inference fallback: ${e.message}")
                    for (y in 0 until h) {
                        val norm = (y.toFloat() / h.toFloat())
                        val offset = (Math.sin(norm * Math.PI * 2.0) * 0.5 + 0.5).toFloat()
                        rowOffsets[y] = (offset - 0.5f) * rowShiftStrength * w
                    }
                }
            } else {
                for (y in 0 until h) {
                    val norm = y.toFloat() / h.toFloat()
                    val offset = (Math.sin(norm * Math.PI * 2.0) * 0.45 + 0.5).toFloat()
                    rowOffsets[y] = (offset - 0.5f) * rowShiftStrength * w
                }
            }

            // Apply per-row horizontal warp: copy rows shifted by rowOffsets
            val srcPixels = IntArray(w)
            for (y in 0 until h) {
                src.getPixels(srcPixels, 0, w, 0, y, w, 1)
                val offset = rowOffsets[y].toInt()
                val destPixels = IntArray(w)
                if (offset >= 0) {
                    // shift right
                    System.arraycopy(srcPixels, 0, destPixels, offset, w - offset)
                    // fill left with repeated edge pixels to avoid holes
                    for (i in 0 until offset) destPixels[i] = srcPixels[0]
                } else {
                    val off = -offset
                    System.arraycopy(srcPixels, off, destPixels, 0, w - off)
                    for (i in w - off until w) destPixels[i] = srcPixels[w-1]
                }
                out.setPixels(destPixels, 0, w, 0, y, w, 1)
            }

            // Apply color darken + subtle contrast shift to make it uncanny
            val cm = ColorMatrix()
            val contrast = if (enhanced) 1.05f else 1.0f
            cm.set(floatArrayOf(
                contrast, 0f, 0f, 0f, -15f,
                0f, contrast, 0f, 0f, -15f,
                0f, 0f, contrast, 0f, -15f,
                0f, 0f, 0f, 1f, 0f
            ))
            val dm = ColorMatrix()
            dm.setScale(darkenFactor, darkenFactor, darkenFactor, 1f)
            cm.postConcat(dm)
            paint.colorFilter = ColorMatrixColorFilter(cm)
            canvas.drawBitmap(out, 0f, 0f, paint)

            return out
        } catch (e: Exception) {
            Logger.e(TAG, "applyAtmosphericWarp failed: ${e.message}")
            return src
        }
    }

    /**
     * Simple helper to save a bitmap to MediaStore (Pictures/TwistedPhone) so Gallery can see it.
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
                resolver.openOutputStream(uri)?.use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
                Toast.makeText(this, "Saved to gallery", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Failed to save image", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Logger.e(TAG, "saveBitmapToGallery failed: ${e.message}")
            Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show()
        }
    }

    // small helpers --------------------------------------------------------

    private fun convertBitmapToFloatBuffer(bmp: Bitmap): Array<FloatArray> {
        // very small wrapper; calling code expects model input; keep defensive
        val h = bmp.height
        val w = bmp.width
        val arr = Array(1) { FloatArray(w * h * 3) }
        val tmp = IntArray(w * h)
        bmp.getPixels(tmp, 0, w, 0, 0, w, h)
        var idx = 0
        for (p in tmp) {
            val r = ((p shr 16) and 0xFF) / 255.0f
            val g = ((p shr 8) and 0xFF) / 255.0f
            val b = (p and 0xFF) / 255.0f
            arr[0][idx++] = r
            arr[0][idx++] = g
            arr[0][idx++] = b
        }
        return arr
    }

    private fun loadMidasInterpreterIfPresent(): Interpreter? {
        return try {
            val candidate = File(filesDir, "models/midas.tflite")
            val path = if (candidate.exists() && candidate.canRead()) candidate.absolutePath else null
            val fd = when {
                path != null -> FileInputStream(path).channel.map(FileChannel.MapMode.READ_ONLY, 0, File(path).length())
                else -> null
            }
            if (fd != null) {
                Interpreter(fd)
            } else null
        } catch (e: Exception) {
            Logger.e(TAG, "loadMidasInterpreterIfPresent error: ${e.message}")
            null
        }
    }

    // extension: convert ImageProxy to Bitmap (simple)
    private fun ImageProxy.toBitmap(): Bitmap? {
        return try {
            val yBuffer = planes[0].buffer
            val uBuffer = planes[1].buffer
            val vBuffer = planes[2].buffer
            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()
            val nv21 = ByteArray(ySize + uSize + vSize)
            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)
            val yuvImage = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null)
            val out = java.io.ByteArrayOutputStream()
            yuvImage.compressToJpeg(android.graphics.Rect(0, 0, width, height), 70, out)
            val bytes = out.toByteArray()
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            Logger.e(TAG, "imageProxy->bitmap failed: ${e.message}")
            null
        }
    }
}
