package com.twistedphone.browser

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.twistedphone.ai.MistralClient
import com.twistedphone.util.Logger
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import android.util.Base64
import android.graphics.BitmapFactory
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.min

/**
 * WebViewActivity
 *
 * - Creates a WebView dynamically and uses JS to extract texts and images.
 * - Sends the top ~10 text snippets to Mistral via MistralClient.twistTextWithRetries (suspend).
 * - Replaces text & images client-side during an overlay period, with a timeout of 15s.
 * - Shows a basic numeric progress indicator in the overlay (percentage).
 *
 * This implementation favors robustness and avoids compile-time dependency on any layout resources.
 */

class WebViewActivity : AppCompatActivity() {
    private val TAG = "WebViewActivity"
    private lateinit var web: WebView
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val MAX_TIMEOUT_MS = 15_000L
    private var transformJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.loadsImagesAutomatically = true
        setContentView(web)

        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                Logger.d(TAG, "onPageStarted: $url")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                Logger.d(TAG, "onPageFinished: $url")
                startTransform(url ?: "")
            }
        }

        // Start with Wikipedia mobile as your original starter page
        web.loadUrl("https://en.m.wikipedia.org/wiki/Main_Page")
    }

    private fun startTransform(url: String) {
        transformJob?.cancel()
        injectOverlay(0, "Loading...")

        transformJob = scope.launch {
            val start = System.currentTimeMillis()
            val deadline = start + MAX_TIMEOUT_MS
            try {
                Logger.d(TAG, "performTransformations start for $url")

                val extractJson = extractTextsAndImages()
                val texts = parseTextsFromExtract(extractJson)
                val imgs = parseImagesFromExtract(extractJson)

                Logger.d(TAG, "Found texts=${texts.size} imgs=${imgs.size} (top10)")

                val totalWork = maxOf(1, texts.size + imgs.size)
                var done = 0

                // Maps for replacements: we will replace occurrences of exact original snippet
                val replacements = mutableListOf<Pair<String, String>>()

                // Transform texts
                for (txt in texts) {
                    if (!isActive) return@launch
                    if (System.currentTimeMillis() > deadline) {
                        Logger.d(TAG, "Timed out during text transforms")
                        break
                    }
                    val original = txt
                    Logger.d(TAG, "Sending to Mistral: ${original.take(200)}")
                    val twisted = MistralClient.twistTextWithRetries("Rewrite the following text into a darker, stranger, unsettling version while keeping similar length and sentence structure. RETURN ONLY the rewritten text:\n\n$original", 1, 4)
                    val finalText = twisted ?: original
                    replacements.add(original to finalText)
                    done++
                    updateProgress((done.toFloat() / totalWork.toFloat() * 100f).toInt())
                }

                // Apply text replacements (best-effort: replace occurrences of the original snippet)
                if (replacements.isNotEmpty()) {
                    val js = buildTextReplaceJs(replacements)
                    runOnUiThread { web.evaluateJavascript(js, null) }
                }

                // Transform images: for each src, download, darken detected faces locally, convert to data-uri and inject
                for (src in imgs) {
                    if (!isActive) return@launch
                    if (System.currentTimeMillis() > deadline) {
                        Logger.d(TAG, "Timed out during image transforms")
                        break
                    }
                    try {
                        Logger.d(TAG, "Downloading image for face-detection: $src")
                        val bmp = downloadBitmap(src)
                        if (bmp != null) {
                            val processed = darkenFaces(bmp) // best-effort (simple detection placeholder)
                            val dataUri = bitmapToDataUri(processed)
                            val injectJs = """
                                (function(){
                                  var imgs=document.getElementsByTagName('img');
                                  for(var i=0;i<imgs.length;i++){
                                    try{
                                      if(imgs[i].src && imgs[i].src.indexOf("${escapeForJs(src)}")!==-1){
                                        imgs[i].src = "${dataUri}";
                                      }
                                    }catch(e){}
                                  }
                                })();
                            """.trimIndent()
                            runOnUiThread { web.evaluateJavascript(injectJs, null) }
                        }
                    } catch (e: Exception) {
                        Logger.e(TAG, "image transform failed: ${e.message}")
                    }
                    done++
                    updateProgress((done.toFloat() / totalWork.toFloat() * 100f).toInt())
                }

                // remove overlay
                runOnUiThread { web.evaluateJavascript("document.getElementById('__twisted_overlay__') && document.getElementById('__twisted_overlay__').remove();", null) }
                Logger.d(TAG, "Injected replacements (texts=${replacements.size}, images=${imgs.size})")

            } catch (e: Exception) {
                Logger.e(TAG, "performTransformations failed: ${e.message}")
                runOnUiThread { web.evaluateJavascript("document.getElementById('__twisted_overlay__') && document.getElementById('__twisted_overlay__').remove();", null) }
            }
        }

        // Enforce timeout
        mainHandler.postDelayed({
            if (transformJob?.isActive == true) {
                transformJob?.cancel()
                Logger.d(TAG, "Transform timeout after ${MAX_TIMEOUT_MS}ms")
                runOnUiThread { web.evaluateJavascript("document.getElementById('__twisted_overlay__') && document.getElementById('__twisted_overlay__').remove();", null) }
            }
        }, MAX_TIMEOUT_MS)
    }

    private fun updateProgress(percent: Int) {
        val text = "Warping... $percent%"
        injectOverlay(percent, text)
    }

    private fun injectOverlay(percent: Int, label: String) {
        val safeLabel = label.replace("\"", "\\\"")
        val js = """
            (function(){
                if(!document.getElementById('__twisted_overlay__')){
                  var d=document.createElement('div');
                  d.id='__twisted_overlay__';
                  d.style.position='fixed';
                  d.style.left='0';
                  d.style.top='0';
                  d.style.right='0';
                  d.style.bottom='0';
                  d.style.background='white';
                  d.style.zIndex='999999';
                  d.style.display='flex';
                  d.style.alignItems='center';
                  d.style.justifyContent='center';
                  d.style.fontSize='18px';
                  d.style.fontFamily='sans-serif';
                  document.body.appendChild(d);
                }
                var el=document.getElementById('__twisted_overlay__');
                el.innerText = "${safeLabel}";
            })();
        """.trimIndent()
        runOnUiThread { web.evaluateJavascript(js, null) }
    }

    private fun escapeForJs(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")

    // Downloads a bitmap from a URL (simple, blocking)
    private fun downloadBitmap(src: String): Bitmap? {
        try {
            val url = URL(src)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 10000
            conn.instanceFollowRedirects = true
            conn.connect()
            val isStream = conn.inputStream
            val bmp = BitmapFactory.decodeStream(isStream)
            isStream.close()
            conn.disconnect()
            return bmp
        } catch (e: Exception) {
            Logger.e(TAG, "downloadBitmap failed: ${e.message}")
            return null
        }
    }

    // Placeholder face-darkening: for small images we don't run ML — we simply darken center area heuristically.
    // Replace with your lightweight local face detector if available.
    private fun darkenFaces(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val paint = Paint()
        paint.alpha = 220
        paint.style = Paint.Style.FILL
        // heuristic: darken central square (best-effort)
        val size = (min(w, h) * 0.45f).toInt()
        val left = (w - size) / 2
        val top = (h - size) / 2
        canvas.drawRect(left.toFloat(), top.toFloat(), (left + size).toFloat(), (top + size).toFloat(), paint)
        return out
    }

    private fun bitmapToDataUri(bmp: Bitmap): String {
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 90, baos)
        val bytes = baos.toByteArray()
        val base = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return "data:image/png;base64,$base"
    }

    // JS extraction: ask the page for candidate text snippets and images, return JSON string
    private fun extractTextsAndImages(): String {
        // This JS returns a JSON string{texts:[...],images:[...]}
        val js = """
            (function(){
              function textNodesUnder(el){
                var n, a=[], walk=document.createTreeWalker(el,NodeFilter.SHOW_TEXT,null,false);
                while(n=walk.nextNode()) {
                  var t = n.nodeValue.trim();
                  if(t && t.length>20) a.push({text:t, len:t.length});
                }
                return a;
              }
              try{
                var texts = textNodesUnder(document.body);
                texts.sort(function(a,b){return b.len - a.len;});
                texts = texts.slice(0,10).map(function(x){return x.text;});
                var imgs = [];
                var imgEls = document.getElementsByTagName('img');
                for(var i=0;i<imgEls.length;i++){
                  try{
                    var s=imgEls[i].src;
                    if(s) imgs.push(s);
                  }catch(e){}
                }
                imgs = imgs.slice(0,10);
                return JSON.stringify({texts:texts, images:imgs});
              }catch(e){
                return JSON.stringify({texts:[],images:[]});
              }
            })();
        """.trimIndent()

        // evaluate synchronously on main thread and return result string
        val latch = java.util.concurrent.CountDownLatch(1)
        val out = arrayOf<String?>(null)
        runOnUiThread {
            web.evaluateJavascript(js) { result ->
                out[0] = result
                latch.countDown()
            }
        }
        latch.await()
        // result is a quoted JSON string literal (e.g. "\"{\\\"texts\\\":...}\""), so unquote if needed
        var r = out[0] ?: "{\"texts\":[],\"images\":[]}"
        if (r.startsWith("\"") && r.endsWith("\"")) {
            r = r.substring(1, r.length - 1).replace("\\\\\"", "\"").replace("\\\\n", "\\n").replace("\\\\/", "/")
        }
        return r
    }

    private fun parseTextsFromExtract(jsonStr: String): List<String> {
        return try {
            val j = JSONObject(jsonStr)
            val arr = j.optJSONArray("texts") ?: JSONArray()
            val out = mutableListOf<String>()
            for (i in 0 until min(10, arr.length())) {
                out.add(arr.optString(i))
            }
            out
        } catch (e: Exception) {
            Logger.e(TAG, "parseTextsFromExtract failed: ${e.message}")
            emptyList()
        }
    }

    private fun parseImagesFromExtract(jsonStr: String): List<String> {
        return try {
            val j = JSONObject(jsonStr)
            val arr = j.optJSONArray("images") ?: JSONArray()
            val out = mutableListOf<String>()
            for (i in 0 until min(10, arr.length())) {
                out.add(arr.optString(i))
            }
            out
        } catch (e: Exception) {
            Logger.e(TAG, "parseImagesFromExtract failed: ${e.message}")
            emptyList()
        }
    }

    private fun buildTextReplaceJs(list: List<Pair<String, String>>): String {
        // Best-effort: replace exact occurrences of each original snippet in text nodes
        val safePairs = list.map {
            val o = it.first.replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")
            val n = it.second.replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")
            "\"$o\"->\"$n\""
        }
        val builder = StringBuilder()
        builder.append("(function(){\n")
        builder.append("  function replaceAllText(original,replacement){\n")
        builder.append("    var walker=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null,false);\n")
        builder.append("    var n;\n")
        builder.append("    while(n=walker.nextNode()){\n")
        builder.append("      try{\n")
        builder.append("        if(n.nodeValue && n.nodeValue.indexOf(original)!==-1){ n.nodeValue = n.nodeValue.replace(original, replacement); }\n")
        builder.append("      }catch(e){}\n")
        builder.append("    }\n")
        builder.append("  }\n")
        for (p in list) {
            val o = p.first.replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")
            val n = p.second.replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")
            builder.append("  replaceAllText(\"$o\",\"$n\");\n")
        }
        builder.append("})();")
        return builder.toString()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
