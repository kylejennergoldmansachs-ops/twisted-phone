package com.twistedphone.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Size
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.twistedphone.R
import com.twistedphone.TwistedApp
import com.twistedphone.util.FileLogger
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import org.tensorflow.lite.task.core.BaseOptions
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

class CameraActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var glSurfaceView: GLSurfaceView

    private val prefs = TwistedApp.instance.settingsPrefs

    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

    // MiDaS interpreter + GPU delegate (if used)
    private var midasInterpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null

    // Pose detector (TFLite Task Vision)
    private var poseDetector: ObjectDetector? = null

    private var useEnhanced = false
    private var aggressiveness = 1

    // last depth/pose results (best-effort)
    private var depthMap: TensorBuffer? = null

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_camera)
        previewView = findViewById(R.id.previewView)
        glSurfaceView = findViewById(R.id.glSurfaceView)

        useEnhanced = prefs.getBoolean("enhanced_camera", false)
        aggressiveness = prefs.getInt("aggressiveness", 1)

        findViewById<Button>(R.id.btnToggle).setOnClickListener {
            cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            startCamera()
        }
        findViewById<Button>(R.id.btnCapture).setOnClickListener { captureImage() }

        // GL setup (renderer will handle buffers correctly)
        glSurfaceView.setEGLContextClientVersion(2)
        glSurfaceView.setRenderer(WarpRenderer(this))
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        loadModelsSafe()
        startCamera()
    }

    /**
     * Robust model loader:
     * - Checks file existence
     * - Uses CompatibilityList to decide on GPU delegate
     * - Tries GPU delegate, falls back to CPU interpreter
     * - Loads pose detector with reasonable BaseOptions (threads), and only sets GPU use if supported
     */
    private fun loadModelsSafe() {
        if (!useEnhanced) {
            FileLogger.d(this, "CameraActivity", "Enhanced mode disabled in settings")
            return
        }

        val modelsDir = File(filesDir, "models")
        if (!modelsDir.exists() || !modelsDir.isDirectory) {
            FileLogger.e(this, "CameraActivity", "Models directory missing: ${modelsDir.absolutePath}")
            useEnhanced = false
            return
        }

        // MiDaS
        val midasFile = File(modelsDir, "midas.tflite")
        if (!midasFile.exists()) {
            FileLogger.e(this, "CameraActivity", "MiDaS model not found: ${midasFile.absolutePath}")
            useEnhanced = false
        } else {
            try {
                val compat = CompatibilityList()
                val options = Interpreter.Options()
                options.setNumThreads(2) // reasonable default; adjust if desired

                if (compat.isDelegateSupportedOnThisDevice) {
                    try {
                        val delegate = GpuDelegate()
                        options.addDelegate(delegate)
                        gpuDelegate = delegate
                        FileLogger.d(this, "CameraActivity", "GPU delegate created for MiDaS")
                    } catch (de: Exception) {
                        FileLogger.e(this, "CameraActivity", "Failed to create GpuDelegate: ${de.message}")
                        // fall through to CPU
                    }
                } else {
                    FileLogger.d(this, "CameraActivity", "GPU delegate not supported on device (CompatibilityList=false)")
                }

                // load mapped file (memory-mapped for faster loads)
                val mapped = FileUtil.loadMappedFile(this, midasFile.path)
                midasInterpreter = Interpreter(mapped, options)
                FileLogger.d(this, "CameraActivity", "Loaded MiDaS interpreter: ${midasFile.absolutePath}")
            } catch (e: Exception) {
                FileLogger.e(this, "CameraActivity", "Model load failed; disabling enhanced mode: ${midasFile.absolutePath} -> ${e.message}")
                // Clean up delegate if it was created
                try { gpuDelegate?.close() } catch (_: Exception) {}
                gpuDelegate = null
                midasInterpreter = null
                useEnhanced = false
            }
        }

        // Pose detector
        val poseFile = File(modelsDir, "pose.tflite")
        if (!poseFile.exists()) {
            FileLogger.e(this, "CameraActivity", "Pose model not found: ${poseFile.absolutePath}")
            // not fatal — pose is optional
        } else if (useEnhanced) {
            try {
                // If GPU delegate supported use task API with GPU; otherwise CPU
                val baseOptBuilder = BaseOptions.builder().setNumThreads(2)
                if (gpuDelegate != null) {
                    // Some Task APIs prefer useGpu flag; but if GPU delegate already attached to Interpreter,
                    // we prefer using CPU variant for Task to avoid double-gpu complexity.
                    FileLogger.d(this, "CameraActivity", "Pose detector will load with CPU threads (GPU delegate present)")
                }
                val baseOptions = baseOptBuilder.build()
                val objOptions = ObjectDetector.ObjectDetectorOptions.builder().setBaseOptions(baseOptions).setMaxResults(2).build()
                poseDetector = ObjectDetector.createFromFileAndOptions(this, poseFile.path, objOptions)
                FileLogger.d(this, "CameraActivity", "Loaded pose detector: ${poseFile.absolutePath}")
            } catch (e: Exception) {
                FileLogger.e(this, "CameraActivity", "Pose detector load failed: ${e.message}")
                poseDetector = null
            }
        }
    }

    private fun startCamera() {
        try {
            val providerF = ProcessCameraProvider.getInstance(this)
            providerF.addListener({
                try {
                    val provider = providerF.get()
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setTargetResolution(Size(640, 480))
                        .build()
                    analysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { img -> processImage(img) }
                    provider.unbindAll()
                    provider.bindToLifecycle(this, cameraSelector, preview, analysis)
                } catch (e: Exception) {
                    FileLogger.e(this, "CameraActivity", "Camera bind failed: ${e.message}")
                    Toast.makeText(this, "Camera not available", Toast.LENGTH_SHORT).show()
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (e: Exception) {
            FileLogger.e(this, "CameraActivity", "startCamera exception: ${e.message}")
            Toast.makeText(this, "Camera initialization failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun processImage(image: ImageProxy) {
        try {
            if (useEnhanced && midasInterpreter != null) {
                val bitmap = imageProxyToBitmap(image)
                if (bitmap != null) {
                    try {
                        val scaled = Bitmap.createScaledBitmap(bitmap, 256, 256, true)
                        val timg = TensorImage.fromBitmap(scaled)
                        // MiDaS expects float32 input — we'll attempt to run and fill outputs accordingly
                        val outArr = Array(1) { FloatArray(256 * 256) }
                        midasInterpreter?.run(timg.buffer, outArr)
                        depthMap = TensorBuffer.createFixedSize(intArrayOf(1, 256, 256, 1), org.tensorflow.lite.DataType.FLOAT32)
                        depthMap?.loadArray(outArr[0])
                    } catch (e: Exception) {
                        FileLogger.e(this, "CameraActivity", "Depth inference failed: ${e.message}")
                    }

                    if (poseDetector != null) {
                        try {
                            poseDetector?.detect(TensorImage.fromBitmap(bitmap))
                        } catch (e: Exception) {
                            FileLogger.e(this, "CameraActivity", "Pose detect failed: ${e.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            FileLogger.e(this, "CameraActivity", "processImage error: ${e.message}")
        } finally {
            image.close()
        }
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        return try {
            val plane = image.planes[0]
            plane.buffer.rewind()
            val bytes = ByteArray(plane.buffer.remaining())
            plane.buffer.get(bytes)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            FileLogger.e(this, "CameraActivity", "imageProxyToBitmap failed: ${e.message}")
            null
        }
    }

    private fun captureImage() {
        try {
            val bmp = previewView.bitmap
            if (bmp != null) {
                val warped = simpleWarp(bmp)
                android.provider.MediaStore.Images.Media.insertImage(contentResolver, warped, "TwistedCapture", "Captured in Twisted Phone")
            }
        } catch (e: Exception) {
            FileLogger.e(this, "CameraActivity", "captureImage failed: ${e.message}")
        }
    }

    private fun simpleWarp(bmp: Bitmap): Bitmap {
        val w = bmp.width; val h = bmp.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val time = System.currentTimeMillis() / 300.0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val xf = x.toFloat() / w; val yf = y.toFloat() / h
                val dx = sin(xf * 12.0 + time) * 8.0 * (1.0 - yf) + cos(yf * 8.0 - time) * 4.0
                val dy = cos(yf * 14.0 - time) * 8.0 * (1.0 - xf) + sin(xf * 6.0 + time) * 3.0
                val sx = (x + dx.toInt()).coerceIn(0, w - 1)
                val sy = (y + dy.toInt()).coerceIn(0, h - 1)
                out.setPixel(x, y, bmp.getPixel(sx, sy))
            }
        }
        return out
    }

    inner class WarpRenderer(private val ctx: Context) : GLSurfaceView.Renderer {
        private var program = 0
        private var textureId = 0
        private var surfaceTexture: android.graphics.SurfaceTexture? = null
        private val projMatrix = FloatArray(16)
        private val viewMatrix = FloatArray(16)
        private var oesTexture = IntArray(1)
        private var vBuffer: FloatBuffer? = null

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            try {
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glGenTextures(1, oesTexture, 0)
                textureId = oesTexture[0]
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
                GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
                GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                surfaceTexture = android.graphics.SurfaceTexture(textureId)
                program = createProgram()
                // native-order direct buffer (fixes "Must use a native order direct Buffer")
                val arr = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
                val bb = java.nio.ByteBuffer.allocateDirect(arr.size * 4).order(ByteOrder.nativeOrder())
                vBuffer = bb.asFloatBuffer()
                vBuffer?.put(arr)
                vBuffer?.position(0)
            } catch (e: Exception) {
                FileLogger.e(ctx, "WarpRenderer", "onSurfaceCreated err: ${e.message}")
            }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            val ratio = width.toFloat() / height
            android.opengl.Matrix.frustumM(projMatrix, 0, -ratio, ratio, -1f, 1f, 3f, 7f)
        }

        override fun onDrawFrame(gl: GL10?) {
            try {
                surfaceTexture?.updateTexImage()
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
                android.opengl.Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, 3f, 0f, 0f, 0f, 0f, 1f, 0f)
                android.opengl.Matrix.multiplyMM(projMatrix, 0, projMatrix, 0, viewMatrix, 0)
                GLES20.glUseProgram(program)
                val timeLoc = GLES20.glGetUniformLocation(program, "time")
                GLES20.glUniform1f(timeLoc, (System.currentTimeMillis() % 1000000) / 1000f)
                val aggLoc = GLES20.glGetUniformLocation(program, "agg")
                GLES20.glUniform1f(aggLoc, aggressiveness.toFloat())
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
                val texLoc = GLES20.glGetUniformLocation(program, "uTexture")
                GLES20.glUniform1i(texLoc, 0)
                val posLoc = GLES20.glGetAttribLocation(program, "vPosition")
                GLES20.glEnableVertexAttribArray(posLoc)
                vBuffer?.position(0)
                GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, vBuffer)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GLES20.glDisableVertexAttribArray(posLoc)
            } catch (e: Exception) {
                FileLogger.e(ctx, "WarpRenderer", "onDrawFrame error: ${e.message}")
            }
        }

        private fun createProgram(): Int {
            val vert = loadShader(GLES20.GL_VERTEX_SHADER, vertexCode)
            val frag = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentCode)
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, vert)
            GLES20.glAttachShader(p, frag)
            GLES20.glLinkProgram(p)
            return p
        }

        private fun loadShader(type: Int, code: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, code)
            GLES20.glCompileShader(shader)
            return shader
        }

        private val vertexCode = """
            attribute vec4 vPosition;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vPosition;
                vTexCoord = (vPosition.xy * 0.5) + 0.5;
            }
        """.trimIndent()

        private val fragmentCode = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            uniform float time;
            uniform float agg;
            varying vec2 vTexCoord;
            void main() {
                vec2 uv = vTexCoord;
                uv.x += sin(uv.y * 10.0 + time) * 0.05 * agg;
                uv.y += cos(uv.x * 8.0 + time) * 0.03 * agg;
                gl_FragColor = texture2D(uTexture, uv);
                gl_FragColor.rgb *= 0.8;
            }
        """.trimIndent()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { midasInterpreter?.close() } catch (_: Exception) {}
        try { gpuDelegate?.close() } catch (_: Exception) {}
        try { poseDetector?.close() } catch (_: Exception) {}
    }
}
