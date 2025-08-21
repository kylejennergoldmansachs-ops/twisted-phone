package com.twistedphone.messages

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.twistedphone.R
import com.twistedphone.alt.AltMessageService
import com.twistedphone.util.FileLogger

class MessagesActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_messages)

        container = findViewById(R.id.messagesContainer)
        refreshMessages()

        val sendBtn = findViewById<Button>(R.id.sendBtn)
        val input = findViewById<EditText>(R.id.replyInput)
        sendBtn.setOnClickListener {
            val t = input.text.toString().trim()
            if (t.isNotEmpty()) {
                try {
                    MessageStore.addMessage(this, "YOU", t)
                } catch (e: Exception) {
                    FileLogger.e(this, "MessagesActivity", "addMessage failed: ${e.message}")
                    return@setOnClickListener
                }
                input.text.clear()
                refreshMessages()
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        val svcIntent = android.content.Intent(this, AltMessageService::class.java)
                        svcIntent.putExtra("is_reply", true)
                        startService(svcIntent)
                    } catch (e: Exception) { FileLogger.e(this,"MessagesActivity","start alt failed: ${e.message}") }
                }, 5000)
            }
        }
    }

    private fun bubbleView(text: String, isUser: Boolean): LinearLayout {
        val wrapper = LinearLayout(this)
        val lpWrap = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lpWrap.setMargins(8, 8, 8, 8)
        wrapper.layoutParams = lpWrap
        wrapper.gravity = if (isUser) Gravity.START else Gravity.END

        val bubble = TextView(this)
        bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        bubble.text = text
        bubble.setTextColor(Color.WHITE)
        bubble.setPadding(18, 12, 18, 12)
        val bubbleLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        if (isUser) {
            bubble.setBackgroundResource(R.drawable.bubble_left)
            bubbleLp.gravity = Gravity.START
        } else {
            bubble.setBackgroundResource(R.drawable.bubble_right)
            bubbleLp.gravity = Gravity.END
        }
        bubble.layoutParams = bubbleLp
        wrapper.addView(bubble)
        return wrapper
    }

    private fun refreshMessages() {
        try {
            container.removeAllViews()
            val msgs = MessageStore.allMessages(this)
            for (m in msgs) {
                val parts = m.split("|", limit = 3)
                val who = parts.getOrNull(0) ?: ""
                val ts = parts.getOrNull(1) ?: ""
                val text = parts.getOrNull(2) ?: ""
                val isUser = who == "YOU"
                container.addView(bubbleView(text, isUser))
            }
        } catch (e: Exception) {
            FileLogger.e(this, "MessagesActivity", "refreshMessages failed: ${e.message}")
        }
    }
}
