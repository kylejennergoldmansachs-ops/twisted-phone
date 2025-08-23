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

    /**
     * Adaptive YUV -> Bitmap converter.
     *
     * Builds both NV21 variants (VU and UV) using plane rowStride/pixelStride and picks the decoded bitmap
     * with the higher variance (heuristic: avoids channel-swapped / striped output).
     */
    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        try {
            val planes = image.planes
            if (planes.size < 3) return null

            val yPlane = planes[0]
            val uPlane = planes[1]
            val vPlane = planes[2]

            val width = image.width
            val height = image.height

            val ySize = yPlane.buffer.remaining()
            val uSize = uPlane.buffer.remaining()
            val vSize = vPlane.buffer.remaining()

            val chromaRowStrideU = uPlane.rowStride
            val chromaRowStrideV = vPlane.rowStride
            val chromaPixelStrideU = uPlane.pixelStride
            val chromaPixelStrideV = vPlane.pixelStride

            FileLogger.d(this, TAG, "YUV meta: w=$width h=$height ySize=$ySize uSize=$uSize vSize=$vSize rowStU=$chromaRowStrideU rowStV=$chromaRowStrideV pixStU=$chromaPixelStrideU pixStV=$chromaPixelStrideV")

            // Build two NV21 variants: VU (nv21VU) and UV (nv21UV)
            val nv21Size = ySize + uSize + vSize
            val nv21VU = ByteArray(nv21Size)
            val nv21UV = ByteArray(nv21Size)

            // copy Y plane into both
            val yBuffer = yPlane.buffer
            val yBufPos = yBuffer.position()
            yBuffer.get(nv21VU, 0, ySize)
            // reset and copy again for second buffer
            yBuffer.position(yBufPos)
            yBuffer.get(nv21UV, 0, ySize)

            var offset = ySize

            val uBuffer = uPlane.buffer
            val vBuffer = vPlane.buffer

            // Build interleaved chroma for both variants
            val chromaHeight = height / 2
            val chromaWidth = width / 2

            for (row in 0 until chromaHeight) {
                val uRowStart = row * chromaRowStrideU
                val vRowStart = row * chromaRowStrideV
                for (col in 0 until chromaWidth) {
                    val uIndex = uRowStart + col * chromaPixelStrideU
                    val vIndex = vRowStart + col * chromaPixelStrideV
                    val uByte = if (uIndex < uBuffer.limit()) uBuffer.get(uIndex) else 0
                    val vByte = if (vIndex < vBuffer.limit()) vBuffer.get(vIndex) else 0
                    // NV21 VU order
                    if (offset < nv21VU.size) nv21VU[offset] = vByte
                    if (offset + 1 < nv21VU.size) nv21VU[offset + 1] = uByte
                    // NV12 (UV) order in the alternate buffer
                    if (offset < nv21UV.size) nv21UV[offset] = uByte
                    if (offset + 1 < nv21UV.size) nv21UV[offset + 1] = vByte
                    offset += 2
                }
            }

            // decode both NV21 buffers to JPEG -> Bitmap
            val bmpVU = nv21ToBitmapSafe(nv21VU, width, height)
            val bmpUV = nv21ToBitmapSafe(nv21UV, width, height)

            // pick the one with larger variance (heuristic: non-garbled images usually have higher variance)
            val scoreVU = bmpVU?.let { computeBitmapVariance(it) } ?: -1.0
            val scoreUV = bmpUV?.let { computeBitmapVariance(it) } ?: -1.0

            FileLogger.d(this, TAG, "NV21 pick scores: VU=$scoreVU UV=$scoreUV")

            return when {
                scoreVU < 0 && scoreUV < 0 -> null
                scoreVU >= scoreUV -> bmpVU
                else -> bmpUV
            }
        } catch (e: Exception) {
            FileLogger.e(this, TAG, "Adaptive YUV->Bitmap failed: ${e.message}")
            return null
        }
    }

    private fun nv21ToBitmapSafe(nv21: ByteArray, width: Int, height: Int): Bitmap? {
        return try {
            val yuvImage = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            val ok = yuvImage.compressToJpeg(Rect(0, 0, width, height), 85, out)
            if (!ok) {
                FileLogger.e(this, TAG, "compressToJpeg false")
                return null
            }
            val bytes = out.toByteArray()
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            FileLogger.e(this, TAG, "nv21->Bitmap decode failed: ${e.message}")
            null
        }
    }

    private fun computeBitmapVariance(bmp: Bitmap): Double {
        // compute variance over grayscale luminance (fast)
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        var sum = 0.0
        var sumSq = 0.0
        val n = (w * h).toDouble()
        for (p in pixels) {
            val r = ((p shr 16) and 0xFF)
            val g = ((p shr 8) and 0xFF)
            val b = (p and 0xFF)
            val l = 0.299 * r + 0.587 * g + 0.114 * b
            sum += l
            sumSq += l * l
        }
        val mean = sum / n
        val variance = (sumSq / n) - (mean * mean)
        return variance
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

        // subtle depth fog
        val fogBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val fogPixels = IntArray(w * h)
        for (y in 0 until h) {
            val rowIndex = (y.toFloat() * depthNorm.size / h).toInt().coerceIn(0, depthNorm.size - 1)
            val row = depthNorm[rowIndex]
            for (x in 0 until w) {
                val d = row[(x.toFloat() * row.size / w).toInt().coerceIn(0, row.size - 1)]
                val alpha = (d * 80).toInt().coerceIn(0, 100)
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
            val inShape = midasInputShape ?: interp.getInputTensor(0).shape().copyOf()
            val outShape = midasOutputShape ?: interp.getOutputTensor(0).shape().copyOf()
            val inType = midasInputType ?: interp.getInputTensor(0).dataType()
            val outType = midasOutputType ?: interp.getOutputTensor(0).dataType()

            val inH = inShape[1]
            val inW = inShape[2]
            val inC = if (inShape.size >= 4) inShape[3] else 3

            val inputByteSize = when (inType) {
                DataType.UINT8 -> inH * inW * inC
                DataType.FLOAT32 -> inH * inW * inC * 4
                else -> inH * inW * inC
            }
            val inputBuf = ByteBuffer.allocateDirect(inputByteSize).order(ByteOrder.nativeOrder())

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

            val outDims = outShape
            val outTotal = outDims.fold(1) { acc, i -> acc * i }
            val outputByteSize = when (outType) {
                DataType.UINT8 -> outTotal
                DataType.FLOAT32 -> outTotal * 4
                else -> outTotal
            }
            val outputBuf = ByteBuffer.allocateDirect(outputByteSize).order(ByteOrder.nativeOrder())

            try {
                outputBuf.rewind()
                interp.run(inputBuf, outputBuf)
            } catch (invokeEx: Exception) {
                val now = System.currentTimeMillis()
                if (now - lastMidasErrorLogTime > MIDAS_ERROR_LOG_SUPPRESS_MS) {
                    FileLogger.e(this, TAG, "Midas run failed (invoke): ${invokeEx.message}")
                    lastMidasErrorLogTime = now
                }
                return fallbackDepthFromLuma(bmp)
            }

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
                return fallbackDepthFromLuma(bmp)
            }

            if (outH == bmp.height && outW == bmp.width) {
                return outArr
            } else {
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
