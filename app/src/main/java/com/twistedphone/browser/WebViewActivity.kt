package com.twistedphone.browser

import android.annotation.SuppressLint
import android.graphics.*
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.twistedphone.R
import com.twistedphone.ai.MistralClient
import com.twistedphone.util.FileLogger
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

@SuppressLint("SetJavaScriptEnabled")
class WebViewActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "WebViewActivity"
        private const val MAX_WARP_MS = 15_000L
    }

    private lateinit var web: WebView
    private lateinit var overlay: FrameLayout
    private lateinit var overlayText: TextView
    private lateinit var overlayPct: TextView
    private lateinit var overlayBar: ProgressBar

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val http = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Build UI programmatically (keeps it independent of layout resource mismatches)
        val root = FrameLayout(this)
        setContentView(root, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.loadsImagesAutomatically = true
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = false
            override fun onPageFinished(view: WebView?, url: String?) {
                FileLogger.d(this@WebViewActivity, TAG, "onPageFinished: $url")
                startWarpFlow(url ?: "")
            }
        }
        web.webChromeClient = WebChromeClient()

        // Overlay (initially hidden)
        overlay = FrameLayout(this).apply {
            setBackgroundColor(0xFFFFFFFF.toInt()) // white overlay while warping
            visibility = View.GONE
            elevation = 50f
        }

        overlayText = TextView(this).apply {
            text = "LOADING"
            textSize = 20f
            setTextColor(0xFF000000.toInt())
            val p = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            p.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            p.topMargin = 40
            layoutParams = p
        }

        overlayPct = TextView(this).apply {
            text = "0%"
            textSize = 18f
            setTextColor(0xFF000000.toInt())
            val p = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            p.gravity = Gravity.CENTER
            layoutParams = p
        }

        overlayBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            val w = ViewGroup.LayoutParams.MATCH_PARENT
            val h = 10
            val p = FrameLayout.LayoutParams(w, h)
            p.gravity = Gravity.BOTTOM
            p.bottomMargin = 40
            layoutParams = p
        }

        overlay.addView(overlayText)
        overlay.addView(overlayPct)
        overlay.addView(overlayBar)

        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Load start page (use the provided intent data if present)
        val toLoad = intent?.dataString ?: "https://en.m.wikipedia.org/wiki/Main_Page"
        FileLogger.d(this, TAG, "Loading initial URL: $toLoad")
        web.loadUrl(toLoad)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    // ---------- UI helpers ----------
    private fun showOverlay() {
        runOnUiThread {
            overlayBar.progress = 0
            overlayPct.text = "0%"
            overlayText.text = "LOADING"
            overlay.visibility = View.VISIBLE
        }
    }
    private fun updateOverlayPercent(p: Int, label: String? = null) {
        val pct = p.coerceIn(0, 100)
        runOnUiThread {
            overlayBar.progress = pct
            overlayPct.text = "$pct%"
            if (!label.isNullOrBlank()) overlayText.text = label
        }
        FileLogger.d(this, TAG, "Warp progress: $pct% ($label)")
    }
    private fun hideOverlay() {
        runOnUiThread { overlay.visibility = View.GONE }
    }

    // ---------- Core warp flow ----------
    private fun startWarpFlow(url: String) {
        // Cancel previous job
        scope.coroutineContext[Job]?.children?.forEach { if (it.isActive) it.cancel() }
        showOverlay()
        updateOverlayPercent(0, "LOADING")

        scope.launch {
            val start = System.currentTimeMillis()
            // ensure we don't run forever
            val result = withTimeoutOrNull(MAX_WARP_MS) {
                try {
                    // 1) Extract text nodes and images via JS
                    updateOverlayPercent(3, "EXTRACTING PAGE")
                    val extractJs = """
                        (function(){
                          try {
                            var texts = [];
                            var imgs = [];
                            var all = document.querySelectorAll('body *');
                            for (var i=0;i<all.length;i++){
                              var el = all[i];
                              if (el && el.innerText && el.innerText.trim().length>10) {
                                texts.push(el.innerText.trim().slice(0,1500));
                              }
                            }
                            var imageEls = Array.from(document.images || []);
                            for (var i=0;i<imageEls.length;i++){
                              try { imgs.push(imageEls[i].src); } catch(e) {}
                            }
                            // send back a JSON with both arrays
                            return JSON.stringify({texts: texts.slice(0,200), images: imgs.slice(0,200)});
                          } catch(e) { return JSON.stringify({texts:[], images:[]}); }
                        })();
                    """.trimIndent()

                    val raw = evaluateJavascriptSuspend(extractJs, 8000L)
                    FileLogger.d(this@WebViewActivity, TAG, "JS extract raw: ${raw?.take(800)}")
                    val (texts, images) = parseExtractedJson(raw)

                    FileLogger.d(this@WebViewActivity, TAG, "Extracted text count=${texts.size}, images count=${images.size}")

                    // 2) Choose top text blocks (by length), send to Mistral, collect replacements
                    updateOverlayPercent(10, "TWISTING TEXTS")
                    val textsTop = texts.sortedByDescending { it.length }.take(10)
                    val replacements = mutableListOf<String?>()

                    for ((i, t) in textsTop.withIndex()) {
                        updateOverlayPercent(10 + ((i.toFloat() / (textsTop.size + 6)) * 40).toInt(), "TWISTING TEXT ${i+1}/${textsTop.size}")
                        FileLogger.d(this@WebViewActivity, TAG, "Sending text to Mistral (len=${t.length}): ${t.take(200)}")
                        val prompt = "Twist the following text to a darker, uncanny version but keep approximately the same length and sentence structure:\n\n$t"
                        val twisted = try {
                            MistralClient.twistTextWithRetries(prompt, 1, 3)
                        } catch (ex: Exception) {
                            FileLogger.e(this@WebViewActivity, TAG, "Mistral call exception: ${ex.message}")
                            null
                        }
                        FileLogger.d(this@WebViewActivity, TAG, "Mistral reply (len=${twisted?.length ?: 0}): ${twisted?.take(800) ?: "<null>"}")
                        replacements.add(twisted)
                    }

                    // 3) Replace text in DOM (match short preview to be safe)
                    updateOverlayPercent(55, "REPLACING TEXT")
                    for ((i, original) in textsTop.withIndex()) {
                        val repl = replacements.getOrNull(i)
                        if (repl.isNullOrBlank()) continue
                        val preview = original.substring(0, min(200, original.length))
                        val safePreview = jsEscape(preview)
                        val safeRepl = jsEscape(repl)
                        val jsReplace = """
                            (function(){
                              try {
                                var all = document.querySelectorAll('body *');
                                for (var j=0;j<all.length;j++){
                                  var el = all[j];
                                  if (el && el.innerText && el.innerText.indexOf($safePreview) !== -1) {
                                    el.innerText = el.innerText.replace($safePreview, $safeRepl);
                                    break;
                                  }
                                }
                              } catch(e) {}
                            })();
                        """.trimIndent()
                        evaluateJavascriptSuspend(jsReplace, 4000L)
                        FileLogger.d(this@WebViewActivity, TAG, "Replaced text preview: ${preview.take(80)} -> ${repl.take(80)}")
                    }

                    // 4) Process images (download, darken faces if present, insert as data URI). Limit to top 8 images.
                    val imgsToProcess = images.filter { it.isNotBlank() }.distinct().take(8)
                    updateOverlayPercent(65, "PROCESSING IMAGES")
                    var processedCount = 0
                    for ((idx, src) in imgsToProcess.withIndex()) {
                        updateOverlayPercent(65 + ((idx.toFloat() / max(1, imgsToProcess.size)) * 30).toInt(), "IMAGE ${idx+1}/${imgsToProcess.size}")
                        try {
                            FileLogger.d(this@WebViewActivity, TAG, "Downloading image: ${src.take(200)}")
                            val req = Request.Builder().url(src).build()
                            val resp = http.newCall(req).execute()
                            val bytes = resp.body?.bytes()
                            resp.close()
                            if (bytes == null || bytes.isEmpty()) {
                                FileLogger.d(this@WebViewActivity, TAG, "Image download empty for $src")
                                continue
                            }
                            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue

                            // Process: detect faces with android.media.FaceDetector (no ML Kit)
                            val processed = darkenFacesOrOverall(bmp)
                            val baos = ByteArrayOutputStream()
                            processed.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                            val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                            val dataUri = "data:image/jpeg;base64,$b64"

                            // Replace one matching image src in DOM with data URI
                            val safeSrc = jsEscape(src)
                            val jsReplaceImg = """
                                (function(){
                                  try {
                                    var imgs = Array.from(document.images || []);
                                    for (var k=0;k<imgs.length;k++){
                                      try {
                                        if (!imgs[k].src) continue;
                                        // match by contains to handle CORS-added absolute / resolved urls
                                        if (imgs[k].src.indexOf(${safeSrc}.replace(/^"(.*)"$/,'$1')) !== -1 || imgs[k].src === ${safeSrc}) {
                                          imgs[k].dataset._twisted = '1';
                                          imgs[k].src = '${dataUri}';
                                          break;
                                        }
                                      } catch(e){}
                                    }
                                  } catch(e){}
                                })();
                            """.trimIndent()
                            evaluateJavascriptSuspend(jsReplaceImg, 3000L)
                            processedCount++
                            FileLogger.d(this@WebViewActivity, TAG, "Replaced image $src -> dataURI (idx=$idx)")
                        } catch (ex: Exception) {
                            FileLogger.e(this@WebViewActivity, TAG, "Image processing failed for $src : ${ex.message}")
                        }
                    }
                    FileLogger.d(this@WebViewActivity, TAG, "Image replacements done: $processedCount")

                    // 5) finalize — bring overlay to 100% then hide shortly after
                    updateOverlayPercent(95, "FINALIZING")
                    // small finalization pause so user sees result
                    delay(250)
                    updateOverlayPercent(100, "DONE")
                    delay(350)
                    true
                } catch (ex: CancellationException) {
                    FileLogger.d(this@WebViewActivity, TAG, "Warp coroutine cancelled")
                    throw ex
                } catch (ex: Exception) {
                    FileLogger.e(this@WebViewActivity, TAG, "Warp pipeline exception: ${ex.message}")
                    false
                }
            }

            // If result == null it means timeout occurred
            if (result == null) {
                FileLogger.e(this@WebViewActivity, TAG, "Warp flow timed out after ${MAX_WARP_MS}ms")
                // reveal page anyway
            } else {
                FileLogger.d(this@WebViewActivity, TAG, "Warp flow result=$result")
            }

            // Always hide overlay now
            hideOverlay()
        }
    }

    // ----------------- Utilities -----------------

    // Evaluate JS on UI thread and suspend until result or timeout
    private suspend fun evaluateJavascriptSuspend(js: String, timeoutMs: Long = 8_000L): String? {
        return withContext(Dispatchers.Main) {
            try {
                suspendCancellableCoroutine<String?> { cont ->
                    try {
                        web.evaluateJavascript(js, ValueCallback { r ->
                            if (!cont.isActive) return@ValueCallback
                            // r is a JSON quoted string sometimes; we return as-is to parser which handles quotes
                            cont.resume(r) {}
                        })
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resumeWith(Result.failure(e))
                    }
                    cont.invokeOnCancellation {
                        // no extra cleanup required
                    }
                }
            } catch (e: Exception) {
                FileLogger.e(this@WebViewActivity, TAG, "evaluateJavascriptSuspend failed: ${e.message}")
                null
            }
        }
    }

    private fun parseExtractedJson(raw: String?): Pair<List<String>, List<String>> {
        try {
            if (raw == null) return Pair(emptyList(), emptyList())
            var s = raw.trim()
            // Unwrap quotes if JS returned a quoted JSON string
            if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
                s = s.substring(1, s.length - 1)
                    .replace("\\n", "")
                    .replace("\\t", "")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
            }
            if (s == "null" || s == "undefined" || s.isBlank()) return Pair(emptyList(), emptyList())
            val obj = org.json.JSONObject(s)
            val texts = mutableListOf<String>()
            val imgs = mutableListOf<String>()
            val jtexts = obj.optJSONArray("texts") ?: JSONArray()
            for (i in 0 until min(jtexts.length(), 200)) {
                val t = jtexts.optString(i, "").trim()
                if (t.isNotEmpty()) texts.add(t)
            }
            val jimgs = obj.optJSONArray("images") ?: JSONArray()
            for (i in 0 until min(jimgs.length(), 200)) {
                val u = jimgs.optString(i, "").trim()
                if (u.isNotEmpty()) imgs.add(u)
            }
            return Pair(texts, imgs)
        } catch (e: Exception) {
            FileLogger.e(this, TAG, "parseExtractedJson failed: ${e.message}")
            return Pair(emptyList(), emptyList())
        }
    }

    // Minimal JS string escaper
    private fun jsEscape(s: String): String {
        val esc = s.replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
        return "\"$esc\""
    }

    /**
     * Detect faces using android.media.FaceDetector (no external ML dependency).
     * If faces are detected we darken face bounding circles; otherwise apply gentle overall darkening.
     */
    private fun darkenFacesOrOverall(src: Bitmap): Bitmap {
        try {
            // android.media.FaceDetector requires RGB_565 and width even
            val w = if (src.width % 2 == 1) src.width - 1 else src.width
            val h = src.height
            val bmp565 = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
            val canvas = Canvas(bmp565)
            canvas.drawBitmap(src, 0f, 0f, null)

            val maxFaces = 5
            val detector = android.media.FaceDetector(w, h, maxFaces)
            val faces = arrayOfNulls<android.media.FaceDetector.Face>(maxFaces)
            val found = detector.findFaces(bmp565, faces)

            val out = src.copy(Bitmap.Config.ARGB_8888, true)
            val c = Canvas(out)
            val paint = Paint().apply {
                isAntiAlias = true
                style = Paint.Style.FILL
                color = Color.argb(200, 0, 0, 0) // strong darken over faces
            }

            if (found > 0) {
                FileLogger.d(this, TAG, "FaceDetector found $found faces (w=${w},h=${h})")
                for (i in 0 until found) {
                    val f = faces[i] ?: continue
                    val eyesDist = f.eyesDistance()
                    val mid = PointF()
                    f.getMidPoint(mid)
                    // convert to float center coordinates
                    val cx = mid.x
                    val cy = mid.y
                    val radius = eyesDist * 1.6f + 10f
                    c.drawCircle(cx, cy, radius, paint)
                }
            } else {
                // gentle overall darken + slight bluish tint for atmosphere
                FileLogger.d(this, TAG, "No faces found; applying overall atmosphere darken")
                val darkPaint = Paint()
                val cm = ColorMatrix().apply {
                    // darken and slightly blue shift: scale R,G,B then add small offset
                    set(floatArrayOf(
                        0.8f, 0f, 0f, 0f, 0f,
                        0f, 0.85f, 0f, 0f, 0f,
                        0f, 0f, 0.9f, 0f, 10f,
                        0f, 0f, 0f, 1f, 0f
                    ))
                }
                darkPaint.colorFilter = ColorMatrixColorFilter(cm)
                c.drawBitmap(out, 0f, 0f, darkPaint)
            }
            return out
        } catch (e: Exception) {
            FileLogger.e(this, TAG, "darkenFacesOrOverall failure: ${e.message}")
            // fallback: just darken whole image
            val out = src.copy(Bitmap.Config.ARGB_8888, true)
            val c = Canvas(out)
            val paint = Paint().apply { color = Color.argb(120, 0, 0, 0) }
            c.drawRect(0f, 0f, out.width.toFloat(), out.height.toFloat(), paint)
            return out
        }
    }
}
