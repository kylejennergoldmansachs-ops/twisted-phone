package com.twistedphone.alt

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.twistedphone.TwistedApp
import com.twistedphone.util.Logger

/**
 * Robust scheduler: uses exact alarms only when the platform and user allow them.
 * Provides:
 *  - scheduleInitial()  : inexact repeating scheduler for the AltMessageService
 *  - scheduleUnlocks(ctx): onboarding one-off unlock alarm — attempts exact scheduling when allowed
 *  - cancel()           : cancels scheduled intents
 */
object AltMessageScheduler {
    private const val REQUEST_CODE = 202401
    private const val UNLOCK_REQUEST_CODE = 202402
    private const val DEFAULT_INTERVAL_MS = 30L * 60L * 1000L // 30 minutes
    private const val TAG = "AltMessageScheduler"

    @JvmStatic
    fun scheduleInitial() {
        val ctx = TwistedApp.instance
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return

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
        try {
            am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, startAt, interval, pi)
        } catch (se: SecurityException) {
            Logger.e(TAG, "scheduleInitial SecurityException: ${se.message}")
        } catch (t: Throwable) {
            Logger.e(TAG, "scheduleInitial failed: ${t.message}")
        }
    }

    /**
     * Schedule a one-off "unlock" alarm roughly two hours from now.
     * If exact alarms are allowed, use exact API; otherwise fall back to non-exact set().
     */
    @JvmStatic
    fun scheduleUnlocks(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return

        val intent = Intent(ctx, AltUnlockReceiver::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else
            PendingIntent.FLAG_UPDATE_CURRENT

        val pi = PendingIntent.getBroadcast(ctx, UNLOCK_REQUEST_CODE, intent, flags)
        val triggerTime = System.currentTimeMillis() + (2 * 60 * 60 * 1000) // 2 hours

        try {
            val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    am.canScheduleExactAlarms()
                } catch (t: Throwable) {
                    Logger.w(TAG, "canScheduleExactAlarms check failed: ${t.message}")
                    false
                }
            } else {
                true
            }

            if (canExact) {
                // prefer exact scheduling
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pi)
                } else {
                    am.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pi)
                }
            } else {
                // fallback to non-exact scheduling (safe)
                am.set(AlarmManager.RTC_WAKEUP, triggerTime, pi)
            }
        } catch (se: SecurityException) {
            // defensive fallback: try a non-exact set() call and log
            Logger.e(TAG, "SecurityException scheduling unlock alarm: ${se.message}")
            try {
                am.set(AlarmManager.RTC_WAKEUP, triggerTime, pi)
            } catch (t: Throwable) {
                Logger.e(TAG, "Fallback set() failed: ${t.message}")
            }
        } catch (t: Throwable) {
            Logger.e(TAG, "scheduleUnlocks failed: ${t.message}")
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
        try {
            am.cancel(pi)
        } catch (t: Throwable) {
            Logger.e(TAG, "cancel service alarm failed: ${t.message}")
        }
        pi.cancel()

        val unlockIntent = Intent(ctx, AltUnlockReceiver::class.java)
        val uflags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else
            PendingIntent.FLAG_UPDATE_CURRENT
        val upi = PendingIntent.getBroadcast(ctx, UNLOCK_REQUEST_CODE, unlockIntent, uflags)
        try {
            am.cancel(upi)
        } catch (t: Throwable) {
            Logger.e(TAG, "cancel unlock alarm failed: ${t.message}")
        }
        upi.cancel()
    }
}
