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

/**
 * Robust WebViewActivity replacement.
 * - Uses activity_webview.xml
 * - Top URL bar + Go button
 * - White overlay with numeric progress while warping (timeout 15s)
 * - Evaluates JS to extract top text/image snippets and inject replacements
 *
 * NOTE: This file uses MistralClient.* utilities that already exist in your repo.
 */
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
                url?.let { urlInput.setText(it) }
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                Logger.d(TAG, "onPageFinished: $url")
                startTransform(url ?: "")
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                // show page load progress (not warp progress)
                progressBar.progress = newProgress
            }
        }

        btnGo.setOnClickListener { loadUrlFromInput() }
        urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { loadUrlFromInput(); true } else false
        }

        // initial page
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
     * Shows the white overlay and kicks off the transform coroutine; enforces the 15s timeout.
     */
    private fun startTransform(url: String) {
        transformJob?.cancel()
        showOverlay(true, "Preparing...")
        transformJob = coroutineScope.launch {
            val start = System.currentTimeMillis()
            val deadline = start + MAX_TIMEOUT_MS
            try {
                Logger.d(TAG, "performTransformations start for $url")

                // Extract text and image srcs via JS
                val extractJson = extractTextsAndImages()
                val texts = parseTextsFromExtract(extractJson)
                val imgs = parseImagesFromExtract(extractJson)

                Logger.d(TAG, "Found texts=${texts.size} imgs=${imgs.size} (top)")

                val totalWork = max(1, texts.size + imgs.size)
                var done = 0

                // 1) Transform texts by sending to Mistral
                val replacements = mutableListOf<Pair<String, String>>()
                for (txt in texts) {
                    if (!isActive) return@launch
                    if (System.currentTimeMillis() > deadline) {
                        Logger.d(TAG, "Timed out during text transforms")
                        break
                    }
                    val original = txt
                    Logger.d(TAG, "Sending to Mistral: ${original.take(200)}")
                    val twisted = MistralClient.twistTextWithRetries(
                        "Rewrite the following text into a darker, stranger, unsettling version while keeping similar length and sentence structure. RETURN ONLY the rewritten text:\n\n$original",
                        1,
                        4
                    )
                    val finalText = twisted ?: original
                    replacements.add(original to finalText)
                    done++
                    updateProgress((done.toFloat() / totalWork.toFloat() * 100f).toInt())
                }

                // apply text replacements (best-effort)
                if (replacements.isNotEmpty()) {
                    val js = buildTextReplaceJs(replacements)
                    runOnUiThread { web.evaluateJavascript(js, null) }
                }

                // 2) Transform images (download and replace with data: URIs)
                for (src in imgs) {
                    if (!isActive) return@launch
                    if (System.currentTimeMillis() > deadline) {
                        Logger.d(TAG, "Timed out during image transforms")
                        break
                    }
                    try {
                        Logger.d(TAG, "Downloading image for processing: $src")
                        val bmp = downloadBitmap(src)
                        if (bmp != null) {
                            // lightweight local face-darken placeholder (best-effort)
                            val processed = darkenFaces(bmp)
                            val dataUri = bitmapToDataUri(processed)
                            val injectJs = """
                                (function(){
                                  var imgs = document.getElementsByTagName('img');
                                  for(var i=0;i<imgs.length;i++){
                                    var el = imgs[i];
                                    if(!el._tw_replaced && el.src && el.src.indexOf('${escapeJsString(src)}') !== -1){
                                      el.style.maxWidth = el.width + "px";
                                      el.dataset._tw_original = el.src;
                                      el.src = "${escapeJsString(dataUri)}";
                                      el._tw_replaced = true;
                                    }
                                  }
                                })();
                            """.trimIndent()
                            runOnUiThread { web.evaluateJavascript(injectJs, null) }
                        }
                    } catch (e: Exception) {
                        Logger.e(TAG, "image transform failed for $src : ${e.message}")
                    }
                    done++
                    updateProgress((done.toFloat() / totalWork.toFloat() * 100f).toInt())
                }

            } catch (e: Exception) {
                Logger.e(TAG, "transform job failed: ${e.message}")
            } finally {
                // hide overlay only if transforms finished OR timeout reached
                val elapsed = System.currentTimeMillis() - start
                if (elapsed >= MAX_TIMEOUT_MS) {
                    Logger.d(TAG, "transform timeout reached; removing overlay")
                } else {
                    Logger.d(TAG, "transform finished in ${elapsed}ms")
                }
                runOnUiThread { showOverlay(false, null) }
            }
        }

        // Enforce a hard timeout to remove overlay after MAX_TIMEOUT_MS
        mainHandler.postDelayed({
            if (transformJob?.isActive == true) {
                transformJob?.cancel()
                Logger.d(TAG, "transformJob cancelled by timeout handler")
                showOverlay(false, null)
            }
        }, MAX_TIMEOUT_MS)
    }

    private fun updateProgress(pct: Int) {
        mainHandler.post {
            loadingPercent.text = "$pct%"
            progressBar.progress = pct
            loadingText.text = "Applying warp — $pct%"
        }
    }

    private fun showOverlay(show: Boolean, text: String?) {
        loadingOverlay.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
        if (show) {
            loadingText.text = text ?: "Processing..."
            loadingPercent.text = "0%"
            progressBar.progress = 0
        }
    }

    /**
     * Evaluates JS that returns JSON {texts: [...], images: [...]}.
     * Implementation: extracts large text nodes and top image srcs.
     */
    private suspend fun extractTextsAndImages(): String {
        return suspendCancellableCoroutine { cont ->
            val js = """
                (function(){
                  function nodeTextSize(n){
                    if(!n) return 0;
                    var s = (n.innerText || n.textContent || "");
                    return s.trim().length;
                  }
                  var allTexts = [];
                  var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_ELEMENT, null, false);
                  var el;
                  while(el = walker.nextNode()) {
                    try {
                      var txt = (el.innerText || el.textContent || "").trim();
                      if(txt && txt.length>20) allTexts.push({text: txt, size: txt.length});
                    } catch(e){}
                  }
                  allTexts.sort(function(a,b){return b.size - a.size});
                  var topTexts = allTexts.slice(0,10).map(function(x){return x.text});
                  var imgs = [];
                  var imgEls = document.getElementsByTagName('img');
                  for(var i=0;i<imgEls.length;i++){
                    try{ if(imgEls[i].src) imgs.push(imgEls[i].src); }catch(e){}
                  }
                  imgs = imgs.slice(0,10);
                  return JSON.stringify({texts: topTexts, images: imgs});
                })();
            """.trimIndent()
            try {
                web.evaluateJavascript(js) { result ->
                    // result is a quoted JSON string; Android returns "null" or quoted string
                    if (cont.isActive) {
                        try {
                            val cleaned = if (result == "null" || result == "undefined") "{}" else result
                            // result could be quoted; remove surrounding quotes
                            val unquoted = if (cleaned.length >= 2 && cleaned.startsWith("\"") && cleaned.endsWith("\"")) {
                                cleaned.substring(1, cleaned.length - 1)
                                    .replace("\\\\n", "")
                                    .replace("\\\"", "\"")
                                    .replace("\\\\", "\\")
                            } else cleaned
                            cont.resume(unquoted, null)
                        } catch (e: Exception) {
                            cont.resumeWith(Result.failure(e))
                        }
                    }
                }
            } catch (e: Exception) {
                if (cont.isActive) cont.resumeWith(Result.failure(e))
            }
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

    /**
     * Download a bitmap from a src (http/https). small safety: timeout & scaling.
     */
    private fun downloadBitmap(src: String): android.graphics.Bitmap? {
        try {
            val url = URL(src)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                instanceFollowRedirects = true
            }
            conn.connect()
            conn.inputStream.use { stream ->
                val bm = android.graphics.BitmapFactory.decodeStream(stream)
                return bm
            }
        } catch (e: Exception) {
            Logger.e(TAG, "downloadBitmap failed: ${e.message}")
            return null
        }
    }

    /**
     * VERY small local "face darken" best-effort: for safety/size we do a simple luminance threshold to
     * darken central faces — it's a placeholder for a local face detector. This ensures we produce modified images.
     */
    private fun darkenFaces(src: android.graphics.Bitmap): android.graphics.Bitmap {
        val bmp = src.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
        try {
            // Simple heuristic: darken bright central ellipse (common face placement)
            val w = bmp.width
            val h = bmp.height
            val canvas = android.graphics.Canvas(bmp)
            val paint = android.graphics.Paint()
            paint.isAntiAlias = true
            paint.color = android.graphics.Color.argb(120, 0, 0, 0) // semi-opaque dark overlay
            val rectF = android.graphics.RectF(w * 0.15f, h * 0.12f, w * 0.85f, h * 0.72f)
            canvas.drawOval(rectF, paint)
        } catch (e: Exception) {
            Logger.e(TAG, "darkenFaces failed: ${e.message}")
        }
        return bmp
    }

    private fun bitmapToDataUri(bmp: android.graphics.Bitmap): String {
        val baos = ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, baos)
        val bytes = baos.toByteArray()
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return "data:image/jpeg;base64,$b64"
    }

    private fun escapeJsString(s: String): String {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("'", "\\'")
    }

    private fun buildTextReplaceJs(replacements: List<Pair<String, String>>): String {
        // Build a JS snippet that walks text nodes and replaces exact occurrences (best-effort).
        val sb = StringBuilder()
        sb.append("(function(){")
        sb.append("function walk(node){var child=node.firstChild; while(child){ if(child.nodeType===3){")
        // text node
        for ((orig, repl) in replacements) {
            val o = escapeJsString(orig.take(200)) // reduce size
            val r = escapeJsString(repl)
            sb.append("try{ child.nodeValue = child.nodeValue.replace(\"$o\", \"$r\"); }catch(e){}")
        }
        sb.append("} else if(child.nodeType===1){ walk(child); } child = child.nextSibling;} }")
        sb.append("walk(document.body); })();")
        return sb.toString()
    }
}
