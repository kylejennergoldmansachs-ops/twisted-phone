package com.twistedphone

import android.app.Application
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
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
        
        // Initialize encrypted preferences
        val masterKey = MasterKey.Builder(this)
            .setKeyGenParameterSpec(
                KeyGenParameterSpec.Builder(
                    MasterKey.DEFAULT_MASTER_KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            )
            .build()

        securePrefs = EncryptedSharedPreferences.create(
            this,
            "secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ) as EncryptedSharedPreferences

        settingsPrefs = getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        
        try {
            AltMessageScheduler.scheduleInitial()
        } catch (e: SecurityException) {
            Log.e("TwistedApp", "Failed to schedule initial alarm: ${e.message}")
        }
    }
}
