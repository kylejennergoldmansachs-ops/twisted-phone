package com.twistedphone.browser

import android.graphics.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import com.twistedphone.R
import com.twistedphone.ai.MistralClient
import com.twistedphone.util.FileLogger
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

class WebViewActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val MAX_TIMEOUT_MS = 15_000L // 15 seconds
    private var transformJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webview)
        web = findViewById(R.id.webView)
        web.settings.javaScriptEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                FileLogger.d(this@WebViewActivity, "WebViewActivity", "onPageStarted: $url")
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                FileLogger.d(this@WebViewActivity, "WebViewActivity", "onPageFinished: $url")
                startTransform(url ?: "")
            }
        }
        // load starter
        web.loadUrl("https://en.m.wikipedia.org/wiki/Main_Page")
    }

    private fun startTransform(url: String) {
        transformJob?.cancel()
        // inject overlay immediately to hide unaltered DOM
        injectLoadingOverlay(0)
        transformJob = scope.launch {
            try {
                FileLogger.d(this@WebViewActivity, "WebViewActivity", "performTransformations start for $url")
                val start = System.currentTimeMillis()
                val textsAndImgs = extractTopTextsAndImages() // will run JS and return parsed JSON
                val texts = textsAndImgs.first
                val imgs = textsAndImgs.second
                FileLogger.d(this@WebViewActivity, "WebViewActivity", "Found texts=${texts.size} imgs=${imgs.size} (top10)")

                val totalWork = texts.size + imgs.size
                var done = 0

                // enforce overall timeout using coroutine with select or manual check
                val deadline = start + MAX_TIMEOUT_MS

                // --- process texts (send to Mistral) ---
                val replacements = mutableMapOf<String, String>() // xpath -> replacement
                for ((idx, item) in texts.withIndex()) {
                    if (!isActive) return@launch
                    // if timed out -> break
                    if (System.currentTimeMillis() > deadline) {
                        FileLogger.d(this@WebViewActivity, "WebViewActivity", "Timed out during text transforms")
                        break
                    }
                    val original = item.getString("text")
                    val xpath = item.getString("xpath")
                    FileLogger.d(this@WebViewActivity, "WebViewActivity", "Sending to Mistral: ${original.take(200)}")
                    val twisted = MistralClient.twistTextWithRetries(original, 1, 4) ?: original
                    replacements[xpath] = twisted
                    done++
                    updateProgress((done.toFloat() / totalWork.toFloat() * 100).toInt())
                }

                // inject text replacements in a single JS call
                if (replacements.isNotEmpty()) {
                    val js = buildReplaceTextJs(replacements)
                    evaluateJsOnMain(js)
                }

                // --- process images ---
                for ((idx, src) in imgs.withIndex()) {
                    if (!isActive) break
                    if (System.currentTimeMillis() > deadline) {
                        FileLogger.d(this@WebViewActivity, "WebViewActivity", "Timed out during image transforms")
                        break
                    }
                    try {
                        FileLogger.d(this@WebViewActivity, "WebViewActivity", "Downloading image for face-detection: $src")
                        val bmp = downloadBitmap(src)
                        if (bmp != null) {
                            val processed = darkenFaces(bmp)
                            val dataUri = bitmapToDataUri(processed)
                            // inject replacement for that src
                            val injectJs = """
                                (function(){
                                  var imgs = document.getElementsByTagName('img');
                                  for(var i=0;i<imgs.length;i++){
                                    if(imgs[i].src && imgs[i].src.indexOf("${escapeForJs(src)}")!==-1){
                                      imgs[i].src = "${dataUri}";
                                    }
                                  }
                                })();
                            """.trimIndent()
                            evaluateJsOnMain(injectJs)
                        }
                    } catch (e: Exception) {
                        FileLogger.e(this@WebViewActivity, "WebViewActivity", "image transform failed: ${e.message}")
                    }
                    done++
                    updateProgress((done.toFloat() / totalWork.toFloat() * 100).toInt())
                }

                // finished: remove overlay
                evaluateJsOnMain("document.getElementById('__twisted_overlay__') && document.getElementById('__twisted_overlay__').remove();")
                FileLogger.d(this@WebViewActivity, "WebViewActivity", "Injected replacements (texts=${replacements.size}, images=${imgs.size})")
            } catch (e: Exception) {
                FileLogger.e(this@WebViewActivity, "WebViewActivity", "Transform job failed: ${e.message}")
                // ensure overlay removed on failure
                evaluateJsOnMain("document.getElementById('__twisted_overlay__') && document.getElementById('__twisted_overlay__').remove();")
            }
        }

        // enforce hard timeout: cancel transformJob after MAX_TIMEOUT_MS and remove overlay
        mainHandler.postDelayed({
            transformJob?.cancel()
            evaluateJsOnMain("document.getElementById('__twisted_overlay__') && document.getElementById('__twisted_overlay__').remove();")
            FileLogger.d(this@WebViewActivity, "WebViewActivity", "Transform timeout after ${MAX_TIMEOUT_MS}ms")
        }, MAX_TIMEOUT_MS)
    }

    private suspend fun extractTopTextsAndImages(): Pair<List<JSONObject>, List<String>> = withContext(Dispatchers.Main) {
        // JS: gather visible text nodes (simple heuristic) and img srcs; return JSON string
        val js = """
            (function(){
              function visible(el){
                var s = window.getComputedStyle(el);
                return s && s.display !== 'none' && s.visibility !== 'hidden' && s.opacity !== '0';
              }
              var texts = [];
              var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, {acceptNode: function(node){ if(node && node.nodeValue && node.nodeValue.trim().length>20) return NodeFilter.FILTER_ACCEPT; return NodeFilter.FILTER_REJECT }}, false);
              var i=0;
              while(walker.nextNode() && texts.length<10){
                var n = walker.currentNode.nodeValue.trim();
                var p = walker.currentNode.parentElement;
                if(p && visible(p)){
                  var xpath = '';
                  try {
                    xpath = getXPathForElement(p);
                  } catch(e){}
                  texts.push({text:n, xpath:xpath, len:n.length});
                }
              }
              // helper for xpath
              function getXPathForElement(el) {
                if(el.id) return 'id("'+el.id+'")';
                var segs = [];
                for(; el && el.nodeType == 1; el = el.parentNode){
                  var idx = 1;
                  for(var sib = el.previousSibling; sib; sib = sib.previousSibling){
                    if(sib.nodeType === 1 && sib.nodeName === el.nodeName) idx++;
                  }
                  var name = el.nodeName.toLowerCase();
                  segs.unshift(name + '[' + idx + ']');
                }
                return '/' + segs.join('/');
              }
              var imgs = [];
              var imgelems = document.getElementsByTagName('img');
              for(var j=0;j<imgelems.length && imgs.length<10;j++){
                var s = imgelems[j].src;
                if(s && s.length>0 && s.indexOf('data:')!==0) imgs.push(s);
              }
              return JSON.stringify({texts:texts,imgs:imgs});
            })();
        """.trimIndent()

        val deferred = CompletableDeferred<Pair<List<JSONObject>, List<String>>>()
        web.evaluateJavascript(js) { raw ->
            try {
                val unquoted = raw?.let {
                    if (it.length >= 2 && it.startsWith("\"") && it.endsWith("\"")) {
                        // JS returned a quoted string — unescape
                        org.json.JSONTokener("\"\"\"\"")
                        org.json.JSONObject("{}") // no-op
                        val s = it.substring(1, it.length - 1).replace("\\\\n", "\n").replace("\\\\\"", "\"").replace("\\\\\\\\", "\\")
                        s
                    } else it
                } ?: ""
                val obj = JSONObject(unquoted)
                val arr = obj.getJSONArray("texts")
                val texts = mutableListOf<JSONObject>()
                for (i in 0 until arr.length()) texts.add(arr.getJSONObject(i))
                val imgsJson = obj.getJSONArray("imgs")
                val imgs = mutableListOf<String>()
                for (i in 0 until imgsJson.length()) imgs.add(imgsJson.getString(i))
                deferred.complete(Pair(texts, imgs))
            } catch (e: Exception) {
                deferred.completeExceptionally(e)
            }
        }
        return@withContext deferred.await()
    }

    // Build JS that replaces text nodes by xpath. We set element.innerText = ...
    private fun buildReplaceTextJs(map: Map<String, String>): String {
        val sb = StringBuilder()
        sb.append("(function(){")
        for ((xpath, repl) in map) {
            val esc = escapeForJs(repl)
            // try id("...") special case else try xpath evaluation
            if (xpath.startsWith("id(\"") && xpath.endsWith("\")")) {
                val id = xpath.substring(4, xpath.length - 2)
                sb.append("var el=document.getElementById(\"${escapeForJs(id)}\"); if(el) el.innerText = \"${esc}\";")
            } else {
                sb.append("try{var result=document.evaluate(\"${escapeForJs(xpath)}\", document, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null); if(result && result.snapshotLength>0){ for(var i=0;i<result.snapshotLength;i++){ try{ result.snapshotItem(i).innerText = \"${esc}\";}catch(e){} } }}catch(ee){};")
            }
        }
        sb.append("})();")
        return sb.toString()
    }

    private fun evaluateJsOnMain(js: String) {
        mainHandler.post { web.evaluateJavascript(js, null) }
    }

    private fun updateProgress(percent: Int) {
        val safe = percent.coerceIn(0, 100)
        // update overlay percent
        val js = """
            (function(){
              var d = document.getElementById('__twisted_overlay__');
              if(!d){
                d = document.createElement('div');
                d.id='__twisted_overlay__';
                d.style.position='fixed';
                d.style.left='0';d.style.top='0';d.style.right='0';d.style.bottom='0';
                d.style.background='white';
                d.style.zIndex='2147483647';
                d.style.display='flex';
                d.style.alignItems='center';
                d.style.justifyContent='center';
                d.style.flexDirection='column';
                var t = document.createElement('div'); t.id='__twisted_percent__'; t.style.fontSize='22px'; t.style.color='black';
                d.appendChild(t); document.body.appendChild(d);
              }
              var e = document.getElementById('__twisted_percent__');
              if(e) e.innerText = 'Twisting… ${safe}%';
            })();
        """.trimIndent()
        evaluateJsOnMain(js)
    }

    private fun injectLoadingOverlay(initialPercent: Int) {
        updateProgress(initialPercent)
    }

    // download bitmap (best-effort)
    private fun downloadBitmap(urlStr: String): Bitmap? {
        return try {
            val u = URL(urlStr)
            val conn = (u.openConnection() as HttpURLConnection).apply { connectTimeout = 7000; readTimeout = 9000 }
            conn.requestMethod = "GET"
            conn.connect()
            val code = conn.responseCode
            if (code in 200..299) {
                val isStream = conn.inputStream
                val bmp = BitmapFactory.decodeStream(isStream)
                isStream.close()
                conn.disconnect()
                bmp
            } else {
                conn.disconnect()
                null
            }
        } catch (e: Exception) {
            FileLogger.e(this, "WebViewActivity", "downloadBitmap error: ${e.message}")
            null
        }
    }

    // lightweight face detection using android.media.FaceDetector (works on RGB_565, limited but local & tiny)
    private fun darkenFaces(src: Bitmap): Bitmap {
        try {
            val w = min(800, src.width)
            val aspect = src.height.toFloat() / src.width
            val h = (w * aspect).toInt()
            val scaled = Bitmap.createScaledBitmap(src, w, h, true)
            // android.media.FaceDetector requires RGB_565
            val fdBmp = scaled.copy(Bitmap.Config.RGB_565, true)
            val faceDetector = android.media.FaceDetector(fdBmp.width, fdBmp.height, 5)
            val faces = arrayOfNulls<android.media.FaceDetector.Face>(5)
            val count = faceDetector.findFaces(fdBmp, faces)
            if (count <= 0) return src
            val out = Bitmap.createBitmap(fdBmp.width, fdBmp.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            c.drawBitmap(fdBmp, 0f, 0f, null)
            val paint = Paint().apply { color = Color.argb(200, 0, 0, 0); style = Paint.Style.FILL }
            for (i in 0 until count) {
                val f = faces[i] ?: continue
                val eyes = PointF(); f.getMidPoint(eyes); val eyesX = eyes.x; val eyesY = eyes.y
                val eyesDist = f.eyesDistance()
                val left = (eyesX - eyesDist * 1.6f).coerceAtLeast(0f)
                val top = (eyesY - eyesDist * 1.6f).coerceAtLeast(0f)
                val right = (eyesX + eyesDist * 1.6f).coerceAtMost(fdBmp.width.toFloat())
                val bottom = (eyesY + eyesDist * 1.6f).coerceAtMost(fdBmp.height.toFloat())
                c.drawRect(left, top, right, bottom, paint)
            }
            // scale back to original width
            val finalBmp = Bitmap.createScaledBitmap(out, src.width, src.height, true)
            return finalBmp
        } catch (e: Exception) {
            FileLogger.e(this, "WebViewActivity", "darkenFaces err: ${e.message}")
            return src
        }
    }

    private fun bitmapToDataUri(bmp: Bitmap): String {
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 90, baos)
        val encoded = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        return "data:image/png;base64,$encoded"
    }

    private fun escapeForJs(s: String): String {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
