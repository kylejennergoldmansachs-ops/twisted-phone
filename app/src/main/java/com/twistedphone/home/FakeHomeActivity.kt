package com.twistedphone.home

import com.twistedphone.util.FileLogger
import android.content.Intent
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
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

class FakeHomeActivity : AppCompatActivity() {
    private lateinit var clock: TextView
    private lateinit var grid: GridView
    private lateinit var backgroundImage: ImageView
    private lateinit var dock: View
    private lateinit var statusBar: View
    private lateinit var rootLayout: RelativeLayout
    private val handler = Handler(Looper.getMainLooper())
    private val rnd = Random()
    private val prefs = TwistedApp.instance.settingsPrefs
    private var originalTime: String = ""
    private var jitteredTime: String = ""
    private var jitterIndex: Int = -1

    // Fallback overlay reference so we only create it once
    private var fallbackOverlay: GridLayout? = null

    // Jitter tick (slower to reduce layout churn)
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

        // --- views ---
        clock = findViewById(R.id.jitterClock)
        grid = findViewById(R.id.appGrid)
        backgroundImage = findViewById(R.id.backgroundImage)
        dock = findViewById(R.id.dock)
        statusBar = findViewById(R.id.statusBar)
        rootLayout = findViewById(R.id.rootLayout)

        // Ensure background image is set safely (vectors supported)
        try {
            val bgDrawable = AppCompatResources.getDrawable(this, R.drawable.background)
            backgroundImage.setImageDrawable(bgDrawable)
            FileLogger.d(this, "FakeHomeActivity", "Background image set")
        } catch (e: Exception) {
            FileLogger.e(this, "FakeHomeActivity", "Failed to set background: ${e.message}")
        }

        // --- apps list ---
        val apps = listOf(
            AppInfo("Browser", Intent(this, WebViewActivity::class.java), R.drawable.ic_browser),
            AppInfo("Camera", Intent(this, CameraActivity::class.java), R.drawable.ic_camera),
            AppInfo("WhosApp", Intent(this, WhosAppActivity::class.java), R.drawable.ic_whosapp),
            AppInfo("Messages", Intent(this, MessagesActivity::class.java), R.drawable.ic_messages),
            AppInfo("Gallery", Intent(this, GalleryActivity::class.java), R.drawable.ic_gallery),
            AppInfo("Settings", Intent(this, SettingsActivity::class.java), R.drawable.ic_settings)
        )

        // --- GridView adapter + visibility hints ---
        grid.numColumns = 3
        val adapter = AppAdapter(this, apps)
        grid.adapter = adapter
        (grid.adapter as? BaseAdapter)?.notifyDataSetChanged()
        grid.visibility = View.VISIBLE
        grid.isClickable = true
        grid.isFocusable = true

        // Make sure overlays are above the background
        grid.bringToFront()
        dock.bringToFront()
        statusBar.bringToFront()
        grid.elevation = 20f
        dock.elevation = 25f
        statusBar.elevation = 30f
        grid.invalidate()

        // Logging
        FileLogger.d(this, "FakeHomeActivity", "Number of apps: ${apps.size}")
        FileLogger.d(this, "FakeHomeActivity", "Grid adapter set: ${grid.adapter != null}")
        FileLogger.d(this, "FakeHomeActivity", "Grid adapter count: ${(grid.adapter as? BaseAdapter)?.count}")

        // --- GridView click handling ---
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

