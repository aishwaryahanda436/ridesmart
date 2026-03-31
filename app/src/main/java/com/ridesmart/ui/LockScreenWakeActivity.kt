package com.ridesmart.ui

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import com.ridesmart.service.RideSmartService

class LockScreenWakeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Tell Android this Activity can show on lock screen and turn screen on
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        Log.d(RideSmartService.TAG, "🔆 LockScreenWakeActivity fired — screen should be on")
        // Immediately finish — this Activity has no UI, it just wakes the screen
        finish()
    }
}
