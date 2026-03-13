package com.ridesmart.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.ElectricRickshaw
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridesmart.ui.ProfileViewModel
import com.ridesmart.ui.theme.*

@Composable
fun VehicleSetupScreen(
    viewModel: ProfileViewModel,
    onContinue: () -> Unit
) {
    val savedVehicleType by viewModel.vehicleType.collectAsState()
    val savedProfile by viewModel.profile.collectAsState()

    var selectedType by remember(savedVehicleType) { mutableStateOf(savedVehicleType) }
    var mileage by remember(savedProfile) { mutableStateOf(savedProfile.mileageKmPerLitre.toString()) }
    var errorMessage by remember { mutableStateOf("") }

    val vehicleTypes = listOf(
        VehicleOption("Bike", Icons.Default.DirectionsBike, "45 km/l avg"),
        VehicleOption("Auto", Icons.Default.ElectricRickshaw, "22 km/l avg"),
        VehicleOption("Car", Icons.Default.DirectionsCar, "15 km/l avg")
    )

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
                "Vehicle Setup",
                color = TextPrimary,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Select your vehicle type and enter mileage for accurate fuel cost calculation.",
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
            // Vehicle Type Selection
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        "Vehicle Type",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        vehicleTypes.forEach { option ->
                            VehicleTypeCard(
                                option = option,
                                isSelected = selectedType == option.name,
                                onClick = {
                                    selectedType = option.name
                                    // Auto-fill default mileage
                                    mileage = when (option.name) {
                                        "Bike" -> "45"
                                        "Auto" -> "22"
                                        "Car" -> "15"
                                        else -> mileage
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // Mileage Input
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Mileage",
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "km per liter",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = mileage,
                        onValueChange = { mileage = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("e.g. 40", color = TextMuted) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        suffix = { Text("km/l", color = TextSecondary, fontSize = 14.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = RideGreen,
                            unfocusedBorderColor = DarkBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedContainerColor = DarkBackground.copy(alpha = 0.5f),
                            unfocusedContainerColor = DarkBackground.copy(alpha = 0.5f),
                            cursorColor = RideGreen
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "This is used to calculate fuel cost per kilometer.",
                        color = TextMuted,
                        fontSize = 12.sp
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
                "Step 3 of 5",
                color = TextMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    val mileageVal = mileage.toDoubleOrNull()
                    if (mileageVal == null || mileageVal <= 0) {
                        errorMessage = "Please enter a valid mileage"
                    } else {
                        errorMessage = ""
                        viewModel.saveVehicleSetup(selectedType, mileageVal)
                        onContinue()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = RideGreen),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Continue", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
}

data class VehicleOption(
    val name: String,
    val icon: ImageVector,
    val hint: String
)

@Composable
fun VehicleTypeCard(
    option: VehicleOption,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = if (isSelected) RideGreen else DarkBorder
    val bgColor = if (isSelected) RideGreen.copy(alpha = 0.08f) else Color.Transparent

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .border(1.5.dp, borderColor, RoundedCornerShape(14.dp))
            .background(bgColor)
            .clickable { onClick() }
            .padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            option.icon,
            contentDescription = null,
            tint = if (isSelected) RideGreen else TextSecondary,
            modifier = Modifier.size(32.dp)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            option.name,
            color = if (isSelected) TextPrimary else TextSecondary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            option.hint,
            color = TextMuted,
            fontSize = 10.sp
        )
    }
}
