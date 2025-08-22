// CameraActivity.kt (MobileSAM-enabled, runtime-adaptive)
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
import java.util.concurrent.TimeUnit
import kotlin.math.*

/**
 * CameraActivity with best-effort MobileSAM support.
 *
 * Behavior:
 * - Load interpreters if files exist in filesDir/models/
 * - When MobileSAM encoder+decoder exist, attempt to run them:
 *     - Resize image to encoder expected size, run encoder to get embeddings
 *     - Try to map decoder inputs by name and supply automatic grid point prompts
 *     - If decoder run succeeds and returns mask logits -> threshold to boolean mask
 * - If MobileSAM absent/incompatible/throws, fall back to ML Kit face+pose mask generator
 *
 * Notes:
 * - MobileSAM runtime compatibility depends on the exact tflite conversion. This code detects tensor names and shapes at runtime and is defensive.
 * - The "automatic prompt grid" is a heuristic — it generates many candidate foreground points across the image and requests masks from the decoder; that approximates an "automatic mask generator" approach when SAM-style outputs are expected.
 * - You should still ship quantized / device-specific TFLite builds (or use a delegate) for production performance.
 */
class CameraActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CameraActivity"
        private const val PROCESSING_MAX_WIDTH = 640
        private const val MAX_ROW_SHIFT_FRACTION = 0.18f
    }

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: ImageView
    private lateinit var captureButton: Button
    private lateinit var switchButton: Button

    private var imageCapture: ImageCapture? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    // Optional TFLite interpreters (may be null)
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

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var lastMask: Array<BooleanArray>? = null
    private var lastOffsets: FloatArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        previewView = findViewById(R.id.previewView)
        captureButton = findViewById(R.id.btnCapture)
        switchButton = findViewById(R.id.btnToggle)

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

        scope.launch { loadModelsIfPresent() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            startCamera()
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
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
                try {
                    FileLogger.d(this@CameraActivity, TAG, "Loading MobileSAM encoder+decoder")
                    mobileSamEncoder = Interpreter(mapFile(encFile))
                    mobileSamDecoder = Interpreter(mapFile(decFile))
                    FileLogger.d(this@CameraActivity, TAG, "MobileSAM interpreters loaded")
                } catch (e: Exception) {
                    FileLogger.e(this@CameraActivity, TAG, "Failed to load MobileSAM interpreters: ${e.message}")
                    mobileSamEncoder = null
                    mobileSamDecoder = null
                }
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
            .setTargetResolution(Size(2048, 1536))
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

    // ----------------- Analyzer and Mask logic -----------------

    private fun analyzeFrame(imageProxy: ImageProxy) {
        val bitmap = imageProxyToBitmap(imageProxy)
        if (bitmap == null) {
            imageProxy.close()
            return
        }

        scope.launch {
            // First: try MobileSAM path if interpreters present
            val maskFromSam = try {
                runMobileSamIfAvailable(bitmap)
            } catch (e: Exception) {
                FileLogger.e(this@CameraActivity, TAG, "MobileSAM run exception: ${e.message}")
                null
            }

            val mask = maskFromSam ?: runMaskFallback(bitmap)

            val offsets = computeRowOffsets(mask)
            lastMask = mask
            lastOffsets = offsets

            val warped = createWarpedBitmap(bitmap, offsets)
            withContext(Dispatchers.Main) {
                overlayView.setImageBitmap(warped)
            }
            imageProxy.close()
        }
    }

    // Convert ImageProxy to a downscaled ARGB bitmap (luma-based)
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        val plane = imageProxy.planes.firstOrNull() ?: return null
        val buffer = plane.buffer ?: return null

        try {
            val width = imageProxy.width
            val height = imageProxy.height
            val ySize = buffer.remaining()
            val y = ByteArray(ySize)
            buffer.get(y, 0, ySize)

            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(width * height)
            for (i in 0 until width * height) {
                val v = y.getOrNull(i)?.toInt() ?: 0
                val vv = (v and 0xFF)
                val col = (0xFF shl 24) or (vv shl 16) or (vv shl 8) or vv
                pixels[i] = col
            }
            bmp.setPixels(pixels, 0, width, 0, 0, width, height)

            val targetW = PROCESSING_MAX_WIDTH
            val targetH = (height.toFloat() / width * targetW).toInt()
            val scaled = Bitmap.createScaledBitmap(bmp, targetW, targetH, true)
            bmp.recycle()
            return scaled
        } catch (e: Exception) {
            FileLogger.e(this, TAG, "imageProxy->bitmap conversion failed: ${e.message}")
            return null
        }
    }

    /**
     * Attempt to run MobileSAM using TFLite interpreters if available.
     * Returns boolean mask (h x w) or null if MobileSAM is unavailable / incompatible / failed.
     *
     * The method:
     *  - queries encoder input shape and resizes the bitmap accordingly
     *  - runs encoder -> embeddings
     *  - inspects decoder input tensor names and tries to map them:
     *      image_embeddings, point_coords, point_labels, mask_input, has_mask_input, orig_im_size
     *  - supplies a grid of points as prompts (automatic mask generation heuristic)
     */
    private suspend fun runMobileSamIfAvailable(bmp: Bitmap): Array<BooleanArray>? = withContext(Dispatchers.Default) {
        val enc = mobileSamEncoder
        val dec = mobileSamDecoder
        if (enc == null || dec == null) return@withContext null

        try {
            // 1) encoder input shape
            val encIn0 = enc.getInputTensor(0)
            val encShape = encIn0.shape() // e.g., [1, H, W, 3] (commonly)
            if (encShape.size < 3) {
                FileLogger.d(this@CameraActivity, TAG, "Unexpected encoder input shape: ${encShape.joinToString()}")
                return@withContext null
            }

            val targetH = encShape[1]
            val targetW = encShape[2]
            // prepare float32 NHWC normalized input
            val encInput = bitmapToFloatArrayNHWC(bmp, targetW, targetH)

            // prepare encoder output buffer using output tensor shape
            val encOutTensor = enc.getOutputTensor(0)
            val encOutShape = encOutTensor.shape() // e.g., [1, Henc, Wenc, C]
            val encOutSize = encOutShape.fold(1) { a, b -> a * b }
            val encOutBuffer = FloatArray(encOutSize)

            // run encoder
            try {
                enc.run(encInput, encOutBuffer)
            } catch (e: Exception) {
                FileLogger.e(this@CameraActivity, TAG, "Encoder run failed: ${e.message}")
                return@withContext null
            }

            // 2) prepare decoder inputs by inspecting decoder tensors
            val decInputCount = dec.inputTensorCount
            val decInputNameToIndex = mutableMapOf<String, Int>()
            for (i in 0 until decInputCount) {
                try {
                    val n = dec.getInputTensor(i).name.lowercase()
                    decInputNameToIndex[n] = i
                } catch (e: Exception) {
                    // ignore tensor name read failures
                }
            }

            // heuristic: find likely indices
            fun findIndexByContains(vararg keys: String): Int? {
                for ((name, idx) in decInputNameToIndex) {
                    for (k in keys) if (name.contains(k)) return idx
                }
                return null
            }

            val idxImageEmb = findIndexByContains("image_emb", "image_embedding", "image_embeddings", "img_emb")
            val idxPointCoords = findIndexByContains("point", "point_coords", "points", "prompt_point")
            val idxPointLabels = findIndexByContains("label", "point_labels", "pointlabel", "point_label")
            val idxMaskInput = findIndexByContains("mask_input", "mask_in", "prev_mask")
            val idxHasMask = findIndexByContains("has_mask", "has_mask_input")
            val idxOrigSize = findIndexByContains("orig", "orig_image", "image_size", "input_size")

            if (idxImageEmb == null) {
                FileLogger.d(this@CameraActivity, TAG, "Decoder does not expose an image_embeddings input we can find. Names: ${decInputNameToIndex.keys}")
                return@withContext null
            }

            // 3) build automatic prompt grid (center of tiles)
            val gridCols = 6
            val gridRows = 4
            val points = mutableListOf<Pair<Float, Float>>()
            for (r in 0 until gridRows) {
                for (c in 0 until gridCols) {
                    val x = (c + 0.5f) / gridCols.toFloat()
                    val y = (r + 0.5f) / gridRows.toFloat()
                    points.add(x to y)
                }
            }
            val numPoints = points.size

            // point coords shape often [1, num_points, 2]
            val pointCoords = FloatArray(1 * numPoints * 2)
            for (i in 0 until numPoints) {
                pointCoords[i * 2 + 0] = points[i].first
                pointCoords[i * 2 + 1] = points[i].second
            }
            // labels: 1 for foreground
            val pointLabels = IntArray(1 * numPoints) { 1 }

            // 4) prepare inputs array for decoder in index order (object array of length decInputCount)
            val decInputs = arrayOfNulls<Any>(decInputCount)
            // fill image_embeddings: TFLite typically expects flattened float array matching enc output shape
            decInputs[idxImageEmb] = encOutBuffer

            if (idxPointCoords != null) decInputs[idxPointCoords] = pointCoords
            if (idxPointLabels != null) decInputs[idxPointLabels] = pointLabels
            if (idxMaskInput != null) decInputs[idxMaskInput] = FloatArray(1) // zero mask
            if (idxHasMask != null) decInputs[idxHasMask] = IntArray(1) { 0 }
            if (idxOrigSize != null) {
                // decoder sometimes wants [1,2] of H,W (original image). Put processing bitmap size (w,h)
                decInputs[idxOrigSize] = intArrayOf(bmp.height, bmp.width)
            }

            // 5) prepare expected outputs: try to get first output shape and allocate buffer
            val decOutCount = dec.outputTensorCount
            if (decOutCount <= 0) {
                FileLogger.d(this@CameraActivity, TAG, "Decoder has no outputs? count=0")
                return@withContext null
            }
            val outShape = dec.getOutputTensor(0).shape() // e.g., [1,1,Hmask,Wmask] or [1,Hmask,Wmask]
            val outSize = outShape.fold(1) { a, b -> a * b }
            val decOutBuffer = FloatArray(outSize)
            val outputs = mutableMapOf<Int, Any>()
            outputs[0] = decOutBuffer

            // 6) run decoder
            try {
                dec.runForMultipleInputsOutputs(decInputs, outputs)
            } catch (e: Exception) {
                FileLogger.e(this@CameraActivity, TAG, "Decoder run failed: ${e.message}")
                return@withContext null
            }

            // 7) interpret decoder output -> produce boolean mask sized to output HxW (or upscale to bmp size)
            val maskH: Int
            val maskW: Int
            if (outShape.size == 4) {
                // [1,1,H,W] or [1,C,H,W]
                maskH = outShape[2]
                maskW = outShape[3]
            } else if (outShape.size == 3) {
                maskH = outShape[1]
                maskW = outShape[2]
            } else {
                FileLogger.d(this@CameraActivity, TAG, "Unexpected decoder output shape: ${outShape.joinToString()}")
                return@withContext null
            }

            val mask = Array(maskH) { BooleanArray(maskW) }
            val flat = decOutBuffer
            // assume single-channel logits; threshold at 0.0 (logit) or 0.5 (prob) — defensively compute sigmoid
            for (y in 0 until maskH) {
                for (x in 0 until maskW) {
                    val idx = y * maskW + x
                    val v = flat.getOrNull(idx) ?: 0f
                    val prob = 1f / (1f + kotlin.math.exp(-v))
                    mask[y][x] = prob >= 0.5f
                }
            }

            // if mask resolution != bmp resolution, upscale mask to bmp resolution
            if (maskW != bmp.width || maskH != bmp.height) {
                val up = Array(bmp.height) { BooleanArray(bmp.width) }
                for (yy in 0 until bmp.height) {
                    val sy = (yy.toFloat() / bmp.height.toFloat()) * maskH.toFloat()
                    val iy = sy.toInt().coerceIn(0, maskH - 1)
                    for (xx in 0 until bmp.width) {
                        val sx = (xx.toFloat() / bmp.width.toFloat()) * maskW.toFloat()
                        val ix = sx.toInt().coerceIn(0, maskW - 1)
                        up[yy][xx] = mask[iy][ix]
                    }
                }
                return@withContext up
            }

            return@withContext mask
        } catch (e: Exception) {
            FileLogger.e(this@CameraActivity, TAG, "runMobileSamIfAvailable exception: ${e.message}")
            return@withContext null
        }
    }

    // helper: convert a Bitmap (any size) into an NHWC float array for TFLite (normalized 0..1)
    private fun bitmapToFloatArrayNHWC(src: Bitmap, targetW: Int, targetH: Int): FloatArray {
        val scaled = Bitmap.createScaledBitmap(src, targetW, targetH, true)
        val pixels = IntArray(targetW * targetH)
        scaled.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
        val floats = FloatArray(1 * targetH * targetW * 3)
        var idx = 0
        for (y in 0 until targetH) {
            for (x in 0 until targetW) {
                val p = pixels[y * targetW + x]
                // extract RGB and normalize to [0,1]
                floats[idx++] = ((p shr 16) and 0xFF) / 255f
                floats[idx++] = ((p shr 8) and 0xFF) / 255f
                floats[idx++] = (p and 0xFF) / 255f
            }
        }
        scaled.recycle()
        return floats
    }

    /**
     * ML Kit fallback mask (faces + pose)
     */
    private suspend fun runMaskFallback(bmp: Bitmap): Array<BooleanArray> = withContext(Dispatchers.Default) {
        try {
            val w = bmp.width; val h = bmp.height
            val mask = Array(h) { BooleanArray(w) }
            val input = InputImage.fromBitmap(bmp, 0)

            val facesTask: Task<List<Face>> = faceDetector.process(input)
            val faces = try { Tasks.await(facesTask, 500, TimeUnit.MILLISECONDS) } catch (e: Exception) {
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

            try {
                val poseTask = poseDetector.process(input)
                val poses = try { Tasks.await(poseTask, 500, TimeUnit.MILLISECONDS) } catch (e: Exception) { emptyList<Pose>() }
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
            return@withContext Array(bmp.height) { BooleanArray(bmp.width) }
        }
    }

    private fun computeRowOffsets(mask: Array<BooleanArray>): FloatArray {
        val h = mask.size
        val w = if (h > 0) mask[0].size else 0
        val offsets = FloatArray(h)
        if (h == 0 || w == 0) return offsets

        for (y in 0 until h) {
            var count = 0
            for (x in 0 until w) if (mask[y][x]) count++
            val frac = count.toFloat() / w.toFloat()
            offsets[y] = (MAX_ROW_SHIFT_FRACTION * frac * frac) * w
        }

        val smoothed = FloatArray(h)
        val radius = 6
        for (y in 0 until h) {
            var sum = 0f
            var norm = 0f
            for (k in -radius..radius) {
                val yy = (y + k).coerceIn(0, h - 1)
                val wght = (1.0f / (1 + kotlin.math.abs(k))).toFloat()
                sum += offsets[yy] * wght
                norm += wght
            }
            smoothed[y] = sum / norm
        }
        return smoothed
    }

    private fun createWarpedBitmap(src: Bitmap, offsets: FloatArray): Bitmap {
        val w = src.width; val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val srcRect = Rect(0, 0, w, 1)
        val dstRect = Rect(0, 0, w, 1)

        for (y in 0 until h) {
            val rowOffset = offsets.getOrNull(y) ?: 0f
            val shift = rowOffset.toInt()
            srcRect.top = y; srcRect.bottom = y + 1
            val left = shift.coerceAtLeast(-w)
            dstRect.left = left
            dstRect.top = y
            dstRect.right = left + w
            dstRect.bottom = y + 1
            canvas.drawBitmap(src, srcRect, dstRect, paint)
        }

        return out
    }

    private fun takePicture() {
        val ic = imageCapture ?: run {
            Toast.makeText(this, "Capture not ready", Toast.LENGTH_SHORT).show()
            return
        }

        val name = "twisted_${System.currentTimeMillis()}"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TwistedPhone")
            }
        }
        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues)
            .build()

        ic.takePicture(outputOptions, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                val savedUri: Uri? = outputFileResults.savedUri
                FileLogger.d(this@CameraActivity, TAG, "Photo saved: $savedUri")
                Toast.makeText(this@CameraActivity, "Saved photo", Toast.LENGTH_SHORT).show()
            }

            override fun onError(exception: ImageCaptureException) {
                FileLogger.e(this@CameraActivity, TAG, "Image capture failed: ${exception.message}")
                Toast.makeText(this@CameraActivity, "Capture failed: ${exception.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }
}
