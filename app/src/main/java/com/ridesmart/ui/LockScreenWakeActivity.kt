package com.ridesmart.ui

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.ridesmart.service.RideSmartService

class LockScreenWakeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Tell Android this Activity can show on lock screen and turn screen on
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        Log.d(RideSmartService.TAG, "🔆 LockScreenWakeActivity fired — screen should be on")
        // Immediately finish — this Activity has no UI, it just wakes the screen
        finish()
    }
}
