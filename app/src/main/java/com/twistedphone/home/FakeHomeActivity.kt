package com.twistedphone.home

import com.twistedphone.util.FileLogger
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import com.twistedphone.R
import com.twistedphone.TwistedApp
import com.twistedphone.browser.WebViewActivity
import com.twistedphone.camera.CameraActivity
import com.twistedphone.gallery.GalleryActivity
import com.twistedphone.messages.MessagesActivity
import com.twistedphone.messages.WhosAppActivity
import com.twistedphone.settings.SettingsActivity
import java.util.Calendar
import java.util.Random
import android.os.Handler
import android.os.Looper
import com.twistedphone.alt.AltMessageService

class FakeHomeActivity : AppCompatActivity() {
    private lateinit var clock: TextView
    private lateinit var grid: GridView
    private lateinit var backgroundImage: ImageView
    private lateinit var dock: LinearLayout
    private lateinit var statusBar: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val rnd = Random()
    private val prefs = TwistedApp.instance.settingsPrefs
    private val securePrefs = TwistedApp.instance.securePrefs
    private var originalTime: String = ""
    private var jitteredTime: String = ""
    private var jitterIndex: Int = -1

    // Jitter tick
    private val tick = object : Runnable {
        override fun run() {
            val now = Calendar.getInstance()
            val currentTime = String.format("%02d:%02d", now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))

            if (System.currentTimeMillis() % 2000 < 200) {
                originalTime = currentTime
                val digitPositions = intArrayOf(0, 1, 3, 4)
                jitterIndex = digitPositions[rnd.nextInt(digitPositions.size)]
                val chars = currentTime.toCharArray()
                chars[jitterIndex] = (rnd.nextInt(10) + '0'.code).toChar()
                jitteredTime = String(chars)
                clock.text = jitteredTime
            } else {
                clock.text = originalTime.ifEmpty { currentTime }
            }

            handler.postDelayed(this, 200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fake_home)

        // views
        clock = findViewById(R.id.jitterClock)
        grid = findViewById(R.id.appGrid)
        backgroundImage = findViewById(R.id.backgroundImage)
        dock = findViewById(R.id.dock)
        statusBar = findViewById(R.id.statusBar)

        // background
        try {
            val bgDrawable = AppCompatResources.getDrawable(this, R.drawable.background)
            backgroundImage.setImageDrawable(bgDrawable)
            FileLogger.d(this, "FakeHomeActivity", "Background image set")
        } catch (e: Exception) {
            FileLogger.e(this, "FakeHomeActivity", "Failed to set background: ${e.message}")
        }

        // set install_time if missing
        if (securePrefs.getLong("install_time", 0L) == 0L) {
            securePrefs.edit().putLong("install_time", System.currentTimeMillis()).apply()
        }

        // App list: NOTE - Messages intentionally removed from home grid.
        val apps = listOf(
            AppInfo("Browser", Intent(this, WebViewActivity::class.java), R.drawable.ic_browser),
            AppInfo("Camera", Intent(this, CameraActivity::class.java), R.drawable.ic_camera),
            AppInfo("WhosApp", Intent(this, WhosAppActivity::class.java), R.drawable.ic_whosapp),
            // Messages intentionally not present on home (only via ALT notifications)
            AppInfo("Gallery", Intent(this, GalleryActivity::class.java), R.drawable.ic_gallery),
            AppInfo("Settings", Intent(this, SettingsActivity::class.java), R.drawable.ic_settings)
        )

        val adapter = AppAdapter(this, apps)
        grid.adapter = adapter
        grid.numColumns = 3

        FileLogger.d(this, "FakeHomeActivity", "Number of apps: ${apps.size}")
        FileLogger.d(this, "FakeHomeActivity", "Grid adapter set: ${grid.adapter != null}")

        // Click handling - apps are available from the start (no locking)
        grid.setOnItemClickListener { _, _, pos, _ ->
            val appName = apps[pos].name
            // All apps unlocked by design
            try {
                FileLogger.d(this, "FakeHomeActivity", "Attempting to launch app: $appName")
                startActivity(apps[pos].intent)
                FileLogger.d(this, "FakeHomeActivity", "Launching app: $appName")
            } catch (t: Throwable) {
                FileLogger.e(this, "FakeHomeActivity", "Failed to launch $appName: ${t.message}")
                Toast.makeText(this, "Unable to open $appName", Toast.LENGTH_SHORT).show()
            }
        }

        // Dock buttons
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        val homeBtn = findViewById<ImageButton>(R.id.btnHome)
        homeBtn.setOnClickListener { /* no-op */ }
        homeBtn.setOnLongClickListener {
            showLogDialog()
            true
        }
        findViewById<ImageButton>(R.id.btnRecent).setOnClickListener { /* no-op */ }

        // Refresh layout once shortly after creation
        handler.postDelayed({
            grid.invalidate()
            grid.requestLayout()
            FileLogger.d(this, "FakeHomeActivity", "Forced GridView layout refresh")
        }, 150L)

        // First-time home open -> schedule Alternative Self message at a random delay within 3 minutes (doesn't count startup)
        val firstKey = "first_home_seen"
        if (!prefs.getBoolean(firstKey, false)) {
            prefs.edit().putBoolean(firstKey, true).apply()
            val delayMs = (0L..(3 * 60 * 1000L)).random()
            FileLogger.d(this, "FakeHomeActivity", "Scheduling AS message in ${delayMs}ms from home open (random within 3min)")
            handler.postDelayed({
                try {
                    startService(Intent(this, AltMessageService::class.java))
                    FileLogger.d(this, "FakeHomeActivity", "Started AltMessageService (scheduled)")
                } catch (e: Exception) {
                    FileLogger.e(this, "FakeHomeActivity", "Failed to start AltMessageService: ${e.message}")
                }
            }, delayMs)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
        FileLogger.d(this, "FakeHomeActivity", "Activity resumed")
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
        FileLogger.d(this, "FakeHomeActivity", "Activity paused")
    }

    private fun showLogDialog() {
        val logs = FileLogger.readAll(this)
        val tv = TextView(this)
        tv.text = logs
        tv.setTextIsSelectable(true)
        tv.setPadding(20, 20, 20, 20)

        val scroll = ScrollView(this)
        scroll.addView(tv)

        AlertDialog.Builder(this)
            .setTitle("TwistedPhone logs")
            .setView(scroll)
            .setPositiveButton("Share") { _, _ ->
                val send = Intent(android.content.Intent.ACTION_SEND)
                send.type = "text/plain"
                send.putExtra(android.content.Intent.EXTRA_SUBJECT, "TwistedPhone logs")
                send.putExtra(android.content.Intent.EXTRA_TEXT, logs)
                startActivity(Intent.createChooser(send, "Share logs"))
            }
            .setNegativeButton("Close", null)
            .show()
    }

    data class AppInfo(val name: String, val intent: Intent, val iconRes: Int)

    class AppAdapter(context: Context, private val apps: List<AppInfo>) : BaseAdapter() {
        private val inflater = LayoutInflater.from(context)

        override fun getCount(): Int = apps.size
        override fun getItem(position: Int): Any = apps[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: inflater.inflate(R.layout.item_app, parent, false)
            val app = apps[position]

            val appNameView = view.findViewById<TextView>(R.id.appName)
            val appIconView = view.findViewById<ImageView>(R.id.appIcon)

            appNameView.text = app.name
            try {
                val drawable = AppCompatResources.getDrawable(parent.context, app.iconRes)
                appIconView.setImageDrawable(drawable)
            } catch (e: Exception) {
                FileLogger.e(parent.context, "AppAdapter", "Failed to set icon for ${app.name}: ${e.message}")
            }

            view.setOnClickListener {
                try {
                    (parent as? AdapterView<*>)?.performItemClick(it, position, getItemId(position))
                } catch (t: Throwable) {
                    try {
                        parent.context.startActivity(app.intent)
                    } catch (_: Throwable) { /* ignore */ }
                }
            }

            view.isClickable = true
            view.isFocusable = true

            return view
        }
    }
}
