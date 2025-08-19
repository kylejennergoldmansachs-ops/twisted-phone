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
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.twistedphone.TwistedApp
import com.twistedphone.ai.MistralClient
import com.twistedphone.messages.MessageStore
import kotlinx.coroutines.*
import java.io.File
import java.util.*

class AltMessageService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val prefs = TwistedApp.instance.securePrefs
    private val settings = TwistedApp.instance.settingsPrefs
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val isReply = intent?.getBooleanExtra("is_reply", false) ?: false
        val installTime = prefs.getLong("install_time", 0)
        val now = System.currentTimeMillis()
        val rng = Random()
        val chance = if (isReply) 1.0 else if (now - installTime > 24 * 60 * 60 * 1000) 0.4 else 0.7
        if (rng.nextDouble() > chance) { stopSelf(); return START_NOT_STICKY }
        scope.launch {
            var msg = ""
            if (now - installTime < 24 * 60 * 60 * 1000 && rng.nextDouble() < 0.5) {
                msg = generateGibberish()
            } else {
                val history = MessageStore.recentHistory(applicationContext)
                var contextHint = "Time: $now"
                try {
                    val locProvider = LocationServices.getFusedLocationProviderClient(applicationContext)
                    val loc = withTimeoutOrNull(2000) { locProvider.lastLocation.await() }
                    if (loc != null) contextHint += " Location: ${loc.latitude},${loc.longitude}"
                } catch (_: Exception) {}
                if (settings.getBoolean("camera_context", false)) {
                    val thumbnail = captureThumbnail()
                    if (thumbnail != null) {
                        val client = MistralClient()
                        val desc = client.getImageDescription(thumbnail)
                        if (desc.isNotBlank()) contextHint += " Scene: $desc"
                    }
                }
                val client = MistralClient()
                msg = client.generateAltMessage(contextHint, history)
            }
            if (msg.isNotBlank()) {
                MessageStore.addMessage(applicationContext, "ALT", msg)
                showNotification(msg)
            }
            stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun generateGibberish(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz1234567890!@#$%^&*()".toCharArray()
        return (1..20).map { chars.random() }.joinToString("")
    }
    private suspend fun captureThumbnail(): String? = withContext(Dispatchers.IO) {
        val provider = ProcessCameraProvider.getInstance(applicationContext).await()
        val imageCapture = ImageCapture.Builder().setTargetResolution(android.util.Size(128, 128)).build()
        provider.bindToLifecycle(null, CameraSelector.DEFAULT_FRONT_CAMERA, imageCapture)
        var b64: String? = null
        val tempFile = File.createTempFile("thumbnail", ".jpg", cacheDir)
        val output = ImageCapture.OutputFileOptions.Builder(tempFile).build()
        imageCapture.takePicture(output, ContextCompat.getMainExecutor(applicationContext), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                val bmp = android.graphics.BitmapFactory.decodeFile(tempFile.absolutePath)
                val baos = java.io.ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, 50, baos)
                b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                tempFile.delete()
            }
            override fun onError(exc: ImageCaptureException) {}
        })
        delay(2000) // wait for capture
        provider.unbindAll()
        b64
    }
    private fun showNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val chId = "altch"
        val ch = NotificationChannel(chId, "Alternative Self", NotificationManager.IMPORTANCE_HIGH)
        nm.createNotificationChannel(ch)
        val intent = Intent(this, com.twistedphone.messages.MessagesActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, chId).setContentTitle("Message from someone...").setContentText(text).setContentIntent(pi).setSmallIcon(android.R.drawable.ic_dialog_info).build()
        nm.notify((System.currentTimeMillis() % 10000).toInt(), n)
    }
    override fun onDestroy() { super.onDestroy(); scope.cancel() }
}
