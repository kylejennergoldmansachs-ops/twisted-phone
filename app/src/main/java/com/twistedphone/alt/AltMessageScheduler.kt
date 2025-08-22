package com.twistedphone.alt

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.twistedphone.TwistedApp

/**
 * Scheduler for periodically starting AltMessageService.
 *
 * - scheduleInitial(): schedule an inexact repeating alarm that starts AltMessageService.
 * - scheduleUnlocks(ctx): schedule an alarm to trigger AltUnlockReceiver (used by onboarding).
 * - cancel(): remove scheduled alarm.
 */
object AltMessageScheduler {
    private const val REQUEST_CODE = 202401
    private const val DEFAULT_INTERVAL_MS = 30L * 60L * 1000L // 30 minutes
    private const val UNLOCK_REQUEST_CODE = 202402

    @JvmStatic
    fun scheduleInitial() {
        val ctx = TwistedApp.instance
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return

        // Intent that starts the Service directly.
        val intent = Intent(ctx, AltMessageService::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else
            PendingIntent.FLAG_UPDATE_CURRENT

        val pi = PendingIntent.getService(ctx, REQUEST_CODE, intent, flags)

        val prefs = ctx.settingsPrefs
        val interval = try {
            prefs.getLong("alt_interval_ms", DEFAULT_INTERVAL_MS)
        } catch (_: Exception) {
            DEFAULT_INTERVAL_MS
        }

        val startAt = SystemClock.elapsedRealtime() + 10_000L
        // inexact repeating is battery-friendly and sufficient for message generation
        am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, startAt, interval, pi)
    }

    /**
     * Schedule a single unlock alarm (used from onboarding). This mirrors the original
     * onboarding implementation: it schedules a single broadcast to AltUnlockReceiver
     * approximately two hours from now.
     *
     * Note: AltUnlockReceiver expects an "app" extra to mark a particular app unlocked.
     * The onboarding/MainActivity code in the repo schedules a single alarm without extras;
     * this preserves that behavior to avoid changing runtime semantics unexpectedly.
     */
    @JvmStatic
    fun scheduleUnlocks(ctx: Context) {
        val alarmMgr = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(ctx, AltUnlockReceiver::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else
            PendingIntent.FLAG_UPDATE_CURRENT

        val alarmIntent = PendingIntent.getBroadcast(ctx, UNLOCK_REQUEST_CODE, intent, flags)
        val triggerTime = System.currentTimeMillis() + (2 * 60 * 60 * 1000) // 2 hours

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmMgr.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, alarmIntent)
        } else {
            alarmMgr.setExact(AlarmManager.RTC_WAKEUP, triggerTime, alarmIntent)
        }
    }

    @JvmStatic
    fun cancel() {
        val ctx = TwistedApp.instance
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(ctx, AltMessageService::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else
            PendingIntent.FLAG_UPDATE_CURRENT
        val pi = PendingIntent.getService(ctx, REQUEST_CODE, intent, flags)
        am.cancel(pi)
        pi.cancel()

        // Also cancel any unlock alarm we scheduled
        val unlockIntent = Intent(ctx, AltUnlockReceiver::class.java)
        val unlockFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else
            PendingIntent.FLAG_UPDATE_CURRENT
        val upi = PendingIntent.getBroadcast(ctx, UNLOCK_REQUEST_CODE, unlockIntent, unlockFlags)
        am.cancel(upi)
        upi.cancel()
    }
}
