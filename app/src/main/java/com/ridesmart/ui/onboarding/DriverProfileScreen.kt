package com.ridesmart.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridesmart.ui.ProfileViewModel
import com.ridesmart.ui.theme.*

@Composable
fun DriverProfileScreen(
    viewModel: ProfileViewModel,
    onContinue: () -> Unit
) {
    val savedName by viewModel.driverName.collectAsState()
    val savedPlatforms by viewModel.platformsUsed.collectAsState()

    var name by remember(savedName) { mutableStateOf(savedName) }
    var selectedPlatforms by remember(savedPlatforms) {
        mutableStateOf(savedPlatforms.ifEmpty { emptySet() })
    }
    var errorMessage by remember { mutableStateOf("") }

    val availablePlatforms = listOf("Uber", "Rapido", "Ola", "Shadowfax")

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
                "Driver Profile",
                color = TextPrimary,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Tell us about yourself and which platforms you drive for.",
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
            // Driver Name
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        "Driver Name",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Enter your name", color = TextMuted) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
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
                }
            }

            Spacer(Modifier.height(20.dp))

            // Platforms Used
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        "Platforms Used",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Select all platforms you drive for",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(16.dp))

                    availablePlatforms.forEach { platform ->
                        val isSelected = selectedPlatforms.contains(platform)
                        PlatformCheckboxItem(
                            name = platform,
                            isSelected = isSelected,
                            onToggle = {
                                selectedPlatforms = if (isSelected) {
                                    selectedPlatforms - platform
                                } else {
                                    selectedPlatforms + platform
                                }
                            }
                        )
                        if (platform != availablePlatforms.last()) {
                            Spacer(Modifier.height(8.dp))
                        }
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
            // Step indicator
            Text(
                "Step 2 of 5",
                color = TextMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    if (name.isBlank()) {
                        errorMessage = "Please enter your name"
                    } else if (selectedPlatforms.isEmpty()) {
                        errorMessage = "Please select at least one platform"
                    } else {
                        errorMessage = ""
                        viewModel.saveDriverProfile(name.trim(), selectedPlatforms)
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

@Composable
fun PlatformCheckboxItem(
    name: String,
    isSelected: Boolean,
    onToggle: () -> Unit
) {
    val borderColor = if (isSelected) RideGreen else DarkBorder
    val bgColor = if (isSelected) RideGreen.copy(alpha = 0.08f) else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .background(bgColor)
            .clickable { onToggle() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            name,
            color = TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        if (isSelected) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = RideGreen,
                modifier = Modifier.size(22.dp)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .border(2.dp, DarkBorder, RoundedCornerShape(11.dp))
            )
        }
    }
}
