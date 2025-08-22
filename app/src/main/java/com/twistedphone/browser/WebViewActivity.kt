package com.twistedphone.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.twistedphone.R
import com.twistedphone.ai.MistralClient
import com.twistedphone.util.Logger
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max
import kotlin.math.min

@SuppressLint("SetJavaScriptEnabled")
class WebViewActivity : AppCompatActivity() {
    private val TAG = "WebViewActivity"

    private lateinit var web: WebView
    private lateinit var urlInput: EditText
    private lateinit var btnGo: Button
    private lateinit var loadingOverlay: android.view.View
    private lateinit var loadingText: TextView
    private lateinit var loadingPercent: TextView
    private lateinit var progressBar: ProgressBar

    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val MAX_TIMEOUT_MS = 15_000L
    private var transformJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webview)

        web = findViewById(R.id.webview)
        urlInput = findViewById(R.id.urlInput)
        btnGo = findViewById(R.id.btnGo)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        loadingText = findViewById(R.id.loadingText)
        loadingPercent = findViewById(R.id.loadingPercent)
        progressBar = findViewById(R.id.progressBar)

        web.settings.javaScriptEnabled = true
        web.settings.loadsImagesAutomatically = true

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                // let WebView handle the URL
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                Logger.d(TAG, "onPageStarted: $url")
                url?.let { runOnUiThread { urlInput.setText(it) } }
                // show white overlay immediately
                runOnUiThread {
                    showOverlay(true, "LOADING")
                    updateProgressUI(0)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                Logger.d(TAG, "onPageFinished: $url")
                // start warp process (runs in background)
                startTransform(url ?: "")
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                // keep WebView load progress in the bar (separate from warp progress)
                mainHandler.post { progressBar.progress = newProgress }
            }
        }

        btnGo.setOnClickListener { loadUrlFromInput() }
        urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { loadUrlFromInput(); true } else false
        }

        // initial page
        web.loadUrl("https://en.m.wikipedia.org/wiki/Main_Page")
    }

    private fun loadUrlFromInput() {
        var url = urlInput.text.toString().trim()
        if (url.isEmpty()) return
        if (!url.startsWith("http")) url = "https://$url"
        web.loadUrl(url)
    }

    override fun onDestroy() {
        super.onDestroy()
        transformJob?.cancel()
        coroutineScope.cancel()
    }

    /**
     * Starts the transform pipeline for the currently loaded page.
     * Shows overlay, attempts Mistral for text, falls back to local twist if needed.
     * Also processes up to top 10 images by downloading and locally darkening faces.
     * Ensures overlay is removed after MAX_TIMEOUT_MS.
     */
    private fun startTransform(url: String) {
        transformJob?.cancel()
        showOverlay(true, "LOADING")
        transformJob = coroutineScope.launch {
            val start = System.currentTimeMillis()
            val deadline = start + MAX_TIMEOUT_MS
            try {
                Logger.d(TAG, "startTransform: extracting DOM for $url")

                val extractJson = extractTextsAndImages()
                val texts = parseTextsFromExtract(extractJson)
                val imgs = parseImagesFromExtract(extractJson)
                Logger.d(TAG, "startTransform: found texts=${texts.size} imgs=${imgs.size}")

                val totalWork = max(1, texts.size + imgs.size)
                var done = 0

                // 1) text transformations
                val replacements = mutableListOf<Pair<String, String>>()
                for (orig in texts) {
                    if (!isActive) return@launch
                    if (System.currentTimeMillis() > deadline) {
                        Logger.d(TAG, "Timeout during text transforms")
                        break
                    }
                    var twisted: String? = null
                    try {
                        // attempt Mistral with a clear prompt; using your helper which should wrap API key
                        twisted = MistralClient.twistTextWithRetries("Rewrite the following text into a darker, stranger, unsettling version while keeping similar length and sentence structure. RETURN ONLY the rewritten text:\n\n$orig", 1, 4)
                    } catch (e: Exception) {
                        Logger.e(TAG, "Mistral failure: ${e.message}")
                    }

                    if (twisted.isNullOrBlank()) {
                        // local fallback: simple but reliable transformation to ensure page is altered
                        twisted = localTextTwistFallback(orig)
                    }

                    replacements.add(orig to twisted)
                    done++
                    updateProgress((done.toFloat() / totalWork.toFloat() * 100f).toInt())
                }

                // apply text replacements via JS in the page (best-effort)
                if (replacements.isNotEmpty()) {
                    val js = buildTextReplaceJs(replacements)
                    runOnUiThread { web.evaluateJavascript(js, null) }
                }

                // 2) image processing (download -> face darken -> inject data URI)
                for (src in imgs) {
                    if (!isActive) return@launch
                    if (System.currentTimeMillis() > deadline) {
                        Logger.d(TAG, "Timeout during image transforms")
                        break
                    }
                    try {
                        val bmp = downloadBitmap(src)
                        if (bmp != null) {
                            val processed = darkenFaces(bmp)
                            val dataUri = bitmapToDataUri(processed)
                            val injectJs = """
                                (function(){
                                  var imgs = document.getElementsByTagName('img');
                                  for(var i=0;i<imgs.length;i++){
                                    try{
                                      var el = imgs[i];
                                      if(!el._tw_replaced && el.src && el.src.indexOf('${escapeJsString(src)}') !== -1){
                                        el.dataset._tw_original = el.src;
                                        el.src = "${escapeJsString(dataUri)}";
                                        el._tw_replaced = true;
                                      }
                                    }catch(e){}
                                  }
                                })();
                            """.trimIndent()
                            runOnUiThread { web.evaluateJavascript(injectJs, null) }
                        }
                    } catch (e: Exception) {
                        Logger.e(TAG, "image transform failed for $src: ${e.message}")
                    }
                    done++
                    updateProgress((done.toFloat() / totalWork.toFloat() * 100f).toInt())
                }

            } catch (e: Exception) {
                Logger.e(TAG, "startTransform job error: ${e.message}")
            } finally {
                // ensure overlay removed after at most MAX_TIMEOUT_MS from start
                val elapsed = System.currentTimeMillis() - start
                val remaining = MAX_TIMEOUT_MS - elapsed
                if (remaining > 0) delay(remaining)
                runOnUiThread { showOverlay(false, null) }
            }
        }

        // safety: hard timeout enforcement
        mainHandler.postDelayed({
            if (transformJob?.isActive == true) {
                transformJob?.cancel()
                Logger.d(TAG, "transformJob cancelled by timeout handler")
                runOnUiThread { showOverlay(false, null) }
            }
        }, MAX_TIMEOUT_MS)
    }

    // update progress UI from background
    private fun updateProgress(pct: Int) {
        mainHandler.post {
            loadingPercent.text = "$pct%"
            progressBar.progress = pct
            loadingText.text = "TWISTING — $pct%"
        }
    }

    private fun showOverlay(show: Boolean, text: String?) {
        mainHandler.post {
            loadingOverlay.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
            if (show) {
                loadingText.text = text ?: "LOADING"
                loadingPercent.text = "0%"
                progressBar.progress = 0
            }
        }
    }

    /**
     * Runs JS on the page that extracts large text nodes and image srcs, returns a JSON string.
     */
    private suspend fun extractTextsAndImages(): String {
        return suspendCancellableCoroutine { cont ->
            val js = """
                (function(){
                  function collectTextNodes(){
                    var arr = [];
                    var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_ELEMENT, null, false);
                    var n;
                    while(n = walker.nextNode()){
                      try {
                        var txt = (n.innerText || n.textContent || "").trim();
                        if(txt && txt.length > 20){
                          arr.push({text: txt, size: txt.length});
                        }
                      } catch(e){}
                    }
                    arr.sort(function(a,b){return b.size - a.size});
                    var top = arr.slice(0,10).map(function(x){ return x.text; });
                    return top;
                  }
                  function collectImgs(){
                    var imgs = [];
                    var els = document.getElementsByTagName('img');
                    for(var i=0;i<els.length;i++){
                      try{ if(els[i].src) imgs.push(els[i].src); }catch(e){}
                    }
                    return imgs.slice(0,10);
                  }
                  return JSON.stringify({texts: collectTextNodes(), images: collectImgs()});
                })();
            """.trimIndent()
            try {
                web.evaluateJavascript(js) { result ->
                    if (!cont.isActive) return@evaluateJavascript
                    try {
                        val cleaned = when (result) {
                            "null", "undefined" -> "{}"
                            else -> {
                                // unescape Android's returned quoted string if needed
                                if (result.length >= 2 && result.startsWith("\"") && result.endsWith("\"")) {
                                    result.substring(1, result.length - 1)
                                        .replace("\\n", "")
                                        .replace("\\\"", "\"")
                                        .replace("\\\\", "\\")
                                } else result
                            }
                        }
                        cont.resume(cleaned, null)
                    } catch (e: Exception) { cont.resumeWith(Result.failure(e)) }
                }
            } catch (e: Exception) {
                if (cont.isActive) cont.resumeWith(Result.failure(e))
            }
        }
    }

    private fun parseTextsFromExtract(json: String): List<String> {
        return try {
            val obj = JSONObject(json)
            val arr = obj.optJSONArray("texts") ?: JSONArray()
            val out = mutableListOf<String>()
            for (i in 0 until min(arr.length(), 10)) {
                val s = arr.optString(i, "").trim()
                if (s.isNotEmpty()) out.add(s)
            }
            out
        } catch (e: Exception) {
            Logger.e(TAG, "parseTextsFromExtract failed: ${e.message}")
            emptyList()
        }
    }

    private fun parseImagesFromExtract(json: String): List<String> {
        return try {
            val obj = JSONObject(json)
            val arr = obj.optJSONArray("images") ?: JSONArray()
            val out = mutableListOf<String>()
            for (i in 0 until min(arr.length(), 10)) {
                val s = arr.optString(i, "").trim()
                if (s.isNotEmpty()) out.add(s)
            }
            out
        } catch (e: Exception) {
            Logger.e(TAG, "parseImagesFromExtract failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Simple HTTP download of an image into a Bitmap (with timeouts).
     */
    private fun downloadBitmap(src: String): android.graphics.Bitmap? {
        try {
            val url = URL(src)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                instanceFollowRedirects = true
            }
            conn.connect()
            conn.inputStream.use { stream ->
                return android.graphics.BitmapFactory.decodeStream(stream)
            }
        } catch (e: Exception) {
            Logger.e(TAG, "downloadBitmap failed: ${e.message}")
            return null
        }
    }

    /**
     * Best-effort local face darkening. Lightweight and fast: draws a semi-opaque oval over central area(s).
     * Not a production face detector — it's a fallback to ensure images become uncanny even offline.
     */
    private fun darkenFaces(src: android.graphics.Bitmap): android.graphics.Bitmap {
        val bmp = src.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
        try {
            val w = bmp.width
            val h = bmp.height
            val canvas = android.graphics.Canvas(bmp)
            val paint = android.graphics.Paint()
            paint.isAntiAlias = true
            // Slightly bluish darken to match camera atmosphere
            paint.color = android.graphics.Color.argb(160, 10, 20, 40)
            // draw a couple of ovals based on face heuristics (upper center, lower-center)
            val rect1 = android.graphics.RectF(w * 0.18f, h * 0.12f, w * 0.82f, h * 0.52f)
            canvas.drawOval(rect1, paint)
            // small second darker patch bottom-left to create uncanny mosaic
            paint.color = android.graphics.Color.argb(110, 0, 0, 0)
            val rect2 = android.graphics.RectF(w * 0.05f, h * 0.55f, w * 0.45f, h * 0.95f)
            canvas.drawOval(rect2, paint)
        } catch (e: Exception) {
            Logger.e(TAG, "darkenFaces failed: ${e.message}")
        }
        return bmp
    }

    private fun bitmapToDataUri(bmp: android.graphics.Bitmap): String {
        val baos = ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, baos)
        val bytes = baos.toByteArray()
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return "data:image/jpeg;base64,$b64"
    }

    // local text twist fallback (guaranteed to produce some change)
    private fun localTextTwistFallback(s: String): String {
        // reverse words, randomly replace some vowels, then truncate/pad to similar length
        val words = s.split("\\s+".toRegex()).filter { it.isNotBlank() }
        val rev = words.reversed().joinToString(" ")
        val replaced = rev.map { ch ->
            when (ch.toLowerCase()) {
                'a' -> '@'
                'e' -> '3'
                'o' -> '0'
                'i' -> '1'
                's' -> '$'
                else -> ch
            }
        }.joinToString("")
        // try to keep similar length
        return if (replaced.length <= s.length * 2) replaced else replaced.take(s.length + 10)
    }

    private fun escapeJsString(s: String): String {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("'", "\\'")
    }

    private fun buildTextReplaceJs(replacements: List<Pair<String, String>>): String {
        // Replace occurrences of the original top snippets with the twisted version.
        // Use exact string replacement on text nodes (best-effort).
        val sb = StringBuilder()
        sb.append("(function(){")
        sb.append("function walk(node){var child=node.firstChild; while(child){ if(child.nodeType===3){")
        for ((orig, repl) in replacements) {
            val o = escapeJsString(orig.take(200))
            val r = escapeJsString(repl)
            // use global replace with regex (escape quotes)
            sb.append("try{ child.nodeValue = child.nodeValue.replace(new RegExp(${jsonString(o)}, 'g'), ${jsonString(r)}); }catch(e){}")
        }
        sb.append("} else if(child.nodeType===1){ walk(child); } child = child.nextSibling;} }")
        sb.append("walk(document.body); })();")
        return sb.toString()
    }

    // helper to produce a JS JSON-style string literal
    private fun jsonString(s: String): String {
        val esc = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
        return "\"$esc\""
    }
}
