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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.twistedphone.R
import com.twistedphone.TwistedApp
import com.twistedphone.util.FileLogger
import kotlinx.coroutines.*
import org.tensorflow.lite.Interpreter
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.*

class CameraActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "CameraActivity"
        private const val PROCESS_W = 256
        private const val PROCESS_H = 256
    }

    private lateinit var previewView: PreviewView
    private lateinit var btnCapture: Button
    private lateinit var btnToggle: Button
    private lateinit var overlay: ImageView

    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraSelectorLens: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService

    // tflite Interpreter (optional)
    private var midasInterpreter: Interpreter? = null
    private val ioScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // latest processed bitmap (warped) - used for capture
    @Volatile private var latestWarped: Bitmap? = null

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

        // add overlay ImageView programmatically (on top of preview)
        overlay = ImageView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.FIT_XY
        }
        // place overlay into root contentView (FrameLayout in your layout)
        (previewView.parent as? ViewGroup)?.addView(overlay)
        overlay.bringToFront()

        cameraExecutor = Executors.newSingleThreadExecutor()

        btnToggle.setOnClickListener {
            cameraSelectorLens = if (cameraSelectorLens == CameraSelector.DEFAULT_BACK_CAMERA) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            bindCameraUseCases()
        }

        btnCapture.setOnClickListener {
            // save current warped image if available
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

        // request permission if needed
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            startCamera()
        }

        // attempt to load midas.tflite if present
        ioScope.launch {
            tryLoadMidasInterpreter()
        }

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
            // convert to bitmap and process
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
            FileLogger.d(this, TAG, "Camera bound: lens=${cameraSelectorLens}")
        } catch (e: Exception) {
            FileLogger.e(this, TAG, "bindToLifecycle failed: ${e.message}")
        }
    }

    private suspend fun tryLoadMidasInterpreter() {
        withContext(Dispatchers.IO) {
            try {
                val modelsDir = File(filesDir, "models")
                val midasFile = File(modelsDir, "midas.tflite")
                if (midasFile.exists()) {
                    val opts = Interpreter.Options().apply { setNumThreads(2) }
                    midasInterpreter = Interpreter(midasFile, opts)
                    FileLogger.d(this@CameraActivity, TAG, "Loaded midas.tflite from ${midasFile.absolutePath}")
                } else {
                    FileLogger.d(this@CameraActivity, TAG, "midas.tflite not found in ${modelsDir.absolutePath}")
                }
            } catch (e: Exception) {
                FileLogger.e(this@CameraActivity, TAG, "Failed to load midas: ${e.message}")
            }
        }
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        // convert YUV_420_888 to NV21 then YuvImage -> JPEG -> Bitmap (works reliably)
        try {
            val yBuffer = image.planes[0].buffer
            val uBuffer = image.planes[1].buffer
            val vBuffer = image.planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)
            yBuffer.get(nv21, 0, ySize)

            // U and V are swapped for NV21
            val rowStride = image.planes[1].rowStride
            val pixelStride = image.planes[1].pixelStride

            // Convert U/V planes to interleaved VU as NV21 expects
            var pos = ySize
            val width = image.width
            val height = image.height
            val chromaHeight = height / 2
            val chromaWidth = width / 2
            val uBytes = ByteArray(uSize)
            val vBytes = ByteArray(vSize)
            uBuffer.get(uBytes)
            vBuffer.get(vBytes)

            // interleave V and U
            var uvIndex = 0
            for (row in 0 until chromaHeight) {
                var col = 0
                val base = row * rowStride
                while (col < chromaWidth) {
                    val vuPos = base + col * pixelStride
                    // v then u
                    if (vuPos < vBytes.size) nv21[pos++] = vBytes[vuPos] else nv21[pos++] = 0
                    if (vuPos < uBytes.size) nv21[pos++] = uBytes[vuPos] else nv21[pos++] = 0
                    col++
                }
            }

            val yuvImage = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, image.width, image.height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 85, out)
            val bytes = out.toByteArray()
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            FileLogger.e(this, TAG, "YUV->Bitmap failed: ${e.message}")
            return null
        }
    }

    private suspend fun processFrameAndShow(src: Bitmap) {
        // downscale for speed
        val down = Bitmap.createScaledBitmap(src, 640, (640f * src.height / src.width).roundToInt(), true)

        // get depth (0..1) either via midas or a cheap heuristic
        val depthNorm = runMidasOrFallback(down)

        // produce row offsets from depth: more depth -> stronger displacement
        val offsets = computeRowOffsetsFromDepth(depthNorm, strength = 40.0f)

        val warped = warpBitmapRowShift(down, offsets)

        val final = applyAtmosphere(warped, depthNorm)

        latestWarped = final

        withContext(Dispatchers.Main) {
            // set overlay image to the processed frame
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
            val avg = sum / w // 0..1 (close..far assuming model)
            // map avg to offset: near (0) -> push outwards less, far (1) -> stronger
            val off = (avg - 0.5f) * 2f * strength // -strength..+strength
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
            val offF = offsets[(y.toFloat() * offsets.size / h).toInt().coerceIn(0, offsets.size - 1)]
            val off = offF.roundToInt()
            val rowStart = y * w
            if (off > 0) {
                // shift right
                for (x in w - 1 downTo 0) {
                    val srcX = (x - off).coerceIn(0, w - 1)
                    outPixels[rowStart + x] = srcPixels[rowStart + srcX]
                }
            } else if (off < 0) {
                // shift left
                for (x in 0 until w) {
                    val srcX = (x - off).coerceIn(0, w - 1)
                    outPixels[rowStart + x] = srcPixels[rowStart + srcX]
                }
            } else {
                // copy
                for (x in 0 until w) outPixels[rowStart + x] = srcPixels[rowStart + x]
            }
        }
        out.setPixels(outPixels, 0, w, 0, 0, w, h)
        return out
    }

    private fun applyAtmosphere(src: Bitmap, depthNorm: Array<FloatArray>): Bitmap {
        // darken, slight blue, vignette, depth-based fog
        val w = src.width
        val h = src.height
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        // exposure down
        val paint = Paint()
        val cm = ColorMatrix()
        // slightly lower brightness and a little bluish tint
        cm.set(floatArrayOf(
            0.75f, 0f, 0f, 0f, 0f,
            0f, 0.82f, 0f, 0f, 0f,
            0f, 0f, 0.9f, 0f, 6f, // tiny blue lift
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

        // depth-based fog (subtle)
        val fogPaint = Paint()
        val fogBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val fogCanvas = Canvas(fogBitmap)
        val fogPixels = IntArray(w * h)
        for (y in 0 until h) {
            val row = depthNorm.getOrNull((y.toFloat() * depthNorm.size / h).toInt().coerceIn(0, depthNorm.size - 1))
            for (x in 0 until w) {
                val d = row?.get((x.toFloat() * row.size / w).toInt().coerceIn(0, row.size - 1)) ?: 0f
                // fog stronger where d is larger (farther)
                val alpha = (d * 80).toInt().coerceIn(0, 100)
                val col = (alpha shl 24) or (0x8090A0) // pale gray-blue fog-ish (packed)
                fogPixels[y * w + x] = col
            }
        }
        fogBitmap.setPixels(fogPixels, 0, w, 0, 0, w, h)
        fogPaint.alpha = 120
        canvas.drawBitmap(fogBitmap, 0f, 0f, null)

        return out
    }

    private fun runMidasOrFallback(bmp: Bitmap): Array<FloatArray> {
        // returns depth[h][w] normalized 0..1
        try {
            midasInterpreter?.let { interp ->
                // Prepare input: resize to PROCESS_W/PROCESS_H = 256
                val small = Bitmap.createScaledBitmap(bmp, PROCESS_W, PROCESS_H, true)
                // model signature from your dump: input UINT8 [1,256,256,3], output UINT8 [1,256,256,1]
                val inputBuf = ByteBuffer.allocateDirect(PROCESS_W * PROCESS_H * 3)
                inputBuf.order(ByteOrder.nativeOrder())
                val pixels = IntArray(PROCESS_W * PROCESS_H)
                small.getPixels(pixels, 0, PROCESS_W, 0, 0, PROCESS_W, PROCESS_H)
                for (p in pixels) {
                    // convert to unsigned 0..255 per channel
                    val r = (p shr 16 and 0xFF).toByte()
                    val g = (p shr 8 and 0xFF).toByte()
                    val b = (p and 0xFF).toByte()
                    inputBuf.put(r)
                    inputBuf.put(g)
                    inputBuf.put(b)
                }
                inputBuf.rewind()
                // output buffer
                val outBuf = Array(1) { Array(PROCESS_H) { ByteArray(PROCESS_W) } } // 1xH x W bytes
                // many TFLite patterns expect ByteArray[][][] for UINT8 quantized; run may accept this
                interp.run(inputBuf, outBuf)
                // convert to float normalized
                val depth = Array(PROCESS_H) { FloatArray(PROCESS_W) }
                var min = 255; var max = 0
                for (y in 0 until PROCESS_H) {
                    for (x in 0 until PROCESS_W) {
                        val v = outBuf[0][y][x].toInt() and 0xFF
                        if (v < min) min = v
                        if (v > max) max = v
                        depth[y][x] = v.toFloat()
                    }
                }
                val range = (max - min).coerceAtLeast(1)
                for (y in 0 until PROCESS_H) for (x in 0 until PROCESS_W) depth[y][x] = (depth[y][x] - min) / range
                // upsample depth to analysis size (we'll return same size as input down bitmap)
                return resizeDepthArray(depth, bmp.height, bmp.width)
            }
        } catch (e: Exception) {
            FileLogger.e(this, TAG, "Midas run failed: ${e.message}")
        }

        // fallback: simple luminance as "depth"
        return fallbackDepthFromLuma(bmp)
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
        var min = 255f
        var max = 0f
        val lumas = FloatArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16 and 0xFF).toFloat()
            val g = (p shr 8 and 0xFF).toFloat()
            val b = (p and 0xFF).toFloat()
            val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
            lumas[i] = l
            min = min(min, l)
            max = max(max, l)
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
        try {
            midasInterpreter?.close()
        } catch (_: Exception) {}
        cameraExecutor.shutdown()
        ioScope.cancel()
        FileLogger.d(this, TAG, "CameraActivity destroyed")
    }
}
