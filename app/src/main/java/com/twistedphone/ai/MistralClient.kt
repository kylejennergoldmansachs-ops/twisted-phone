package com.twistedphone.ai

import android.util.Log
import com.twistedphone.util.FileLogger
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

object MistralClient {
    // Call the Mistral Agents completions endpoint with correct payload shape.
    // Returns plain text or null if failure.
    // NOTE: Caller must provide agentId + apiKey via your settings prefs (we look up TwistedApp)
    private const val TAG = "MistralClient"
    private const val BASE = "https://api.mistral.ai/v1/agents/completions"

    // twistTextWithRetries: send prompt and try up to maxAttempts (with jitter waits 2-7s on retryable errors)
    suspend fun twistTextWithRetries(prompt: String, minAttempts: Int = 1, maxAttempts: Int = 4): String? {
        val apiKey = com.twistedphone.TwistedApp.instance.settingsPrefs.getString("mistral_api_key", null)
        val agent = com.twistedphone.TwistedApp.instance.settingsPrefs.getString("mistral_agent", null)
        if (apiKey.isNullOrEmpty() || agent.isNullOrEmpty()) {
            FileLogger.e(TAG, "missing API key or agent id")
            return null
        }
        var attempt = 0
        while (attempt < maxAttempts) {
            attempt++
            try {
                val out = callAgent(prompt, apiKey, agent)
                if (out != null) return out
                // else fallthrough to retry
            } catch (e: Exception) {
                FileLogger.e(TAG, "callAgent exception: ${e.message}")
            }
            if (attempt >= minAttempts) {
                val wait = Random.nextLong(2000, 7000)
                FileLogger.d(TAG, "twistTextWithRetries attempt ${attempt} failed; waiting ${wait}ms")
                delay(wait)
            }
        }
        return null
    }

    // Low-level single call. Returns plain text or null.
    private fun callAgent(prompt: String, apiKey: String, agentId: String): String? {
        var conn: HttpURLConnection? = null
        try {
            val url = URL(BASE)
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 25000
                readTimeout = 30000
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
                doOutput = true
            }

            val payload = JSONObject()
            payload.put("agent_id", agentId)
            payload.put("max_tokens", 300)
            payload.put("stream", false)
            // messages field - single user message with prompt
            val messages = org.json.JSONArray()
            val mobj = org.json.JSONObject()
            mobj.put("role", "user")
            mobj.put("content", prompt)
            messages.put(mobj)
            payload.put("messages", messages)
            payload.put("response_format", org.json.JSONObject().put("type", "text"))

            val out = OutputStreamWriter(conn.outputStream)
            out.write(payload.toString())
            out.flush()
            out.close()

            val code = conn.responseCode
            val body = StringBuilder()
            val reader = if (code in 200..299) BufferedReader(conn.inputStream.reader()) else BufferedReader(conn.errorStream?.reader() ?: conn.inputStream.reader())
            reader.use {
                var line = it.readLine()
                while (line != null) {
                    body.append(line)
                    line = it.readLine()
                }
            }
            val bodyStr = body.toString()
            FileLogger.d(TAG, "callAgent response code=$code body=$bodyStr")
            if (code == 200 || code == 201) {
                // parse assistant content
                val j = JSONObject(bodyStr)
                val choices = j.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    val msg = choices.getJSONObject(0).getJSONObject("message")
                    val content = msg.optString("content", null)
                    return content?.trim()
                }
                // Fallback: try "choices[0].message.content"
                return null
            } else {
                // handle rate-limit as retryable (429)
                if (code == 429) {
                    FileLogger.d(TAG, "rate limited (429)")
                    return null
                }
                FileLogger.e(TAG, "callAgent error code=$code")
                return null
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "callAgent network exception: ${e.message}")
            return null
        } finally {
            conn?.disconnect()
        }
    }
}
