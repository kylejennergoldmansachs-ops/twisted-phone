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
 * MistralClient
 * - Tries modern agents payload (agent_id + messages) first.
 * - Falls back to legacy agent/input shape if needed.
 * - Provides twistTextWithRetries(), generateAltMessage(), getImageDescription(), transformImageToDataUri().
 */
class MistralClient {
    private val client = OkHttpClient()
    private val prefs = TwistedApp.instance.securePrefs

    private fun apiKey(): String = prefs.getString("mistral_key", "") ?: ""
    private fun textAgent(): String = prefs.getString("text_agent", "") ?: ""
    private fun pixAgent(): String = prefs.getString("pix_agent", "") ?: ""

    private suspend fun callAgentModern(agentId: String, prompt: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        val key = apiKey()
        if (key.isBlank()) {
            FileLogger.e(TwistedApp.instance, "MistralClient", "No Mistral API key configured (modern)")
            return@withContext Pair(-1, "")
        }
        val payload = JSONObject().apply {
            put("agent_id", agentId)
            val msgs = JSONArray().apply { put(JSONObject().put("role", "user").put("content", prompt)) }
            put("messages", msgs)
            put("response_format", JSONObject().put("type", "text"))
        }
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("https://api.mistral.ai/v1/agents/completions")
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Accept", "application/json")
            .post(body)
            .build()
        try {
            FileLogger.d(TwistedApp.instance, "MistralClient", "callAgentModern agent=$agentId prompt=${prompt.take(180)}")
            client.newCall(req).execute().use { resp ->
                val code = resp.code
                val s = resp.body?.string() ?: ""
                FileLogger.d(TwistedApp.instance, "MistralClient", "callAgentModern response code=$code body=${s.take(1200)}")
                return@withContext Pair(code, s)
            }
        } catch (e: Exception) {
            FileLogger.e(TwistedApp.instance, "MistralClient", "callAgentModern exception: ${e.message}")
            Pair(-1, "")
        }
    }

    private suspend fun callAgentLegacy(agentId: String, prompt: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        val key = apiKey()
        if (key.isBlank()) {
            FileLogger.e(TwistedApp.instance, "MistralClient", "No Mistral API key configured (legacy)")
            return@withContext Pair(-1, "")
        }
        val payload = JSONObject().apply {
            put("agent", agentId)
            put("input", JSONObject().put("text", prompt))
        }
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("https://api.mistral.ai/v1/agents/completions")
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Accept", "application/json")
            .post(body)
            .build()
        try {
            FileLogger.d(TwistedApp.instance, "MistralClient", "callAgentLegacy agent=$agentId prompt=${prompt.take(180)}")
            client.newCall(req).execute().use { resp ->
                val code = resp.code
                val s = resp.body?.string() ?: ""
                FileLogger.d(TwistedApp.instance, "MistralClient", "callAgentLegacy response code=$code body=${s.take(1200)}")
                return@withContext Pair(code, s)
            }
        } catch (e: Exception) {
            FileLogger.e(TwistedApp.instance, "MistralClient", "callAgentLegacy exception: ${e.message}")
            Pair(-1, "")
        }
    }

    private fun parseAgentResponse(body: String): String {
        if (body.isBlank()) return ""
        // Mistral may return plain text or JSON with choices / output
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
        } catch (e: Exception) {
            FileLogger.e(TwistedApp.instance, "MistralClient", "parseAgentResponse non-JSON fallback: ${e.message}")
            // not JSON — return raw trimmed
            return body.trim()
        }
        return ""
    }

    /**
     * High-level agent call: try modern shape first, then legacy shape
     */
    private suspend fun callAgent(agentId: String, prompt: String): String {
        // try modern form
        val (codeModern, bodyModern) = callAgentModern(agentId, prompt)
        if (codeModern in 200..299 && bodyModern.isNotBlank()) {
            return bodyModern
        }
        // if 422 or missing, try legacy
        FileLogger.d(TwistedApp.instance, "MistralClient", "Modern call failed (code=$codeModern); trying legacy shape")
        val (codeLegacy, bodyLegacy) = callAgentLegacy(agentId, prompt)
        if (codeLegacy in 200..299 && bodyLegacy.isNotBlank()) return bodyLegacy
        // If both failed, return latest body (if any) for debugging
        return if (bodyModern.isNotBlank()) bodyModern else bodyLegacy
    }

    /**
     * twistTextWithRetries: send prompt to textAgent with retry/backoff (2-7s)
     */
    suspend fun twistTextWithRetries(input: String, maxAttempts: Int = 3): String {
        if (input.isBlank()) return input
        val agent = textAgent()
        if (agent.isBlank()) {
            FileLogger.e(TwistedApp.instance, "MistralClient", "twistTextWithRetries: no textAgent configured")
            return input
        }
        var attempt = 0
        while (attempt < maxAttempts) {
            attempt++
            try {
                val prompt = "Rewrite the following text into a darker, stranger, unsettling version while keeping similar length and sentence structure. RETURN ONLY the rewritten text:\n\n$input"
                val raw = callAgent(agent, prompt)
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
     * Generate Alternative Self message — uses pixAgent if available
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
        FileLogger.d(TwistedApp.instance, "MistralClient", "generateAltMessage parsed=${parsed.take(240)}")
        parsed
    }

    /**
     * Get a short description of an image (base64).
     */
    suspend fun getImageDescription(imageBase64: String): String = withContext(Dispatchers.IO) {
        val agent = pixAgent().ifBlank { textAgent() }
        if (agent.isBlank()) return@withContext ""
        val prompt = "Describe the following image in one short creepy phrase. Return only the phrase."
        val payload = JSONObject().apply {
            put("agent_id", agent)
            put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            // include the image bytes as an 'input' field too (some deployments accept it)
            put("input", JSONObject().apply { put("image_base64", imageBase64) })
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
     * Download image and darken faces locally then return data:image/jpeg;base64,...
     * Skip non-http(s) schemes (data:// already handled at caller).
     */
    suspend fun transformImageToDataUri(url: String, localDarken: Boolean = true): String = withContext(Dispatchers.IO) {
        if (url.startsWith("data:", true)) return@withContext url
        if (!(url.startsWith("http://", true) || url.startsWith("https://", true))) return@withContext url
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
