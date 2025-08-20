package com.twistedphone.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Base64
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.twistedphone.TwistedApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URL
import kotlin.random.Random

class MistralClient {
    private val client = OkHttpClient()
    private val prefs = TwistedApp.instance.securePrefs
    private fun apiKey() = prefs.getString("mistral_key","") ?: ""
    private fun textAgent() = prefs.getString("text_agent","ag:ddacd900:20250418:genbot-text:2d411523") ?: ""
    private fun pixAgent() = prefs.getString("pix_agent","ag:ddacd900:20250418:untitled-agent:8dfc3563") ?: ""

    /**
     * Original simple call — kept for compatibility.
     */
    suspend fun twistTextPreserveLength(input: String): String = withContext(Dispatchers.IO) {
        val k = apiKey(); if (k.isBlank()) return@withContext input
        val prompt = "Rewrite the following text into a dark, unsettling version while keeping roughly the same length and structure. Return only the rewritten text:\n\n$input"
        val payload = JSONObject().apply { put("agent", textAgent()); put("input", JSONObject().put("text", prompt)) }
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("https://api.mistral.ai/v1/agents/completions").addHeader("Authorization", "Bearer $k").post(body).build()
        try {
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@withContext input
                val s = r.body?.string() ?: return@withContext input
                val jo = JSONObject(s)
                jo.optString("output", jo.optJSONArray("choices")?.optJSONObject(0)?.optString("text", input) ?: input)
            }
        } catch (e: Exception) { input }
    }

    /**
     * Wrapper that retries up to maxAttempts when there's a problem (network, rate limit, non-2xx).
     * On error, waits a random time between 2s and 7s before retrying.
     */
    suspend fun twistTextWithRetries(input: String, maxAttempts: Int = 3): String {
        var attempt = 0
        var lastErr: Exception? = null
        while (attempt < maxAttempts) {
            attempt++
            try {
                val out = twistTextPreserveLength(input)
                // If the response is equal to input and we got an actual http success it's probably fine.
                // But if output is blank treat as failure and retry.
                if (out.isNotBlank()) return out
            } catch (e: Exception) {
                lastErr = e
            }
            // random wait 2..7 seconds before retrying
            val waitMs = (2000 .. 7000).random()
            delay(waitMs.toLong())
        }
        // fallback: return original input if all attempts fail
        return input
    }

    /**
     * Download image, darken faces locally (fast MLKit) and optionally send to Pix agent for a creepier warp,
     * returning a data:image/jpeg;base64,... string for direct assignment to <img>.src
     */
    suspend fun transformImageToDataUri(url: String, localDarken: Boolean = true): String = withContext(Dispatchers.IO) {
        val key = apiKey(); if (key.isBlank()) return@withContext url
        try {
            val conn = URL(url).openConnection().apply { connectTimeout = 5000; readTimeout = 5000 }
            var bmp = android.graphics.BitmapFactory.decodeStream(conn.getInputStream()) ?: return@withContext url
            if (localDarken) bmp = darkenFaces(bmp)
            val max = 512
            val scale = minOf(1.0, max.toDouble() / maxOf(bmp.width, bmp.height))
            val w = (bmp.width * scale).toInt().coerceAtLeast(1); val h = (bmp.height * scale).toInt().coerceAtLeast(1)
            val scaled = android.graphics.Bitmap.createScaledBitmap(bmp, w, h, true)
            val baos = ByteArrayOutputStream(); scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, baos)
            val b = baos.toByteArray(); val b64 = Base64.encodeToString(b, Base64.NO_WRAP)

            // ask Pix agent for a creepier warp (best-effort)
            val payload = JSONObject().apply {
                put("agent", pixAgent())
                put("input", JSONObject().apply {
                    put("image_base64", b64)
                    put("instructions", "Return a warped creepy JPEG base64 string emphasizing darker tones and elongated proportions. Return only base64.")
                })
            }
            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("https://api.mistral.ai/v1/agents/completions").addHeader("Authorization", "Bearer $key").post(body).build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@withContext "data:image/jpeg;base64,$b64"
                val s = r.body?.string() ?: return@withContext "data:image/jpeg;base64,$b64"
                val jo = JSONObject(s)
                val out = jo.optString("image_base64", jo.optString("output", s))
                if (out.startsWith("data:")) out else "data:image/jpeg;base64,$out"
            }
        } catch (e: Exception) {
            // network or processing failed - fallback to original URL
            url
        }
    }

    /**
     * Local face darkening using ML Kit face detection (fast mode). Returns a new Bitmap with rectangles darkened.
     */
    private suspend fun darkenFaces(bmp: Bitmap): Bitmap = withContext(Dispatchers.IO) {
        val options = FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).build()
        val detector = FaceDetection.getClient(options)
        val image = InputImage.fromBitmap(bmp, 0)
        val newBmp = bmp.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(newBmp)
        val paint = Paint().apply { color = 0xCC000000.toInt() } // semi-opaque darken
        try {
            val result = Tasks.await(detector.process(image))
            for (face in result) {
                val bounds = face.boundingBox
                // clip to bitmap bounds
                val left = bounds.left.coerceAtLeast(0)
                val top = bounds.top.coerceAtLeast(0)
                val right = bounds.right.coerceAtMost(newBmp.width)
                val bottom = bounds.bottom.coerceAtMost(newBmp.height)
                if (left < right && top < bottom) {
                    canvas.drawRect(Rect(left, top, right, bottom), paint)
                }
            }
        } catch (_: Exception) {
            // ignore face-detect errors, return original (unmodified) in that case
        }
        newBmp
    }

    // --- Existing alt-related helpers (kept as-is) ---

    suspend fun generateAltMessage(contextHint: String, history: String): String = withContext(Dispatchers.IO) {
        val k = apiKey(); if (k.isBlank()) return@withContext ""
        val name = prefs.getString("player_name", "") ?: ""
        val prompt = "You are the user's alternative self. Use the context and history to produce 1 short unsettling sentence addressing the user by name if available. Keep memory of conversation. History: $history Context: $contextHint"
        val payload = JSONObject().apply { put("agent", textAgent()); put("input", JSONObject().put("text", prompt)) }
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("https://api.mistral.ai/v1/agents/completions").addHeader("Authorization", "Bearer $k").post(body).build()
        try {
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@withContext ""
                val s = r.body?.string() ?: return@withContext ""
                val jo = JSONObject(s)
                jo.optString("output", jo.optJSONArray("choices")?.optJSONObject(0)?.optString("text", "") ?: "")
            }
        } catch (e: Exception) { "" }
    }

    suspend fun getImageDescription(b64: String): String = withContext(Dispatchers.IO) {
        val k = apiKey(); if (k.isBlank()) return@withContext ""
        val payload = JSONObject().apply {
            put("agent", pixAgent())
            put("input", JSONObject().apply {
                put("image_base64", b64)
                put("instructions", "Describe the scene briefly in a creepy way. Return only the description.")
            })
        }
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("https://api.mistral.ai/v1/agents/completions").addHeader("Authorization", "Bearer $k").post(body).build()
        try {
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@withContext ""
                val s = r.body?.string() ?: return@withContext ""
                val jo = JSONObject(s)
                jo.optString("output", "")
            }
        } catch (e: Exception) { "" }
    }
}
