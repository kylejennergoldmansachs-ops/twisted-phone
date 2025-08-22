package com.twistedphone.messages
import android.content.Context

object MessageStore {
    private const val PREF = "tw_msgs"
    private const val KEY = "msgs"

    fun addMessage(ctx: Context, who: String, text: String) {
        val prefs = ctx.getSharedPreferences(PREF, 0)
        val s = prefs.getStringSet(KEY, mutableSetOf())!!.toMutableSet()
        s.add("${who}|${System.currentTimeMillis()}|${text}")
        prefs.edit().putStringSet(KEY, s).apply()
    }

    fun allMessages(ctx: Context) = ctx.getSharedPreferences(PREF,0).getStringSet(KEY,mutableSetOf())!!.toList().sorted()

    fun recentHistory(ctx: Context, limit: Int = 10): String {
        return allMessages(ctx).takeLast(limit).joinToString("\n") { it.split("|", limit = 3).let { p -> "${p[0]}: ${p[2]}" } }
    }

    /**
     * Compatibility API expected by AltMessageService and other callers: returns a List<String>
     * Each entry formatted as "WHO: message"
     */
    fun getRecentMessages(ctx: Context, limit: Int = 10): List<String> {
        val parsed = allMessages(ctx).mapNotNull { raw ->
            val parts = raw.split("|", limit = 3)
            if (parts.size >= 3) {
                val who = parts[0]
                val ts = parts[1].toLongOrNull() ?: 0L
                val text = parts[2]
                Triple(who, ts, text)
            } else null
        }.sortedBy { it.second }

        return parsed.takeLast(limit).map { (who, _, text) -> "$who: $text" }
    }

    /**
     * Compatibility helper to insert an incoming/generator message.
     * Keeps the same storage format: WHO|timestamp|text
     */
    fun insertIncoming(ctx: Context, text: String, timestampMs: Long = System.currentTimeMillis()) {
        val prefs = ctx.getSharedPreferences(PREF, 0)
        val s = prefs.getStringSet(KEY, mutableSetOf())!!.toMutableSet()
        s.add("ALT|$timestampMs|$text")
        prefs.edit().putStringSet(KEY, s).apply()
    }
}
