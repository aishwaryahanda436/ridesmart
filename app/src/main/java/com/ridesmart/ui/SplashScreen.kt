package com.ridesmart.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridesmart.ui.theme.DarkBackground
import com.ridesmart.ui.theme.RideGreen
import kotlinx.coroutines.delay

/**
 * Ultra-Modern Splash Screen
 * Features a glass-morphism logo effect and animated glow.
 */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    var startAnimation by remember { mutableStateOf(false) }

    val infiniteTransition = rememberInfiniteTransition(label = "glow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_alpha"
    )

    val scale by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0.4f,
        animationSpec = tween(1000, easing = OvershootInterpolator(1.5f).toEasing()),
        label = "scale"
    )

    LaunchedEffect(Unit) {
        startAnimation = true
        delay(2200L)
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground),
        contentAlignment = Alignment.Center
    ) {
        // Background Glow
        Box(
            modifier = Modifier
                .size(250.dp)
                .blur(80.dp)
                .alpha(glowAlpha)
                .background(
                    Brush.radialGradient(
                        colors = listOf(RideGreen.copy(alpha = 0.4f), Color.Transparent)
                    ),
                    shape = CircleShape
                )
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.scale(scale)
        ) {
            // Animated Logo Container
            Box(
                modifier = Modifier
                    .size(130.dp)
                    .clip(RoundedCornerShape(36.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(Color.White.copy(alpha = 0.1f), Color.White.copy(alpha = 0.02f))
                        )
                    )
                    .padding(1.dp)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(Color.White.copy(alpha = 0.05f), Color.Transparent)
                        ),
                        shape = RoundedCornerShape(36.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text("🚀", fontSize = 64.sp)
            }

            Spacer(Modifier.height(32.dp))

            Text(
                text = "RideSmart",
                color = Color.White,
                fontSize = 42.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-1.5).sp
            )

            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(RideGreen, CircleShape)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "URPDE ENGINE v2.0",
                    color = RideGreen,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 3.sp
                )
            }
        }

        // Version hint at bottom
        Text(
            text = "PRODUCTION READY",
            color = Color.White.copy(alpha = 0.2f),
            fontSize = 10.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp),
            letterSpacing = 2.sp
        )
    }
}

private fun android.view.animation.Interpolator.toEasing() = Easing { x -> getInterpolation(x) }
class OvershootInterpolator(private val tension: Float = 2f) : android.view.animation.Interpolator {
    override fun getInterpolation(t: Float): Float {
        val t1 = t - 1.0f
        return t1 * t1 * ((tension + 1) * t1 + tension) + 1.0f
    }
}
