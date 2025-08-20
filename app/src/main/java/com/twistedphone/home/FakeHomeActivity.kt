package com.twistedphone.home

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import com.twistedphone.R
import com.twistedphone.TwistedApp
import com.twistedphone.alt.AltMessageService
import com.twistedphone.browser.WebViewActivity
import com.twistedphone.camera.CameraActivity
import com.twistedphone.gallery.GalleryActivity
import com.twistedphone.messages.WhosAppActivity
import com.twistedphone.settings.SettingsActivity
import com.twistedphone.util.FileLogger
import java.util.*

class FakeHomeActivity : AppCompatActivity() {
    private lateinit var clock: TextView
    private lateinit var grid: GridView
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = TwistedApp.instance.settingsPrefs
    private val securePrefs = TwistedApp.instance.securePrefs

    // Simple jitter effect ticker
    private val rnd = Random()
    private val ticker = object : Runnable {
        override fun run() {
            try {
                val now = Calendar.getInstance()
                val h = now.get(Calendar.HOUR_OF_DAY)
                val m = now.get(Calendar.MINUTE)
                val s = String.format(Locale.US, "%02d:%02d", h, m)
                // occasionally corrupt a digit for jitter
                if (System.currentTimeMillis() % 2000 < 200) {
                    val ci = listOf(0,1,3,4).random()
                    val chs = s.toCharArray()
                    chs[ci] = ('0' + rnd.nextInt(10))
                    clock.text = String(chs)
                } else {
                    clock.text = s
                }
            } catch (e: Exception) {
                FileLogger.e(this@FakeHomeActivity, "Ticker", "ticker error: ${e.message}")
            } finally {
                handler.postDelayed(this, 200)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fake_home)

        clock = findViewById(R.id.jitterClock)
        grid = findViewById(R.id.appGrid)

        try {
            findViewById<ImageView>(R.id.backgroundImage).setImageDrawable(
                AppCompatResources.getDrawable(this, R.drawable.background)
            )
            FileLogger.d(this, "FakeHomeActivity", "Background image set")
        } catch (e: Exception) {
            FileLogger.e(this, "FakeHomeActivity", "Background image set failed: ${e.message}")
        }

        // set install time if missing
        if (securePrefs.getLong("install_time", 0L) == 0L) securePrefs.edit().putLong("install_time", System.currentTimeMillis()).apply()

        // Build the home grid — NOTE: Messages intentionally NOT listed here (accessible only from notifications)
        val apps = listOf(
            AppInfo("Browser", Intent(this, WebViewActivity::class.java), R.drawable.ic_browser),
            AppInfo("Camera", Intent(this, CameraActivity::class.java), R.drawable.ic_camera),
            AppInfo("WhosApp", Intent(this, WhosAppActivity::class.java), R.drawable.ic_whosapp),
            AppInfo("Gallery", Intent(this, GalleryActivity::class.java), R.drawable.ic_gallery),
            AppInfo("Settings", Intent(this, SettingsActivity::class.java), R.drawable.ic_settings)
        )

        grid.numColumns = 3
        grid.adapter = AppAdapter(this, apps)

        grid.setOnItemClickListener { _, _, pos, _ ->
            val app = apps[pos]
            try {
                startActivity(app.intent)
                FileLogger.d(this, "FakeHomeActivity", "Launching app: ${app.name}")
            } catch (t: Throwable) {
                FileLogger.e(this, "FakeHomeActivity", "Failed to launch ${app.name}: ${t.message}")
                Toast.makeText(this, "Unable to open ${app.name}", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnHome).setOnClickListener { /* no-op */ }
        findViewById<ImageButton>(R.id.btnRecent).setOnClickListener { /* no-op */ }
        findViewById<ImageButton>(R.id.btnHome).setOnLongClickListener {
            showLogDialog(); true
        }

        // Layout refresh shortly after start
        handler.postDelayed({ grid.invalidate(); grid.requestLayout(); FileLogger.d(this,"FakeHomeActivity","Grid refresh") }, 120)

        // Schedule Alternative Self message at a random time within 3 minutes AFTER home opens (do not count "startup" duration)
        val firstHomeSeenKey = "first_home_seen"
        if (!prefs.getBoolean(firstHomeSeenKey, false)) {
            prefs.edit().putBoolean(firstHomeSeenKey, true).apply()
            val delayMs = (0L..(3 * 60 * 1000L)).random()
            FileLogger.d(this, "FakeHomeActivity", "Scheduling AS message in ${delayMs}ms from home open (random within 3min)")
            handler.postDelayed({
                try {
                    startService(Intent(this, AltMessageService::class.java))
                    FileLogger.d(this, "FakeHomeActivity", "Started AltMessageService (scheduled)")
                } catch (e: Exception) {
                    FileLogger.e(this, "FakeHomeActivity", "Start AltMessageService failed: ${e.message}")
                }
            }, delayMs)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    private fun showLogDialog() {
        val logs = FileLogger.readAll(this)
        val tv = TextView(this).apply { text = logs; setTextIsSelectable(true); setPadding(12,12,12,12) }
        val scroll = ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this).setTitle("Logs").setView(scroll)
            .setPositiveButton("Share") { _, _ ->
                val i = android.content.Intent(android.content.Intent.ACTION_SEND)
                i.type = "text/plain"
                i.putExtra(android.content.Intent.EXTRA_TEXT, logs)
                startActivity(android.content.Intent.createChooser(i, "Share logs"))
            }
            .setNegativeButton("Close", null)
            .show()
    }

    data class AppInfo(val name: String, val intent: Intent, val iconRes: Int)

    private class AppAdapter(private val ctx: FakeHomeActivity, private val apps: List<AppInfo>) : BaseAdapter() {
        private val inflater = ctx.layoutInflater
        override fun getCount(): Int = apps.size
        override fun getItem(position: Int): Any = apps[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
            val view = convertView ?: inflater.inflate(R.layout.item_app, parent, false)
            val app = apps[position]
            val name = view.findViewById<TextView>(R.id.appName)
            val icon = view.findViewById<android.widget.ImageView>(R.id.appIcon)
            name.text = app.name
            try { icon.setImageDrawable(AppCompatResources.getDrawable(ctx, app.iconRes)) } catch (_: Exception) {}
            return view
        }
    }
}
