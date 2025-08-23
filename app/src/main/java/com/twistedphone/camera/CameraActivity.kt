package com.twistedphone.camera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.*
import android.net.Uri
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
        // fallback processing size for speed when model not available
        private const val FALLBACK_PROC_W = 256
        private const val FALLBACK_PROC_H = 256
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
                    val ok = saveBitmapToGallery(bmp)
                    withContext(Dispatchers.Main) {
                        if (ok) Toast.makeText(this@CameraActivity, "Saved warped image", Toast.LENGTH_SHORT).show()
                        else Toast.makeText(this@CameraActivity, "Save failed", Toast.LENGTH_SHORT).show()
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

        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
            try {
                val bmp = imageProxyToBitmap(imageProxy)
                if (bmp != null) {
                    ioScope.launch { processFrameAndShow(bmp) }
                }
            } catch (e: Exception) {
                FileLogger.e(this, TAG, "Analyzer error: ${e.message}")
            } finally {
                imageProxy.close()
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

                // safer interpreter options (avoid aggressive delegates)
                val opts = Interpreter.Options().apply {
                    setNumThreads(2)
                    // try to disable XNNPACK if API available
                    try {
                        val method = Interpreter.Options::class.java.getMethod("setUseXNNPACK", Boolean::class.javaPrimitiveType)
                        method.invoke(this, false)
                    } catch (_: Exception) {
                    }
                }

                midasInterpreter = Interpreter(midasFile, opts)

                midasInterpreter?.let { interp ->
                    try {
                        midasInputShape = interp.getInputTensor(0).shape().copyOf()
                        midasInputType = interp.getInputTensor(0).dataType()
                        midasOutputShape = interp.getOutputTensor(0).shape().copyOf()
                        midasOutputType = interp.getOutputTensor(0).dataType()
                        FileLogger.d(this@CameraActivity, TAG, "Loaded midas.tflite; inShape=${midasInputShape?.contentToString()} inType=$midasInputType outShape=${midasOutputShape?.contentToString()} outType=$midasOutputType")
                    } catch (e: Exception) {
                        FileLogger.e(this@CameraActivity, TAG, "Loaded midas but failed to read tensor info: ${e.message}")
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
                val base = row * rowStride
                var col = 0
                while (col < chromaWidth) {
                    val vuPos = base + col * pixelStride
                    val vByte = if (vuPos < vBytes.size) vBytes[vuPos] else 0
                    val uByte = if (vuPos < uBytes.size) uBytes[vuPos] else 0
                    if (pos < nv21.size) nv21[pos++] = vByte
                    if (pos < nv21.size) nv21[pos++] = uByte
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
        // throttle model runs: run heavy model only every 3rd frame
        frameCounter = (frameCounter + 1) % 3

        // downscale for speed (keep consistent small resolution)
        val targetW = 640
        val targetH = (targetW.toFloat() * src.height / src.width).roundToInt()
        val down = Bitmap.createScaledBitmap(src, targetW, targetH, true)

        // get depth (0..1)
        val depthNorm = runMidasOrFallback(down, runModel = (frameCounter == 0))

        // compute offsets (row-average)
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
            // push far pixels more (avg in 0..1)
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
            // pick offset by mapping y across offsets rows
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
                // copy fast
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

        // subtle depth fog
        val fogBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val fogPixels = IntArray(w * h)
        for (y in 0 until h) {
            val rowIndex = (y.toFloat() * depthNorm.size / h).toInt().coerceIn(0, depthNorm.size - 1)
            val row = depthNorm[rowIndex]
            for (x in 0 until w) {
                val d = row[(x.toFloat() * row.size / w).toInt().coerceIn(0, row.size - 1)]
                val alpha = (d * 80).toInt().coerceIn(0, 100)
                // pale bluish fog color - compose ARGB (alpha in top byte)
                val fogColor = (alpha shl 24) or (0x008090A0)
                fogPixels[y * w + x] = fogColor
            }
        }
        fogBitmap.setPixels(fogPixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(fogBitmap, 0f, 0f, null)

        return out
    }

    /**
     * Run midas if available and if runModel==true (we throttle). If anything fails returns fallback depth.
     * Returns normalized depth array [h][w] in 0..1.
     */
    private fun runMidasOrFallback(bmp: Bitmap, runModel: Boolean = true): Array<FloatArray> {
        val interp = midasInterpreter
        if (interp == null || !runModel) {
            return fallbackDepthFromLuma(bmp)
        }

        try {
            // read shapes and types (should already be cached)
            val inShape = midasInputShape ?: interp.getInputTensor(0).shape().copyOf()
            val outShape = midasOutputShape ?: interp.getOutputTensor(0).shape().copyOf()
            val inType = midasInputType ?: interp.getInputTensor(0).dataType()
            val outType = midasOutputType ?: interp.getOutputTensor(0).dataType()

            // expected: inShape = [1, H, W, C]
            val inH = inShape[1]
            val inW = inShape[2]
            val inC = if (inShape.size >= 4) inShape[3] else 3

            // build input buffer
            val inputByteSize = when (inType) {
                DataType.UINT8 -> inH * inW * inC
                DataType.FLOAT32 -> inH * inW * inC * 4
                else -> inH * inW * inC
            }
            val inputBuf = ByteBuffer.allocateDirect(inputByteSize).order(ByteOrder.nativeOrder())

            // resize bmp to model expected size
            val small = Bitmap.createScaledBitmap(bmp, inW, inH, true)
            val pixels = IntArray(inW * inH)
            small.getPixels(pixels, 0, inW, 0, 0, inW, inH)

            if (inType == DataType.UINT8) {
                for (p in pixels) {
                    inputBuf.put(((p shr 16) and 0xFF).toByte())
                    inputBuf.put(((p shr 8) and 0xFF).toByte())
                    inputBuf.put((p and 0xFF).toByte())
                }
            } else {
                val fb = inputBuf.asFloatBuffer()
                for (p in pixels) {
                    val r = ((p shr 16) and 0xFF) / 255.0f
                    val g = ((p shr 8) and 0xFF) / 255.0f
                    val b = (p and 0xFF) / 255.0f
                    fb.put(r); fb.put(g); fb.put(b)
                }
            }
            inputBuf.rewind()

            // prepare output buffer based on output shape
            val outDims = outShape
            val outTotal = outDims.fold(1) { acc, i -> acc * i }
            val outputByteSize = when (outType) {
                DataType.UINT8 -> outTotal
                DataType.FLOAT32 -> outTotal * 4
                else -> outTotal
            }
            val outputBuf = ByteBuffer.allocateDirect(outputByteSize).order(ByteOrder.nativeOrder())

            // invoke
            try {
                outputBuf.rewind()
                interp.run(inputBuf, outputBuf)
            } catch (invokeEx: Exception) {
                val now = System.currentTimeMillis()
                if (now - lastMidasErrorLogTime > MIDAS_ERROR_LOG_SUPPRESS_MS) {
                    FileLogger.e(this, TAG, "Midas run failed (invoke): ${invokeEx.message}")
                    lastMidasErrorLogTime = now
                }
                // fallback to simple luminance
                return fallbackDepthFromLuma(bmp)
            }

            // read output buffer and normalize to 0..1 array
            outputBuf.rewind()
            val outH = outDims[1]
            val outW = outDims[2]
            val outArr = Array(outH) { FloatArray(outW) }

            if (outType == DataType.FLOAT32) {
                val fb = outputBuf.asFloatBuffer()
                val tmp = FloatArray(outH * outW)
                fb.get(tmp)
                var idx = 0
                var min = Float.MAX_VALUE
                var max = -Float.MAX_VALUE
                for (y in 0 until outH) {
                    for (x in 0 until outW) {
                        val v = tmp[idx++]
                        if (v < min) min = v
                        if (v > max) max = v
                        outArr[y][x] = v
                    }
                }
                val rng = (max - min).coerceAtLeast(1e-6f)
                for (y in 0 until outH) for (x in 0 until outW) outArr[y][x] = (outArr[y][x] - min) / rng
            } else if (outType == DataType.UINT8) {
                val bytes = ByteArray(outH * outW)
                outputBuf.get(bytes)
                var idx = 0
                var min = 255
                var max = 0
                for (y in 0 until outH) {
                    for (x in 0 until outW) {
                        val v = bytes[idx++].toInt() and 0xFF
                        if (v < min) min = v
                        if (v > max) max = v
                        outArr[y][x] = v.toFloat()
                    }
                }
                val rng = (max - min).coerceAtLeast(1)
                for (y in 0 until outH) for (x in 0 until outW) outArr[y][x] = (outArr[y][x] - min) / rng.toFloat()
            } else {
                // unknown type, fallback
                return fallbackDepthFromLuma(bmp)
            }

            // if model output differs from requested (resample to bmp h/w)
            if (outH == bmp.height && outW == bmp.width) {
                return outArr
            } else {
                // nearest-resample from outArr to target size (bmp.width,bmp.height)
                val resized = Array(bmp.height) { FloatArray(bmp.width) }
                for (y in 0 until bmp.height) {
                    val sy = (y.toFloat() * outH / bmp.height).toInt().coerceIn(0, outH - 1)
                    for (x in 0 until bmp.width) {
                        val sx = (x.toFloat() * outW / bmp.width).toInt().coerceIn(0, outW - 1)
                        resized[y][x] = outArr[sy][sx]
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

    // Simple fallback: use normalized luminance as depth
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
                // invert l so bright -> near (0), dark -> far (1)
                out[y][x] = 1.0f - l
            }
        }
        return out
    }

    private fun saveBitmapToGallery(bmp: Bitmap): Boolean {
        return try {
            val filename = "twisted_warp_${System.currentTimeMillis()}.jpg"
            val resolver = contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/TwistedPhone")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val imageUri: Uri? = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (imageUri == null) {
                FileLogger.e(this, TAG, "resolver.insert returned null")
                return false
            }

            var succeeded = false
            resolver.openOutputStream(imageUri)?.use { out: OutputStream ->
                succeeded = bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
                out.flush()
            }

            if (succeeded && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(imageUri, contentValues, null, null)
            }

            succeeded
        } catch (e: Exception) {
            FileLogger.e(this, TAG, "Save failed: ${e.message}")
            false
        }
    }

    private fun takePictureUsingImageCapture() {
        val ic = imageCapture ?: run {
            Toast.makeText(this, "Capture not ready", Toast.LENGTH_SHORT).show()
            return
        }
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
