package com.twistedphone.home
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.GridView
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.twistedphone.R
import com.twistedphone.TwistedApp
import com.twistedphone.browser.WebViewActivity
import com.twistedphone.camera.CameraActivity
import com.twistedphone.gallery.GalleryActivity
import com.twistedphone.messages.MessagesActivity
import com.twistedphone.messages.WhosAppActivity
import com.twistedphone.settings.SettingsActivity
import java.util.*

class FakeHomeActivity : AppCompatActivity() {
    private lateinit var clock: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val rnd = Random()
    private val prefs = TwistedApp.instance.settingsPrefs
    private val tick = object : Runnable {
        override fun run() {
            val now = Calendar.getInstance()
            now.add(Calendar.SECOND, rnd.nextInt(3)-1)
            clock.text = String.format("%02d:%02d", now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))
            handler.postDelayed(this, 1000)
        }
    }
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_fake_home)
        clock = findViewById(R.id.jitterClock)
        val grid = findViewById<GridView>(R.id.appGrid)
        val apps = mutableListOf(
            Pair("Browser", Intent(this, WebViewActivity::class.java)),
            Pair("Camera", Intent(this, CameraActivity::class.java)),
            Pair("WhosApp", Intent(this, WhosAppActivity::class.java)),
            Pair("Messages", Intent(this, MessagesActivity::class.java)),
            Pair("Gallery", Intent(this, GalleryActivity::class.java)),
            Pair("Settings", Intent(this, SettingsActivity::class.java))
        )
        grid.numColumns = 3
        grid.adapter = AppAdapter(this, apps)
        grid.setOnItemClickListener { _, _, pos, _ -> 
            val appName = apps[pos].first
            if (isAppUnlocked(appName)) {
                startActivity(apps[pos].second)
            } else {
                android.widget.Toast.makeText(this, "$appName is locked. Wait for unlock.", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() } // simulated back
        findViewById<ImageButton>(R.id.btnHome).setOnClickListener { /* stay home */ }
        findViewById<ImageButton>(R.id.btnRecent).setOnClickListener { /* show recent, placeholder */ }
    }
    private fun isAppUnlocked(app: String): Boolean {
        return prefs.getBoolean("unlock_$app", if(app == "Browser") true else false) // browser always unlocked
    }
    override fun onResume() { super.onResume(); handler.post(tick) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(tick) }
}
