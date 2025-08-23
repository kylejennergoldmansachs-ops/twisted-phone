package com.twistedphone.camera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.twistedphone.R
import com.twistedphone.util.FileLogger
import kotlinx.coroutines.*
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.*

class CameraActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "CameraActivity"
        // throttle heavy model every N frames
        private const val MIDAS_FRAME_SKIP = 3
    }

    private lateinit var previewView: PreviewView
    private lateinit var btnCapture: Button
    private lateinit var btnToggle: Button
    private lateinit var overlay: ImageView

    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraSelectorLens = CameraSelector.DEFAULT_BACK_CAMERA
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService

    private var midasInterpreter: Interpreter? = null
    private var midasInputShape: IntArray? = null
    private var midasOutputShape: IntArray? = null
    private var midasInputType: DataType? = null
    private var midasOutputType: DataType? = null

    private val ioScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @Volatile private var latestWarped: Bitmap? = null

    // inference throttling & error suppression
    private var frameCounter = 0
    private var lastMidasErrorLogTime = 0L
    private val MIDAS_ERROR_LOG_SUPPRESS_MS = 3000L

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        previewView = findViewById(R.id.previewView)
        btnCapture = findViewById(R.id.btnCapture)
        btnToggle = findViewById(R.id.btnToggle)

        overlay = ImageView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.FIT_XY
        }
        (previewView.parent as? ViewGroup)?.addView(overlay)
        overlay.bringToFront()

        cameraExecutor = Executors.newSingleThreadExecutor()

        btnToggle.setOnClickListener {
            cameraSelectorLens = if (cameraSelectorLens == CameraSelector.DEFAULT_BACK_CAMERA) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            bindCameraUseCases()
        }

        btnCapture.setOnClickListener {
            ioScope.launch {
                latestWarped?.let { bmp ->
                    if (saveBitmapToGallery(bmp)) {
                        withContext(Dispatchers.Main) { Toast.makeText(this@CameraActivity, "Saved warped image", Toast.LENGTH_SHORT).show() }
                    } else {
                        withContext(Dispatchers.Main) { Toast.makeText(this@CameraActivity, "Save failed", Toast.LENGTH_SHORT).show() }
                    }
                } ?: run {
                    withContext(Dispatchers.Main) { Toast.makeText(this@CameraActivity, "Nothing to save yet", Toast.LENGTH_SHORT).show() }
                }
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            startCamera()
        }

        // Load model in background
        ioScope.launch { tryLoadMidasInterpreter() }

        FileLogger.d(this, TAG, "CameraActivity created")
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val cp = cameraProvider ?: return
        cp.unbindAll()

        val preview = Preview.Builder()
            .setTargetResolution(Size(1280, 720))
            .build().also { it.setSurfaceProvider(previewView.surfaceProvider) }

        imageCapture = ImageCapture.Builder().setTargetRotation(previewView.display.rotation).build()

        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(640, 480))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        imageAnalysis.setAnalyzer(cameraExecutor) { image ->
            try {
                val bmp = imageProxyToBitmap(image)
                if (bmp != null) {
                    ioScope.launch {
                        processFrameAndShow(bmp)
                    }
                }
            } catch (e: Exception) {
                FileLogger.e(this, TAG, "Analyzer error: ${e.message}")
            } finally {
                image.close()
            }
        }

        try {
            cp.bindToLifecycle(this, cameraSelectorLens, preview, imageCapture, imageAnalysis)
            FileLogger.d(this, TAG, "Camera bound: lens=$cameraSelectorLens")
        } catch (e: Exception) {
            FileLogger.e(this, TAG, "bindToLifecycle failed: ${e.message}")
        }
    }

    private suspend fun tryLoadMidasInterpreter() {
        withContext(Dispatchers.IO) {
            try {
                val modelsDir = File(filesDir, "models")
                val midasFile = File(modelsDir, "midas.tflite")
                if (!midasFile.exists()) {
                    FileLogger.d(this@CameraActivity, TAG, "midas.tflite not found in ${modelsDir.absolutePath}")
                    return@withContext
                }

                // Try with XNNPACK enabled first. If any native error happens we will recreate without it.
                val opts = Interpreter.Options().apply {
                    setNumThreads(2)
                    try {
                        // call setUseXNNPACK(true) if available (some TF versions)
                        val method = Interpreter.Options::class.java.getMethod("setUseXNNPACK", Boolean::class.javaPrimitiveType)
                        method.invoke(this, true)
                    } catch (_: Exception) {
                        // ignore if not available
                    }
                }

                try {
                    midasInterpreter = Interpreter(midasFile, opts)
                } catch (e: Throwable) {
                    FileLogger.e(this@CameraActivity, TAG, "MiDaS initial create with XNNPACK failed: ${e.message}. Trying fallback without XNNPACK.")
                    try {
                        val opts2 = Interpreter.Options().apply {
                            setNumThreads(2)
                            try {
                                val method = Interpreter.Options::class.java.getMethod("setUseXNNPACK", Boolean::class.javaPrimitiveType)
                                method.invoke(this, false)
                            } catch (_: Exception) { }
                        }
                        midasInterpreter = Interpreter(midasFile, opts2)
                    } catch (e2: Throwable) {
                        FileLogger.e(this@CameraActivity, TAG, "Failed to create MiDaS interpreter: ${e2.message}")
                        midasInterpreter = null
                    }
                }

                midasInterpreter?.let { interp ->
                    try {
                        midasInputShape = interp.getInputTensor(0).shape().copyOf()
                        midasInputType = interp.getInputTensor(0).dataType()
                        midasOutputShape = interp.getOutputTensor(0).shape().copyOf()
                        midasOutputType = interp.getOutputTensor(0).dataType()
                        FileLogger.d(this@CameraActivity, TAG, "Loaded midas.tflite; inShape=${midasInputShape?.contentToString()} inType=$midasInputType outShape=${midasOutputShape?.contentToString()} outType=$midasOutputType")
                    } catch (e: Exception) {
                        FileLogger.e(this@CameraActivity, TAG, "Loaded MiDaS but failed to read tensor info: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                FileLogger.e(this@CameraActivity, TAG, "Failed to load midas: ${e.message}")
            }
        }
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        try {
            val yBuffer = image.planes[0].buffer
            val uBuffer = image.planes[1].buffer
            val vBuffer = image.planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)
            yBuffer.get(nv21, 0, ySize)

            val rowStride = image.planes[1].rowStride
            val pixelStride = image.planes[1].pixelStride

            val uBytes = ByteArray(uSize)
            val vBytes = ByteArray(vSize)
            uBuffer.get(uBytes)
            vBuffer.get(vBytes)

            var pos = ySize
            val width = image.width
            val height = image.height
            val chromaHeight = height / 2
            val chromaWidth = width / 2

            for (row in 0 until chromaHeight) {
                var col = 0
                val base = row * rowStride
                while (col < chromaWidth) {
                    val vuPos = base + col * pixelStride
                    nv21[pos++] = if (vuPos < vBytes.size) vBytes[vuPos] else 0
                    nv21[pos++] = if (vuPos < uBytes.size) uBytes[vuPos] else 0
                    col++
                }
            }

            val yuvImage = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 85, out)
            val bytes = out.toByteArray()
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            FileLogger.e(this, TAG, "YUV->Bitmap failed: ${e.message}")
            return null
        }
    }

    private suspend fun processFrameAndShow(src: Bitmap) {
        // throttle model runs: run heavy model only every MIDAS_FRAME_SKIP frames
        frameCounter = (frameCounter + 1) % MIDAS_FRAME_SKIP

        // downscale for speed (target width)
        val targetW = 640
        val targetH = (targetW.toFloat() * src.height / src.width).roundToInt()
        val down = Bitmap.createScaledBitmap(src, targetW, targetH, true)

        // get depth (0..1 per pixel) as Array<FloatArray> [h][w]
        val depthNorm = runMidasOrFallback(down, runModel = (frameCounter == 0))

        // compute per-row offsets from depth
        val offsets = computeRowOffsetsFromDepth(depthNorm, strength = 36.0f)

        val warped = warpBitmapRowShift(down, offsets)

        val final = applyAtmosphere(warped, depthNorm)

        latestWarped = final

        withContext(Dispatchers.Main) {
            overlay.setImageBitmap(final)
        }
    }

    private fun computeRowOffsetsFromDepth(depth: Array<FloatArray>, strength: Float): FloatArray {
        val h = depth.size
        val w = depth[0].size
        val offsets = FloatArray(h)
        for (y in 0 until h) {
            var sum = 0f
            for (x in 0 until w) sum += depth[y][x]
            val avg = sum / w
            // push far pixels more; map avg(0..1) to offset centered at 0
            val off = (avg - 0.5f) * 2f * strength
            offsets[y] = off
        }
        return offsets
    }

    private fun warpBitmapRowShift(bmp: Bitmap, offsets: FloatArray): Bitmap {
        val w = bmp.width
        val h = bmp.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val srcPixels = IntArray(w * h)
        val outPixels = IntArray(w * h)
        bmp.getPixels(srcPixels, 0, w, 0, 0, w, h)

        for (y in 0 until h) {
            val offIndex = (y.toFloat() * offsets.size / h).toInt().coerceIn(0, offsets.size - 1)
            val offF = offsets[offIndex]
            val off = offF.roundToInt()
            val rowStart = y * w
            if (off > 0) {
                for (x in w - 1 downTo 0) {
                    val srcX = (x - off).coerceIn(0, w - 1)
                    outPixels[rowStart + x] = srcPixels[rowStart + srcX]
                }
            } else if (off < 0) {
                for (x in 0 until w) {
                    val srcX = (x - off).coerceIn(0, w - 1)
                    outPixels[rowStart + x] = srcPixels[rowStart + srcX]
                }
            } else {
                System.arraycopy(srcPixels, rowStart, outPixels, rowStart, w)
            }
        }
        out.setPixels(outPixels, 0, w, 0, 0, w, h)
        return out
    }

    private fun applyAtmosphere(src: Bitmap, depthNorm: Array<FloatArray>): Bitmap {
        val w = src.width
        val h = src.height
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)

        // exposure / tint color matrix -> darker & slightly blue
        val paint = Paint()
        val cm = ColorMatrix()
        cm.set(floatArrayOf(
            0.75f, 0f, 0f, 0f, 0f,
            0f, 0.82f, 0f, 0f, 0f,
            0f, 0f, 0.9f, 0f, 6f,
            0f, 0f, 0f, 1f, 0f
        ))
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(out, 0f, 0f, paint)

        // vignette
        val overlayPaint = Paint()
        val shader = RadialGradient(
            (w / 2).toFloat(), (h / 2).toFloat(),
            max(w, h).toFloat() * 0.8f,
            intArrayOf(0x00000000, 0x66000000.toInt()),
            floatArrayOf(0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        overlayPaint.shader = shader
        overlayPaint.isFilterBitmap = true
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), overlayPaint)

        // subtle depth fog (pale bluish)
        val fogBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val fogPixels = IntArray(w * h)
        for (y in 0 until h) {
            val rowIndex = (y.toFloat() * depthNorm.size / h).toInt().coerceIn(0, depthNorm.size - 1)
            val row = depthNorm[rowIndex]
            for (x in 0 until w) {
                val d = row[(x.toFloat() * row.size / w).toInt().coerceIn(0, row.size - 1)]
                val alpha = (d * 80).toInt().coerceIn(0, 100)
                // pale bluish fog: ARGB
                val fogColor = (alpha shl 24) or (0x008090A0)
                fogPixels[y * w + x] = fogColor
            }
        }
        fogBitmap.setPixels(fogPixels, 0, w, 0, 0, w, h)
        val fogPaint = Paint()
        fogPaint.isFilterBitmap = true
        fogPaint.alpha = 120
        canvas.drawBitmap(fogBitmap, 0f, 0f, fogPaint)

        return out
    }

    /**
     * Run MiDaS if available. On any failure return fallback depth based on luma.
     * Returns normalized depth array [h][w] (0..1).
     */
    private fun runMidasOrFallback(bmp: Bitmap, runModel: Boolean = true): Array<FloatArray> {
        val interp = midasInterpreter
        if (interp == null || !runModel) {
            return fallbackDepthFromLuma(bmp)
        }

        try {
            // Use cached shapes/types when present
            val inShape = midasInputShape ?: interp.getInputTensor(0).shape().copyOf()
            val outShape = midasOutputShape ?: interp.getOutputTensor(0).shape().copyOf()
            val inType = midasInputType ?: interp.getInputTensor(0).dataType()
            val outType = midasOutputType ?: interp.getOutputTensor(0).dataType()

            // Expectation: inShape = [1, H, W, C] (from your model dump earlier)
            val inH = if (inShape.size >= 3) inShape[inShape.size - 3] else bmp.height
            val inW = if (inShape.size >= 2) inShape[inShape.size - 2] else bmp.width
            val inC = if (inShape.size >= 4) inShape[inShape.size - 1] else 3

            // Resize input to expected
            val small = Bitmap.createScaledBitmap(bmp, inW, inH, true)
            val pixels = IntArray(inW * inH)
            small.getPixels(pixels, 0, inW, 0, 0, inW, inH)

            // Build flat input primitive array (no nested arrays) for interpreter.run
            val inputObj: Any = when (inType) {
                DataType.UINT8 -> {
                    val arr = ByteArray(inW * inH * inC)
                    var idx = 0
                    for (p in pixels) {
                        arr[idx++] = ((p shr 16) and 0xFF).toByte()
                        arr[idx++] = ((p shr 8) and 0xFF).toByte()
                        arr[idx++] = (p and 0xFF).toByte()
                    }
                    arr
                }
                DataType.FLOAT32 -> {
                    val arr = FloatArray(inW * inH * inC)
                    var idx = 0
                    for (p in pixels) {
                        arr[idx++] = ((p shr 16) and 0xFF) / 255.0f
                        arr[idx++] = ((p shr 8) and 0xFF) / 255.0f
                        arr[idx++] = (p and 0xFF) / 255.0f
                    }
                    arr
                }
                else -> {
                    // fallback to float array
                    val arr = FloatArray(inW * inH * inC)
                    var idx = 0
                    for (p in pixels) {
                        arr[idx++] = ((p shr 16) and 0xFF) / 255.0f
                        arr[idx++] = ((p shr 8) and 0xFF) / 255.0f
                        arr[idx++] = (p and 0xFF) / 255.0f
                    }
                    arr
                }
            }

            // Prepare output flat primitive array
            // outShape frequently like [1, H, W, 1]
            val outTotal = outShape.fold(1) { acc, i -> acc * i }
            val outputObj: Any = when (outType) {
                DataType.UINT8 -> ByteArray(outTotal)
                DataType.FLOAT32 -> FloatArray(outTotal)
                else -> FloatArray(outTotal)
            }

            // Run model; catch exceptions and attempt safe retry without XNNPACK
            try {
                interp.run(inputObj, outputObj)
            } catch (invokeEx: Throwable) {
                val now = System.currentTimeMillis()
                if (now - lastMidasErrorLogTime > MIDAS_ERROR_LOG_SUPPRESS_MS) {
                    FileLogger.e(this, TAG, "Midas run failed (invoke): ${invokeEx.message}")
                    lastMidasErrorLogTime = now
                }
                // Try a one-off recreate without XNNPACK
                try {
                    FileLogger.d(this, TAG, "Midas run: recreating interpreter without XNNPACK and retrying")
                    try { interp.close() } catch (_: Exception) {}
                    midasInterpreter = null
                    val mfile = File(filesDir, "models/midas.tflite")
                    val altOpts = Interpreter.Options().apply {
                        setNumThreads(1)
                        try {
                            val method = Interpreter.Options::class.java.getMethod("setUseXNNPACK", Boolean::class.javaPrimitiveType)
                            method.invoke(this, false)
                        } catch (_: Exception) {}
                    }
                    midasInterpreter = Interpreter(mfile, altOpts)
                    // refresh cached metadata
                    midasInputShape = midasInterpreter?.getInputTensor(0)?.shape()?.copyOf()
                    midasOutputShape = midasInterpreter?.getOutputTensor(0)?.shape()?.copyOf()
                    midasInputType = midasInterpreter?.getInputTensor(0)?.dataType()
                    midasOutputType = midasInterpreter?.getOutputTensor(0)?.dataType()

                    midasInterpreter?.run(inputObj, outputObj)
                } catch (retryEx: Throwable) {
                    FileLogger.e(this, TAG, "Midas retry failed: ${retryEx.message}")
                    return fallbackDepthFromLuma(bmp)
                }
            }

            // Convert flat output primitive array into 2D normalized float array [h][w]
            val (outH, outW) = if (outShape.size >= 3) {
                Pair(outShape[outShape.size - 3], outShape[outShape.size - 2])
            } else {
                Pair(inH, inW)
            }
            val outChannels = if (outShape.size >= 4) outShape[outShape.size - 1] else 1
            val flatFloat = FloatArray(outH * outW * outChannels)

            when (outputObj) {
                is ByteArray -> {
                    for (i in outputObj.indices) {
                        flatFloat[i] = (outputObj[i].toInt() and 0xFF) / 255.0f
                    }
                }
                is FloatArray -> {
                    for (i in outputObj.indices) flatFloat[i] = outputObj[i]
                }
                else -> {
                    // fallback
                    for (i in flatFloat.indices) flatFloat[i] = 0.5f
                }
            }

            // pick first channel and reshape to [outH][outW]
            val out2d = Array(outH) { FloatArray(outW) }
            for (y in 0 until outH) {
                for (x in 0 until outW) {
                    val base = (y * outW + x) * outChannels
                    out2d[y][x] = flatFloat.getOrElse(base) { 0.5f }
                }
            }

            // If model output size differs from requested (rare) resample by nearest
            if (outH == inH && outW == inW) {
                return out2d
            } else {
                val resized = Array(inH) { FloatArray(inW) }
                for (y in 0 until inH) {
                    val sy = (y.toFloat() * outH / inH).toInt().coerceIn(0, outH - 1)
                    for (x in 0 until inW) {
                        val sx = (x.toFloat() * outW / inW).toInt().coerceIn(0, outW - 1)
                        resized[y][x] = out2d[sy][sx]
                    }
                }
                return resized
            }
        } catch (e: Exception) {
            val now = System.currentTimeMillis()
            if (now - lastMidasErrorLogTime > MIDAS_ERROR_LOG_SUPPRESS_MS) {
                FileLogger.e(this, TAG, "runMidasOrFallback top-level error: ${e.message}")
                lastMidasErrorLogTime = now
            }
            return fallbackDepthFromLuma(bmp)
        }
    }

    // Simple fallback: use normalized luminance as depth (0..1)
    private fun fallbackDepthFromLuma(bmp: Bitmap): Array<FloatArray> {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = Array(h) { FloatArray(w) }
        for (y in 0 until h) {
            val rowStart = y * w
            for (x in 0 until w) {
                val p = pixels[rowStart + x]
                val r = ((p shr 16) and 0xFF)
                val g = ((p shr 8) and 0xFF)
                val b = (p and 0xFF)
                val l = (0.299f * r + 0.587f * g + 0.114f * b) / 255.0f
                // invert l so bright -> near (0), dark -> far (1) (adjust to taste)
                out[y][x] = 1.0f - l
            }
        }
        return out
    }

    private fun saveBitmapToGallery(bmp: Bitmap): Boolean {
        return try {
            val filename = "twisted_warp_${System.currentTimeMillis()}.jpg"
            val fos: OutputStream?
            val resolver = contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/TwistedPhone")
                }
            }
            val imageUri: Uri? = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            fos = imageUri?.let { resolver.openOutputStream(it) }
            fos?.use {
                bmp.compress(Bitmap.CompressFormat.JPEG, 92, it)
                it.flush()
            }
            true
        } catch (e: Exception) {
            FileLogger.e(this, TAG, "Save failed: ${e.message}")
            false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            midasInterpreter?.close()
        } catch (_: Exception) {}
        ioScope.cancel()
        try {
            cameraExecutor.shutdown()
        } catch (_: Exception) {}
        FileLogger.d(this, TAG, "CameraActivity destroyed")
    }
}
