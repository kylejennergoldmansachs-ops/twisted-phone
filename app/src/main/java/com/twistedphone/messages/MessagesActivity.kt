package com.twistedphone.messages

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.twistedphone.R
import com.twistedphone.alt.AltMessageService
import com.twistedphone.util.FileLogger

/**
 * MessagesActivity
 * - Displays messages stacked vertically (main text left-aligned, meta line right-aligned)
 * - Uses MessageStore.addMessage(context, who, text) API (two-arg form) to add messages
 * - Updates the UI in-place instead of finishing/restarting the activity
 */
class MessagesActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_messages)

        container = findViewById(R.id.messagesContainer)
        refreshMessages()

        val sendBtn = findViewById<Button>(R.id.sendBtn)
        val input = findViewById<EditText>(R.id.replyInput)
        sendBtn.setOnClickListener {
            val t = input.text.toString().trim()
            if (t.isNotEmpty()) {
                try {
                    // MessageStore.addMessage(context, who, text) — use the two-arg form expected by the original project
                    MessageStore.addMessage(this, "YOU", t)
                } catch (e: Exception) {
                    // fallback to a safe append into the store string API if it differs
                    try {
                        MessageStore.addMessage(this, "YOU|${System.currentTimeMillis()}|$t")
                    } catch (ex: Exception) {
                        FileLogger.e(this, "MessagesActivity", "addMessage fallback failed: ${ex.message}")
                    }
                }
                input.text.clear()
                // update UI in-place
                refreshMessages()

                // schedule AS reply after a short delay
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        val svcIntent = Intent(this, AltMessageService::class.java)
                        svcIntent.putExtra("is_reply", true)
                        startService(svcIntent)
                        FileLogger.d(this, "MessagesActivity", "Scheduled AltMessageService reply")
                    } catch (e: Exception) {
                        FileLogger.e(this, "MessagesActivity", "Failed to start AltMessageService: ${e.message}")
                    }
                }, 5000)
            }
        }
    }

    private fun refreshMessages() {
        try {
            container.removeAllViews()
            val msgs = MessageStore.allMessages(this)
            for (m in msgs) {
                // expect stored format either "WHO|meta|text" or "WHO|text" or plain text
                val parts = m.split("|", limit = 3)
                val who = parts.getOrNull(0) ?: ""
                val meta = parts.getOrNull(1) ?: ""
                val text = parts.getOrNull(2) ?: if (parts.size >= 2) parts[1] else parts.getOrNull(0) ?: ""

                val block = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    lp.setMargins(8, 8, 8, 8)
                    layoutParams = lp
                }

                val tvMain = TextView(this).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    this.text = text
                    gravity = Gravity.START
                }
                val tvMeta = TextView(this).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    this.text = meta
                    gravity = Gravity.END
                }
                block.addView(tvMain)
                block.addView(tvMeta)
                container.addView(block)
            }
        } catch (e: Exception) {
            FileLogger.e(this, "MessagesActivity", "refreshMessages failed: ${e.message}")
        }
    }
}
