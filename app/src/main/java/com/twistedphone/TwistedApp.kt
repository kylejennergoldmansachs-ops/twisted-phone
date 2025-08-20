package com.twistedphone

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.twistedphone.alt.AltMessageScheduler

class TwistedApp : Application() {

    companion object {
        lateinit var instance: TwistedApp
            private set
    }

    // Use SharedPreferences type so fallback is safe. EncryptedSharedPreferences implements SharedPreferences.
    lateinit var securePrefs: SharedPreferences
    lateinit var settingsPrefs: SharedPreferences

    override fun onCreate() {
        super.onCreate()
        instance = this

        // settings prefs (public settings)
        settingsPrefs = getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)

        // Try encrypted prefs, fall back to plain SharedPreferences (no invalid cast)
        try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            securePrefs = EncryptedSharedPreferences.create(
                "secure_prefs",
                masterKeyAlias,
                applicationContext,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (t: Throwable) {
            // If encrypted prefs creation fails, use normal SharedPreferences as a safe fallback.
            // Do NOT cast the result to EncryptedSharedPreferences (invalid cast).
            securePrefs = getSharedPreferences("secure_prefs", Context.MODE_PRIVATE)
        }

        // Schedule ALT message system initial work (if any)
        try {
            AltMessageScheduler.scheduleInitial(this)
        } catch (t: Throwable) {
            // Swallow scheduler errors to avoid crashing startup
            // We want the app to remain usable even if scheduling fails on certain OEM devices.
            t.printStackTrace()
        }
    }
}
