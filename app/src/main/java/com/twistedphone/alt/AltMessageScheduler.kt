package com.twistedphone.alt
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import java.util.*

object AltMessageScheduler {
    fun scheduleInitial(ctx: Context) {
        val prefs = ctx.getSharedPreferences("tw_msgs", 0)
        if (!prefs.contains("install_time") ) prefs.edit().putLong("install_time", System.currentTimeMillis()).apply()
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val rng = Random()
        if (rng.nextDouble() < 0.7) {
            val offset = (rng.nextDouble() * 24 * 60 * 60 * 1000).toLong()
            scheduleAlarm(am, ctx, System.currentTimeMillis() + offset, 1001)
        }
        scheduleAlarm(am, ctx, System.currentTimeMillis() + 7 * 24 * 60 * 60 * 1000, 1002, true)
    }

    fun scheduleUnlocks(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // camera unlock after 6 min
        val unlockTime = SystemClock.elapsedRealtime() + 6 * 60 * 1000
        val intent = Intent(ctx, AltUnlockReceiver::class.java).putExtra("app", "Camera")
        val pi = PendingIntent.getBroadcast(ctx, 1003, intent, PendingIntent.FLAG_IMMUTABLE)
        am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, unlockTime, pi)
        // other unlocks...
    }

    private fun scheduleAlarm(am: AlarmManager, ctx: Context, time: Long, reqCode: Int, repeating: Boolean = false) {
        val intent = Intent(ctx, AltMessageReceiver::class.java)
        val pi = PendingIntent.getBroadcast(ctx, reqCode, intent, PendingIntent.FLAG_IMMUTABLE)
        if (repeating) {
            am.setInexactRepeating(AlarmManager.RTC_WAKEUP, time, 7 * 24 * 60 * 60 * 1000, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pi)
        }
    }
}
