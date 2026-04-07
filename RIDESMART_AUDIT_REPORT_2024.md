# RideSmart Professional Audit Report (2024-2025)
**Status:** CRITICAL ISSUES IDENTIFIED
**Target Market:** India (Uber, Ola, Rapido, Shadowfax)

---

## Section 1 — Root Cause Analysis of the Primary Bug (Defaults ignoring User Input)

### 1.1 The "Destructive Save" in ProfileViewModel
**File:** `app/src/main/java/com/ridesmart/ui/ProfileViewModel.kt`
**Root Cause:** In `validateAndSave()`, the code constructs a brand new `RiderProfile()` object instead of updating the existing one. 
**Impact:** It fails to copy `platformPlans` and `incentiveProfiles`. Every time a user updates their petrol price or mileage, their specific platform commissions (like "Uber Pass" or "Rapido 15%") are wiped out and reset to empty maps. The app then falls back to hardcoded defaults.

### 1.2 Service Race Condition
**File:** `app/src/main/java/com/ridesmart/service/RideSmartService.kt`
**Root Cause:** `cachedProfile` is initialized using `stateIn(..., initialValue = RiderProfile())`. 
**Impact:** `RiderProfile()` contains hardcoded Delhi-based defaults (₹102 petrol, 45km/l). Since DataStore is asynchronous, the service uses these defaults for the first few ride calculations until the disk read completes.

### 1.3 Logic Override in ProfitCalculator
**File:** `app/src/main/java/com/ridesmart/engine/ProfitCalculator.kt`
**Root Cause:** Line 53-58 contains a check: `if (profile.mileageKmPerLitre == RiderProfile.DEFAULT_MILEAGE) ...`.
**Impact:** If a user actually has a bike with 45km/l mileage and saves it, the engine ignores the user's setting and forces a vehicle-type default instead.

---

## Section 2 — Complete Fix Plan

### 2.1 Critical Severity
*   **Fix Profile Update Logic:** Modify `ProfileViewModel` to use `.copy()` on the current state.
*   **Add "isConfigured" Flag:** Update `RiderProfile` model to include a boolean `isConfigured`.
*   **Service Guard:** Prevent `RideSmartService` from showing any overlay if `isConfigured` is false.

### 2.2 High Severity
*   **Validation Ranges:** Update `ProfileViewModel` ranges to 2024-2025 Indian reality:
    *   Mileage: 25 - 80 km/l (to support heavy bikes and mopeds).
    *   Fuel: ₹90 - ₹115 (current range).
    *   CNG: ₹70 - ₹95.
*   **Platform Priority:** Ensure `ProfitCalculator` checks `perPlatformPlan` before global commission.

### 2.3 Medium Severity
*   **Uber OCR Throttling:** Prevent "OCR Burn" by checking `isInteractive` and battery status.
*   **Incentive Resets:** Add a `BroadcastReceiver` for midnight resets so the app doesn't need a restart to clear "Completed Today" counts.

---

## Section 3 — Code Changes

### Model Update (`RiderProfile.kt`)
```kotlin
data class RiderProfile(
    val isConfigured: Boolean = false, // Critical flag
    val mileageKmPerLitre: Double = DEFAULT_MILEAGE,
    // ...
)
```

### ViewModel Fix (`ProfileViewModel.kt`)
```kotlin
// In validateAndSave()
val current = profile.value
saveProfile(current.copy(
    isConfigured = true,
    mileageKmPerLitre = pMileage,
    fuelPricePerLitre = pFuel,
    cngPricePerKg = pCng,
    maintenancePerKm = pMaint,
    depreciationPerKm = pDepr,
    minAcceptableNetProfit = pMinProfitVal!!,
    minAcceptablePerKm = pMinKmVal!!,
    targetEarningPerHour = pHour,
    dailyEarningTarget = pDailyTarget.coerceAtLeast(0.0),
    platformCommissionPercent = pComm,
    useCustomCommission = pComm > 0
))
```

### Service Guard (`RideSmartService.kt`)
```kotlin
private suspend fun processScreen(nodes: List<String>, pkg: String) {
    val profile = cachedProfile.value
    if (!profile.isConfigured) {
        Log.d(TAG, "Profile not configured. Ignoring calculation.")
        return 
    }
    // ...
}
```

---

## Section 4 — Verification Checklist
1.  **Commission Persistence:** Save a 10% commission for Uber. Update petrol price. Verify Uber commission is still 10%.
2.  **Mileage Sensitivity:** Set mileage to 30km/l. Verify that fuel cost in overlay increases significantly compared to the 45km/l default.
3.  **Onboarding Block:** Clear app data. Trigger a ride. No overlay should appear until the profile is saved once.

---

## Section 5 — Remaining Risks
*   **Supabase Security:** The API key is in `build.gradle.kts`. This must be moved to `local.properties`.
*   **Accessibility Node Latency:** Rapido's UI is complex; the node crawler may skip nodes on slower phones. Recommend increasing `visited` node limit to 500.
