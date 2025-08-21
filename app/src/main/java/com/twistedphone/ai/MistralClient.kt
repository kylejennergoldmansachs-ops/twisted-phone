package com.twistedphone.ai

import com.twistedphone.util.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random
import com.twistedphone.TwistedApp

/**
 * Robust Mistral client implemented as a Kotlin object (singleton).
 * Reads API key + agent id from TwistedApp.instance.settingsPrefs.
 */
object MistralClient {
    private const val TAG = "MistralClient"
    // Keep the default base URL; if your environment requires a different endpoint,
    // store it in prefs or change here.
    private const val BASE_URL = "https://api.mistral.ai/v1/agents/completions"

    /**
     * Top-level helper with randomized backoff and retries.
     * Returns trimmed text or null.
     */
    fun twistTextWithRetries(prompt: String, minAttempts: Int = 1, maxAttempts: Int = 4): String? = runBlocking {
        val prefs = TwistedApp.instance.settingsPrefs
        val apiKey = prefs.getString("mistral_api_key", null)
        val agent = prefs.getString("mistral_agent", null)
        if (apiKey.isNullOrBlank() || agent.isNullOrBlank()) {
            Logger.e(TAG, "Missing Mistral API key or agent id in prefs")
            return@runBlocking null
        }

        var attempt = 0
        while (attempt < maxAttempts) {
            attempt++
            try {
                val r = callAgent(prompt, apiKey, agent)
                if (!r.isNullOrBlank()) {
                    Logger.d(TAG, "twistTextWithRetries success on attempt $attempt")
                    return@runBlocking r
                }
            } catch (e: Exception) {
                Logger.e(TAG, "twistTextWithRetries attempt $attempt exception: ${e.message}")
            }

            if (attempt >= minAttempts) {
                val wait = Random.nextLong(1500L, 6000L)
                Logger.d(TAG, "twistTextWithRetries attempt $attempt failed; waiting ${wait}ms")
                delay(wait)
            }
        }
        return@runBlocking null
    }

    /**
     * Low-level HTTP call to the agent completions endpoint.
     * Returns text or null.
     */
    private fun callAgent(prompt: String, apiKey: String, agentId: String): String? {
        var conn: HttpURLConnection? = null
        try {
            val url = URL(BASE_URL)
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 25_000
                readTimeout = 30_000
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }

            val payload = JSONObject().apply {
                put("agent_id", agentId)
                put("max_tokens", 400)
                put("stream", false)
                val messages = JSONArray()
                val m = JSONObject()
                m.put("role", "user")
                m.put("content", prompt)
                messages.put(m)
                put("messages", messages)
                put("response_format", JSONObject().put("type", "text"))
            }

            OutputStreamWriter(conn.outputStream).use { w ->
                w.write(payload.toString())
                w.flush()
            }

            val code = conn.responseCode
            val sb = StringBuilder()

            // Read response (success or error stream)
            try {
                BufferedReader(InputStreamReader(conn.inputStream)).use { br ->
                    var l = br.readLine()
                    while (l != null) { sb.append(l); l = br.readLine() }
                }
            } catch (e: Exception) {
                // try error stream
                conn.errorStream?.reader()?.use { sb.append(it.readText()) }
            }

            val body = sb.toString()
            Logger.d(TAG, "callAgent response code=$code bodyPrefix=${body.take(800)}")

            if (code in 200..299) {
                val j = JSONObject(body)
                val choices = j.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    val msg = choices.optJSONObject(0)?.optJSONObject("message")
                    val content = msg?.optString("content", null)
                    return content?.trim()
                }
                // Defensive: check top-level text keys sometimes used by alternative endpoints
                val text = j.optString("text", null) ?: j.optString("output", null)
                return text?.trim()
            } else {
                Logger.e(TAG, "callAgent returned HTTP $code")
                return null
            }
        } catch (e: Exception) {
            Logger.e(TAG, "callAgent exception: ${e.message}")
            try {
                val err = conn?.errorStream?.reader()?.use { it.readText() }
                if (!err.isNullOrBlank()) Logger.d(TAG, "callAgent error body: ${err.take(800)}")
            } catch (_: Exception) {}
            return null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Generate a short "alt" mobile message (1-3 sentences).
     */
    fun generateAltMessage(contextHint: String, history: List<String>): String? {
        val hist = if (history.isEmpty()) "" else history.joinToString(separator = "\n") { it }
        val prompt = """
            You are a creative assistant that writes short, uncanny mobile messages based on a context hint and a short history.
            Context: $contextHint
            History:
            $hist

            Write a short (1-3 sentences) message suitable to appear as a mysterious incoming message on a phone.
            Do not include any labels or metadata. Output just the message text.
        """.trimIndent()
        return twistTextWithRetries(prompt, 1, 3)
    }

    /**
     * Describe an image provided as a data URI or base64 string.
     */
    fun getImageDescription(imageDataUri: String): String? {
        val prompt = """
            You are an image describer. Given the image data below (data URI or base64),
            produce a single concise sentence describing the main scene and the most salient objects.
            If the input looks invalid, reply with an empty string.
            Image:
            $imageDataUri
            Description:
        """.trimIndent()
        return twistTextWithRetries(prompt, 1, 2)
    }

    /**
     * Transform an image (data URI) to a distorted/selfie data URI or a single-line description.
     * Returns whatever the agent outputs (data URI or textual description).
     */
    fun transformImageToDataUri(imageDataUri: String): String? {
        val prompt = """
            You are a playful image transformer. The input is an image as a data URI:
            $imageDataUri

            Your task: describe a succinct transformation that would make this image look like a distorted / uncanny selfie,
            and then output a single line containing a data URI (data:image/jpeg;base64,...) representing that transformed image.
            If you cannot produce binary data, output a short textual description that can be used instead.
            Output exactly one value (either a data URI or a single-line description).
        """.trimIndent()
        return twistTextWithRetries(prompt, 1, 3)
    }
}
