package com.ridesmart.ui.onboarding

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridesmart.isAccessibilityServiceEnabled
import com.ridesmart.ui.theme.*

@Composable
fun PermissionSetupScreen(onContinue: () -> Unit) {
    val context = LocalContext.current

    var accessibilityGranted by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    // Refresh permission states when the screen is resumed
    LaunchedEffect(Unit) {
        accessibilityGranted = isAccessibilityServiceEnabled(context)
        overlayGranted = Settings.canDrawOverlays(context)
    }

    val requiredGranted = accessibilityGranted && overlayGranted

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
                "Permissions",
                color = TextPrimary,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "RideSmart needs these permissions to detect ride offers and show profit analysis.",
                color = TextSecondary,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
        }

        Spacer(Modifier.height(24.dp))

        // Permission Cards
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            PermissionCard(
                icon = Icons.Default.Accessibility,
                title = "Accessibility Service",
                description = "Used to read ride offer popup UI elements from driver apps.",
                isGranted = accessibilityGranted,
                isRequired = true,
                onEnable = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            )

            PermissionCard(
                icon = Icons.Default.Layers,
                title = "Draw Over Other Apps",
                description = "Required to display the profit popup overlay on top of ride apps.",
                isGranted = overlayGranted,
                isRequired = true,
                onEnable = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                }
            )

            PermissionCard(
                icon = Icons.Default.Screenshot,
                title = "Screen Capture",
                description = "Used for OCR when ride apps block accessibility data.",
                isGranted = false,
                isRequired = false,
                onEnable = { /* Handled during service runtime */ }
            )

            PermissionCard(
                icon = Icons.Default.Notifications,
                title = "Notification Access",
                description = "Used to detect ride notifications faster (optional).",
                isGranted = false,
                isRequired = false,
                onEnable = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                    )
                }
            )

            Spacer(Modifier.height(8.dp))
        }

        // Bottom button
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
        ) {
            // Refresh button
            TextButton(
                onClick = {
                    accessibilityGranted = isAccessibilityServiceEnabled(context)
                    overlayGranted = Settings.canDrawOverlays(context)
                },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text("Refresh Permission Status", color = RideGreen, fontSize = 14.sp)
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = onContinue,
                enabled = requiredGranted,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = RideGreen,
                    disabledContainerColor = DarkSurfaceVariant
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    if (requiredGranted) "Continue" else "Grant Required Permissions",
                    color = if (requiredGranted) Color.White else TextMuted,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }
    }
}

@Composable
fun PermissionCard(
    icon: ImageVector,
    title: String,
    description: String,
    isGranted: Boolean,
    isRequired: Boolean,
    onEnable: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Icon
            Surface(
                modifier = Modifier.size(48.dp),
                color = if (isGranted) RideGreen.copy(alpha = 0.15f) else RideGreen.copy(alpha = 0.1f),
                shape = RoundedCornerShape(14.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = RideGreen,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (isRequired) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            color = SignalRed.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                "Required",
                                color = SignalRed,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    description,
                    color = TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(12.dp))

                if (isGranted) {
                    Surface(
                        color = RideGreen.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = RideGreen,
                                modifier = Modifier.size(16.dp)
                            )
                            Text("Enabled", color = RideGreen, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                } else {
                    OutlinedButton(
                        onClick = onEnable,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = RideGreen),
                        border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                            brush = androidx.compose.ui.graphics.SolidColor(RideGreen.copy(alpha = 0.5f))
                        )
                    ) {
                        Text("Enable", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
