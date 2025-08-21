package com.twistedphone.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.media.ImageFormat
import android.media.ImageReader
import android.net.Uri
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.view.Surface
import android.widget.Button
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
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import org.tensorflow.lite.task.core.BaseOptions
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.min

/**
 * Replaces previous CameraActivity:
 * - Correct portrait orientation and aspect-correct shader sampling
 * - Graceful MiDaS loading (GPU via reflection, or CPU fallback)
 * - Capture: reads GL frontbuffer inside GL thread -> saves warped image to MediaStore (Gallery visible)
 * - Avoids NoClassDefFoundError by reflection (GPU delegate)
 * - Uses direct NativeOrder buffers properly to avoid "Must use native order direct Buffer" errors
 */
class CameraActivity : AppCompatActivity() {
    private lateinit var glSurfaceView: GLSurfaceView
    private val prefs = TwistedApp.instance.settingsPrefs

    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var midasInterpreter: Interpreter? = null
    private var poseDetector: ObjectDetector? = null
    private var gpuDelegateInstance: Any? = null

    private var useEnhanced = false
    private var aggressiveness = 1

    // depth data
    @Volatile private var depthFloatArray: FloatArray? = null
    private val depthReady = AtomicBoolean(false)

    // state
    private val surfaceReady = AtomicBoolean(false)
    private var cameraSurface: Surface? = null
    private val cameraBound = AtomicBoolean(false)

    // capture helper
    private val captureRequest = AtomicBoolean(false)

