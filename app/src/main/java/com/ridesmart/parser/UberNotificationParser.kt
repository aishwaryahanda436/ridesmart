package com.ridesmart.parser

import android.app.Notification
import android.util.Log

/**
 * UberNotificationParser is now simplified to only detect Uber notification signals.
 * Reflection-based extraction is removed to ensure future Android compatibility.
 * Actual data extraction for Uber is handled by UberOcrEngine.
 */
object UberNotificationParser {

    private const val TAG = "RideSmart"

    /**
     * Checks if the notification is a valid Uber ride offer signal.
     * We don't extract data here anymore; we just signal RideSmartService to trigger OCR.
     */
    fun isUberRideOffer(notification: Notification): Boolean {
        val extras = notification.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text  = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()  ?: ""
        
        // Uber driver notifications often contain these keywords or patterns
        val combined = "$title $text".lowercase()
        val isUber = combined.contains("uber") || combined.contains("request")
        
        // If it looks like a ride offer (has currency symbol or "min"), signal it
        val hasFareSignal = combined.contains("₹") || combined.contains("rs.")
        val hasTimeSignal = combined.contains("min") || combined.contains("sec")
        
        return isUber && (hasFareSignal || hasTimeSignal)
    }
}
