package com.ridesmart.ui.onboarding

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
import com.ridesmart.ui.ProfileViewModel
import com.ridesmart.ui.theme.*

@Composable
fun OperatingCostScreen(
    viewModel: ProfileViewModel,
    onComplete: () -> Unit
) {
    val savedProfile by viewModel.profile.collectAsState()

    var fuelPrice by remember(savedProfile) { mutableStateOf(savedProfile.fuelPricePerLitre.toString()) }
    var maintenance by remember(savedProfile) { mutableStateOf(savedProfile.maintenancePerKm.toString()) }
    var serviceCharge by remember { mutableStateOf("0.0") }
    var errorMessage by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        // Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 56.dp, start = 24.dp, end = 24.dp)
        ) {
            Text(
                "Operating Costs",
                color = TextPrimary,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Configure your running costs for accurate profit calculations.",
                color = TextSecondary,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
        }

        Spacer(Modifier.height(24.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
        ) {
            // Fuel Price
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        "Fuel Price",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Current price of fuel in your city",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = fuelPrice,
                        onValueChange = { fuelPrice = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("e.g. 94.77", color = TextMuted) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        prefix = { Text("₹", color = TextSecondary, fontSize = 16.sp) },
                        suffix = { Text("per liter", color = TextSecondary, fontSize = 13.sp) },
                        colors = onboardingTextFieldColors()
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Maintenance Cost
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        "Maintenance Cost",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Average maintenance cost including tyres, oil, service",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = maintenance,
                        onValueChange = { maintenance = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("e.g. 0.80", color = TextMuted) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        prefix = { Text("₹", color = TextSecondary, fontSize = 16.sp) },
                        suffix = { Text("per km", color = TextSecondary, fontSize = 13.sp) },
                        colors = onboardingTextFieldColors()
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Platform Service Charge (Optional)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Platform Service Charge",
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            color = RideGreen.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                "Optional",
                                color = RideGreen,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Any additional service charges deducted per ride",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = serviceCharge,
                        onValueChange = { serviceCharge = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("e.g. 0", color = TextMuted) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        prefix = { Text("₹", color = TextSecondary, fontSize = 16.sp) },
                        colors = onboardingTextFieldColors()
                    )
                }
            }

            if (errorMessage.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(errorMessage, color = SignalRed, fontSize = 13.sp)
            }

            Spacer(Modifier.height(24.dp))
        }

        // Bottom button
        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                "Step 5 of 5 — Final Step",
                color = TextMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    val fuel = fuelPrice.toDoubleOrNull()
                    val maint = maintenance.toDoubleOrNull()
                    val svcCharge = serviceCharge.toDoubleOrNull() ?: 0.0

                    if (fuel == null || fuel <= 0) {
                        errorMessage = "Please enter a valid fuel price"
                    } else if (maint == null || maint < 0) {
                        errorMessage = "Please enter a valid maintenance cost"
                    } else {
                        errorMessage = ""
                        viewModel.saveOperatingCosts(fuel, maint, svcCharge)
                        onComplete()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = RideGreen),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Complete Setup", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
}
