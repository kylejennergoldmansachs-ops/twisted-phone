package com.twistedphone.messages
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
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
    private var isTyping = false // set true when service starts generating

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_whosapp)
        val container = findViewById<LinearLayout>(R.id.chatContainer)
        val indicator = findViewById<TextView>(R.id.typingIndicator)
        val msgs = MessageStore.allMessages(this)
        for (m in msgs) {
            val parts = m.split("|", limit = 3)
            val who = parts[0]; val text = parts.getOrNull(2) ?: ""
            val ll = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = if (who == "ALT") android.view.Gravity.END else android.view.Gravity.START }
            val av = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(40, 40); setImageBitmap(getAvatar(who)) }
            val tv = TextView(this).apply { this.text = text; setPadding(8, 8, 8, 8) }
            ll.addView(if (who == "ALT") tv else av)
            ll.addView(if (who == "ALT") av else tv)
            container.addView(ll)
        }
        // Simulate typing if generating (in real, listen for broadcast from service)
        if (isTyping) {
            indicator.text = typingIndicator
            indicator.visibility = View.VISIBLE
            handler.postDelayed({ indicator.visibility = View.GONE }, 3000) // fake duration
        }
    }
    private fun getAvatar(who: String): android.graphics.Bitmap? {
        val b64 = prefs.getString(if (who == "ALT") "as_selfie_b64" else "selfie_b64", "") ?: return null
        val bytes = Base64.decode(b64, Base64.NO_WRAP)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
}
