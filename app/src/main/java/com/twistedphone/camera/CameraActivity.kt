package com.twistedphone.camera
import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Bundle
import android.util.Size
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.twistedphone.R
import com.twistedphone.TwistedApp
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import org.tensorflow.lite.task.vision.detector.Detection
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import java.io.File
import java.nio.ByteBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

class CameraActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var glSurfaceView: GLSurfaceView
    private val prefs = TwistedApp.instance.settingsPrefs
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var midasInterpreter: Interpreter? = null
    private var poseDetector: ObjectDetector? = null
    private var useEnhanced = false
    private var aggressiveness = 1
    private var depthMap: TensorBuffer? = null // low-res depth
    private var poseLandmarks: List<Detection>? = null // detections

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_camera)
        previewView = findViewById(R.id.previewView)
        glSurfaceView = findViewById(R.id.glSurfaceView)
        glSurfaceView.setEGLContextClientVersion(2)
        glSurfaceView.setRenderer(WarpRenderer(this))
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        useEnhanced = prefs.getBoolean("enhanced_camera", false)
        aggressiveness = prefs.getInt("aggressiveness", 1)
        findViewById<Button>(R.id.btnToggle).setOnClickListener {
            cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            startCamera()
        }
        findViewById<Button>(R.id.btnCapture).setOnClickListener { captureImage() }
        loadModels()
        startCamera()
    }

    private fun loadModels() {
        if (!useEnhanced) return
        try {
            val midasFile = File(filesDir, "models/midas.tflite")
            if (midasFile.exists()) {
                val options = Interpreter.Options()
                if (CompatibilityList().isDelegateSupportedOnThisDevice) {
                    options.addDelegate(GpuDelegate())
                }
                midasInterpreter = Interpreter(FileUtil.loadMappedFile(this, midasFile.path), options)
            }
            val poseFile = File(filesDir, "models/pose.tflite")
            if (poseFile.exists()) {
                val poseOptions = ObjectDetector.ObjectDetectorOptions.builder().setBaseOptions(org.tensorflow.lite.task.core.BaseOptions.builder().useGpu().build()).setMaxResults(1).build()
                poseDetector = ObjectDetector.createFromFileAndOptions(this, poseFile.path, poseOptions)
            }
        } catch (e: Exception) { useEnhanced = false }
    }

    private fun startCamera() {
        val providerF = ProcessCameraProvider.getInstance(this)
        providerF.addListener({
            val provider = providerF.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).setTargetResolution(Size(640, 480)).build()
            analysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { img -> processImage(img) }
            provider.unbindAll()
            provider.bindToLifecycle(this, cameraSelector, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processImage(image: ImageProxy) {
    if (useEnhanced) {
        // Convert ImageProxy to Bitmap
        val bitmap = imageProxyToBitmap(image) ?: return
        // MiDaS depth
        midasInterpreter?.let { interpreter ->
            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, 256, 256, true)
            val input = TensorImage.fromBitmap(scaledBitmap)
            val outputs = Array(1) { FloatArray(256 * 256) }
            interpreter.run(arrayOf(input.buffer), outputs)
            depthMap = TensorBuffer.createFixedSize(intArrayOf(1, 256, 256, 1), org.tensorflow.lite.DataType.FLOAT32)
            depthMap?.loadArray(outputs[0])
        }
        // Pose detection
        poseDetector?.let { detector ->
            poseLandmarks = detector.detect(TensorImage.fromBitmap(bitmap))
        }
    }
        image.close()
    }
    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        val planeProxy = image.planes[0]
        val buffer = planeProxy.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun captureImage() {
        // Capture from preview, apply warp (using current depth/pose), save to MediaStore
        previewView.bitmap?.let { bmp ->
            val warped = simpleWarp(bmp) // placeholder warp, or use shader snapshot if possible
            android.provider.MediaStore.Images.Media.insertImage(contentResolver, warped, "TwistedCapture", "Captured in Twisted Phone")
        }
    }

    private fun simpleWarp(bmp: Bitmap): Bitmap {
        // Simple CPU warp fallback if needed
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
        private val mvpMatrix = FloatArray(16)
        private val projMatrix = FloatArray(16)
        private val viewMatrix = FloatArray(16)
        private var surfaceTexture: SurfaceTexture? = null
        private val oesTexture = IntArray(1)

        init {
            GLES20.glGenTextures(1, oesTexture, 0)
            textureId = oesTexture[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            surfaceTexture = SurfaceTexture(textureId)
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            program = createProgram() // create shader program
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            val ratio = width.toFloat() / height
            Matrix.frustumM(projMatrix, 0, -ratio, ratio, -1f, 1f, 3f, 7f)
        }

        override fun onDrawFrame(gl: GL10?) {
            surfaceTexture?.updateTexImage()
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, 3f, 0f, 0f, 0f, 0f, 1f, 0f)
            Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, viewMatrix, 0)
            GLES20.glUseProgram(program)
            val timeLoc = GLES20.glGetUniformLocation(program, "time")
            GLES20.glUniform1f(timeLoc, (System.currentTimeMillis() % 1000000) / 1000f)
            val aggLoc = GLES20.glGetUniformLocation(program, "agg")
            GLES20.glUniform1f(aggLoc, aggressiveness.toFloat())
            // Bind OES texture
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            val texLoc = GLES20.glGetUniformLocation(program, "uTexture")
            GLES20.glUniform1i(texLoc, 0)
            // Draw quad (vertices for full screen quad)
            val vBuffer = java.nio.FloatBuffer.wrap(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            val posLoc = GLES20.glGetAttribLocation(program, "vPosition")
            GLES20.glEnableVertexAttribArray(posLoc)
            GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, vBuffer)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(posLoc)
        }

        private fun createProgram(): Int {
            val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexCode)
            val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentCode)
            val prog = GLES20.glCreateProgram()
            GLES20.glAttachShader(prog, vertexShader)
            GLES20.glAttachShader(prog, fragmentShader)
            GLES20.glLinkProgram(prog)
            return prog
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
                gl_FragColor.rgb *= 0.8; // darken
            }
        """.trimIndent()
    }

    override fun onDestroy() {
        super.onDestroy()
        midasInterpreter?.close()
        poseDetector?.close()
    }
}
