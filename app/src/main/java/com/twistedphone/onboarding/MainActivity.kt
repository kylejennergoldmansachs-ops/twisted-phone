package com.twistedphone.onboarding

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.twistedphone.R
import com.twistedphone.TwistedApp
import com.twistedphone.ai.MistralClient
import com.twistedphone.alt.AltMessageScheduler
import com.twistedphone.alt.AltUnlockReceiver
import com.twistedphone.home.FakeHomeActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class MainActivity : AppCompatActivity() {
    private val REQ_SELFIE = 2345
    private val securePrefs = TwistedApp.instance.securePrefs
    private val settingsPrefs = TwistedApp.instance.settingsPrefs
    private lateinit var progress: ProgressBar
    private lateinit var loadingText: TextView
    private lateinit var skipModels: CheckBox

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)

        // If setup already complete, go to home
        if (isSetupComplete()) {
            startHome()
            return
        }

        setContentView(R.layout.activity_main)
        val name = findViewById<EditText>(R.id.nameInput)
        val api = findViewById<EditText>(R.id.apiInput)
        val huggingfaceInput = findViewById<EditText>(R.id.huggingfaceInput)
        val pix = findViewById<EditText>(R.id.pixtralInput)
        val textAgent = findViewById<EditText>(R.id.textAgentInput)

        // sensible defaults (keep as you had)
        pix.setText("ag:ddacd900:20250418:untitled-agent:8dfc3563")
        textAgent.setText("ag:ddacd900:20250418:genbot-text:2d411523")

        progress = findViewById(R.id.progressModels)
        loadingText = findViewById(R.id.loadingText)
        skipModels = findViewById(R.id.skipModels)

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            val n = name.text.toString().trim()
            val k = api.text.toString().trim()
            val hfToken = huggingfaceInput.text.toString().trim()
            val p = pix.text.toString().trim()
            val t = textAgent.text.toString().trim()

            if (n.isNotEmpty() && k.isNotEmpty()) {
                // store values in secure prefs (secrets) and in settings prefs (MistralClient reads settingsPrefs)
                securePrefs.edit().putString("player_name", n).putString("mistral_key", k).putString("huggingface_token", hfToken).putString("pix_agent", p).putString("text_agent", t).apply()

                // Also store the keys where MistralClient expects them:
                settingsPrefs.edit()
                    .putString("player_name", n)
                    .putString("mistral_api_key", k)        // key MistralClient will read
                    .putString("mistral_agent", if (t.isNotBlank()) t else p) // prefer textAgent, fall back to pix agent
                    .putString("huggingface_token", hfToken)
                    .apply()

                // take selfie
                startActivityForResult(Intent(MediaStore.ACTION_IMAGE_CAPTURE), REQ_SELFIE)
            }
        }

        // Request permissions required for onboarding flow
        requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS), 101)
    }

    private fun isSetupComplete(): Boolean {
        val name = securePrefs.getString("player_name", "")
        val key = securePrefs.getString("mistral_key", "")
        val selfie = securePrefs.getString("selfie_b64", "")
        return !name.isNullOrEmpty() && !key.isNullOrEmpty() && !selfie.isNullOrEmpty()
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == REQ_SELFIE && res == Activity.RESULT_OK) {
            val bmp = data?.extras?.get("data") as? Bitmap ?: return
            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, baos)
            val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

            // store selfie in secure prefs (private)
            securePrefs.edit().putString("selfie_b64", b64).apply()
            // and in settings prefs too for any components that may read from settingsPrefs
            settingsPrefs.edit().putString("selfie_b64", b64).apply()

            // generate distorted selfie for AS using MistralClient singleton
            CoroutineScope(Dispatchers.Main).launch {
                val client = MistralClient
                val distorted: String? = withContext(Dispatchers.IO) {
                    try {
                        client.transformImageToDataUri("data:image/jpeg;base64,$b64")
                    } catch (e: Exception) {
                        // safe fallback: return null
                        null
                    }
                }
                // store only if we actually got something back
                if (!distorted.isNullOrBlank()) {
                    securePrefs.edit().putString("as_selfie_b64", distorted).apply()
                    settingsPrefs.edit().putString("as_selfie_b64", distorted).apply()
                } else {
                    // Clear potential leftover or leave previous value — here we simply log via prefs removal
                    // (keeps behavior explicit)
                    securePrefs.edit().remove("as_selfie_b64").apply()
                    settingsPrefs.edit().remove("as_selfie_b64").apply()
                }
            }

            if (skipModels.isChecked) {
                startHome()
            } else {
                downloadModels()
            }
        }
    }

    private fun downloadModels() {
        val huggingfaceToken = securePrefs.getString("huggingface_token", "") ?: ""
        progress.visibility = View.VISIBLE
        loadingText.text = "Downloading models..."
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        // Create input data for the worker
        val inputData = Data.Builder()
            .putString("HUGGINGFACE_TOKEN", huggingfaceToken)
            .build()

        val work = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(constraints)
            .setInputData(inputData)
            .build()

        WorkManager.getInstance(this).enqueue(work)
        WorkManager.getInstance(this).getWorkInfoByIdLiveData(work.id).observe(this) { info ->
            if (info.state.isFinished) {
                progress.visibility = View.GONE
                if (info.state.name == "SUCCEEDED") {
                    loadingText.text = "Models downloaded."
                } else {
                    loadingText.text = "Model download failed, using fallbacks."
                }
                startHome()
            }
        }
    }

    private fun scheduleUnlocks() {
        val alarmMgr = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, AltUnlockReceiver::class.java)
        val alarmIntent = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val triggerTime = System.currentTimeMillis() + (2 * 60 * 60 * 1000) // 2 hours

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            alarmMgr.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, alarmIntent)
        } else {
            alarmMgr.setExact(AlarmManager.RTC_WAKEUP, triggerTime, alarmIntent)
        }
    }

    private fun startHome() {
        // mark install time and schedule unlocks
        securePrefs.edit().putLong("install_time", System.currentTimeMillis()).apply()
        settingsPrefs.edit().putLong("install_time", System.currentTimeMillis()).apply()
        AltMessageScheduler.scheduleUnlocks(this)
        startActivity(Intent(this, FakeHomeActivity::class.java))
        finish()
    }
}
