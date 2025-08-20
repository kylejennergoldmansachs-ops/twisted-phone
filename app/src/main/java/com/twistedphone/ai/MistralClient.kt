package com.twistedphone.ai

import com.twistedphone.TwistedApp
import com.twistedphone.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URL
import kotlin.random.Random
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.IOException

/**
 * Robust MistralClient used across the app.
 * - Uses TwistedApp.instance as a Context for logging.
 * - Provides: callAgent, parseAgentResponse, twistTextWithRetries,
 *   generateAltMessage, getImageDescription, transformImageToDataUri
 * - Retries are randomized between 2s and 7s on errors.
 */
class MistralClient {
    private val client = OkHttpClient()
    private val prefs = TwistedApp.instance.securePrefs

    private fun apiKey(): String = prefs.getString("mistral_key", "") ?: ""
    private fun textAgent(): String = prefs.getString("text_agent", "") ?: ""
    private fun pixAgent(): String = prefs.getString("pix_agent", "") ?: ""

    private suspend fun callAgent(agentId: String, inputText: String): String = withContext(Dispatchers.IO) {
        val key = apiKey()
        if (key.isBlank()) {
            FileLogger.e(TwistedApp.instance, "MistralClient", "No Mistral API key configured")
            return@withContext ""
        }
        val payload = JSONObject().apply {
            put("agent", agentId)
            put("input", JSONObject().put("text", inputText))
        }
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("https://api.mistral.ai/v1/agents/completions")
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Accept", "application/json")
            .post(body)
            .build()
        try {
            FileLogger.d(TwistedApp.instance, "MistralClient", "callAgent agent=$agentId prompt=${inputText.take(240)}")
            client.newCall(req).execute().use { resp ->
                val code = resp.code
                val s = resp.body?.string() ?: ""
                FileLogger.d(TwistedApp.instance, "MistralClient", "callAgent response code=$code body=${s.take(1200)}")
                if (!resp.isSuccessful) return@withContext ""
                return@withContext s
            }
        } catch (e: Exception) {
            FileLogger.e(TwistedApp.instance, "MistralClient", "callAgent exception: ${e.message}")
            ""
        }
    }

    /**
     * Best-effort JSON/text parser for Mistral agent responses.
     * Accepts several possible shapes so the rest of the app doesn't receive gibberish.
     */
    private fun parseAgentResponse(body: String): String {
        if (body.isBlank()) return ""
        try {
            val jo = JSONObject(body)
            if (jo.has("output")) {
                val o = jo.opt("output")
                if (o != null) return o.toString()
            }
            if (jo.has("choices")) {
                val choices = jo.optJSONArray("choices") ?: JSONArray()
                if (choices.length() > 0) {
                    val c0 = choices.optJSONObject(0) ?: JSONObject()
                    if (c0.has("text")) return c0.optString("text", "")
                    if (c0.has("message")) {
                        val msg = c0.opt("message")
                        if (msg is JSONObject) {
                            if (msg.has("content")) {
                                val content = msg.opt("content")
                                if (content is String) return content
                                if (content is JSONArray && content.length() > 0) return content.getString(0)
                            }
                            val contentArr = msg.optJSONArray("content")
                            if (contentArr != null && contentArr.length() > 0) return contentArr.getString(0)
                        } else if (msg is String) {
                            return msg
                        }
                    }
                    val maybeText = c0.optString("text", "")
                    if (maybeText.isNotBlank()) return maybeText
                }
            }
            if (jo.has("result")) return jo.optString("result", "")
            if (jo.has("output") && jo.optJSONObject("output")?.has("text") == true) {
                return jo.optJSONObject("output")!!.optString("text", "")
            }
        } catch (e: Exception) {
            // Not JSON or unexpected shape — return raw trimmed string.
            FileLogger.e(TwistedApp.instance, "MistralClient", "parseAgentResponse JSON parse failed: ${e.message}")
            return body.trim()
        }
        return ""
    }

    /**
     * Public method used by the Browser to rewrite page text.
     * Retries up to maxAttempts; waits 2..7s random backoff between attempts.
     */
    suspend fun twistTextWithRetries(input: String, maxAttempts: Int = 3): String {
        if (input.isBlank()) return input
        val agent = textAgent()
        if (agent.isBlank()) {
            FileLogger.e(TwistedApp.instance, "MistralClient", "twistTextWithRetries: no text agent configured")
            return input
        }
        var attempt = 0
        while (attempt < maxAttempts) {
            attempt++
            try {
                val raw = callAgent(agent, "Rewrite the following text into a darker, stranger, unsettling version while keeping similar length and sentence structure. RETURN ONLY the rewritten text:\n\n$input")
                if (raw.isNotBlank()) {
                    val parsed = parseAgentResponse(raw)
                    if (parsed.isNotBlank()) return parsed
                }
            } catch (e: Exception) {
                FileLogger.e(TwistedApp.instance, "MistralClient", "twistText attempt $attempt exception: ${e.message}")
            }
            val wait = Random.nextLong(2000L, 7000L)
            FileLogger.d(TwistedApp.instance, "MistralClient", "twistTextWithRetries attempt $attempt failed; waiting ${wait}ms")
            delay(wait)
        }
        return input
    }

