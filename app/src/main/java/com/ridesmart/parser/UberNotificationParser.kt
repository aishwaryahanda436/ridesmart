package com.ridesmart.parser

import android.app.Notification
import android.util.Log
import java.util.ArrayList

object UberNotificationParser {

    private const val TAG = "RideSmart"

    /**
     * Attempts to extract ride data from an Uber push notification.
     * Uses three strategies in order of speed:
     *   1. Standard notification extras (fastest, may be empty)
     *   2. RemoteViews reflection (extracts from custom layout)
     *   3. Returns null → caller uses notification shade fallback
     */
    fun extractFromNotification(notification: Notification): List<String>? {

        // ── STRATEGY 1: Standard extras ──
        val extras = notification.extras
        val candidates = listOfNotNull(
            extras?.getCharSequence(Notification.EXTRA_TITLE),
            extras?.getCharSequence(Notification.EXTRA_TEXT),
            extras?.getCharSequence(Notification.EXTRA_BIG_TEXT),
            extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)
        ).map { it.toString() }.filter { it.isNotBlank() }

        val combined = candidates.joinToString(" ")
        val hasFare = Regex("₹\\s?\\d+").containsMatchIn(combined)
        val hasDist = Regex("\\d+\\.?\\d*\\s*[Kk][Mm]").containsMatchIn(combined) ||
                      Regex("\\d+\\s*[Mm]in").containsMatchIn(combined)

        if (hasFare && hasDist) {
            Log.d(TAG, "🚗 UBER extras parsed: $candidates")
            return candidates
        }

        // ── STRATEGY 2: RemoteViews reflection ──
        val remoteViews = try {
            @Suppress("DEPRECATION")
            notification.bigContentView ?: notification.contentView
        } catch (e: Exception) { null } ?: return null

        return try {
            val clazz = remoteViews.javaClass
            // Android 15+ uses "mActions", older uses "actions"
            val actionsField = clazz.declaredFields.firstOrNull {
                it.name == "mActions" || it.name == "actions"
            } ?: return null

            actionsField.isAccessible = true
            val actions = actionsField.get(remoteViews) as? ArrayList<*> ?: return null

            val extracted = mutableListOf<String>()

            for (action in actions) {
                if (action == null) continue
                val actionFields = action.javaClass.declaredFields

                val methodField = actionFields.firstOrNull {
                    it.name == "mMethodName" || it.name == "methodName"
                } ?: continue
                methodField.isAccessible = true
                val method = methodField.get(action)?.toString() ?: continue
                if (method != "setText") continue

                val valueField = actionFields.firstOrNull {
                    it.name == "mValue" || it.name == "value"
                } ?: continue
                valueField.isAccessible = true
                val value = valueField.get(action)?.toString() ?: continue
                if (value.isNotBlank()) extracted.add(value)
            }

            if (extracted.isEmpty()) null else {
                Log.d(TAG, "🚗 UBER reflection extracted: $extracted")
                extracted
            }
        } catch (e: Exception) {
            Log.d(TAG, "🚗 UBER reflection failed: ${e.message}")
            null
        }
    }
}
