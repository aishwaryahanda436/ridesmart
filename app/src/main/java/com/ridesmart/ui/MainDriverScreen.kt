package com.ridesmart.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridesmart.data.RideEntry
import com.ridesmart.data.RideHistoryRepository
import com.ridesmart.isAccessibilityServiceEnabled
import com.ridesmart.model.Signal
import com.ridesmart.ui.theme.*
import java.util.*

/**
 * Main Driver Screen — the app's primary hub.
 * Shows monitoring status, today's ride stats, and navigation to other screens.
 */
@Composable
fun MainDriverScreen(
    onNavigateHistory: () -> Unit,
    onNavigateDashboard: () -> Unit,
    onNavigateSettings: () -> Unit,
    onNavigatePermissions: () -> Unit
) {
    val context = LocalContext.current
    val repo = remember { RideHistoryRepository(context) }
    val history by repo.historyFlow.collectAsState(initial = emptyList())
    val isServiceActive = remember(Unit) { isAccessibilityServiceEnabled(context) }

    // Today's rides
    val todayRides = remember(history) {
        val todayCal = Calendar.getInstance()
        history.filter { entry ->
            val cal = Calendar.getInstance().apply { timeInMillis = entry.timestampMs }
            cal.get(Calendar.YEAR) == todayCal.get(Calendar.YEAR) &&
            cal.get(Calendar.DAY_OF_YEAR) == todayCal.get(Calendar.DAY_OF_YEAR)
        }
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(56.dp))

        // ── HEADER ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    "RideSmart",
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    "Driver Dashboard",
                    color = TextSecondary,
                    fontSize = 14.sp
                )
            }
            // Status indicator
            StatusBadge(isActive = isServiceActive, onClick = {
                if (!isServiceActive) onNavigatePermissions()
            })
        }

        Spacer(Modifier.height(24.dp))

        // ── MONITORING STATUS CARD ──
        MonitoringStatusCard(isActive = isServiceActive, onSetup = onNavigatePermissions)

        Spacer(Modifier.height(24.dp))

        // ── TODAY'S STATS ──
        Text(
            "TODAY'S PERFORMANCE",
            color = TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Spacer(Modifier.height(12.dp))

        TodayStatsGrid(todayRides)

        Spacer(Modifier.height(24.dp))

        // ── LATEST RIDE ──
        if (todayRides.isNotEmpty()) {
            Text(
                "LATEST RIDE",
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Spacer(Modifier.height(12.dp))
            LatestRideCard(todayRides.first())
            Spacer(Modifier.height(24.dp))
        }

        // ── NAVIGATION ACTIONS ──
        Text(
            "QUICK ACTIONS",
            color = TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Spacer(Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NavActionCard(
                icon = "📊",
                label = "Dashboard",
                onClick = onNavigateDashboard,
                modifier = Modifier.weight(1f)
            )
            NavActionCard(
                icon = "📋",
                label = "History",
                onClick = onNavigateHistory,
                modifier = Modifier.weight(1f)
            )
            NavActionCard(
                icon = "⚙️",
                label = "Settings",
                onClick = onNavigateSettings,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(40.dp))
    }
}

@Composable
fun StatusBadge(isActive: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = if (isActive) SignalGreen.copy(alpha = 0.12f) else SignalRed.copy(alpha = 0.12f),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                if (isActive) "●" else "●",
                color = if (isActive) RideGreen else SignalRed,
                fontSize = 10.sp
            )
            Text(
                if (isActive) "Active" else "Inactive",
                color = if (isActive) RideGreen else SignalRed,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun MonitoringStatusCard(isActive: Boolean, onSetup: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) SignalGreen.copy(alpha = 0.08f) else DarkCard
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(
                        if (isActive) RideGreen.copy(alpha = 0.15f) else SignalRed.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(16.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(if (isActive) "🚀" else "🛡️", fontSize = 28.sp)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (isActive) "System Active" else "Setup Required",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (isActive) "Monitoring ride offers in real-time"
                    else "Grant permissions to start monitoring",
                    color = if (isActive) RideGreen else TextSecondary,
                    fontSize = 13.sp
                )
            }
            if (!isActive) {
                TextButton(onClick = onSetup) {
                    Text("Setup", color = RideGreen, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun TodayStatsGrid(rides: List<RideEntry>) {
    val totalRides = rides.size
    val totalProfit = rides.sumOf { it.netProfit }
    val totalEarnings = rides.sumOf { it.baseFare }
    val avgProfit = if (totalRides > 0) totalProfit / totalRides else 0.0
    val greenCount = rides.count { it.signal == Signal.GREEN }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard(
                label = "RIDES",
                value = "$totalRides",
                icon = "🏍️",
                modifier = Modifier.weight(1f)
            )
            StatCard(
                label = "NET PROFIT",
                value = "₹${"%.0f".format(totalProfit)}",
                icon = "💰",
                valueColor = if (totalProfit > 0) RideGreen else SignalRed,
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard(
                label = "GROSS FARE",
                value = "₹${"%.0f".format(totalEarnings)}",
                icon = "📈",
                modifier = Modifier.weight(1f)
            )
            StatCard(
                label = "AVG PROFIT",
                value = "₹${"%.0f".format(avgProfit)}",
                icon = "⭐",
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard(
                label = "GREEN RIDES",
                value = "$greenCount",
                icon = "✅",
                valueColor = RideGreen,
                modifier = Modifier.weight(1f)
            )
            StatCard(
                label = "ACCEPTANCE",
                value = if (totalRides > 0) "${(greenCount * 100 / totalRides)}%" else "—",
                icon = "📊",
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun StatCard(
    label: String,
    value: String,
    icon: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Color.White
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(icon, fontSize = 16.sp)
                Spacer(Modifier.width(8.dp))
                Text(label, color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text(value, color = valueColor, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}

@Composable
fun LatestRideCard(entry: RideEntry) {
    val (signalColor, signalLabel) = when (entry.signal) {
        Signal.GREEN -> Pair(SignalGreen, "ACCEPT")
        Signal.YELLOW -> Pair(SignalYellow, "BORDERLINE")
        Signal.RED -> Pair(SignalRed, "SKIP")
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    entry.platform,
                    color = TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                Surface(
                    color = signalColor.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        signalLabel,
                        color = signalColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("FARE", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text("₹${"%.0f".format(entry.baseFare)}", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f)) {
                    Text("NET PROFIT", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "₹${"%.0f".format(entry.netProfit)}",
                        color = signalColor,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text("₹/KM", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text("₹${"%.1f".format(entry.earningPerKm)}", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("RIDE", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text("${"%.1f".format(entry.rideKm)}km", color = TextPrimary, fontSize = 14.sp)
                }
                Column(Modifier.weight(1f)) {
                    Text("PICKUP", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text("${"%.1f".format(entry.pickupKm)}km", color = TextPrimary, fontSize = 14.sp)
                }
                Column(Modifier.weight(1f)) {
                    Text("FUEL COST", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text("₹${"%.0f".format(entry.fuelCost)}", color = TextPrimary, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
fun NavActionCard(icon: String, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(icon, fontSize = 28.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                label,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
    }
}
