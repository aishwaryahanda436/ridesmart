package com.ridesmart

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ridesmart.data.ProfileRepository
import com.ridesmart.ui.DisclosureScreen
import com.ridesmart.ui.ProfileSetupScreen
import com.ridesmart.ui.RideHistoryScreen
import com.ridesmart.ui.ProfitDashboardScreen
import com.ridesmart.ui.RideComparisonScreen
import com.ridesmart.ui.theme.RidesmartTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        val repository = ProfileRepository(this)

        setContent {
            RidesmartTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0F0F13)
                ) {
                    val scope = rememberCoroutineScope()
                    val isDisclosureAccepted by repository.isDisclosureAccepted.collectAsState(initial = null)
                    val navController = rememberNavController()

                    if (isDisclosureAccepted == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Color(0xFF3DDC84))
                        }
                    } else if (isDisclosureAccepted == false) {
                        DisclosureScreen(
                            onAccept = { scope.launch { repository.setDisclosureAccepted(true) } },
                            onDecline = { finish() }
                        )
                    } else {
                        NavHost(navController = navController, startDestination = "permissions") {
                            composable("permissions") {
                                PermissionSetupScreen(
                                    repository = repository,
                                    onEditProfile = { navController.navigate("profile") },
                                    onViewHistory = { navController.navigate("history") },
                                    onViewDashboard = { navController.navigate("dashboard") },
                                    onViewComparison = { navController.navigate("comparison") }
                                )
                            }
                            composable("profile") {
                                ProfileSetupScreen(
                                    onSaved = { navController.popBackStack() },
                                    onBack = { navController.popBackStack() }
                                )
                            }
                            composable("history") {
                                RideHistoryScreen(onBack = { navController.popBackStack() })
                            }
                            composable("dashboard") {
                                ProfitDashboardScreen(onBack = { navController.popBackStack() })
                            }
                            composable("comparison") {
                                RideComparisonScreen(onBack = { navController.popBackStack() })
                            }
                        }
                    }
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

