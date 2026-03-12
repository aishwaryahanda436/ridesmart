package com.ridesmart.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ridesmart.model.RiderProfile
import com.ridesmart.ui.theme.*

@Composable
fun ProfileSetupScreen(
    onSaved: () -> Unit,
    viewModel: ProfileViewModel = viewModel()
) {
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        // ── TOP NAVIGATION ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 52.dp, start = 8.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onSaved) {
                Text("← Back", color = RideGreen, fontSize = 15.sp)
            }
            Spacer(Modifier.weight(1f))
            Text("Earning Targets", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Box(Modifier.width(60.dp)) // Symmetry
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Text(
                "Configure your costs and targets to get accurate GREEN/RED signals.",
                color = TextSecondary,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )

            Spacer(Modifier.height(24.dp))

            // ── SECTION: VEHICLE & FUEL ──
            ProfileGroupCard(title = "Vehicle & Fuel", icon = "🏍️") {
                ProfileInputField("Bike Mileage (km/L)", mileage, { mileage = it }, "Delhi avg is 45")
                ProfileInputField("Petrol Price (₹/L)", fuelPrice, { fuelPrice = it }, "Current city price")
                ProfileInputField("CNG Price (₹/kg)", cngPrice, { cngPrice = it }, "For CNG auto (~₹85)")
            }

            // ── SECTION: RUNNING COSTS ──
            ProfileGroupCard(title = "Running Costs", icon = "🛠️") {
                ProfileInputField("Maintenance (₹/km)", maintenance, { maintenance = it }, "Tyre, Oil, Service (~0.8)")
                ProfileInputField("Depreciation (₹/km)", depreciation, { depreciation = it }, "Value loss (~0.3)")
            }

            // ── SECTION: EARNING GOALS ──
            ProfileGroupCard(title = "Earning Goals", icon = "💰") {
                ProfileInputField("Min Profit / Ride (₹)", minProfit, { minProfit = it }, "Skip if profit < this")
                ProfileInputField("Min Net ₹/km", minPerKm, { minPerKm = it }, "Delhi avg is 3.5 - 5.0")
                ProfileInputField("Target ₹/hour", targetPerHr, { targetPerHr = it }, "Delhi avg is 150 - 250")
            }

            // ── SECTION: ADVANCED ──
            ProfileGroupCard(title = "Platform", icon = "📱") {
                ProfileInputField("Commission (%)", commission, { commission = it }, "Use 0 if fare is post-cut")
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
                        onSaved()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = RideGreen),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Save & Apply Settings", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
fun ProfileGroupCard(title: String, icon: String, content: @Composable ColumnScope.() -> Unit) {
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
fun ProfileInputField(label: String, value: String, onValueChange: (String) -> Unit, hint: String) {
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
