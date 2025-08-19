package com.twistedphone.settings
import android.os.Bundle
import android.widget.SeekBar
import android.widget.Switch
import androidx.appcompat.app.AppCompatActivity
import com.twistedphone.R
import com.twistedphone.TwistedApp

class SettingsActivity : AppCompatActivity() {
    private val prefs = TwistedApp.instance.settingsPrefs
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_settings)
        findViewById<Switch>(R.id.swEnhancedCamera).apply {
            isChecked = prefs.getBoolean("enhanced_camera", false)
            setOnCheckedChangeListener { _, b -> prefs.edit().putBoolean("enhanced_camera", b).apply() }
        }
        findViewById<Switch>(R.id.swApiUsage).apply {
            isChecked = prefs.getBoolean("high_api_usage", false)
            setOnCheckedChangeListener { _, b -> prefs.edit().putBoolean("high_api_usage", b).apply() }
        }
        findViewById<Switch>(R.id.swCameraContext).apply {
            isChecked = prefs.getBoolean("camera_context", false)
            setOnCheckedChangeListener { _, b -> prefs.edit().putBoolean("camera_context", b).apply() }
        }
        findViewById<SeekBar>(R.id.seekAggressiveness).apply {
            progress = prefs.getInt("aggressiveness", 1)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) prefs.edit().putInt("aggressiveness", p).apply() }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
    }
}
