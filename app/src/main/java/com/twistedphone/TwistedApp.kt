package com.twistedphone
import android.app.Application
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import android.content.SharedPreferences

class TwistedApp : Application() {
    companion object { lateinit var instance: TwistedApp; private set }
    lateinit var securePrefs: SharedPreferences
    lateinit var settingsPrefs: SharedPreferences

    override fun onCreate() {
        super.onCreate()
        instance = this
        val mk = MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        securePrefs = EncryptedSharedPreferences.create(this,"twisted_secure",mk,EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        settingsPrefs = getSharedPreferences("twisted_settings", MODE_PRIVATE)
        com.twistedphone.alt.AltMessageScheduler.scheduleInitial(this) // schedule on app create
    }
}
