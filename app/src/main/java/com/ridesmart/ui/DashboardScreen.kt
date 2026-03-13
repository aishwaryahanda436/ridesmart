package com.ridesmart.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridesmart.data.RideEntry
import com.ridesmart.data.RideHistoryRepository
import com.ridesmart.model.Signal
import com.ridesmart.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Analytics dashboard showing ride performance data.
 * Displays total rides, earnings, profit, daily/weekly performance.
 */
@Composable
fun DashboardScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { RideHistoryRepository(context) }
    val history by repo.historyFlow.collectAsState(initial = emptyList())

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        // ── TOP BAR ──
        Spacer(Modifier.height(48.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("← Back", color = RideGreen, fontSize = 15.sp)
            }
            Spacer(Modifier.weight(1f))
            Text("Analytics", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Box(Modifier.width(60.dp))
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(16.dp))

            if (history.isEmpty()) {
                EmptyDashboardState()
            } else {
                // ── LIFETIME STATS ──
                SectionHeader("LIFETIME SUMMARY")
                Spacer(Modifier.height(12.dp))
                LifetimeStatsCards(history)

                Spacer(Modifier.height(24.dp))

                // ── DAILY PERFORMANCE (last 7 days) ──
                SectionHeader("DAILY PERFORMANCE")
                Spacer(Modifier.height(12.dp))
                DailyPerformanceChart(history)

                Spacer(Modifier.height(24.dp))

                // ── PLATFORM BREAKDOWN ──
                SectionHeader("PLATFORM BREAKDOWN")
                Spacer(Modifier.height(12.dp))
                PlatformBreakdown(history)

                Spacer(Modifier.height(24.dp))

                // ── SIGNAL DISTRIBUTION ──
                SectionHeader("RIDE QUALITY")
                Spacer(Modifier.height(12.dp))
                SignalDistribution(history)

                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        title,
        color = TextSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp
    )
}

