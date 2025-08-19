package com.twistedphone.alt
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class AltMessageReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        GlobalScope.launch { ctx.startService(Intent(ctx, AltMessageService::class.java)) }
    }
}
