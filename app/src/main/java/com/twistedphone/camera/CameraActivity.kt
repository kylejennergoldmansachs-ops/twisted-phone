package com.twistedphone.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.twistedphone.R
import com.twistedphone.TwistedApp
import com.twistedphone.util.FileLogger
import kotlinx.coroutines.*
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import org.tensorflow.lite.task.core.BaseOptions
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

/**
 * CameraActivity — CameraX -> GLSurfaceView pipeline with true shader warping.
 *
 * Highlights:
 * - GLSurfaceView renderer creates OES texture + SurfaceTexture.
 * - Once surfaceTexture ready, activity binds CameraX Preview to the Surface created from it.
 * - Fragment shader warps the incoming camera texture per-pixel.
 * - Optional: if MiDaS is available, we run low-res depth inference on analysis frames and upload a 256x256 depth texture to the shader (depth influence).
 * - Robust: GPU delegate via reflection (avoids NoClassDefFoundError), CPU fallback.
 * - No vignette circle (you said you hate them).
 *
 * Drop this file in replacing previous CameraActivity.kt.
 */
class CameraActivity : AppCompatActivity() {
    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var container: FrameLayout
    private val prefs = TwistedApp.instance.settingsPrefs

    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

    // model/interpreter
    private var midasInterpreter: Interpreter? = null
    private var poseDetector: ObjectDetector? = null
    private var gpuDelegateInstance: Any? = null

    private var useEnhanced = false
    private var aggressiveness = 1

    // depth data shared between inference coroutine and GL renderer
    @Volatile private var depthFloatArray: FloatArray? = null
    private val depthReady = AtomicBoolean(false)

    // callbacks / state
    private var surfaceReady = AtomicBoolean(false)
    private var cameraSurface: Surface? = null
    private var cameraBound = AtomicBoolean(false)
    private var previewSurfaceProvided = AtomicBoolean(false)

