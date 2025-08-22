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
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import com.twistedphone.R
import com.twistedphone.util.FileLogger
import kotlinx.coroutines.*
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import kotlin.math.*

/**
 * CameraActivity:
 * - Enhanced mode default ON
 * - Front/rear switch
 * - MiDaS depth if midas.tflite exists in filesDir/models/
 * - MobileSAM encoder/decoder detection (logged), fallback to ML Kit masks
 * - Uses a downscaled preview analysis pipeline for realtime object-aware warping
 *
 * Notes:
 * - Place models into: filesDir/models/[midas.tflite, MobileSam_MobileSAMEncoder.tflite, MobileSam_MobileSAMDecoder.tflite]
 * - The activity queries TFLite input/output shapes dynamically so it works with the exact .tflite you have.
 */
class CameraActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CameraActivity"
        private const val PROCESSING_MAX_WIDTH = 640        // downscale width for analysis
        private const val MAX_ROW_SHIFT_FRACTION = 0.18f    // max horizontal row shift relative to width
    }

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: ImageView
    private lateinit var captureButton: Button
    private lateinit var switchButton: Button

    private var imageCapture: ImageCapture? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    // TFLite models (optional)
    private var midasInterpreter: Interpreter? = null
    private var mobileSamEncoder: Interpreter? = null
    private var mobileSamDecoder: Interpreter? = null

    // ML Kit detectors (fallback)
    private val faceDetector by lazy {
        val opts = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .build()
        FaceDetection.getClient(opts)
    }
    private val poseDetector by lazy {
        val opts = PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
        PoseDetection.getClient(opts)
    }

    // coroutine scope for background processing
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // last computed mask & offsets for saving captures
    @Volatile private var lastMask: Array<BooleanArray>? = null
    @Volatile private var lastOffsets: FloatArray? = null

    // simple prefs: default enhanced ON (read preference if exists)
    private val prefs by lazy { getSharedPreferences("twisted_prefs", MODE_PRIVATE) }
    private val enhancedDefault: Boolean
        get() = prefs.getBoolean("enhanced_camera", true)

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        previewView = findViewById(R.id.previewView)
        captureButton = findViewById(R.id.btnCapture)
        switchButton = findViewById(R.id.btnToggle)

        // overlay image view (draw warped frame on top of preview)
        overlayView = ImageView(this)
        overlayView.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        overlayView.scaleType = ImageView.ScaleType.FIT_CENTER
        (previewView.parent as FrameLayout).addView(overlayView)
        overlayView.visibility = View.VISIBLE

        captureButton.setOnClickListener { takePicture() }
        switchButton.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            bindCameraUseCases()
        }

        // load optional models on background thread
        scope.launch { loadModelsIfPresent() }

        // check permission and start camera
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            startCamera()
        }
    }

    private suspend fun loadModelsIfPresent() = withContext(Dispatchers.IO) {
        try {
            val mdir = File(filesDir, "models")
            if (!mdir.exists()) {
                FileLogger.d(this@CameraActivity, TAG, "No models directory yet: ${mdir.absolutePath}")
                return@withContext
            }

            val midasFile = File(mdir, "midas.tflite")
            if (midasFile.exists()) {
                FileLogger.d(this@CameraActivity, TAG, "Loading midas.tflite")
                midasInterpreter = Interpreter(mapFile(midasFile))
                FileLogger.d(this@CameraActivity, TAG, "Loaded MiDaS interpreter; input shape: ${midasInterpreter?.getInputTensor(0)?.shape()?.joinToString()}")
            } else {
                FileLogger.d(this@CameraActivity, TAG, "midas.tflite not found; will use luminance fallback for depth")
            }

            val encFile = File(mdir, "MobileSam_MobileSAMEncoder.tflite")
            val decFile = File(mdir, "MobileSam_MobileSAMDecoder.tflite")
            if (encFile.exists() && decFile.exists()) {
                FileLogger.d(this@CameraActivity, TAG, "MobileSAM encoder+decoder present (encoder: ${encFile.name}, decoder: ${decFile.name})")
                // load interpreters (we don't run them blindly)
                mobileSamEncoder = Interpreter(mapFile(encFile))
                mobileSamDecoder = Interpreter(mapFile(decFile))
                FileLogger.d(this@CameraActivity, TAG, "MobileSAM loaded; encoder input shape: ${mobileSamEncoder?.getInputTensor(0)?.shape()?.joinToString()}")
            } else {
                FileLogger.d(this@CameraActivity, TAG, "MobileSAM files missing or incomplete; will fallback to ML Kit masks")
            }

        } catch (e: Exception) {
            FileLogger.e(this@CameraActivity, TAG, "Error loading models: ${e.message}")
        }
    }

    private fun mapFile(f: File): ByteBuffer {
        val fc = FileInputStream(f).channel
        val bb = fc.map(FileChannel.MapMode.READ_ONLY, 0, fc.size())
        fc.close()
        return bb.order(ByteOrder.nativeOrder())
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        val preview = Preview.Builder()
            .setTargetRotation(previewView.display?.rotation ?: 0)
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        imageCapture = ImageCapture.Builder()
            .setTargetRotation(previewView.display?.rotation ?: 0)
            .setTargetResolution(Size(1280, 720))
            .build()

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetResolution(Size(PROCESSING_MAX_WIDTH, PROCESSING_MAX_WIDTH * 16 / 9))
            .build()

        analysis.setAnalyzer(cameraExecutor) { imageProxy ->
            try {
                analyzeFrame(imageProxy)
            } catch (e: Exception) {
                FileLogger.e(this, TAG, "Analyzer exception: ${e.message}")
                imageProxy.close()
            }
        }

        try {
            provider.bindToLifecycle(this, cameraSelector, preview, imageCapture, analysis)
        } catch (e: Exception) {
            FileLogger.e(this, TAG, "bindToLifecycle failed: ${e.message}")
        }
    }

    private fun analyzeFrame(proxy: ImageProxy) {
        val mediaImage = proxy.image ?: run { proxy.close(); return }
        // convert to downscaled bitmap for analysis
        val bitmap = imageToBitmap(mediaImage, proxy.imageInfo.rotationDegrees, PROCESSING_MAX_WIDTH)
        if (bitmap == null) {
            proxy.close(); return
        }

        scope.launch {
            try {
                // Depth: MiDaS if present else luminance fallback
                val depth = if (midasInterpreter != null) {
                    runMiDaS(bitmap)
                } else {
                    luminanceDepthApprox(bitmap)
                }

                // Mask: try MobileSAM (not fully wired here) -> fallback to ML Kit face/pose rectangles
                val mask = runMaskFallback(bitmap)

                // Compute offsets from depth
                val offsets = computeRowOffsetsFromDepth(depth, bitmap.width, bitmap.height)

                // Keep last for capture
                lastMask = mask
                lastOffsets = offsets

                // Warp and recolor
                val warped = warpBitmap(bitmap, offsets, mask)
                val final = applyAtmosphere(warped, mask, enhancedDefault)

                withContext(Dispatchers.Main) {
                    try { overlayView.setImageBitmap(final) } catch (e: Exception) { FileLogger.e(this@CameraActivity, TAG, "UI setImage failed: ${e.message}") }
                }
            } catch (e: Exception) {
                FileLogger.e(this@CameraActivity, TAG, "Frame processing error: ${e.message}")
            } finally {
                proxy.close()
            }
        }
    }

    // Convert Image (YUV_420_888) to downscaled ARGB bitmap
    private fun imageToBitmap(image: android.media.Image, rotation: Int, maxWidth: Int): Bitmap? {
        return try {
            val y = image.planes[0].buffer
            val u = image.planes[1].buffer
            val v = image.planes[2].buffer
            val ySize = y.remaining()
            val uSize = u.remaining()
            val vSize = v.remaining()
            val nv21 = ByteArray(ySize + uSize + vSize)
            y.get(nv21, 0, ySize)
            v.get(nv21, ySize, vSize)
            u.get(nv21, ySize + vSize, uSize)
            val yuv = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, image.width, image.height, null)
            val baos = java.io.ByteArrayOutputStream()
            yuv.compressToJpeg(android.graphics.Rect(0, 0, image.width, image.height), 80, baos)
            val bytes = baos.toByteArray()
            var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (rotation != 0) {
                val matrix = Matrix(); matrix.postRotate(rotation.toFloat())
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            }
            if (bmp.width > maxWidth) {
                val newH = (bmp.height.toFloat() * maxWidth / bmp.width).toInt()
                bmp = Bitmap.createScaledBitmap(bmp, maxWidth, newH, true)
            }
            bmp
        } catch (e: Exception) {
            FileLogger.e(this, TAG, "imageToBitmap failed: ${e.message}")
            null
        }
    }

    // Run MiDaS dynamically by querying input/output tensor shapes
    private suspend fun runMiDaS(bmp: Bitmap): FloatArray = withContext(Dispatchers.Default) {
        val interp = midasInterpreter ?: return@withContext luminanceDepthApprox(bmp)
        try {
            val inputTensor = interp.getInputTensor(0)
            val inputShape = inputTensor.shape() // e.g. [1, h, w, 3] or [1,3,h,w]
            val inputDims = inputShape.toList()
            FileLogger.d(this@CameraActivity, TAG, "MiDaS input shape: ${inputShape.joinToString()}")
            // choose width/height from shape
            val (modelW, modelH) = when {
                inputShape.size == 4 && inputShape[1] == 3 -> Pair(inputShape[3], inputShape[2])  // [1,3,h,w]
                inputShape.size == 4 && inputShape[3] == 3 -> Pair(inputShape[2], inputShape[1])  // [1,h,w,3]
                inputShape.size == 3 -> Pair(inputShape[2], inputShape[1])
                else -> Pair(256, 256)
            }
            // prepare input buffer float32
            val resized = Bitmap.createScaledBitmap(bmp, modelW, modelH, true)
            val inBuf = ByteBuffer.allocateDirect(4 * modelW * modelH * 3).order(ByteOrder.nativeOrder())
            inBuf.rewind()
            val px = IntArray(modelW * modelH)
            resized.getPixels(px, 0, modelW, 0, 0, modelW, modelH)
            // normalization heuristic: scale to [0,1]
            for (i in px.indices) {
                val c = px[i]
                inBuf.putFloat(((c shr 16) and 0xFF) / 255f)
                inBuf.putFloat(((c shr 8) and 0xFF) / 255f)
                inBuf.putFloat((c and 0xFF) / 255f)
            }
            inBuf.rewind()

            // create output object matching output shape dynamically
            val outShape = interp.getOutputTensor(0).shape() // e.g. [1, h, w, 1] or [1, h, w]
            FileLogger.d(this@CameraActivity, TAG, "MiDaS output shape: ${outShape.joinToString()}")
            val output = createFloatArrayForShape(outShape)

            // Run inference
            interp.run(inBuf, output)
            // flatten output to target w*h
            val outW: Int
            val outH: Int
            if (outShape.size >= 3) {
                outH = outShape[outShape.size - 3]
                outW = outShape[outShape.size - 2]
            } else {
                outH = modelH; outW = modelW
            }
            // Pull data into FloatArray of length bmp.width*bmp.height
            val depthArr = FloatArray(bmp.width * bmp.height)
            // extract from nested arrays
            val tmp2d = extract2DFloatArray(output)
            // upsample/resize to bmp dims (nearest neighbor)
            for (y in 0 until bmp.height) {
                val ry = (y.toFloat() * tmp2d.size / bmp.height).toInt().coerceIn(0, tmp2d.size - 1)
                val row = tmp2d[ry]
                for (x in 0 until bmp.width) {
                    val rx = (x.toFloat() * row.size / bmp.width).toInt().coerceIn(0, row.size - 1)
                    // normalize heuristically to [0,1]
                    val v = row[rx]
                    depthArr[y * bmp.width + x] = v.toFloat()
                }
            }
            FileLogger.d(this@CameraActivity, TAG, "MiDaS inference completed (approx)")
            return@withContext depthArr
        } catch (e: Exception) {
            FileLogger.e(this@CameraActivity, TAG, "MiDaS inference error: ${e.message}")
            return@withContext luminanceDepthApprox(bmp)
        }
    }

    // Helper: create nested float arrays based on shape (max 4D supported)
    private fun createFloatArrayForShape(shape: IntArray): Any {
        return when (shape.size) {
            1 -> FloatArray(shape[0])
            2 -> Array(shape[0]) { FloatArray(shape[1]) }
            3 -> Array(shape[0]) { Array(shape[1]) { FloatArray(shape[2]) } }
            4 -> Array(shape[0]) { Array(shape[1]) { Array(shape[2]) { FloatArray(shape[3]) } } }
            else -> FloatArray(shape.fold(1) { a, b -> a * b })
        }
    }

    // Helper: extract 2D float array if output is nested arrays of floats
    private fun extract2DFloatArray(obj: Any): Array<FloatArray> {
        return try {
            when (obj) {
                is FloatArray -> arrayOf(obj)
                is Array<*> -> {
                    // try to flatten to Array<FloatArray>
                    val outer = obj as Array<*>
                    if (outer.isEmpty()) return arrayOf()
                    // if outer elements are FloatArray
                    return if (outer[0] is FloatArray) {
                        @Suppress("UNCHECKED_CAST")
                        outer as Array<FloatArray>
                    } else if (outer[0] is Array<*>) {
                        // take first two dims
                        val h = outer.size
                        val w = (outer[0] as Array<*>).size
                        val out = Array(h) { FloatArray(w) }
                        for (i in 0 until h) {
                            val rowObj = outer[i] as Array<*>
                            for (j in 0 until w) {
                                val valAny = rowObj[j]
                                out[i][j] = when (valAny) {
                                    is Float -> valAny
                                    is Double -> valAny.toFloat()
                                    is Number -> valAny.toFloat()
                                    else -> 0f
                                }
                            }
                        }
                        out
                    } else {
                        arrayOf()
                    }
                }
                else -> arrayOf()
            }
        } catch (e: Exception) {
            FileLogger.e(this, TAG, "extract2DFloatArray failed: ${e.message}")
            arrayOf()
        }
    }

    // Fallback simple depth estimate from luminance (faster and reliable)
    private fun luminanceDepthApprox(bmp: Bitmap): FloatArray {
        val w = bmp.width; val h = bmp.height
        val arr = FloatArray(w * h)
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val lum = (0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)) / 255f
            arr[i] = (1f - lum).coerceIn(0f, 1f)
        }
        return arr
    }

    // Mask generation: if MobileSAM present, we could use it; for now fallback to ML Kit face/pose boxes
    private suspend fun runMaskFallback(bmp: Bitmap): Array<BooleanArray> = withContext(Dispatchers.Default) {
        try {
            val w = bmp.width; val h = bmp.height
            val mask = Array(h) { BooleanArray(w) }
            val input = InputImage.fromBitmap(bmp, 0)

            // Faces
            val facesTask: Task<List<Face>> = faceDetector.process(input)
            val faces = try { Tasks.await(facesTask, 500) } catch (e: Exception) {
                FileLogger.d(this@CameraActivity, TAG, "faceTask failed: ${e.message}"); emptyList<Face>()
            }
            for (f in faces) {
                val r = f.boundingBox
                val left = r.left.coerceAtLeast(0); val top = r.top.coerceAtLeast(0)
                val right = r.right.coerceAtMost(w); val bottom = r.bottom.coerceAtMost(h)
                for (yy in top until bottom) {
                    for (xx in left until right) mask[yy][xx] = true
                }
            }

            // Pose keypoints -> mask wrists/head
            try {
                val poseTask = poseDetector.process(input)
                val poses = try { Tasks.await(poseTask, 500) } catch (e: Exception) { emptyList<Pose>() }
                for (p in poses) {
                    val keypoints = listOfNotNull(
                        p.getPoseLandmark(com.google.mlkit.vision.pose.PoseLandmark.NOSE),
                        p.getPoseLandmark(com.google.mlkit.vision.pose.PoseLandmark.LEFT_WRIST),
                        p.getPoseLandmark(com.google.mlkit.vision.pose.PoseLandmark.RIGHT_WRIST)
                    )
                    for (kp in keypoints) {
                        val cx = kp.position.x.toInt().coerceIn(0, w - 1)
                        val cy = kp.position.y.toInt().coerceIn(0, h - 1)
                        val r = 28
                        for (yy in (cy - r).coerceAtLeast(0) until (cy + r).coerceAtMost(h - 1)) {
                            for (xx in (cx - r).coerceAtLeast(0) until (cx + r).coerceAtMost(w - 1)) mask[yy][xx] = true
                        }
                    }
                }
            } catch (e: Exception) {
                FileLogger.d(this@CameraActivity, TAG, "pose detection skipped/failed: ${e.message}")
            }

            return@withContext mask
        } catch (e: Exception) {
            FileLogger.e(this@CameraActivity, TAG, "runMaskFallback error: ${e.message}")
            // return empty mask on failure
            return@withContext Array(bmp.height) { BooleanArray(bmp.width) }
        }
    }

    // Compute horizontal row offsets (parallax) from a depth array length w*h
    private fun computeRowOffsetsFromDepth(depth: FloatArray, w: Int, h: Int): FloatArray {
        try {
            val rowAvg = FloatArray(h)
            for (y in 0 until h) {
                var sum = 0f
                for (x in 0 until w) sum += depth[y * w + x]
                rowAvg[y] = sum / w
            }
            val minv = rowAvg.minOrNull() ?: 0f
            val maxv = rowAvg.maxOrNull() ?: 1f
            val rng = (maxv - minv).let { if (it <= 1e-6f) 1f else it }
            val maxShift = w * MAX_ROW_SHIFT_FRACTION
            val offsets = FloatArray(h)
            for (y in 0 until h) {
                val norm = (rowAvg[y] - minv) / rng
                offsets[y] = (norm * 2f - 1f) * maxShift // near -> negative, far -> positive
            }
            return offsets
        } catch (e: Exception) {
            FileLogger.e(this, TAG, "computeRowOffsetsFromDepth error: ${e.message}")
            return FloatArray(h) { 0f }
        }
    }

    // Warp only inside masked pixels: shift masked pixels by row offset
    private fun warpBitmap(src: Bitmap, offsets: FloatArray, mask: Array<BooleanArray>?): Bitmap {
        val w = src.width; val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val rowPixels = IntArray(w)
        for (y in 0 until h) {
            src.getPixels(rowPixels, 0, w, 0, y, w, 1)
            val outRow = IntArray(w)
            val shift = if (y < offsets.size) offsets[y].toInt() else 0
            if (mask == null) {
                // global warp
                for (x in 0 until w) outRow[x] = rowPixels[(x - shift).coerceIn(0, w - 1)]
            } else {
                val rowMask = mask.getOrNull(y)
                if (rowMask == null) {
                    for (x in 0 until w) outRow[x] = rowPixels[(x - shift).coerceIn(0, w - 1)]
                } else {
                    for (x in 0 until w) outRow[x] = if (rowMask[x]) rowPixels[(x - shift).coerceIn(0, w - 1)] else rowPixels[x]
                }
            }
            out.setPixels(outRow, 0, w, 0, y, w, 1)
        }
        return out
    }

    // Darker + slight blue atmosphere; selective chromatic doubling for masked rects
    private fun applyAtmosphere(src: Bitmap, mask: Array<BooleanArray>?, enhanced: Boolean): Bitmap {
        val w = src.width; val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val contrast = if (enhanced) 1.07f else 1.03f
        val brighten = if (enhanced) -22f else -8f
        val cm = ColorMatrix(floatArrayOf(
            contrast,0f,0f,0f,brighten,
            0f,contrast,0f,0f,brighten,
            0f,0f,contrast,0f,brighten-8f,
            0f,0f,0f,1f,0f
        ))
        val blueTint = ColorMatrix(); blueTint.setScale(0.95f,0.96f,1.06f,1f)
        cm.postConcat(blueTint)
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(src, 0f, 0f, paint)

        if (mask != null) {
            // bounding boxes from mask (connected components)
            val rects = maskBoundingBoxes(mask)
            val redPaint = Paint(); redPaint.isFilterBitmap = true
            redPaint.colorFilter = PorterDuffColorFilter(Color.argb(if (enhanced) 120 else 80, 255, 0, 0), PorterDuff.Mode.SRC_ATOP)
            val bluePaint = Paint(); bluePaint.isFilterBitmap = true
            bluePaint.colorFilter = PorterDuffColorFilter(Color.argb(if (enhanced) 90 else 60, 0, 0, 255), PorterDuff.Mode.SRC_ATOP)
            val shift = if (enhanced) 6 else 3
            for (r in rects) {
                try {
                    val srcRect = Rect(r.left, r.top, r.right, r.bottom)
                    val dstR = Rect(r.left + shift, r.top, r.right + shift, r.bottom)
                    val dstB = Rect(r.left - shift, r.top, r.right - shift, r.bottom)
                    canvas.drawBitmap(src, srcRect, dstR, redPaint)
                    canvas.drawBitmap(src, srcRect, dstB, bluePaint)
                } catch (_: Exception) {}
            }
        }
        return out
    }

    // Simple connected components -> bounding rectangles
    private fun maskBoundingBoxes(mask: Array<BooleanArray>): List<Rect> {
        val h = mask.size
        if (h == 0) return emptyList()
        val w = mask[0].size
        val visited = Array(h) { BooleanArray(w) }
        val rects = mutableListOf<Rect>()
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!visited[y][x] && mask[y][x]) {
                    var minX = x; var minY = y; var maxX = x; var maxY = y
                    val q = ArrayDeque<Pair<Int,Int>>()
                    q.add(Pair(x,y)); visited[y][x] = true
                    while (q.isNotEmpty()) {
                        val (cx, cy) = q.removeFirst()
                        minX = min(minX, cx); minY = min(minY, cy)
                        maxX = max(maxX, cx); maxY = max(maxY, cy)
                        for (dy in -1..1) for (dx in -1..1) {
                            val nx = cx + dx; val ny = cy + dy
                            if (nx in 0 until w && ny in 0 until h && !visited[ny][nx] && mask[ny][nx]) {
                                visited[ny][nx] = true
                                q.add(Pair(nx, ny))
                            }
                        }
                    }
                    rects.add(Rect(minX, minY, maxX+1, maxY+1))
                }
            }
        }
        return rects
    }

    // Capture pipeline: use last computed mask+offsets to produce final warped saved image
    private fun takePicture() {
        val ic = imageCapture ?: return
        val ts = System.currentTimeMillis()
        val displayName = "twisted_${ts}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TwistedPhone")
        }
        val resolver = contentResolver
        val uri: Uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: run {
            Toast.makeText(this, "Cannot create media entry", Toast.LENGTH_SHORT).show()
            return
        }

        ic.takePicture(ContextCompat.getMainExecutor(this), object: ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(proxy: ImageProxy) {
                scope.launch {
                    try {
                        val mediaImage = proxy.image
                        if (mediaImage != null) {
                            var bmp = imageToBitmap(mediaImage, proxy.imageInfo.rotationDegrees, maxWidth = mediaImage.width)
                            if (bmp == null) {
                                bmp = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
                            }
                            // compute depth for full-res using MiDaS if available (downscale for speed)
                            val small = Bitmap.createScaledBitmap(bmp, PROCESSING_MAX_WIDTH, (PROCESSING_MAX_WIDTH * bmp.height / bmp.width), true)
                            val depth = if (midasInterpreter != null) runMiDaS(small) else luminanceDepthApprox(small)
                            val offsets = computeRowOffsetsFromDepth(depth, small.width, small.height)
                            // upscale offsets to full height (nearest)
                            val scaledOffsets = FloatArray(bmp.height)
                            for (y in 0 until bmp.height) {
                                val ry = (y.toFloat() * small.height / bmp.height).toInt().coerceIn(0, small.height - 1)
                                scaledOffsets[y] = offsets[ry] * (bmp.width.toFloat() / small.width.toFloat())
                            }
                            val mask = lastMask ?: Array(small.height) { BooleanArray(small.width) }
                            // scale mask to full-res (naive nearest)
                            val fullMask = Array(bmp.height) { BooleanArray(bmp.width) }
                            for (y in 0 until bmp.height) {
                                val ry = (y.toFloat() * small.height / bmp.height).toInt().coerceIn(0, small.height - 1)
                                for (x in 0 until bmp.width) {
                                    val rx = (x.toFloat() * small.width / bmp.width).toInt().coerceIn(0, small.width - 1)
                                    fullMask[y][x] = mask[ry][rx]
                                }
                            }
                            val warped = warpBitmap(bmp, scaledOffsets, fullMask)
                            val final = applyAtmosphere(warped, fullMask, enhancedDefault)
                            resolver.openOutputStream(uri)?.use { os -> final.compress(Bitmap.CompressFormat.JPEG, 90, os) }
                            FileLogger.d(this@CameraActivity, TAG, "Saved warped image to $uri")
                            withContext(Dispatchers.Main) { Toast.makeText(this@CameraActivity, "Saved capture", Toast.LENGTH_SHORT).show() }
                        }
                    } catch (e: Exception) {
                        FileLogger.e(this@CameraActivity, TAG, "Capture processing error: ${e.message}")
                    } finally {
                        proxy.close()
                    }
                }
            }
            override fun onError(exception: ImageCaptureException) {
                FileLogger.e(this@CameraActivity, TAG, "ImageCapture error: ${exception.message}")
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            midasInterpreter?.close()
            mobileSamEncoder?.close()
            mobileSamDecoder?.close()
        } catch (_: Exception) {}
        cameraExecutor.shutdown()
        scope.cancel()
        FileLogger.d(this, TAG, "CameraActivity destroyed")
    }
}
