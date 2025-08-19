
package com.twistedphone

import android.app.Application
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.twistedphone.alt.AltMessageScheduler

class TwistedApp : Application() {
    companion object {
        lateinit var instance: TwistedApp
            private set
    }

    lateinit var securePrefs: EncryptedSharedPreferences
    lateinit var settingsPrefs: android.content.SharedPreferences

    override fun onCreate() {
        super.onCreate()
        instance = this
        
        // Initializ encrypted preferences with correct configuration
        try {
            val masterKey = MasterKey.Builder(this)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            securePrefs = EncryptedSharedPreferences.create(
                this,
                "secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            ) as EncryptedSharedPreferences
        } catch (e: Exception) {
            Log.e("TwistedApp", "Failed to create encrypted preferences: ${e.message}")
            // Fall back to regular shared preferences
            securePrefs = getSharedPreferences("secure_prefs", MODE_PRIVATE) as EncryptedSharedPreferences
        }

        settingsPrefs = getSharedPreferences("settings", MODE_PRIVATE)
        
        try {
            AltMessageScheduler.scheduleInitial()
        } catch (e: SecurityException) {
            Log.e("TwistedApp", "Failed to schedule initial alarm: ${e.message}")
        }
    }
}
