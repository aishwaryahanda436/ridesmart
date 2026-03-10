package com.ridesmart.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ridesmart.service.RideSmartService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == "android.intent.action.QUICKBOOT_POWERON") {
            Log.d("RideSmart", "🔄 Boot completed — restarting RideSmart")
            
            // RideSmartForegroundService does not exist in the project,
            // so we start RideSmartService as a foreground service as requested.
            val serviceIntent = Intent(context, RideSmartService::class.java)
            context.startForegroundService(serviceIntent)
        }
    }
}
