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
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URL
import java.util.concurrent.TimeUnit

class MistralClient {
    private val client = OkHttpClient()
    private val prefs = TwistedApp.instance.securePrefs
    private fun apiKey() = prefs.getString("mistral_key","") ?: ""
    private fun textAgent() = prefs.getString("text_agent","ag:ddacd900:20250418:genbot-text:2d411523") ?: ""
    private fun pixAgent() = prefs.getString("pix_agent","ag:ddacd900:20250418:untitled-agent:8dfc3563") ?: ""
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
        } catch (_: Exception) { url }
    }
    private suspend fun darkenFaces(bmp: Bitmap): Bitmap = withContext(Dispatchers.IO) {
        val options = FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).build()
        val detector = FaceDetection.getClient(options)
        val image = InputImage.fromBitmap(bmp, 0)
        val newBmp = bmp.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(newBmp)
        val paint = Paint().apply { color = 0xAA000000.toInt() }
        val result = Tasks.await(detector.process(image))
        for (face in result) {
            val bounds = face.boundingBox
            canvas.drawRect(Rect(bounds.left, bounds.top, bounds.right, bounds.bottom), paint)
        }
        newBmp
    }
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
