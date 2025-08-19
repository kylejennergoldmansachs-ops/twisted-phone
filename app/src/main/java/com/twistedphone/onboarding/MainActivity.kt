package com.twistedphone.onboarding
import android.Manifest
import android.app.Activity
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
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.twistedphone.R
import com.twistedphone.TwistedApp
import com.twistedphone.ai.MistralClient
import com.twistedphone.home.FakeHomeActivity
import com.twistedphone.alt.AltMessageScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class MainActivity : AppCompatActivity() {
    private val REQ_SELFIE = 2345
    private val prefs = TwistedApp.instance.securePrefs
    private lateinit var progress: ProgressBar
    private lateinit var loadingText: TextView
    private lateinit var skipModels: CheckBox
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_main)
        val name = findViewById<EditText>(R.id.nameInput)
        val api = findViewById<EditText>(R.id.apiInput)
        val pix = findViewById<EditText>(R.id.pixtralInput)
        val textAgent = findViewById<EditText>(R.id.textAgentInput)
        pix.setText("ag:ddacd900:20250418:untitled-agent:8dfc3563")
        textAgent.setText("ag:ddacd900:20250418:genbot-text:2d411523")
        progress = findViewById(R.id.progressModels)
        loadingText = findViewById(R.id.loadingText)
        skipModels = findViewById(R.id.skipModels)
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            val n = name.text.toString().trim(); val k = api.text.toString().trim()
            val p = pix.text.toString().trim(); val t = textAgent.text.toString().trim()
            if(n.isNotEmpty() && k.isNotEmpty()) {
                prefs.edit().putString("player_name", n).putString("mistral_key", k).putString("pix_agent", p).putString("text_agent", t).apply()
                // take selfie
                startActivityForResult(Intent(MediaStore.ACTION_IMAGE_CAPTURE), REQ_SELFIE)
            }
        }
        requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS), 101)
    }
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if(req==REQ_SELFIE && res==Activity.RESULT_OK) {
            val bmp = data?.extras?.get("data") as? Bitmap ?: return
            val baos = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, 80, baos)
            val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            prefs.edit().putString("selfie_b64", b64).apply()
            // generate distorted selfie for AS
            CoroutineScope(Dispatchers.Main).launch {
                val client = MistralClient()
                val distorted = withContext(Dispatchers.IO) { client.transformImageToDataUri("data:image/jpeg;base64,$b64") }
                prefs.edit().putString("as_selfie_b64", distorted).apply()
            }
            if(skipModels.isChecked) {
                startHome()
            } else {
                downloadModels()
            }
        }
    }
    private fun downloadModels() {
        progress.visibility = View.VISIBLE
        loadingText.text = "Downloading models..."
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val work = OneTimeWorkRequestBuilder<ModelDownloadWorker>().setConstraints(constraints).build()
        WorkManager.getInstance(this).enqueue(work)
        WorkManager.getInstance(this).getWorkInfoByIdLiveData(work.id).observe(this) { info ->
            if(info.state.isFinished) {
                progress.visibility = View.GONE
                if(info.state.name == "SUCCEEDED") {
                    loadingText.text = "Models downloaded."
                } else {
                    loadingText.text = "Model download failed, using fallbacks."
                }
                startHome()
            }
        }
    }
    private fun startHome() {
        prefs.edit().putLong("install_time", System.currentTimeMillis()).apply()
        AltMessageScheduler.scheduleUnlocks(this) // extended for timed unlocks
        startActivity(Intent(this, FakeHomeActivity::class.java))
        finish()
    }
}
