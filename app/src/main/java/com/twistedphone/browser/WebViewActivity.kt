package com.twistedphone.browser

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.FaceDetector
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.twistedphone.util.Logger
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.twistedphone.ai.MistralClient
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.*
import kotlin.math.min
import org.json.JSONObject
import com.twistedphone.util.Filelogger

class WebViewActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val MAX_TIMEOUT_MS = 15_000L
    private var transformJob: Job? = null
    private val TAG = "WebViewActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Create WebView programmatically to avoid resource id mismatches.
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        setContentView(web)

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                Logger.d(TAG, "onPageFinished: $url")
                startTransform(url ?: "")
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                Log.d(TAG, "onPageStarted: $url")
            }
        }

        web.loadUrl("https://en.m.wikipedia.org/wiki/Main_Page")
    }

    private fun startTransform(url: String) {
        transformJob?.cancel()
        injectLoadingOverlay(0)

        transformJob = scope.launch {
            try {
                Log.d(TAG, "performTransformations start for $url")
                val start = System.currentTimeMillis()
                val (texts, imgs) = extractTopTextsAndImages()

                Log.d(TAG, "Found texts=${texts.size} imgs=${imgs.size} (top10)")
                val totalWork = texts.size + imgs.size
                var done = 0
                val deadline = start + MAX_TIMEOUT_MS

                val replacements = mutableMapOf<String, String>()

                for (item in texts) {
                    if (!isActive) return@launch
                    if (System.currentTimeMillis() > deadline) {
                        Log.d(TAG, "Timed out during text transforms")
                        break
                    }
                    val original = item.getString("text")
                    val xpath = item.getString("xpath")
                    Log.d(TAG, "Sending to Mistral: ${original.take(200)}")
                    val twisted = MistralClient.twistTextWithRetries(original, 1, 4) ?: original
                    replacements[xpath] = twisted
                    done++
                    updateProgress((done.toFloat() / totalWork.toFloat() * 100).toInt())
                }

                if (replacements.isNotEmpty()) {
                    val js = buildReplaceTextJs(replacements)
                    evaluateJsOnMain(js)
                }

                for (src in imgs) {
                    if (!isActive) break
                    if (System.currentTimeMillis() > deadline) {
                        Log.d(TAG, "Timed out during image transforms")
                        break
                    }
                    try {
                        Log.d(TAG, "Downloading image for face-detection: $src")
                        val bmp = downloadBitmap(src)
                        if (bmp != null) {
                            val processed = darkenFaces(bmp)
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
                            evaluateJsOnMain(injectJs)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "image transform failed: ${e.message}")
                    }
                    done++
                    updateProgress((done.toFloat() / totalWork.toFloat() * 100).toInt())
                }

                evaluateJsOnMain("document.getElementById('__twisted_overlay__') && document.getElementById('__twisted_overlay__').remove();")
                Log.d(TAG, "Injected replacements (texts=${replacements.size}, images=${imgs.size})")
            } catch (e: Exception) {
                Log.e(TAG, "Transform job failed: ${e.message}")
                evaluateJsOnMain("document.getElementById('__twisted_overlay__') && document.getElementById('__twisted_overlay__').remove();")
            }
        }

        mainHandler.postDelayed({
            transformJob?.cancel()
            evaluateJsOnMain("document.getElementById('__twisted_overlay__') && document.getElementById('__twisted_overlay__').remove();")
            Log.d(TAG, "Transform timeout after ${MAX_TIMEOUT_MS}ms")
        }, MAX_TIMEOUT_MS)
    }

    private suspend fun extractTopTextsAndImages(): Pair<List<JSONObject>, List<String>> = withContext(Dispatchers.Main) {
        val js = """
            (function(){
              function visible(el){
                var s = window.getComputedStyle(el);
                return s && s.display !== 'none' && s.visibility !== 'hidden' && s.opacity !== '0';
              }
              var texts = [];
              var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, {acceptNode: function(node){ if(node && node.nodeValue && node.nodeValue.trim().length>20) return NodeFilter.FILTER_ACCEPT; return NodeFilter.FILTER_REJECT }}, false);
              while(walker.nextNode() && texts.length<10){
                var n = walker.currentNode.nodeValue.trim();
                var p = walker.currentNode.parentElement;
                if(p && visible(p)){
                  var xpath = '';
                  try { xpath = getXPathForElement(p); } catch(e){}
                  texts.push({text:n, xpath:xpath, len:n.length});
                }
              }
              function getXPathForElement(el) {
                if(el.id) return 'id(\"'+el.id+'\")';
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
                val unquoted = if (raw != null && raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
                    raw.substring(1, raw.length - 1).replace("\\\\n", "\n").replace("\\\\\"", "\"").replace("\\\\\\\\", "\\")
                } else raw ?: ""
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

    private fun buildReplaceTextJs(map: Map<String, String>): String {
        val sb = StringBuilder()
        sb.append("(function(){")
        for ((xpath, repl) in map) {
            val esc = escapeForJs(repl)
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
            Log.e(TAG, "downloadBitmap error: ${e.message}")
            null
        }
    }

    private fun darkenFaces(src: Bitmap): Bitmap {
        try {
            val w = min(800, src.width)
            val aspect = src.height.toFloat() / src.width
            val h = (w * aspect).toInt()
            val scaled = Bitmap.createScaledBitmap(src, w, h, true)
            val fdBmp = scaled.copy(Bitmap.Config.RGB_565, true)
            val faceDetector = FaceDetector(fdBmp.width, fdBmp.height, 5)
            val faces = arrayOfNulls<FaceDetector.Face>(5)
            val count = faceDetector.findFaces(fdBmp, faces)
            if (count <= 0) return src
            val out = Bitmap.createBitmap(fdBmp.width, fdBmp.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            c.drawBitmap(fdBmp, 0f, 0f, null)
            val paint = Paint().apply { color = Color.argb(200, 0, 0, 0); style = Paint.Style.FILL }
            for (i in 0 until count) {
                val f = faces[i] ?: continue
                val eyes = android.graphics.PointF()
                f.getMidPoint(eyes)
                val eyesDist = f.eyesDistance()
                val left = (eyes.x - eyesDist * 1.6f).coerceAtLeast(0f)
                val top = (eyes.y - eyesDist * 1.6f).coerceAtLeast(0f)
                val right = (eyes.x + eyesDist * 1.6f).coerceAtMost(fdBmp.width.toFloat())
                val bottom = (eyes.y + eyesDist * 1.6f).coerceAtMost(fdBmp.height.toFloat())
                c.drawRect(left, top, right, bottom, paint)
            }
            val finalBmp = Bitmap.createScaledBitmap(out, src.width, src.height, true)
            return finalBmp
        } catch (e: Exception) {
            Log.e(TAG, "darkenFaces err: ${e.message}")
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
