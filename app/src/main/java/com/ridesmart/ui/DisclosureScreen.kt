package com.ridesmart.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridesmart.R

@Composable
fun DisclosureScreen(
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    val bgColor = Color(0xFF0F0F13)
    val green   = Color(0xFF3DDC84)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        // ── ICON ──
        Surface(
            modifier = Modifier.size(80.dp),
            color = green.copy(alpha = 0.1f),
            shape = RoundedCornerShape(24.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Visibility,
                    contentDescription = null,
                    tint = green,
                    modifier = Modifier.size(40.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // ── TITLE ──
        Text(
            text = stringResource(R.string.disclosure_title),
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        // ── MAIN DISCLOSURE TEXT ──
        Text(
            text = stringResource(R.string.disclosure_main_text),
            color = Color(0xFFE2E2EC),
            fontSize = 16.sp,
            lineHeight = 24.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        // ── KEY POINTS ──
        DisclosurePoint(
            icon = Icons.Default.Info,
            title = stringResource(R.string.why_need_title),
            description = stringResource(R.string.why_need_desc)
        )

        Spacer(modifier = Modifier.height(16.dp))

        DisclosurePoint(
            icon = Icons.Default.Info,
            title = stringResource(R.string.privacy_title),
            description = stringResource(R.string.privacy_desc)
        )

        Spacer(modifier = Modifier.height(16.dp))

        DisclosurePoint(
            icon = Icons.Default.Info,
            title = stringResource(R.string.no_automation_title),
            description = stringResource(R.string.no_automation_desc)
        )

        Spacer(modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.height(32.dp))

        // ── ACTIONS ──
        Button(
            onClick = onAccept,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = green),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(stringResource(R.string.agree_continue), color = Color.Black, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(12.dp))

        TextButton(onClick = onDecline) {
            Text(stringResource(R.string.no_exit_app), color = Color(0xFF6B6B85))
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun DisclosurePoint(icon: ImageVector, title: String, description: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF16161C), RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Icon(icon, contentDescription = null, tint = Color(0xFF3DDC84), modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text(description, color = Color(0xFF6B6B85), fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}