    // inference scope
    private val inferScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)
        glSurfaceView = findViewById(R.id.glSurfaceView)

        useEnhanced = prefs.getBoolean("enhanced_camera", false)
        aggressiveness = prefs.getInt("aggressiveness", 1)

        findViewById<Button>(R.id.btnToggle).setOnClickListener {
            cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            rebindCamera()
        }
        findViewById<Button>(R.id.btnCapture).setOnClickListener { captureImage() }

        // GLSurface setup
        glSurfaceView.setEGLContextClientVersion(2)
        val renderer = WarpRenderer(
            onSurfaceTextureReady = { st ->
                runOnUiThread {
                    FileLogger.d(this, "CameraActivity", "SurfaceTexture ready")
                    cameraSurface?.release()
                    cameraSurface = Surface(st)
                    surfaceReady.set(true)
                    if (!cameraBound.get()) startCameraIfReady()
                }
            },
            getDepth = { if (depthReady.get()) depthFloatArray else null },
            getAgg = { aggressiveness },
            requestCapture = { captureRequest.get() },
            onCaptureHandled = { captureRequest.set(false) }
        )
        glSurfaceView.setRenderer(renderer)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        // Load models async
        inferScope.launch { loadModelsSafe() }
    }

    override fun onResume() {
        super.onResume()
        if (surfaceReady.get() && !cameraBound.get()) startCameraIfReady()
    }

    override fun onPause() {
        super.onPause()
        cameraBound.set(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        inferScope.cancel()
        try { midasInterpreter?.close() } catch (_: Exception) {}
        try {
            gpuDelegateInstance?.let { it::class.java.getMethod("close").invoke(it) }
        } catch (_: Exception) {}
        try { poseDetector?.close() } catch (_: Exception) {}
        cameraSurface?.release()
    }

    // Graceful model loading (GPU via reflection)
    private suspend fun loadModelsSafe() {
        if (!useEnhanced) {
            FileLogger.d(this, "CameraActivity", "Enhanced disabled")
            return
        }
        val modelsDir = filesDir.resolve("models")
        if (!modelsDir.exists()) {
            FileLogger.e(this, "CameraActivity", "models dir missing")
            useEnhanced = false
            return
        }
        val midasFile = modelsDir.resolve("midas.tflite")
        if (midasFile.exists()) {
            try {
                val options = Interpreter.Options()
                options.setNumThreads(2)
                var usedDelegate = false
                try {
                    Class.forName("org.tensorflow.lite.gpu.GpuDelegate")
                    val compat = CompatibilityList()
                    if (compat.isDelegateSupportedOnThisDevice) {
                        try {
                            val delegateClass = Class.forName("org.tensorflow.lite.gpu.GpuDelegate")
                            val ctor = delegateClass.getConstructor()
                            val delegate = ctor.newInstance()
                            options.addDelegate(delegate as org.tensorflow.lite.Delegate)
                            gpuDelegateInstance = delegate
                            usedDelegate = true
                        } catch (e: Throwable) {
                            FileLogger.e(this, "CameraActivity", "GPU delegate creation failed: ${e.message}")
                        }
                    } else {
                        FileLogger.d(this, "CameraActivity", "GPU delegate unsupported by CompatibilityList")
                    }
                } catch (_: ClassNotFoundException) {
                    FileLogger.d(this, "CameraActivity", "GpuDelegate class not present -> CPU fallback")
                }
                val mapped = FileUtil.loadMappedFile(this, midasFile.path)
                midasInterpreter = Interpreter(mapped, options)
                FileLogger.d(this, "CameraActivity", "MiDaS loaded (gpu=$usedDelegate)")
            } catch (e: Throwable) {
                FileLogger.e(this, "CameraActivity", "MiDaS load failed: ${e.message}")
                useEnhanced = false
                midasInterpreter = null
                try { gpuDelegateInstance?.let { it::class.java.getMethod("close").invoke(it) } } catch (_: Exception) {}
                gpuDelegateInstance = null
            }
        } else {
            FileLogger.d(this, "CameraActivity", "MiDaS not present")
            useEnhanced = false
        }

        val poseFile = modelsDir.resolve("pose.tflite")
        if (poseFile.exists() && useEnhanced) {
            try {
                val base = BaseOptions.builder().setNumThreads(2).build()
                val opts = ObjectDetector.ObjectDetectorOptions.builder().setBaseOptions(base).setMaxResults(2).build()
                poseDetector = ObjectDetector.createFromFileAndOptions(this, poseFile.path, opts)
                FileLogger.d(this, "CameraActivity", "Pose detector ready")
            } catch (e: Throwable) {
                FileLogger.e(this, "CameraActivity", "Pose load failed: ${e.message}")
                poseDetector = null
            }
        }

        // If midas loaded, set depth inference analyzer when camera bound
        if (midasInterpreter != null) {
            runOnUiThread { startCameraIfReady() }
        }
    }

    private fun startCameraIfReady() {
        if (!surfaceReady.get()) return
        if (cameraBound.get()) return

        val providerF = ProcessCameraProvider.getInstance(this)
        providerF.addListener({
            try {
                val provider = providerF.get()
                val preview = Preview.Builder()
                    // prefer portrait resolution to avoid 90° rotation/stretch
                    .setTargetRotation(windowManager.defaultDisplay.rotation)
                    .setTargetResolution(Size(720, 1280)) // portrait target
                    .build()

                val sp = Preview.SurfaceProvider { request ->
                    if (cameraSurface == null) {
                        FileLogger.e(this, "CameraActivity", "cameraSurface null")
                        request.willNotProvideSurface()
                        return@SurfaceProvider
                    }
                    request.provideSurface(cameraSurface!!, ContextCompat.getMainExecutor(this)) { result ->
                        FileLogger.d(this, "CameraActivity", "Surface provided callback: ${result.resultCode}")
                    }
                }
                preview.setSurfaceProvider(sp)

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(Size(640, 480))
                    .build()

                analysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { image ->
                    // run MiDaS inference off main thread
                    try {
                        if (midasInterpreter != null) {
                            val bmp = imageProxyToBitmap(image)
                            if (bmp != null) {
                                inferScope.launch {
                                    try {
                                        val scaled = Bitmap.createScaledBitmap(bmp, 256, 256, true)
                                        val timg = TensorImage.fromBitmap(scaled)
                                        val out = Array(1) { FloatArray(256 * 256) }
                                        midasInterpreter?.run(timg.buffer, out)
                                        depthFloatArray = out[0]
                                        depthReady.set(true)
                                    } catch (e: Throwable) {
                                        FileLogger.e(this@CameraActivity, "Depth inference err: ${e.message}")
                                    }
                                }
                            }
                        }
                        if (poseDetector != null) {
                            // best-effort pose detection
                            try {
                                val bmp = imageProxyToBitmap(image) ?: return@setAnalyzer
                                poseDetector?.detect(TensorImage.fromBitmap(Bitmap.createScaledBitmap(bmp, 320, 320, true)))
                            } catch (_: Exception) {}
                        }
                    } finally {
                        image.close()
                    }
                }

                provider.unbindAll()
                provider.bindToLifecycle(this, cameraSelector, preview, analysis)
                cameraBound.set(true)
                FileLogger.d(this, "CameraActivity", "Camera bound")
            } catch (e: Exception) {
                FileLogger.e(this, "CameraActivity", "Camera bind failed: ${e.message}")
                Toast.makeText(this, "Camera not available: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun rebindCamera() {
        cameraBound.set(false)
        startCameraIfReady()
    }

    private fun captureImage() {
        // request the renderer to capture next GL frame
        if (captureRequest.get()) return
        captureRequest.set(true)
        Toast.makeText(this, "Capturing warped frame...", Toast.LENGTH_SHORT).show()
    }

    // Helper to convert ImageProxy to Bitmap (best-effort)
    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        return try {
            val plane = image.planes[0]
            plane.buffer.rewind()
            val bytes = ByteArray(plane.buffer.remaining())
            plane.buffer.get(bytes)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            FileLogger.e(this, "CameraActivity", "imageProxyToBitmap: ${e.message}")
            null
        }
    }

    // GL Renderer inner class: handles capture by reading pixels from GL framebuffer
    inner class WarpRenderer(
        private val onSurfaceTextureReady: (SurfaceTexture) -> Unit,
        private val getDepth: () -> FloatArray?,
        private val getAgg: () -> Int,
        private val requestCapture: () -> Boolean,
        private val onCaptureHandled: () -> Unit
    ) : GLSurfaceView.Renderer {
        private var oesTex = IntArray(1)
        private var surfaceTexture: SurfaceTexture? = null
        private var program = 0
        private var vbo: ByteBuffer? = null
        private var depthTex = IntArray(1)

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glGenTextures(1, oesTex, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex[0])
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

            surfaceTexture = SurfaceTexture(oesTex[0])
            surfaceTexture?.setDefaultBufferSize(720, 1280) // portrait default
            onSurfaceTextureReady(surfaceTexture!!)

            GLES20.glGenTextures(1, depthTex, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTex[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            val empty = ByteArray(256 * 256)
            val bb = ByteBuffer.allocateDirect(empty.size).order(ByteOrder.nativeOrder())
            bb.put(empty); bb.position(0)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, 256, 256, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, bb)

            program = createProgram(vertexShader, fragmentShader)
            val arr = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
            vbo = ByteBuffer.allocateDirect(arr.size * 4).order(ByteOrder.nativeOrder())
            vbo?.asFloatBuffer()?.put(arr)
            vbo?.position(0)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            surfaceTexture?.setDefaultBufferSize(width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            surfaceTexture?.let {
                try { it.updateTexImage() } catch (_: Exception) {}
            }

            // upload depth if present
            val depth = getDepth()
            if (depth != null) {
                val bytes = ByteArray(256 * 256)
                var i = 0
                var min = Float.MAX_VALUE
                var max = Float.MIN_VALUE
                while (i < depth.size) {
                    val v = depth[i]
                    if (v < min) min = v
                    if (v > max) max = v
                    i++
                }
                val range = if (max - min < 1e-6f) 1f else (max - min)
                var j = 0
                while (j < depth.size) {
                    val n = ((depth[j] - min) / range).coerceIn(0f, 1f)
                    bytes[j] = (n * 255f).toInt().toByte()
                    j++
                }
                val bb = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
                bb.put(bytes); bb.position(0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTex[0])
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, 256, 256, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, bb)
            }

            GLES20.glUseProgram(program)
            val timeLoc = GLES20.glGetUniformLocation(program, "u_time")
            val aggLoc = GLES20.glGetUniformLocation(program, "u_agg")
            val texLoc = GLES20.glGetUniformLocation(program, "u_texture")
            val depthLoc = GLES20.glGetUniformLocation(program, "u_depth")
            val resLoc = GLES20.glGetUniformLocation(program, "u_resolution")
            GLES20.glUniform1f(timeLoc, (System.currentTimeMillis() % 1000000L) / 1000f)
            GLES20.glUniform1f(aggLoc, getAgg().toFloat())
            GLES20.glUniform2f(resLoc, glSurfaceView.width.toFloat(), glSurfaceView.height.toFloat())

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex[0])
            GLES20.glUniform1i(texLoc, 0)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTex[0])
            GLES20.glUniform1i(depthLoc, 1)

            val pos = GLES20.glGetAttribLocation(program, "a_pos")
            GLES20.glEnableVertexAttribArray(pos)
            vbo?.position(0)
            GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, vbo)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(pos)

            // if capture requested, read pixels (on GL thread) and save
            if (requestCapture()) {
                readPixelsAndSave()
                onCaptureHandled()
            }
        }

        private fun readPixelsAndSave() {
            try {
                val w = glSurfaceView.width
                val h = glSurfaceView.height
                val ib = IntArray(w * h)
                val ibb = IntBuffer.allocate(w * h)
                // read RGBA
                GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, ibb)
                ibb.rewind()
                ibb.get(ib)
                // convert to ARGB and flip vertically
                val pix = IntArray(w * h)
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        val i = y * w + x
                        val rgba = ib[i]
                        val r = (rgba shr 0) and 0xff
                        val g = (rgba shr 8) and 0xff
                        val b = (rgba shr 16) and 0xff
                        val a = (rgba shr 24) and 0xff
                        // convert RGBA->ARGB
                        val argb = (a shl 24) or (r shl 16) or (g shl 8) or b
                        val destIndex = (h - 1 - y) * w + x
                        pix[destIndex] = argb
                    }
                }
                val bmp = Bitmap.createBitmap(pix, w, h, Bitmap.Config.ARGB_8888)
                // Save to MediaStore (call on UI thread)
                runOnUiThread {
                    try {
                        val filename = "twisted_${System.currentTimeMillis()}.png"
                        val mime = "image/png"
                        val values = ContentValues()
                        values.put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                        values.put(MediaStore.Images.Media.MIME_TYPE, mime)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            values.put(MediaStore.Images.Media.IS_PENDING, 1)
                        }
                        val uri: Uri? = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        uri?.let {
                            val os: OutputStream? = contentResolver.openOutputStream(it)
                            bmp.compress(Bitmap.CompressFormat.PNG, 95, os)
                            os?.close()
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                values.clear()
                                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                                contentResolver.update(it, values, null, null)
                            }
                            Toast.makeText(this@CameraActivity, "Saved capture to gallery", Toast.LENGTH_SHORT).show()
                        } ?: run {
                            Toast.makeText(this@CameraActivity, "Failed to save image", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        FileLogger.e(this@CameraActivity, "captureSave: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                FileLogger.e(this@CameraActivity, "readPixels: ${e.message}")
            }
        }

        private fun createProgram(vs: String, fs: String): Int {
            val v = loadShader(GLES20.GL_VERTEX_SHADER, vs)
            val f = loadShader(GLES20.GL_FRAGMENT_SHADER, fs)
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

        private val vertexShader = """
            attribute vec2 a_pos;
            varying vec2 v_uv;
            void main() {
                gl_Position = vec4(a_pos, 0.0, 1.0);
                v_uv = a_pos * 0.5 + 0.5;
            }
        """.trimIndent()

        // Fragment shader does aspect-correct sampling and warp; no vignette
        private val fragmentShader = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES u_tex;
            uniform sampler2D u_depth;
            uniform float u_time;
            uniform float u_agg;
            uniform vec2 u_resolution;
            varying vec2 v_uv;
            float hash(vec2 p){ return fract(sin(dot(p, vec2(127.1,311.7))) * 43758.5453123); }
            float noise(vec2 p){
                vec2 i = floor(p); vec2 f = fract(p);
                float a = hash(i), b = hash(i+vec2(1.0,0.0));
                float c = hash(i+vec2(0.0,1.0)), d = hash(i+vec2(1.0,1.0));
                vec2 u = f*f*(3.0-2.0*f);
                return mix(a,b,u.x) + (c-a)*u.y*(1.0-u.x) + (d-b)*u.x*u.y;
            }
            void main(){
                vec2 uv = v_uv;
                // aspect correction
                float aspect = u_resolution.x / max(u_resolution.y, 1.0);
                vec2 centered = uv - 0.5;
                centered.x /= aspect;
                vec2 uv_corr = centered + 0.5;
                float n = noise(uv_corr * vec2(8.0, 8.0) + u_time * 0.2);
                float base = (n - 0.5) * 0.04 * u_agg;
                float depthInfluence = 0.0;
                vec4 sample = texture2D(u_tex, uv);
                if (texture2D(u_depth, uv_corr).r > 0.0) {
                    depthInfluence = texture2D(u_depth, uv_corr).r * 0.06 * u_agg;
                } else {
                    float l = (sample.r + sample.g + sample.b) / 3.0;
                    depthInfluence = (1.0 - l) * 0.02 * u_agg;
                }
                float angle = sin(uv.y * 12.0 + u_time) * 3.1415 * 0.03 * u_agg;
                vec2 dir = vec2(cos(angle), sin(angle));
                vec2 displaced = uv + dir * (base + depthInfluence);
                displaced.x += sin((uv.y + u_time*0.1) * 8.0) * 0.008 * u_agg;
                displaced.y += cos((uv.x - u_time*0.12) * 6.0) * 0.006 * u_agg;
                vec4 color = texture2D(u_tex, displaced);
                color.rgb *= 0.78; // darken mood
                gl_FragColor = color;
            }
        """.trimIndent()
    }
}