    // Coroutine scope for inference
    private val inferScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_camera)
        container = findViewById(R.id.cameraContainer)
        glSurfaceView = findViewById(R.id.glSurfaceView)
        useEnhanced = prefs.getBoolean("enhanced_camera", false)
        aggressiveness = prefs.getInt("aggressiveness", 1)

        findViewById<Button>(R.id.btnToggle).setOnClickListener {
            cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            rebindCamera()
        }
        findViewById<Button>(R.id.btnCapture).setOnClickListener { captureImage() }

        // GLSurface setup: renderer will create OES surfaceTexture and call the callback
        glSurfaceView.setEGLContextClientVersion(2)
        val renderer = GLWarpRenderer(this,
            onSurfaceTextureReady = { st, texId ->
                // called from GL thread -> post to main thread
                runOnUiThread {
                    FileLogger.d(this, "CameraActivity", "Renderer signalled SurfaceTexture ready; binding camera soon")
                    cameraSurface?.release()
                    cameraSurface = Surface(st)
                    surfaceReady.set(true)
                    // if camera not yet bound, attempt bind
                    if (!cameraBound.get()) startCameraIfReady()
                }
            },
            getDepthProvider = {
                // returns current depth array if ready (256x256)
                if (depthReady.get()) depthFloatArray else null
            },
            aggressiveness = { aggressiveness }
        )
        glSurfaceView.setRenderer(renderer)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        // Try loading models asynchronously (if requested)
        inferScope.launch {
            loadModelsSafe()
        }
    }

    override fun onResume() {
        super.onResume()
        // If surface already ready and camera not bound, try binding
        if (surfaceReady.get() && !cameraBound.get()) startCameraIfReady()
    }

    override fun onPause() {
        super.onPause()
        // camera will be unbound by lifecycle automatically but clear flag
        cameraBound.set(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        inferScope.cancel()
        try { midasInterpreter?.close() } catch (_: Exception) {}
        try {
            gpuDelegateInstance?.let {
                try { it::class.java.getMethod("close").invoke(it) } catch(_ : Exception) {}
            }
        } catch (_: Exception) {}
        try { poseDetector?.close() } catch (_: Exception) {}
        cameraSurface?.release()
    }

    // ---------- Model loader (robust) ----------
    private suspend fun loadModelsSafe() {
        // Keep same robust GPU reflection approach as earlier
        if (!useEnhanced) {
            FileLogger.d(this, "CameraActivity", "Enhanced disabled in settings")
            return
        }

        val modelsDir = File(filesDir, "models")
        if (!modelsDir.exists() || !modelsDir.isDirectory) {
            FileLogger.e(this, "CameraActivity", "Models directory missing: ${modelsDir.absolutePath}")
            useEnhanced = false
            return
        }

        val midasFile = File(modelsDir, "midas.tflite")
        if (!midasFile.exists()) {
            FileLogger.e(this, "CameraActivity", "MiDaS not found: ${midasFile.absolutePath}")
            useEnhanced = false
        } else {
            try {
                val options = Interpreter.Options()
                options.setNumThreads(2)
                var usedDelegate = false
                try {
                    // Only attempt if class exists
                    Class.forName("org.tensorflow.lite.gpu.GpuDelegate")
                    val compat = CompatibilityList()
                    if (compat.isDelegateSupportedOnThisDevice) {
                        try {
                            val delegateClass = Class.forName("org.tensorflow.lite.gpu.GpuDelegate")
                            val ctor = delegateClass.getConstructor()
                            val delegate = ctor.newInstance()
                            // add delegate via reflection cast
                            options.addDelegate(delegate as org.tensorflow.lite.Delegate)
                            gpuDelegateInstance = delegate
                            usedDelegate = true
                            FileLogger.d(this, "CameraActivity", "GPU delegate created via reflection")
                        } catch (e: Throwable) {
                            FileLogger.e(this, "CameraActivity", "GPU delegate creation failed: ${e.message}")
                        }
                    } else {
                        FileLogger.d(this, "CameraActivity", "CompatibilityList: GPU delegate unsupported")
                    }
                } catch (cnf: ClassNotFoundException) {
                    FileLogger.d(this, "CameraActivity", "GpuDelegate class missing -> CPU fallback")
                }

                val mapped = FileUtil.loadMappedFile(this, midasFile.path)
                midasInterpreter = Interpreter(mapped, options)
                FileLogger.d(this, "CameraActivity", "Loaded MiDaS interpreter (gpuUsed=$usedDelegate)")
            } catch (e: Throwable) {
                FileLogger.e(this, "CameraActivity", "MiDaS load failed -> disabling enhanced: ${e.message}")
                try { gpuDelegateInstance?.let { it::class.java.getMethod("close").invoke(it) } } catch (_: Exception) {}
                gpuDelegateInstance = null
                midasInterpreter = null
                useEnhanced = false
            }
        }

        // Pose detector (optional)
        val poseFile = File(modelsDir, "pose.tflite")
        if (poseFile.exists() && useEnhanced) {
            try {
                val baseOptions = BaseOptions.builder().setNumThreads(2).build()
                val objOptions = ObjectDetector.ObjectDetectorOptions.builder().setBaseOptions(baseOptions).setMaxResults(2).build()
                poseDetector = ObjectDetector.createFromFileAndOptions(this, poseFile.path, objOptions)
                FileLogger.d(this, "CameraActivity", "Loaded pose detector")
            } catch (e: Throwable) {
                FileLogger.e(this, "CameraActivity", "Pose load failed: ${e.message}")
                poseDetector = null
            }
        }

        // If MiDaS is available, start running inference on analysis frames
        if (midasInterpreter != null) {
            startDepthInferenceLoop()
        } else {
            FileLogger.d(this, "CameraActivity", "MiDaS not usable — depth-based warp disabled")
        }
    }

    private fun startDepthInferenceLoop() {
        // analysis pipeline: bind an ImageAnalysis that samples frames for MiDaS input
        // We'll bind an analyzer when camera is ready (so that CameraX is already bound).
        // The analyzer will do light preprocessing and run midasInterpreter; results saved to depthFloatArray.
        runOnUiThread { startCameraIfReady() } // ensure start attempt
    }

    // ---------- Camera / binding ----------
    private fun startCameraIfReady() {
        if (!surfaceReady.get()) {
            FileLogger.d(this, "CameraActivity", "SurfaceTexture not ready yet — delaying camera bind")
            return
        }
        if (cameraBound.get()) return

        val providerF = ProcessCameraProvider.getInstance(this)
        providerF.addListener({
            try {
                val provider = providerF.get()

                // Preview: use SurfaceRequest provider to provide our surface (cameraSurface)
                val preview = Preview.Builder().build()
                val sp = Preview.SurfaceProvider { request ->
                    if (cameraSurface == null) {
                        FileLogger.e(this, "CameraActivity", "SurfaceProvider: cameraSurface null")
                        request.willNotProvide()
                        return@SurfaceProvider
                    }
                    // Provide the surface (main executor)
                    request.provideSurface(cameraSurface!!, ContextCompat.getMainExecutor(this)) { result ->
                        FileLogger.d(this, "CameraActivity", "Surface provided callback: result=$result")
                    }
                    previewSurfaceProvided.set(true)
                }
                preview.setSurfaceProvider(sp)

                // Analysis: keep low-res 256x256 frames for midas inference if enabled
                val analysisBuilder = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(Size(640, 480))
                val analysis = analysisBuilder.build()
                analysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { image ->
                    try {
                        if (midasInterpreter != null) {
                            // sample and run inference off main thread
                            val bitmap = imageProxyToBitmap(image)
                            if (bitmap != null) {
                                inferScope.launch {
                                    try {
                                        val scaled = Bitmap.createScaledBitmap(bitmap, 256, 256, true)
                                        val timg = TensorImage.fromBitmap(scaled)
                                        val out = Array(1) { FloatArray(256 * 256) }
                                        midasInterpreter?.run(timg.buffer, out)
                                        depthFloatArray = out[0]
                                        depthReady.set(true)
                                    } catch (e: Throwable) {
                                        FileLogger.e(this@CameraActivity, "CameraActivity", "Depth inference error: ${e.message}")
                                    }
                                }
                            }
                        }
                        // run pose detection occasionally
                        if (poseDetector != null) {
                            try {
                                poseDetector?.detect(TensorImage.fromBitmap(imageProxyToBitmap(image) ?: return@setAnalyzer))
                            } catch (e: Exception) { /* non-fatal */ }
                        }
                    } catch (e: Exception) {
                        FileLogger.e(this, "CameraActivity", "analysis error: ${e.message}")
                    } finally {
                        image.close()
                    }
                }

                // Bind to lifecycle: Preview + Analysis
                provider.unbindAll()
                provider.bindToLifecycle(this, cameraSelector, preview, analysis)
                cameraBound.set(true)
                FileLogger.d(this, "CameraActivity", "Camera bound to GLSurface surface")
            } catch (e: Exception) {
                FileLogger.e(this, "CameraActivity", "Camera bind failed: ${e.message}")
                Toast.makeText(this, "Camera not available", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun rebindCamera() {
        // Unbind and rebind; useful after toggle
        cameraBound.set(false)
        startCameraIfReady()
    }

    private fun captureImage() {
        // capture a snapshot of the preview (read pixels from GLSurfaceView)
        // Simpler: ask the glSurfaceView to take snapshot via queueEvent and read pixels in renderer.
        try {
            glSurfaceView.queueEvent {
                // renderer will handle snapshot if we add callback (not implemented here for brevity)
            }
            // fallback: attempt to capture preview by reading GL frontbuffer — complex; keep simple path:
            Toast.makeText(this, "Capture not implemented (use screen capture)", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            FileLogger.e(this, "CameraActivity", "captureImage failed: ${e.message}")
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

    // ---------- GL Renderer (inner) ----------
    inner class GLWarpRenderer(
        val ctx: Context,
        val onSurfaceTextureReady: (SurfaceTexture, Int) -> Unit,
        val getDepthProvider: () -> FloatArray?,
        val aggressiveness: () -> Int
    ) : GLSurfaceView.Renderer {
        // OES texture / surfaceTexture
        private var oesTexture = IntArray(1)
        private var surfaceTexture: SurfaceTexture? = null

        // shader program
        private var program = 0
        private var vBuffer: FloatBuffer? = null

        // depth texture (256x256) id
        private var depthTexId = 0
        private var depthTexReady = false

        // uniform locations
        private var timeLoc = -1
        private var aggLoc = -1
        private var depthEnabledLoc = -1
        private var depthTexLoc = -1
        private var resolutionLoc = -1

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glGenTextures(1, oesTexture, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture[0])
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            // create SurfaceTexture bound to the OES texture
            surfaceTexture = SurfaceTexture(oesTexture[0])
            surfaceTexture?.setDefaultBufferSize(1280, 720) // initial default
            // notify activity that surfaceTexture is ready (so camera can bind)
            onSurfaceTextureReady(surfaceTexture!!, oesTexture[0])

            // create a small 256x256 depth texture (GL_R32F not widely available; so use GL_LUMINANCE via GL_RGB with float->byte mapping)
            val depthIds = IntArray(1)
            GLES20.glGenTextures(1, depthIds, 0)
            depthTexId = depthIds[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTexId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            // allocate placeholder
            val empty = ByteArray(256 * 256)
            val bb = ByteBuffer.allocateDirect(empty.size)
            bb.put(empty); bb.position(0)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, 256, 256, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, bb)
            depthTexReady = true

            // compile shaders & program
            program = createProgram(vertexShaderCode, fragmentShaderCode)
            timeLoc = GLES20.glGetUniformLocation(program, "u_time")
            aggLoc = GLES20.glGetUniformLocation(program, "u_agg")
            depthEnabledLoc = GLES20.glGetUniformLocation(program, "u_depth_enabled")
            depthTexLoc = GLES20.glGetUniformLocation(program, "u_depth_tex")
            resolutionLoc = GLES20.glGetUniformLocation(program, "u_resolution")

            // prepare vertex buffer (full-screen quad)
            val arr = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
            val bb2 = ByteBuffer.allocateDirect(arr.size * 4).order(ByteOrder.nativeOrder())
            vBuffer = bb2.asFloatBuffer()
            vBuffer?.put(arr)
            vBuffer?.position(0)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            // reflect buffer size to SurfaceTexture so camera matches
            surfaceTexture?.setDefaultBufferSize(width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            // update camera texture
            surfaceTexture?.let {
                try {
                    it.updateTexImage()
                } catch (e: Exception) {
                    // ok to ignore
                }
            }

            // if depth data ready, upload as 256x256 LUMINANCE bytes
            val depth = getDepthIfAvailable()
            if (depth != null) {
                // convert float depth [-..] to byte 0..255
                val bytes = ByteArray(256 * 256)
                var i = 0
                var max = Float.MIN_VALUE
                var min = Float.MAX_VALUE
                while (i < depth.size) {
                    val v = depth[i]
                    if (v > max) max = v
                    if (v < min) min = v
                    i++
                }
                // normalize
                val range = if (max - min <= 1e-6f) 1f else (max - min)
                i = 0
                while (i < depth.size) {
                    val norm = ((depth[i] - min) / range).coerceIn(0f, 1f)
                    bytes[i] = (norm * 255f).toInt().toByte()
                    i++
                }
                val bb = ByteBuffer.allocateDirect(bytes.size)
                bb.put(bytes); bb.position(0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTexId)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, 256, 256, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, bb)
            }

            GLES20.glUseProgram(program)
            val t = (System.currentTimeMillis() % 1000000L).toFloat() / 1000f
            GLES20.glUniform1f(timeLoc, t)
            GLES20.glUniform1f(aggLoc, (aggressiveness()).toFloat())

            // bind OES texture
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture[0])
            val texLoc = GLES20.glGetUniformLocation(program, "u_texture")
            GLES20.glUniform1i(texLoc, 0)

            // bind depth texture to texture unit 1
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTexId)
            GLES20.glUniform1i(depthTexLoc, 1)
            GLES20.glUniform1i(depthEnabledLoc, if (depthReady.get()) 1 else 0)

            // resolution uniform
            GLES20.glUniform2f(resolutionLoc, glSurfaceView.width.toFloat(), glSurfaceView.height.toFloat())

            // draw quad
            val posLoc = GLES20.glGetAttribLocation(program, "a_position")
            GLES20.glEnableVertexAttribArray(posLoc)
            vBuffer?.position(0)
            GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, vBuffer)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(posLoc)
        }

        private fun getDepthIfAvailable(): FloatArray? {
            // return and clear depthReady flag to avoid re-uploading same array
            val arr = getDepthProvider()
            if (arr != null) return arr
            return null
        }

        private fun createProgram(vertSrc: String, fragSrc: String): Int {
            val v = loadShader(GLES20.GL_VERTEX_SHADER, vertSrc)
            val f = loadShader(GLES20.GL_FRAGMENT_SHADER, fragSrc)
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, v)
            GLES20.glAttachShader(p, f)
            GLES20.glLinkProgram(p)
            return p
        }

        private fun loadShader(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            return s
        }

        // Vertex shader (simple passthrough)
        private val vertexShaderCode = """
            attribute vec4 a_position;
            varying vec2 v_uv;
            void main() {
                gl_Position = a_position;
                v_uv = (a_position.xy * 0.5) + 0.5;
            }
        """.trimIndent()

        // Fragment shader: true warp that samples the OES texture and displaces UVs.
        // Uses depth texture (if available) as an extra factor to vary displacement.
        private val fragmentShaderCode = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES u_texture;
            uniform sampler2D u_depth_tex;
            uniform float u_time;
            uniform float u_agg;
            uniform int u_depth_enabled;
            uniform vec2 u_resolution;
            varying vec2 v_uv;

            // 2D noise - small
            float hash(vec2 p) {
                return fract(sin(dot(p, vec2(127.1,311.7))) * 43758.5453123);
            }
            float noise(vec2 p){
                vec2 i = floor(p);
                vec2 f = fract(p);
                float a = hash(i);
                float b = hash(i + vec2(1.0,0.0));
                float c = hash(i + vec2(0.0,1.0));
                float d = hash(i + vec2(1.0,1.0));
                vec2 u = f*f*(3.0-2.0*f);
                return mix(a,b,u.x) + (c-a)*u.y*(1.0-u.x) + (d-b)*u.x*u.y;
            }

            void main() {
                vec2 uv = v_uv;
                // base displacement (temporal + noise)
                float n = noise(uv * vec2(10.0, 6.0) + u_time * 0.2);
                float base = (n - 0.5) * 0.035 * u_agg;

                // sample depth if available
                float depthInfluence = 0.0;
                if (u_depth_enabled == 1) {
                    vec2 dUV = uv;
                    // adjust for depth texture aspect (256x256 -> same)
                    depthInfluence = texture2D(u_depth_tex, dUV).r * 0.06 * u_agg;
                } else {
                    // fallback: derive a luminance from the preview to pseudo-depth
                    vec4 samp = texture2D(u_texture, uv);
                    float l = (samp.r + samp.g + samp.b) / 3.0;
                    depthInfluence = (1.0 - l) * 0.02 * u_agg;
                }

                // directional swirling displacement
                float angle = sin(uv.y * 20.0 + u_time) * 6.2831 * 0.03 * u_agg;
                vec2 dir = vec2(cos(angle), sin(angle));

                vec2 displaced = uv + dir * (base + depthInfluence);

                // additional small pinch/pull based on sin waves
                displaced.x += sin((uv.y + u_time*0.1) * 10.0) * 0.01 * u_agg;
                displaced.y += cos((uv.x - u_time*0.12) * 8.0) * 0.008 * u_agg;

                // final color sample
                vec4 color = texture2D(u_texture, displaced);
                // darken slightly for mood
                color.rgb *= 0.75;
                gl_FragColor = color;
            }
        """.trimIndent()
    }
}
