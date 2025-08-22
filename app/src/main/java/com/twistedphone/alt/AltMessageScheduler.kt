package com.twistedphone.alt

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.twistedphone.TwistedApp

/**
 * Scheduler for periodically starting AltMessageService.
 *
 * This file **only** schedules/starts the service. The Service implementation is in AltMessageService.kt.
 *
 * Behavior:
 * - scheduleInitial(): schedule an inexact repeating alarm that starts AltMessageService.
 * - cancel(): remove scheduled alarm.
 */
object AltMessageScheduler {
    private const val REQUEST_CODE = 202401
    private const val DEFAULT_INTERVAL_MS = 30L * 60L * 1000L // 30 minutes

    @JvmStatic
    fun scheduleInitial() {
        val ctx = TwistedApp.instance
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return

        // Intent that starts the Service directly.
        val intent = Intent(ctx, AltMessageService::class.java)
        val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M)
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

    @JvmStatic
    fun cancel() {
        val ctx = TwistedApp.instance
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(ctx, AltMessageService::class.java)
        val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M)
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else
            PendingIntent.FLAG_UPDATE_CURRENT
        val pi = PendingIntent.getService(ctx, REQUEST_CODE, intent, flags)
        am.cancel(pi)
        pi.cancel()
    }
}
