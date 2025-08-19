package com.twistedphone.alt
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.twistedphone.TwistedApp

class AltUnlockReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        val app = intent?.getStringExtra("app") ?: return
        TwistedApp.instance.settingsPrefs.edit().putBoolean("unlock_$app", true).apply()
        // notify user "Camera is now fixed"
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val chId = "unlockch"
        val ch = android.app.NotificationChannel(chId, "Unlocks", android.app.NotificationManager.IMPORTANCE_DEFAULT)
        nm.createNotificationChannel(ch)
        val n = androidx.core.app.NotificationCompat.Builder(ctx, chId).setContentTitle("$app unlocked").setContentText("The $app app is now available.").setSmallIcon(android.R.drawable.ic_dialog_info).build()
        nm.notify(app.hashCode(), n)
    }
}
