package com.twistedphone.home

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

class FakeHomeActivity : AppCompatActivity() {
    private lateinit var clock: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val rnd = Random()
    private val prefs = TwistedApp.instance.settingsPrefs
    private val tick = object : Runnable {
        override fun run() {
            val now = Calendar.getInstance()
            // Only jitter every 5 seconds with a 30% chance
            if (rnd.nextFloat() < 0.3f && System.currentTimeMillis() % 5000 < 1000) {
                // Apply jitter: randomly change one digit
                val currentTime = String.format("%02d:%02d", now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))
                val chars = currentTime.toCharArray()
                val randomIndex = rnd.nextInt(chars.size)
                if (chars[randomIndex].isDigit()) {
                    chars[randomIndex] = (rnd.nextInt(10) + '0'.code).toChar()
                }
                clock.text = String(chars)
            } else {
                // Normal time
                clock.text = String.format("%02d:%02d", now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))
            }
            handler.postDelayed(this, 1000)
        }
    }
    
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_fake_home)
        
        // Set background if available
        // Replace the background setting code in onCreate() with this:
        try {
            // First try to use the custom background
            val background = BitmapFactory.decodeResource(resources, R.drawable.background)
            val scaledBackground = Bitmap.createScaledBitmap(
                background, 
                resources.displayMetrics.widthPixels,
                resources.displayMetrics.heightPixels,
                true
            )
            val rootView = findViewById<View>(android.R.id.content).rootView
            rootView.background = BitmapDrawable(resources, scaledBackground)
        } catch (e: Exception) {
            // Use default background if custom background isn't available
            findViewById<View>(android.R.id.content).rootView.setBackgroundColor(0xFF1A1A1A.toInt())
            android.util.Log.e("FakeHomeActivity", "Failed to load background: ${e.message}")
        }
        
        // Replace the clock-related code in FakeHomeActivity.kt with this:
        private lateinit var clock: TextView
        private val handler = Handler(Looper.getMainLooper())
        private val rnd = Random()
        private var originalTime: String = ""
        private var jitteredTime: String = ""
        private var jitterIndex: Int = -1
        private val tick = object : Runnable {
            override fun run() {
                val now = Calendar.getInstance()
                val currentTime = String.format("%02d:%02d", now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))
                // Every 2 seconds, change a random digit
                if (System.currentTimeMillis() % 2000 < 100) {
                    // Save original time
                    originalTime = currentTime
                    // Choose a random digit to jitter (positions 0,1,3,4 are digits in HH:MM format)
                    val digitPositions = intArrayOf(0, 1, 3, 4)
                    jitterIndex = digitPositions[rnd.nextInt(digitPositions.size)]
                    // Create jittered time with one random digit changed
                    val chars = currentTime.toCharArray()
                    chars[jitterIndex] = (rnd.nextInt(10) + '0'.code).toChar()
                    jitteredTime = String(chars)
                    clock.text = jitteredTime
                } else {
                    // Show normal time
                    clock.text = originalTime
                }
                handler.postDelayed(this, 100) // Update every 100ms for smooth animation
            }
        }
        
        // Create app list with icons
        val apps = listOf(
            AppInfo("Browser", Intent(this, WebViewActivity::class.java), R.drawable.ic_browser),
            AppInfo("Camera", Intent(this, CameraActivity::class.java), R.drawable.ic_camera),
            AppInfo("WhosApp", Intent(this, WhosAppActivity::class.java), R.drawable.ic_whosapp),
            AppInfo("Messages", Intent(this, MessagesActivity::class.java), R.drawable.ic_messages),
            AppInfo("Gallery", Intent(this, GalleryActivity::class.java), R.drawable.ic_gallery),
            AppInfo("Settings", Intent(this, SettingsActivity::class.java), R.drawable.ic_settings)
        )
        
        grid.numColumns = 3
        grid.adapter = AppAdapter(this, apps)
        grid.setOnItemClickListener { _, _, pos, _ -> 
            val appName = apps[pos].name
            if (isAppUnlocked(appName)) {
                startActivity(apps[pos].intent)
            } else {
                Toast.makeText(this, "$appName is locked. Wait for unlock.", Toast.LENGTH_SHORT).show()
            }
        }
        
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnHome).setOnClickListener { /* stay home */ }
        findViewById<ImageButton>(R.id.btnRecent).setOnClickListener { /* show recent, placeholder */ }
    }
    
    private fun isAppUnlocked(app: String): Boolean {
        return prefs.getBoolean("unlock_$app", app == "Browser")
    }
    
    override fun onResume() { 
        super.onResume()
        handler.post(tick) 
    }
    
    override fun onPause() { 
        super.onPause()
        handler.removeCallbacks(tick) 
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
            view.findViewById<TextView>(R.id.appName).text = app.name
            view.findViewById<ImageView>(R.id.appIcon).setImageResource(app.iconRes)
            return view
        }
    }
}
