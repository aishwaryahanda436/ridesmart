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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ridesmart.service.OemPermissionHelper
import com.ridesmart.ui.*
import com.ridesmart.ui.theme.*

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RidesmartTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBackground
                ) {
                    RideSmartApp()
                }
            }
        }

        requestOverlayPermissionIfNeeded()
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

/**
 * App navigation — state-based routing between screens.
 * Flow: Splash → Profile Setup (if first time) → Main Driver Screen
 */
@Composable
fun RideSmartApp(profileViewModel: ProfileViewModel = viewModel()) {
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Splash) }
    val hasCompletedSetup by profileViewModel.hasCompletedSetup.collectAsState()

    // Handle initial routing after splash
    val onSplashFinished = {
        if (!hasCompletedSetup) {
            currentScreen = Screen.ProfileSetup
        } else {
            currentScreen = Screen.Main
        }
    }

    when (currentScreen) {
        Screen.Splash -> SplashScreen(
            onFinished = onSplashFinished
        )
        Screen.Main -> MainDriverScreen(
            onNavigateHistory = { currentScreen = Screen.History },
            onNavigateDashboard = { currentScreen = Screen.Dashboard },
            onNavigateSettings = { currentScreen = Screen.Settings },
            onNavigatePermissions = { currentScreen = Screen.Permissions }
        )
        Screen.History -> RideHistoryScreen(
            onBack = { currentScreen = Screen.Main }
        )
        Screen.Dashboard -> DashboardScreen(
            onBack = { currentScreen = Screen.Main }
        )
        Screen.Settings -> SettingsScreen(
            onBack = { currentScreen = Screen.Main }
        )
        Screen.Permissions -> PermissionSetupScreen(
            onBack = { currentScreen = Screen.Main }
        )
        Screen.ProfileSetup -> ProfileSetupScreen(
            onSaved = { currentScreen = Screen.Main },
            viewModel = profileViewModel
        )
    }
}

/** Navigation destinations */
sealed class Screen {
    data object Splash : Screen()
    data object Main : Screen()
    data object History : Screen()
    data object Dashboard : Screen()
    data object Settings : Screen()
    data object Permissions : Screen()
    data object ProfileSetup : Screen()
}

// ═══════════════════════════════════════════════════════════════════
// Permission Setup Screen
// ═══════════════════════════════════════════════════════════════════

@Composable
fun PermissionSetupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var refreshTrigger by remember { mutableIntStateOf(0) }
    val scrollState = rememberScrollState()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { refreshTrigger++ }
    )

    key(refreshTrigger) {
        val isOverlayGranted = Settings.canDrawOverlays(context)
        val isAccessibilityGranted = isAccessibilityServiceEnabled(context)
        val isNotificationGranted = isNotificationPermissionGranted(context)
        val allGranted = isOverlayGranted && isAccessibilityGranted && isNotificationGranted

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(DarkBackground)
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── TOP BAR ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 52.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) {
                    Text("← Back", color = RideGreen, fontSize = 15.sp)
                }
                Spacer(Modifier.weight(1f))
                Text("Permissions", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Box(Modifier.width(60.dp))
            }

            Spacer(modifier = Modifier.height(32.dp))

            HeaderSection(allGranted)

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                "REQUIRED PERMISSIONS",
                color = TextSecondary,
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
                    context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")))
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            PermissionCard(
                title = "Accessibility Service",
                status = if (isAccessibilityGranted) "Active" else "Required",
                icon = "🔍",
                isGranted = isAccessibilityGranted,
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        })
                    }
                }
            )

            if (Build.VERSION.SDK_INT >= 33 && !isAccessibilityGranted) {
                Spacer(modifier = Modifier.height(20.dp))
                RestrictedSettingsGuide()
            }

            Spacer(modifier = Modifier.height(24.dp))
            
            // ── Spec v2.0: OEM Setup Wizard ──
            OemWizardSection()

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

@Composable
fun OemWizardSection() {
    val context = LocalContext.current
    val manufacturer = Build.MANUFACTURER.uppercase()
    val autoStartIntent = OemPermissionHelper.getAutoStartIntent(context)

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "$manufacturer OPTIMIZATION",
            color = TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkCard),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, DarkBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Prevent Service Death",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Text(
                    "To prevent $manufacturer from killing RideSmart in the background, you must manually disable battery optimization.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        context.startActivity(OemPermissionHelper.getBatteryOptimizationIntent(context))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = RideGreen.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("1. Open Battery Settings", color = RideGreen, fontSize = 13.sp)
                }
                
                Text(
                    "Once settings open, find RideSmart and set to 'Don't Optimize' or 'Unrestricted'.",
                    color = SignalYellow,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                )

                if (autoStartIntent != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            try {
                                context.startActivity(autoStartIntent)
                            } catch (e: Exception) {
                                // Fallback or silent fail
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = RideGreen.copy(alpha = 0.15f)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("2. Enable Auto-Start", color = RideGreen, fontSize = 13.sp)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                
                ManufacturerBatteryGuide()
            }
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
                    if (isReady) SignalGreen.copy(alpha = 0.1f)
                    else SignalRed.copy(alpha = 0.1f),
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
            color = if (isReady) RideGreen else TextSecondary,
            fontSize = 14.sp
        )
    }
}

@Composable
fun PermissionCard(title: String, status: String, icon: String, isGranted: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { if (!isGranted) onClick() },
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(16.dp),
        border = if (!isGranted) BorderStroke(1.dp, DarkBorder) else null
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(icon, fontSize = 24.sp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(status, color = if (isGranted) RideGreen else SignalRed, fontSize = 12.sp)
            }
            if (isGranted) {
                Text("✅", fontSize = 20.sp)
            } else {
                Text("➔", color = RideGreen, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun RestrictedSettingsGuide() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A0F00)),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, SignalYellow.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "If Accessibility is greyed out:",
                color = SignalYellow,
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
        manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") ->
            "Xiaomi Fix: App Info → Battery Saver → No Restrictions"
        manufacturer.contains("oppo") || manufacturer.contains("realme") ->
            "Oppo/Realme Fix: App Info → Battery → Allow Background Activity"
        manufacturer.contains("vivo") ->
            "Vivo Fix: Settings → Battery → High Background Power Consumption → Enable RideSmart"
        else -> "Ensure battery optimization is disabled for RideSmart to function in the background."
    }

    Text(
        text = guideText,
        color = TextMuted,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        modifier = Modifier.padding(top = 8.dp)
    )
}
