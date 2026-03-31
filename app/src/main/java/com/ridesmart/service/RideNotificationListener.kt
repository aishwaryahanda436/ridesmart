package com.ridesmart.service

import android.app.Notification
import android.app.NotificationManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class RideNotificationListener : NotificationListenerService() {

    private val listenerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var lastOfferKey = ""
    private var lastOfferTime = 0L

    companion object {
        private const val TAG = "RideSmartNotif"
        private const val DEDUPE_MS = 2500L
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        if (!RideSmartService.SUPPORTED_PACKAGES.any { pkg.contains(it) }) return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        if (isIgnorable(sbn)) return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim() ?: ""
        val body = if (bigText.length > text.length) bigText else text

        if (title.isBlank() && body.isBlank()) return
        if (!isOfferHeuristic(pkg, title, body)) return

        val currentKey = "${pkg}_${title}_${body.take(30)}"
        val now = System.currentTimeMillis()
        if (currentKey == lastOfferKey && (now - lastOfferTime) < DEDUPE_MS) return
        
        lastOfferKey = currentKey
        lastOfferTime = now

        Log.d(TAG, "[$pkg] Valid offer notification detected: $title")

        listenerScope.launch {
            RideSmartService.externalNotificationFlow.emit(
                RideSmartService.NotificationEvent.Posted(pkg, title, body)
            )
        }
    }

    private fun isIgnorable(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        
        // BUG FIX: Only block foreground service notifications.
        // MINIMUM VIABLE PATCH: Do NOT block ongoing event notifs — Uber ride offers are ongoing by design.
        if (n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return true
        
        val ranking = Ranking()
        if (currentRanking != null && currentRanking.getRanking(sbn.key, ranking)) {
            // Only process Default or High importance notifications
            if (ranking.importance < NotificationManager.IMPORTANCE_DEFAULT) return true
        }
        
        return false
    }

    private fun isOfferHeuristic(pkg: String, title: String, body: String): Boolean {
        val combined = "$title $body".lowercase()
        if (pkg.contains("uber")) {
            val isOnlineNotif = (combined.contains("online") || combined.contains("finding")) && 
                                !combined.contains("₹") && !combined.contains("rs")
            if (isOnlineNotif) return false
        }

        val hasMoney = combined.contains("₹") || combined.contains("rs") || combined.contains("rupees")
        val hasRide  = combined.contains("request") || combined.contains("trip") || 
                       combined.contains("offer") || combined.contains("ride") || 
                       combined.contains("match") || combined.contains("new")
        
        return hasMoney || hasRide
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        if (!RideSmartService.SUPPORTED_PACKAGES.any { pkg.contains(it) }) return

        listenerScope.launch {
            RideSmartService.externalNotificationFlow.emit(
                RideSmartService.NotificationEvent.Removed(pkg)
            )
        }
    }

    override fun onDestroy() {
        listenerScope.cancel()
        super.onDestroy()
    }
}
