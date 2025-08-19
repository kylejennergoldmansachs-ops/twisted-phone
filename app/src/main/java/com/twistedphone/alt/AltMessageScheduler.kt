package com.twistedphone.alt

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.twistedphone.TwistedApp
import java.util.*

object AltMessageScheduler {
    fun scheduleInitial() {
        val interval = 45 * 60 * 1000L // 45 minutes
        scheduleAlarm(interval)
    }

    // Add this function to your AltMessageScheduler.kt
    fun scheduleUnlocks(context: Context) {
        val alarmMgr = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AltUnlockReceiver::class.java)
        val alarmIntent = PendingIntent.getBroadcast(context, 1, intent, PendingIntent.FLAG_IMMUTABLE)
        val triggerTime = System.currentTimeMillis() + (2 * 60 * 60 * 1000) // 2 hours from now
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmMgr.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, alarmIntent)
            } else {
                alarmMgr.setExact(AlarmManager.RTC_WAKEUP, triggerTime, alarmIntent)
            }
        } catch (e: SecurityException) {
            Log.e("AltMessageScheduler", "Failed to schedule unlock alarm: ${e.message}")
        }
    }

    fun scheduleAlarm(interval: Long) {
        val alarmMgr = TwistedApp.instance.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(TwistedApp.instance, AltMessageReceiver::class.java)
        val alarmIntent = PendingIntent.getBroadcast(TwistedApp.instance, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val triggerTime = System.currentTimeMillis() + interval

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Check if we have permission to schedule exact alarms on Android 12+
                if (alarmMgr.canScheduleExactAlarms()) {
                    alarmMgr.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, alarmIntent)
                } else {
                    // Fall back to inexact alarm if we don't have permission
                    alarmMgr.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, alarmIntent)
                    Log.w("AltMessageScheduler", "No exact alarm permission, using inexact alarm")
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmMgr.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, alarmIntent)
            } else {
                alarmMgr.setExact(AlarmManager.RTC_WAKEUP, triggerTime, alarmIntent)
            }
        } catch (e: SecurityException) {
            Log.e("AltMessageScheduler", "Failed to schedule alarm: ${e.message}")
            // Fall back to inexact alarm
            alarmMgr.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, alarmIntent)
        }
    }
}