    /**
     * Generate an Alternative Self line. Uses Pix agent if present, otherwise text agent.
     * Returns a single short line of text (or empty string).
     */
    suspend fun generateAltMessage(contextHint: String, history: String): String = withContext(Dispatchers.IO) {
        val agent = pixAgent().ifBlank { textAgent() }
        if (agent.isBlank()) {
            FileLogger.e(TwistedApp.instance, "MistralClient", "generateAltMessage: no agent configured")
            return@withContext ""
        }
        val prompt = """
            You are the user's Alternative Self (AI). Using the provided context and message history, produce ONE short, creepy, unsettling line of text addressing the user. Use the user's name if present. RETURN ONLY ONE LINE OF PLAIN TEXT, NO JSON OR MARKUP.
            
            Context: $contextHint
            
            History: $history
        """.trimIndent()
        val raw = callAgent(agent, prompt)
        val parsed = parseAgentResponse(raw)
        parsed
    }

    /**
     * Used by AltMessageService: takes an image base64 and returns a short description string.
     */
    suspend fun getImageDescription(imageBase64: String): String = withContext(Dispatchers.IO) {
        val agent = pixAgent().ifBlank { textAgent() }
        if (agent.isBlank()) return@withContext ""
        val prompt = "Describe the following image in one short creepy phrase. Return only the phrase."
        val payload = JSONObject().apply {
            put("agent", agent)
            put("input", JSONObject().apply {
                put("image_base64", imageBase64)
                put("instructions", prompt)
            })
        }
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        val key = apiKey()
        val req = Request.Builder().url("https://api.mistral.ai/v1/agents/completions").addHeader("Authorization", "Bearer $key").post(body).build()
        try {
            client.newCall(req).execute().use { resp ->
                val s = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return@withContext ""
                val parsed = parseAgentResponse(s)
                return@withContext parsed
            }
        } catch (e: Exception) {
            FileLogger.e(TwistedApp.instance, "MistralClient", "getImageDescription failed: ${e.message}")
            ""
        }
    }

    /**
     * Downloads an image and darkens detected faces (fast ML Kit detection).
     * Returns a data:image/jpeg;base64,... string on success; falls back to the original URL on failure.
     */
    suspend fun transformImageToDataUri(url: String, localDarken: Boolean = true): String = withContext(Dispatchers.IO) {
        val key = apiKey()
        try {
            val conn = URL(url).openConnection()
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val bmp = BitmapFactory.decodeStream(conn.getInputStream()) ?: return@withContext url
            val processed = if (localDarken) darkenFaces(bmp) else bmp
            val baos = ByteArrayOutputStream()
            processed.compress(Bitmap.CompressFormat.JPEG, 80, baos)
            val b = baos.toByteArray()
            val b64 = Base64.encodeToString(b, Base64.NO_WRAP)
            return@withContext "data:image/jpeg;base64,$b64"
        } catch (e: IOException) {
            FileLogger.e(TwistedApp.instance, "MistralClient", "transformImageToDataUri network failed: ${e.message}")
            return@withContext url
        } catch (e: Exception) {
            FileLogger.e(TwistedApp.instance, "MistralClient", "transformImageToDataUri failed: ${e.message}")
            return@withContext url
        }
    }

    private suspend fun darkenFaces(bmp: Bitmap): Bitmap = withContext(Dispatchers.IO) {
        val options = FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).build()
        val detector = FaceDetection.getClient(options)
        val image = InputImage.fromBitmap(bmp, 0)
        val newBmp = bmp.copy(Bitmap.Config.ARGB_8888, true)
        try {
            val result = Tasks.await(detector.process(image))
            val canvas = android.graphics.Canvas(newBmp)
            val paint = android.graphics.Paint().apply { color = 0xCC000000.toInt() }
            for (face in result) {
                val b = face.boundingBox
                val left = b.left.coerceAtLeast(0)
                val top = b.top.coerceAtLeast(0)
                val right = b.right.coerceAtMost(newBmp.width)
                val bottom = b.bottom.coerceAtMost(newBmp.height)
                if (left < right && top < bottom) {
                    canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), paint)
                }
            }
        } catch (e: Exception) {
            FileLogger.e(TwistedApp.instance, "MistralClient", "darkenFaces error: ${e.message}")
        }
        newBmp
    }
}
