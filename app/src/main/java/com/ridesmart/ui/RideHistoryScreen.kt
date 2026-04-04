package com.ridesmart.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ridesmart.R
import com.ridesmart.data.RideEntry
import com.ridesmart.model.Signal
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun RideHistoryScreen(
    onBack: () -> Unit,
    viewModel: HistoryViewModel = viewModel()
) {
    val context = LocalContext.current
    val groupedHistory by viewModel.groupedHistory.collectAsStateWithLifecycle()
    val isHistoryEmpty by viewModel.isHistoryEmpty.collectAsStateWithLifecycle()
    
    var showClearConfirm by remember { mutableStateOf(false) }

    BackHandler(onBack = onBack)

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.clear_history_title)) },
            text = { Text(stringResource(R.string.clear_history_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearHistory()
                        showClearConfirm = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFDC2626))
                ) {
                    Text(stringResource(R.string.clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.cancel), color = Color.White)
                }
            },
            containerColor = Color(0xFF16161C),
            titleContentColor = Color.White,
            textContentColor = Color(0xFF6B6B85)
        )
    }

    Scaffold(
        topBar = {
            Column(modifier = Modifier.background(Color(0xFF0F0F13))) {
                Spacer(Modifier.height(48.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.back), color = Color(0xFF3DDC84), fontSize = 15.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        stringResource(R.string.ride_history),
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.weight(1f))
                    
                    if (!isHistoryEmpty) {
                        IconButton(onClick = { 
                            val fullHistory = groupedHistory.values.flatten()
                            shareHistory(context, fullHistory)
                        }) {
                            Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.share), tint = Color(0xFF3DDC84))
                        }
                    }
                    
                    IconButton(onClick = { showClearConfirm = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete), tint = Color(0xFF6B6B85))
                    }
                }
            }
        },
        containerColor = Color(0xFF0F0F13)
    ) { padding ->
        if (isHistoryEmpty) {
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
                groupedHistory.forEach { (dateKey, rides) ->
                    val headerKey = "header_${dateKey.first}_${dateKey.second}_${dateKey.third}"
                    item(key = headerKey) {
                        DailySummaryHeader(rides = rides, dateKey = dateKey)
                    }
                    items(
                        count = rides.size,
                        key = { index -> "${rides[index].timestampMs}_${rides[index].packageName}_$index" }
                    ) { index ->
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

private fun shareHistory(context: Context, history: List<RideEntry>) {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    val csvHeader = "Timestamp,Platform,BaseFare,NetProfit,RideKm,PickupKm,TotalKm,Signal,PickupAddr,DropAddr\n"
    
    val csvContent = history.joinToString("\n") { e ->
        val date = sdf.format(Date(e.timestampMs))
        val pAddr = e.pickupAddress.replace(",", " ")
        val dAddr = e.dropAddress.replace(",", " ")
        "$date,${e.platform},${e.baseFare},${e.netProfit},${e.rideKm},${e.pickupKm},${e.totalKm},${e.signal.name},\"$pAddr\",\"$dAddr\""
    }

    val shareBody = csvHeader + csvContent
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "RideSmart History Export")
        putExtra(Intent.EXTRA_TEXT, shareBody)
    }
    context.startActivity(Intent.createChooser(intent, "Share History via"))
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
                tint = Color(0xFF1A1A22),
                modifier = Modifier.size(100.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.empty_history_message),
                color = Color(0xFF555555),
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
    
    val dateFormat = remember { SimpleDateFormat("EEE, d MMM", Locale.getDefault()) }
    val label = remember(dateKey) {
        if (isToday) "Today" else dateFormat.format(cal.time)
    }

    val totalNet = rides.sumOf { it.netProfit }
    val totalFare = rides.sumOf { it.baseFare }
    val totalKmSum = rides.sumOf { it.totalKm }.takeIf { it > 0.0 } ?: 1.0
    val avgPerKm   = rides.sumOf { it.netProfit } / totalKmSum
    val greenCount = rides.count { it.signal == Signal.GREEN }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A22)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val displayLabel = if (label == "Today") stringResource(R.string.today) else label
                Text(displayLabel, color = Color(0xFF3DDC84), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Surface(
                    color = Color(0xFF16A34A).copy(alpha = 0.1f),
                    shape = RoundedCornerShape(100.dp)
                ) {
                    Text(
                        "$greenCount GREEN · ${rides.size} TOTAL",
                        color = Color(0xFF3DDC84),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                SummaryCell(stringResource(R.string.gross_fare), "₹${"%.0f".format(totalFare)}", Modifier.weight(1f))
                SummaryCell(stringResource(R.string.net_profit), "₹${"%.0f".format(totalNet)}", Modifier.weight(1f))
                SummaryCell(stringResource(R.string.avg_per_km), "₹${"%.1f".format(avgPerKm)}", Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun SummaryCell(label: String, value: String, modifier: Modifier) {
    Column(modifier = modifier) {
        Text(label, color = Color(0xFF6B6B85), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.05.sp)
        Text(value, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
    }
}

@Composable
fun RideRow(entry: RideEntry) {
    val (signalColor, signalIcon) = when (entry.signal) {
        Signal.GREEN  -> Pair(Color(0xFF16A34A), "🟢")
        Signal.YELLOW -> Pair(Color(0xFFCA8A04), "🟡")
        Signal.RED    -> Pair(Color(0xFFDC2626), "🔴")
    }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val timeStr = remember(entry.timestampMs) {
        timeFormat.format(Date(entry.timestampMs))
    }
    var expanded by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = Color(0xFF16161C)),
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
                        color = Color(0xFF6B6B85),
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
                        Text(timeStr, color = Color(0xFF444455), fontSize = 11.sp)
                        Icon(
                            Icons.Filled.ArrowDropDown,
                            contentDescription = null,
                            tint = Color(0xFF444455),
                            modifier = Modifier.size(16.dp).rotate(rotation)
                        )
                    }
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = Color(0xFF2A2A36), thickness = 0.5.dp)
                    Spacer(Modifier.height(12.dp))

                    if (entry.pickupAddress.isNotBlank()) {
                        DetailRow(stringResource(R.string.label_pickup), entry.pickupAddress)
                    }
                    if (entry.dropAddress.isNotBlank()) {
                        DetailRow(stringResource(R.string.label_drop), entry.dropAddress)
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        if (entry.tipAmount > 0) MiniStat(stringResource(R.string.label_tip), "₹${"%.0f".format(entry.tipAmount)}")
                        if (entry.premiumAmount > 0) MiniStat(stringResource(R.string.label_premium), "₹${"%.0f".format(entry.premiumAmount)}")
                        MiniStat(stringResource(R.string.label_payout), "₹${"%.0f".format(entry.actualPayout)}")
                        MiniStat(stringResource(R.string.label_cost), "₹${"%.0f".format(entry.totalCost)}")
                    }

                    Spacer(Modifier.height(16.dp))
                    
                    // ── CALCULATION BREAKDOWN SECTION ──
                    Text(
                        stringResource(R.string.label_breakdown),
                        color = Color(0xFF3DDC84),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.05.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F0F13)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            CalculationLine(stringResource(R.string.label_fuel_cost), "- ₹${"%.1f".format(entry.fuelCost)}", Color(0xFF6B6B85))
                            CalculationLine(stringResource(R.string.label_wear_cost), "- ₹${"%.1f".format(entry.wearCost)}", Color(0xFF6B6B85))
                            Spacer(Modifier.height(4.dp))
                            HorizontalDivider(color = Color(0xFF2A2A36), thickness = 0.5.dp)
                            Spacer(Modifier.height(4.dp))
                            CalculationLine(
                                stringResource(R.string.label_net_formula), 
                                "₹${"%.1f".format(entry.netProfit)}", 
                                signalColor, 
                                isBold = true
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        MiniStat(stringResource(R.string.label_per_km_stat), "₹${"%.1f".format(entry.efficiencyPerKm)}")
                        if (entry.earningPerHour > 0) MiniStat(stringResource(R.string.label_per_hr_stat), "₹${"%.0f".format(entry.earningPerHour)}")
                        if (entry.estimatedDurationMin > 0) MiniStat(stringResource(R.string.label_duration), "${entry.estimatedDurationMin}m")
                        MiniStat(stringResource(R.string.label_pickup_pct), "${entry.pickupRatioPct}%")
                    }

                    Spacer(Modifier.height(12.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        if (entry.riderRating > 0) MiniStat(stringResource(R.string.label_rating), "⭐ ${"%.2f".format(entry.riderRating)}")
                        if (entry.paymentType.isNotBlank()) MiniStat(stringResource(R.string.label_payment), entry.paymentType)
                        MiniStat(stringResource(R.string.label_score), "₹${"%.1f".format(entry.decisionScore)}")
                    }

                    if (entry.failedChecks.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        entry.failedChecks.split("|").forEach { check ->
                            if (check.isNotBlank()) {
                                Text(
                                    "⚠️ $check",
                                    color = Color(0xFFCA8A04),
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
fun CalculationLine(label: String, value: String, color: Color, isBold: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color(0xFF6B6B85), fontSize = 11.sp)
        Text(
            value, 
            color = color, 
            fontSize = 11.sp, 
            fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Text(label, color = Color(0xFF6B6B85), fontSize = 12.sp, modifier = Modifier.width(75.dp), fontWeight = FontWeight.Medium)
        Text(value, color = Color(0xFFE2E2EC), fontSize = 12.sp, modifier = Modifier.weight(1f), lineHeight = 16.sp)
    }
}

@Composable
fun MiniStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color(0xFF6B6B85), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.05.sp)
        Text(value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}
