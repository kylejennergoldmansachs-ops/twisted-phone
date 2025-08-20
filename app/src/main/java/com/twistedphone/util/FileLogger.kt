package com.twistedphone.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileLogger {
    private const val FILENAME = "twisted_log.txt"
    private val tsFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun d(context: Context, tag: String, message: String) {
        Log.d(tag, message)
        appendToFile(context, "D", tag, message)
    }

    fun e(context: Context, tag: String, message: String) {
        Log.e(tag, message)
        appendToFile(context, "E", tag, message)
    }

    private fun appendToFile(context: Context, level: String, tag: String, message: String) {
        try {
            val f = File(context.filesDir, FILENAME)
            val ts = tsFormat.format(Date())
            f.appendText("$ts $level/$tag: $message\n")
        } catch (io: IOException) {
            // don't crash on logger failure; fallback to regular Log
            Log.e("FileLogger", "Failed to write log: ${io.message}")
        }
    }

    fun readAll(context: Context): String {
        return try {
            val f = File(context.filesDir, FILENAME)
            if (!f.exists()) return "— no logs yet —"
            f.readText()
        } catch (io: IOException) {
            "Failed to read log: ${io.message}"
        }
    }
}
