package com.ridesmart.ui.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridesmart.ui.ProfileViewModel
import com.ridesmart.ui.theme.*

@Composable
fun PlatformPaymentScreen(
    viewModel: ProfileViewModel,
    onContinue: () -> Unit
) {
    val savedModel by viewModel.paymentModel.collectAsState()
    val savedProfile by viewModel.profile.collectAsState()

    var selectedModel by remember(savedModel) { mutableStateOf(savedModel) }
    var commissionPercent by remember(savedProfile) {
        mutableStateOf(savedProfile.platformCommissionPercent.toString())
    }
    var dailyPassCost by remember(savedProfile) {
        mutableStateOf(savedProfile.subscriptionDailyCost.toString())
    }
    var rentalCost by remember { mutableStateOf("0.0") }
    var errorMessage by remember { mutableStateOf("") }

    val paymentModels = listOf(
        PaymentModelOption("commission", "Commission Based", "Platform takes a percentage of each ride fare"),
        PaymentModelOption("daily_pass", "Daily Pass", "Pay a fixed daily amount to the platform"),
        PaymentModelOption("rental", "Rental Model", "Pay daily rental cost for the vehicle")
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
                "Payment Model",
                color = TextPrimary,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "How does your platform charge you? This affects profit calculation.",
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
            // Payment Model Selection
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        "Select Payment Model",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(16.dp))

                    paymentModels.forEach { model ->
                        PaymentModelItem(
                            option = model,
                            isSelected = selectedModel == model.id,
                            onClick = { selectedModel = model.id }
                        )
                        if (model != paymentModels.last()) {
                            Spacer(Modifier.height(10.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // Dynamic fields based on selection
            AnimatedVisibility(visible = selectedModel == "commission") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkCard),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            "Commission Percentage",
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Percentage the platform takes from each ride",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = commissionPercent,
                            onValueChange = { commissionPercent = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("e.g. 20", color = TextMuted) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            suffix = { Text("%", color = TextSecondary, fontSize = 16.sp) },
                            colors = onboardingTextFieldColors()
                        )
                    }
                }
            }

            AnimatedVisibility(visible = selectedModel == "daily_pass") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkCard),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            "Daily Pass Cost",
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Fixed amount paid daily to the platform",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = dailyPassCost,
                            onValueChange = { dailyPassCost = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("e.g. 150", color = TextMuted) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            prefix = { Text("₹", color = TextSecondary, fontSize = 16.sp) },
                            colors = onboardingTextFieldColors()
                        )
                    }
                }
            }

            AnimatedVisibility(visible = selectedModel == "rental") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkCard),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            "Daily Rental Cost",
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Daily cost for renting the vehicle",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = rentalCost,
                            onValueChange = { rentalCost = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("e.g. 500", color = TextMuted) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            prefix = { Text("₹", color = TextSecondary, fontSize = 16.sp) },
                            colors = onboardingTextFieldColors()
                        )
                    }
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
                "Step 4 of 5",
                color = TextMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    val commission = commissionPercent.toDoubleOrNull() ?: 0.0
                    val passCost = dailyPassCost.toDoubleOrNull() ?: 0.0
                    val rental = rentalCost.toDoubleOrNull() ?: 0.0

                    when (selectedModel) {
                        "commission" -> {
                            if (commission < 0 || commission > 100) {
                                errorMessage = "Commission must be between 0 and 100%"
                                return@Button
                            }
                        }
                        "daily_pass" -> {
                            if (passCost < 0) {
                                errorMessage = "Daily pass cost must be a positive number"
                                return@Button
                            }
                        }
                        "rental" -> {
                            if (rental < 0) {
                                errorMessage = "Rental cost must be a positive number"
                                return@Button
                            }
                        }
                    }

                    errorMessage = ""
                    viewModel.savePlatformPayment(selectedModel, commission, passCost, rental)
                    onContinue()
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

data class PaymentModelOption(
    val id: String,
    val title: String,
    val description: String
)

@Composable
fun PaymentModelItem(
    option: PaymentModelOption,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) RideGreen else DarkBorder
    val bgColor = if (isSelected) RideGreen.copy(alpha = 0.08f) else Color.Transparent

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .background(bgColor)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(
            option.title,
            color = TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(2.dp))
        Text(
            option.description,
            color = TextSecondary,
            fontSize = 12.sp
        )
    }
}

@Composable
fun onboardingTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = RideGreen,
    unfocusedBorderColor = DarkBorder,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    focusedContainerColor = DarkBackground.copy(alpha = 0.5f),
    unfocusedContainerColor = DarkBackground.copy(alpha = 0.5f),
    cursorColor = RideGreen
)
