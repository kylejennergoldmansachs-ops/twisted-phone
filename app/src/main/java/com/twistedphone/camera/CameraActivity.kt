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
import java.util.concurrent.Executors
import kotlin.math.*

/**
 * CameraActivity - robust, object-aware distortion camera.
 *
 * Key features:
 * - Robust ImageProxy->Bitmap conversion that respects rowStride/pixelStride (fixes purple stripes).
 * - Adaptive full-res real-time attempt (toggle via prefs "full_res_realtime") or safe low-res processing fallback.
 * - Object-aware tile-based warp: mask produced by Midas depth if present, else Sobel edges fallback.
 * - Darker / bluish atmosphere via color matrix + selective chromatic shift.
 * - Process heavy work off-UI (coroutines + executor); preview shown via ImageView overlay (CENTER_CROP) to avoid tiling/stretching.
 * - Processed capture saved to MediaStore.
 *
 * Usage:
 * - Put optional midas.tflite at filesDir/models/midas.tflite for depth-based masks.
 * - Toggle TwistedApp.instance.settingsPrefs.getBoolean("enhanced_camera_mode", false) to amplify effect.
 * - Toggle TwistedApp.instance.settingsPrefs.getBoolean("full_res_realtime", false) to attempt full-res realtime processing (may be slow).
 */
class CameraActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "CameraActivity"
        // small/fast processing resolution for inference when not using full-res realtime
        private val DEFAULT_PROC_SIZE = Size(512, 384) // larger than 320 to improve object detection quality
        // target high-res for "full-res" attempt; CameraX may clamp to device supported
        private val HIGH_PROC_SIZE = Size(1280, 720)
        // tile size for warp (pixels) — tradeoff quality vs speed
        private const val BASE_TILE_SIZE = 64
        // maximum shift fraction of width
        private const val MAX_SHIFT_FRACTION = 0.22f
        // analysis throttle ms
        private const val MIN_ANALYSIS_INTERVAL_MS = 100L // ~10 FPS max for heavy processing
    }

    private lateinit var root: FrameLayout
    private lateinit var previewView: PreviewView
    private lateinit var overlayView: ImageView
    private lateinit var captureButton: Button

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null

    // background scopes/executors
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val bgScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // optional midas interpreter (may be null)
    private var midasInterpreter: Interpreter? = null

    // last processed bitmap for quick capture
    @Volatile private var lastProcessedPreviewBitmap: Bitmap? = null
    @Volatile private var lastMaskSmall: Array<BooleanArray>? = null
    @Volatile private var lastRowOffsetsSmall: FloatArray? = null

    // time throttling
    @Volatile private var lastAnalysisMs = 0L

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else { Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show(); finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // build UI programmatically (keeps layout simple and avoids layout inflation issues)
        root = FrameLayout(this)
        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        overlayView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.CENTER_CROP // preserve aspect, avoid stretching
            setBackgroundColor(Color.TRANSPARENT)
        }
        captureButton = Button(this).apply {
            text = "CAPTURE"
            setOnClickListener { onCaptureClicked() }
        }

        root.addView(previewView)
        root.addView(overlayView)
        // place button top-left small margin
        val btnParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        btnParams.leftMargin = 16; btnParams.topMargin = 16
        root.addView(captureButton, btnParams)
        setContentView(root)

        // try to load midas model (async)
        bgScope.launch {
            midasInterpreter = tryLoadMidas()
            if (midasInterpreter != null) Logger.d(TAG, "Midas model loaded")
            else Logger.d(TAG, "Midas not available; using edge-based fallback")
        }

        // check permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            startCamera()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
        bgScope.cancel()
        analysisExecutor.shutdownNow()
        try { midasInterpreter?.close() } catch (_: Throwable) {}
        cameraProvider?.unbindAll()
    }

    // --------------------- CameraX setup ---------------------
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindUseCases()
            } catch (e: Exception) {
                Logger.e(TAG, "Camera provider failure: ${e.message}")
                Toast.makeText(this, "Camera start failed", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindUseCases() {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val preview = Preview.Builder()
            .setTargetRotation(previewView.display.rotation)
            .build().also { it.setSurfaceProvider(previewView.surfaceProvider) }

        imageCapture = ImageCapture.Builder()
            .setTargetRotation(previewView.display.rotation)
            .build()

        // Decide analysis resolution based on user preference and device capability
        val fullResRealtime = TwistedApp.instance.settingsPrefs.getBoolean("full_res_realtime", false)
        val targetRes = if (fullResRealtime) HIGH_PROC_SIZE else DEFAULT_PROC_SIZE

        imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(targetRes)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build().also { ia ->
                ia.setAnalyzer(analysisExecutor) { image ->
                    try {
                        val now = System.currentTimeMillis()
                        val minInterval = MIN_ANALYSIS_INTERVAL_MS
                        if (now - lastAnalysisMs < minInterval) {
                            image.close(); return@setAnalyzer
                        }
                        lastAnalysisMs = now
                        // process frame off main thread but we need a fast conversion first
                        bgScope.launch {
                            val procBitmap = convertImageProxyToBitmapSafe(image)
                            if (procBitmap != null) {
                                // main processing pipeline
                                processAndRenderFrame(procBitmap, image)
                            } else {
                                Logger.e(TAG, "convertImageProxyToBitmapSafe returned null")
                            }
                            image.close()
                        }
                    } catch (e: Exception) {
                        Logger.e(TAG, "analyzer error: ${e.message}")
                        image.close()
                    }
                }
            }

        try {
            val selector = CameraSelector.DEFAULT_BACK_CAMERA
            provider.bindToLifecycle(this, selector, preview, imageCapture, imageAnalysis)
        } catch (e: Exception) {
            Logger.e(TAG, "bind camera failed: ${e.message}")
            Toast.makeText(this, "Camera bind failed", Toast.LENGTH_SHORT).show()
        }
    }

    // --------------------- Conversion: ImageProxy -> Bitmap (robust) ---------------------
    /**
     * Correctly convert ImageProxy (YUV_420_888) to RGB Bitmap.
     * Respects rowStride and pixelStride to avoid chroma corruption (purple stripes).
     * Returns a Bitmap sized to the ImageAnalysis target resolution.
     */
    private fun convertImageProxyToBitmapSafe(image: ImageProxy): Bitmap? {
        try {
            val format = image.format
            if (format != ImageFormat.YUV_420_888 && format != ImageFormat.NV21 && format != ImageFormat.YV12) {
                Logger.w(TAG, "Unexpected image format: $format")
            }
            val width = image.width
            val height = image.height
            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]

            val yBuffer = yPlane.buffer
            val uBuffer = uPlane.buffer
            val vBuffer = vPlane.buffer

            val yRowStride = yPlane.rowStride
            val yPixelStride = yPlane.pixelStride
            val uRowStride = uPlane.rowStride
            val uPixelStride = uPlane.pixelStride
            val vRowStride = vPlane.rowStride
            val vPixelStride = vPlane.pixelStride

            val ySize = width * height
            val chromaSize = ySize / 2
            val nv21 = ByteArray(ySize + chromaSize)

            // copy Y
            var pos = 0
            val yRow = ByteArray(yRowStride)
            yBuffer.position(0)
            for (row in 0 until height) {
                yBuffer.get(yRow, 0, yRowStride)
                if (yPixelStride == 1) {
                    System.arraycopy(yRow, 0, nv21, pos, width)
                    pos += width
                } else {
                    var out = pos
                    var i = 0
                    while (i < width) {
                        nv21[out++] = yRow[i * yPixelStride]
                        i++
                    }
                    pos += width
                }
            }

            // copy VU (NV21 expects V then U)
            val chromaHeight = height / 2
            val uRow = ByteArray(uRowStride)
            val vRow = ByteArray(vRowStride)
            uBuffer.position(0); vBuffer.position(0)
            for (row in 0 until chromaHeight) {
                val uRead = min(uRowStride, uRow.size)
                val vRead = min(vRowStride, vRow.size)
                uBuffer.get(uRow, 0, uRead)
                vBuffer.get(vRow, 0, vRead)
                var col = 0
                while (col < width) {
                    val vVal = vRow[col * vPixelStride]
                    val uVal = uRow[col * uPixelStride]
                    nv21[pos++] = vVal
                    nv21[pos++] = uVal
                    col += 2
                }
            }

            // convert NV21 bytes to JPEG then decode bitmap (robust)
            val yuvImage = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            val ok = yuvImage.compressToJpeg(android.graphics.Rect(0, 0, width, height), 85, out)
            if (!ok) return null
            val bytes = out.toByteArray()
            var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null

            // rotate to correct orientation according to rotationDegrees if necessary
            val rotation = image.imageInfo.rotationDegrees
            if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                bmp.recycle()
                bmp = rotated
            }

            // scale to analysis resolution (we set target resolution in ImageAnalysis)
            val target = imageAnalysis?.resolutionInfo?.resolution ?: DEFAULT_PROC_SIZE
            val proc = Bitmap.createScaledBitmap(bmp, target.width, target.height, true)
            bmp.recycle()
            return proc
        } catch (e: Exception) {
            Logger.e(TAG, "convertImageProxyToBitmapSafe failed: ${e.message}")
            return null
        }
    }

    // --------------------- Main processing pipeline ---------------------

    /**
     * Process the small/analysis bitmap, compute object-aware mask, offsets, and produce a processed preview bitmap.
     * - If midas model is available, we attempt depth-based mask; otherwise fall back to Sobel edges.
     * - Produces lastProcessedPreviewBitmap and updates overlayView on UI thread.
     *
     * imageProxy argument is used only to optionally fetch the full-res capture later (we pass it here to know orientation),
     * but we always close the imageProxy at the caller.
     */
    private suspend fun processAndRenderFrame(smallBitmap: Bitmap, imageProxyForInfo: ImageProxy? = null) {
        try {
            val enhanced = TwistedApp.instance.settingsPrefs.getBoolean("enhanced_camera_mode", false)
            val fullResRealtime = TwistedApp.instance.settingsPrefs.getBoolean("full_res_realtime", false)

            // 1) produce mask (depth-based if possible)
            val depthSmall: Array<FloatArray>? = midasInterpreter?.let { runMidasSafely(it, smallBitmap) }
            val maskSmall = computeMaskFromDepthOrEdges(depthSmall, smallBitmap)
            lastMaskSmall = maskSmall

            // 2) compute tile-based offsets for small bitmap
            val rowOffsetsSmall = computeRowOffsetsByTile(maskSmall, smallBitmap.width, smallBitmap.height, enhanced)
            lastRowOffsetsSmall = rowOffsetsSmall

            // 3) warp small bitmap
            val warpedSmall = warpBitmapTiles(smallBitmap, rowOffsetsSmall, tileSizeFromWidth(smallBitmap.width))

            // 4) color grade / atmosphere
            val finalSmall = applyAtmosphereAndSelectiveChromatic(warpedSmall, maskSmall, enhanced)

            // 5) update preview overlay on main thread
            lastProcessedPreviewBitmap = finalSmall
            withContext(Dispatchers.Main) {
                try {
                    overlayView.setImageBitmap(finalSmall)
                } catch (e: Exception) {
                    Logger.e(TAG, "Failed to set overlay bitmap: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "processAndRenderFrame failed: ${e.message}")
        }
    }

    // --------------------- Depth model utilities (optional) ---------------------

    private fun tryLoadMidas(): Interpreter? {
        return try {
            val f = File(filesDir, "models/midas.tflite")
            if (!f.exists()) return null
            val mb = loadMappedFile(f)
            val opts = Interpreter.Options().apply { setNumThreads(2) }
            Interpreter(mb, opts)
        } catch (e: Exception) {
            Logger.e(TAG, "tryLoadMidas error: ${e.message}")
            null
        }
    }

    private fun loadMappedFile(f: File): MappedByteBuffer {
        val fis = FileInputStream(f)
        return fis.channel.map(FileChannel.MapMode.READ_ONLY, 0, f.length())
    }

    /**
     * Run midas safely: tries to map common input shapes and returns a 2D float array [h][w].
     * If inference fails, returns null.
     */
    private fun runMidasSafely(interp: Interpreter, bmp: Bitmap): Array<FloatArray>? {
        return try {
            val inputTensor = interp.getInputTensor(0)
            val shape = inputTensor.shape() // e.g., [1,H,W,3]
            if (shape.size != 4) return null
            val reqH = shape[1]
            val reqW = shape[2]
            val small = Bitmap.createScaledBitmap(bmp, reqW, reqH, true)
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
            val outShape = interp.getOutputTensor(0).shape() // e.g., [1,H,W,1]
            val outH = outShape[1]; val outW = outShape[2]
            val out = Array(outH) { FloatArray(outW) }
            interp.run(input, out)
            out
        } catch (e: Exception) {
            Logger.e(TAG, "runMidasSafely failed: ${e.message}")
            null
        }
    }

    // --------------------- Masking utilities ---------------------

    /**
     * If depth is available (2D float array), build a boolean mask by thresholding & local variance.
     * Otherwise, run Sobel edge detection on the small bitmap to produce a mask representing text/objects.
     */
    private fun computeMaskFromDepthOrEdges(depth: Array<FloatArray>?, bmp: Bitmap): Array<BooleanArray> {
        val w = bmp.width; val h = bmp.height
        val mask = Array(h) { BooleanArray(w) }
        if (depth != null) {
            // normalize and threshold
            var min = Float.MAX_VALUE; var max = -Float.MAX_VALUE
            for (r in depth) for (v in r) { if (v < min) min = v; if (v > max) max = v }
            val range = max - min
            val thresh = min + (if (range < 1e-6f) 0.01f else range * 0.25f)
            for (y in 0 until min(h, depth.size)) {
                val row = depth[y]
                for (x in 0 until min(w, row.size)) {
                    mask[y][x] = row[x] < thresh // many midas variants: nearer objects produce lower values; adjust if inverted
                }
            }
            // small dilation to make objects more robust
            dilateMask(mask, 2)
            return mask
        } else {
            // Sobel edges
            val pixels = IntArray(w * h)
            val gray = IntArray(w * h)
            bmp.getPixels(pixels, 0, w, 0, 0, w, h)
            for (i in pixels.indices) {
                val c = pixels[i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                gray[i] = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
            }
            for (y in 1 until h - 1) {
                for (x in 1 until w - 1) {
                    val gx = -gray[(y - 1) * w + (x - 1)] + gray[(y - 1) * w + (x + 1)] - 2 * gray[y * w + (x - 1)] + 2 * gray[y * w + (x + 1)] - gray[(y + 1) * w + (x - 1)] + gray[(y + 1) * w + (x + 1)]
                    val gy = -gray[(y - 1) * w + (x - 1)] - 2 * gray[(y - 1) * w + x] - gray[(y - 1) * w + (x + 1)] + gray[(y + 1) * w + (x - 1)] + 2 * gray[(y + 1) * w + x] + gray[(y + 1) * w + (x + 1)]
                    val mag = sqrt((gx * gx + gy * gy).toDouble()).toFloat()
                    mask[y][x] = mag > 90f
                }
            }
            dilateMask(mask, 1)
            return mask
        }
    }

    private fun dilateMask(mask: Array<BooleanArray>, radius: Int) {
        val h = mask.size; val w = mask[0].size
        val tmp = Array(h) { BooleanArray(w) }
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (mask[y][x]) {
                    for (dy in -radius..radius) {
                        for (dx in -radius..radius) {
                            val ny = y + dy; val nx = x + dx
                            if (ny in 0 until h && nx in 0 until w) tmp[ny][nx] = true
                        }
                    }
                }
            }
        }
        for (y in 0 until h) for (x in 0 until w) mask[y][x] = tmp[y][x]
    }

    // --------------------- Tile offsets computation ---------------------

    /**
     * Compute one horizontal offset per row by aggregating tile-level mask density.
     * This gives organic object-aware horizontal displacement without per-pixel remapping.
     */
    private fun computeRowOffsetsByTile(mask: Array<BooleanArray>, w: Int, h: Int, enhanced: Boolean): FloatArray {
        val tileSize = tileSizeFromWidth(w)
        val tilesX = (w + tileSize - 1) / tileSize
        val tilesY = (h + tileSize - 1) / tileSize
        val tileOffsets = Array(tilesY) { FloatArray(tilesX) }
        // compute density per tile
        for (ty in 0 until tilesY) {
            val y0 = ty * tileSize
            val y1 = min(h, y0 + tileSize)
            for (tx in 0 until tilesX) {
                val x0 = tx * tileSize
                val x1 = min(w, x0 + tileSize)
                var cnt = 0; var tot = 0
                for (yy in y0 until y1) {
                    for (xx in x0 until x1) {
                        tot++
                        if (mask[yy][xx]) cnt++
                    }
                }
                val dens = if (tot == 0) 0f else cnt.toFloat() / tot.toFloat()
                // average depth/influence maps to offset fraction
                val base = if (enhanced) 0.12f else 0.05f
                var shift = base * (1f + dens * 2.4f)
                // alternate sign by tile to create organic shifts
                if ((tx + ty) % 2 == 1) shift = -shift
                tileOffsets[ty][tx] = shift * w.toFloat()
            }
        }
        // convert tileOffsets to rowOffsets by averaging tiles overlapping each row
        val rowOffsets = FloatArray(h)
        for (y in 0 until h) {
            val ty = y / tileSize
            var s = 0f; var c = 0
            for (tx in 0 until tilesX) {
                s += tileOffsets[ty.coerceIn(0, tilesY - 1)][tx]
                c++
            }
            rowOffsets[y] = s / max(1, c)
        }
        // optional vertical smoothing
        val smooth = FloatArray(h)
        val radius = 3
        for (y in 0 until h) {
            var s = 0f; var c = 0
            for (k in -radius..radius) {
                val yy = (y + k).coerceIn(0, h - 1)
                s += rowOffsets[yy]; c++
            }
            smooth[y] = s / c
        }
        // clamp
        val maxShift = (w * MAX_SHIFT_FRACTION)
        for (i in smooth.indices) smooth[i] = smooth[i].coerceIn(-maxShift, maxShift)
        return smooth
    }

    private fun tileSizeFromWidth(w: Int): Int {
        return max(24, min(BASE_TILE_SIZE, w / 16))
    }

    // --------------------- Warp implementation (tile-based horizontal shift) ---------------------

    /**
     * Warp bitmap by shifting rows according to rowOffsets.
     * Uses nearest-neighbor copy for speed; tile-based offsets computed beforehand.
     */
    private fun warpBitmapTiles(src: Bitmap, rowOffsets: FloatArray, tileSize: Int): Bitmap {
        val w = src.width; val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val srcRow = IntArray(w)
        val tmp = IntArray(w)
        for (y in 0 until h) {
            src.getPixels(srcRow, 0, w, 0, y, w, 1)
            var off = rowOffsets[y].roundToInt()
            // smaller offsets for tiles near zero to avoid huge jumps
            off = off.coerceIn(-w / 2, w / 2)
            if (off == 0) {
                out.setPixels(srcRow, 0, w, 0, y, w, 1)
            } else if (off > 0) {
                System.arraycopy(srcRow, 0, tmp, off, w - off)
                // fill left with edge pixel to avoid holes
                for (i in 0 until off) tmp[i] = srcRow[0]
                out.setPixels(tmp, 0, w, 0, y, w, 1)
            } else {
                val o = -off
                System.arraycopy(srcRow, o, tmp, 0, w - o)
                for (i in (w - o) until w) tmp[i] = srcRow[w - 1]
                out.setPixels(tmp, 0, w, 0, y, w, 1)
            }
        }
        return out
    }

    // --------------------- Atmosphere / color grade ---------------------

    /**
     * Apply darker, bluish color grade; then selectively overlay chromatic shifts near object mask regions.
     */
    private fun applyAtmosphereAndSelectiveChromatic(src: Bitmap, mask: Array<BooleanArray>?, enhanced: Boolean): Bitmap {
        val w = src.width; val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val contrast = if (enhanced) 1.06f else 1.02f
        val bright = if (enhanced) -20f else -8f
        val cm = ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, bright,
            0f, contrast, 0f, 0f, bright,
            0f, 0f, contrast, 0f, bright - 6f, // slight blue bias via later overlay
            0f, 0f, 0f, 1f, 0f
        ))
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(src, 0f, 0f, paint)

        // selective chromatic shift: compute bounding rects from mask and overlay shifted red/blue
        if (mask != null) {
            val rects = boundingRectsFromMask(mask)
            val redPaint = Paint()
            redPaint.isFilterBitmap = true
            redPaint.colorFilter = PorterDuffColorFilter(Color.argb(if (enhanced) 120 else 80, 255, 0, 0), PorterDuff.Mode.SRC_ATOP)
            val bluePaint = Paint()
            bluePaint.isFilterBitmap = true
            bluePaint.colorFilter = PorterDuffColorFilter(Color.argb(if (enhanced) 100 else 60, 0, 0, 255), PorterDuff.Mode.SRC_ATOP)
            val shiftPx = if (enhanced) 4 else 2
            for (r in rects) {
                try {
                    val srcRect = Rect(r.left, r.top, r.right, r.bottom)
                    val dstR = Rect(r.left + shiftPx, r.top, r.right + shiftPx, r.bottom)
                    val dstB = Rect(r.left - shiftPx, r.top, r.right - shiftPx, r.bottom)
                    canvas.drawBitmap(src, srcRect, dstR, redPaint)
                    canvas.drawBitmap(src, srcRect, dstB, bluePaint)
                } catch (_: Exception) { /* ignore */ }
            }
        }
        return out
    }

    // Helper: find bounding rects of connected mask components (limit count)
    private fun boundingRectsFromMask(mask: Array<BooleanArray>): List<Rect> {
        val h = mask.size
        if (h == 0) return emptyList()
        val w = mask[0].size
        val visited = Array(h) { BooleanArray(w) }
        val rects = mutableListOf<Rect>()
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!visited[y][x] && mask[y][x]) {
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
                    val pad = 6
                    rects.add(Rect((minX - pad).coerceAtLeast(0), (minY - pad).coerceAtLeast(0), (maxX + pad).coerceAtMost(w - 1), (maxY + pad).coerceAtMost(h - 1)))
                    if (rects.size >= 10) return rects
                }
            }
        }
        return rects
    }

    // --------------------- Capture & save (full-res) ---------------------

    private fun onCaptureClicked() {
        // capture full-res using imageCapture if available and then process full-res using last small mask/offsets
        val ic = imageCapture ?: run {
            Toast.makeText(this, "Capture not ready", Toast.LENGTH_SHORT).show(); return
        }
        // Use takePicture with callback to get saved file or ImageProxy; we will use ImageCapture.toBitmap on API21+ or use .takePicture with executor
        val outOptions = ImageCapture.OutputFileOptions.Builder(createTempFileInCache()).build()
        ic.takePicture(outOptions, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                val savedUri = outputFileResults.savedUri
                // The savedUri contains the raw camera capture - read it back as Bitmap, then process using last mask/upscale
                coroutineScope.launch {
                    val rawBitmap = try {
                        val stream = contentResolver.openInputStream(savedUri!!)
                        BitmapFactory.decodeStream(stream)
                    } catch (e: Exception) {
                        Logger.e(TAG, "read saved raw image failed: ${e.message}")
                        null
                    }
                    if (rawBitmap != null) {
                        val processed = processFullResCapture(rawBitmap)
                        // save processed final image to MediaStore
                        saveProcessedToMediaStore(processed)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@CameraActivity, "Saved processed photo", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@CameraActivity, "Capture read failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            override fun onError(exception: ImageCaptureException) {
                Logger.e(TAG, "takePicture error: ${exception.message}")
                Toast.makeText(this@CameraActivity, "Capture failed", Toast.LENGTH_SHORT).show()
            }
        })
    }

    // Create a temp file in cache to let ImageCapture save quickly
    private fun createTempFileInCache(): java.io.File {
        val f = File(cacheDir, "cap_${System.currentTimeMillis()}.jpg")
        if (!f.exists()) f.createNewFile()
        return f
    }

    /**
     * Upscales small mask -> full-res and applies tile-based warp + atmosphere to produce final processed capture.
     */
    private fun processFullResCapture(raw: Bitmap): Bitmap {
        try {
            // get last small mask & offsets
            val maskSmall = lastMaskSmall
            val offsetsSmall = lastRowOffsetsSmall
            if (maskSmall == null || offsetsSmall == null) {
                // fallback: simply apply atmosphere to raw
                return applyAtmosphereAndSelectiveChromatic(raw, null, TwistedApp.instance.settingsPrefs.getBoolean("enhanced_camera_mode", false))
            }

            val fullW = raw.width; val fullH = raw.height
            // build mask bitmap from boolean array then scale up
            val smallW = maskSmall[0].size; val smallH = maskSmall.size
            val maskBmp = Bitmap.createBitmap(smallW, smallH, Bitmap.Config.ALPHA_8)
            val pixels = ByteArray(smallW * smallH)
            var idx = 0
            for (y in 0 until smallH) {
                for (x in 0 until smallW) {
                    pixels[idx++] = if (maskSmall[y][x]) 255.toByte() else 0.toByte()
                }
            }
            maskBmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(pixels))
            // upscale mask to full resolution (bilinear)
            val maskFullBmp = Bitmap.createScaledBitmap(maskBmp, fullW, fullH, true)
            // convert maskFullBmp to boolean array
            val maskFull = Array(fullH) { BooleanArray(fullW) }
            val maskPixels = IntArray(fullW * fullH)
            maskFullBmp.getPixels(maskPixels, 0, fullW, 0, 0, fullW, fullH)
            idx = 0
            for (y in 0 until fullH) {
                for (x in 0 until fullW) {
                    maskFull[y][x] = (maskPixels[idx] and 0xFF) > 32
                    idx++
                }
            }

            // upsample offsets to full height: scale rowOffsetsSmall (which was length smallH) to fullH
            val offsetsFull = FloatArray(fullH)
            for (y in 0 until fullH) {
                val srcY = (y.toFloat() * offsetsSmall.size / fullH).toInt().coerceIn(0, offsetsSmall.size - 1)
                offsetsFull[y] = offsetsSmall[srcY] * (fullW.toFloat() / (smallW.toFloat()))
            }

            // warp full-res using offsetsFull
            val tileSize = tileSizeFromWidth(fullW)
            val warped = warpBitmapTiles(raw, offsetsFull, tileSize)

            // atmosphere on full-res with maskFull
            val final = applyAtmosphereAndSelectiveChromatic(warped, maskFull, TwistedApp.instance.settingsPrefs.getBoolean("enhanced_camera_mode", false))
            return final
        } catch (e: Exception) {
            Logger.e(TAG, "processFullResCapture failed: ${e.message}")
            return raw
        }
    }

    private fun saveProcessedToMediaStore(bmp: Bitmap) {
        try {
            val resolver = contentResolver
            val fname = "twisted_processed_${System.currentTimeMillis()}.jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fname)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TwistedPhone")
                }
            }
            val uri: Uri? = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 92, out) }
                Logger.d(TAG, "Saved processed image to $uri")
            } else {
                Logger.e(TAG, "MediaStore insert returned null")
            }
        } catch (e: Exception) {
            Logger.e(TAG, "saveProcessedToMediaStore failed: ${e.message}")
        }
    }
}
