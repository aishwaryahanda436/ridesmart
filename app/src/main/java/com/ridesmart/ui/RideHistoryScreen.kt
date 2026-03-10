package com.ridesmart.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridesmart.data.RideEntry
import com.ridesmart.data.RideHistoryRepository
import com.ridesmart.model.Signal
import com.ridesmart.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun RideHistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { RideHistoryRepository(context) }
    val scope = rememberCoroutineScope()
    val history by repo.historyFlow.collectAsState(initial = emptyList())

    // Date filter state
    var selectedFilter by remember { mutableStateOf("All") }
    val filterOptions = listOf("All", "Today", "This Week", "This Month")

    val filteredHistory = remember(history, selectedFilter) {
        val now = Calendar.getInstance()
        history.filter { entry ->
            val entryCal = Calendar.getInstance().apply { timeInMillis = entry.timestampMs }
            when (selectedFilter) {
                "Today" -> entryCal.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
                           entryCal.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
                "This Week" -> {
                    val weekAgo = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -7) }
                    entry.timestampMs >= weekAgo.timeInMillis
                }
                "This Month" -> entryCal.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
                                entryCal.get(Calendar.MONTH) == now.get(Calendar.MONTH)
                else -> true
            }
        }
    }

    // Group rides by calendar date
    val grouped = remember(filteredHistory) {
        filteredHistory.groupBy { entry ->
            val cal = Calendar.getInstance().apply { timeInMillis = entry.timestampMs }
            Triple(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))
        }.toSortedMap(compareByDescending<Triple<Int,Int,Int>> { it.first }
            .thenByDescending { it.second }
            .thenByDescending { it.third })
    }

    Scaffold(
        topBar = {
            Column(modifier = Modifier.background(DarkBackground)) {
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
                    Text(
                        "Ride History",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { scope.launch { repo.clearHistory() } }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Clear", tint = TextSecondary)
                    }
                }
                // ── DATE FILTER CHIPS ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    filterOptions.forEach { option ->
                        FilterChip(
                            selected = selectedFilter == option,
                            onClick = { selectedFilter = option },
                            label = {
                                Text(
                                    option,
                                    fontSize = 12.sp,
                                    fontWeight = if (selectedFilter == option) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = RideGreen.copy(alpha = 0.15f),
                                selectedLabelColor = RideGreen,
                                containerColor = DarkSurfaceVariant,
                                labelColor = TextSecondary
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                borderColor = DarkBorder,
                                selectedBorderColor = RideGreen.copy(alpha = 0.3f),
                                enabled = true,
                                selected = selectedFilter == option
                            ),
                            shape = RoundedCornerShape(20.dp)
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        },
        containerColor = DarkBackground
    ) { padding ->
        if (filteredHistory.isEmpty()) {
            EmptyHistoryState(Modifier.padding(padding))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                grouped.forEach { (dateKey, rides) ->
                    item(key = "header_$dateKey") {
                        DailySummaryHeader(rides = rides, dateKey = dateKey)
                    }
                    items(rides.size, key = { index -> rides[index].timestampMs }) { index ->
                        RideRow(rides[index])
                    }
                    item {
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyHistoryState(modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.History,
                contentDescription = null,
                tint = DarkSurfaceVariant,
                modifier = Modifier.size(100.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "No rides recorded yet.\nRides are saved automatically\nwhen the overlay appears.",
                color = TextSecondary,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp
            )
        }
    }
}

@Composable
fun DailySummaryHeader(rides: List<RideEntry>, dateKey: Triple<Int, Int, Int>) {
    val cal = Calendar.getInstance().apply {
        set(dateKey.first, dateKey.second, dateKey.third)
    }
    val isToday = run {
        val today = Calendar.getInstance()
        dateKey.first == today.get(Calendar.YEAR) &&
        dateKey.second == today.get(Calendar.MONTH) &&
        dateKey.third == today.get(Calendar.DAY_OF_MONTH)
    }
    val label = if (isToday) "Today" else
        SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(cal.time)

    val totalNet = rides.sumOf { it.netProfit }
    val totalFare = rides.sumOf { it.baseFare }
    val avgPerKm = if (rides.sumOf { it.rideKm } > 0)
        rides.sumOf { it.netProfit } / rides.sumOf { it.rideKm } else 0.0
    val greenCount = rides.count { it.signal == Signal.GREEN }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label, color = RideGreen, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Surface(
                    color = SignalGreen.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(100.dp)
                ) {
                    Text(
                        "$greenCount GREEN · ${rides.size} TOTAL",
                        color = RideGreen,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                SummaryCell("GROSS FARE", "₹${"%.0f".format(totalFare)}", Modifier.weight(1f))
                SummaryCell("NET PROFIT", "₹${"%.0f".format(totalNet)}", Modifier.weight(1f))
                SummaryCell("AVG ₹/KM", "₹${"%.1f".format(avgPerKm)}", Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun SummaryCell(label: String, value: String, modifier: Modifier) {
    Column(modifier = modifier) {
        Text(label, color = TextSecondary, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.05.sp)
        Text(value, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
    }
}

@Composable
fun RideRow(entry: RideEntry) {
    val (signalColor, signalIcon) = when (entry.signal) {
        Signal.GREEN  -> Pair(SignalGreen, "🟢")
        Signal.YELLOW -> Pair(SignalYellow, "🟡")
        Signal.RED    -> Pair(SignalRed, "🔴")
    }
    val timeStr = remember(entry.timestampMs) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(entry.timestampMs))
    }
    var expanded by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(signalIcon, fontSize = 18.sp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "₹${"%.0f".format(entry.baseFare)} · ${"%.1f".format(entry.rideKm)}km",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Text(
                        "${entry.platform} · pickup ${"%.1f".format(entry.pickupKm)}km",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "₹${"%.0f".format(entry.netProfit)}",
                        color = signalColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(timeStr, color = TextMuted, fontSize = 11.sp)
                        Icon(
                            Icons.Filled.ArrowDropDown,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(16.dp).rotate(rotation)
                        )
                    }
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = DarkBorder, thickness = 0.5.dp)
                    Spacer(Modifier.height(12.dp))

                    if (entry.pickupAddress.isNotBlank()) {
                        DetailRow("📍 Pickup", entry.pickupAddress)
                    }
                    if (entry.dropAddress.isNotBlank()) {
                        DetailRow("🏁 Drop", entry.dropAddress)
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        if (entry.tipAmount > 0) MiniStat("TIP", "₹${"%.0f".format(entry.tipAmount)}")
                        if (entry.premiumAmount > 0) MiniStat("PREMIUM", "₹${"%.0f".format(entry.premiumAmount)}")
                        MiniStat("PAYOUT", "₹${"%.0f".format(entry.actualPayout)}")
                        MiniStat("COST", "₹${"%.0f".format(entry.totalCost)}")
                    }

                    Spacer(Modifier.height(12.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        MiniStat("₹/KM", "₹${"%.1f".format(entry.earningPerKm)}")
                        if (entry.earningPerHour > 0) MiniStat("₹/HR", "₹${"%.0f".format(entry.earningPerHour)}")
                        if (entry.estimatedDurationMin > 0) MiniStat("DURATION", "${entry.estimatedDurationMin}m")
                        MiniStat("PICKUP%", "${entry.pickupRatioPct}%")
                    }

                    Spacer(Modifier.height(12.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        if (entry.riderRating > 0) MiniStat("RATING", "⭐ ${"%.2f".format(entry.riderRating)}")
                        if (entry.paymentType.isNotBlank()) MiniStat("PAYMENT", entry.paymentType)
                        MiniStat("SCORE", "₹${"%.1f".format(entry.smartScore)}")
                    }

                    if (entry.failedChecks.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        entry.failedChecks.split("|").forEach { check ->
                            if (check.isNotBlank()) {
                                Text(
                                    "⚠️ $check",
                                    color = SignalYellow,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Text(label, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.width(75.dp), fontWeight = FontWeight.Medium)
        Text(value, color = TextPrimary, fontSize = 12.sp, modifier = Modifier.weight(1f), lineHeight = 16.sp)
    }
}

@Composable
fun MiniStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = TextSecondary, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.05.sp)
        Text(value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}
