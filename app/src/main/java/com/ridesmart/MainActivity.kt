package com.ridesmart

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.ridesmart.ui.ProfileSetupScreen
import com.ridesmart.ui.RideHistoryScreen
import com.ridesmart.ui.theme.RidesmartTheme

class MainActivity : ComponentActivity() {

    private var refreshTrigger by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RidesmartTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0F0F13)
                ) {
                    var showProfileSetup by remember { mutableStateOf(false) }
                    var showHistory by remember { mutableStateOf(false) }

                    when {
                        showProfileSetup -> {
                            ProfileSetupScreen(onSaved = { showProfileSetup = false })
                        }
                        showHistory -> {
                            RideHistoryScreen(onBack = { showHistory = false })
                        }
                        else -> {
                            PermissionSetupScreen(
                                refreshTrigger = refreshTrigger,
                                onEditProfile = { showProfileSetup = true },
                                onViewHistory = { showHistory = true },
                                onRefresh = { refreshTrigger++ }
                            )
                        }
                    }
                }
            }
        }
        
        requestOverlayPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        refreshTrigger++
    }

    private fun requestOverlayPermissionIfNeeded() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }
}

@Composable
fun PermissionSetupScreen(
    refreshTrigger: Int,
    onEditProfile: () -> Unit,
    onViewHistory: () -> Unit,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { onRefresh() }
    )

    key(refreshTrigger) {
        val isOverlayGranted = Settings.canDrawOverlays(context)
        val isAccessibilityGranted = isAccessibilityServiceEnabled(context)
        val isNotificationGranted = isNotificationPermissionGranted(context)
        val allGranted = isOverlayGranted && isAccessibilityGranted && isNotificationGranted

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0F0F13))
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(64.dp))

            // ── DYNAMIC HEADER ──
            HeaderSection(allGranted)

            Spacer(modifier = Modifier.height(32.dp))

            // ── PERMISSION LIST ──
            Text(
                "REQUIRED PERMISSIONS",
                color = Color(0xFF6B6B85),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
            )

            PermissionCard(
                title = "Overlay Window",
                status = if (isOverlayGranted) "Active" else "Required",
                icon = "🖼️",
                isGranted = isOverlayGranted,
                onClick = {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                    context.startActivity(intent)
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            PermissionCard(
                title = "Accessibility Service",
                status = if (isAccessibilityGranted) "Active" else "Required",
                icon = "🔍",
                isGranted = isAccessibilityGranted,
                onClick = {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    context.startActivity(intent)
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            PermissionCard(
                title = "Notifications",
                status = if (isNotificationGranted) "Active" else "Required",
                icon = "🔔",
                isGranted = isNotificationGranted,
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    }
                }
            )

            if (Build.VERSION.SDK_INT >= 33 && !isAccessibilityGranted) {
                Spacer(modifier = Modifier.height(20.dp))
                RestrictedSettingsGuide()
            }

            Spacer(modifier = Modifier.height(32.dp))

            // ── ACTION BUTTONS ──
            QuickActionButton(
                text = "Edit Earning Targets",
                icon = "⚙️",
                onClick = onEditProfile
            )

            Spacer(modifier = Modifier.height(12.dp))

            QuickActionButton(
                text = "View Ride History",
                icon = "📊",
                onClick = onViewHistory
            )

            Spacer(modifier = Modifier.height(24.dp))

            ManufacturerBatteryGuide()

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

@Composable
fun HeaderSection(isReady: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(
                    if (isReady) Color(0xFF16A34A).copy(alpha = 0.1f) 
                    else Color(0xFFDC2626).copy(alpha = 0.1f),
                    shape = RoundedCornerShape(24.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(if (isReady) "🚀" else "🛡️", fontSize = 40.sp)
        }
        
        Spacer(Modifier.height(16.dp))
        
        Text(
            text = if (isReady) "System Ready" else "Setup Required",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
        
        Text(
            text = if (isReady) "Monitoring for ride offers..." else "Grant permissions to begin monitoring",
            color = if (isReady) Color(0xFF3DDC84) else Color(0xFF6B6B85),
            fontSize = 14.sp
        )
    }
}

@Composable
fun PermissionCard(title: String, status: String, icon: String, isGranted: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { if (!isGranted) onClick() },
        colors = CardDefaults.cardColors(containerColor = Color(0xFF16161C)),
        shape = RoundedCornerShape(16.dp),
        border = if (!isGranted) BorderStroke(1.dp, Color(0xFF2A2A36)) else null
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(icon, fontSize = 24.sp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(status, color = if (isGranted) Color(0xFF3DDC84) else Color(0xFFDC2626), fontSize = 12.sp)
            }
            if (isGranted) {
                Text("✅", fontSize = 20.sp)
            } else {
                Text("➔", color = Color(0xFF3DDC84), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun QuickActionButton(text: String, icon: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A36)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(icon, fontSize = 18.sp)
            Spacer(Modifier.width(12.dp))
            Text(text, color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun RestrictedSettingsGuide() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A0F00)),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color(0xFFEAB308).copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "If Accessibility is greyed out:",
                color = Color(0xFFEAB308),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            val stepStyle = MaterialTheme.typography.bodySmall.copy(color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
            Text("1. Open Settings → Apps → RideSmart", style = stepStyle)
            Text("2. Tap ⋮ (three dots) in top right corner", style = stepStyle)
            Text("3. Tap 'Allow restricted settings'", style = stepStyle)
            Text("4. Now enable Accessibility Service", style = stepStyle)
        }
    }
}

@Composable
fun ManufacturerBatteryGuide() {
    val manufacturer = Build.MANUFACTURER.lowercase()
    val guideText = when {
        manufacturer.contains("samsung") -> 
            "Samsung Fix: Settings → Battery → Background usage → Never sleeping apps → Add RideSmart"
        manufacturer.contains("xiaomi") -> 
            "Xiaomi Fix: Settings → Apps → RideSmart → Battery Saver → No Restrictions"
        else -> 
            "Battery Fix: Settings → Apps → RideSmart → Battery → Unrestricted"
    }

    Text(
        text = guideText,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        color = Color(0xFF6B6B85),
        fontSize = 11.sp,
        lineHeight = 16.sp
    )
}

fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expectedComponentName = "${context.packageName}/com.ridesmart.service.RideSmartService"
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabledServices.contains(expectedComponentName)
}

fun isNotificationPermissionGranted(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.areNotificationsEnabled()
    }
}
