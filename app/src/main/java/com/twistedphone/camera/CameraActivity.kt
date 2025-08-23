package com.twistedphone.camera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.*
import android.media.Image
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
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
import kotlinx.coroutines.*
import org.tensorflow.lite.Interpreter
import com.twistedphone.util.FileLogger
import com.twistedphone.R
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * CameraActivity: unified version that
 * - loads MiDaS and MobileSAM if present (files/models)
 * - runs MiDaS adaptively (handles UINT8 or FLOAT32 input/output)
 * - runs MobileSAM encoder+decoder if present (simple center-point query)
 * - falls back to a simple luminance-based mask when MobileSAM is absent
 * - warps only masked pixels based on depth-derived offsets
 * - has a DUMP MODELS button that runs TFLiteInspector.dumpModelsInAppModelsDir(context)
 */
class CameraActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "CameraActivity"
        private const val PROCESSING_MAX_WIDTH = 480 // smaller to keep frame-time reasonable
        private const val MAX_SHIFT_PX = 40 // how much rows shift at most
    }

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: ImageView
    private lateinit var btnCapture: Button
    private lateinit var btnToggle: Button

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    // optional tflite interpreters
    private var midasInterpreter: Interpreter? = null
    private var mobileSamEncoder: Interpreter? = null
    private var mobileSamDecoder: Interpreter? = null

    // last computed outputs (used by capture or UI)
    private var lastMask: Array<BooleanArray>? = null
    private var lastOffsets: FloatArray? = null

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
        btnToggle = findViewById(R.id.btnToggle)

        // overlay image view (draw warped frame on top of preview)
        overlayView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.VISIBLE
        }
        (previewView.parent as? FrameLayout)?.addView(overlayView)

        btnCapture.setOnClickListener { takePicture() }
        btnToggle.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            bindCameraUseCases()
        }

        // Add Dump Models button programmatically (top-right) so you can paste output easily
        val dumpButton = Button(this).apply {
            text = "DUMP MODELS"
            textSize = 12f
            val params = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            params.gravity = Gravity.TOP or Gravity.END
            params.topMargin = 8
            params.rightMargin = 8
            layoutParams = params
            setOnClickListener { onDumpModelsClicked() }
        }
        (previewView.parent as? FrameLayout)?.addView(dumpButton)

        // load optional models on background thread
        scope.launch { loadModelsIfPresent() }

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
            FileLogger.e(this, TAG, "bindCameraUseCases error: ${e.message}")
        }
    }

    // ---------------------------
    // Frame analysis pipeline
    // ---------------------------
    private fun analyzeFrame(proxy: ImageProxy) {
        val mediaImage = proxy.image ?: run { proxy.close(); return }
        val bmp = imageToBitmap(mediaImage, proxy.imageInfo.rotationDegrees, PROCESSING_MAX_WIDTH) ?: run { proxy.close(); return }

        scope.launch {
            try {
                val depth = if (midasInterpreter != null) runMiDaS(bmp) else luminanceDepthApprox(bmp)
                val maskFromSam = runMobileSamIfAvailable(bmp)
                val mask = maskFromSam ?: runMaskFallback(bmp)

                val offsets = computeRowOffsetsFromDepth(depth, bmp.width, bmp.height)

                lastMask = mask
                lastOffsets = offsets

                val warped = warpBitmapFast(bmp, offsets, mask)
                val final = applyAtmosphere(warped, mask, enhancedCamera)

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
    private fun imageToBitmap(image: Image, rotation: Int, maxWidth: Int): Bitmap? {
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
            if (bmp == null) return null
            val aspect = bmp.height.toFloat() / bmp.width.toFloat()
            val targetW = maxWidth
            val targetH = (targetW * aspect).toInt()
            bmp = Bitmap.createScaledBitmap(bmp, targetW, targetH, true)
            if (rotation != 0) {
                val matrix = Matrix()
                matrix.postRotate(rotation.toFloat())
                val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                bmp.recycle()
                bmp = rotated
            }
            bmp
        } catch (e: Exception) {
            FileLogger.e(this, TAG, "imageToBitmap failed: ${e.message}")
            null
        }
    }

    // ---------------------------
    // Model loading (MiDaS & MobileSAM)
    // ---------------------------
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
                FileLogger.d(this@CameraActivity, TAG, "midas.tflite not found; using luminance fallback")
            }

            val enc = File(mdir, "MobileSam_MobileSAMEncoder.tflite")
            val dec = File(mdir, "MobileSam_MobileSAMDecoder.tflite")
            if (enc.exists() && dec.exists()) {
                FileLogger.d(this@CameraActivity, TAG, "MobileSAM encoder+decoder present")
                mobileSamEncoder = Interpreter(mapFile(enc))
                mobileSamDecoder = Interpreter(mapFile(dec))
                FileLogger.d(this@CameraActivity, TAG, "MobileSAM loaded; enc input shape: ${mobileSamEncoder?.getInputTensor(0)?.shape()?.joinToString()}, dec inputs: ${mobileSamDecoder?.inputTensorCount}")
            } else {
                FileLogger.d(this@CameraActivity, TAG, "MobileSAM files missing; falling back to lightweight masks")
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

    // ---------------------------
    // MiDaS runtime (adaptive to dtype/shapes)
    // ---------------------------
    private suspend fun runMiDaS(bmp: Bitmap): FloatArray = withContext(Dispatchers.Default) {
        val interp = midasInterpreter ?: return@withContext luminanceDepthApprox(bmp)
        try {
            val inTensor = interp.getInputTensor(0)
            val inShape = inTensor.shape() // e.g. [1,h,w,3] or [1,3,h,w]
            val modelW: Int
            val modelH: Int
            if (inShape.size == 4 && inShape[1] == 3) { // [1,3,h,w]
                modelW = inShape[3]; modelH = inShape[2]
            } else if (inShape.size == 4 && inShape[3] == 3) { // [1,h,w,3]
                modelW = inShape[2]; modelH = inShape[1]
            } else {
                modelW = PROCESSING_MAX_WIDTH; modelH = PROCESSING_MAX_WIDTH * bmp.height / bmp.width
            }
            val resized = Bitmap.createScaledBitmap(bmp, modelW, modelH, true)
            val inputType = inTensor.dataType()
            // Prepare input buffer
            val inputArray: Any = when (inputType) {
                org.tensorflow.lite.DataType.UINT8 -> {
                    // ByteBuffer of bytes
                    val bb = ByteBuffer.allocateDirect(modelW * modelH * 3)
                    bb.order(ByteOrder.nativeOrder())
                    val pixels = IntArray(modelW * modelH)
                    resized.getPixels(pixels, 0, modelW, 0, 0, modelW, modelH)
                    for (p in pixels) {
                        bb.put((p shr 16 and 0xFF).toByte())
                        bb.put((p shr 8 and 0xFF).toByte())
                        bb.put((p and 0xFF).toByte())
                    }
                    bb.rewind(); bb
                }
                else -> {
                    // FLOAT32
                    val arr = Array(1) { Array(modelH) { FloatArray(modelW * 3) } } // shape [1,h,w*3] => we will flatten later to [1,h,w,3] automatically by interpreter
                    // but interpreter expects [1,h,w,3] sequence: easiest is FloatArray(1*modelH*modelW*3)
                    val farr = FloatArray(modelH * modelW * 3)
                    var idx = 0
                    val pixels = IntArray(modelW * modelH)
                    resized.getPixels(pixels, 0, modelW, 0, 0, modelW, modelH)
                    for (c in pixels) {
                        farr[idx++] = ((c shr 16 and 0xFF) / 255.0f)
                        farr[idx++] = ((c shr 8 and 0xFF) / 255.0f)
                        farr[idx++] = ((c and 0xFF) / 255.0f)
                    }
                    arrayOf(farr) // wrap so interpreter accepts
                }
            }

            // Prepare output structure dynamically
            val outTensor = interp.getOutputTensor(0)
            val outShape = outTensor.shape()
            val outSize = outShape.reduce { acc, i -> acc * i }
            val outType = outTensor.dataType()

            val outBuffer: Any = when (outType) {
                org.tensorflow.lite.DataType.FLOAT32 -> FloatArray(outSize)
                org.tensorflow.lite.DataType.UINT8 -> ByteArray(outSize)
                else -> FloatArray(outSize)
            }

            // Run model
            try {
                interp.run(inputArray, outBuffer)
            } catch (ex: Exception) {
                FileLogger.e(this@CameraActivity, TAG, "MiDaS run failed: ${ex.message}")
                return@withContext luminanceDepthApprox(resized)
            }

            // Convert output to float array normalized 0..1
            val depthFloat = FloatArray(outSize)
            if (outType == org.tensorflow.lite.DataType.FLOAT32) {
                val fa = outBuffer as FloatArray
                for (i in fa.indices) depthFloat[i] = fa[i]
            } else if (outType == org.tensorflow.lite.DataType.UINT8) {
                val ba = outBuffer as ByteArray
                for (i in ba.indices) depthFloat[i] = (ba[i].toInt() and 0xFF) / 255f
            } else {
                val fa = outBuffer as FloatArray
                for (i in fa.indices) depthFloat[i] = fa[i]
            }

            // If output size matches modelW*modelH*1 -> return cropped/resized to modelW*modelH
            // Flattening: if output has channel dim we try to pick first channel
            if (outShape.size >= 3 && outShape.lastIndex >= 2) {
                // If shape like [1, h, w, 1] or [1, h, w] etc.
                // We return array corresponding to h*w
                val h = outShape[outShape.size - 3]
                val w = outShape[outShape.size - 2]
                // If sizes equal, ok. Else try nearest neighbor resizing to bitmap dims.
                if (h == modelH && w == modelW) {
                    return@withContext depthFloat
                } else {
                    // resize depthFloat (nearest) to modelH x modelW
                    val resizedDepth = FloatArray(modelH * modelW)
                    for (y in 0 until modelH) {
                        val ry = (y.toFloat() * h / modelH).toInt().coerceIn(0, h - 1)
                        for (x in 0 until modelW) {
                            val rx = (x.toFloat() * w / modelW).toInt().coerceIn(0, w - 1)
                            val idx = ry * w + rx
                            resizedDepth[y * modelW + x] = depthFloat.getOrElse(idx) { 0f }
                        }
                    }
                    return@withContext resizedDepth
                }
            }

            // fallback
            depthFloat
        } catch (e: Exception) {
            FileLogger.e(this@CameraActivity, TAG, "runMiDaS error: ${e.message}")
            return@withContext luminanceDepthApprox(bmp)
        }
    }

    // Simple luminance->depth fallback (very rough)
    private fun luminanceDepthApprox(bmp: Bitmap): FloatArray {
        val w = bmp.width; val h = bmp.height
        val pixels = IntArray(w*h); bmp.getPixels(pixels,0,w,0,0,w,h)
        val depths = FloatArray(w*h)
        var sum = 0f
        for (i in pixels.indices) {
            val p = pixels[i]
            val lum = (0.21f * ((p shr 16) and 0xFF) + 0.72f * ((p shr 8) and 0xFF) + 0.07f * (p and 0xFF))/255f
            depths[i] = lum
            sum += lum
        }
        val avg = sum/(w*h)
        for (i in depths.indices) depths[i] = (depths[i] / max(1e-6f, avg)) // relative
        return depths
    }

    // ---------------------------
    // MobileSAM integration (simple center-point caller)
    // ---------------------------
    private suspend fun runMobileSamIfAvailable(bmp: Bitmap): Array<BooleanArray>? = withContext(Dispatchers.Default) {
        val enc = mobileSamEncoder ?: return@withContext null
        val dec = mobileSamDecoder ?: return@withContext null
        try {
            // Encoder input shape -> typically [1,1024,1024,3] based on your inspector
            val inShape = enc.getInputTensor(0).shape()
            if (inShape.size != 4) return@withContext null
            val modelW = inShape[2] // assuming [1,h,w,3] or [1,1024,1024,3]
            val modelH = inShape[1]
            val inW = if (inShape[3] == 3) inShape[2] else modelW
            val inH = if (inShape[3] == 3) inShape[1] else modelH

            val resized = Bitmap.createScaledBitmap(bmp, inW, inH, true)

            // Prepare encoder input: FLOAT32 [1,h,w,3], normalized 0..1
            val pixels = IntArray(inW*inH); resized.getPixels(pixels,0,inW,0,0,inW,inH)
            val encInput = FloatArray(inW*inH*3)
            var idx = 0
            for (p in pixels) {
                encInput[idx++] = ((p shr 16 and 0xFF)/255f)
                encInput[idx++] = ((p shr 8 and 0xFF)/255f)
                encInput[idx++] = ((p and 0xFF)/255f)
            }

            // Encoder output shape based on inspector: [1,64,64,256]
            val encOutTensor = enc.getOutputTensor(0)
            val encOutShape = encOutTensor.shape()
            val encOutSize = encOutShape.reduce { a,b -> a*b }
            val encOut = FloatArray(encOutSize)

            // run encoder
            enc.run(arrayOf(encInput), encOut) // interpreter accepts object arrays for multi-dim

            // Decoder: expects [image_embeddings, point_coords, point_labels]
            // Prepare point_coords: we'll ask center point: normalized (x,y)
            val targetMaskH = 256
            val targetMaskW = 256
            val pointCoords = FloatArray(1 * 2 * 2) // [1,2,2] two points
            // center point
            pointCoords[0] = 0.5f; pointCoords[1] = 0.5f
            // dummy second point (ignored)
            pointCoords[2] = 0f; pointCoords[3] = 0f
            val pointLabels = FloatArray(1 * 2)
            pointLabels[0] = 1f; pointLabels[1] = 0f

            // decoder input list: embeddings must be shaped as expected by decoder - we pass encOut directly
            val decOutMask = FloatArray(1 * targetMaskH * targetMaskW * 1)
            val decOutScores = FloatArray(1 * 1)
            val outputs = hashMapOf<Int, Any>(0 to decOutMask, 1 to decOutScores)
            val inputs = arrayOf(encOut, pointCoords, pointLabels)

            // Some interpreters require multi-input runner
            try {
                dec.runForMultipleInputsOutputs(inputs, outputs)
            } catch (ex: Exception) {
                FileLogger.e(this@CameraActivity, TAG, "MobileSAM decoder run failed: ${ex.message}")
                return@withContext null
            }

            // decOutMask = shape [1,256,256,1] float32
            // convert mask to boolean[][] of bmp size (we will nearest-resize mask to bmp dims)
            val maskFloat = decOutMask
            val mask2d = Array(targetMaskH) { BooleanArray(targetMaskW) }
            for (y in 0 until targetMaskH) {
                for (x in 0 until targetMaskW) {
                    val valf = maskFloat[y * targetMaskW + x]
                    mask2d[y][x] = valf > 0.4f
                }
            }

            // Resize mask2d to bmp width/height (nearest)
            val outH = bmp.height; val outW = bmp.width
            val bigMask = Array(outH) { BooleanArray(outW) }
            for (y in 0 until outH) {
                val ry = (y.toFloat() * targetMaskH / outH).toInt().coerceIn(0, targetMaskH - 1)
                for (x in 0 until outW) {
                    val rx = (x.toFloat() * targetMaskW / outW).toInt().coerceIn(0, targetMaskW - 1)
                    bigMask[y][x] = mask2d[ry][rx]
                }
            }

            return@withContext bigMask
        } catch (e: Exception) {
            FileLogger.e(this@CameraActivity, TAG, "runMobileSamIfAvailable error: ${e.message}")
            return@withContext null
        }
    }

    // Lightweight fallback mask (luminance threshold + simple morphological area grow)
    private fun runMaskFallback(bmp: Bitmap): Array<BooleanArray> {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w*h); bmp.getPixels(px,0,w,0,0,w,h)
        val lums = FloatArray(w*h)
        var sum = 0f
        for (i in px.indices) {
            val p = px[i]
            val lum = (0.21f * ((p shr 16) and 0xFF) + 0.72f * ((p shr 8) and 0xFF) + 0.07f * (p and 0xFF))/255f
            lums[i] = lum
            sum += lum
        }
        val avg = sum / (w*h)
        val mask = Array(h) { BooleanArray(w) }
        val thresh = avg * 1.02f
        for (y in 0 until h) for (x in 0 until w) {
            mask[y][x] = lums[y*w + x] > thresh
        }
        // small morphology: fill small holes horizontally
        for (y in 0 until h) {
            var run = 0
            for (x in 0 until w) {
                if (mask[y][x]) run++ else run = 0
                if (run in 2..3) { // extend previous tiny runs
                    for (k in 0 until run) mask[y][x-k] = true
                }
            }
        }
        return mask
    }

    // ---------------------------
    // Depth -> row offsets
    // ---------------------------
    private fun computeRowOffsetsFromDepth(depth: FloatArray, w: Int, h: Int): FloatArray {
        // If depth length matches w*h, treat as per-pixel depth
        val offsets = FloatArray(h)
        try {
            if (depth.size >= w*h) {
                // compute per-row average depth
                for (y in 0 until h) {
                    var sum = 0f
                    for (x in 0 until w) sum += depth[y*w + x]
                    val avg = sum / w
                    // map avg depth to a shift: nearer -> larger shift (we invert because depth ~ larger -> far? depends on model)
                    // we normalize using typical range
                    val shift = ( (1f - avg).coerceIn(0f, 1f) ) * MAX_SHIFT_PX
                    offsets[y] = shift
                }
                // smooth offsets vertically
                val sm = FloatArray(h)
                for (y in 0 until h) {
                    var s = 0f; var n = 0f
                    for (k in -3..3) {
                        val yy = (y+k).coerceIn(0, h-1)
                        val wgh = 1f / (1 + abs(k))
                        s += offsets[yy] * wgh; n += wgh
                    }
                    sm[y] = s / n
                }
                return sm
            }
        } catch (e: Exception) {
            FileLogger.e(this, TAG, "computeRowOffsetsFromDepth error: ${e.message}")
        }
        // fallback gradient
        for (y in 0 until h) offsets[y] = (y.toFloat()/h.toFloat() - 0.5f) * MAX_SHIFT_PX
        return offsets
    }

    // ---------------------------
    // Warping + final color / atmosphere
    // ---------------------------
    private fun warpBitmapFast(src: Bitmap, offsets: FloatArray, mask: Array<BooleanArray>): Bitmap {
        val w = src.width; val h = src.height
        val srcPixels = IntArray(w*h); src.getPixels(srcPixels, 0, w, 0, 0, w, h)
        val dstPixels = IntArray(w*h)
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

    private fun applyAtmosphere(src: Bitmap, mask: Array<BooleanArray>, enhanced: Boolean): Bitmap {
        val w = src.width; val h = src.height
        val pixels = IntArray(w*h); src.getPixels(pixels,0,w,0,0,w,h)
        val out = IntArray(w*h)
        // If enhanced: stronger darken and desaturate on mask interior, lighten/blur edges? We'll do simpler: desat background and slightly color-shift masked object
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y*w + x
                val p = pixels[idx]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                if (mask[y][x]) {
                    // object: slight color shift + darker
                    val nr = (r * (if (enhanced) 0.8f else 0.9f)).toInt().coerceIn(0,255)
                    val ng = (g * (if (enhanced) 0.7f else 0.9f)).toInt().coerceIn(0,255)
                    val nb = (b * (if (enhanced) 0.9f else 0.95f)).toInt().coerceIn(0,255)
                    out[idx] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
                } else {
                    // background: desaturate and dim
                    val lum = (0.3f*r + 0.59f*g + 0.11f*b).toInt()
                    val factor = if (enhanced) 0.6f else 0.8f
                    val v = (lum * factor).toInt().coerceIn(0,255)
                    out[idx] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                }
            }
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(out,0,w,0,0,w,h)
        return bmp
    }

    // ---------------------------
    // Capture pipeline - uses lastMask/lastOffsets to save warped image
    // ---------------------------
    private fun takePicture() {
        val ic = imageCapture ?: run { Toast.makeText(this, "Capture not ready", Toast.LENGTH_SHORT).show(); return }
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
                            if (bmp == null) bmp = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)

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
                            // scale mask to full-res (nearest)
                            val fullMask = Array(bmp.height) { BooleanArray(bmp.width) }
                            for (y in 0 until bmp.height) {
                                val ry = (y.toFloat() * small.height / bmp.height).toInt().coerceIn(0, small.height - 1)
                                for (x in 0 until bmp.width) {
                                    val rx = (x.toFloat() * small.width / bmp.width).toInt().coerceIn(0, small.width - 1)
                                    fullMask[y][x] = mask[ry][rx]
                                }
                            }
                            val warped = warpBitmapFast(bmp, scaledOffsets, fullMask)
                            val final = applyAtmosphere(warped, fullMask, enhancedCamera)
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

    // ---------------------------
    // Dump models button -> runs TFLiteInspector.dumpModelsInAppModelsDir(context)
    // ---------------------------
    private fun onDumpModelsClicked() {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    FileLogger.d(this@CameraActivity, TAG, "Starting TFLiteInspector.dumpModelsInAppModelsDir")
                    try {
                        TFLiteInspector.dumpModelsInAppModelsDir(this@CameraActivity)
                    } catch (e: Exception) {
                        FileLogger.e(this@CameraActivity, TAG, "TFLiteInspector failed: ${e.message}")
                    }
                    FileLogger.d(this@CameraActivity, TAG, "TFLiteInspector finished")
                }
                // Optionally show toast - real output is in FileLogger and inspector dialog in TFLiteInspector
                withContext(Dispatchers.Main) { Toast.makeText(this@CameraActivity, "Dumped models (check app logs/clipboard)", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                FileLogger.e(this@CameraActivity, TAG, "onDumpModelsClicked failed: ${e.message}")
            }
        }
    }
}
