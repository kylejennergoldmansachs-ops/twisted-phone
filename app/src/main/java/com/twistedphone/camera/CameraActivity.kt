package com.twistedphone.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.media.Image
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import com.twistedphone.R
import com.twistedphone.util.FileLogger
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetector
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.*
import org.tensorflow.lite.Interpreter
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "CameraActivity"
        private const val PROCESSING_MAX_WIDTH = 640 // downscale for analysis
        private const val MAX_ROW_SHIFT_FRACTION = 0.12f
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var cameraExecutor: ExecutorService

    // ML models (may be null if not present/loaded)
    private var midasInterpreter: Interpreter? = null
    private var mobileSamEncoder: Interpreter? = null
    private var mobileSamDecoder: Interpreter? = null

    // ML Kit helpers (initialized elsewhere in your app)
    private lateinit var faceDetector: FaceDetector
    private lateinit var poseDetector: PoseDetector

    // last results (for capture)
    private var lastMask: Array<BooleanArray>? = null
    private var lastOffsets: FloatArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera) // ensure layout exists
        cameraExecutor = Executors.newSingleThreadExecutor()

        // TODO: initialize faceDetector/poseDetector and interpreters (midas, mobilesam)
        // Your existing repo had model loading code elsewhere (ModelDownloadWorker); assume it populates the interpreter fields.

        startCamera()
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val previewView = findViewById<android.view.View>(R.id.previewView) // adapt layout id
                val preview = Preview.Builder().build()
                val selector = CameraSelector.DEFAULT_BACK_CAMERA
                preview.setSurfaceProvider((previewView as androidx.camera.view.PreviewView).surfaceProvider)

                val imageCapture = ImageCapture.Builder()
                    .setTargetRotation(previewView.display?.rotation ?: 0)
                    .setTargetResolution(Size(1280, 720))
                    .build()

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(Size(PROCESSING_MAX_WIDTH, PROCESSING_MAX_WIDTH * 16 / 9))
                    .build()

                analysis.setAnalyzer(cameraExecutor) { proxy ->
                    try {
                        analyzeFrame(proxy)
                    } catch (e: Exception) {
                        FileLogger.e(this@CameraActivity, TAG, "Analyzer exception: ${e.message}")
                        proxy.close()
                    }
                }

                try {
                    provider.unbindAll()
                    provider.bindToLifecycle(this, selector, preview, imageCapture, analysis)
                } catch (e: Exception) {
                    FileLogger.e(this@CameraActivity, TAG, "bindToLifecycle failed: ${e.message}")
                }

            } catch (e: Exception) {
                FileLogger.e(this@CameraActivity, TAG, "Camera start failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(proxy: ImageProxy) {
        val mediaImage = proxy.image ?: run { proxy.close(); return }
        val bitmap = imageToBitmap(mediaImage, proxy.imageInfo.rotationDegrees, PROCESSING_MAX_WIDTH)
        if (bitmap == null) {
            proxy.close(); return
        }

        scope.launch {
            try {
                val depth = if (midasInterpreter != null) runMiDaS(bitmap) else luminanceDepthApprox(bitmap)

                // Try MobileSAM (if available), else fallback to ML Kit rect heuristics
                val maskFromSam = runMobileSamIfAvailable(bitmap)
                val mask = maskFromSam ?: runMaskFallback(bitmap)

                val offsets = computeRowOffsetsFromDepth(depth, bitmap.width, bitmap.height)

                lastMask = mask
                lastOffsets = offsets

                val warped = warpBitmap(bitmap, offsets, mask)
                val final = applyAtmosphere(warped, mask, enhanced = true)

                withContext(Dispatchers.Main) {
                    // update your overlay view (ensure you have an overlayView)
                    try {
                        val overlayView = findViewById<android.widget.ImageView>(R.id.overlayView)
                        overlayView.setImageBitmap(final)
                    } catch (e: Exception) {
                        FileLogger.e(this@CameraActivity, TAG, "UI setImage failed: ${e.message}")
                    }
                }

            } catch (e: Exception) {
                FileLogger.e(this@CameraActivity, TAG, "Frame processing error: ${e.message}")
            } finally {
                proxy.close()
            }
        }
    }

    // Convert YUV_420_888 image to scaled ARGB bitmap
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
            val baos = ByteArrayOutputStream()
            yuv.compressToJpeg(android.graphics.Rect(0, 0, image.width, image.height), 80, baos)
            val bytes = baos.toByteArray()
            var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
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
            FileLogger.e(this, TAG, "imageProxy->bitmap conversion failed: ${e.message}")
            null
        }
    }

    // ---------------------------
    // MobileSAM integration
    // ---------------------------

    /**
     * Try to run MobileSAM using TFLite interpreters (encoder + decoder).
     * Returns mask (h x w) or null if MobileSAM disabled/failed.
     *
     * This inspects decoder input names (defensively) and maps image_embeddings, points, labels, mask_input, has_mask_input, orig_im_size.
     * It uses a grid of center-of-tiles points as automatic prompts (heuristic).
     */
    private suspend fun runMobileSamIfAvailable(bmp: Bitmap): Array<BooleanArray>? = withContext(Dispatchers.Default) {
        val enc = mobileSamEncoder
        val dec = mobileSamDecoder
        if (enc == null || dec == null) return@withContext null

        try {
            // encoder input shape -> scale image accordingly
            val encIn0 = enc.getInputTensor(0)
            val encShape = encIn0.shape() // e.g. [1, H, W, 3]
            if (encShape.size < 3) {
                FileLogger.d(this@CameraActivity, TAG, "Unexpected encoder input shape: ${encShape.joinToString()}")
                return@withContext null
            }
            val targetH = encShape[1]
            val targetW = encShape[2]

            val encInput = bitmapToFloatArrayNHWC(bmp, targetW, targetH)

            // prepare encoder output buffer
            val encOutTensor = enc.getOutputTensor(0)
            val encOutShape = encOutTensor.shape()
            val encOutSize = encOutShape.fold(1) { a, b -> a * b }
            val encOutBuffer = FloatArray(encOutSize)

            try {
                enc.run(encInput, encOutBuffer)
            } catch (e: Exception) {
                FileLogger.e(this@CameraActivity, TAG, "Encoder run failed: ${e.message}")
                return@withContext null
            }

            // inspect decoder inputs
            val decInputCount = dec.inputTensorCount
            val decInputNameToIndex = mutableMapOf<String, Int>()
            for (i in 0 until decInputCount) {
                try {
                    // NOTE: Tensor.name() is Java method -> call name()
                    val n = dec.getInputTensor(i).name().lowercase()
                    decInputNameToIndex[n] = i
                } catch (_: Exception) { /* ignore */ }
            }

            // helper to find by substring
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
                FileLogger.d(this@CameraActivity, TAG, "Decoder missing image_embeddings input. Found: ${decInputNameToIndex.keys}")
                return@withContext null
            }

            // build grid points
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

            val pointCoords = FloatArray(1 * numPoints * 2)
            for (i in 0 until numPoints) {
                pointCoords[i * 2 + 0] = points[i].first
                pointCoords[i * 2 + 1] = points[i].second
            }
            val pointLabels = IntArray(1 * numPoints) { 1 }

            // prepare decoder inputs array (by index order)
            val decInputs = Array<Any?>(decInputCount) { null }

            // put image embedding (flattened encOutBuffer) into the decoder input if shape matches
            decInputs[idxImageEmb] = encOutBuffer

            if (idxPointCoords != null) decInputs[idxPointCoords] = pointCoords
            if (idxPointLabels != null) decInputs[idxPointLabels] = pointLabels

            if (idxOrigSize != null) {
                // some decoders expect [1,2] of orig size (height,width)
                decInputs[idxOrigSize] = intArrayOf(bmp.height, bmp.width)
            }

            if (idxHasMask != null) {
                decInputs[idxHasMask] = intArrayOf(0) // no mask provided
            }

            if (idxMaskInput != null) {
                // supply zeros if decoder wants mask input
                val outTensor = dec.getInputTensor(idxMaskInput)
                val maskShape = outTensor.shape()
                val maskSize = maskShape.fold(1) { a, b -> a * b }
                // default zeros float array
                decInputs[idxMaskInput] = FloatArray(maskSize) { 0f }
            }

            // prepare decoder output buffer by inspecting output tensor
            val outTensor = dec.getOutputTensor(0)
            val outShape = outTensor.shape()
            val outSize = outShape.fold(1) { a, b -> a * b }
            val decOutBuffer = FloatArray(outSize)
            val outputs = hashMapOf<Int, Any>(0 to decOutBuffer)

            // run decoder
            try {
                dec.runForMultipleInputsOutputs(decInputs, outputs)
            } catch (e: Exception) {
                FileLogger.e(this@CameraActivity, TAG, "Decoder run failed: ${e.message}")
                return@withContext null
            }

            // interpret output -> mask
            val maskH: Int
            val maskW: Int
            if (outShape.size == 4) {
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
            for (y in 0 until maskH) {
                for (x in 0 until maskW) {
                    val idx = y * maskW + x
                    val v = flat.getOrNull(idx) ?: 0f
                    val prob = 1f / (1f + kotlin.math.exp(-v))
                    mask[y][x] = prob >= 0.5f
                }
            }

            // upscale mask to bmp size if needed (nearest)
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

    /** Helper: convert Bitmap -> NHWC FloatArray in range [0,1] (1xHWx3) */
    private fun bitmapToFloatArrayNHWC(src: Bitmap, targetW: Int, targetH: Int): FloatArray {
        val scaled = Bitmap.createScaledBitmap(src, targetW, targetH, true)
        val pixels = IntArray(targetW * targetH)
        scaled.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
        val floats = FloatArray(1 * targetH * targetW * 3)
        var idx = 0
        for (y in 0 until targetH) {
            for (x in 0 until targetW) {
                val p = pixels[y * targetW + x]
                val r = ((p shr 16) and 0xFF).toFloat() / 255.0f
                val g = ((p shr 8) and 0xFF).toFloat() / 255.0f
                val b = ((p) and 0xFF).toFloat() / 255.0f
                floats[idx++] = r
                floats[idx++] = g
                floats[idx++] = b
            }
        }
        return floats
    }

    // ---------------------------
    // Fallback mask generation using ML Kit face/pose
    // ---------------------------
    private suspend fun runMaskFallback(bmp: Bitmap): Array<BooleanArray> = withContext(Dispatchers.Default) {
        try {
            val w = bmp.width; val h = bmp.height
            val mask = Array(h) { BooleanArray(w) }
            val input = InputImage.fromBitmap(bmp, 0)

            val facesTask = faceDetector.process(input)
            val faces = try { Tasks.await(facesTask, 500) } catch (e: Exception) { FileLogger.d(this@CameraActivity, TAG, "faceTask failed: ${e.message}"); emptyList<Face>() }
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
            return@withContext Array(bmp.height) { BooleanArray(bmp.width) }
        }
    }

    // ---------------------------
    // Depth -> offsets, warping, atmosphere
    // ---------------------------

    private fun computeRowOffsetsFromDepth(depth: FloatArray, w: Int, h: Int): FloatArray {
        try {
            val rowAvg = FloatArray(h)
            for (y in 0 until h) {
                var sum = 0f
                for (x in 0 until w) sum += depth[y * w + x]
                rowAvg[y] = sum / w
            }
            val minv = rowAvg.minOrNull() ?: 0f
            val maxv = rowAvg.maxOrNull() ?: 0f
            val norm = (maxv - minv).let { if (it <= 0f) 1f else it }
            val offsets = FloatArray(h)
            for (y in 0 until h) {
                val v = (rowAvg[y] - minv) / norm
                offsets[y] = v * w * MAX_ROW_SHIFT_FRACTION
            }
            // smooth
            val smoothed = FloatArray(h)
            val radius = 6
            for (y in 0 until h) {
                var sum = 0f
                var normw = 0f
                for (k in -radius..radius) {
                    val yy = (y + k).coerceIn(0, h - 1)
                    val wght = (1.0f / (1 + kotlin.math.abs(k))).toFloat()
                    sum += offsets[yy] * wght
                    normw += wght
                }
                smoothed[y] = sum / normw
            }
            return smoothed
        } catch (e: Exception) {
            FileLogger.e(this, TAG, "computeRowOffsetsFromDepth failed: ${e.message}")
            return FloatArray(h)
        }
    }

    private fun warpBitmap(src: Bitmap, offsets: FloatArray, mask: Array<BooleanArray>): Bitmap {
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

    private fun applyAtmosphere(src: Bitmap, mask: Array<BooleanArray>, enhanced: Boolean): Bitmap {
        // Simple per-pixel recolor: darken background and slightly shift hue for masked pixels.
        val w = src.width; val h = src.height
        val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                val p = pixels[idx]
                if (mask[y][x]) {
                    // slightly desaturate + shift color for object
                    val r = ((p shr 16) and 0xff)
                    val g = ((p shr 8) and 0xff)
                    val b = (p and 0xff)
                    val nr = (r * 0.95).toInt().coerceIn(0, 255)
                    val ng = (g * 0.9).toInt().coerceIn(0, 255)
                    val nb = (b * 0.9).toInt().coerceIn(0, 255)
                    pixels[idx] = (0xff shl 24) or (nr shl 16) or (ng shl 8) or nb
                } else {
                    // darken background
                    val r = ((p shr 16) and 0xff)
                    val g = ((p shr 8) and 0xff)
                    val b = (p and 0xff)
                    val nr = (r * 0.6).toInt().coerceIn(0, 255)
                    val ng = (g * 0.6).toInt().coerceIn(0, 255)
                    val nb = (b * 0.6).toInt().coerceIn(0, 255)
                    pixels[idx] = (0xff shl 24) or (nr shl 16) or (ng shl 8) or nb
                }
            }
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }

    // -------- lifecycle cleanup
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

    // Dummy placeholder for MiDaS run - replace with your real implementation
    private fun runMiDaS(bmp: Bitmap): FloatArray {
        // In repo there is a Midas interpreter handling; keep minimal placeholder to preserve compile-time type usage.
        val w = bmp.width; val h = bmp.height
        val out = FloatArray(w * h)
        for (i in out.indices) out[i] = 0.5f
        return out
    }

    // Simple luminance fallback for depth
    private fun luminanceDepthApprox(bmp: Bitmap): FloatArray {
        val w = bmp.width; val h = bmp.height
        val out = FloatArray(w * h)
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                val p = pixels[idx]
                val r = ((p shr 16) and 0xff)
                val g = ((p shr 8) and 0xff)
                val b = (p and 0xff)
                val lum = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
                out[idx] = 1f - lum // darker -> farther
            }
        }
        return out
    }
}
