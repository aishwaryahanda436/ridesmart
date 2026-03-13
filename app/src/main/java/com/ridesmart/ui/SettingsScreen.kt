package com.ridesmart.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ridesmart.isAccessibilityServiceEnabled
import com.ridesmart.isNotificationPermissionGranted
import com.ridesmart.model.RiderProfile
import com.ridesmart.ui.theme.*

/**
 * Modern settings screen — configurable ride parameters, overlay toggle,
 * and notification preferences. Grouped into logical sections.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: ProfileViewModel = viewModel()
) {
    val context = LocalContext.current
    val savedProfile by viewModel.profile.collectAsState()

    var mileage      by remember(savedProfile) { mutableStateOf(savedProfile.mileageKmPerLitre.toString()) }
    var fuelPrice    by remember(savedProfile) { mutableStateOf(savedProfile.fuelPricePerLitre.toString()) }
    var cngPrice     by remember(savedProfile) { mutableStateOf(savedProfile.cngPricePerKg.toString()) }
    var maintenance  by remember(savedProfile) { mutableStateOf(savedProfile.maintenancePerKm.toString()) }
    var depreciation by remember(savedProfile) { mutableStateOf(savedProfile.depreciationPerKm.toString()) }
    var minProfit    by remember(savedProfile) { mutableStateOf(savedProfile.minAcceptableNetProfit.toString()) }
    var minPerKm     by remember(savedProfile) { mutableStateOf(savedProfile.minAcceptablePerKm.toString()) }
    var targetPerHr  by remember(savedProfile) { mutableStateOf(savedProfile.targetEarningPerHour.toString()) }
    var commission   by remember(savedProfile) { mutableStateOf(savedProfile.platformCommissionPercent.toString()) }
    var errorMessage by remember { mutableStateOf("") }

    val isOverlayGranted = Settings.canDrawOverlays(context)
    val isAccessibilityGranted = isAccessibilityServiceEnabled(context)
    val isNotificationGranted = isNotificationPermissionGranted(context)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        // ── TOP BAR ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 52.dp, start = 8.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("← Back", color = RideGreen, fontSize = 15.sp)
            }
            Spacer(Modifier.weight(1f))
            Text("Settings", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Box(Modifier.width(60.dp))
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Text(
                "Configure costs, targets, and app preferences.",
                color = TextSecondary,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )

            Spacer(Modifier.height(24.dp))

            // ── SECTION: SERVICE STATUS ──
            SettingsGroupCard(title = "Service Status", icon = "🔌") {
                ServiceToggleRow(
                    label = "Overlay Window",
                    isEnabled = isOverlayGranted,
                    onToggle = {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:${context.packageName}")))
                    }
                )
                ServiceToggleRow(
                    label = "Accessibility Service",
                    isEnabled = isAccessibilityGranted,
                    onToggle = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                )
                ServiceToggleRow(
                    label = "Notifications",
                    isEnabled = isNotificationGranted,
                    onToggle = {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = android.net.Uri.parse("package:${context.packageName}")
                        })
                    }
                )
            }

            // ── SECTION: VEHICLE & FUEL ──
            SettingsGroupCard(title = "Vehicle & Fuel", icon = "🏍️") {
                SettingsInputField("Bike Mileage (km/L)", mileage, { mileage = it }, "Delhi avg is 45")
                SettingsInputField("Petrol Price (₹/L)", fuelPrice, { fuelPrice = it }, "Current city price")
                SettingsInputField("CNG Price (₹/kg)", cngPrice, { cngPrice = it }, "For CNG auto (~₹85)")
            }

            // ── SECTION: RUNNING COSTS ──
            SettingsGroupCard(title = "Running Costs", icon = "🛠️") {
                SettingsInputField("Maintenance (₹/km)", maintenance, { maintenance = it }, "Tyre, Oil, Service (~0.8)")
                SettingsInputField("Depreciation (₹/km)", depreciation, { depreciation = it }, "Value loss (~0.3)")
            }

            // ── SECTION: EARNING GOALS ──
            SettingsGroupCard(title = "Earning Goals", icon = "💰") {
                SettingsInputField("Min Profit / Ride (₹)", minProfit, { minProfit = it }, "Skip if profit < this")
                SettingsInputField("Min Net ₹/km", minPerKm, { minPerKm = it }, "Delhi avg is 3.5 - 5.0")
                SettingsInputField("Target ₹/hour", targetPerHr, { targetPerHr = it }, "Delhi avg is 150 - 250")
            }

            // ── SECTION: PLATFORM ──
            SettingsGroupCard(title = "Platform", icon = "📱") {
                SettingsInputField("Commission (%)", commission, { commission = it }, "Use 0 if fare is post-cut")
            }

            if (errorMessage.isNotEmpty()) {
                Text(errorMessage, color = SignalRed, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = {
                    val pMileage = mileage.toDoubleOrNull() ?: -1.0
                    val pFuel = fuelPrice.toDoubleOrNull() ?: -1.0
                    val pCng = cngPrice.toDoubleOrNull() ?: -1.0
                    val pMaint = maintenance.toDoubleOrNull() ?: -1.0
                    val pDepr = depreciation.toDoubleOrNull() ?: -1.0
                    val pMinProfit = minProfit.toDoubleOrNull()
                    val pMinKm = minPerKm.toDoubleOrNull()
                    val pHour = targetPerHr.toDoubleOrNull() ?: -1.0
                    val pComm = commission.toDoubleOrNull() ?: -1.0

                    if (pMileage <= 0 || pFuel <= 0 || pCng <= 0 || pMaint < 0 || pDepr < 0 ||
                        pMinProfit == null || pMinKm == null || pHour <= 0 || pComm < 0) {
                        errorMessage = "Please enter valid numbers in all fields"
                    } else {
                        viewModel.saveProfile(RiderProfile(
                            mileageKmPerLitre = pMileage,
                            fuelPricePerLitre = pFuel,
                            cngPricePerKg = pCng,
                            maintenancePerKm = pMaint,
                            depreciationPerKm = pDepr,
                            minAcceptableNetProfit = pMinProfit,
                            minAcceptablePerKm = pMinKm,
                            targetEarningPerHour = pHour,
                            platformCommissionPercent = pComm
                        ))
                        onBack()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = RideGreen),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Save & Apply Settings", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
fun SettingsGroupCard(title: String, icon: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        text = title.uppercase(),
        color = TextSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

@Composable
fun ServiceToggleRow(label: String, isEnabled: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                if (isEnabled) "Active" else "Tap to enable",
                color = if (isEnabled) RideGreen else TextSecondary,
                fontSize = 12.sp
            )
        }
        Switch(
            checked = isEnabled,
            onCheckedChange = { if (!isEnabled) onToggle() },
            colors = SwitchDefaults.colors(
                checkedTrackColor = RideGreen,
                checkedThumbColor = Color.White,
                uncheckedTrackColor = DarkBorder,
                uncheckedThumbColor = TextSecondary
            )
        )
    }
}

@Composable
fun SettingsInputField(label: String, value: String, onValueChange: (String) -> Unit, hint: String) {
    Column(modifier = Modifier.padding(bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Text(hint, color = TextSecondary, fontSize = 11.sp)
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
                focusedBorderColor = RideGreen,
                unfocusedBorderColor = DarkBorder,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedContainerColor = DarkBackground.copy(alpha = 0.5f),
                unfocusedContainerColor = DarkBackground.copy(alpha = 0.5f)
            )
        )
    }
}
