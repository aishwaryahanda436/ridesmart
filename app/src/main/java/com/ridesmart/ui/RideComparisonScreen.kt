package com.ridesmart.ui

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ridesmart.data.RideEntry
import com.ridesmart.data.RideHistoryRepository
import com.ridesmart.model.Signal
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import java.util.Calendar
import java.util.Locale

// ── ViewModel ─────────────────────────────────────────────────────────

class ComparisonViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = RideHistoryRepository(application)

    private fun startOfDayMs(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun endOfDayMs() = startOfDayMs() + 24 * 60 * 60 * 1000L - 1L

    val todayRides = repo.getRidesInRange(startOfDayMs(), endOfDayMs())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

// ── Sort options ──────────────────────────────────────────────────────

enum class CompareSort(val label: String) {
    SCORE("Score"),
    PROFIT("Profit"),
    PER_KM("₹/km"),
    PICKUP("Pickup")
}

// ── Screen ────────────────────────────────────────────────────────────

@Composable
fun RideComparisonScreen(
    onBack: () -> Unit,
    viewModel: ComparisonViewModel = viewModel()
) {
    val rides by viewModel.todayRides.collectAsStateWithLifecycle()
    var sort  by remember { mutableStateOf(CompareSort.SCORE) }

    val bgColor = Color(0xFF0F0F13)
    val cardBg  = Color(0xFF16161C)
    val green   = Color(0xFF3DDC84)

    BackHandler(onBack = onBack)

    val sorted = remember(rides, sort) {
        when (sort) {
            CompareSort.SCORE  -> rides.sortedByDescending { it.decisionScore }
            CompareSort.PROFIT -> rides.sortedByDescending { it.netProfit }
            CompareSort.PER_KM -> rides.sortedByDescending { it.efficiencyPerKm }
            CompareSort.PICKUP -> rides.sortedBy { it.pickupKm }
        }
    }

    Scaffold(
        topBar = {
            Column(modifier = Modifier.background(bgColor)) {
                Spacer(Modifier.height(48.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onBack) {
                        Text("← Back", color = green, fontSize = 15.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "Today's Rides",
                        color      = Color.White,
                        fontSize   = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${rides.size} rides",
                        color    = Color(0xFF6B6B85),
                        fontSize = 13.sp
                    )
                }

                // Sort chips
                Row(
                    modifier            = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CompareSort.values().forEach { option ->
                        val selected = sort == option
                        Surface(
                            modifier  = Modifier.clickable { sort = option },
                            color     = if (selected) green.copy(alpha = 0.15f)
                                        else Color(0xFF1A1A22),
                            shape     = RoundedCornerShape(20.dp),
                            border    = if (selected)
                                androidx.compose.foundation.BorderStroke(1.dp, green)
                            else null
                        ) {
                            Text(
                                option.label,
                                color     = if (selected) green else Color(0xFF6B6B85),
                                fontSize  = 12.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                modifier  = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        },
        containerColor = bgColor
    ) { padding ->
        if (sorted.isEmpty()) {
            Box(
                modifier        = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No rides recorded today.\nAccept a ride to see it here.",
                    color    = Color(0xFF555555),
                    fontSize = 14.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    lineHeight = 22.sp
                )
            }
        } else {
            LazyColumn(
                modifier        = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding  = PaddingValues(bottom = 24.dp, top = 4.dp)
            ) {
                items(sorted, key = { it.id }) { ride ->
                    ComparisonRideCard(ride = ride, cardBg = cardBg, green = green)
                }
            }
        }
    }
}

// ── Single ride card ──────────────────────────────────────────────────

@Composable
private fun ComparisonRideCard(ride: RideEntry, cardBg: Color, green: Color) {
    val signalColor = when (ride.signal) {
        Signal.GREEN  -> Color(0xFF16A34A)
        Signal.YELLOW -> Color(0xFFCA8A04)
        Signal.RED    -> Color(0xFFDC2626)
    }
    val scoreColor = when {
        ride.decisionScore >= 75.0 -> Color(0xFF16A34A)
        ride.decisionScore >= 45.0 -> Color(0xFFCA8A04)
        else                   -> Color(0xFFDC2626)
    }
    val timeStr = remember(ride.timestampMs) {
        java.text.SimpleDateFormat("HH:mm", Locale.getDefault())
            .format(java.util.Date(ride.timestampMs))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = cardBg),
        shape    = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header row
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        color = signalColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            when (ride.signal) {
                                Signal.GREEN  -> "●"
                                Signal.YELLOW -> "●"
                                Signal.RED    -> "●"
                            },
                            color    = signalColor,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Text(ride.platform, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(timeStr, color = Color(0xFF555555), fontSize = 12.sp)
                }
                // SmartScore badge
                if (ride.decisionScore > 0.0) {
                    Surface(
                        color = scoreColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            "S:${ride.decisionScore.toInt()}",
                            color      = scoreColor,
                            fontSize   = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier   = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Key metrics row
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                CompStat("Net profit", "₹${"%.0f".format(ride.netProfit)}", green)
                CompStat("Fare", "₹${"%.0f".format(ride.baseFare)}")
                CompStat("₹/km", "₹${"%.1f".format(ride.efficiencyPerKm)}")
                CompStat("Pickup", "${"%.1f".format(ride.pickupKm)}km",
                    if (ride.pickupKm > 2.5) Color(0xFFDC2626)
                    else if (ride.pickupKm > 1.0) Color(0xFFCA8A04)
                    else green
                )
            }

            Spacer(Modifier.height(8.dp))

            // Distance row
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                CompStat("Ride km", "${"%.1f".format(ride.rideKm)} km")
                CompStat("Total km", "${"%.1f".format(ride.totalKm)} km")
                CompStat("Cost", "₹${"%.0f".format(ride.totalCost)}")

                // FIXED Bug 5: Replace penalty with pickup ratio
                val pickupPct = if (ride.totalKm > 0)
                    (ride.pickupKm / ride.totalKm * 100).toInt() else 0
                CompStat(
                    "Pkup%", "${pickupPct}%",
                    when {
                        pickupPct > 35 -> Color(0xFFDC2626)
                        pickupPct > 20 -> Color(0xFFCA8A04)
                        else           -> Color(0xFF3DDC84)
                    }
                )
            }

            // Failed checks (compact)
            if (ride.failedChecks.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = Color(0xFF2A2A36), thickness = 0.5.dp)
                Spacer(Modifier.height(6.dp))
                ride.failedChecks.split("|").forEach { check ->
                    if (check.isNotBlank()) {
                        Text("⚠ $check",
                            color    = Color(0xFFCA8A04),
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompStat(label: String, value: String, color: Color = Color.White) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color(0xFF6B6B85), fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Text(value, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}
