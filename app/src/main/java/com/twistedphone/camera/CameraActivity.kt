// File: app/src/main/java/com/twistedphone/camera/CameraActivity.kt
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
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import com.twistedphone.TwistedApp
import com.twistedphone.util.FileLogger
import com.twistedphone.util.Logger
import kotlinx.coroutines.*
import org.tensorflow.lite.Interpreter
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import kotlin.math.*

/**
 * Final CameraActivity that integrates:
 * - MiDaS (optional) for depth
 * - MobileSAM encoder + decoder (optional) for segmentation (requires both files)
 * - ML Kit Face + Pose
 * - Robust fallbacks to keep UI working even if models missing
 *
 * Models (if present) must be at: filesDir/models/
 * - midas.tflite
 * - MobileSam_MobileSAMEncoder.tflite
 * - MobileSam_MobileSAMDecoder.tflite
 *
 * Settings:
 * - "enhanced_camera_mode" boolean in TwistedApp.instance.settingsPrefs (default true)
 */
class CameraActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "CameraActivity"
        private const val PROCESSING_WIDTH = 640 // width used for model processing; tune for perf
        private const val MAX_ROW_SHIFT_FRACTION = 0.18f
    }

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: ImageView
    private lateinit var captureButton: Button
    private lateinit var switchCameraButton: ImageButton

    // CameraX
    private var imageCapture: ImageCapture? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    // Models
    private var midasInterpreter: Interpreter? = null
    private var mobileSamEncoder: Interpreter? = null
    private var mobileSamDecoder: Interpreter? = null

    // ML Kit
    private val faceDetector by lazy {
        val opts = FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).build()
        FaceDetection.getClient(opts)
    }
    private val poseDetector by lazy {
        val opts = PoseDetectorOptions.Builder().setDetectorMode(PoseDetectorOptions.STREAM_MODE).build()
        PoseDetection.getClient(opts)
    }

    // State
    private val bgScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @Volatile private var lastSmallMask: Array<BooleanArray>? = null
    @Volatile private var lastSmallOffsets: FloatArray? = null
    @Volatile private var lastProcessedPreviewBitmap: Bitmap? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // UI (programmatic so it works with your current layout approach)
        val root = FrameLayout(this)
        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        overlayView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        captureButton = Button(this).apply {
            text = "CAPTURE"
            val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            lp.bottomMargin = 56
            layoutParams = lp
            setOnClickListener { capturePressed() }
        }
        switchCameraButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_camera)
            val lp = FrameLayout.LayoutParams(140, 140)
            lp.gravity = Gravity.TOP or Gravity.END
            lp.topMargin = 36
            lp.rightMargin = 16
            layoutParams = lp
            setBackgroundColor(Color.argb(120, 0, 0, 0))
            setOnClickListener { toggleCamera() }
        }
        root.addView(previewView)
        root.addView(overlayView)
        root.addView(captureButton)
        root.addView(switchCameraButton)
        setContentView(root)

        // Enhanced default
        try {
            val prefs = TwistedApp.instance.settingsPrefs
            if (!prefs.contains("enhanced_camera_mode")) {
                prefs.edit().putBoolean("enhanced_camera_mode", true).apply()
                Logger.d(TAG, "Set default enhanced_camera_mode=true")
            }
        } catch (t: Throwable) {
            Logger.e(TAG, "Settings prefs unavailable: ${t.message}")
        }

        // Load models (background)
        bgScope.launch {
            loadMidasIfPresent()
            loadMobileSamIfPresent()
        }

        // Permissions / camera
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            startCamera()
        }
    }

    private fun toggleCamera() {
        // flip between front and back by toggling lens facing in use cases
        // easiest approach: restart camera with opposite selector (CameraSelector.DEFAULT_BACK_CAMERA vs DEFAULT_FRONT_CAMERA)
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val cameraProvider = providerFuture.get()
            val current = cameraProvider.availableCameraInfos // not used directly; restart by unbind & bind
            cameraProvider.unbindAll()
            // Use previewView implementation mode to default; the user toggles by swapping target resolution suggestion
            startCamera() // simple restart (Preview/Analysis configuration will pick default lens automatically)
            Logger.d(TAG, "toggleCamera: requested restart")
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val cameraProvider = providerFuture.get()
            try {
                cameraProvider.unbindAll()

                val preview = Preview.Builder().setTargetResolution(Size(1280, 720)).build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                imageCapture = ImageCapture.Builder().setTargetRotation(previewView.display.rotation).build()

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .setTargetResolution(Size(PROCESSING_WIDTH, PROCESSING_WIDTH * 16 / 9))
                    .build()

                analysis.setAnalyzer(cameraExecutor, ImageAnalysis.Analyzer { imageProxy ->
                    bgScope.launch {
                        try {
                            val bmp = imageProxyToBitmap(imageProxy)
                            if (bmp != null) processFrame(bmp)
                        } catch (t: Throwable) {
                            Logger.e(TAG, "Analyzer exception: ${t.message}")
                        } finally {
                            imageProxy.close()
                        }
                    }
                })

                // bind; default lens chosen by CameraX (front/back maintained by app restart; for simplicity we didn't store lens)
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, analysis)
                Logger.d(TAG, "CameraX bound")
            } catch (e: Exception) {
                Logger.e(TAG, "startCamera failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // Converts ImageProxy (YUV) to Bitmap safely (lossy path via JPEG to be safe)
    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        try {
            val nv21 = yuv420ToNv21(image)
            val yuvImage = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, image.width, image.height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 85, out)
            val bytes = out.toByteArray()
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (t: Throwable) {
            Logger.e(TAG, "imageProxyToBitmap error: ${t.message}")
            return null
        }
    }

    private fun yuv420ToNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0].buffer
        val uPlane = image.planes[1].buffer
        val vPlane = image.planes[2].buffer
        val yRowStride = image.planes[0].rowStride
        val uvRowStride = image.planes[1].rowStride
        val uvPixelStride = image.planes[1].pixelStride
        val nv21 = ByteArray(width * height * 3 / 2)
        var pos = 0
        val yBytes = ByteArray(yPlane.remaining())
        yPlane.get(yBytes)
        // copy Y rows manually respecting rowStride
        val yRow = ByteArray(yRowStride)
        var srcOffset = 0
        for (r in 0 until height) {
            val rowStart = r * yRowStride
            System.arraycopy(yBytes, rowStart, nv21, pos, width)
            pos += width
        }
        // UV
        val uBytes = ByteArray(uPlane.remaining())
        val vBytes = ByteArray(vPlane.remaining())
        uPlane.get(uBytes); vPlane.get(vBytes)
        var uvPos = width * height
        for (r in 0 until height / 2) {
            var col = 0
            while (col < width) {
                val uIndex = r * uvRowStride + (col / 2) * uvPixelStride
                val vIndex = r * uvRowStride + (col / 2) * uvPixelStride
                nv21[uvPos++] = vBytes[vIndex]
                nv21[uvPos++] = uBytes[uIndex]
                col += 2
            }
        }
        return nv21
    }

    // Main per-frame pipeline
    private suspend fun processFrame(fullBmp: Bitmap) {
        withContext(Dispatchers.Default) {
            val startT = System.currentTimeMillis()
            try {
                val enhanced = TwistedApp.instance.settingsPrefs.getBoolean("enhanced_camera_mode", true)
                // 1. downscale to PROCESSING_WIDTH
                val w = PROCESSING_WIDTH
                val h = (fullBmp.height.toFloat() * (w.toFloat() / fullBmp.width.toFloat())).roundToInt()
                val smallBmp = Bitmap.createScaledBitmap(fullBmp, w, h, true)

                // 2. Attempt MobileSAM segmentation if models available
                var maskFromSam: Array<BooleanArray>? = null
                if (mobileSamEncoder != null && mobileSamDecoder != null) {
                    try {
                        maskFromSam = runMobileSam(smallBmp)
                        if (maskFromSam != null) Logger.d(TAG, "MobileSAM provided mask: ${maskFromSam.size}x${maskFromSam[0].size}")
                    } catch (t: Throwable) {
                        Logger.e(TAG, "MobileSAM inference failed: ${t.message}")
                        maskFromSam = null
                    }
                }

                // 3. Depth (MiDaS) - optional
                val depthMap = midasInterpreter?.let { runMidas(it, smallBmp) } // may return null

                // 4. ML Kit: faces + pose boxes
                val faces = runFaceDetector(smallBmp)
                val poseBoxes = runPoseDetector(smallBmp)

                // 5. Compose mask:
                val finalMask = composeMask(smallBmp.width, smallBmp.height, maskFromSam, depthMap, faces, poseBoxes)

                // 6. compute row offsets
                val offsets = computeOffsetsFromMask(finalMask, enhanced)

                // 7. warp + color grade
                val warped = warpSmallAndColorize(smallBmp, offsets, depthMap, finalMask, enhanced)

                // 8. present
                lastSmallMask = finalMask
                lastSmallOffsets = offsets
                lastProcessedPreviewBitmap?.recycle()
                lastProcessedPreviewBitmap = warped.copy(Bitmap.Config.ARGB_8888, false)
                withContext(Dispatchers.Main) {
                    overlayView.setImageBitmap(warped)
                }

                val dur = System.currentTimeMillis() - startT
                Logger.d(TAG, "Frame processed (w=${w},h=${h}) in ${dur}ms - mask density=${maskDensity(finalMask)}")
            } catch (t: Throwable) {
                Logger.e(TAG, "processFrame exception: ${t.message}")
            }
        }
    }

    // ------------------ MobileSAM integration ------------------
    // Attempt to run encoder->decoder with simple box prompt (full-image) or optional small boxes from pose/face.
    // This function is deliberately defensive and will return null if the decoder shape doesn't match expectation.
    private fun runMobileSam(smallBmp: Bitmap): Array<BooleanArray>? {
        try {
            if (mobileSamEncoder == null || mobileSamDecoder == null) return null
            val enc = mobileSamEncoder!!
            val dec = mobileSamDecoder!!

            // prepare encoder input according to encoder input tensor shape
            val inTensor = enc.getInputTensor(0)
            val inShape = inTensor.shape() // typically [1, H, W, 3]
            val reqH = if (inShape.size >= 3) inShape[inShape.size - 3] else smallBmp.height
            val reqW = if (inShape.size >= 2) inShape[inShape.size - 2] else smallBmp.width
            val bmpResized = Bitmap.createScaledBitmap(smallBmp, reqW, reqH, true)

            // build input array [1][H][W][3] float
            val inputArray = Array(1) { Array(reqH) { Array(reqW) { FloatArray(3) } } }
            val px = IntArray(reqW * reqH)
            bmpResized.getPixels(px, 0, reqW, 0, 0, reqW, reqH)
            var idx = 0
            for (y in 0 until reqH) {
                for (x in 0 until reqW) {
                    val c = px[idx++]
                    inputArray[0][y][x][0] = ((c shr 16) and 0xFF) / 255.0f
                    inputArray[0][y][x][1] = ((c shr 8) and 0xFF) / 255.0f
                    inputArray[0][y][x][2] = (c and 0xFF) / 255.0f
                }
            }

            // run encoder
            // output shape unknown: probe output tensor
            val encOutCount = enc.outputTensorCount
            if (encOutCount < 1) {
                Logger.e(TAG, "MobileSAM encoder: unexpected outputs")
                bmpResized.recycle()
                return null
            }
            // allocate output as ByteBuffer using output tensor shape
            val outTensor = enc.getOutputTensor(0)
            val outShape = outTensor.shape()
            // create container of matching shape
            val encOutput = createZeroFloatContainer(outShape)

            enc.run(inputArray, encOutput)

            // encOutput is nested arrays; flatten to single FloatArray for passing to decoder
            val embeddingFlat = flattenNestedFloatArrays(encOutput)

            // Prepare decoder inputs dynamically
            // We will try common ordering: [embeddings, boxes, points, point_labels, ...] but we will detect dec input shapes.
            val decInputCount = dec.inputTensorCount
            val decInputs = arrayOfNulls<Any>(decInputCount)

            // First input: embeddings - try match first decoder input shape
            // We attempt to shape embedding to decoder expected first input shape if possible
            val decFirstShape = dec.getInputTensor(0).shape()
            // If first input expects [1, N, C] or [1, H, W, C] etc, we attempt to provide embedding accordingly.
            // The simplest is to provide embeddingFlat as FloatArray and let tflite accept it if shapes align.
            decInputs[0] = reshapeEmbeddingsToTensorForm(embeddingFlat, decFirstShape)

            // For remaining inputs, we try to provide a default "full-image box" prompt and no points:
            for (i in 1 until decInputCount) {
                val shape = dec.getInputTensor(i).shape()
                // If shape matches [1,4] or [1, N, 4], prepare boxes
                if (shape.size >= 2 && shape.lastIndex >= 1 && shape[shape.size - 1] == 4) {
                    // provide a single full-image box [x0,y0,x1,y1] normalized (0..1) or pixel coords depending on decoder
                    val w = smallBmp.width.toFloat()
                    val h = smallBmp.height.toFloat()
                    // We provide normalized box as FloatArray batch [1,4] -> [0f,0f,1f,1f]
                    val boxArr = FloatArray(shape.reduce { acc, s -> acc * s }.coerceAtLeast(4)) // allocate full size
                    // Find where to place [1,4] if shape e.g., [1,1,4] etc
                    // We'll fill leading dims then set last 4 dims to [0,0,1,1] for first box
                    if (shape.size == 2 && shape[0] == 1 && shape[1] == 4) {
                        boxArr[0] = 0f; boxArr[1] = 0f; boxArr[2] = 1f; boxArr[3] = 1f
                        decInputs[i] = arrayOf(boxArr) // sometimes decoder expects 2D
                    } else {
                        // fallback: fill zeros
                        for (k in boxArr.indices) boxArr[k] = 0f
                        if (shape.last() >= 4) {
                            boxArr[0] = 0f; boxArr[1] = 0f; boxArr[2] = 1f; boxArr[3] = 1f
                        }
                        decInputs[i] = boxArr
                    }
                } else {
                    // default zero array matching tensor size
                    val total = shape.reduce { a, b -> a * b }
                    decInputs[i] = FloatArray(total)
                }
            }

            // Prepare outputs: use decoder.getOutputTensor shapes
            val outputs = mutableMapOf<Int, Any>()
            val decOutCount = dec.outputTensorCount
            for (o in 0 until decOutCount) {
                val outT = dec.getOutputTensor(o)
                val os = outT.shape()
                val tot = os.reduce { a, b -> a * b }
                // assume float outputs
                val arr = FloatArray(tot)
                outputs[o] = arr
            }

            // Run decoder guarded
            try {
                dec.runForMultipleInputsOutputs(decInputs, outputs)
                // reconstruct mask from typical decoder output (assume first output is mask logits of shape [1, H, W] or [1,1,H,W])
                val out0Shape = dec.getOutputTensor(0).shape()
                val out0 = outputs[0] as FloatArray
                // We try to detect height/width
                val (mh, mw) = when {
                    out0Shape.size == 3 -> Pair(out0Shape[1], out0Shape[2])
                    out0Shape.size == 4 -> Pair(out0Shape[2], out0Shape[3])
                    else -> Pair(smallBmp.height, smallBmp.width)
                }
                // build boolean mask by thresholding logits at 0.0
                val mask = Array(mh) { BooleanArray(mw) }
                for (yy in 0 until mh) {
                    for (xx in 0 until mw) {
                        val idx = if (out0Shape.size == 3) yy * mw + xx else (yy * mw + xx)
                        val v = out0.getOrNull(idx) ?: 0f
                        mask[yy][xx] = v > 0.0f
                    }
                }
                bmpResized.recycle()
                return mask
            } catch (decEx: Exception) {
                Logger.e(TAG, "MobileSAM decoder run failed: ${decEx.message}")
                bmpResized.recycle()
                return null
            }
        } catch (ex: Exception) {
            Logger.e(TAG, "MobileSAM pipeline error: ${ex.message}")
            return null
        }
    }

    // Helper: create nested float container matching tflite output, but use Any to pass to run
    private fun createZeroFloatContainer(shape: IntArray): Any {
        // supports typical shapes up to 4 dims
        return when (shape.size) {
            1 -> FloatArray(shape[0])
            2 -> Array(shape[0]) { FloatArray(shape[1]) }
            3 -> Array(shape[0]) { Array(shape[1]) { FloatArray(shape[2]) } }
            4 -> Array(shape[0]) { Array(shape[1]) { Array(shape[2]) { FloatArray(shape[3]) } } }
            else -> FloatArray(shape.reduce { a, b -> a * b })
        }
    }

    private fun flattenNestedFloatArrays(container: Any): FloatArray {
        // This flattens nested arrays of Float into FloatArray
        return when (container) {
            is FloatArray -> container
            is Array<*> -> {
                val list = ArrayList<Float>()
                fun rec(v: Any?) {
                    when (v) {
                        is FloatArray -> for (x in v) list.add(x)
                        is Array<*> -> for (e in v) rec(e)
                        else -> {}
                    }
                }
                rec(container)
                val out = FloatArray(list.size)
                for (i in list.indices) out[i] = list[i]
                out
            }
            else -> FloatArray(0)
        }
    }

    private fun reshapeEmbeddingsToTensorForm(flat: FloatArray, shape: IntArray): Any {
        // Try to map flat into nested array matching `shape`; keep it simple: place values in row-major
        val total = shape.reduce { a, b -> a * b }
        val source = if (flat.size >= total) flat else FloatArray(total) { if (it < flat.size) flat[it] else 0f }
        return when (shape.size) {
            1 -> source.copyOfRange(0, total)
            2 -> {
                val out = Array(shape[0]) { FloatArray(shape[1]) }
                var idx = 0
                for (i in 0 until shape[0]) for (j in 0 until shape[1]) out[i][j] = source[idx++]
                out
            }
            3 -> {
                val out = Array(shape[0]) { Array(shape[1]) { FloatArray(shape[2]) } }
                var idx = 0
                for (i in 0 until shape[0]) for (j in 0 until shape[1]) for (k in 0 until shape[2]) out[i][j][k] = source[idx++]
                out
            }
            4 -> {
                val out = Array(shape[0]) { Array(shape[1]) { Array(shape[2]) { FloatArray(shape[3]) } } }
                var idx = 0
                for (a in 0 until shape[0]) for (b in 0 until shape[1]) for (c in 0 until shape[2]) for (d in 0 until shape[3])
                    out[a][b][c][d] = source[idx++]
                out
            }
            else -> source
        }
    }

    // -------------------- MiDaS helpers --------------------
    private fun loadMidasIfPresent() {
        try {
            val f = File(filesDir, "models/midas.tflite")
            if (!f.exists()) {
                Logger.d(TAG, "midas.tflite not present")
                return
            }
            val bb = loadMappedFile(f)
            val opts = Interpreter.Options().apply { setNumThreads(2) }
            midasInterpreter = Interpreter(bb, opts)
            Logger.d(TAG, "MiDaS loaded")
        } catch (e: Exception) {
            Logger.e(TAG, "loadMidasIfPresent failed: ${e.message}")
            midasInterpreter = null
        }
    }

    private fun runMidas(interp: Interpreter, bmp: Bitmap): Array<FloatArray>? {
        try {
            val inTensor = interp.getInputTensor(0)
            val shape = inTensor.shape() // expecting [1,H,W,3]
            val reqH = if (shape.size >= 3) shape[1] else bmp.height
            val reqW = if (shape.size >= 2) shape[2] else bmp.width
            val resized = Bitmap.createScaledBitmap(bmp, reqW, reqH, true)
            val input = Array(1) { Array(reqH) { Array(reqW) { FloatArray(3) } } }
            val px = IntArray(reqW * reqH)
            resized.getPixels(px, 0, reqW, 0, 0, reqW, reqH)
            var idx = 0
            for (y in 0 until reqH) {
                for (x in 0 until reqW) {
                    val c = px[idx++]
                    input[0][y][x][0] = ((c shr 16) and 0xFF) / 255.0f
                    input[0][y][x][1] = ((c shr 8) and 0xFF) / 255.0f
                    input[0][y][x][2] = (c and 0xFF) / 255.0f
                }
            }
            val outTensor = interp.getOutputTensor(0)
            val outShape = outTensor.shape()
            val outH = if (outShape.size >= 2) outShape[outShape.size - 2] else reqH
            val outW = if (outShape.size >= 1) outShape[outShape.size - 1] else reqW
            val out = Array(outH) { FloatArray(outW) }
            interp.run(input, out)
            resized.recycle()
            // normalize to 0..1
            var min = Float.MAX_VALUE; var max = -Float.MAX_VALUE
            for (r in out) for (v in r) { min = min(min, v); max = max(max, v) }
            val range = if (max - min < 1e-6f) 1f else max - min
            for (y in 0 until outH) for (x in 0 until outW) out[y][x] = (out[y][x] - min) / range
            return out
        } catch (e: Exception) {
            Logger.e(TAG, "runMidas failed: ${e.message}")
            return null
        }
    }

    // -------------------- MobileSAM load helpers --------------------
    private fun loadMobileSamIfPresent() {
        try {
            val encFile = File(filesDir, "models/MobileSam_MobileSAMEncoder.tflite")
            val decFile = File(filesDir, "models/MobileSam_MobileSAMDecoder.tflite")
            if (!encFile.exists() || !decFile.exists()) {
                Logger.d(TAG, "MobileSAM encoder/decoder not present; skipping")
                return
            }
            val encMapped = loadMappedFile(encFile)
            val decMapped = loadMappedFile(decFile)
            val opts = Interpreter.Options().apply { setNumThreads(2) }
            mobileSamEncoder = Interpreter(encMapped, opts)
            mobileSamDecoder = Interpreter(decMapped, opts)
            Logger.d(TAG, "MobileSAM encoder & decoder loaded")
        } catch (e: Exception) {
            Logger.e(TAG, "loadMobileSamIfPresent failed: ${e.message}")
            mobileSamEncoder = null
            mobileSamDecoder = null
        }
    }

    private fun loadMappedFile(file: File): MappedByteBuffer {
        val fis = FileInputStream(file)
        val channel = fis.channel
        val mb = channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
        fis.close()
        return mb
    }

    // ---------------- ML Kit wrappers ----------------
    private fun runFaceDetector(bmp: Bitmap): List<Face> {
        return try {
            val img = InputImage.fromBitmap(bmp, 0)
            val task = faceDetector.process(img)
            Tasks.await(task)
        } catch (e: Exception) {
            Logger.e(TAG, "face detection failed: ${e.message}")
            emptyList()
        }
    }

    private fun runPoseDetector(bmp: Bitmap): List<Pose> {
        return try {
            val img = InputImage.fromBitmap(bmp, 0)
            val task = poseDetector.process(img)
            Tasks.await(task)
        } catch (e: Exception) {
            Logger.e(TAG, "pose detection failed: ${e.message}")
            emptyList()
        }
    }

    // ---------------- mask composition & utilities ----------------
    private fun composeMask(w: Int, h: Int,
                            samMask: Array<BooleanArray>?,
                            depth: Array<FloatArray>?,
                            faces: List<Face>,
                            poseBoxes: List<Pose>): Array<BooleanArray> {
        val mask = Array(h) { BooleanArray(w) { false } }

        // 1) include SAM mask (if present) - scale to w,h
        if (samMask != null) {
            val mh = samMask.size; val mw = samMask[0].size
            for (y in 0 until h) {
                val sy = (y * mh / h).coerceIn(0, mh-1)
                for (x in 0 until w) {
                    val sx = (x * mw / w).coerceIn(0, mw-1)
                    if (samMask[sy][sx]) mask[y][x] = true
                }
            }
        }

        // 2) include faces
        for (f in faces) {
            val r = f.boundingBox
            val left = r.left.coerceIn(0, w-1)
            val top = r.top.coerceIn(0, h-1)
            val right = r.right.coerceIn(0, w-1)
            val bottom = r.bottom.coerceIn(0, h-1)
            for (yy in top..bottom) for (xx in left..right) if (yy in 0 until h && xx in 0 until w) mask[yy][xx] = true
        }

        // 3) include pose bounding rectangles (coarse)
        for (p in poseBoxes) {
            // collect landmarks -> bounding
            try {
                val landmarks = p.allPoseLandmarks
                if (landmarks.isNotEmpty()) {
                    var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE
                    for (lm in landmarks) {
                        val lx = lm.position.x.roundToInt().coerceIn(0, w-1)
                        val ly = lm.position.y.roundToInt().coerceIn(0, h-1)
                        minX = min(minX, lx); minY = min(minY, ly); maxX = max(maxX, lx); maxY = max(maxY, ly)
                    }
                    val padX = ((maxX - minX) * 0.2f).roundToInt().coerceAtLeast(8)
                    val padY = ((maxY - minY) * 0.2f).roundToInt().coerceAtLeast(8)
                    val L = (minX - padX).coerceAtLeast(0); val T = (minY - padY).coerceAtLeast(0)
                    val R = (maxX + padX).coerceAtMost(w-1); val B = (maxY + padY).coerceAtMost(h-1)
                    for (yy in T..B) for (xx in L..R) mask[yy][xx] = true
                }
            } catch (_: Exception) {}
        }

        // 4) include near-depth areas (if depth present). We choose near (small depth) as object masks
        if (depth != null) {
            val dh = depth.size; val dw = if (dh > 0) depth[0].size else 0
            // compute threshold as (min + range*0.34)
            var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE
            for (r in depth) for (v in r) { mn = min(mn, v); mx = max(mx, v) }
            val range = if (mx - mn < 1e-6f) 1f else mx - mn
            val th = mn + range * 0.34f
            for (y in 0 until h) {
                val sy = (y * dh / h).coerceIn(0, dh-1)
                for (x in 0 until w) {
                    val sx = (x * dw / w).coerceIn(0, dw-1)
                    if (depth[sy][sx] < th) mask[y][x] = true
                }
            }
        }

        // Dilate small mask so warps look smoother
        dilateMask(mask, 2)
        // If mask is empty, ensure small center fallback so user sees effect
        var cnt = 0
        val total = w * h
        mask.forEach { row -> row.forEach { if (it) cnt++ } }
        if (cnt < max(1, total / 500)) { // <0.2%
            val cx = w / 2; val cy = h / 2
            val rw = (w * 0.25).coerceAtLeast(20); val rh = (h * 0.25).coerceAtLeast(20)
            val L = (cx - rw/2).coerceAtLeast(0); val R = (cx + rw/2).coerceAtMost(w-1)
            val T = (cy - rh/2).coerceAtLeast(0); val B = (cy + rh/2).coerceAtMost(h-1)
            for (yy in T..B) for (xx in L..R) mask[yy][xx] = true
        }
        return mask
    }

    private fun dilateMask(mask: Array<BooleanArray>, r: Int) {
        val h = mask.size; val w = mask[0].size
        val tmp = Array(h) { BooleanArray(w) }
        for (y in 0 until h) for (x in 0 until w) if (mask[y][x]) {
            for (dy in -r..r) for (dx in -r..r) {
                val ny = y + dy; val nx = x + dx
                if (ny in 0 until h && nx in 0 until w) tmp[ny][nx] = true
            }
        }
        for (y in 0 until h) for (x in 0 until w) mask[y][x] = tmp[y][x]
    }

    private fun maskDensity(mask: Array<BooleanArray>): Float {
        var cnt = 0; var tot = 0
        mask.forEach { r -> r.forEach { if (it) cnt++; tot++ } }
        return cnt.toFloat() / max(1f, tot.toFloat())
    }

    // ---------------- offsets & warping ----------------
    private fun computeOffsetsFromMask(mask: Array<BooleanArray>, enhanced: Boolean): FloatArray {
        val h = mask.size; val w = mask[0].size
        val tile = max(16, w / 16)
        val tilesY = (h + tile - 1) / tile
        val tilesX = (w + tile - 1) / tile
        val tileOffsets = Array(tilesY) { FloatArray(tilesX) }
        for (ty in 0 until tilesY) {
            val y0 = ty * tile; val y1 = min(h, y0 + tile)
            for (tx in 0 until tilesX) {
                val x0 = tx * tile; val x1 = min(w, x0 + tile)
                var cnt = 0; var total = 0
                for (yy in y0 until y1) for (xx in x0 until x1) { total++; if (mask[yy][xx]) cnt++ }
                val dens = if (total == 0) 0f else cnt.toFloat() / total.toFloat()
                val base = if (enhanced) 0.14f else 0.07f
                var v = (1f + dens * 3f) * base * w
                if ((tx + ty) % 2 == 0) v = -v
                tileOffsets[ty][tx] = v
            }
        }
        // project to rows
        val rowOffsets = FloatArray(h)
        for (y in 0 until h) {
            val ty = y / tile
            var s = 0f; var c = 0
            for (tx in 0 until tilesX) { s += tileOffsets[ty][tx]; c++ }
            rowOffsets[y] = if (c == 0) 0f else s / c
        }
        // smooth
        val out = FloatArray(h)
        for (i in 0 until h) {
            var s = 0f; var c = 0
            for (k in -2..2) {
                val idx = (i + k).coerceIn(0, h-1)
                s += rowOffsets[idx]; c++
            }
            out[i] = (s / c).coerceIn(-w * MAX_ROW_SHIFT_FRACTION, w * MAX_ROW_SHIFT_FRACTION)
        }
        return out
    }

    private fun warpSmallAndColorize(src: Bitmap, rowOffsets: FloatArray,
                                     depth: Array<FloatArray>?, mask: Array<BooleanArray>, enhanced: Boolean): Bitmap {
        // warp horizontally per-row
        val w = src.width; val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val srcRow = IntArray(w)
        val tmp = IntArray(w)
        for (y in 0 until h) {
            src.getPixels(srcRow, 0, w, 0, y, w, 1)
            val off = rowOffsets[y].roundToInt().coerceIn(-w/2, w/2)
            if (off == 0) { out.setPixels(srcRow, 0, w, 0, y, w, 1); continue }
            if (off > 0) {
                System.arraycopy(srcRow, 0, tmp, off, w - off)
                for (i in 0 until off) tmp[i] = srcRow[0]
            } else {
                val o = -off
                System.arraycopy(srcRow, o, tmp, 0, w - o)
                for (i in w - o until w) tmp[i] = srcRow[w - 1]
            }
            out.setPixels(tmp, 0, w, 0, y, w, 1)
        }

        // apply depth-aware darkening/bluish toning
        val pixels = IntArray(w * h)
        out.getPixels(pixels, 0, w, 0, 0, w, h)
        var minD = Float.MAX_VALUE; var maxD = -Float.MAX_VALUE
        if (depth != null) {
            for (r in depth) for (v in r) { minD = min(minD, v); maxD = max(maxD, v) }
            if (maxD - minD < 1e-6f) { minD = 0f; maxD = 1f }
        }
        val darkFactor = if (enhanced) 0.48f else 0.28f
        val blueBias = if (enhanced) 0.11f else 0.06f
        val dh = depth?.size ?: h; val dw = if (depth != null && depth.isNotEmpty()) depth[0].size else w

        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                var r = (pixels[idx] shr 16) and 0xFF
                var g = (pixels[idx] shr 8) and 0xFF
                var b = pixels[idx] and 0xFF
                val depthNorm = if (depth != null) {
                    val sy = (y * dh / h).coerceIn(0, dh - 1)
                    val sx = (x * dw / w).coerceIn(0, dw - 1)
                    ((depth[sy][sx] - minD) / (maxD - minD)).coerceIn(0f, 1f)
                } else 0.5f
                val maskVal = if (mask[y][x]) 1f else 0f
                val mul = 1f - (depthNorm * darkFactor * (1f - 0.35f*maskVal))
                r = (r * mul * (1f - blueBias * depthNorm)).roundToInt().coerceIn(0,255)
                g = (g * mul * (1f - blueBias * depthNorm * 0.5f)).roundToInt().coerceIn(0,255)
                b = (b * mul * (1f + blueBias * depthNorm)).roundToInt().coerceIn(0,255)
                pixels[idx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    // ---------------- Face & Pose helpers ----------------
    private fun runFaceDetector(bmp: Bitmap): List<Face> {
        return try {
            val input = InputImage.fromBitmap(bmp, 0)
            val task = faceDetector.process(input)
            Tasks.await(task)
        } catch (e: Exception) {
            Logger.e(TAG, "face detect failed: ${e.message}")
            emptyList()
        }
    }

    private fun runPoseDetector(bmp: Bitmap): List<Pose> {
        return try {
            val input = InputImage.fromBitmap(bmp, 0)
            val task = poseDetector.process(input)
            Tasks.await(task)
        } catch (e: Exception) {
            Logger.e(TAG, "pose detect failed: ${e.message}")
            emptyList()
        }
    }

    // ---------------- Capture flow ----------------
    private fun capturePressed() {
        val ic = imageCapture ?: run { Toast.makeText(this, "Capture not ready", Toast.LENGTH_SHORT).show(); return }
        val outFile = File(cacheDir, "cap_${System.currentTimeMillis()}.jpg")
        val opts = ImageCapture.OutputFileOptions.Builder(outFile).build()
        ic.takePicture(opts, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onError(exception: ImageCaptureException) {
                Logger.e(TAG, "capture error: ${exception.message}")
                Toast.makeText(this@CameraActivity, "Capture error", Toast.LENGTH_SHORT).show()
            }
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                // read raw and process full-res with lastSmallMask/offsets
                bgScope.launch {
                    try {
                        val raw = BitmapFactory.decodeFile(outFile.absolutePath)
                        val processed = if (lastSmallMask != null && lastSmallOffsets != null) {
                            processFullResUsingLast(raw, lastSmallMask!!, lastSmallOffsets!!)
                        } else {
                            applyDepthDarkeningFull(raw, null, Array(raw.height) { BooleanArray(raw.width) }, true)
                        }
                        saveBitmapToGallery(processed)
                        runOnUiThread { Toast.makeText(this@CameraActivity, "Saved processed", Toast.LENGTH_SHORT).show() }
                    } catch (t: Throwable) {
                        Logger.e(TAG, "post-capture process failed: ${t.message}")
                    }
                }
            }
        })
    }

    private fun processFullResUsingLast(raw: Bitmap, maskSmall: Array<BooleanArray>, offsetsSmall: FloatArray): Bitmap {
        try {
            val fw = raw.width; val fh = raw.height
            val smH = maskSmall.size; val smW = maskSmall[0].size
            // scale mask to full
            val maskFull = Array(fh) { BooleanArray(fw) }
            for (y in 0 until fh) {
                val sy = (y * smH / fh).coerceIn(0, smH - 1)
                for (x in 0 until fw) {
                    val sx = (x * smW / fw).coerceIn(0, smW - 1)
                    maskFull[y][x] = maskSmall[sy][sx]
                }
            }
            // expand offsets
            val offsetsFull = FloatArray(fh)
            for (y in 0 until fh) {
                val sy = (y * offsetsSmall.size / fh).coerceIn(0, offsetsSmall.size - 1)
                offsetsFull[y] = offsetsSmall[sy] * (fw.toFloat() / smW.toFloat())
            }
            val tile = max(24, fw / 16)
            val warped = warpFullResByRows(raw, offsetsFull)
            val final = applyDepthDarkeningFull(warped, null, maskFull, TwistedApp.instance.settingsPrefs.getBoolean("enhanced_camera_mode", true))
            return final
        } catch (e: Exception) {
            Logger.e(TAG, "processFullResUsingLast failed: ${e.message}")
            return raw
        }
    }

    private fun warpFullResByRows(src: Bitmap, rowOffsets: FloatArray): Bitmap {
        val w = src.width; val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val row = IntArray(w); val tmp = IntArray(w)
        for (y in 0 until h) {
            src.getPixels(row, 0, w, 0, y, w, 1)
            val off = rowOffsets[y].roundToInt().coerceIn(-w/2, w/2)
            if (off == 0) { out.setPixels(row, 0, w, 0, y, w, 1); continue }
            if (off > 0) {
                System.arraycopy(row, 0, tmp, off, w - off)
                for (i in 0 until off) tmp[i] = row[0]
            } else {
                val o = -off
                System.arraycopy(row, o, tmp, 0, w - o)
                for (i in w - o until w) tmp[i] = row[w - 1]
            }
            out.setPixels(tmp, 0, w, 0, y, w, 1)
        }
        return out
    }

    private fun applyDepthDarkeningFull(src: Bitmap, depth: Array<FloatArray>?, mask: Array<BooleanArray>, enhanced: Boolean): Bitmap {
        val w = src.width; val h = src.height
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val pix = IntArray(w * h); out.getPixels(pix, 0, w, 0, 0, w, h)
        var minD = Float.MAX_VALUE; var maxD = -Float.MAX_VALUE
        if (depth != null) for (r in depth) for (v in r) { minD = min(minD, v); maxD = max(maxD, v) }
        if (maxD - minD < 1e-6f) { minD = 0f; maxD = 1f }
        val darkFactor = if (enhanced) 0.45f else 0.25f
        val blueShift = if (enhanced) 0.08f else 0.04f
        val dh = depth?.size ?: h; val dw = if (depth != null && depth.isNotEmpty()) depth[0].size else w
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                var r = (pix[idx] shr 16) and 0xFF
                var g = (pix[idx] shr 8) and 0xFF
                var b = pix[idx] and 0xFF
                val depthNorm = if (depth != null) {
                    val sy = (y * dh / h).coerceIn(0, dh - 1)
                    val sx = (x * dw / w).coerceIn(0, dw - 1)
                    ((depth[sy][sx] - minD) / (maxD - minD)).coerceIn(0f, 1f)
                } else 0.5f
                val maskVal = if (mask[y][x]) 1f else 0f
                val mul = 1f - (depthNorm * darkFactor * (1f - 0.35f*maskVal))
                r = (r * mul * (1f - blueShift * depthNorm)).roundToInt().coerceIn(0,255)
                g = (g * mul * (1f - blueShift * depthNorm * 0.5f)).roundToInt().coerceIn(0,255)
                b = (b * mul * (1f + blueShift * depthNorm)).roundToInt().coerceIn(0,255)
                pix[idx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        out.setPixels(pix, 0, w, 0, 0, w, h)
        return out
    }

    private fun saveBitmapToGallery(bmp: Bitmap) {
        try {
            val resolver = contentResolver
            val fname = "twisted_capture_${System.currentTimeMillis()}.jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fname)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 92, out) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                FileLogger.d(applicationContext, TAG, "Saved processed capture: $uri")
            } else {
                Logger.e(TAG, "MediaStore insert returned null")
            }
        } catch (e: Exception) {
            Logger.e(TAG, "saveBitmapToGallery failed: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            midasInterpreter?.close()
            mobileSamEncoder?.close()
            mobileSamDecoder?.close()
        } catch (_: Exception) {}
        bgScope.cancel()
        cameraExecutor.shutdown()
    }
}
