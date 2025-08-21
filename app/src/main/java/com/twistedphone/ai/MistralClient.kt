package com.twistedphone.ai

import com.twistedphone.util.Logger
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import com.twistedphone.TwistedApp

/**
 * Minimal Mistral client that posts to the agents completions API and returns plain text.
 *
 * Usage:
 *  - Call MistralClient.twistTextWithRetries(...) from coroutines (suspend).
 *  - For older call-sites that used MistralClient(context, prompt) style, an operator invoke wrapper is provided.
 *
 * Notes:
 *  - Reads API key + agent id from TwistedApp.instance.settingsPrefs ("mistral_api_key", "mistral_agent").
 *  - Retries with jitter on transient failures.
 */
object MistralClient {
    private const val TAG = "MistralClient"
    private const val BASE_URL = "https://api.mistral.ai/v1/agents/completions"

    operator fun invoke(prompt: String): String? {
        // convenience synchronous wrapper for older callsites
        return try {
            runBlocking { twistTextWithRetries(prompt, 1, 4) }
        } catch (e: Exception) {
            Logger.e(TAG, "invoke() failed: ${e.message}")
            null
        }
    }

    /**
     * Twist text by calling the Mistral agent with retries/jitter.
     * Returns null if no valid response.
     */
    suspend fun twistTextWithRetries(prompt: String, minAttempts: Int = 1, maxAttempts: Int = 4): String? {
        val prefs = TwistedApp.instance.settingsPrefs
        val apiKey = prefs.getString("mistral_api_key", null)
        val agent = prefs.getString("mistral_agent", null)
        if (apiKey.isNullOrBlank() || agent.isNullOrBlank()) {
            Logger.e(TAG, "Missing Mistral API key or agent id in prefs")
            return null
        }

        var attempt = 0
        while (attempt < maxAttempts) {
            attempt++
            try {
                val r = callAgent(prompt, apiKey, agent)
                if (!r.isNullOrBlank()) return r
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
            val url = URL(BASE_URL)
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 25_000
                readTimeout = 30_000
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }

            val payload = JSONObject()
            payload.put("agent_id", agentId)
            payload.put("max_tokens", 300)
            payload.put("stream", false)

            val messages = JSONArray()
            val m = JSONObject()
            m.put("role", "user")
            m.put("content", prompt)
            messages.put(m)
            payload.put("messages", messages)
            payload.put("response_format", JSONObject().put("type", "text"))

            val w = OutputStreamWriter(conn.outputStream)
            w.write(payload.toString())
            w.flush()
            w.close()

            val code = conn.responseCode
            val reader = if (code in 200..299) BufferedReader(conn.inputStream.reader()) else BufferedReader((conn.errorStream ?: conn.inputStream).reader())
            val sb = StringBuilder()
            reader.use {
                var l = it.readLine()
                while (l != null) { sb.append(l); l = it.readLine() }
            }
            val body = sb.toString()
            Logger.d(TAG, "callAgent response code=$code bodyPrefix=${body.take(800)}")

            if (code in 200..299) {
                val j = JSONObject(body)
                val choices = j.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    val msg = choices.getJSONObject(0).optJSONObject("message")
                    val content = msg?.optString("content", null)
                    return content?.trim()
                }
                return null
            } else {
                // if rate limited or other error, return null so caller can retry
                Logger.e(TAG, "callAgent returned HTTP $code")
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
