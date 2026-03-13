package com.ridesmart.service

import android.app.Notification
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Primary early-trigger channel for ride offer notifications.
 *
 * Fires as soon as a notification is posted — before the popup renders on screen —
 * giving RideSmartService a ~400–600 ms head start for data extraction.
 *
 * This is additive: the existing onAccessibilityEvent path in RideSmartService
 * remains fully intact and serves as the secondary confirmation channel.
 */
class RideNotificationListener : NotificationListenerService() {

    companion object {
        const val TAG = "RideNotifListener"
        const val ACTION_RIDE_NOTIFICATION = "com.ridesmart.ACTION_RIDE_NOTIFICATION"
        const val EXTRA_PACKAGE   = "pkg"
        const val EXTRA_TITLE     = "title"
        const val EXTRA_TEXT      = "text"
        const val EXTRA_BIG_TEXT  = "bigText"
        const val EXTRA_TIMESTAMP = "timestamp"
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (pkg !in RideSmartService.SUPPORTED_PACKAGES) return

        val extras  = sbn.notification.extras
        val title   = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()    ?: ""
        val text    = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()     ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
        val combined = "$title $text $bigText"

        val isOffer = combined.contains("₹") ||
                      combined.contains("trip",    ignoreCase = true) ||
                      combined.contains("ride",    ignoreCase = true) ||
                      combined.contains("request", ignoreCase = true)

        if (!isOffer) return

        Log.d(TAG, "🔔 Early ride notification from $pkg: $title")

        val intent = Intent(ACTION_RIDE_NOTIFICATION).apply {
            setPackage(packageName)
            putExtra(EXTRA_PACKAGE,   pkg)
            putExtra(EXTRA_TITLE,     title)
            putExtra(EXTRA_TEXT,      text)
            putExtra(EXTRA_BIG_TEXT,  bigText)
            putExtra(EXTRA_TIMESTAMP, System.currentTimeMillis())
        }
        sendBroadcast(intent)
    }
}