        // --- Dock buttons (with long-press to show logs) ---
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            FileLogger.d(this, "FakeHomeActivity", "Back button clicked")
            finish()
        }
        val homeBtn = findViewById<ImageButton>(R.id.btnHome)
        homeBtn.setOnClickListener {
            FileLogger.d(this, "FakeHomeActivity", "Home button clicked")
        }
        homeBtn.setOnLongClickListener {
            showLogDialog()
            true
        }
        findViewById<ImageButton>(R.id.btnRecent).setOnClickListener {
            FileLogger.d(this, "FakeHomeActivity", "Recent button clicked")
        }

        // --- After a short delay confirm visible children; if none visible, create fallback overlay ---
        handler.postDelayed({
            try {
                val visibleChild = checkGridHasVisibleChild()
                FileLogger.d(this, "FakeHomeActivity", "Grid visibleChild=$visibleChild childCount=${grid.childCount}")
                if (!visibleChild) {
                    createFallbackOverlay(apps)
                } else {
                    FileLogger.d(this, "FakeHomeActivity", "Grid appears to render children normally")
                }
            } catch (e: Exception) {
                FileLogger.e(this, "FakeHomeActivity", "Error checking grid: ${e.message}")
            }
        }, 700L)
    }

    // --- Robust visibility check: returns true if at least one grid child is actually visible to the user ---
    private fun checkGridHasVisibleChild(): Boolean {
        val count = grid.childCount
        FileLogger.d(this, "FakeHomeActivity", "Checking grid children: count=$count")
        for (i in 0 until count) {
            val child = grid.getChildAt(i) ?: continue

            // Basic checks
            if (child.visibility != View.VISIBLE) {
                FileLogger.d(this, "FakeHomeActivity", "child[$i] not VISIBLE")
                continue
            }
            if (!child.isShown) {
                FileLogger.d(this, "FakeHomeActivity", "child[$i] !isShown")
                continue
            }
            if (child.width <= 0 || child.height <= 0) {
                FileLogger.d(this, "FakeHomeActivity", "child[$i] has zero size (${child.width}x${child.height})")
                continue
            }

            // Compute visible rect in global coords
            val visibleRect = Rect()
            val hasVisible = child.getGlobalVisibleRect(visibleRect)
            if (!hasVisible) {
                FileLogger.d(this, "FakeHomeActivity", "child[$i] getGlobalVisibleRect returned false")
                continue
            }

            val visibleArea = visibleRect.width().coerceAtLeast(0) * visibleRect.height().coerceAtLeast(0)
            val totalArea = child.width * child.height
            val visiblePct = if (totalArea > 0) (visibleArea * 100) / totalArea else 0

            FileLogger.d(this, "FakeHomeActivity",
                "child[$i] bounds=${child.width}x${child.height} visibleRect=${visibleRect} visibleArea=$visibleArea totalArea=$totalArea visiblePct=$visiblePct")

            // require at least 30% visible (tweakable)
            val MIN_VISIBLE_PCT = 30
            if (visiblePct < MIN_VISIBLE_PCT) {
                FileLogger.d(this, "FakeHomeActivity", "child[$i] visiblePct<$MIN_VISIBLE_PCT -> rejected")
                continue
            }

            // Check occlusion by other root children (sibling overlays)
            var occluded = false
            val root = rootLayout
            val rootChildCount = root.childCount
            val childRect = visibleRect

            for (r in 0 until rootChildCount) {
                val candidate = root.getChildAt(r) ?: continue
                if (candidate == grid || candidate == child) continue
                if (candidate.visibility != View.VISIBLE) continue

                val candidateZ = if (android.os.Build.VERSION.SDK_INT >= 21) candidate.z else 0f
                val childZ = if (android.os.Build.VERSION.SDK_INT >= 21) child.z else 0f
                if (candidateZ + 0.001f < childZ) continue // likely not occluding

                val candRect = Rect()
                val candHasRect = candidate.getGlobalVisibleRect(candRect)
                if (!candHasRect) continue

                val overlap = rectIntersectionArea(childRect, candRect)
                if (overlap <= 0) continue

                val overlapPctOfVisible = (overlap * 100) / (visibleArea.coerceAtLeast(1))
                FileLogger.d(this, "FakeHomeActivity",
                    "child[$i] candidateOccluder index=$r overlap=$overlap overlapPctOfVisible=$overlapPctOfVisible candidateZ=$candidateZ")

                if (overlapPctOfVisible >= 50 && isEffectivelyOpaqueView(candidate)) {
                    FileLogger.d(this, "FakeHomeActivity",
                        "child[$i] occluded by root child index=$r overlapPct=$overlapPctOfVisible")
                    occluded = true
                    break
                }
            }

            if (occluded) continue

            // All checks passed; this child is actually visible to the user
            FileLogger.d(this, "FakeHomeActivity", "child[$i] PASSED visibility checks -> visible to user")
            return true
        }

        FileLogger.d(this, "FakeHomeActivity", "No visible grid child detected after checks")
        return false
    }

    // Intersection area helper
    private fun rectIntersectionArea(a: Rect, b: Rect): Int {
        val left = maxOf(a.left, b.left)
        val right = minOf(a.right, b.right)
        val top = maxOf(a.top, b.top)
        val bottom = minOf(a.bottom, b.bottom)
        if (right <= left || bottom <= top) return 0
        return (right - left) * (bottom - top)
    }

    // Heuristic: treat a view as opaque if alpha ~1 and background is a solid ColorDrawable with full alpha,
    // or if background exists and view.alpha is almost 1 (conservative).
    private fun isEffectivelyOpaqueView(v: View): Boolean {
        if (v.alpha < 0.9f) return false
        val bg = v.background ?: return false
        if (bg is ColorDrawable) {
            val alpha = Color.alpha(bg.color)
            return alpha >= 250
        }
        return v.background != null && v.alpha >= 0.98f
    }

    // If GridView didn't render for this device, create a simple GridLayout overlay that is clickable.
    private fun createFallbackOverlay(apps: List<AppInfo>) {
        if (fallbackOverlay != null) {
            FileLogger.d(this, "FakeHomeActivity", "Fallback overlay already exists, skipping creation")
            return
        }

        FileLogger.d(this, "FakeHomeActivity", "Creating fallback overlay because GridView did not render children")
        grid.visibility = View.GONE

        val overlay = GridLayout(this)
        overlay.columnCount = 3
        overlay.useDefaultMargins = true
        overlay.setPadding(12, 12, 12, 12)

        val params = RelativeLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        params.addRule(RelativeLayout.BELOW, statusBar.id)
        params.addRule(RelativeLayout.ABOVE, dock.id)
        overlay.layoutParams = params

        val inflater = LayoutInflater.from(this)
        for ((index, app) in apps.withIndex()) {
            val item = inflater.inflate(R.layout.item_app, overlay, false)
            val name = item.findViewById<TextView>(R.id.appName)
            val icon = item.findViewById<ImageView>(R.id.appIcon)
            name.text = app.name
            try {
                val d = AppCompatResources.getDrawable(this, app.iconRes)
                icon.setImageDrawable(d)
                FileLogger.d(this, "FakeHomeActivity", "Fallback set icon for ${app.name} drawable=${d != null}")
            } catch (e: Exception) {
                FileLogger.e(this, "FakeHomeActivity", "Fallback failed to set icon for ${app.name}: ${e.message}")
            }

            item.setOnClickListener {
                val appName = app.name
                if (isAppUnlocked(appName)) {
                    startActivity(app.intent)
                    FileLogger.d(this, "FakeHomeActivity", "Fallback launching app: $appName")
                } else {
                    Toast.makeText(this, "$appName is locked. Wait for unlock.", Toast.LENGTH_SHORT).show()
                    FileLogger.d(this, "FakeHomeActivity", "Fallback blocked launch (locked): $appName")
                }
            }

            val specRow = GridLayout.spec(index / 3, 1f)
            val specCol = GridLayout.spec(index % 3, 1f)
            val glp = GridLayout.LayoutParams(specRow, specCol)
            glp.width = 0
            glp.height = LayoutParams.WRAP_CONTENT
            glp.setGravity(Gravity.CENTER)
            item.layoutParams = glp
            overlay.addView(item)
        }

        rootLayout.addView(overlay)
        overlay.bringToFront()
        overlay.elevation = 40f
        fallbackOverlay = overlay

        FileLogger.d(this, "FakeHomeActivity", "Fallback overlay created with ${apps.size} items")
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
                    "getView pos=$position name=${app.name} iconRes=${app.iconRes} drawable=${drawable != null} childIndex=${position}")
            } catch (e: Exception) {
                FileLogger.e(parent.context, "AppAdapter", "Failed to set icon for ${app.name}: ${e.message}")
            }

            return view
        }
    }
}
