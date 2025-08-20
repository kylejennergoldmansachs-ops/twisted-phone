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

class MessagesActivity : AppCompatActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_messages)
        val container = findViewById<LinearLayout>(R.id.messagesContainer)
        val msgs = MessageStore.allMessages(this)
        for (m in msgs) {
            val parts = m.split("|", limit = 3)
            val who = parts[0]
            val meta = parts.getOrNull(1) ?: ""
            val text = parts.getOrNull(2) ?: ""

            val block = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.setMargins(6, 6, 6, 6)
                layoutParams = lp
            }

            val tvMain = TextView(this).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                text = text
                gravity = Gravity.START
            }
            val tvMeta = TextView(this).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                text = meta
                gravity = Gravity.END
            }

            block.addView(tvMain)
            block.addView(tvMeta)
            container.addView(block)
        }

        val send = findViewById<Button>(R.id.sendBtn)
        val input = findViewById<EditText>(R.id.replyInput)
        send.setOnClickListener {
            val t = input.text.toString().trim()
            if (t.isNotEmpty()) {
                MessageStore.addMessage(this, "YOU", t)
                input.text.clear()
                // refresh UI quickly
                finish()
                startActivity(Intent(this, MessagesActivity::class.java))
                // trigger AS response after short delay
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        startService(Intent(this, AltMessageService::class.java).putExtra("is_reply", true))
                    } catch (e: Exception) {
                        FileLogger.e(this, "MessagesActivity", "start alt service failed: ${e.message}")
                    }
                }, 5000)
            }
        }
    }
}
