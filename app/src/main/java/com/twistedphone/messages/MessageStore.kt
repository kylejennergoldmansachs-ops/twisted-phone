package com.twistedphone.messages
import android.content.Context

object MessageStore {
    fun addMessage(ctx: Context, who: String, text: String) {
        val prefs = ctx.getSharedPreferences("tw_msgs", 0)
        val s = prefs.getStringSet("msgs", mutableSetOf())!!.toMutableSet()
        s.add("${who}|${System.currentTimeMillis()}|${text}")
        prefs.edit().putStringSet("msgs", s).apply()
    }
    fun allMessages(ctx: Context) = ctx.getSharedPreferences("tw_msgs",0).getStringSet("msgs",mutableSetOf())!!.toList().sorted()
    fun recentHistory(ctx: Context, limit: Int = 10): String {
        return allMessages(ctx).takeLast(limit).joinToString("\n") { it.split("|", 3).let { p -> "${p[0]}: ${p[2]}" } }
    }
}
