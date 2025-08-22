package com.twistedphone.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.twistedphone.R
import com.twistedphone.ai.MistralClient
import com.twistedphone.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

@SuppressLint("SetJavaScriptEnabled")
class WebViewActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "WebViewActivity"
        private const val MAX_WARP_SECONDS = 15L
    }

    private lateinit var webview: WebView
    private lateinit var loadingOverlay: View
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val client = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webview)
        webview = findViewById(R.id.webview)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        progressBar = findViewById(R.id.progressBar)
        progressText = TextView(this).apply {
            // overlay shows percent label; we will attach it to the overlay
            (loadingOverlay.parent as? View)?.let { parent ->
                // layout already exists; simply set text properties
                text = "LOADING 0%"
                setTextColor(0xFF000000.toInt())
                loadingOverlay.post { loadingOverlay.visibility = View.GONE }
            }
        }

        webview.settings.javaScriptEnabled = true
        webview.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = false
            override fun onPageFinished(view: WebView?, url: String?) {
                FileLogger.d(this@WebViewActivity, TAG, "Page finished load: $url")
                // Start warping flow
                startWarpProcess(url ?: "")
            }
        }
        webview.webChromeClient = WebChromeClient()
        // load initial URL passed in intent
        val toLoad = intent?.dataString ?: "https://en.wikipedia.org/wiki/Main_Page"
        webview.loadUrl(toLoad)
    }

    private fun runOnUi(block: () -> Unit) = runOnUiThread { block() }

    private fun showOverlay() {
        runOnUi { loadingOverlay.visibility = View.VISIBLE; progressBar.visibility = View.VISIBLE }
    }

    private fun hideOverlay() {
        runOnUi { loadingOverlay.visibility = View.GONE; progressBar.visibility = View.GONE }
    }

    private fun updateProgress(percent: Int) {
        runOnUi {
            try {
                progressBar.progress = percent
            } catch (_: Exception) {}
            progressText.text = "LOADING ${percent}%"
            if (loadingOverlay.parent != null) {
                // attach progress text if not attached already
                if (progressText.parent == null) {
                    (loadingOverlay as? View)?.post {
                        // add as child if overlay is a ViewGroup - simple approach: skip if not possible
                    }
                }
            }
        }
        FileLogger.d(this, TAG, "Warp progress: $percent%")
    }

    private fun startWarpProcess(url: String) {
        showOverlay()
        updateProgress(0)
        val startedAt = System.currentTimeMillis()
        scope.launch {
            try {
                // 1) Extract largest text nodes via JS
                val jsExtract = """
                    (function(){
                      var nodes = [];
                      function textLength(el) {
                        return (el && el.innerText) ? el.innerText.length : 0;
                      }
                      var all = document.querySelectorAll('body *');
                      for (var i=0;i<all.length;i++) {
                        var el = all[i];
                        if (el && el.innerText && el.innerText.trim().length>10) {
                          var rect = el.getBoundingClientRect();
                          nodes.push({len: el.innerText.trim().length, area: rect.width*rect.height, text: el.innerText.trim().slice(0,1500), xpath: el});
                        }
                      }
                      // convert to simple array of text by area+length and return
                      var arr = [];
                      for (var i=0;i<nodes.length;i++) arr.push(nodes[i].text);
                      return JSON.stringify(arr.slice(0,50));
                    })();
                """.trimIndent()
                // evaluate and get stringified JSON (on UI thread)
                val extractedTextJson = evaluateJavascriptBlocking(jsExtract)
                FileLogger.d(this@WebViewActivity, TAG, "Extracted text JSON prefix: ${extractedTextJson?.take(800)}")
                val textList = parseJsonArrayOfStrings(extractedTextJson)

                // pick top 10 by naive heuristic (longest first)
                val top = textList.sortedByDescending { it.length }.take(10)
                FileLogger.d(this@WebViewActivity, TAG, "Top ${top.size} text blocks for twist: lengths=${top.map{it.length}}")

                // 2) For each text, call Mistral to get twisted replacement (with retries)
                val replacements = mutableListOf<String?>()
                for ((idx, t) in top.withIndex()) {
                    updateProgress(((idx.toFloat() / (top.size + 6)) * 100).toInt())
                    FileLogger.d(this@WebViewActivity, TAG, "Sending text ${idx+1}/${top.size} to Mistral; length=${t.length}")
                    val prompt = "Twist the following text to a darker, uncanny version but keep approximately the same length and sentence structure:\n\n$t"
                    val twisted = MistralClient.twistTextWithRetries(prompt, 1, 3)
                    FileLogger.d(this@WebViewActivity, TAG, "Mistral response for text ${idx+1}: ${twisted?.take(800) ?: "<null>"}")
                    replacements.add(twisted)
                }

                updateProgress(50)

                // 3) Replace text nodes — we do simple substring replacements: find occurrences and replace
                // For safety we only replace short previews we extracted (first n characters) to avoid large DOM mutation risks
                for ((i, original) in top.withIndex()) {
                    val repl = replacements.getOrNull(i)
                    if (repl.isNullOrBlank()) continue
                    val jsReplace = """
                        (function(){
                          try {
                            var all = document.querySelectorAll('body *');
                            for (var i=0;i<all.length;i++) {
                              var el = all[i];
                              if (el && el.innerText && el.innerText.includes(${jsEscapeString(original.substring(0, minOf(200, original.length)))})) {
                                el.innerText = el.innerText.replace(${jsEscapeString(original.substring(0, minOf(200, original.length)))}, ${jsEscapeString(repl)});
                                break;
                              }
                            }
                          } catch(e){}
                        })();
                    """.trimIndent()
                    evaluateJavascriptBlocking(jsReplace)
                }

                updateProgress(70)

                // 4) Image processing: find top images by size (JS extract), download and darken faces (simple)
                val jsImgs = """
                    (function(){
                      var imgs = Array.from(document.images).map(function(i){ return {src:i.src, w:i.naturalWidth, h:i.naturalHeight}; });
                      imgs.sort(function(a,b){ return (b.w*b.h) - (a.w*a.h); });
                      return JSON.stringify(imgs.slice(0,10));
                    })()
                """.trimIndent()
                val imgsJson = evaluateJavascriptBlocking(jsImgs)
                FileLogger.d(this@WebViewActivity, TAG, "Top images json: ${imgsJson?.take(800)}")
                val imgEntries = parseImageEntries(imgsJson)

                var imgCount = 0
                for ((idx, img) in imgEntries.withIndex()) {
                    try {
                        updateProgress(70 + ((idx.toFloat() / maxOf(1, imgEntries.size) ) * 25).toInt())
                        if (img.src.isNullOrBlank()) continue
                        FileLogger.d(this@WebViewActivity, TAG, "Downloading image ${idx+1}/${imgEntries.size}: ${img.src.take(200)}")
                        val req = Request.Builder().url(img.src).build()
                        val resp = client.newCall(req).execute()
                        val bytes = resp.body?.bytes()
                        resp.close()
                        if (bytes == null || bytes.isEmpty()) { FileLogger.d(this@WebViewActivity, TAG, "Image download empty"); continue }
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                        // simple face-darken: draw translucent black circle over faces if any detected using ML Kit face detector
                        val processed = simpleDarkenFaces(bmp)
                        val baos = ByteArrayOutputStream()
                        processed.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                        val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                        val dataUri = "data:image/jpeg;base64,$b64"
                        // replace one occurrence of this src in DOM
                        val safeSrc = jsEscapeString(img.src)
                        val jsReplaceImg = """
                            (function(){
                              try {
                                var imgs = Array.from(document.images);
                                for (var i=0;i<imgs.length;i++){
                                  if (imgs[i].src && imgs[i].src.indexOf(${safeSrc}.replace(/^"(.*)"$/,'$1'))!==-1) {
                                    imgs[i].dataset._twisted = '1';
                                    imgs[i].src = '${dataUri}';
                                    break;
                                  }
                                }
                              } catch(e){}
                            })();
                        """.trimIndent()
                        evaluateJavascriptBlocking(jsReplaceImg)
                        imgCount++
                        FileLogger.d(this@WebViewActivity, TAG, "Replaced page image ${idx+1} with twisted dataUri")
                    } catch (e: Exception) {
                        FileLogger.e(this@WebViewActivity, TAG, "Image processing failed for index $idx: ${e.message}")
                    }
                }

                FileLogger.d(this@WebViewActivity, TAG, "Image replacements done: $imgCount images processed")
                updateProgress(95)

                // Done — either wait a bit to show result or hide overlay
                val elapsed = (System.currentTimeMillis() - startedAt) / 1000L
                if (elapsed < MAX_WARP_SECONDS) {
                    // simulate finishing progress to 100%
                    updateProgress(100)
                }
            } catch (e: Exception) {
                FileLogger.e(this@WebViewActivity, TAG, "Warping pipeline error: ${e.message}")
            } finally {
                // ensure we hide overlay after total max time
                runOnUiThread {
                    loadingOverlay.postDelayed({ hideOverlay() }, 300)
                }
            }
        }
    }

    // Helpful utilities

    private fun jsEscapeString(s: String): String {
        val esc = s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("'", "\\'").replace("\"", "\\\"")
        return "\"$esc\""
    }

    private fun evaluateJavascriptBlocking(js: String, timeoutMs: Long = 8000): String? {
        val job = CompletableDeferred<String?>()
        runOnUiThread {
            try {
                webview.evaluateJavascript(js) { result ->
                    job.complete(result)
                }
            } catch (e: Exception) {
                job.completeExceptionally(e)
            }
        }
        return try {
            runBlockingWithTimeout(timeoutMs) { job.await() }
        } catch (e: Exception) {
            FileLogger.e(this, TAG, "evaluateJavascriptBlocking failed: ${e.message}")
            null
        }
    }

    private fun runBlockingWithTimeout(ms: Long, block: suspend () -> String?): String? {
        return try {
            kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(ms) { block() } }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseJsonArrayOfStrings(json: String?): List<String> {
        try {
            if (json == null) return emptyList()
            // webview evaluate returns quoted JSON string, possibly with surrounding quotes - trim
            val cleaned = json.trim().trim('"').replace("\\n", "\n").replace("\\\"", "\"")
            // try parsing naively: it's expected to be a JSON array of strings
            val arr = org.json.JSONArray(cleaned)
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                out.add(arr.optString(i, ""))
            }
            return out
        } catch (e: Exception) {
            FileLogger.e(this, TAG, "parseJsonArrayOfStrings failed: ${e.message}")
            return emptyList()
        }
    }

    private data class ImgEntry(val src: String?, val w: Int, val h: Int)
    private fun parseImageEntries(json: String?): List<ImgEntry> {
        try {
            if (json == null) return emptyList()
            val cleaned = json.trim().trim('"').replace("\\\"", "\"")
            val arr = org.json.JSONArray(cleaned)
            val out = mutableListOf<ImgEntry>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                out.add(ImgEntry(obj.optString("src", null), obj.optInt("w", 0), obj.optInt("h", 0)))
            }
            return out
        } catch (e: Exception) {
            FileLogger.e(this, TAG, "parseImageEntries failed: ${e.message}")
            return emptyList()
        }
    }

    private fun simpleDarkenFaces(bmp: Bitmap): Bitmap {
        try {
            val mutable = bmp.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = android.graphics.Canvas(mutable)
            val paint = android.graphics.Paint().apply { isAntiAlias = true }
            // quick attempt: use Google MLKit face detection (fast) — do on IO thread synchronously
            val input = InputImage.fromBitmap(bmp, 0)
            val faces = try {
                Tasks.await(FaceDetection.getClient(FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).build()).process(input), 2000)
            } catch (e: Exception) {
                emptyList<com.google.mlkit.vision.face.Face>()
            }
            for (f in faces) {
                val r = f.boundingBox
                paint.color = 0xAA000000.toInt()
                canvas.drawRect(r, paint)
            }
            return mutable
        } catch (e: Exception) {
            FileLogger.e(this, TAG, "simpleDarkenFaces failed: ${e.message}")
            return bmp
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.coroutineContext.cancelChildren()
    }
}
