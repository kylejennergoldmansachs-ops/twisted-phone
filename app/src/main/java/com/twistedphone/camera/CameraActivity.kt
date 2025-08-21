package com.twistedphone.camera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.twistedphone.TwistedApp
import com.twistedphone.util.Logger
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.*

class CameraActivity : AppCompatActivity() {
    private val TAG = "CameraActivity"
    private val REQUEST_PERMS = 101
    private lateinit var container: FrameLayout
    private lateinit var displayView: ImageView
    private lateinit var btnToggle: Button
    private lateinit var btnCapture: Button

    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var analysisUseCase: ImageAnalysis? = null
    private var previewUseCase: Preview? = null
    private var lastWarped: Bitmap? = null

    // Enhanced models
    private var useEnhanced = false
    private var aggressiveness = 1
    private var midasInterpreter: Interpreter? = null
    private var poseDetector: ObjectDetector? = null
    private var depthFloatArray: FloatArray? = null
    private var frameCounter = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupUI()
        checkPermissions()
        loadConfiguration()
        setupButtonListeners()
        loadModelsSafe()
    }

    private fun setupUI() {
        container = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        displayView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
        }

        btnToggle = Button(this).apply {
            text = "Flip"
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                setMargins(20, 20, 20, 40)
            }
            layoutParams = lp
        }

        btnCapture = Button(this).apply {
            text = "Capture"
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(20, 20, 20, 40)
            }
            layoutParams = lp
        }

        container.addView(displayView)
        container.addView(btnToggle)
        container.addView(btnCapture)
        setContentView(container)
    }

    private fun checkPermissions() {
        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_PERMS)
        } else {
            startCamera()
        }
    }

    private fun loadConfiguration() {
        useEnhanced = TwistedApp.instance.settingsPrefs.getBoolean("enhanced_camera", false)
        aggressiveness = TwistedApp.instance.settingsPrefs.getInt("aggressiveness", 1)
    }

    private fun setupButtonListeners() {
        btnToggle.setOnClickListener {
            cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) 
                CameraSelector.DEFAULT_FRONT_CAMERA 
            else 
                CameraSelector.DEFAULT_BACK_CAMERA
            startCamera()
        }

        btnCapture.setOnClickListener {
            lastWarped?.let { bitmap ->
                saveBitmapToGallery(bitmap, "TwistedCapture")
            } ?: run {
                Toast.makeText(this, "No frame to capture yet", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun allPermissionsGranted(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_PERMS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Permissions denied", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun startCamera() {
        val cameraProviderF = ProcessCameraProvider.getInstance(this)
        cameraProviderF.addListener({
            val provider = cameraProviderF.get()
            provider.unbindAll()

            // Preview setup
            previewUseCase = Preview.Builder()
                .setTargetResolution(Size(720, 1280))
                .build()
                .also {
                    it.setSurfaceProvider { request ->
                        val surfaceTexture = displayView.surfaceTexture
                        surfaceTexture?.let { texture ->
                            texture.setDefaultBufferSize(720, 1280)
                            request.provideSurface(Surface(texture))
                        }
                    }
                }

            // Analysis setup
            analysisUseCase = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysisUseCase?.setAnalyzer(cameraExecutor) { imageProxy ->
                try {
                    handleFrame(imageProxy)
                } finally {
                    imageProxy.close()
                }
            }

            try {
                provider.bindToLifecycle(
                    this,
                    cameraSelector,
                    previewUseCase,
                    analysisUseCase
                )
                Logger.d(TAG, "Camera bound successfully")
            } catch (e: Exception) {
                Logger.e(TAG, "Camera bind failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleFrame(image: ImageProxy) {
        val bmp = imageToBitmap(image) ?: return
        val warped = if (useEnhanced && midasInterpreter != null) {
            enhancedWarp(bmp)
        } else {
            simpleWarp(bmp)
        }
        lastWarped = warped

        runOnUiThread {
            displayView.setImageBitmap(warped)
        }
    }

    private fun imageToBitmap(image: ImageProxy): Bitmap? {
        return try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            
            // Apply rotation correction
            val matrix = Matrix().apply {
                postRotate(image.imageInfo.rotationDegrees.toFloat())
            }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (e: Exception) {
            Logger.e(TAG, "Image to bitmap conversion failed: ${e.message}")
            null
        }
    }

    private fun simpleWarp(src: Bitmap): Bitmap {
        val targetW = 720
        val scale = min(1.0f, targetW.toFloat() / src.width)
        val w = max(1, (src.width * scale).toInt())
        val h = max(1, (src.height * scale).toInt())
        
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint()
        
        val time = System.currentTimeMillis() / 200.0
        val matrix = Matrix().apply {
            postScale(1.0f, 1.0f)
            postTranslate(
                (sin(time * 0.1) * 10 * aggressiveness).toFloat(),
                (cos(time * 0.1) * 10 * aggressiveness).toFloat()
            )
        }
        
        canvas.drawBitmap(small, matrix, paint)
        
        // Apply color filter for darker mood
        val filter = ColorMatrixColorFilter(ColorMatrix().apply {
            setScale(0.8f, 0.8f, 0.8f, 1f)
        })
        paint.colorFilter = filter
        canvas.drawBitmap(out, 0f, 0f, paint)
        
        return Bitmap.createScaledBitmap(out, src.width, src.height, true)
    }

    private fun enhancedWarp(src: Bitmap): Bitmap {
        // Run inference every 3 frames to reduce load
        if (frameCounter++ % 3 == 0) {
            runMidasInference(src)
        }

        val warped = simpleWarp(src)
        // Additional enhancement using depth data would go here
        return warped
    }

    private fun runMidasInference(bmp: Bitmap) {
        try {
            val input = Bitmap.createScaledBitmap(bmp, 256, 256, true)
            val output = Array(1) { FloatArray(256 * 256) }
            midasInterpreter?.run(input.toByteBuffer(), output)
            depthFloatArray = output[0]
        } catch (e: Exception) {
            Logger.e(TAG, "MiDaS inference failed: ${e.message}")
        }
    }

    private fun saveBitmapToGallery(bmp: Bitmap, titleBase: String) {
        val filename = "$titleBase-${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TwistedPhone")
            }
        }

        try {
            contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)?.let { uri ->
                contentResolver.openOutputStream(uri)?.use { stream ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                    Toast.makeText(this, "Image saved to gallery", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to save image: ${e.message}")
            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadModelsSafe() {
        if (!useEnhanced) {
            Logger.d(TAG, "Enhanced mode disabled in settings")
            return
        }

        try {
            // Load MiDaS model
            val midasFile = filesDir.resolve("models/midas.tflite")
            if (midasFile.exists()) {
                val options = Interpreter.Options().apply {
                    setNumThreads(2)
                    // Try GPU delegation with fallback
                    try {
                        val compat = CompatibilityList()
                        if (compat.isDelegateSupportedOnThisDevice) {
                            val delegate = compat.bestDelegate
                            addDelegate(delegate)
                        }
                    } catch (e: Exception) {
                        Logger.d(TAG, "GPU not available, using CPU")
                    }
                }
                midasInterpreter = Interpreter(FileUtil.loadMappedFile(this, midasFile.path), options)
                Logger.d(TAG, "MiDaS model loaded successfully")
            }

            // Load pose detection model
            val poseFile = filesDir.resolve("models/pose.tflite")
            if (poseFile.exists()) {
                val baseOptions = BaseOptions.builder().setNumThreads(2).build()
                val options = ObjectDetector.ObjectDetectorOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setMaxResults(2)
                    .build()
                poseDetector = ObjectDetector.createFromFileAndOptions(this, poseFile.path, options)
                Logger.d(TAG, "Pose model loaded successfully")
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Model loading failed: ${e.message}")
            useEnhanced = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        midasInterpreter?.close()
        poseDetector?.close()
    }

    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    }
}

// Extension function to convert Bitmap to ByteBuffer
private fun Bitmap.toByteBuffer(): ByteBuffer {
    val bytes = ByteArray(width * height * 4)
    val buffer = ByteBuffer.allocateDirect(bytes.size)
    copyPixelsToBuffer(buffer)
    buffer.rewind()
    return buffer
}
