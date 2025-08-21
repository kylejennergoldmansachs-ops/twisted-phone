package com.twistedphone.browser

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import kotlin.math.min

/**
 * WebViewActivity
 * - Limits transform timeout to 15s total
 * - Sends text transforms in small batches (concurrency ~3)
 * - Sends image transforms in small batches (concurrency ~4)
 * - Applies whatever replacements finished when timeout hits
 */
class WebViewActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var progress: ProgressBar
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val client = MistralClient()
    private val prefs = TwistedApp.instance.settingsPrefs
    private val cache = mutableMapOf<String, String>()
    private var overlay: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentTransformJob: Job? = null
    private val TRANSFORM_TIMEOUT_MS = 15_000L // reduced to 15s as requested

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
                currentTransformJob?.cancel()
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                FileLogger.d(this@WebViewActivity, "WebViewActivity", "onPageFinished: $url")
                if (url != null) performTransformations(url) else hideOverlay()
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
        val et = EditText(this).apply {
            hint = "Enter URL"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setSingleLine(true)
        }
        val go = Button(this).apply {
            text = "Go"
            setOnClickListener {
                val txt = et.text.toString().trim()
                if (txt.isNotEmpty()) {
                    val final = if (txt.startsWith("http")) txt else "https://$txt"
                    web.loadUrl(final)
                    showOverlay()
                }
            }
        }
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

    /**
     * Perform transformations with:
     * - global timeout of TRANSFORM_TIMEOUT_MS
     * - batch-processing (batch size 3 for texts, 4 for images)
     * - apply whatever completed when timeout finishes
     */
    private fun performTransformations(url: String) {
        currentTransformJob = scope.launch {
            try {
                FileLogger.d(this@WebViewActivity, "WebViewActivity", "performTransformations start for $url")
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
                    function visible(el){
                        if(!el) return false;
                        if(el.hasAttribute && el.hasAttribute('hidden')) return false;
                        var rects = el.getClientRects();
                        if(!rects || rects.length===0) return false;
                        var cs = window.getComputedStyle(el);
                        if(!cs) return true;
                        if(cs.visibility==='hidden' || cs.display==='none') return false;
                        if(parseFloat(cs.opacity||1) < 0.1) return false;
                        return true;
                    }
                    var walker=document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT,null,false);
                    var texts=[];
                    while(walker.nextNode()){
                        var t = walker.currentNode.nodeValue.trim();
                        if(!t || t.length < 30) continue;
                        var parent = walker.currentNode.parentNode;
                        var tag = parent && parent.tagName ? parent.tagName.toLowerCase() : '';
                        if(['script','style','noscript','svg','code','pre','head','link','meta'].indexOf(tag) !== -1) continue;
                        if(!visible(parent)) continue;
                        if(/^[\.\{\<\[\#\*]/.test(t)) continue;
                        if(t.indexOf('http') !== -1) continue;
                        if(t.indexOf('{')!==-1 && t.indexOf('}')!==-1) continue;
                        texts.push({text:t, xpath:getXPath(parent), len:t.length});
                    }
                    texts.sort(function(a,b){ return b.len - a.len; });
                    texts = texts.slice(0,10);
                    var imgs = Array.from(document.images).filter(function(i){ try { return (i.naturalWidth||0) > 40 && (i.src||'').indexOf('http')===0; } catch(e){ return false; }}).map(function(i){ return {src:i.src||'', area:(i.naturalWidth||0)*(i.naturalHeight||0), xpath:getXPath(i)};});
                    imgs.sort(function(a,b){ return b.area - a.area; });
                    imgs = imgs.slice(0,10);
                    return JSON.stringify({texts:texts, imgs:imgs});
                })();
                """.trimIndent()

                val raw = evaluateJavascriptSafely(jsExtract)
                FileLogger.d(this@WebViewActivity, "WebViewActivity", "jsExtract raw: ${raw?.take(400)}")
                if (raw == null) {
                    FileLogger.e(this@WebViewActivity, "WebViewActivity", "Extraction returned null")
                    hideOverlay(); return@launch
                }

                // parse extractor result
                val clean = try { JSONArray("[$raw]").getString(0) } catch (e: Exception) { raw }
                val jo = JSONObject(clean)
                val texts = jo.optJSONArray("texts") ?: JSONArray()
                val imgs = jo.optJSONArray("imgs") ?: JSONArray()
                FileLogger.d(this@WebViewActivity, "WebViewActivity", "Found texts=${texts.length()} imgs=${imgs.length()} (top10)")

                // Prepare storage for replacements
                val twistedPairs = mutableListOf<Pair<String,String>>()
                val imgPairs = mutableListOf<Pair<String,String>>()

                // batch-run text transforms (batch size 3)
                val batchSizeText = 3
                val totalTexts = min(10, texts.length())
                val textScope = CoroutineScope(Dispatchers.IO + Job())

                withTimeoutOrNull(TRANSFORM_TIMEOUT_MS) {
                    var i = 0
                    while (i < totalTexts) {
                        val end = min(i + batchSizeText, totalTexts)
                        val batch = (i until end).map { idx ->
                            val tjo = texts.getJSONObject(idx)
                            val original = tjo.optString("text")
                            val xpath = tjo.optString("xpath")
                            textScope.async {
                                try {
                                    FileLogger.d(this@WebViewActivity, "WebViewActivity", "Sending to Mistral: ${original.take(200)}")
                                    val twisted = client.twistTextWithRetries(original, maxAttempts = 3)
                                    FileLogger.d(this@WebViewActivity, "WebViewActivity", "Mistral responded (len=${twisted.length}) example: ${twisted.take(200)}")
                                    if (twisted.isNotBlank() && twisted != original) synchronized(twistedPairs) { twistedPairs.add(Pair(xpath, twisted)) }
                                } catch (e: Exception) {
                                    FileLogger.e(this@WebViewActivity, "WebViewActivity", "text transform failed: ${e.message}")
                                }
                            }
                        }
                        // await this batch
                        batch.awaitAll()
                        i = end
                    }
                    // images: batch size 4
                    val batchSizeImg = 4
                    val totalImgs = min(10, imgs.length())
                    val imgScope = CoroutineScope(Dispatchers.IO + Job())
                    var j = 0
                    while (j < totalImgs) {
                        val endj = min(j + batchSizeImg, totalImgs)
                        val batchImg = (j until endj).map { idx ->
                            val ijo = imgs.getJSONObject(idx)
                            val src = ijo.optString("src")
                            imgScope.async {
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
                        }
                        batchImg.awaitAll()
                        j = endj
                    }
                } // end withTimeoutOrNull

                // Build JS replacements for whatever we have
                val jsReplace = StringBuilder("(function(){")
                for ((xpath, twisted) in twistedPairs) {
                    jsReplace.append("try{var n=document.evaluate('${escapeJs(xpath)}',document,null,XPathResult.FIRST_ORDERED_NODE_TYPE,null).singleNodeValue; if(n){ n.textContent = ${JSONObject.quote(twisted)}; }}catch(e){};")
                }
                for ((src, dataUri) in imgPairs) {
                    jsReplace.append("try{var imgs=document.images; for(var i=0;i<imgs.length;i++){ if(imgs[i].src==${JSONObject.quote(src)}||imgs[i].src==${JSONObject.quote(src.trim())}) imgs[i].src=${JSONObject.quote(dataUri)}; }}catch(e){};")
                }
                jsReplace.append("})();")

                // Apply JS even if some transforms timed out - show what's done
                web.evaluateJavascript(jsReplace.toString(), null)
                cache[url] = jsReplace.toString()
                FileLogger.d(this@WebViewActivity, "WebViewActivity", "Injected replacements (texts=${twistedPairs.size}, images=${imgPairs.size})")
            } catch (e: CancellationException) {
                FileLogger.d(this@WebViewActivity, "WebViewActivity", "Transform job cancelled")
            } catch (e: Exception) {
                FileLogger.e(this@WebViewActivity, "WebViewActivity", "performTransformations error: ${e.message}")
            } finally {
                // Ensure overlay is removed after a short delay so user sees page
                mainHandler.postDelayed({ hideOverlay() }, 300)
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
