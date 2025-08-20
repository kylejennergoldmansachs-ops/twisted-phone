package com.twistedphone.browser

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
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
import com.twistedphone.util.FileLogger
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLConnection
import kotlin.random.Random

class WebViewActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var progress: ProgressBar
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val client = MistralClient()
    private val prefs = TwistedApp.instance.settingsPrefs
    private val cache = mutableMapOf<String, String>()
    private var overlay: View? = null
    private var currentTransformJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_webview)
        web = findViewById(R.id.webview)
        progress = findViewById(R.id.progressBar)
        web.settings.javaScriptEnabled = true
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                showOverlay()
                FileLogger.d(this@WebViewActivity, "WebViewActivity", "onPageStarted: $url")
                // cancel any previous transform tasks to avoid races
                currentTransformJob?.cancel()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                FileLogger.d(this@WebViewActivity, "WebViewActivity", "onPageFinished: $url")
                if (url != null) performTransformations(url)
                else hideOverlay()
            }
        }

        addAddressBar()
        web.loadUrl("https://en.wikipedia.org/wiki/Main_Page")
    }

    private fun addAddressBar() {
        val root = findViewById<ViewGroup>(android.R.id.content)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#F6F6F6"))
            setPadding(8, 8, 8, 8)
            elevation = 6f
        }
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.TOP
        bar.layoutParams = lp

        val et = EditText(this).apply {
            hint = "Enter URL"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setSingleLine(true)
        }
        val go = Button(this).apply { text = "Go"; setOnClickListener {
            val txt = et.text.toString().trim()
            if (txt.isNotEmpty()) {
                val final = if (txt.startsWith("http")) txt else "https://$txt"
                web.loadUrl(final)
                showOverlay()
            }
        } }
        val back = Button(this).apply { text = "Back"; setOnClickListener { if (web.canGoBack()) web.goBack() } }

        bar.addView(et); bar.addView(go); bar.addView(back)
        (root.getChildAt(0) as ViewGroup).addView(bar)
        web.setPadding(0, (56 * resources.displayMetrics.density).toInt(), 0, 0)
    }

    private fun showOverlay() {
        try {
            if (overlay?.parent != null) return
            val root = findViewById<ViewGroup>(android.R.id.content)
            overlay = View(this).apply { setBackgroundColor(Color.WHITE); isClickable = true }
            val p = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            (root.getChildAt(0) as ViewGroup).addView(overlay, p)
            progress.visibility = View.VISIBLE
        } catch (e: Exception) {
            FileLogger.e(this, "WebViewActivity", "showOverlay error: ${e.message}")
        }
    }

    private fun hideOverlay() {
        try {
            progress.visibility = View.GONE
            if (overlay?.parent != null) (overlay?.parent as ViewGroup).removeView(overlay)
            overlay = null
        } catch (e: Exception) {
            FileLogger.e(this, "WebViewActivity", "hideOverlay error: ${e.message}")
        }
    }

    private fun performTransformations(url: String) {
        // Always start a fresh transform job for each navigation
        currentTransformJob = scope.launch {
            try {
                FileLogger.d(this@WebViewActivity, "WebViewActivity", "performTransformations start for $url")
                // Apply cached script quickly if exists to speed up
                cache[url]?.let { cachedJs ->
                    web.evaluateJavascript(cachedJs, null)
                    FileLogger.d(this@WebViewActivity, "WebViewActivity", "Applied cached transform for $url")
                    hideOverlay()
                    return@launch
                }

                val jsExtract = """
                (function(){
                    function getXPath(node){
                        if(!node) return '';
                        var path='';
                        while(node && node.nodeType==Node.ELEMENT_NODE){
                            var sib=node.previousSibling, idx=1;
                            while(sib){ if(sib.nodeType==Node.ELEMENT_NODE && sib.tagName==node.tagName) idx++; sib=sib.previousSibling; }
                            path='/'+node.tagName.toLowerCase()+'['+idx+']'+path;
                            node=node.parentNode;
                        }
                        return path;
                    }
                    var texts=[]; var walker=document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
                    while(walker.nextNode()){
                        var t=walker.currentNode.nodeValue.trim();
                        if(t && t.length>20) texts.push({text:t,xpath:getXPath(walker.currentNode.parentNode),len:t.length});
                    }
                    texts.sort(function(a,b){ return b.len - a.len;});
                    texts = texts.slice(0, 10);
                    var imgs = Array.from(document.images).map(function(i){ return {src:i.src||'', area:(i.naturalWidth||0)*(i.naturalHeight||0), xpath:getXPath(i)};});
                    imgs.sort(function(a,b){ return b.area - a.area; });
                    imgs = imgs.slice(0, 10);
                    return JSON.stringify({texts:texts, imgs:imgs});
                })();
                """.trimIndent()

                val raw = evaluateJavascriptSafely(jsExtract)
                FileLogger.d(this@WebViewActivity, "WebViewActivity", "jsExtract raw: ${raw?.take(200)}")
                if (raw == null) {
                    FileLogger.e(this@WebViewActivity, "WebViewActivity", "Extraction returned null")
                    hideOverlay(); return@launch
                }

                val clean = try { JSONArray("[$raw]").getString(0) } catch (e: Exception) { raw }
                val jo = JSONObject(clean)
                val texts = jo.optJSONArray("texts") ?: JSONArray()
                val imgs = jo.optJSONArray("imgs") ?: JSONArray()
                FileLogger.d(this@WebViewActivity, "WebViewActivity", "Found texts=${texts.length()} imgs=${imgs.length()} (top10)")

                // Transform texts concurrently with limit
                val twistedPairs = mutableListOf<Pair<String,String>>()
                val textJobs = mutableListOf<Deferred<Unit>>()
                val textScope = CoroutineScope(Dispatchers.IO + Job())
                val toProcessTexts = minOf(10, texts.length())
                for (i in 0 until toProcessTexts) {
                    val tjo = texts.getJSONObject(i)
                    val original = tjo.optString("text")
                    val xpath = tjo.optString("xpath")
                    val job = textScope.async {
                        try {
                            FileLogger.d(this@WebViewActivity, "WebViewActivity", "Sending to Mistral: ${original.take(160)}...")
                            val twisted = client.twistTextWithRetries(original, maxAttempts = 3)
                            FileLogger.d(this@WebViewActivity, "WebViewActivity", "Mistral responded (len=${twisted.length}) example: ${twisted.take(160)}")
                            if (twisted.isNotBlank() && twisted != original) synchronized(twistedPairs) { twistedPairs.add(Pair(xpath, twisted)) }
                        } catch (e: Exception) {
                            FileLogger.e(this@WebViewActivity, "WebViewActivity", "text transform failed: ${e.message}")
                        }
                    }
                    textJobs.add(job)
                }

                // Transform images concurrently
                val imgPairs = mutableListOf<Pair<String,String>>()
                val imgJobs = mutableListOf<Deferred<Unit>>()
                val imgScope = CoroutineScope(Dispatchers.IO + Job())
                val toProcessImgs = minOf(10, imgs.length())
                for (i in 0 until toProcessImgs) {
                    val ijo = imgs.getJSONObject(i)
                    val src = ijo.optString("src")
                    if (src.isNullOrBlank()) continue
                    val j = imgScope.async {
                        try {
                            FileLogger.d(this@WebViewActivity, "WebViewActivity", "Downloading image for face-detection: ${src.take(200)}")
                            val transformed = client.transformImageToDataUri(src, localDarken = true)
                            if (!transformed.isNullOrBlank() && transformed != src) {
                                synchronized(imgPairs) { imgPairs.add(Pair(src, transformed)) }
                                FileLogger.d(this@WebViewActivity, "WebViewActivity", "Image transformed for ${src.take(120)}")
                            }
                        } catch (e: Exception) {
                            FileLogger.e(this@WebViewActivity, "WebViewActivity", "Image transform failed: ${e.message}")
                        }
                    }
                    imgJobs.add(j)
                }

                // Wait for all transforms but cap the wait time
                val all = textJobs + imgJobs
                try {
                    withTimeout(18_000L) { all.awaitAll() }
                } catch (e: TimeoutCancellationException) {
                    FileLogger.e(this@WebViewActivity, "WebViewActivity", "Transform timeout after 18s: ${e.message}")
                }

                // Build JS replacer
                val jsReplace = StringBuilder("(function(){")
                for ((xpath, twisted) in twistedPairs) {
                    jsReplace.append("try{var n=document.evaluate('${escapeJs(xpath)}',document,null,XPathResult.FIRST_ORDERED_NODE_TYPE,null).singleNodeValue; if(n){ n.textContent = ${JSONObject.quote(twisted)}; }}catch(e){};")
                }
                for ((src, dataUri) in imgPairs) {
                    jsReplace.append("try{var imgs=document.images; for(var i=0;i<imgs.length;i++){ if(imgs[i].src==${JSONObject.quote(src)}||imgs[i].src==${JSONObject.quote(src.trim())}) imgs[i].src=${JSONObject.quote(dataUri)}; }}catch(e){};")
                }
                jsReplace.append("})();")

                // Inject replacement into page
                web.evaluateJavascript(jsReplace.toString(), null)
                cache[url] = jsReplace.toString()
                FileLogger.d(this@WebViewActivity, "WebViewActivity", "Injected replacements (texts=${twistedPairs.size}, images=${imgPairs.size})")
            } catch (e: CancellationException) {
                FileLogger.d(this@WebViewActivity, "WebViewActivity", "Transform job cancelled")
            } catch (e: Exception) {
                FileLogger.e(this@WebViewActivity, "WebViewActivity", "performTransformations error: ${e.message}")
            } finally {
                // ensure overlay removed after a short delay
                mainHandler.postDelayed({ hideOverlay() }, 350)
            }
        }
    }

    private suspend fun evaluateJavascriptSafely(js: String): String? = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<String?>()
        try {
            web.evaluateJavascript(js) { result ->
                deferred.complete(result)
            }
            deferred.await()
        } catch (e: Exception) {
            FileLogger.e(this@WebViewActivity, "WebViewActivity", "evaluateJavascript error: ${e.message}")
            null
        }
    }

    private fun escapeJs(s: String): String = s.replace("'", "\\'")

    override fun onDestroy() {
        super.onDestroy()
        currentTransformJob?.cancel()
        scope.cancel()
    }
}
