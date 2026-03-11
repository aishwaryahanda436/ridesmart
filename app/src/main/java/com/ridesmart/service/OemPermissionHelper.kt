package com.ridesmart.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log

/**
 * Spec v2.0 Section 9: OEM Battery Optimization & Background Service Reliability.
 * Handles deep-linking to manufacturer-specific permission screens to prevent
 * service death on Xiaomi, Samsung, and BBK devices.
 */
object OemPermissionHelper {

    private const val TAG = "RideSmart"

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

    fun getBatteryOptimizationIntent(context: Context): Intent {
        val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        intent.data = Uri.parse("package:${context.packageName}")
        return intent
    }

    fun isSamsung(): Boolean = Build.MANUFACTURER.lowercase().contains("samsung")
    fun isXiaomi(): Boolean = Build.MANUFACTURER.lowercase().contains("xiaomi")
}
