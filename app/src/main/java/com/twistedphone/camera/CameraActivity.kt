package com.twistedphone.camera

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.media.Image
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.twistedphone.R
import com.twistedphone.tflite.TFLiteInspector
import com.twistedphone.util.FileLogger
import kotlinx.coroutines.*
import org.tensorflow.lite.Interpreter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import kotlin.math.*

/**
 * CameraActivity with an added "Dump Models" button that runs TFLiteInspector.dumpModelsInAppModelsDir(context)
 * and shows the resulting text file contents in a dialog with "Copy" button so you can paste here.
 *
 * Replace your existing CameraActivity.kt with this file.
 */
class CameraActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "CameraActivity"
        private const val PROCESSING_MAX_WIDTH = 640
    }

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: ImageView
    private lateinit var btnCapture: Button
    private lateinit var btnFlip: Button

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    // optional interpreters (left null unless you wire them)
    private var midasInterpreter: Interpreter? = null
    private var mobileSamEncoder: Interpreter? = null
    private var mobileSamDecoder: Interpreter? = null

    private var enhancedCamera = true

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        previewView = findViewById(R.id.previewView)
        btnCapture = findViewById(R.id.btnCapture)
        btnFlip = findViewById(R.id.btnFlip)

        // overlay view on top
        overlayView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.VISIBLE
        }
        (previewView.parent as? FrameLayout)?.addView(overlayView)

        btnCapture.setOnClickListener { takePicture() }
        btnFlip.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            bindCameraUseCases()
        }

        // Add Dump Models button programmatically (top-right)
        val dumpButton = Button(this).apply {
            text = "DUMP MODELS"
            textSize = 12f
            val sizePx = (44 * resources.displayMetrics.density).toInt()
            val params = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            params.gravity = Gravity.TOP or Gravity.END
            params.topMargin = 8
            params.rightMargin = 8
            layoutParams = params
            setOnClickListener { onDumpModelsClicked() }
        }
        (previewView.parent as? FrameLayout)?.addView(dumpButton)

        // permission & start
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermission.launch(Manifest.permission.CAMERA)
        } else {
            startCamera()
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            val prefs = getSharedPreferences("twisted_prefs", MODE_PRIVATE)
            enhancedCamera = prefs.getBoolean("enhanced_camera", true)
            FileLogger.d(this, TAG, "onResume: enhancedCamera=$enhancedCamera")
        } catch (e: Exception) {
            FileLogger.e(this, TAG, "pref read failed: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        cameraExecutor.shutdown()
        try {
            midasInterpreter?.close()
            mobileSamEncoder?.close()
            mobileSamDecoder?.close()
        } catch (_: Exception) {}
        FileLogger.d(this, TAG, "CameraActivity destroyed")
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        try {
            provider.unbindAll()

            val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

            val preview = Preview.Builder()
                .setTargetRotation(previewView.display?.rotation ?: 0)
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            imageCapture = ImageCapture.Builder()
                .setTargetRotation(previewView.display?.rotation ?: 0)
                .setTargetResolution(Size(2048, 1536))
                .build()

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .setTargetResolution(Size(PROCESSING_MAX_WIDTH, (PROCESSING_MAX_WIDTH * 16 / 9)))
                .build()

            analysis.setAnalyzer(cameraExecutor) { proxy ->
                try {
                    analyzeFrame(proxy)
                } catch (e: Exception) {
                    FileLogger.e(this, TAG, "analyzer threw: ${e.message}")
                    proxy.close()
                }
            }

            provider.bindToLifecycle(this, cameraSelector, preview, imageCapture, analysis)
        } catch (e: Exception) {
            FileLogger.e(this, TAG, "bindToLifecycle error: ${e.message}")
        }
    }

    private fun analyzeFrame(proxy: ImageProxy) {
        val mediaImage = proxy.image ?: run { proxy.close(); return }
        val bmp = try { imageProxyToBitmap(proxy) } catch (e: Exception) { null }
        if (bmp == null) { proxy.close(); return }

        scope.launch(Dispatchers.Default) {
            try {
                // quick fallback mask
                val mask = runMaskFallback(bmp)
                val offsets = computeRowOffsets(mask)
                val warped = warpBitmapFast(bmp, offsets, mask)
                withContext(Dispatchers.Main) { overlayView.setImageBitmap(warped) }
            } catch (e: Exception) {
                FileLogger.e(this@CameraActivity, TAG, "processing failed: ${e.message}")
            } finally {
                proxy.close()
            }
        }
    }

    // Robust YUV_420_888 -> ARGB_8888 conversion (as earlier)
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        val image = imageProxy.image ?: return null
        if (image.format != ImageFormat.YUV_420_888) {
            // fallback not supported here
        }

        val width = image.width
        val height = image.height

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride

        val nv21 = ByteArray(width * height * 3 / 2)
        var pos = 0

        val row = ByteArray(yRowStride)
        for (r in 0 until height) {
            yBuffer.position(r * yRowStride)
            yBuffer.get(row, 0, yRowStride)
            System.arraycopy(row, 0, nv21, pos, width)
            pos += width
        }

        val chromaHeight = height / 2
        val chromaWidth = width / 2
        val vRow = ByteArray(vRowStride)
        val uRow = ByteArray(uRowStride)
        var chromaPos = width * height

        for (r in 0 until chromaHeight) {
            vBuffer.position(r * vRowStride)
            vBuffer.get(vRow, 0, vRowStride)
            uBuffer.position(r * uRowStride)
            uBuffer.get(uRow, 0, uRowStride)
            for (c in 0 until chromaWidth) {
                val vIndex = c * vPixelStride
                val uIndex = c * uPixelStride
                val vb = vRow.getOrNull(vIndex) ?: 0
                val ub = uRow.getOrNull(uIndex) ?: 0
                nv21[chromaPos++] = vb
                nv21[chromaPos++] = ub
            }
        }

        return try {
            val yuv = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null)
            val baos = ByteArrayOutputStream()
            yuv.compressToJpeg(Rect(0, 0, width, height), 85, baos)
            val bytes = baos.toByteArray()
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (bmp != null && bmp.width > PROCESSING_MAX_WIDTH) {
                val aspect = bmp.height.toFloat() / bmp.width.toFloat()
                val tw = PROCESSING_MAX_WIDTH
                val th = max(1, (tw * aspect).toInt())
                val scaled = Bitmap.createScaledBitmap(bmp, tw, th, true)
                bmp.recycle()
                bmp = scaled
            }
            bmp
        } catch (e: Exception) {
            FileLogger.e(this, TAG, "NV21->bitmap decode failed: ${e.message}")
            null
        }
    }

    private fun runMaskFallback(bmp: Bitmap): Array<BooleanArray> {
        val w = bmp.width; val h = bmp.height
        val mask = Array(h) { BooleanArray(w) { false } }
        val rw = (w * 0.35f).toInt().coerceAtLeast(24)
        val rh = (h * 0.5f).toInt().coerceAtLeast(24)
        val left = (w - rw) / 2
        val top = (h - rh) / 2
        for (y in top until (top + rh)) {
            if (y < 0 || y >= h) continue
            for (x in left until (left + rw)) if (x in 0 until w) mask[y][x] = true
        }
        return mask
    }

    private fun computeRowOffsets(mask: Array<BooleanArray>): FloatArray {
        val h = mask.size
        val w = if (h > 0) mask[0].size else 0
        val offsets = FloatArray(h)
        if (h == 0 || w == 0) return offsets
        for (y in 0 until h) {
            var c = 0
            for (x in 0 until w) if (mask[y][x]) c++
            val frac = c.toFloat() / w.toFloat()
            offsets[y] = frac * frac * (w * 0.12f)
        }
        // smoothing
        val rad = 6
        val sm = FloatArray(h)
        for (y in 0 until h) {
            var s = 0f; var n = 0f
            for (k in -rad..rad) {
                val yy = (y + k).coerceIn(0, h - 1)
                val wgh = 1.0f / (1 + kotlin.math.abs(k))
                s += offsets[yy] * wgh
                n += wgh
            }
            sm[y] = s / n
        }
        return sm
    }

    private fun warpBitmapFast(src: Bitmap, offsets: FloatArray, mask: Array<BooleanArray>): Bitmap {
        val w = src.width; val h = src.height
        val srcPixels = IntArray(w * h); src.getPixels(srcPixels, 0, w, 0, 0, w, h)
        val dstPixels = IntArray(w * h)
        for (y in 0 until h) {
            val shift = offsets.getOrNull(y)?.roundToInt() ?: 0
            val rowBase = y * w
            for (x in 0 until w) {
                val idx = rowBase + x
                dstPixels[idx] = if (mask[y].getOrNull(x) == true) {
                    val sx = (x - shift).coerceIn(0, w - 1)
                    srcPixels[rowBase + sx]
                } else srcPixels[idx]
            }
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(dstPixels, 0, w, 0, 0, w, h)
        return out
    }

    private fun takePicture() {
        val ic = imageCapture ?: run { Toast.makeText(this, "Capture not ready", Toast.LENGTH_SHORT).show(); return }
        val name = "twisted_${System.currentTimeMillis()}"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TwistedPhone")
        }
        val outOptions = ImageCapture.OutputFileOptions.Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues).build()
        ic.takePicture(outOptions, ContextCompat.getMainExecutor(this), object: ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                val uri = outputFileResults.savedUri
                FileLogger.d(this@CameraActivity, TAG, "Saved photo: $uri")
                Toast.makeText(this@CameraActivity, "Saved: $uri", Toast.LENGTH_SHORT).show()
            }
            override fun onError(exception: ImageCaptureException) {
                FileLogger.e(this@CameraActivity, TAG, "Capture failed: ${exception.message}")
                Toast.makeText(this@CameraActivity, "Capture failed: ${exception.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    /** Called when user taps "DUMP MODELS" */
    private fun onDumpModelsClicked() {
        scope.launch {
            try {
                // Ensure the inspector exists in your project and is working
                withContext(Dispatchers.IO) {
                    FileLogger.d(this@CameraActivity, TAG, "Starting TFLiteInspector.dumpModelsInAppModelsDir")
                    // This will create files/tflite_signatures.txt if models exist
                    TFLiteInspector.dumpModelsInAppModelsDir(this@CameraActivity)
                    FileLogger.d(this@CameraActivity, TAG, "TFLiteInspector finished")
                }

                // Read the written file and display it
                val fileName = "tflite_signatures.txt"
                val file = File(filesDir, fileName)
                if (!file.exists()) {
                    // show message if not present
                    showTextDialog("No dump file", "Inspector did not produce $fileName. Check ModelDownloadWorker or ensure models are in files/models/")
                    FileLogger.d(this@CameraActivity, TAG, "Dump file not found: ${file.absolutePath}")
                    return@launch
                }

                val content = withContext(Dispatchers.IO) {
                    file.readText(Charsets.UTF_8)
                }

                // Log and show dialog
                FileLogger.d(this@CameraActivity, TAG, "Model dump:\n$content")
                showTextDialog("TFLite Signatures Dump", content, allowCopy = true)

            } catch (e: Exception) {
                FileLogger.e(this@CameraActivity, TAG, "onDumpModelsClicked failed: ${e.message}")
                showTextDialog("Dump Error", "Failed to run inspector: ${e.message}")
            }
        }
    }

    /** Show a scrollable dialog with the given text. If allowCopy true show Copy button to copy full text. */
    private fun showTextDialog(title: String, text: String, allowCopy: Boolean = false) {
        runOnUiThread {
            try {
                val tv = TextView(this).apply {
                    setTextIsSelectable(true)
                    setText(text)
                    setPadding(20, 20, 20, 20)
                }
                val scroll = ScrollView(this).apply {
                    addView(tv)
                    val params = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.7).toInt())
                    layoutParams = params
                }
                val b = AlertDialog.Builder(this).setTitle(title).setView(scroll).setCancelable(true)
                if (allowCopy) {
                    b.setPositiveButton("COPY") { _, _ ->
                        try {
                            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("tflite_dump", text)
                            cm.setPrimaryClip(clip)
                            Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(this, "Copy failed: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                    b.setNegativeButton("CLOSE", null)
                } else {
                    b.setPositiveButton("OK", null)
                }
                b.show()
            } catch (e: Exception) {
                Toast.makeText(this, "Dialog failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
