package com.twistedphone.alt

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import android.util.Base64
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Tasks
import com.twistedphone.TwistedApp
import com.twistedphone.ai.MistralClient
import com.twistedphone.messages.MessageStore
import com.twistedphone.messages.MessagesActivity
import com.twistedphone.util.Logger
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream

/**
 * AltMessageService - generates an alternative-self message and posts a notification.
 * Fix performed: tapping the notification now opens MessagesActivity.
 */
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
        val includeCameraContext = prefs.getBoolean("camera_context", false)
        val includeLocation = prefs.getBoolean("include_location", false)

        var thumbnailDataUri: String? = null
        if (includeCameraContext) {
            try {
                thumbnailDataUri = getMostRecentImageAsDataUri()
            } catch (e: Exception) {
                Logger.e(TAG, "thumbnail extraction failed: ${e.message}")
            }
        }

        var locationStr: String? = null
        if (includeLocation) {
            try {
                val fused = LocationServices.getFusedLocationProviderClient(this@AltMessageService)
                val loc = Tasks.await(fused.lastLocation)
                if (loc != null) locationStr = "${loc.latitude},${loc.longitude}"
            } catch (e: Exception) {
                Logger.e(TAG, "location fetch failed: ${e.message}")
            }
        }

        val promptHint = prefs.getString("prompt_hint", "something odd") ?: "something odd"
        val client = MistralClient
        val desc = thumbnailDataUri?.let { client.getImageDescription(it) }
        val history = MessageStore.getRecentMessages(this@AltMessageService, 6)
        val contextHint = buildString {
            append(promptHint)
            if (!desc.isNullOrBlank()) append(" Image description: $desc")
            if (!locationStr.isNullOrBlank()) append(" Location: $locationStr")
        }

        val generated = client.generateAltMessage(contextHint, history)
        val text = generated ?: "..."

        showNotification(text)
        MessageStore.insertIncoming(this@AltMessageService, text, System.currentTimeMillis())
    }

    private fun getMostRecentImageAsDataUri(): String? {
        val resolver: ContentResolver = contentResolver
        val uriExternal: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED)
        val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        var cursor: Cursor? = null
        try {
            cursor = resolver.query(uriExternal, projection, null, null, sort)
            if (cursor != null && cursor.moveToFirst()) {
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val id = cursor.getLong(idIndex)
                val uri = Uri.withAppendedPath(uriExternal, id.toString())
                val input = resolver.openInputStream(uri)
                input?.use { inputStream ->
                    val bmp = BitmapFactory.decodeStream(inputStream) ?: return null
                    val thumb = Bitmap.createScaledBitmap(bmp, 256, (256f * bmp.height / bmp.width).toInt(), true)
                    val baos = ByteArrayOutputStream()
                    thumb.compress(Bitmap.CompressFormat.JPEG, 70, baos)
                    val bytes = baos.toByteArray()
                    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    return "data:image/jpeg;base64,$b64"
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "getMostRecentImageAsDataUri failed: ${e.message}")
        } finally {
            cursor?.close()
        }
        return null
    }

    private fun showNotification(text: String) {
        val chId = "alt_messages"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(chId, "Alt messages", NotificationManager.IMPORTANCE_DEFAULT)
            nm.createNotificationChannel(ch)
        }

        // IMPORTANT: open MessagesActivity when user taps the notification
        val intent = Intent(this, MessagesActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else
            PendingIntent.FLAG_UPDATE_CURRENT
        val pi = PendingIntent.getActivity(this, 0, intent, piFlags)

        val n = NotificationCompat.Builder(this, chId)
            .setContentTitle("Message from someone.")
            .setContentText(text)
            .setContentIntent(pi)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()
        nm.notify((System.currentTimeMillis() % 10000).toInt(), n)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
