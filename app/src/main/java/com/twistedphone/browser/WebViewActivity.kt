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
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                Logger.d(TAG, "onPageStarted: $url")
                url?.let { runOnUiThread { urlInput.setText(it) } }
                runOnUiThread {
                    showOverlay(true, "LOADING")
                    updateProgressUI(0)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                Logger.d(TAG, "onPageFinished: $url")
                startTransform(url ?: "")
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                // raw page load progress shown in progress bar but warp progress will overwrite it
                mainHandler.post { progressBar.progress = newProgress }
            }
        }

        btnGo.setOnClickListener { loadUrlFromInput() }
        urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { loadUrlFromInput(); true } else false
        }

        // initial
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
     * Main pipeline:
     *  - Extract visible text snippets and image srcs via evaluateJavascript (safe visible-only extraction)
     *  - Send top snippets to MistralClient (logged), fallback locally if needed
     *  - Replace texts in DOM using injected JS
     *  - Download and locally process up to 10 images (darken faces) and replace src with data: URIs
     *  - Keeps overlay for up to MAX_TIMEOUT_MS and ensures overlay is removed
     */
    private fun startTransform(url: String) {
        transformJob?.cancel()
        showOverlay(true, "LOADING")
        transformJob = coroutineScope.launch {
            val startTime = System.currentTimeMillis()
            val deadline = startTime + MAX_TIMEOUT_MS
            try {
                Logger.d(TAG, "startTransform: extracting DOM for $url")

                val rawJsResult = extractTextsAndImagesRawJs()
                Logger.d(TAG, "Raw JS result: ${rawJsResult.take(2000)}")

                val cleanedJson = cleanEvaluateJavascriptResult(rawJsResult)
                Logger.d(TAG, "Cleaned JSON extract: ${cleanedJson.take(2000)}")

                val texts = parseTextsFromExtract(cleanedJson).toMutableList()
                val imgs = parseImagesFromExtract(cleanedJson).toMutableList()

                Logger.d(TAG, "Parsed texts=${texts.size} images=${imgs.size}")

                // fallback if nothing found
                if (texts.isEmpty()) {
                    Logger.d(TAG, "No texts found in structured extract; trying body.innerText fallback")
                    val bodyText = extractBodyInnerText()
                    Logger.d(TAG, "body.innerText length=${bodyText.length}")
                    if (bodyText.trim().isNotEmpty()) {
                        val paras = bodyText.split("\n").map { it.trim() }.filter { it.length > 40 }
                        texts.addAll(paras.take(6))
                        Logger.d(TAG, "Added ${paras.take(6).size} paragraphs from body text fallback")
                    }
                }

                // Log previews of what we'll send
                for ((i, t) in texts.withIndex()) Logger.d(TAG, "Text[$i] preview: ${t.take(160).replace("\n", " ")}")
                for ((i, s) in imgs.withIndex()) Logger.d(TAG, "Image[$i] src: $s")

                val totalWork = max(1, texts.size + imgs.size)
                var done = 0

                // TEXT transforms
                val replacements = mutableListOf<Pair<String, String>>()
                for (orig in texts) {
                    if (!isActive) return@launch
                    if (System.currentTimeMillis() > deadline) {
                        Logger.d(TAG, "Timeout reached while processing texts")
                        break
                    }

                    var twisted: String? = null
                    try {
                        Logger.d(TAG, "Sending to Mistral (preview): ${orig.take(300).replace("\n", " ")}")
                        twisted = MistralClient.twistTextWithRetries(
                            "Rewrite the following text into a darker, stranger, unsettling version while keeping similar length and sentence structure. RETURN ONLY the rewritten text:\n\n$orig",
                            1, 4
                        )
                        Logger.d(TAG, "Mistral reply (raw): ${twisted?.take(2000) ?: "NULL"}")
                    } catch (e: Exception) {
                        Logger.e(TAG, "Mistral call failed: ${e.message}")
                        twisted = null
                    }

                    if (twisted.isNullOrBlank()) {
                        twisted = localTextTwistFallback(orig)
                        Logger.d(TAG, "Local fallback twist preview: ${twisted.take(200)}")
                    }

                    replacements.add(orig to twisted)
                    done++
                    updateProgressUI(((done.toFloat() / totalWork.toFloat()) * 100f).toInt())
                }

                // Inject text replacements into DOM
                if (replacements.isNotEmpty()) {
                    val js = buildTextReplaceJs(replacements)
                    Logger.d(TAG, "Injecting text-replace JS (length=${js.length}). Preview:\n${js.take(800)}")
                    runOnUiThread {
                        web.evaluateJavascript(js) { res ->
                            Logger.d(TAG, "evaluateJavascript (text replace) returned: ${res ?: "null"}")
                        }
                    }
                } else {
                    Logger.d(TAG, "No text replacements to inject")
                }

                // IMAGE processing: download -> darken faces -> data URI replacement
                for (src in imgs) {
                    if (!isActive) return@launch
                    if (System.currentTimeMillis() > deadline) {
                        Logger.d(TAG, "Timeout reached while processing images")
                        break
                    }
                    try {
                        Logger.d(TAG, "Downloading image: $src")
                        val bmp = downloadBitmap(src)
                        if (bmp == null) {
                            Logger.e(TAG, "Image download null for $src")
                        } else {
                            Logger.d(TAG, "Downloaded image ${bmp.width}x${bmp.height}")
                            val processed = darkenFaces(bmp)
                            Logger.d(TAG, "Processed image ${processed.width}x${processed.height}")
                            val dataUri = bitmapToDataUri(processed)
                            Logger.d(TAG, "Data URI length for image: ${dataUri.length}")
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
                            Logger.d(TAG, "Injecting JS to replace image src for $src")
                            runOnUiThread {
                                web.evaluateJavascript(injectJs) { r ->
                                    Logger.d(TAG, "evaluateJavascript (img replace) returned: ${r ?: "null"}")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Logger.e(TAG, "image transform failed for $src: ${e.message}")
                    }
                    done++
                    updateProgressUI(((done.toFloat() / totalWork.toFloat()) * 100f).toInt())
                }

                Logger.d(TAG, "startTransform complete loop done=$done total=$totalWork")

            } catch (e: Exception) {
                Logger.e(TAG, "startTransform job error: ${e.message}")
            } finally {
                val elapsed = System.currentTimeMillis() - startTime
                val remaining = MAX_TIMEOUT_MS - elapsed
                if (remaining > 0) delay(remaining)
                runOnUiThread { showOverlay(false, null) }
            }
        }

        // hard safety timeout
        mainHandler.postDelayed({
            if (transformJob?.isActive == true) {
                transformJob?.cancel()
                Logger.d(TAG, "transformJob cancelled by timeout handler")
                runOnUiThread { showOverlay(false, null) }
            }
        }, MAX_TIMEOUT_MS)
    }

    // Return raw string evaluateJavascript returned (useful for debugging)
    private suspend fun extractTextsAndImagesRawJs(): String {
        return suspendCancellableCoroutine { cont ->
            val js = """
                (function(){
                  function visible(el){
                    try{
                      if(!el) return false;
                      if(el.offsetWidth===0 && el.offsetHeight===0) return false;
                      var rects = el.getClientRects();
                      if(!rects || rects.length===0) return false;
                      if(window.getComputedStyle){
                        var s = window.getComputedStyle(el);
                        if(s && (s.visibility==='hidden' || s.display==='none')) return false;
                      }
                      return true;
                    }catch(e){ return false; }
                  }
                  function collectTextNodes(){
                    var arr=[];
                    var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_ELEMENT, null, false);
                    var n;
                    while((n = walker.nextNode())){
                      try{
                        if(!visible(n)) continue;
                        var txt = (n.innerText || n.textContent || "").trim();
                        if(txt && txt.length > 20){
                          var rect = n.getBoundingClientRect();
                          var area = (rect.width||0) * (rect.height||0);
                          arr.push({text: txt, area: area, size: txt.length});
                        }
                      } catch(e){}
                    }
                    arr.sort(function(a,b){ return (b.area||0) - (a.area||0) });
                    var top = arr.slice(0,10).map(function(x){ return x.text; });
                    return top;
                  }
                  function collectImgs(){
                    var imgs=[];
                    var els = document.getElementsByTagName('img');
                    for(var i=0;i<els.length;i++){
                      try{ if(els[i].src && visible(els[i])) imgs.push(els[i].src); }catch(e){}
                    }
                    return imgs.slice(0,10);
                  }
                  return JSON.stringify({texts: collectTextNodes(), images: collectImgs()});
                })();
            """.trimIndent()
            try {
                web.evaluateJavascript(js) { result ->
                    if (!cont.isActive) return@evaluateJavascript
                    cont.resume(result ?: "") {}
                }
            } catch (e: Exception) {
                if (cont.isActive) cont.resumeWith(Result.failure(e))
            }
        }
    }

    private suspend fun extractBodyInnerText(): String {
        return suspendCancellableCoroutine { cont ->
            val js = "(function(){ try{ return (document.body && document.body.innerText) ? document.body.innerText : ''; }catch(e){ return ''; } })();"
            try {
                web.evaluateJavascript(js) { result ->
                    if (!cont.isActive) return@evaluateJavascript
                    val cleaned = cleanEvaluateJavascriptResult(result ?: "")
                    cont.resume(cleaned) {}
                }
            } catch (e: Exception) {
                if (cont.isActive) cont.resumeWith(Result.failure(e))
            }
        }
    }

    // Unwrap the WebView quoted string and other common artifacts
    private fun cleanEvaluateJavascriptResult(raw: String): String {
        try {
            if (raw.isBlank()) return "{}"
            var s = raw
            if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
                s = s.substring(1, s.length - 1)
                    .replace("\\n", "")
                    .replace("\\t", "")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
            }
            if (s == "null" || s == "undefined") return "{}"
            return s
        } catch (e: Exception) {
            Logger.e(TAG, "cleanEvaluateJavascriptResult failed: ${e.message}")
            return "{}"
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

    private fun updateProgressUI(progress: Int) {
        mainHandler.post {
            val pct = progress.coerceIn(0, 100)
            loadingPercent.text = "$pct%"
            progressBar.progress = pct
            loadingText.text = "TWISTING — $pct%"
            Logger.d(TAG, "UI progress updated: $pct%")
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
            Logger.d(TAG, "showOverlay: $show text=$text")
        }
    }

    // Download an image (with logging). Returns Bitmap or null
    private fun downloadBitmap(src: String): android.graphics.Bitmap? {
        try {
            val url = URL(src)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
                instanceFollowRedirects = true
            }
            conn.connect()
            conn.inputStream.use { stream ->
                val bmp = android.graphics.BitmapFactory.decodeStream(stream)
                Logger.d(TAG, "downloadBitmap success for $src -> ${bmp?.width}x${bmp?.height}")
                return bmp
            }
        } catch (e: Exception) {
            Logger.e(TAG, "downloadBitmap failed for $src: ${e.message}")
            return null
        }
    }

    // Best-effort image face darkening (fast local)
    private fun darkenFaces(src: android.graphics.Bitmap): android.graphics.Bitmap {
        val bmp = src.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
        try {
            val width = bmp.width
            val height = bmp.height
            val canvas = android.graphics.Canvas(bmp)
            val paint = android.graphics.Paint()
            paint.isAntiAlias = true
            paint.color = android.graphics.Color.argb(170, 6, 12, 30)
            val rect1 = android.graphics.RectF(width * 0.18f, height * 0.12f, width * 0.82f, height * 0.52f)
            canvas.drawOval(rect1, paint)
            paint.color = android.graphics.Color.argb(120, 0, 0, 0)
            val rect2 = android.graphics.RectF(width * 0.05f, height * 0.55f, width * 0.45f, height * 0.95f)
            canvas.drawOval(rect2, paint)
            Logger.d(TAG, "darkenFaces applied to bitmap ${width}x${height}")
        } catch (e: Exception) {
            Logger.e(TAG, "darkenFaces error: ${e.message}")
        }
        return bmp
    }

    private fun bitmapToDataUri(bmp: android.graphics.Bitmap): String {
        val baos = ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, baos)
        val bytes = baos.toByteArray()
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return "data:image/jpeg;base64,$b64"
    }

    private fun localTextTwistFallback(s: String): String {
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
        val out = if (replaced.length <= s.length * 2) replaced else replaced.take(s.length + 10)
        Logger.d(TAG, "localTextTwistFallback produced len=${out.length}")
        return out
    }

    private fun escapeJsString(s: String): String {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("'", "\\'")
    }

    /**
     * Build JS that walks the document and replaces occurrences of the extracted snippets.
     * Uses split/join to avoid regex special char pitfalls.
     */
    private fun buildTextReplaceJs(replacements: List<Pair<String, String>>): String {
        val sb = StringBuilder()
        sb.append("(function(){")
        sb.append("function walk(node){var child=node.firstChild; while(child){ if(child.nodeType===3){")
        for ((orig, repl) in replacements) {
            val o = escapeForJsString(orig.take(200))
            val r = escapeJsString(repl)
            sb.append("try{ if(child.nodeValue && child.nodeValue.indexOf(${jsonString(o)})!==-1) child.nodeValue = child.nodeValue.split(${jsonString(o)}).join(${jsonString(r)}); }catch(e){}")
        }
        sb.append("} else if(child.nodeType===1){ walk(child); } child = child.nextSibling;} }")
        sb.append("walk(document.body); })();")
        return sb.toString()
    }

    private fun escapeForJsString(s: String): String {
        return s.replace("\n", " ").replace("\r", " ").trim()
    }

    private fun jsonString(s: String): String {
        val esc = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
        return "\"$esc\""
    }
}
