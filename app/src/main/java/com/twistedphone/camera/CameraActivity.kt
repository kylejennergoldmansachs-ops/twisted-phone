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

class CameraActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "CameraActivity"
        private val DEFAULT_PROC_SIZE = Size(512, 384)
        private val HIGH_PROC_SIZE = Size(1280, 720)
        private const val BASE_TILE_SIZE = 64
        private const val MAX_SHIFT_FRACTION = 0.22f
        private const val MIN_ANALYSIS_INTERVAL_MS = 100L
    }

    private lateinit var root: FrameLayout
    private lateinit var previewView: PreviewView
    private lateinit var overlayView: ImageView
    private lateinit var captureButton: Button

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val bgScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var midasInterpreter: Interpreter? = null

    @Volatile private var lastProcessedPreviewBitmap: Bitmap? = null
    @Volatile private var lastMaskSmall: Array<BooleanArray>? = null
    @Volatile private var lastRowOffsetsSmall: FloatArray? = null

    @Volatile private var lastAnalysisMs = 0L

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else { Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show(); finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        root = FrameLayout(this)
        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        overlayView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.TRANSPARENT)
        }
        captureButton = Button(this).apply {
            text = "CAPTURE"
            setOnClickListener { onCaptureClicked() }
        }

        root.addView(previewView)
        root.addView(overlayView)
        val btnParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        btnParams.leftMargin = 16; btnParams.topMargin = 16
        root.addView(captureButton, btnParams)
        setContentView(root)

        bgScope.launch {
            midasInterpreter = tryLoadMidas()
            if (midasInterpreter != null) Logger.d(TAG, "Midas model loaded") else Logger.d(TAG, "Midas not found")
        }

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

        val fullResRealtime = TwistedApp.instance.settingsPrefs.getBoolean("full_res_realtime", false)
        val targetRes = if (fullResRealtime) HIGH_PROC_SIZE else DEFAULT_PROC_SIZE

        imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(targetRes)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build().also { ia ->
                ia.setAnalyzer(analysisExecutor) { image ->
                    try {
                        val now = System.currentTimeMillis()
                        if (now - lastAnalysisMs < MIN_ANALYSIS_INTERVAL_MS) { image.close(); return@setAnalyzer }
                        lastAnalysisMs = now
                        bgScope.launch {
                            val procBitmap = convertImageProxyToBitmapSafe(image)
                            if (procBitmap != null) {
                                processAndRenderFrame(procBitmap)
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

    // robust conversion: respects strides and pixelStride, returns scaled bitmap to DEFAULT_PROC_SIZE
    private fun convertImageProxyToBitmapSafe(image: ImageProxy): Bitmap? {
        try {
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

            val yuvImage = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            val ok = yuvImage.compressToJpeg(android.graphics.Rect(0, 0, width, height), 85, out)
            if (!ok) return null
            val bytes = out.toByteArray()
            var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null

            val rotation = image.imageInfo.rotationDegrees
            if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                bmp.recycle()
                bmp = rotated
            }

            val proc = Bitmap.createScaledBitmap(bmp, DEFAULT_PROC_SIZE.width, DEFAULT_PROC_SIZE.height, true)
            bmp.recycle()
            return proc
        } catch (e: Exception) {
            Logger.e(TAG, "convertImageProxyToBitmapSafe failed: ${e.message}")
            return null
        }
    }

    private suspend fun processAndRenderFrame(smallBitmap: Bitmap) {
        try {
            val enhanced = TwistedApp.instance.settingsPrefs.getBoolean("enhanced_camera_mode", false)

            val depthSmall: Array<FloatArray>? = midasInterpreter?.let { runMidasSafely(it, smallBitmap) }
            val maskSmall = computeMaskFromDepthOrEdges(depthSmall, smallBitmap)
            lastMaskSmall = maskSmall

            val rowOffsetsSmall = computeRowOffsetsByTile(maskSmall, smallBitmap.width, smallBitmap.height, enhanced)
            lastRowOffsetsSmall = rowOffsetsSmall

            val warpedSmall = warpBitmapTiles(smallBitmap, rowOffsetsSmall, tileSizeFromWidth(smallBitmap.width))

            val finalSmall = applyAtmosphereAndSelectiveChromatic(warpedSmall, maskSmall, enhanced)

            lastProcessedPreviewBitmap = finalSmall
            withContext(Dispatchers.Main) {
                try { overlayView.setImageBitmap(finalSmall) } catch (e: Exception) { Logger.e(TAG, "set overlay failed: ${e.message}") }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "processAndRenderFrame failed: ${e.message}")
        }
    }

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

    private fun runMidasSafely(interp: Interpreter, bmp: Bitmap): Array<FloatArray>? {
        return try {
            val inputTensor = interp.getInputTensor(0)
            val shape = inputTensor.shape()
            if (shape.size != 4) return null
            val reqH = shape[1]; val reqW = shape[2]
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
            val outShape = interp.getOutputTensor(0).shape()
            val outH = outShape[1]; val outW = outShape[2]
            val out = Array(outH) { FloatArray(outW) }
            interp.run(input, out)
            out
        } catch (e: Exception) {
            Logger.e(TAG, "runMidasSafely failed: ${e.message}")
            null
        }
    }

    private fun computeMaskFromDepthOrEdges(depth: Array<FloatArray>?, bmp: Bitmap): Array<BooleanArray> {
        val width = bmp.width; val height = bmp.height
        val mask = Array(height) { BooleanArray(width) }
        if (depth != null) {
            var min = Float.MAX_VALUE; var max = -Float.MAX_VALUE
            for (r in depth) for (v in r) { if (v < min) min = v; if (v > max) max = v }
            val range = max - min
            val thresh = min + (if (range < 1e-6f) 0.01f else range * 0.25f)
            for (y in 0 until min(height, depth.size)) {
                val row = depth[y]
                for (x in 0 until min(width, row.size)) {
                    mask[y][x] = row[x] < thresh
                }
            }
            dilateMask(mask, 2)
            return mask
        } else {
            val pixels = IntArray(width * height)
            val gray = IntArray(width * height)
            bmp.getPixels(pixels, 0, width, 0, 0, width, height)
            for (i in pixels.indices) {
                val c = pixels[i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                gray[i] = ((0.299 * r + 0.587 * g + 0.114 * b).toInt())
            }
            for (y in 1 until height - 1) {
                for (x in 1 until width - 1) {
                    val gx = -gray[(y - 1) * width + (x - 1)] + gray[(y - 1) * width + (x + 1)] - 2 * gray[y * width + (x - 1)] + 2 * gray[y * width + (x + 1)] - gray[(y + 1) * width + (x - 1)] + gray[(y + 1) * width + (x + 1)]
                    val gy = -gray[(y - 1) * width + (x - 1)] - 2 * gray[(y - 1) * width + x] - gray[(y - 1) * width + (x + 1)] + gray[(y + 1) * width + (x - 1)] + 2 * gray[(y + 1) * width + x] + gray[(y + 1) * width + (x + 1)]
                    val mag = sqrt((gx * gx + gy * gy).toDouble()).toFloat()
                    mask[y][x] = mag > 90f
                }
            }
            dilateMask(mask, 1)
            return mask
        }
    }

    private fun dilateMask(mask: Array<BooleanArray>, radius: Int) {
        val height = mask.size; val width = mask[0].size
        val tmp = Array(height) { BooleanArray(width) }
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (mask[y][x]) {
                    for (dy in -radius..radius) {
                        for (dx in -radius..radius) {
                            val ny = y + dy; val nx = x + dx
                            if (ny in 0 until height && nx in 0 until width) tmp[ny][nx] = true
                        }
                    }
                }
            }
        }
        for (y in 0 until height) for (x in 0 until width) mask[y][x] = tmp[y][x]
    }

    private fun computeRowOffsetsByTile(mask: Array<BooleanArray>, width: Int, height: Int, enhanced: Boolean): FloatArray {
        val tileSize = tileSizeFromWidth(width)
        val tilesX = (width + tileSize - 1) / tileSize
        val tilesY = (height + tileSize - 1) / tileSize
        val tileOffsets = Array(tilesY) { FloatArray(tilesX) }
        for (ty in 0 until tilesY) {
            val y0 = ty * tileSize
            val y1 = min(height, y0 + tileSize)
            for (tx in 0 until tilesX) {
                val x0 = tx * tileSize
                val x1 = min(width, x0 + tileSize)
                var cnt = 0; var tot = 0
                for (yy in y0 until y1) {
                    for (xx in x0 until x1) {
                        tot++
                        if (mask[yy][xx]) cnt++
                    }
                }
                val dens = if (tot == 0) 0f else cnt.toFloat() / tot.toFloat()
                val base = if (enhanced) 0.12f else 0.05f
                var shift = base * (1f + dens * 2.4f)
                if ((tx + ty) % 2 == 1) shift = -shift
                tileOffsets[ty][tx] = shift * width.toFloat()
            }
        }
        val rowOffsets = FloatArray(height)
        for (y in 0 until height) {
            val ty = y / tileSize
            var s = 0f; var c = 0
            for (tx in 0 until tilesX) {
                s += tileOffsets[ty.coerceIn(0, tilesY - 1)][tx]
                c++
            }
            rowOffsets[y] = s / max(1, c)
        }
        val smooth = FloatArray(height)
        val radius = 3
        for (y in 0 until height) {
            var s = 0f; var c = 0
            for (k in -radius..radius) {
                val yy = (y + k).coerceIn(0, height - 1)
                s += rowOffsets[yy]; c++
            }
            smooth[y] = s / c
        }
        val maxShift = (width * MAX_SHIFT_FRACTION)
        for (i in smooth.indices) smooth[i] = smooth[i].coerceIn(-maxShift, maxShift)
        return smooth
    }

    private fun tileSizeFromWidth(width: Int): Int {
        return max(24, min(BASE_TILE_SIZE, width / 16))
    }

    private fun warpBitmapTiles(src: Bitmap, rowOffsets: FloatArray, tileSize: Int): Bitmap {
        val width = src.width; val height = src.height
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val srcRow = IntArray(width)
        val tmp = IntArray(width)
        for (y in 0 until height) {
            src.getPixels(srcRow, 0, width, 0, y, width, 1)
            var off = rowOffsets[y].roundToInt()
            off = off.coerceIn(-width / 2, width / 2)
            if (off == 0) {
                out.setPixels(srcRow, 0, width, 0, y, width, 1)
            } else if (off > 0) {
                System.arraycopy(srcRow, 0, tmp, off, width - off)
                for (i in 0 until off) tmp[i] = srcRow[0]
                out.setPixels(tmp, 0, width, 0, y, width, 1)
            } else {
                val o = -off
                System.arraycopy(srcRow, o, tmp, 0, width - o)
                for (i in (width - o) until width) tmp[i] = srcRow[width - 1]
                out.setPixels(tmp, 0, width, 0, y, width, 1)
            }
        }
        return out
    }

    private fun applyAtmosphereAndSelectiveChromatic(src: Bitmap, mask: Array<BooleanArray>?, enhanced: Boolean): Bitmap {
        val width = src.width; val height = src.height
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val contrast = if (enhanced) 1.06f else 1.02f
        val bright = if (enhanced) -20f else -8f
        val cm = ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, bright,
            0f, contrast, 0f, 0f, bright,
            0f, 0f, contrast, 0f, bright - 6f,
            0f, 0f, 0f, 1f, 0f
        ))
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(src, 0f, 0f, paint)

        if (mask != null) {
            val rects = boundingRectsFromMask(mask)
            val redPaint = Paint(); redPaint.isFilterBitmap = true
            redPaint.colorFilter = PorterDuffColorFilter(Color.argb(if (enhanced) 120 else 80, 255, 0, 0), PorterDuff.Mode.SRC_ATOP)
            val bluePaint = Paint(); bluePaint.isFilterBitmap = true
            bluePaint.colorFilter = PorterDuffColorFilter(Color.argb(if (enhanced) 100 else 60, 0, 0, 255), PorterDuff.Mode.SRC_ATOP)
            val shiftPx = if (enhanced) 4 else 2
            for (r in rects) {
                try {
                    val srcRect = Rect(r.left, r.top, r.right, r.bottom)
                    val dstR = Rect(r.left + shiftPx, r.top, r.right + shiftPx, r.bottom)
                    val dstB = Rect(r.left - shiftPx, r.top, r.right - shiftPx, r.bottom)
                    canvas.drawBitmap(src, srcRect, dstR, redPaint)
                    canvas.drawBitmap(src, srcRect, dstB, bluePaint)
                } catch (_: Exception) {}
            }
        }
        return out
    }

    private fun boundingRectsFromMask(mask: Array<BooleanArray>): List<Rect> {
        val height = mask.size
        if (height == 0) return emptyList()
        val width = mask[0].size
        val visited = Array(height) { BooleanArray(width) }
        val rects = mutableListOf<Rect>()
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (!visited[y][x] && mask[y][x]) {
                    var minX = x; var minY = y; var maxX = x; var maxY = y
                    val queue = ArrayDeque<Pair<Int, Int>>()
                    queue.add(Pair(x, y)); visited[y][x] = true
                    while (queue.isNotEmpty()) {
                        val (cx, cy) = queue.removeFirst()
                        minX = min(minX, cx); minY = min(minY, cy)
                        maxX = max(maxX, cx); maxY = max(maxY, cy)
                        for (ny in max(0, cy - 1) .. min(height - 1, cy + 1)) {
                            for (nx in max(0, cx - 1) .. min(width - 1, cx + 1)) {
                                if (!visited[ny][nx] && mask[ny][nx]) {
                                    visited[ny][nx] = true
                                    queue.add(Pair(nx, ny))
                                }
                            }
                        }
                    }
                    val pad = 6
                    rects.add(Rect((minX - pad).coerceAtLeast(0), (minY - pad).coerceAtLeast(0), (maxX + pad).coerceAtMost(width - 1), (maxY + pad).coerceAtMost(height - 1)))
                    if (rects.size >= 10) return rects
                }
            }
        }
        return rects
    }

    // capture
    private fun onCaptureClicked() {
        val ic = imageCapture ?: run { Toast.makeText(this, "Capture not ready", Toast.LENGTH_SHORT).show(); return }
        val outFile = createTempFileInCache()
        val outOptions = ImageCapture.OutputFileOptions.Builder(outFile).build()
        ic.takePicture(outOptions, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                val savedUri = Uri.fromFile(outFile)
                coroutineScope.launch {
                    val rawBitmap = try {
                        contentResolver.openInputStream(savedUri)?.use { BitmapFactory.decodeStream(it) }
                    } catch (e: Exception) {
                        Logger.e(TAG, "read saved raw image failed: ${e.message}")
                        null
                    }
                    if (rawBitmap != null) {
                        val processed = processFullResCapture(rawBitmap)
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

    private fun createTempFileInCache(): java.io.File {
        val f = File(cacheDir, "cap_${System.currentTimeMillis()}.jpg")
        if (!f.exists()) f.createNewFile()
        return f
    }

    private fun processFullResCapture(raw: Bitmap): Bitmap {
        try {
            val maskSmall = lastMaskSmall
            val offsetsSmall = lastRowOffsetsSmall
            if (maskSmall == null || offsetsSmall == null) {
                return applyAtmosphereAndSelectiveChromatic(raw, null, TwistedApp.instance.settingsPrefs.getBoolean("enhanced_camera_mode", false))
            }
            val fullWidth = raw.width; val fullHeight = raw.height
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
            val maskFullBmp = Bitmap.createScaledBitmap(maskBmp, fullWidth, fullHeight, true)
            val maskFull = Array(fullHeight) { BooleanArray(fullWidth) }
            val maskPixels = IntArray(fullWidth * fullHeight)
            maskFullBmp.getPixels(maskPixels, 0, fullWidth, 0, 0, fullWidth, fullHeight)
            idx = 0
            for (y in 0 until fullHeight) {
                for (x in 0 until fullWidth) {
                    maskFull[y][x] = (maskPixels[idx] and 0xFF) > 32
                    idx++
                }
            }
            val offsetsFull = FloatArray(fullHeight)
            for (y in 0 until fullHeight) {
                val srcY = (y.toFloat() * offsetsSmall.size / fullHeight).toInt().coerceIn(0, offsetsSmall.size - 1)
                offsetsFull[y] = offsetsSmall[srcY] * (fullWidth.toFloat() / (smallW.toFloat()))
            }
            val tileSize = tileSizeFromWidth(fullWidth)
            val warped = warpBitmapTiles(raw, offsetsFull, tileSize)
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
