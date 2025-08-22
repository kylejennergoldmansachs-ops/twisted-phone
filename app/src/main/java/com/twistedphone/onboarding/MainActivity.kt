package com.twistedphone.onboarding

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
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
    private val REQ_EXACT_ALARM = 4321

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

        // sensible defaults
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
                // store secrets and settings
                securePrefs.edit()
                    .putString("player_name", n)
                    .putString("mistral_key", k)
                    .putString("huggingface_token", hfToken)
                    .putString("pix_agent", p)
                    .putString("text_agent", t)
                    .apply()

                settingsPrefs.edit()
                    .putString("player_name", n)
                    .putString("mistral_api_key", k)
                    .putString("mistral_agent", if (t.isNotBlank()) t else p)
                    .putString("huggingface_token", hfToken)
                    .apply()

                // take selfie
                startActivityForResult(Intent(MediaStore.ACTION_IMAGE_CAPTURE), REQ_SELFIE)
            }
        }

        // Request runtime permissions required for onboarding flow
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

            // store selfie in secure prefs (private) and in settings for other code paths
            securePrefs.edit().putString("selfie_b64", b64).apply()
            settingsPrefs.edit().putString("selfie_b64", b64).apply()

            // optionally create a distorted selfie for AS (background)
            CoroutineScope(Dispatchers.Main).launch {
                val client = MistralClient
                val distorted: String? = withContext(Dispatchers.IO) {
                    try {
                        client.transformImageToDataUri("data:image/jpeg;base64,$b64")
                    } catch (e: Exception) {
                        null
                    }
                }
                if (!distorted.isNullOrBlank()) {
                    securePrefs.edit().putString("as_selfie_b64", distorted).apply()
                    settingsPrefs.edit().putString("as_selfie_b64", distorted).apply()
                } else {
                    securePrefs.edit().remove("as_selfie_b64").apply()
                    settingsPrefs.edit().remove("as_selfie_b64").apply()
                }
            }

            if (skipModels.isChecked) {
                startHome()
            } else {
                downloadModels()
            }
        } else if (req == REQ_EXACT_ALARM) {
            // Returned from the exact-alarm permission screen. Attempt to schedule unlocks (scheduler will re-check capability)
            try {
                AltMessageScheduler.scheduleUnlocks(this)
            } catch (t: Throwable) {
                t.printStackTrace()
            }
            // continue to home regardless
            startActivity(Intent(this, FakeHomeActivity::class.java))
            finish()
        }
    }

    private fun downloadModels() {
        val huggingfaceToken = securePrefs.getString("huggingface_token", "") ?: ""
        progress.visibility = View.VISIBLE
        loadingText.text = "Downloading models..."
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

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
                loadingText.text = if (info.state.name == "SUCCEEDED") "Models downloaded." else "Model download failed, using fallbacks."
                startHome()
            }
        }
    }

    private fun startHome() {
        // mark install time
        securePrefs.edit().putLong("install_time", System.currentTimeMillis()).apply()
        settingsPrefs.edit().putLong("install_time", System.currentTimeMillis()).apply()

        // If platform requires explicit exact-alarm grant, send user to the system settings screen.
        val alarmMgr = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val needsRequest = if (alarmMgr == null) {
            false
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    !alarmMgr.canScheduleExactAlarms()
                } catch (t: Throwable) {
                    // defensive: if check fails, don't block the flow
                    false
                }
            } else {
                false
            }
        }

        if (needsRequest) {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            intent.data = Uri.parse("package:$packageName")
            try {
                startActivityForResult(intent, REQ_EXACT_ALARM)
                // return here; onActivityResult will resume flow
                return
            } catch (t: Throwable) {
                // if the settings activity call fails, fall through and try scheduling (scheduler will fallback)
                t.printStackTrace()
            }
        }

        // proceed: scheduler will check permissions and fall back if necessary
        try {
            AltMessageScheduler.scheduleUnlocks(this)
        } catch (t: Throwable) {
            t.printStackTrace()
        }

        startActivity(Intent(this, FakeHomeActivity::class.java))
        finish()
    }
}
