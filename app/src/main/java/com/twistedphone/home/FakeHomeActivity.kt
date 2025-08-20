package com.twistedphone.home

import com.twistedphone.util.FileLogger
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
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
import androidx.appcompat.app.AlertDialog
import android.widget.ScrollView

class FakeHomeActivity : AppCompatActivity() {
    private lateinit var clock: TextView
    private lateinit var grid: GridView
    private lateinit var backgroundImage: ImageView
    private lateinit var dock: View
    private lateinit var statusBar: View
    private val handler = Handler(Looper.getMainLooper())
    private val rnd = Random()
    private val prefs = TwistedApp.instance.settingsPrefs
    private var originalTime: String = ""
    private var jitteredTime: String = ""
    private var jitterIndex: Int = -1

    private val tick = object : Runnable {
        override fun run() {
            val now = Calendar.getInstance()
            val currentTime = String.format("%02d:%02d", now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))

            if (System.currentTimeMillis() % 2000 < 100) {
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

            handler.postDelayed(this, 100)
        }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_fake_home)

        // Views
        clock = findViewById(R.id.jitterClock)
        grid = findViewById(R.id.appGrid)
        backgroundImage = findViewById(R.id.backgroundImage)
        dock = findViewById(R.id.dock)
        statusBar = findViewById(R.id.statusBar)

        // Ensure background image
        try {
            val bgDrawable = AppCompatResources.getDrawable(this, R.drawable.background)
            backgroundImage.setImageDrawable(bgDrawable)
            FileLogger.d(this, "FakeHomeActivity", "Background image set")
        } catch (e: Exception) {
            FileLogger.e(this, "FakeHomeActivity", "Failed to set background: ${e.message}")
        }

        // App list
        val apps = listOf(
            AppInfo("Browser", Intent(this, WebViewActivity::class.java), R.drawable.ic_browser),
            AppInfo("Camera", Intent(this, CameraActivity::class.java), R.drawable.ic_camera),
            AppInfo("WhosApp", Intent(this, WhosAppActivity::class.java), R.drawable.ic_whosapp),
            AppInfo("Messages", Intent(this, MessagesActivity::class.java), R.drawable.ic_messages),
            AppInfo("Gallery", Intent(this, GalleryActivity::class.java), R.drawable.ic_gallery),
            AppInfo("Settings", Intent(this, SettingsActivity::class.java), R.drawable.ic_settings)
        )

        grid.numColumns = 3
        val adapter = AppAdapter(this, apps)
        grid.adapter = adapter
        (grid.adapter as? BaseAdapter)?.notifyDataSetChanged()
        grid.visibility = View.VISIBLE
        grid.isClickable = true
        grid.isFocusable = true

        // Keep overlays visible above background
        grid.bringToFront()
        dock.bringToFront()
        statusBar.bringToFront()
        grid.elevation = 20f
        dock.elevation = 25f
        statusBar.elevation = 30f

        // Debug logs
        FileLogger.d(this, "FakeHomeActivity", "Number of apps: ${apps.size}")
        FileLogger.d(this, "FakeHomeActivity", "Grid adapter set: ${grid.adapter != null}")
        FileLogger.d(this, "FakeHomeActivity", "Grid adapter count: ${(grid.adapter as? BaseAdapter)?.count}")

        grid.setOnItemClickListener { _, _, pos, _ ->
            val appName = apps[pos].name
            if (isAppUnlocked(appName)) {
                startActivity(apps[pos].intent)
                FileLogger.d(this, "FakeHomeActivity", "Launching app: $appName")
            } else {
                Toast.makeText(this, "$appName is locked. Wait for unlock.", Toast.LENGTH_SHORT).show()
                FileLogger.d(this, "FakeHomeActivity", "Blocked launch (locked): $appName")
            }
        }

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            FileLogger.d(this, "FakeHomeActivity", "Back button clicked")
            finish()
        }
        val homeBtn = findViewById<ImageButton>(R.id.btnHome)
        homeBtn.setOnClickListener {
            FileLogger.d(this, "FakeHomeActivity", "Home button clicked")
        }
        // Long press home = show logs
        homeBtn.setOnLongClickListener {
            showLogDialog()
            true
        }
        findViewById<ImageButton>(R.id.btnRecent).setOnClickListener {
            FileLogger.d(this, "FakeHomeActivity", "Recent button clicked")
        }
    }

    private fun isAppUnlocked(app: String): Boolean {
        return prefs.getBoolean("unlock_$app", app == "Browser")
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
                val send = Intent(Intent.ACTION_SEND)
                send.type = "text/plain"
                send.putExtra(Intent.EXTRA_SUBJECT, "TwistedPhone logs")
                send.putExtra(Intent.EXTRA_TEXT, logs)
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

            appIconView.isFocusable = false
            appIconView.isFocusableInTouchMode = false
            appNameView.isFocusable = false
            appNameView.isFocusableInTouchMode = false

            appNameView.text = app.name
            try {
                val drawable = AppCompatResources.getDrawable(parent.context, app.iconRes)
                appIconView.setImageDrawable(drawable)
                FileLogger.d(parent.context, "AppAdapter",
                    "getView pos=$position name=${app.name} iconRes=${app.iconRes} drawable=${drawable != null}")
            } catch (e: Exception) {
                FileLogger.e(parent.context, "AppAdapter", "Failed to set icon for ${app.name}: ${e.message}")
            }

            return view
        }
    }
}
