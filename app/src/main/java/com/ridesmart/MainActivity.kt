package com.ridesmart

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ridesmart.ui.*
import com.ridesmart.ui.theme.*

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RidesmartTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBackground
                ) {
                    RideSmartApp()
                }
            }
        }

        requestOverlayPermissionIfNeeded()
    }

    private fun requestOverlayPermissionIfNeeded() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }
}

/**
 * App navigation — state-based routing between screens.
 * Flow: Splash → Profile Setup (if first time) → Main
 */
@Composable
fun RideSmartApp(profileViewModel: ProfileViewModel = viewModel()) {
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Splash) }
    val hasCompletedSetup by profileViewModel.hasCompletedSetup.collectAsState()

    // Handle initial routing after splash
    val onSplashFinished = {
        currentScreen = if (!hasCompletedSetup) {
            Screen.ProfileSetup
        } else {
            Screen.Main
        }
    }

    when (currentScreen) {
        Screen.Splash -> SplashScreen(
            onFinished = onSplashFinished
        )
        Screen.Main -> PlaceholderScreen(
            title = "Main Driver Screen",
            onNavigateSettings = { currentScreen = Screen.ProfileSetup }
        )
        Screen.ProfileSetup -> ProfileSetupScreen(
            onSaved = { currentScreen = Screen.Main },
            viewModel = profileViewModel
        )
    }
}

/** Navigation destinations */
sealed class Screen {
    data object Splash : Screen()
    data object Main : Screen()
    data object ProfileSetup : Screen()
}

/**
 * Placeholder for the main driver screen.
 * Other screens (History, Dashboard, Settings, Permissions) will be added later.
 */
@Composable
fun PlaceholderScreen(title: String, onNavigateSettings: () -> Unit) {
    androidx.compose.foundation.layout.Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            color = androidx.compose.ui.graphics.Color.White,
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onNavigateSettings) {
            Text("Edit Profile", color = RideGreen)
        }
    }
}