@Composable
fun LifetimeStatsCards(rides: List<RideEntry>) {
    val totalRides = rides.size
    val totalProfit = rides.sumOf { it.netProfit }
    val totalEarnings = rides.sumOf { it.baseFare }
    val avgProfit = if (totalRides > 0) totalProfit / totalRides else 0.0
    val totalKm = rides.sumOf { it.rideKm }
    val avgPerKm = if (totalKm > 0) totalProfit / totalKm else 0.0

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // Main number
            Text("Total Net Profit", color = TextSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "₹${"%.0f".format(totalProfit)}",
                color = if (totalProfit >= 0) RideGreen else SignalRed,
                fontSize = 36.sp,
                fontWeight = FontWeight.ExtraBold
            )

            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = DarkBorder, thickness = 0.5.dp)
            Spacer(Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                DashStat("RIDES", "$totalRides", Modifier.weight(1f))
                DashStat("GROSS FARE", "₹${"%.0f".format(totalEarnings)}", Modifier.weight(1f))
                DashStat("AVG PROFIT", "₹${"%.0f".format(avgProfit)}", Modifier.weight(1f))
            }

            Spacer(Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                DashStat("TOTAL KM", "${"%.0f".format(totalKm)}", Modifier.weight(1f))
                DashStat("AVG ₹/KM", "₹${"%.1f".format(avgPerKm)}", Modifier.weight(1f))
                DashStat("GREEN %", "${if (totalRides > 0) (rides.count { it.signal == Signal.GREEN } * 100.0 / totalRides).toInt() else 0}%", Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun DashStat(label: String, value: String, modifier: Modifier) {
    Column(modifier = modifier) {
        Text(label, color = TextSecondary, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
        Spacer(Modifier.height(2.dp))
        Text(value, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
    }
}

@Composable
fun DailyPerformanceChart(rides: List<RideEntry>) {
    val cal = Calendar.getInstance()
    val dateFormat = SimpleDateFormat("EEE", Locale.getDefault())
    val last7Days = (0..6).map { daysAgo ->
        val dayCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -daysAgo) }
        val dayRides = rides.filter { entry ->
            val entryCal = Calendar.getInstance().apply { timeInMillis = entry.timestampMs }
            entryCal.get(Calendar.YEAR) == dayCal.get(Calendar.YEAR) &&
            entryCal.get(Calendar.DAY_OF_YEAR) == dayCal.get(Calendar.DAY_OF_YEAR)
        }
        Triple(
            if (daysAgo == 0) "Today" else dateFormat.format(dayCal.time),
            dayRides.sumOf { it.netProfit },
            dayRides.size
        )
    }.reversed()

    val maxProfit = last7Days.maxOfOrNull { it.second }?.coerceAtLeast(1.0) ?: 1.0

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // Bar chart
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Bottom
            ) {
                last7Days.forEach { (label, profit, count) ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            if (count > 0) "$count" else "",
                            color = TextSecondary,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        val barHeight = if (profit > 0) (profit / maxProfit * 80).coerceIn(4.0, 80.0) else 4.0
                        val barColor = when {
                            profit > 0 -> RideGreen
                            profit < 0 -> SignalRed
                            else -> DarkBorder
                        }
                        Box(
                            modifier = Modifier
                                .width(20.dp)
                                .height(barHeight.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(barColor.copy(alpha = 0.7f))
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            label,
                            color = TextSecondary,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PlatformBreakdown(rides: List<RideEntry>) {
    val byPlatform = rides.groupBy { it.platform }
        .map { (platform, platformRides) ->
            Triple(platform, platformRides.size, platformRides.sumOf { it.netProfit })
        }
        .sortedByDescending { it.third }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            byPlatform.forEachIndexed { index, (platform, count, profit) ->
                if (index > 0) {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = DarkBorder.copy(alpha = 0.5f), thickness = 0.5.dp)
                    Spacer(Modifier.height(8.dp))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(platform, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text("$count rides", color = TextSecondary, fontSize = 12.sp)
                    }
                    Text(
                        "₹${"%.0f".format(profit)}",
                        color = if (profit >= 0) RideGreen else SignalRed,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            if (byPlatform.isEmpty()) {
                Text("No platform data yet", color = TextSecondary, fontSize = 14.sp)
            }
        }
    }
}

@Composable
fun SignalDistribution(rides: List<RideEntry>) {
    val greenCount = rides.count { it.signal == Signal.GREEN }
    val yellowCount = rides.count { it.signal == Signal.YELLOW }
    val redCount = rides.count { it.signal == Signal.RED }
    val total = rides.size.coerceAtLeast(1)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // Progress bar showing distribution
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp))
            ) {
                if (greenCount > 0) {
                    Box(
                        Modifier
                            .weight(greenCount.toFloat() / total)
                            .fillMaxHeight()
                            .background(SignalGreen)
                    )
                }
                if (yellowCount > 0) {
                    Box(
                        Modifier
                            .weight(yellowCount.toFloat() / total)
                            .fillMaxHeight()
                            .background(SignalYellow)
                    )
                }
                if (redCount > 0) {
                    Box(
                        Modifier
                            .weight(redCount.toFloat() / total)
                            .fillMaxHeight()
                            .background(SignalRed)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                SignalStat("✅ GREEN", greenCount, SignalGreen, Modifier.weight(1f))
                SignalStat("🟡 YELLOW", yellowCount, SignalYellow, Modifier.weight(1f))
                SignalStat("🔴 RED", redCount, SignalRed, Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun SignalStat(label: String, count: Int, color: Color, modifier: Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$count", color = color, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        Text(label, color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun EmptyDashboardState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 80.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("📊", fontSize = 64.sp)
            Spacer(Modifier.height(16.dp))
            Text(
                "No data yet",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Ride analytics will appear here\nafter your first ride is detected.",
                color = TextSecondary,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}
