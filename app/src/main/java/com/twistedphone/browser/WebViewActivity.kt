package com.twistedphone.browser

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import com.twistedphone.R
import com.twistedphone.TwistedApp
import com.twistedphone.ai.MistralClient
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * Reworked WebViewActivity:
 * - Adds a small address bar programmatically (EditText + Go + Back)
 * - Shows a white overlay while transforming DOM
 * - Extracts top-10 largest text nodes and top images
 * - Sends text to Mistral via MistralClient.twistTextWithRetries(...) with retry/backoff
 * - Downloads images, darkens faces locally via MistralClient.transformImageToDataUri(...)
 * - Replaces DOM client-side using XPath-based node lookup
 * - Caches transformations per-URL
 */
class WebViewActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var progress: ProgressBar
    private lateinit var overlay: View
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val client = MistralClient()
    private val prefs = TwistedApp.instance.settingsPrefs
    private val cache = mutableMapOf<String, String>() // url -> js replacement (cached)

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_webview)

        web = findViewById(R.id.webview)
        progress = findViewById(R.id.progressBar)

        // basic webview setup
        web.settings.javaScriptEnabled = true
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (url == null) return
                // show overlay + progress while transforming
                showOverlay()
                performTransformations(url)
            }
        }

        // Insert a light address bar (programmatically) above the WebView so we don't have to update XML
        addAddressBar()

        // initial page
        web.loadUrl("https://en.wikipedia.org/wiki/Main_Page")
    }

    private fun addAddressBar() {
        val root = findViewById<ViewGroup>(android.R.id.content)
        // container for bar
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#F2F2F2"))
            elevation = 8f
            setPadding(8, 8, 8, 8)
        }
        val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.gravity = Gravity.TOP
        bar.layoutParams = params

        val urlInput = EditText(this).apply {
            hint = "https://"
            minLines = 1
            maxLines = 1
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnGo = Button(this).apply {
            text = "Go"
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val btnBack = Button(this).apply {
            text = "Back"
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        btnGo.setOnClickListener {
            val u = urlInput.text.toString().trim()
            if (u.isNotEmpty()) {
                val finalUrl = if (u.startsWith("http")) u else "https://$u"
                web.loadUrl(finalUrl)
            }
        }
        btnBack.setOnClickListener {
            if (web.canGoBack()) web.goBack()
        }

        bar.addView(urlInput)
        bar.addView(btnGo)
        bar.addView(btnBack)

        // place it above content; content view is a FrameLayout so we can add this
        val parent = root.getChildAt(0) as? ViewGroup
        parent?.addView(bar)

        // adjust the existing webview top margin so the bar won't cover it
        web.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = (56 * resources.displayMetrics.density).toInt()
        }
    }

    private fun showOverlay() {
        if (::overlay.isInitialized && overlay.parent != null) return
        val root = findViewById<ViewGroup>(android.R.id.content)
        overlay = View(this).apply {
            setBackgroundColor(Color.WHITE)
            alpha = 1f
            isClickable = true
        }
        val p = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        (root.getChildAt(0) as ViewGroup).addView(overlay, p)
        progress.visibility = View.VISIBLE
    }

    private fun hideOverlay() {
        try {
            progress.visibility = View.GONE
            if (::overlay.isInitialized) {
                val root = findViewById<ViewGroup>(android.R.id.content)
                (root.getChildAt(0) as ViewGroup).removeView(overlay)
            }
        } catch (_: Exception) {}
    }

    private fun performTransformations(url: String) {
        // apply cached replacements if present
        cache[url]?.let { js ->
            web.evaluateJavascript(js, null)
            hideOverlay()
            return
        }

        // JS: collect text nodes (parent xpath, text) and images (src, xpath). We ask for top 10 texts by length.
        val jsExtract = """
            (function(){
                function getXPath(node) {
                    if(!node) return '';
                    var path = '';
                    while (node && node.nodeType == Node.ELEMENT_NODE) {
                        var sib = node.previousSibling, idx = 1;
                        while (sib) { if (sib.nodeType == Node.ELEMENT_NODE && sib.tagName == node.tagName) idx++; sib = sib.previousSibling; }
                        path = '/' + node.tagName.toLowerCase() + '[' + idx + ']' + path;
                        node = node.parentNode;
                    }
                    return path;
                }
                var texts = [];
                var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
                while (walker.nextNode()) {
                    var t = walker.currentNode.nodeValue.trim();
                    if (t && t.length > 20) {
                        texts.push({text: t, xpath: getXPath(walker.currentNode.parentNode), len: t.length});
                    }
                }
                texts.sort(function(a,b){ return b.len - a.len; });
                texts = texts.slice(0, 10);
                var imgs = Array.from(document.images).map(function(i){ return {src:i.src||'', area:(i.naturalWidth||0)*(i.naturalHeight||0), xpath: getXPath(i)};});
                imgs.sort(function(a,b){ return b.area - a.area; });
                imgs = imgs.slice(0, 10);
                return JSON.stringify({texts: texts, imgs: imgs});
            })();
        """.trimIndent()

        web.evaluateJavascript(jsExtract) { rawResult ->
            val nonNullResult = rawResult ?: "{}"
            scope.launch {
                // evaluateJavascript returns a quoted JSON string; unwrap carefully
                val clean = unquoteJsResult(nonNullResult)
                try {
                    val jo = JSONObject(clean)
                    val texts = jo.optJSONArray("texts") ?: JSONArray()
                    val imgs = jo.optJSONArray("imgs") ?: JSONArray()

                    // number of texts/images to process; always attempt up to 10 texts
                    val numTexts = minOf(10, texts.length())
                    val numImgs = minOf(10, imgs.length())

                    // transform texts (concurrent but limited)
                    val twistedPairs = mutableListOf<Pair<String, String>>() // xpath -> twistedText
                    val textJobs = mutableListOf<Deferred<Unit>>()
                    val textScope = CoroutineScope(Dispatchers.IO + Job())
                    for (i in 0 until numTexts) {
                        val tjo = texts.getJSONObject(i)
                        val original = tjo.optString("text")
                        val xpath = tjo.optString("xpath")
                        // launch transform with retry wrapper
                        val job = textScope.async {
                            try {
                                val twisted = client.twistTextWithRetries(original, maxAttempts = 3)
                                if (twisted.isNotBlank() && twisted != original) {
                                    synchronized(twistedPairs) { twistedPairs.add(Pair(xpath, twisted)) }
                                }
                            } catch (_: Exception) { /* swallow - fallback to original */ }
                        }
                        textJobs.add(job)
                    }

                    // transform images (concurrent but limited)
                    val imagePairs = mutableListOf<Pair<String, String>>() // originalSrc -> dataUri
                    val imgJobs = mutableListOf<Deferred<Unit>>()
                    val imgScope = CoroutineScope(Dispatchers.IO + Job())
                    for (i in 0 until numImgs) {
                        val ijo = imgs.getJSONObject(i)
                        val src = ijo.optString("src")
                        if (src.isNullOrBlank()) continue
                        val job = imgScope.async {
                            try {
                                // try to transform image (will darken faces locally and optionally call Pix agent)
                                val transformed = client.transformImageToDataUri(src, localDarken = true)
                                if (!transformed.isNullOrBlank() && transformed != src) {
                                    synchronized(imagePairs) { imagePairs.add(Pair(src, transformed)) }
                                }
                            } catch (_: Exception) {}
                        }
                        imgJobs.add(job)
                    }

                    // wait for everything, but cap wait time to avoid locking UI
                    val allJobs = textJobs + imgJobs
                    withTimeoutOrNull(15_000L) { allJobs.awaitAll() } // don't wait forever; we have retries inside

                    // Build JavaScript to replace nodes
                    val jsReplace = StringBuilder("(function(){")
                    for ((xpath, twisted) in twistedPairs) {
                        // safer: set textContent so markup won't be reinterpreted
                        jsReplace.append("try{ var node = document.evaluate('${escapeJs(xpath)}', document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue; if(node) { node.textContent = ${JSONObject.quote(twisted)}; } }catch(e){};")
                    }
                    for ((src, trans) in imagePairs) {
                        jsReplace.append("try{ var imgs=document.images; for(var i=0;i<imgs.length;i++){ if(imgs[i].src==${JSONObject.quote(src)}||imgs[i].src==${JSONObject.quote(src.trim())}) imgs[i].src=${JSONObject.quote(trans)}; } }catch(e){};")
                    }
                    jsReplace.append("})();")

                    // inject replacements (on UI thread)
                    withContext(Dispatchers.Main) {
                        web.evaluateJavascript(jsReplace.toString(), null)
                        cache[url] = jsReplace.toString()
                    }
                } catch (e: Exception) {
                    // parsing / replacement failed — swallow and fall back silently
                    e.printStackTrace()
                } finally {
                    // ensure overlay hidden after a short tidying delay for smoother UX
                    delay(300)
                    hideOverlay()
                }
            }
        }
    }

    private fun unquoteJsResult(raw: String): String {
        // raw is often like: "\"{\\\"texts\\\":...}\"" (a quoted JSON string)
        if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length >= 2) {
            val inner = raw.substring(1, raw.length - 1)
            // Undo common JS escaping
            return inner.replace("\\\\", "\\").replace("\\\"", "\"").replace("\\n", "\n")
        }
        return raw
    }

    private fun escapeJs(s: String): String {
        return s.replace("'", "\\'")
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
