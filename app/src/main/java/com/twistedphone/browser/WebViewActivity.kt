package com.twistedphone.browser

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.twistedphone.R
import com.twistedphone.TwistedApp
import com.twistedphone.ai.MistralClient
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

class WebViewActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var progressBar: ProgressBar
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val client = MistralClient()
    private val prefs = TwistedApp.instance.settingsPrefs
    private val cache = mutableMapOf<String, String>() // simple URL to transformed JS replace script
    private var overlay: FrameLayout? = null

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)

        // ensure system bars are visible so the device nav overlays the app
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE

        setContentView(R.layout.activity_webview)
        web = findViewById(R.id.webview)
        progressBar = findViewById(R.id.progressBar)
        web.settings.javaScriptEnabled = true
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                // show overlay + spinner while we extract & transform
                showTransformOverlay()
                performTransformations(url ?: return)
            }
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                // make sure overlay is shown during load as well
                showTransformOverlay()
            }
        }

        // Add a small address bar programmatically so we don't need to edit XML.
        addAddressBar()

        // initial URL
        web.loadUrl("https://en.wikipedia.org/wiki/Main_Page")
    }

    private fun addAddressBar() {
        val root = findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 8, 8, 8)
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP
            }
            elevation = 8f
            setBackgroundColor(0xDDFFFFFF.toInt())
        }
        val urlInput = EditText(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            hint = "Enter URL or search"
            setSingleLine(true)
        }
        val go = Button(this).apply {
            text = "Go"
            setOnClickListener {
                val raw = urlInput.text.toString().trim()
                if (raw.isNotEmpty()) {
                    val url = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
                    web.loadUrl(url)
                    showTransformOverlay()
                }
            }
        }
        bar.addView(urlInput)
        bar.addView(go)

        // insert at top of root view
        root.addView(bar)
        // let WebView content be below the bar visually by adding top padding
        web.setPadding(0, (56 * resources.displayMetrics.density).toInt(), 0, 0)
        web.clipToPadding = false
    }

    private fun showTransformOverlay() {
        if (overlay != null) {
            overlay?.visibility = View.VISIBLE
            return
        }
        val root = findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
        overlay = FrameLayout(this).apply {
            setBackgroundColor(0xFFFFFFFF.toInt()) // white overlay while transforming
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        val spinner = ProgressBar(this).apply {
            isIndeterminate = true
            val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            lp.gravity = Gravity.CENTER
            layoutParams = lp
        }
        overlay?.addView(spinner)
        root.addView(overlay)
        progressBar.visibility = View.VISIBLE
    }

    private fun hideTransformOverlay() {
        overlay?.visibility = View.GONE
        progressBar.visibility = View.GONE
    }

    private fun performTransformations(url: String) {
        // If we have cached replacement script for this URL, inject it and hide overlay quickly
        if (cache.containsKey(url)) {
            web.evaluateJavascript(cache[url]!!, null)
            // small delay to let DOM settle then hide overlay
            scope.launch {
                delay(300)
                hideTransformOverlay()
            }
            return
        }

        // extract some texts & images via injected JS
        val jsExtract = """
            (function(){
                function getXPath(node) {
                    let path = '';
                    while (node && node.nodeType == Node.ELEMENT_NODE) {
                        let sib = node.previousSibling, idx = 1;
                        while (sib) { if (sib.nodeType == Node.ELEMENT_NODE && sib.tagName == node.tagName) idx++; sib = sib.previousSibling; }
                        path = '/' + node.tagName.toLowerCase() + '[' + idx + ']' + path;
                        node = node.parentNode;
                    }
                    return path;
                }
                let texts = [];
                let walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
                while (walker.nextNode()) {
                    let text = walker.currentNode.nodeValue.trim();
                    if (text.length > 30) texts.push({text: text, xpath: getXPath(walker.currentNode.parentNode), len: text.length});
                }
                texts.sort((a,b) => b.len - a.len);
                texts = texts.slice(0, 6);
                let imgs = Array.from(document.images).map(i => ({src: i.src, area: (i.naturalWidth||0)*(i.naturalHeight||0), xpath: getXPath(i)})).sort((a,b)=>b.area-a.area).slice(0,4);
                return JSON.stringify({texts: texts, imgs: imgs});
            })();
        """.trimIndent()

        web.evaluateJavascript(jsExtract) { result ->
            // evaluateJavascript returns a JS string literal (quoted). Unescape using JSONArray trick:
            val jsonString = try {
                if (result == null) "{}" else JSONArray("[$result]").getString(0)
            } catch (e: Exception) {
                // Fallback: try raw (not ideal)
                result ?: "{}"
            }

            scope.launch {
                try {
                    val jo = JSONObject(jsonString)
                    val texts = jo.optJSONArray("texts") ?: org.json.JSONArray()
                    val imgs = jo.optJSONArray("imgs") ?: org.json.JSONArray()
                    val highUsage = prefs.getBoolean("high_api_usage", false)
                    val numTexts = if (highUsage) texts.length() else minOf(3, texts.length())
                    val numImgs = if (highUsage) imgs.length() else minOf(2, imgs.length())

                    // Collect replacements
                    val twistedTexts = mutableListOf<Pair<String, String>>()
                    for (i in 0 until numTexts) {
                        val t = texts.getJSONObject(i)
                        val orig = t.optString("text", "")
                        if (orig.isBlank()) continue
                        val twisted = withContext(Dispatchers.IO) { client.twistTextPreserveLength(orig) }
                        if (twisted.isNotBlank() && twisted != orig) twistedTexts.add(Pair(t.optString("xpath"), twisted))
                    }

                    val imageMap = mutableMapOf<String, String>()
                    for (i in 0 until numImgs) {
                        val img = imgs.getJSONObject(i)
                        val src = img.optString("src")
                        if (src.isBlank()) continue
                        val transformed = withContext(Dispatchers.IO) { client.transformImageToDataUri(src) }
                        if (transformed.isNotBlank() && transformed != src) imageMap[src] = transformed
                    }

                    // Build replace script
                    val jsReplace = StringBuilder("(function(){")
                    for ((xpath, twisted) in twistedTexts) {
                        // Use innerText on the parent element (the xpath was for the element containing text)
                        jsReplace.append("try{let el=document.evaluate('$xpath',document,null,XPathResult.FIRST_ORDERED_NODE_TYPE,null).singleNodeValue; if(el) el.innerText = ${JSONObject.quote(twisted)};}catch(e){};")
                    }
                    for ((src, trans) in imageMap) {
                        jsReplace.append("try{let imgs=document.images; for(let i=0;i<imgs.length;i++){ if(imgs[i].src==${JSONObject.quote(src)}) imgs[i].src=${JSONObject.quote(trans)}; }}catch(e){};")
                    }
                    jsReplace.append("})();")

                    // Inject the replacement script
                    web.evaluateJavascript(jsReplace.toString(), null)
                    // cache script for quick re-use
                    cache[url] = jsReplace.toString()
                } catch (e: Exception) {
                    // swallow but report
                    e.printStackTrace()
                } finally {
                    // ensure overlay is hidden after a small delay so DOM can settle
                    delay(500)
                    hideTransformOverlay()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