@Composable
fun PermissionSetupScreen(
    repository: ProfileRepository,
    onEditProfile: () -> Unit,
    onViewHistory: () -> Unit,
    onViewDashboard: () -> Unit,
    onViewComparison: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val lifecycleOwner = LocalLifecycleOwner.current

    var refreshTrigger by remember { mutableIntStateOf(0) }
    val isOnline by repository.isOnline.collectAsState(initial = true)

    // Re-check permissions when user returns to app
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshTrigger++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Permission states derived from trigger
    val isOverlayGranted = remember(refreshTrigger) { Settings.canDrawOverlays(context) }
    val isAccessibilityGranted = remember(refreshTrigger) { isAccessibilityServiceEnabled(context) }
    val isNotificationListenerGranted = remember(refreshTrigger) { isNotificationListenerEnabled(context) }
    val isBatteryOptimized = remember(refreshTrigger) { isBatteryOptimizationIgnored(context) }

    val allGranted = isOverlayGranted && isAccessibilityGranted && isNotificationListenerGranted && isBatteryOptimized

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F13))
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(64.dp))

        HeaderSection(allGranted, isOnline)

        Spacer(modifier = Modifier.height(24.dp))

        OnlineOfflineToggle(
            isOnline = isOnline,
            allGranted = allGranted,
            onToggle = { scope.launch { repository.setOnlineStatus(!isOnline) } }
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            stringResource(R.string.required_permissions),
            color = Color(0xFF6B6B85),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
        )

        PermissionCard(
            title = stringResource(R.string.perm_overlay_title),
            status = if (isOverlayGranted) stringResource(R.string.active) else stringResource(R.string.required),
            icon = "🖼️",
            isGranted = isOverlayGranted,
            onClick = {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                context.startActivity(intent)
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        PermissionCard(
            title = stringResource(R.string.perm_accessibility_title),
            status = if (isAccessibilityGranted) stringResource(R.string.active) else stringResource(R.string.required),
            icon = "🔍",
            isGranted = isAccessibilityGranted,
            onClick = {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                context.startActivity(intent)
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        PermissionCard(
            title = stringResource(R.string.perm_notifications_title),
            status = if (isNotificationListenerGranted) stringResource(R.string.active) else stringResource(R.string.required),
            icon = "🔔",
            isGranted = isNotificationListenerGranted,
            onClick = {
                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                context.startActivity(intent)
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        PermissionCard(
            title = stringResource(R.string.perm_battery_title),
            status = if (isBatteryOptimized) stringResource(R.string.optimized) else stringResource(R.string.action_required),
            icon = "🔋",
            isGranted = isBatteryOptimized,
            onClick = {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(intent)
            }
        )

        if (Build.VERSION.SDK_INT >= 33 && !isAccessibilityGranted) {
            Spacer(modifier = Modifier.height(20.dp))
            RestrictedSettingsGuide()
        }

        Spacer(modifier = Modifier.height(32.dp))

        QuickActionButton(text = stringResource(R.string.edit_earning_targets), icon = "⚙️", onClick = onEditProfile)
        Spacer(modifier = Modifier.height(12.dp))
        QuickActionButton(text = stringResource(R.string.view_ride_history), icon = "📊", onClick = onViewHistory)
        Spacer(modifier = Modifier.height(12.dp))
        QuickActionButton(
            text    = stringResource(R.string.view_profit_dashboard),
            icon    = "💰",
            onClick = onViewDashboard
        )
        Spacer(modifier = Modifier.height(12.dp))
        QuickActionButton(
            text    = "Today's Comparison",
            icon    = "📊",
            onClick = onViewComparison
        )

        Spacer(modifier = Modifier.height(24.dp))
        ManufacturerBatteryGuide()
        Spacer(modifier = Modifier.height(40.dp))
    }
}

@Composable
fun OnlineOfflineToggle(
    isOnline: Boolean,
    allGranted: Boolean,
    onToggle: () -> Unit
) {
    val green = Color(0xFF3DDC84)
    val bgColor by animateColorAsState(if (isOnline && allGranted) green.copy(alpha = 0.1f) else Color(0xFF16161C), label = "bgColor")
    val strokeColor by animateColorAsState(if (isOnline && allGranted) green else Color(0xFF2A2A36), label = "strokeColor")

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .clickable(enabled = allGranted) { onToggle() },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        border = BorderStroke(1.dp, strokeColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.PowerSettingsNew,
                contentDescription = null,
                tint = if (!allGranted) Color(0xFF6B6B85) else if (isOnline) green else Color(0xFF6B6B85),
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isOnline && allGranted) stringResource(R.string.go_offline) else stringResource(R.string.go_online),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Text(
                    text = if (!allGranted) stringResource(R.string.perm_required_desc) else if (isOnline) stringResource(R.string.monitoring_active_desc) else stringResource(R.string.monitoring_paused_desc),
                    color = Color(0xFF6B6B85),
                    fontSize = 12.sp
                )
            }
            Switch(
                checked = isOnline && allGranted,
                onCheckedChange = { if (allGranted) onToggle() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.Black,
                    checkedTrackColor = green,
                    uncheckedThumbColor = Color(0xFF6B6B85),
                    uncheckedTrackColor = Color(0xFF2A2A36)
                ),
                enabled = allGranted
            )
        }
    }
}

@Composable
fun HeaderSection(isReady: Boolean, isOnline: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(
                    if (!isReady) Color(0xFFDC2626).copy(alpha = 0.1f)
                    else if (isOnline) Color(0xFF16A34A).copy(alpha = 0.1f)
                    else Color(0xFFEAB308).copy(alpha = 0.1f),
                    shape = RoundedCornerShape(24.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(if (!isReady) "🛡️" else if (isOnline) "🚀" else "⏸️", fontSize = 40.sp)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = if (!isReady) stringResource(R.string.setup_required) else stringResource(R.string.system_ready),
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = if (!isReady) stringResource(R.string.monitoring_inactive)
                   else if (isOnline) stringResource(R.string.monitoring_active)
                   else stringResource(R.string.monitoring_paused),
            color = if (!isReady) Color(0xFF6B6B85)
                    else if (isOnline) Color(0xFF3DDC84)
                    else Color(0xFFEAB308),
            fontSize = 14.sp
        )
    }
}

@Composable
fun PermissionCard(title: String, status: String, icon: String, isGranted: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
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
            if (isGranted) Text("✅", fontSize = 20.sp)
            else Text("➔", color = Color(0xFF3DDC84), fontWeight = FontWeight.Bold)
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
            Text(text = stringResource(R.string.restricted_settings_title), color = Color(0xFFEAB308), fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(8.dp))
            val stepStyle = MaterialTheme.typography.bodySmall.copy(color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
            Text(stringResource(R.string.restricted_settings_step1), style = stepStyle)
            Text(stringResource(R.string.restricted_settings_step2), style = stepStyle)
            Text(stringResource(R.string.restricted_settings_step3), style = stepStyle)
            Text(stringResource(R.string.restricted_settings_step4), style = stepStyle)
        }
    }
}

@Composable
fun ManufacturerBatteryGuide() {
    val manufacturer = Build.MANUFACTURER.lowercase()
    val guideText = when {
        manufacturer.contains("samsung") -> stringResource(R.string.battery_guide_samsung)
        manufacturer.contains("xiaomi") -> stringResource(R.string.battery_guide_xiaomi)
        else -> stringResource(R.string.battery_guide_generic)
    }
    Text(text = guideText, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), color = Color(0xFF6B6B85), fontSize = 11.sp, lineHeight = 16.sp)
}

fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expectedComponentName = "${context.packageName}/com.ridesmart.service.RideSmartService"
    val enabledServices = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    return enabledServices.contains(expectedComponentName)
}

fun isNotificationListenerEnabled(context: Context): Boolean {
    val pkgName = context.packageName
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    if (flat != null) {
        val names = flat.split(":").toTypedArray()
        for (name in names) {
            val cn = ComponentName.unflattenFromString(name)
            if (cn != null && cn.packageName == pkgName) {
                return true
            }
        }
    }
    return false
}

fun isBatteryOptimizationIgnored(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}
