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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
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
                    ioScope.launch { processFrameAndShow(bmp) }
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

                // safe options; try to avoid problematic delegates first
                val opts = Interpreter.Options().apply {
                    setNumThreads(2)
                    // Try to turn off XNNPACK delegate if available (safer fallback)
                    try {
                        // some TF Lite versions expose setUseXNNPACK
                        val method = Interpreter.Options::class.java.getMethod("setUseXNNPACK", Boolean::class.javaPrimitiveType)
                        method.invoke(this, false)
                    } catch (_: Exception) {
                        // ignore if not present
                    }
                }

                midasInterpreter = Interpreter(midasFile, opts)

                // query shapes & types
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
            intArrayOf(0x00000000, 0x66000000),
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
                // pale bluish fog color - compose ARGB
                val fogColor = (alpha shl 24) or (0x8090A0)
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

            // expected: inShape = [1, H, W, C] or [1, W, H, C] depending on model
            val batch = inShape[0]
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
                // models expect [R,G,B] bytes 0..255
                for (p in pixels) {
                    inputBuf.put(((p shr 16) and 0xFF).toByte())
                    inputBuf.put(((p shr 8) and 0xFF).toByte())
                    inputBuf.put(((p) and 0xFF).toByte())
                }
            } else {
                // FLOAT32 expects normalized floats 0..1 or -1..1 depending on model card; we use 0..1
                val fb = inputBuf.asFloatBuffer()
                for (p in pixels) {
                    val r = ((p shr 16) and 0xFF) / 255.0f
                    val g = ((p shr 8) and 0xFF) / 255.0f
                    val b = ((p) and 0xFF) / 255.0f
                    fb.put(r); fb.put(g); fb.put(b)
                }
            }
            inputBuf.rewind()

            // prepare output buffer based on output shape
            // typical midas out: [1, H, W, 1]
            val outDims = outShape // like [1,256,256,1]
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
                // as extra attempt: recreate interpreter disabling XNNPACK and try once
                try {
                    FileLogger.d(this, TAG, "Attempting to recreate interpreter with XNNPACK disabled")
                    interp.close()
                    midasInterpreter = null
                    val altOpts = Interpreter.Options().apply {
                        setNumThreads(1)
                        try {
                            val method = Interpreter.Options::class.java.getMethod("setUseXNNPACK", Boolean::class.javaPrimitiveType)
                            method.invoke(this, false)
                        } catch (_: Exception) {}
                    }
                    val modelsDir = File(filesDir, "models")
                    val midasFile = File(modelsDir, "midas.tflite")
                    midasInterpreter = Interpreter(midasFile, altOpts)
                    // update references
                    midasInputShape = midasInterpreter?.getInputTensor(0)?.shape()?.copyOf()
                    midasOutputShape = midasInterpreter?.getOutputTensor(0)?.shape()?.copyOf()
                    midasInputType = midasInterpreter?.getInputTensor(0)?.dataType()
                    midasOutputType = midasInterpreter?.getOutputTensor(0)?.dataType()
                    // try again once
                    outputBuf.rewind()
                    midasInterpreter?.run(inputBuf, outputBuf)
                } catch (retryEx: Exception) {
                    val now2 = System.currentTimeMillis()
                    if (now2 - lastMidasErrorLogTime > MIDAS_ERROR_LOG_SUPPRESS_MS) {
                        FileLogger.e(this, TAG, "Midas retry failed: ${retryEx.message}")
                        lastMidasErrorLogTime = now2
                    }
                    return fallbackDepthFromLuma(bmp)
                }
            }

            // parse outputBuf -> produce normalized depth
            outputBuf.rewind()

            // compute output height/width from outDims
            // Handle common layout [1,H,W,1] or [1,H,W]
            val outH: Int
            val outW: Int
            if (outDims.size >= 4) {
                outH = outDims[1]
                outW = outDims[2]
            } else if (outDims.size == 3) {
                outH = outDims[1]
                outW = outDims[2]
            } else {
                // fallback
                outH = FALLBACK_PROC_H
                outW = FALLBACK_PROC_W
            }

            val depthArr = Array(outH) { FloatArray(outW) }
            var minV = Float.MAX_VALUE
            var maxV = -Float.MAX_VALUE

            if (outType == DataType.UINT8) {
                for (y in 0 until outH) {
                    for (x in 0 until outW) {
                        val b = outputBuf.get().toInt() and 0xFF
                        val v = b.toFloat()
                        depthArr[y][x] = v
                        if (v < minV) minV = v
                        if (v > maxV) maxV = v
                    }
                }
            } else {
                val fb = outputBuf.asFloatBuffer()
                for (y in 0 until outH) {
                    for (x in 0 until outW) {
                        val v = fb.get()
                        depthArr[y][x] = v
                        if (v < minV) minV = v
                        if (v > maxV) maxV = v
                    }
                }
            }

            // normalize 0..1
            val range = (maxV - minV).coerceAtLeast(1e-6f)
            for (y in 0 until outH) {
                for (x in 0 until outW) {
                    depthArr[y][x] = (depthArr[y][x] - minV) / range
                }
            }

            // upsample to bmp size (nearest)
            return resizeDepthArray(depthArr, bmp.height, bmp.width)
        } catch (e: Exception) {
            val now = System.currentTimeMillis()
            if (now - lastMidasErrorLogTime > MIDAS_ERROR_LOG_SUPPRESS_MS) {
                FileLogger.e(this, TAG, "Midas run failed: ${e.message}")
                lastMidasErrorLogTime = now
            }
            return fallbackDepthFromLuma(bmp)
        }
    }

    private fun resizeDepthArray(src: Array<FloatArray>, tgtH: Int, tgtW: Int): Array<FloatArray> {
        val out = Array(tgtH) { FloatArray(tgtW) }
        val sh = src.size
        val sw = src[0].size
        for (y in 0 until tgtH) {
            val sy = (y.toFloat() * sh / tgtH).toInt().coerceIn(0, sh - 1)
            for (x in 0 until tgtW) {
                val sx = (x.toFloat() * sw / tgtW).toInt().coerceIn(0, sw - 1)
                out[y][x] = src[sy][sx]
            }
        }
        return out
    }

    private fun fallbackDepthFromLuma(bmp: Bitmap): Array<FloatArray> {
        val w = bmp.width
        val h = bmp.height
        val out = Array(h) { FloatArray(w) }
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        val lumas = FloatArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16 and 0xFF).toFloat()
            val g = (p shr 8 and 0xFF).toFloat()
            val b = (p and 0xFF).toFloat()
            val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
            lumas[i] = l
            if (l < min) min = l
            if (l > max) max = l
        }
        val rng = (max - min).coerceAtLeast(1f)
        for (y in 0 until h) for (x in 0 until w) {
            out[y][x] = ((lumas[y * w + x] - min) / rng)
        }
        return out
    }

    private fun saveBitmapToGallery(bmp: Bitmap): Boolean {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.DISPLAY_NAME, "twisted_warp_${System.currentTimeMillis()}.jpg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TwistedPhone")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val resolver = contentResolver
            val uri: Uri? = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                resolver.openOutputStream(it)?.use { os ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 92, os)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
                true
            } ?: false
        } catch (e: Exception) {
            FileLogger.e(this, TAG, "save failed: ${e.message}")
            false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { midasInterpreter?.close() } catch (_: Exception) {}
        cameraExecutor.shutdown()
        ioScope.cancel()
        FileLogger.d(this, TAG, "CameraActivity destroyed")
    }
}
