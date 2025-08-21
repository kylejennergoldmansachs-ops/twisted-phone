package com.twistedphone.ai

import android.content.Context
import com.twistedphone.util.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

/**
 * Mistral client (lightweight). Uses Logger for logging so that your on-device FileLogger receives the messages.
 *
 * Backwards-compatible: operator fun invoke(context, prompt) for older call sites that expect MistralClient(...) usage.
 */
object MistralClient {
    private const val TAG = "MistralClient"
    private const val BASE = "https://api.mistral.ai/v1/agents/completions"

    operator fun invoke(context: Context, prompt: String): String? {
        return try {
            runBlocking { twistTextWithRetries(prompt, 1, 4) }
        } catch (e: Exception) {
            Logger.e(TAG, "invoke error: ${e.message}")
            null
        }
    }

    /**
     * Suspend call that retries with jitter on transient failures.
     */
    suspend fun twistTextWithRetries(prompt: String, minAttempts: Int = 1, maxAttempts: Int = 4): String? {
        val prefs = com.twistedphone.TwistedApp.instance.settingsPrefs
        val apiKey = prefs.getString("mistral_api_key", null)
        val agent = prefs.getString("mistral_agent", null)
        if (apiKey.isNullOrBlank() || agent.isNullOrBlank()) {
            Logger.e(TAG, "Missing mistral_api_key or mistral_agent in prefs")
            return null
        }

        var attempt = 0
        while (attempt < maxAttempts) {
            attempt++
            try {
                val res = callAgent(prompt, apiKey, agent)
                if (res != null) return res
            } catch (e: Exception) {
                Logger.e(TAG, "twistTextWithRetries attempt $attempt exception: ${e.message}")
            }
            if (attempt >= minAttempts) {
                val wait = Random.nextLong(2000, 7000)
                Logger.d(TAG, "twistTextWithRetries attempt $attempt failed; waiting ${wait}ms")
                delay(wait)
            }
        }
        return null
    }

    private fun callAgent(prompt: String, apiKey: String, agentId: String): String? {
        var conn: HttpURLConnection? = null
        try {
            val url = URL(BASE)
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 25_000
                readTimeout = 30_000
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
                doOutput = true
            }

            val payload = JSONObject()
            payload.put("agent_id", agentId)
            payload.put("max_tokens", 300)
            payload.put("stream", false)

            val messages = JSONArray()
            val mobj = JSONObject()
            mobj.put("role", "user")
            mobj.put("content", prompt)
            messages.put(mobj)
            payload.put("messages", messages)
            payload.put("response_format", JSONObject().put("type", "text"))

            val writer = OutputStreamWriter(conn.outputStream)
            writer.write(payload.toString())
            writer.flush()
            writer.close()

            val code = conn.responseCode
            val reader = if (code in 200..299) BufferedReader(conn.inputStream.reader()) else BufferedReader((conn.errorStream ?: conn.inputStream).reader())
            val body = StringBuilder()
            reader.use {
                var line = it.readLine()
                while (line != null) {
                    body.append(line)
                    line = it.readLine()
                }
            }
            val bodyStr = body.toString()
            Logger.d(TAG, "callAgent response code=$code body=${if (bodyStr.length > 1000) bodyStr.substring(0, 1000) + "..." else bodyStr}")

            if (code in 200..299) {
                val j = JSONObject(bodyStr)
                val choices = j.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    val message = choices.getJSONObject(0).optJSONObject("message")
                    val content = message?.optString("content", null)
                    return content?.trim()
                }
                return null
            } else {
                if (code == 429) {
                    Logger.d(TAG, "callAgent rate-limited (429)")
                    return null
                }
                Logger.e(TAG, "callAgent unexpected code=$code")
                return null
            }
        } catch (e: Exception) {
            Logger.e(TAG, "callAgent exception: ${e.message}")
            return null
        } finally {
            conn?.disconnect()
        }
    }
}
