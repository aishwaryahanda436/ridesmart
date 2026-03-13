package com.ridesmart.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridesmart.data.ProfileRepository
import com.ridesmart.data.RideEntry
import com.ridesmart.data.RideHistoryRepository
import com.ridesmart.isAccessibilityServiceEnabled
import com.ridesmart.model.Signal
import com.ridesmart.ui.theme.*
import java.util.*

@Composable
fun MainDriverScreen(
    onNavigateHistory: () -> Unit,
    onNavigateDashboard: () -> Unit,
    onNavigateSettings: () -> Unit,
    onNavigatePermissions: () -> Unit
) {
    val context = LocalContext.current
    val repo = remember { RideHistoryRepository(context) }
    val profileRepo = remember { ProfileRepository(context) }
    val history by repo.historyFlow.collectAsState(initial = emptyList())
    val driverName by profileRepo.driverNameFlow.collectAsState(initial = "")
    val isServiceActive = remember(Unit) { isAccessibilityServiceEnabled(context) }
    val isOverlayGranted = remember(Unit) { Settings.canDrawOverlays(context) }

    // Driver stats
    val totalRides = history.size
    val acceptedRides = history.count { it.signal == Signal.GREEN }
    val rejectedRides = history.count { it.signal == Signal.RED }

    val todayRides = remember(history) {
        val todayCal = Calendar.getInstance()
        history.filter { entry ->
            val cal = Calendar.getInstance().apply { timeInMillis = entry.timestampMs }
            cal.get(Calendar.YEAR) == todayCal.get(Calendar.YEAR) &&
            cal.get(Calendar.DAY_OF_YEAR) == todayCal.get(Calendar.DAY_OF_YEAR)
        }
    }

    val scrollState = rememberScrollState()

    Scaffold(
        containerColor = DarkBackground,
        bottomBar = {
            ModernBottomNav(
                onHistory = onNavigateHistory,
                onDashboard = onNavigateDashboard,
                onSettings = onNavigateSettings,
                onPermissions = onNavigatePermissions
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp)
        ) {
            Spacer(Modifier.height(32.dp))

            // ── TOP PROFILE & STATUS ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "Welcome Back,",
                        color = TextSecondary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        driverName.ifBlank { "Captain" },
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                // Status Toggle Style Badge
                StatusBadge(isActive = isServiceActive, onClick = onNavigatePermissions)
            }

            Spacer(Modifier.height(32.dp))

            // ── ACTIVE MONITORING CARD ──
            ActiveServiceCard(isActive = isServiceActive, onSetup = onNavigatePermissions)

            Spacer(Modifier.height(24.dp))

            // ── SYSTEM STATUS ──
            Text(
                "SYSTEM STATUS",
                color = TextMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            Spacer(Modifier.height(12.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    SystemStatusRow("Accessibility Service", isServiceActive)
                    Spacer(Modifier.height(10.dp))
                    SystemStatusRow("Overlay Permission", isOverlayGranted)
                    Spacer(Modifier.height(10.dp))
                    SystemStatusRow("OCR Engine", true) // OCR via ML Kit is always available
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── MONITORING CONTROLS ──
            Text(
                "MONITORING",
                color = TextMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            Spacer(Modifier.height(12.dp))

            MonitoringButtons(
                isServiceActive = isServiceActive,
                context = context,
                onNavigatePermissions = onNavigatePermissions
            )

            Spacer(Modifier.height(24.dp))

            // ── DRIVER STATS ──
            Text(
                "DRIVER STATS",
                color = TextMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            Spacer(Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatBox(
                    label = "Analyzed",
                    value = "$totalRides",
                    icon = Icons.Default.Analytics,
                    color = RideBlue,
                    modifier = Modifier.weight(1f)
                )
                StatBox(
                    label = "Accepted",
                    value = "$acceptedRides",
                    icon = Icons.Default.ThumbUp,
                    color = SignalGreen,
                    modifier = Modifier.weight(1f)
                )
                StatBox(
                    label = "Rejected",
                    value = "$rejectedRides",
                    icon = Icons.Default.ThumbDown,
                    color = SignalRed,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(32.dp))

            // ── TODAY'S OVERVIEW ──
            Text(
                "TODAY'S METRICS",
                color = TextMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            Spacer(Modifier.height(16.dp))

            SummaryGrid(todayRides)

            Spacer(Modifier.height(32.dp))

            // ── RECENT PERFORMANCE ──
            if (todayRides.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "LATEST OFFER",
                        color = TextMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                    Text(
                        "View All",
                        color = RideGreen,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable { onNavigateHistory() }
                    )
                }
                Spacer(Modifier.height(16.dp))
                ModernRideCard(todayRides.first())
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
fun StatusBadge(isActive: Boolean, onClick: () -> Unit) {
    val color = if (isActive) SignalGreen else SignalRed
    Surface(
        modifier = Modifier
            .clip(CircleShape)
            .clickable { onClick() }
            .border(1.dp, color.copy(alpha = 0.3f), CircleShape),
        color = color.copy(alpha = 0.1f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(color, CircleShape)
            )
            Text(
                if (isActive) "ONLINE" else "OFFLINE",
                color = color,
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.sp
            )
        }
    }
}

@Composable
fun MonitoringButtons(
    isServiceActive: Boolean,
    context: Context,
    onNavigatePermissions: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = {
                if (!isServiceActive) {
                    onNavigatePermissions()
                }
            },
            enabled = !isServiceActive,
            modifier = Modifier
                .weight(1f)
                .height(52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = RideGreen,
                disabledContainerColor = RideGreen.copy(alpha = 0.3f)
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            val contentColor = if (!isServiceActive) Color.White else Color.White.copy(alpha = 0.5f)
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = contentColor
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Start Monitoring",
                color = contentColor,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }

        OutlinedButton(
            onClick = {
                if (isServiceActive) {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            },
            enabled = isServiceActive,
            modifier = Modifier
                .weight(1f)
                .height(52.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = SignalRed,
                disabledContentColor = TextMuted
            ),
            border = BorderStroke(
                1.dp,
                if (isServiceActive) SignalRed.copy(alpha = 0.5f) else DarkBorder
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(
                Icons.Default.Stop,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Stop Monitoring",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
fun ActiveServiceCard(isActive: Boolean, onSetup: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.dp, if (isActive) RideGreen.copy(alpha = 0.2f) else DarkBorder)
    ) {
        Row(
            modifier = Modifier.padding(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(
                        Brush.linearGradient(
                            listOf(RideGreen.copy(alpha = 0.2f), Color.Transparent)
                        ),
                        shape = RoundedCornerShape(20.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (isActive) Icons.Default.Radar else Icons.Default.Shield,
                    contentDescription = null,
                    tint = if (isActive) RideGreen else SignalRed,
                    modifier = Modifier.size(32.dp)
                )
            }
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (isActive) "Radar Active" else "Engine Paused",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    if (isActive) "Scanning for high-profit rides..." else "Permissions required to start",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            }
            if (!isActive) {
                IconButton(
                    onClick = onSetup,
                    modifier = Modifier.background(RideGreen, CircleShape)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Color.Black)
                }
            }
        }
    }
}

@Composable
fun SummaryGrid(rides: List<RideEntry>) {
    val totalProfit = rides.sumOf { it.netProfit }
    val totalKm = rides.sumOf { it.rideKm }
    val avgEpk = if (totalKm > 0) totalProfit / totalKm else 0.0

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        StatBox(
            label = "Profit",
            value = "₹${"%.0f".format(totalProfit)}",
            icon = Icons.Default.Payments,
            color = RideGreen,
            modifier = Modifier.weight(1f)
        )
        StatBox(
            label = "Avg EPK",
            value = "₹${"%.1f".format(avgEpk)}",
            icon = Icons.Default.Speed,
            color = RideBlue,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun StatBox(label: String, value: String, icon: ImageVector, color: Color, modifier: Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(16.dp))
            Text(value, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(label, color = TextSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
fun ModernRideCard(entry: RideEntry) {
    val color = when (entry.signal) {
        Signal.GREEN -> SignalGreen
        Signal.YELLOW -> SignalYellow
        Signal.RED -> SignalRed
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.2f))
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    entry.platform.uppercase(),
                    color = color,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.sp
                )
                Text(
                    "₹${"%.0f".format(entry.baseFare)} Offer",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(Modifier.height(20.dp))
            
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "₹${"%.0f".format(entry.netProfit)}",
                    color = Color.White,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Black
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Est. Profit",
                    color = TextSecondary,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
            
            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = DarkBorder)
            Spacer(Modifier.height(20.dp))
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MetricItem("Distance", "${"%.1f".format(entry.rideKm)} km", Icons.Default.Route)
                MetricItem("Pickup", "${"%.1f".format(entry.pickupKm)} km", Icons.Default.PersonPinCircle)
                MetricItem("Profit/km", "₹${"%.1f".format(entry.earningPerKm)}", Icons.AutoMirrored.Filled.TrendingUp)
            }
        }
    }
}

@Composable
fun MetricItem(label: String, value: String, icon: ImageVector) {
    Column(horizontalAlignment = Alignment.Start) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = TextMuted, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Text(value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun ModernBottomNav(
    onHistory: () -> Unit,
    onDashboard: () -> Unit,
    onSettings: () -> Unit,
    onPermissions: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp)
            .clip(RoundedCornerShape(24.dp)),
        color = DarkSurface,
        tonalElevation = 8.dp,
        border = BorderStroke(1.dp, DarkBorder)
    ) {
        Row(
            modifier = Modifier
                .padding(vertical = 8.dp, horizontal = 12.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavIcon(Icons.Default.Home, "Home", true, {})
            NavIcon(Icons.Default.BarChart, "Dashboard", false, onDashboard)
            NavIcon(Icons.Default.History, "History", false, onHistory)
            NavIcon(Icons.Default.Shield, "Permissions", false, onPermissions)
            NavIcon(Icons.Default.Settings, "Settings", false, onSettings)
        }
    }
}

@Composable
fun NavIcon(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val color = if (active) RideGreen else TextMuted
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 10.dp)
    ) {
        Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(24.dp))
        if (active) {
            Spacer(Modifier.height(4.dp))
            Box(modifier = Modifier.size(4.dp).background(RideGreen, CircleShape))
        }
    }
}

@Composable
fun SystemStatusRow(label: String, isActive: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    if (isActive) SignalGreen else SignalRed,
                    CircleShape
                )
        )
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            color = TextPrimary,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f)
        )
        Text(
            if (isActive) "Active" else "Inactive",
            color = if (isActive) SignalGreen else SignalRed,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
