package com.twistedphone

import android.app.Application
import android.util.Log
import com.twistedphone.alt.AltMessageScheduler

class TwistedApp : Application() {
    companion object {
        lateinit var instance: TwistedApp
            private set
    }

    lateinit var securePrefs: EncryptedSharedPrefs
    lateinit var settingsPrefs: android.content.SharedPreferences

    override fun onCreate() {
        super.onCreate()
        instance = this
        securePrefs = EncryptedSharedPrefs(this)
        settingsPrefs = getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        
        try {
            AltMessageScheduler.scheduleInitial()
        } catch (e: SecurityException) {
            Log.e("TwistedApp", "Failed to schedule initial alarm: ${e.message}")
        }
    }
}
