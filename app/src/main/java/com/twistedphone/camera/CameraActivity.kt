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
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.twistedphone.TwistedApp
import com.twistedphone.util.Logger
import kotlinx.coroutines.*
import org.tensorflow.lite.Interpreter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.*

/**
 * CameraActivity (replacement)
 * - CameraX PreviewView for camera
 * - Overlay ImageView to display processed frames (preserves aspect ratio via CENTER_CROP)
 * - Object-aware distortion using model depth (midas.tflite) if present, otherwise fast saliency/edge regions
 * - Color grading to create darker atmosphere (no cheap vignette overlay)
 * - Capture saves processed image to MediaStore
 *
 * Notes:
 * - Place your midas.tflite model at filesDir/models/midas.tflite for depth-based processing.
 * - This code is intentionally defensive: if model not present, fallback to saliency.
 */
class CameraActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "CameraActivity"
        private const val TARGET_ANALYSIS_FPS = 8L // throttle inference
        private val PROC_SIZE = Size(320, 240) // processing resolution -> fast
    }

    private lateinit var root: FrameLayout
    private lateinit var previewView: PreviewView
    private lateinit var overlayView: ImageView
    private lateinit var captureButton: Button

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var analysisUseCase: ImageAnalysis? = null

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val bgScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Model interpreter (optional)
    private var midasInterpreter: Interpreter? = null

    // last processed frame (for capture)
    @Volatile
    private var lastProcessedBitmap: Bitmap? = null
    private var lastProcessedFullRes: Bitmap? = null

    private var lastAnalysisTime = 0L

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else { Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show(); finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        previewView = PreviewView(this)
        overlayView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.CENTER_CROP // preserves aspect, avoids stretching
            visibility = View.VISIBLE
            // let overlay be on top
        }
        captureButton = Button(this).apply {
            text = "Capture"
            setOnClickListener { captureProcessed() }
        }

        root.addView(previewView)
        root.addView(overlayView)
        // button on top-right-ish
        val btnParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        btnParams.marginStart = 24
        btnParams.topMargin = 24
        root.addView(captureButton, btnParams)

        setContentView(root)

        // attempt to load midas model if present
        bgScope.launch {
            midasInterpreter = tryLoadMidas()
        }

        // permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            startCamera()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        bgScope.cancel()
        cameraProvider?.unbindAll()
        try { midasInterpreter?.close() } catch (_: Throwable) {}
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindUseCases() {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        imageCapture = ImageCapture.Builder()
            .setTargetRotation(previewView.display.rotation)
            .build()

        analysisUseCase = ImageAnalysis.Builder()
            .setTargetResolution(PROC_SIZE)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        analysisUseCase?.setAnalyzer(ContextCompat.getMainExecutor(this)) { imageProxy ->
            val now = System.currentTimeMillis()
            // throttle heavy work
            if (now - lastAnalysisTime < 1000L / TARGET_ANALYSIS_FPS) {
                imageProxy.close()
                return@setAnalyzer
            }
            lastAnalysisTime = now

            // offload processing to bgScope, but we need a fast conversion to bitmap first
            bgScope.launch {
                try {
                    val bitmap = imageProxyToBitmap(imageProxy)
                    if (bitmap != null) {
                        // keep a full-res processed copy in background for capture if we want (smaller memory footprint: scaled)
                        val processed = processFrameObjectAware(bitmap)
                        lastProcessedBitmap = processed
                        // show on UI
                        withContext(Dispatchers.Main) {
                            overlayView.setImageBitmap(processed)
                        }
                    }
                } catch (e: Exception) {
                    Logger.e(TAG, "analysis error: ${e.message}")
                } finally {
                    imageProxy.close()
                }
            }
        }

        try {
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            cameraProvider?.bindToLifecycle(this, cameraSelector, preview, imageCapture, analysisUseCase)
        } catch (e: Exception) {
            Logger.e(TAG, "bindUseCases failed: ${e.message}")
            Toast.makeText(this, "Camera start failed", Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------- Processing pipeline -------------------

    /**
     * Convert ImageProxy (YUV) -> rotated Bitmap (with correct orientation).
     * This uses a NV21 byte array conversion & BitmapFactory decode (fast and reliable).
     */
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
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)
            val yuvImage = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, image.width, image.height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(android.graphics.Rect(0, 0, image.width, image.height), 75, out)
            val bytes = out.toByteArray()
            var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            // rotate to match sensor -> display orientation
            val rotation = image.imageInfo.rotationDegrees
            if (rotation != 0) {
                val matrix = Matrix()
                matrix.postRotate(rotation.toFloat())
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            }
            // scale down to a manageable processing size (we want speed)
            val proc = Bitmap.createScaledBitmap(bmp, PROC_SIZE.width, PROC_SIZE.height, true)
            return proc
        } catch (e: Exception) {
            Logger.e(TAG, "imageProxyToBitmap failed: ${e.message}")
            return null
        }
    }

    /**
     * Main frame processing: tries model depth then saliency; returns a processed Bitmap (same PROC_SIZE)
     */
    private fun processFrameObjectAware(srcSmall: Bitmap): Bitmap {
        try {
            val enhanced = TwistedApp.instance.settingsPrefs.getBoolean("enhanced_camera_mode", false)

            // Get a per-row offset map and object masks
            val depthMap = midasInterpreter?.let { runMidasSafely(it, srcSmall) } // may be null
            val objectMask = if (depthMap != null) {
                // object mask from normalized depth: deeper contrast regions -> objects
                computeObjectMaskFromDepth(depthMap, srcSmall.width, srcSmall.height)
            } else {
                // fallback: saliency via edge energy
                computeObjectMaskByEdges(srcSmall)
            }

            // compute per-row offsets that are stronger inside objectMask and weaker outside
            val rowOffsets = computeOffsetsMix(depthMap, srcSmall.width, srcSmall.height, objectMask, enhanced)

            // warp per-row
            val warped = warpPerRow(srcSmall, rowOffsets)

            // apply atmospheric color grade
            val final = applyAtmosphere(warped, enhanced, objectMask)

            // store last full-res preview for capture if desired (we can upscale processed with nearest)
            lastProcessedFullRes = Bitmap.createScaledBitmap(final, final.width, final.height, true)

            return final
        } catch (e: Exception) {
            Logger.e(TAG, "processFrameObjectAware failed: ${e.message}")
            return srcSmall
        }
    }

    // ------------------- Depth / model helpers -------------------

    private fun tryLoadMidas(): Interpreter? {
        return try {
            val f = File(filesDir, "models/midas.tflite")
            if (!f.exists()) {
                Logger.d(TAG, "midas.tflite not found; using saliency fallback")
                return null
            }
            val mb = loadMappedFile(f)
            val opts = Interpreter.Options().apply { setNumThreads(2) }
            Interpreter(mb, opts)
        } catch (e: Exception) {
            Logger.e(TAG, "midas load failed: ${e.message}")
            null
        }
    }

    private fun loadMappedFile(f: File): MappedByteBuffer {
        val input = FileInputStream(f).channel
        return input.map(FileChannel.MapMode.READ_ONLY, 0, f.length())
    }

    private fun runMidasSafely(interp: Interpreter, bmp: Bitmap): Array<FloatArray>? {
        return try {
            // many midas variants accept 1xHxWx3 float input and output 1xHxWx1 - but rather than guessing,
            // we will attempt a simple typical flow: scale to model input size (read shape), create float input and run.
            val inputTensor = interp.getInputTensor(0)
            val shape = inputTensor.shape() // e.g., [1, H, W, 3]
            if (shape.size != 4) return null
            val reqH = shape[1]
            val reqW = shape[2]
            val small = Bitmap.createScaledBitmap(bmp, reqW, reqH, true)
            // build float input [1, H, W, 3]
            val input = Array(1) { Array(reqH) { Array(reqW) { FloatArray(3) } } }
            val tmp = IntArray(reqW * reqH)
            small.getPixels(tmp, 0, reqW, 0, 0, reqW, reqH)
            var idx = 0
            for (y in 0 until reqH) {
                for (x in 0 until reqW) {
                    val c = tmp[idx++]
                    input[0][y][x][0] = ((c shr 16) and 0xFF) / 255.0f
                    input[0][y][x][1] = ((c shr 8) and 0xFF) / 255.0f
                    input[0][y][x][2] = (c and 0xFF) / 255.0f
                }
            }
            // prepare output
            val outShape = interp.getOutputTensor(0).shape() // e.g., [1,H,W,1]
            val out = Array(outShape[1]) { FloatArray(outShape[2]) }
            interp.run(input, out)
            out
        } catch (e: Exception) {
            Logger.e(TAG, "Midas run failed: ${e.message}")
            null
        }
    }

    // ------------------- Object mask (from depth or edges) -------------------

    private fun computeObjectMaskFromDepth(depth: Array<FloatArray>?, w: Int, h: Int): Array<BooleanArray> {
        // treat bigger depth variations as object edges; threshold using mean/std
        val mask = Array(h) { BooleanArray(w) }
        if (depth == null) return mask
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        for (r in depth) for (v in r) { if (v < min) min = v; if (v > max) max = v }
        val range = max - min
        val threshold = min + (if (range > 1e-6f) range * 0.25f else 0.05f)
        for (y in 0 until h) {
            val row = depth[y]
            val rowLen = row.size
            for (x in 0 until w) {
                val v = if (x < rowLen) row[x] else row[rowLen - 1]
                mask[y][x] = v < threshold // object regions (nearby) often lower depth in some midas variants
            }
        }
        return mask
    }

    private fun computeObjectMaskByEdges(src: Bitmap): Array<BooleanArray> {
        val w = src.width
        val h = src.height
        val mask = Array(h) { BooleanArray(w) }
        // simple Sobel edge magnitude and threshold
        val gray = IntArray(w * h)
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in 0 until pixels.size) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            gray[i] = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
        }
        // Sobel kernels
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val gx = (-gray[(y - 1) * w + (x - 1)]) + (gray[(y - 1) * w + (x + 1)]) +
                        (-2 * gray[y * w + (x - 1)]) + (2 * gray[y * w + (x + 1)]) +
                        (-gray[(y + 1) * w + (x - 1)]) + (gray[(y + 1) * w + (x + 1)])
                val gy = (-gray[(y - 1) * w + (x - 1)]) + (-2 * gray[(y - 1) * w + x]) + (-gray[(y - 1) * w + (x + 1)]) +
                        (gray[(y + 1) * w + (x - 1)]) + (2 * gray[(y + 1) * w + x]) + (gray[(y + 1) * w + (x + 1)])
                val mag = sqrt((gx.toDouble() * gx + gy.toDouble() * gy)).toFloat()
                mask[y][x] = mag > 100f // threshold; tuned for 320x240
            }
        }
        return mask
    }

    // ------------------- Offsets computation -------------------

    private fun computeOffsetsMix(depth: Array<FloatArray>?, w: Int, h: Int, mask: Array<BooleanArray>, enhanced: Boolean): FloatArray {
        // produce per-row offset: stronger where mask has more true values for that row
        val baseShift = if (enhanced) 0.09f else 0.04f
        val offsets = FloatArray(h)
        for (y in 0 until h) {
            // compute mask density on row
            var count = 0
            for (x in 0 until w) if (mask[y][x]) count++
            val density = count.toFloat() / w.toFloat()
            // depth contribution: average depth row slope if available
            val depthFactor = if (depth != null) {
                val row = depth.getOrNull(y) ?: depth[y.coerceAtMost(depth.size - 1)]
                val avg = row.average().toFloat()
                (0.5f - (avg - 0.5f)).coerceIn(-1f, 1f) // center
            } else 0f
            val shift = baseShift * (1f + density * 2.5f + depthFactor * 1.2f)
            // alternate sign (some rows push left vs right for organic look)
            offsets[y] = ((if (y % 2 == 0) 1 else -1) * shift * w)
        }
        // smooth offsets vertically (simple blur)
        val smoothed = FloatArray(h)
        val window = 5
        for (y in 0 until h) {
            var s = 0f; var c = 0
            for (k in (y - window) .. (y + window)) {
                if (k in 0 until h) { s += offsets[k]; c++ }
            }
            smoothed[y] = s / c.toFloat()
        }
        // clamp
        for (i in smoothed.indices) {
            val maxShift = w * 0.22f
            smoothed[i] = smoothed[i].coerceIn(-maxShift, maxShift)
        }
        return smoothed
    }

    // ------------------- Warp + atmosphere -------------------

    private fun warpPerRow(src: Bitmap, rowOffsets: FloatArray): Bitmap {
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val rowBuf = IntArray(w)
        val tmp = IntArray(w)
        for (y in 0 until h) {
            src.getPixels(rowBuf, 0, w, 0, y, w, 1)
            val off = rowOffsets[y].roundToInt()
            if (off == 0) {
                out.setPixels(rowBuf, 0, w, 0, y, w, 1)
            } else if (off > 0) {
                System.arraycopy(rowBuf, 0, tmp, off, w - off)
                // fill left with edge pixel for stability
                for (i in 0 until off) tmp[i] = rowBuf[0]
                out.setPixels(tmp, 0, w, 0, y, w, 1)
            } else {
                val o = -off
                System.arraycopy(rowBuf, o, tmp, 0, w - o)
                for (i in w - o until w) tmp[i] = rowBuf[w - 1]
                out.setPixels(tmp, 0, w, 0, y, w, 1)
            }
        }
        return out
    }

    private fun applyAtmosphere(src: Bitmap, enhanced: Boolean, mask: Array<BooleanArray>): Bitmap {
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        // base color grade: darker, slightly higher contrast
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val contrast = if (enhanced) 1.06f else 1.02f
        val brightness = if (enhanced) -22f else -10f
        val cm = ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, brightness,
            0f, contrast, 0f, 0f, brightness,
            0f, 0f, contrast, 0f, brightness,
            0f, 0f, 0f, 1f, 0f
        ))
        paint.colorFilter = ColorMatrixColorFilter(cm)
        c.drawBitmap(src, 0f, 0f, paint)

        // subtle chromatic separation - only where mask true (object-aware)
        val redPaint = Paint()
        redPaint.isFilterBitmap = true
        redPaint.colorFilter = PorterDuffColorFilter(Color.argb(80, 255, 0, 0), PorterDuff.Mode.SRC_ATOP)
        val bluePaint = Paint()
        bluePaint.isFilterBitmap = true
        bluePaint.colorFilter = PorterDuffColorFilter(Color.argb(70, 0, 0, 255), PorterDuff.Mode.SRC_ATOP)

        // We'll overlay shifted layers only around object clusters to avoid global glitchy effect
        // Compute bounding rects from mask
        val rects = boundingRectsFromMask(mask)
        for (r in rects) {
            val srcRect = Rect(r.left, r.top, r.right, r.bottom)
            val dstRectR = Rect(r.left + 2, r.top, r.right + 2, r.bottom)
            val dstRectB = Rect(r.left - 2, r.top, r.right - 2, r.bottom)
            try {
                c.drawBitmap(src, srcRect, dstRectR, redPaint)
                c.drawBitmap(src, srcRect, dstRectB, bluePaint)
            } catch (e: Exception) {
                // ignore if any bounds issues
            }
        }

        return out
    }

    private fun boundingRectsFromMask(mask: Array<BooleanArray>): List<Rect> {
        val h = mask.size
        if (h == 0) return emptyList()
        val w = mask[0].size
        val visited = Array(h) { BooleanArray(w) }
        val rects = mutableListOf<Rect>()
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!visited[y][x] && mask[y][x]) {
                    // BFS flood fill small region
                    var minX = x; var minY = y; var maxX = x; var maxY = y
                    val queue = ArrayDeque<Pair<Int, Int>>()
                    queue.add(Pair(x, y)); visited[y][x] = true
                    while (queue.isNotEmpty()) {
                        val (cx, cy) = queue.removeFirst()
                        minX = min(minX, cx); minY = min(minY, cy)
                        maxX = max(maxX, cx); maxY = max(maxY, cy)
                        for (ny in max(0, cy - 1) .. min(h - 1, cy + 1)) {
                            for (nx in max(0, cx - 1) .. min(w - 1, cx + 1)) {
                                if (!visited[ny][nx] && mask[ny][nx]) {
                                    visited[ny][nx] = true
                                    queue.add(Pair(nx, ny))
                                }
                            }
                        }
                    }
                    // expand a bit and clamp
                    val padX = 6; val padY = 6
                    val l = (minX - padX).coerceAtLeast(0)
                    val t = (minY - padY).coerceAtLeast(0)
                    val r = (maxX + padX).coerceAtMost(w - 1)
                    val b = (maxY + padY).coerceAtMost(h - 1)
                    rects.add(Rect(l, t, r, b))
                    if (rects.size >= 8) return rects // limit
                }
            }
        }
        return rects
    }

    // ------------------- Capture saving -------------------

    private fun captureProcessed() {
        // If we already have a processed small bitmap, upscale to a reasonable save size and store
        val bmp = lastProcessedBitmap
        if (bmp == null) {
            Toast.makeText(this, "No frame ready", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                // upscale to 1080p width preserve aspect
                val width = 1080
                val height = (bmp.height.toFloat() / bmp.width.toFloat() * width).toInt()
                val saveBmp = Bitmap.createScaledBitmap(bmp, width, height, true)
                saveBitmapToGallery(saveBmp)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@CameraActivity, "Saved processed photo", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Logger.e(TAG, "captureProcessed failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@CameraActivity, "Save failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun saveBitmapToGallery(bmp: Bitmap) {
        val fname = "twisted_${System.currentTimeMillis()}.jpg"
        val resolver = contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fname)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TwistedPhone")
            }
        }
        val uri: Uri? = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            resolver.openOutputStream(uri)?.use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            Logger.d(TAG, "Saved processed image to gallery: $uri")
        } else {
            Logger.e(TAG, "Failed to insert MediaStore entry")
        }
    }
}
