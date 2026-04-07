package com.ridesmart.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ridesmart.R
import com.ridesmart.model.IncentiveProfile
import com.ridesmart.model.PlatformPlan
import com.ridesmart.model.PlanType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ProfileSetupScreen(
    onSaved: () -> Unit,
    onBack: () -> Unit,
    viewModel: ProfileViewModel = viewModel()
) {
    val savedProfile by viewModel.profile.collectAsState()
    val errorMessage by viewModel.errorFlow.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Bug 1C Fix: Use one-time initialization instead of remember(savedProfile)
    var mileage      by remember { mutableStateOf("") }
    var fuelPrice    by remember { mutableStateOf("") }
    var cngPrice     by remember { mutableStateOf("") }
    var maintenance  by remember { mutableStateOf("") }
    var depreciation by remember { mutableStateOf("") }
    var minProfit    by remember { mutableStateOf("") }
    var minPerKm     by remember { mutableStateOf("") }
    var targetPerHr  by remember { mutableStateOf("") }
    var commission   by remember { mutableStateOf("") }
    var dailyTarget  by remember { mutableStateOf("") }
    var useCustomComm by remember { mutableStateOf(false) }

    val platforms = listOf("Rapido", "Uber", "Ola", "Shadowfax")

    // Platform plan state
    val planTypes = remember { platforms.associateWith { mutableStateOf(PlanType.COMMISSION) } }
    val commissions = remember { platforms.associateWith { mutableStateOf("0") } }
    val passAmounts = remember { platforms.associateWith { mutableStateOf("0") } }
    val passDays = remember { platforms.associateWith { mutableStateOf("1") } }

    // Incentive state
    val incEnabled = remember { platforms.associateWith { mutableStateOf(false) } }
    val incTargets = remember { platforms.associateWith { mutableStateOf("0") } }
    val incRewards = remember { platforms.associateWith { mutableStateOf("0") } }
    val incCompleted = remember { platforms.associateWith { mutableStateOf("0") } }

    val isInitialized = remember { mutableStateOf(false) }
    LaunchedEffect(savedProfile) {
        if (!isInitialized.value && savedProfile.isConfigured) {
            mileage = savedProfile.mileageKmPerLitre.toString()
            fuelPrice = savedProfile.fuelPricePerLitre.toString()
            cngPrice = savedProfile.cngPricePerKg.toString()
            maintenance = savedProfile.maintenancePerKm.toString()
            depreciation = savedProfile.depreciationPerKm.toString()
            minProfit = savedProfile.minAcceptableNetProfit.toString()
            minPerKm = savedProfile.minAcceptablePerKm.toString()
            targetPerHr = savedProfile.targetEarningPerHour.toString()
            commission = savedProfile.platformCommissionPercent.toString()
            useCustomComm = savedProfile.useCustomCommission
            dailyTarget = if (savedProfile.dailyEarningTarget > 0.0) savedProfile.dailyEarningTarget.toLong().toString() else ""

            platforms.forEach { name ->
                val plan = savedProfile.platformPlans[name]
                if (plan != null) {
                    planTypes[name]?.value = plan.planType
                    commissions[name]?.value = plan.commissionPercent.toString()
                    passAmounts[name]?.value = plan.passAmount.toString()
                    passDays[name]?.value = plan.passDurationDays.toString()
                }
                val inc = savedProfile.incentiveProfiles[name]
                if (inc != null) {
                    incEnabled[name]?.value = inc.enabled
                    incTargets[name]?.value = inc.targetRides.toString()
                    incRewards[name]?.value = inc.rewardAmount.toString()
                    incCompleted[name]?.value = inc.completedToday.toString()
                }
            }
            isInitialized.value = true
        }
    }

    val bgColor = Color(0xFF0F0F13)
    val cardBg  = Color(0xFF16161C)
    val green   = Color(0xFF3DDC84)

    val settingsSavedMsg = stringResource(R.string.settings_saved)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = bgColor,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.back), color = green, fontSize = 15.sp)
                }
                Spacer(Modifier.weight(1f))
                Text(stringResource(R.string.earning_targets), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Box(Modifier.width(60.dp))
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Text(
                stringResource(R.string.profile_config_desc),
                color = Color(0xFF6B6B85),
                fontSize = 13.sp,
                lineHeight = 18.sp
            )

            Spacer(Modifier.height(24.dp))

            ProfileGroupCard(title = stringResource(R.string.group_vehicle_fuel), icon = "🏍️", bgColor = cardBg) {
                InputField(stringResource(R.string.label_mileage), mileage, { mileage = it }, "25 - 80")
                InputField(stringResource(R.string.label_petrol), fuelPrice, { fuelPrice = it }, "90 - 115")
                InputField(stringResource(R.string.label_cng), cngPrice, { cngPrice = it }, "70 - 95")
            }

            ProfileGroupCard(title = stringResource(R.string.group_running_costs), icon = "🛠️", bgColor = cardBg) {
                InputField(stringResource(R.string.label_maintenance), maintenance, { maintenance = it }, "0.0 - 3.0")
                InputField(stringResource(R.string.label_depreciation), depreciation, { depreciation = it }, "0.0 - 3.0")
            }

            ProfileGroupCard(title = stringResource(R.string.group_earning_goals), icon = "💰", bgColor = cardBg) {
                InputField(stringResource(R.string.label_min_profit), minProfit, { minProfit = it }, "0 - 500")
                InputField(stringResource(R.string.label_min_per_km), minPerKm, { minPerKm = it }, "2.0 - 20.0")
                InputField(stringResource(R.string.label_target_per_hour), targetPerHr, { targetPerHr = it }, "0 - 500")
                InputField(
                    label         = "Daily target (₹)",
                    value         = dailyTarget,
                    onValueChange = { dailyTarget = it },
                    hint          = "e.g. 1200   (0 or blank to hide)"
                )
            }

            // Bug 3B: Global Commission Toggle & Input
            ProfileGroupCard(title = "GLOBAL OVERRIDE", icon = "🌍", bgColor = cardBg) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Use Custom Commission", color = Color.White, fontSize = 14.sp)
                    Switch(checked = useCustomComm, onCheckedChange = { useCustomComm = it }, colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = green))
                }
                if (useCustomComm) {
                    InputField("Global Commission %", commission, { commission = it }, "0 - 50")
                }
            }

            // ── PER-PLATFORM PLANS ──
            Text(
                "PLATFORM PLANS".uppercase(),
                color = Color(0xFF6B6B85),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )

            platforms.forEach { platformName ->
                PlatformPlanCard(
                    platformName  = platformName,
                    planType      = planTypes[platformName]!!.value,
                    onPlanChange  = { planTypes[platformName]!!.value = it },
                    commission    = commissions[platformName]!!.value,
                    onCommChange  = { commissions[platformName]!!.value = it },
                    passAmount    = passAmounts[platformName]!!.value,
                    onPassChange  = { passAmounts[platformName]!!.value = it },
                    passDays      = passDays[platformName]!!.value,
                    onDaysChange  = { passDays[platformName]!!.value = it },
                    incEnabled    = incEnabled[platformName]!!.value,
                    onIncToggle   = { incEnabled[platformName]!!.value = it },
                    targetRides   = incTargets[platformName]!!.value,
                    onTargetChange= { incTargets[platformName]!!.value = it },
                    rewardAmount  = incRewards[platformName]!!.value,
                    onRewardChange= { incRewards[platformName]!!.value = it },
                    completedToday= incCompleted[platformName]!!.value,
                    onCompletedChange = { incCompleted[platformName]!!.value = it },
                    bgColor       = cardBg
                )
                Spacer(Modifier.height(12.dp))
            }

            if (errorMessage.isNotEmpty()) {
                Text(errorMessage, color = Color(0xFFDC2626), fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = {
                    // Bug 5B Fix: Cap completedToday
                    val finalPlans = platforms.associateWith { name ->
                        PlatformPlan(
                            planType = planTypes[name]!!.value,
                            commissionPercent = commissions[name]!!.value.toDoubleOrNull() ?: 0.0,
                            passAmount = passAmounts[name]!!.value.toDoubleOrNull() ?: 0.0,
                            passDurationDays = passDays[name]!!.value.toIntOrNull() ?: 1
                        )
                    }
                    val finalIncentives = platforms.associateWith { name ->
                        val target = incTargets[name]!!.value.toIntOrNull() ?: 0
                        val completed = (incCompleted[name]!!.value.toIntOrNull() ?: 0).coerceIn(0, if (target > 0) target else Int.MAX_VALUE)
                        IncentiveProfile(
                            enabled = incEnabled[name]!!.value,
                            targetRides = target,
                            rewardAmount = incRewards[name]!!.value.toDoubleOrNull() ?: 0.0,
                            completedToday = completed
                        )
                    }

                    // Bug 1B Fix: Use atomic validateAndSaveAll
                    viewModel.validateAndSaveAll(
                        mileage = mileage,
                        fuel = fuelPrice,
                        cng = cngPrice,
                        maint = maintenance,
                        depr = depreciation,
                        minProfit = minProfit,
                        minKm = minPerKm,
                        hour = targetPerHr,
                        comm = if (useCustomComm) commission else "0",
                        dailyTarget = dailyTarget,
                        plans = finalPlans,
                        incentives = finalIncentives,
                        onSuccess = {
                            scope.launch {
                                snackbarHostState.showSnackbar(settingsSavedMsg)
                                onSaved() // Atomic save complete, no race condition
                            }
                        }
                    )
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = green),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(stringResource(R.string.save_and_apply), color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
fun ProfileGroupCard(title: String, icon: String, bgColor: Color, content: @Composable ColumnScope.() -> Unit) {
    Text(
        text = title.uppercase(),
        color = Color(0xFF6B6B85),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

@Composable
fun InputField(label: String, value: String, onValueChange: (String) -> Unit, hint: String) {
    Column(modifier = Modifier.padding(bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Text(hint, color = Color(0xFF6B6B85), fontSize = 11.sp)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF3DDC84),
                unfocusedBorderColor = Color(0xFF2A2A36),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedContainerColor = Color(0xFF0F0F13).copy(alpha = 0.5f),
                unfocusedContainerColor = Color(0xFF0F0F13).copy(alpha = 0.5f)
            )
        )
    }
}

@Composable
fun PlatformPlanCard(
    platformName: String,
    planType: PlanType,
    onPlanChange: (PlanType) -> Unit,
    commission: String,
    onCommChange: (String) -> Unit,
    passAmount: String,
    onPassChange: (String) -> Unit,
    passDays: String,
    onDaysChange: (String) -> Unit,
    incEnabled: Boolean,
    onIncToggle: (Boolean) -> Unit,
    targetRides: String,
    onTargetChange: (String) -> Unit,
    rewardAmount: String,
    onRewardChange: (String) -> Unit,
    completedToday: String,
    onCompletedChange: (String) -> Unit,
    bgColor: Color
) {
    val green = Color(0xFF3DDC84)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            // Platform name header
            Text(
                platformName,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Plan type toggle: Commission | Pass
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(PlanType.COMMISSION to "Commission", PlanType.PASS to "Pass").forEach { (type, label) ->
                    val selected = planType == type
                    OutlinedButton(
                        onClick = { onPlanChange(type) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (selected) green.copy(alpha = 0.15f)
                                             else Color.Transparent,
                            contentColor   = if (selected) green else Color(0xFF6B6B85)
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (selected) green else Color(0xFF2A2A36)
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Fields that depend on plan type
            if (planType == PlanType.COMMISSION) {
                InputField(
                    label         = "Commission %",
                    value         = commission,
                    onValueChange = onCommChange,
                    hint          = "0 – 35"
                )
            } else {
                InputField(
                    label         = "Pass Amount (₹)",
                    value         = passAmount,
                    onValueChange = onPassChange,
                    hint          = "e.g. 30, 130, 500"
                )
                InputField(
                    label         = "Pass Duration (days)",
                    value         = passDays,
                    onValueChange = onDaysChange,
                    hint          = "1 / 3 / 7 / 20"
                )
                // Read-only estimated per-ride cost
                val estPerRide = run {
                    val amt  = passAmount.toDoubleOrNull() ?: 0.0
                    val days = passDays.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    val rides = 15.0 * days
                    if (rides > 0) amt / rides else 0.0
                }
                Text(
                    "Est. ₹${"%.2f".format(estPerRide)} per ride (at 15 rides/day)",
                    color  = Color(0xFF6B6B85),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            HorizontalDivider(
                color     = Color(0xFF2A2A36),
                thickness = 0.5.dp,
                modifier  = Modifier.padding(vertical = 8.dp)
            )

            // Incentive section
            Row(
                modifier             = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment    = Alignment.CenterVertically
            ) {
                Text(
                    "Incentive / Bonus",
                    color      = Color.White,
                    fontSize   = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                Switch(
                    checked         = incEnabled,
                    onCheckedChange = onIncToggle,
                    colors          = SwitchDefaults.colors(
                        checkedThumbColor  = Color.Black,
                        checkedTrackColor  = green
                    )
                )
            }

            if (incEnabled) {
                Spacer(Modifier.height(8.dp))
                InputField(
                    label         = "Target rides",
                    value         = targetRides,
                    onValueChange = onTargetChange,
                    hint          = "e.g. 20"
                )
                InputField(
                    label         = "Reward (₹)",
                    value         = rewardAmount,
                    onValueChange = onRewardChange,
                    hint          = "e.g. 200"
                )
                InputField(
                    label         = "Rides completed today",
                    value         = completedToday,
                    onValueChange = onCompletedChange,
                    hint          = "0 – target"
                )
                // Show progress
                val completed = completedToday.toIntOrNull() ?: 0
                val target    = targetRides.toIntOrNull() ?: 0
                val reward    = rewardAmount.toDoubleOrNull() ?: 0.0
                val remaining = (target - completed).coerceAtLeast(0)
                if (target > 0) {
                    Text(
                        if (remaining == 0)
                            "Target complete — ₹${"%.0f".format(reward)} earned"
                        else
                            "$remaining rides to unlock ₹${"%.0f".format(reward)}",
                        color    = if (remaining == 0) green else Color(0xFF6B6B85),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            }
        }
    }
}
