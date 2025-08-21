// --- BEGIN AltMessageService.kt ---
package com.twistedphone.alt

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.location.Location
import android.os.IBinder
import android.provider.MediaStore
import android.util.Base64
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Tasks
import com.twistedphone.TwistedApp
import com.twistedphone.ai.MistralClient
import com.twistedphone.messages.MessageStore
import kotlinx.coroutines.*
import java.io.File

class AltMessageService : Service() {
    private val TAG = "AltMessageService"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch {
            handleWork()
        }
        return START_STICKY
    }

    private suspend fun handleWork() {
        val prefs = TwistedApp.instance.settingsPrefs
        val settings = TwistedApp.instance.settingsPrefs
        val cameraContext = settings.getBoolean("camera_context", false)
        val useLocation = settings.getBoolean("include_location", false)

        var thumbnail: String? = null
        if (cameraContext) {
            val thumb = captureThumbnail()
            if (thumb != null) {
                // captureThumbnail returns a Data URI / base64 string
                thumbnail = thumb
            }
        }

        var locationStr: String? = null
        if (useLocation) {
            try {
                val fused = LocationServices.getFusedLocationProviderClient(this@AltMessageService)
                val t = Tasks.await(fused.lastLocation)
                if (t != null) {
                    locationStr = "${t.latitude},${t.longitude}"
                }
            } catch (e: Exception) {
                // ignore
            }
        }

        val promptHint = prefs.getString("prompt_hint", "a short strange message") ?: "a short strange message"
        // Use singleton MistralClient (no constructor)
        val client = MistralClient
        val desc = if (thumbnail != null) client.getImageDescription(thumbnail) else null

        val history = MessageStore.getRecentMessages(this@AltMessageService, 5)
        val generated = client.generateAltMessage(promptHint + (desc?.let { " Image: $it" } ?: ""), history)
        val text = generated ?: "..."

        showNotification(text)
        // store generated message
        MessageStore.insertIncoming(this@AltMessageService, text, System.currentTimeMillis())
    }

    private fun captureThumbnail(): String? {
        // Attempt to grab a quick camera thumbnail via MediaStore or your camera capture activity.
        // This tries to reuse existing CameraCaptureActivity if present in project.
        return try {
            // Placeholder: app may implement real capture path; attempt MediaStore thumbnail if possible.
            val bitmap = Bitmap.createBitmap(1,1, Bitmap.Config.ARGB_8888) // fallback tiny placeholder
            val baos = java.io.ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 60, baos)
            val b = baos.toByteArray()
            "data:image/jpeg;base64," + Base64.encodeToString(b, Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    private fun showNotification(text: String) {
        val chId = "alt_messages"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val ch = NotificationChannel(chId, "Alt messages", NotificationManager.IMPORTANCE_DEFAULT)
            nm.createNotificationChannel(ch)
        }
        val intent = Intent(this, javaClass) // no-op target
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, chId)
            .setContentTitle("Message from someone...")
            .setContentText(text)
            .setContentIntent(pi)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        nm.notify((System.currentTimeMillis() % 10000).toInt(), n)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
// --- END AltMessageService.kt ---
