package com.twistedphone.messages

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.twistedphone.R
import com.twistedphone.TwistedApp
import com.twistedphone.util.FileLogger

class WhosAppActivity : AppCompatActivity() {
    private val prefs = TwistedApp.instance.securePrefs

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_whosapp)

        try {
            val container = findViewById<LinearLayout>(R.id.chatContainer)
            container.removeAllViews()

            val msgs = MessageStore.allMessages(this)
            for (m in msgs) {
                val parts = m.split("|", limit = 3)
                val who = parts.getOrNull(0) ?: ""
                val text = parts.getOrNull(2) ?: parts.getOrNull(1) ?: ""
                val isUser = who == "YOU"

                val wrapper = LinearLayout(this)
                val lpWrap = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lpWrap.setMargins(6, 6, 6, 6)
                wrapper.layoutParams = lpWrap
                wrapper.gravity = if (isUser) Gravity.START else Gravity.END

                // optional avatar for ALT
                if (!isUser) {
                    val avB64 = prefs.getString("as_selfie_b64", "") ?: ""
                    if (avB64.isNotBlank()) {
                        try {
                            val bytes = Base64.decode(avB64, Base64.NO_WRAP)
                            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            val iv = ImageView(this)
                            iv.setImageBitmap(bmp)
                            val ivlp = LinearLayout.LayoutParams(48, 48)
                            ivlp.setMargins(4,4,8,4)
                            iv.layoutParams = ivlp
                            wrapper.addView(iv)
                        } catch (e: Exception) {
                            FileLogger.e(this, "WhosAppActivity", "avatar decode failed: ${e.message}")
                        }
                    }
                }

                val bubble = TextView(this)
                bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                bubble.text = text
                bubble.setPadding(16, 10, 16, 10)
                if (isUser) bubble.setBackgroundResource(R.drawable.bubble_left) else bubble.setBackgroundResource(R.drawable.bubble_right)
                wrapper.addView(bubble)
                container.addView(wrapper)
            }
        } catch (e: Exception) {
            FileLogger.e(this, "WhosAppActivity", "onCreate failed: ${e.message}")
        }
    }
}
