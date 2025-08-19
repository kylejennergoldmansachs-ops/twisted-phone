package com.twistedphone.messages
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.twistedphone.R
import com.twistedphone.alt.AltMessageService

class MessagesActivity : AppCompatActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_messages)
        val container = findViewById<LinearLayout>(R.id.messagesContainer)
        val msgs = MessageStore.allMessages(this)
        for (m in msgs) {
            val parts = m.split("|", limit = 3)
            val who = parts[0]; val text = parts.getOrNull(2) ?: ""
            val tv = TextView(this)
            tv.text = if (who == "YOU") "You: $text" else "ALT: $text"
            tv.gravity = if (who == "YOU") android.view.Gravity.START else android.view.Gravity.END
            container.addView(tv)
        }
        val send = findViewById<Button>(R.id.sendBtn); val input = findViewById<EditText>(R.id.replyInput)
        send.setOnClickListener {
            val t = input.text.toString().trim()
            if (t.isNotEmpty()) {
                MessageStore.addMessage(this, "YOU", t)
                input.text.clear()
                // refresh UI
                finish()
                startActivity(Intent(this, MessagesActivity::class.java))
                // trigger AS response after delay
                Handler(Looper.getMainLooper()).postDelayed({
                    startService(Intent(this, AltMessageService::class.java).putExtra("is_reply", true))
                }, 5000) // 5s delay
            }
        }
    }
}
