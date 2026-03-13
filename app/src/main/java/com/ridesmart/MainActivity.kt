package com.ridesmart

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ridesmart.ui.*
import com.ridesmart.ui.onboarding.*
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
    }
}

/**
 * App navigation — state-based routing between screens.
 * Flow: Splash → Onboarding (Permissions → Profile → Vehicle → Payment → Costs) → Dashboard
 */
@Composable
fun RideSmartApp(profileViewModel: ProfileViewModel = viewModel()) {
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Splash) }
    val hasCompletedSetup by profileViewModel.hasCompletedSetup.collectAsState()

    // Handle initial routing after splash
    val onSplashFinished = {
        currentScreen = if (!hasCompletedSetup) {
            Screen.PermissionSetup
        } else {
            Screen.Main
        }
    }

    when (currentScreen) {
        Screen.Splash -> SplashScreen(
            onFinished = onSplashFinished
        )

        // ── ONBOARDING FLOW ──
        Screen.PermissionSetup -> PermissionSetupScreen(
            onContinue = { currentScreen = Screen.DriverProfile }
        )
        Screen.DriverProfile -> DriverProfileScreen(
            viewModel = profileViewModel,
            onContinue = { currentScreen = Screen.VehicleSetup }
        )
        Screen.VehicleSetup -> VehicleSetupScreen(
            viewModel = profileViewModel,
            onContinue = { currentScreen = Screen.PlatformPayment }
        )
        Screen.PlatformPayment -> PlatformPaymentScreen(
            viewModel = profileViewModel,
            onContinue = { currentScreen = Screen.OperatingCost }
        )
        Screen.OperatingCost -> OperatingCostScreen(
            viewModel = profileViewModel,
            onComplete = { currentScreen = Screen.Main }
        )

        // ── MAIN APP ──
        Screen.Main -> MainDriverScreen(
            onNavigateHistory = { currentScreen = Screen.RideHistory },
            onNavigateDashboard = { currentScreen = Screen.Dashboard },
            onNavigateSettings = { currentScreen = Screen.Settings },
            onNavigatePermissions = { currentScreen = Screen.PermissionSetup }
        )
        Screen.RideHistory -> RideHistoryScreen(
            onBack = { currentScreen = Screen.Main }
        )
        Screen.Dashboard -> DashboardScreen(
            onBack = { currentScreen = Screen.Main }
        )
        Screen.Settings -> SettingsScreen(
            onBack = { currentScreen = Screen.Main }
        )
    }
}

/** Navigation destinations */
sealed class Screen {
    data object Splash : Screen()

    // Onboarding flow
    data object PermissionSetup : Screen()
    data object DriverProfile : Screen()
    data object VehicleSetup : Screen()
    data object PlatformPayment : Screen()
    data object OperatingCost : Screen()

    // Main app
    data object Main : Screen()
    data object RideHistory : Screen()
    data object Dashboard : Screen()
    data object Settings : Screen()
}
