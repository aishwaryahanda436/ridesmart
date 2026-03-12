package com.ridesmart.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Spec v2.0 Section 9: OEM Battery Optimization & Background Service Reliability.
 * Handles deep-linking to manufacturer-specific permission screens to prevent
 * service death on Xiaomi, Samsung, and BBK devices.
 */
object OemPermissionHelper {

    fun getAutoStartIntent(context: Context): Intent? {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return when {
            manufacturer.contains("xiaomi") -> Intent().apply {
                component = ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
            }
            manufacturer.contains("oppo") -> Intent().apply {
                component = ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
            }
            manufacturer.contains("vivo") -> Intent().apply {
                component = ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
            }
            manufacturer.contains("oneplus") -> Intent().apply {
                component = ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")
            }
            else -> null
        }
    }

    /**
     * Returns an intent to the system battery optimization settings.
     * Complies with Play Store policy by using ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
     * instead of requesting the exemption directly.
     */
    fun getBatteryOptimizationIntent(context: Context): Intent {
        return Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }
}
