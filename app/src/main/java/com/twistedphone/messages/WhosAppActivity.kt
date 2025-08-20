package com.twistedphone.messages

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.twistedphone.R
import com.twistedphone.TwistedApp
import com.twistedphone.util.FileLogger

/**
 * WhosAppActivity
 * - Shows chat messages stacked vertically (consistent single-column layout)
 * - Avoids reassigning vals; safe parsing of MessageStore entries
 */
class WhosAppActivity : AppCompatActivity() {
    private val prefs = TwistedApp.instance.securePrefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_whosapp)

        try {
            val container = findViewById<LinearLayout>(R.id.chatContainer)
            container.removeAllViews()

            val msgs = MessageStore.allMessages(this)
            for (m in msgs) {
                val parts = m.split("|", limit = 3)
                val who = parts.getOrNull(0) ?: ""
                val meta = parts.getOrNull(1) ?: ""
                val text = parts.getOrNull(2) ?: if (parts.size >= 2) parts[1] else parts.getOrNull(0) ?: ""

                val block = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    lp.setMargins(6, 6, 6, 6)
                    layoutParams = lp
                }

                // avatar if available
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
                    } catch (e: Exception) {
                        FileLogger.e(this, "WhosAppActivity", "avatar decode failed: ${e.message}")
                    }
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
            FileLogger.e(this, "WhosAppActivity", "onCreate failed: ${e.message}")
        }
    }
}
