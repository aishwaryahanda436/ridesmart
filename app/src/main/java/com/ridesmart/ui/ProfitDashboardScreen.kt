package com.ridesmart.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun ProfitDashboardScreen(
    onBack: () -> Unit,
    viewModel: DashboardViewModel = viewModel()
) {
    val summary by viewModel.settledSummary.collectAsStateWithLifecycle()
    val dayLabel  by viewModel.dayLabel.collectAsStateWithLifecycle()
    val dayOffset by viewModel.dayOffset.collectAsStateWithLifecycle()

    val bgColor = Color(0xFF0F0F13)
    val cardBg  = Color(0xFF16161C)
    val green   = Color(0xFF3DDC84)

    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            Column(modifier = Modifier.background(bgColor)) {
                Spacer(Modifier.height(48.dp))
                Row(
                    modifier              = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = onBack) {
                        Text("← Back", color = green, fontSize = 15.sp)
                    }

                    Row(
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        IconButton(onClick = { viewModel.goToPreviousDay() }, modifier = Modifier.size(32.dp)) {
                            Text("◀", color = Color(0xFF6B6B85), fontSize = 14.sp)
                        }
                        Text(
                            text       = dayLabel,
                            color      = Color.White,
                            fontSize   = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(
                            onClick  = { viewModel.goToNextDay() },
                            modifier = Modifier.size(32.dp),
                            enabled  = dayOffset < 0
                        ) {
                            Text(
                                "▶",
                                color    = if (dayOffset < 0) Color(0xFF6B6B85) else Color(0xFF2A2A36),
                                fontSize = 14.sp
                            )
                        }
                    }

                    if (dayOffset < 0) {
                        TextButton(onClick = { viewModel.goToToday() }) {
                            Text("Today", color = green, fontSize = 13.sp)
                        }
                    } else {
                        Box(Modifier.width(60.dp))
                    }
                }
            }
        },
        containerColor = bgColor
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item {
                Spacer(Modifier.height(4.dp))
                DaySummaryCard(summary = summary, green = green, cardBg = cardBg)
            }

            item {
                Text(
                    "PLATFORM BREAKDOWN",
                    color = Color(0xFF6B6B85),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
            }

            items(summary.platforms) { detail ->
                PlatformDayCard(
                    detail = detail,
                    cardBg = cardBg,
                    green  = green,
                    onUpdateProgress = { completed ->
                        viewModel.updateIncentiveProgress(detail.platform, completed)
                    }
                )
            }

            if (summary.platforms.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No rides on ${if (dayOffset == 0) "today" else dayLabel}.",
                            color = Color(0xFF555555),
                            fontSize = 14.sp,
                            lineHeight = 22.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }

            item {
                SettlementExplainerCard(cardBg = cardBg)
            }
        }
    }
}

@Composable
private fun DaySummaryCard(
    summary: SettledDaySummary,
    green: Color,
    cardBg: Color
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = cardBg),
        shape    = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Settled profit", color = Color(0xFF6B6B85), fontSize = 12.sp)
                    Text(
                        "₹${"%.0f".format(summary.totalSettledProfit)}",
                        color = green,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("${summary.totalRides} rides", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("Operating ₹${"%.0f".format(summary.totalOperatingProfit)}", color = Color(0xFF6B6B85), fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = Color(0xFF2A2A36), thickness = 0.5.dp)
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                DashStat("Gross", "₹${"%.0f".format(summary.grossEarnings)}")
                DashStat("Ride cost", "-₹${"%.0f".format(summary.totalRideCost)}")
                DashStat("Commission", "-₹${"%.0f".format(summary.totalCommission)}")
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                DashStat("Pass cost", "-₹${"%.0f".format(summary.totalPassCost)}", color = Color(0xFFCA8A04))
                DashStat("Incentives", "+₹${"%.0f".format(summary.totalIncentive)}", color = green)
                DashStat("Final", "₹${"%.0f".format(summary.totalSettledProfit)}", color = green)
            }
        }
    }
}

@Composable
private fun DashStat(label: String, value: String, color: Color = Color.White) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color(0xFF6B6B85), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
        Text(value, color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PlatformDayCard(
    detail: SettledPlatformDetail,
    cardBg: Color,
    green: Color,
    onUpdateProgress: (Int) -> Unit
) {
    var showProgressEdit by remember { mutableStateOf(false) }
    var progressInput by remember { mutableStateOf(detail.incentiveProgress.first.toString()) }
    val isPassPlatform = detail.passCost > 0.0
    val hasIncentive   = detail.incentiveTarget > 0.0
    val incentiveComplete = detail.incentiveProgress.first >= detail.incentiveProgress.second
                            && detail.incentiveProgress.second > 0

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = cardBg),
        shape    = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(detail.platform, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    if (isPassPlatform) {
                        Surface(
                            color = Color(0x33EF9F27),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                "PASS",
                                color    = Color(0xFFEF9F27),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Text(
                    "₹${"%.0f".format(detail.settledProfit)}",
                    color = if (detail.settledProfit >= 0) green else Color(0xFFDC2626),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MiniStat("Rides", "${detail.rides}")
                MiniStat("Gross", "₹${"%.0f".format(detail.grossEarnings)}")
                // FIXED Bug 7: label changed to Ride profit
                MiniStat("Ride profit", "₹${"%.0f".format(detail.rideOperatingProfit)}")
            }
            if (isPassPlatform || detail.commissionDeducted > 0) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = Color(0xFF2A2A36), thickness = 0.5.dp)
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    if (detail.commissionDeducted > 0)
                        MiniStat("Commission", "-₹${"%.0f".format(detail.commissionDeducted)}", color = Color(0xFFCA8A04))
                    if (isPassPlatform)
                        MiniStat("Pass deducted", "-₹${"%.0f".format(detail.passCost)}", color = Color(0xFFCA8A04))
                }
            }
            if (hasIncentive) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = Color(0xFF2A2A36), thickness = 0.5.dp)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        val (done, target) = detail.incentiveProgress
                        Text(
                            if (incentiveComplete)
                                "Incentive unlocked +₹${"%.0f".format(detail.incentiveTarget)}"
                            else
                                "$done / $target rides — ₹${"%.0f".format(detail.incentiveTarget)} target",
                            color = if (incentiveComplete) green else Color(0xFF6B6B85),
                            fontSize = 12.sp
                        )
                    }
                    TextButton(onClick = { showProgressEdit = !showProgressEdit }) {
                        Text("Update", color = green, fontSize = 12.sp)
                    }
                }
                if (showProgressEdit) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value         = progressInput,
                            onValueChange = { progressInput = it },
                            modifier      = Modifier.weight(1f),
                            singleLine    = true,
                            label         = { Text("Rides completed") },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor   = green,
                                unfocusedBorderColor = Color(0xFF2A2A36),
                                focusedTextColor     = Color.White,
                                unfocusedTextColor   = Color.White
                            )
                        )
                        Button(
                            onClick = {
                                progressInput.toIntOrNull()?.let {
                                    onUpdateProgress(it)
                                    showProgressEdit = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = green)
                        ) {
                            Text("Save", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, color: Color = Color.White) {
    Column {
        Text(label, color = Color(0xFF6B6B85), fontSize = 10.sp)
        Text(value, color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SettlementExplainerCard(cardBg: Color) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = cardBg),
        shape    = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("How this works", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Commission is deducted per ride in real time.\n" +
                "Pass costs are deducted once here at end of day.\n" +
                "Incentives are added only when the target is met.\n" +
                "This is your true settled profit for today.",
                color    = Color(0xFF6B6B85),
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
        }
    }
}
