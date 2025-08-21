package com.twistedphone.util

/**
 * Logger wrapper:
 * - Tries to call a static FileLogger method (any method that accepts a single String)
 *   on some common class names if present:
 *     - com.twistedphone.FileLogger
 *     - com.twistedphone.logging.FileLogger
 *     - com.twistedphone.util.FileLogger
 * - If it cannot find such a method, falls back to android.util.Log.
 *
 * This approach avoids compile-time dependency on your FileLogger implementation
 * while still using it on-device for your hold-home GUI.
 */
object Logger {
    private val candidates = arrayOf(
        "com.twistedphone.FileLogger",
        "com.twistedphone.logging.FileLogger",
        "com.twistedphone.util.FileLogger"
    )

    fun d(tag: String, msg: String) = write("D", tag, msg)
    fun i(tag: String, msg: String) = write("I", tag, msg)
    fun e(tag: String, msg: String) = write("E", tag, msg)

    private fun write(level: String, tag: String, msg: String) {
        val combined = "$level/$tag: $msg"
        try {
            for (name in candidates) {
                try {
                    val clazz = Class.forName(name)
                    val methods = clazz.methods
                    for (m in methods) {
                        val params = m.parameterTypes
                        if (java.lang.reflect.Modifier.isStatic(m.modifiers) && params.size == 1 && params[0] == String::class.java) {
                            try {
                                m.invoke(null, combined)
                                return
                            } catch (_: Throwable) {
                                // try next method
                            }
                        }
                    }
                } catch (_: ClassNotFoundException) {
                    // try next candidate
                } catch (_: Throwable) {
                    // ignored, try next candidate
                }
            }
        } catch (_: Throwable) {
            // fallthrough to Android Log
        }

        // Fallback to standard Android logging if FileLogger couldn't be used
        try {
            when (level) {
                "E" -> android.util.Log.e(tag, msg)
                "I" -> android.util.Log.i(tag, msg)
                else -> android.util.Log.d(tag, msg)
            }
        } catch (_: Throwable) {
            // last resort: ignore
        }
    }
}
