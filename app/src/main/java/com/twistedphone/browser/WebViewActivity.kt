package com.twistedphone.browser
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import com.twistedphone.R
import com.twistedphone.TwistedApp
import com.twistedphone.ai.MistralClient
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

class WebViewActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var progress: ProgressBar
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val client = MistralClient()
    private val prefs = TwistedApp.instance.settingsPrefs
    private val cache = mutableMapOf<String, String>() // simple URL to transformed HTML snippet cache

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_webview)
        web = findViewById(R.id.webview)
        progress = findViewById(R.id.progressBar)
        web.settings.javaScriptEnabled = true
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.VISIBLE
                performTransformations(url ?: return)
            }
        }
        web.loadUrl("https://en.wikipedia.org/wiki/Main_Page")
    }

    private fun performTransformations(url: String) {
        if (cache.containsKey(url)) {
            web.evaluateJavascript(cache[url], null)
            progress.visibility = View.GONE
            return
        }
        val jsExtract = """
            (function(){
                function getXPath(node) {
                    let path = '';
                    while (node && node.nodeType == Node.ELEMENT_NODE) {
                        let sib = node.previousSibling, idx = 1;
                        while (sib) { if (sib.nodeType == Node.ELEMENT_NODE && sib.tagName == node.tagName) idx++; sib = sib.previousSibling; }
                        path = `/${node.tagName.toLowerCase()}[${idx}]${path}`;
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
        """
        web.evaluateJavascript(jsExtract) { result ->
            scope.launch {
                try {
                    val jo = JSONObject(result)
                    val texts = jo.optJSONArray("texts") ?: JSONArray()
                    val imgs = jo.optJSONArray("imgs") ?: JSONArray()
                    val highUsage = prefs.getBoolean("high_api_usage", false)
                    val numTexts = if (highUsage) texts.length() else minOf(3, texts.length())
                    val numImgs = if (highUsage) imgs.length() else minOf(2, imgs.length())
                    val twistedTexts = mutableListOf<Pair<String, String>>()
                    for (i in 0 until numTexts) {
                        val t = texts.getJSONObject(i)
                        val twisted = client.twistTextPreserveLength(t.optString("text"))
                        if (twisted != t.optString("text")) twistedTexts.add(Pair(t.optString("xpath"), twisted))
                    }
                    val imageMap = mutableMapOf<String, String>()
                    for (i in 0 until numImgs) {
                        val img = imgs.getJSONObject(i)
                        val src = img.optString("src")
                        val transformed = client.transformImageToDataUri(src)
                        if (transformed != src) imageMap[src] = transformed
                    }
                    val jsReplace = StringBuilder("(function(){")
                    for ((xpath, twisted) in twistedTexts) {
                        jsReplace.append("try { document.evaluate('$xpath', document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue.innerText = ${JSONObject.quote(twisted)}; } catch(e){};")
                    }
                    for ((src, trans) in imageMap) {
                        jsReplace.append("try { let imgs = document.images; for(let i=0;i<imgs.length;i++){ if(imgs[i].src=='$src') imgs[i].src='$trans'; } } catch(e){};")
                    }
                    jsReplace.append("})();")
                    web.evaluateJavascript(jsReplace.toString(), null)
                    cache[url] = jsReplace.toString() // cache the JS replace script
                } catch (e: Exception) {}
                delay(10000) // intentional 10s
                progress.visibility = View.GONE
            }
        }
    }
    override fun onDestroy(){ super.onDestroy(); scope.cancel() }
}
