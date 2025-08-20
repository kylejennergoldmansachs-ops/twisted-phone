package com.twistedphone.messages

import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.twistedphone.R
import com.twistedphone.TwistedApp

class WhosAppActivity : AppCompatActivity() {
    private val prefs = TwistedApp.instance.securePrefs
    private val typingIndicator = "Typing..."
    private val handler = Handler(Looper.getMainLooper())
    private var isTyping = false

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_whosapp)
        val container = findViewById<LinearLayout>(R.id.chatContainer)
        val indicator = findViewById<TextView>(R.id.typingIndicator)
        val msgs = MessageStore.allMessages(this)

        for (m in msgs) {
            val parts = m.split("|", limit = 3)
            val who = parts[0]
            val meta = parts.getOrNull(1) ?: ""
            val text = parts.getOrNull(2) ?: ""

            val block = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.setMargins(6,6,6,6)
                layoutParams = lp
            }

            // optional avatar (small) - display above the message if available
            val avB64 = if (who == "ALT") prefs.getString("as_selfie_b64", "") else prefs.getString("selfie_b64", "")
            if (!avB64.isNullOrBlank()) {
                try {
                    val bytes = Base64.decode(avB64, Base64.NO_WRAP)
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    val iv = ImageView(this).apply {
                        setImageBitmap(bmp)
                        val lp = LinearLayout.LayoutParams(48, 48)
                        lp.gravity = Gravity.START
                        layoutParams = lp
                    }
                    block.addView(iv)
                } catch (_: Exception) { /* ignore avatar decode errors */ }
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

        if (isTyping) {
            indicator.text = typingIndicator
            indicator.visibility = View.VISIBLE
            handler.postDelayed({ indicator.visibility = View.GONE }, 3000)
        }
    }

    private fun getAvatar(who: String): android.graphics.Bitmap? {
        val b64 = prefs.getString(if (who == "ALT") "as_selfie_b64" else "selfie_b64", "") ?: return null
        val bytes = Base64.decode(b64, Base64.NO_WRAP)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
}
