# Project Progress Log

## Project Overview
RideSmart (RideProfit Lens) is a real-time profitability analyzer for bike taxi and delivery captains. The app automatically detects ride offers from apps like Uber and Rapido, extracts fare and distance data, and calculates the net profit based on the driver's vehicle costs. It provides an immediate color-coded verdict (Accept/Skip) via a floating overlay.

## Technical Stack
- **Android Target:** API 35 (Android 15)
- **Min SDK:** API 26 (Android 8.0)
- **Primary Frameworks:** Jetpack Compose, Kotlin Coroutines, Preferences DataStore.
- **System Components:** AccessibilityService, NotificationListenerService, Foreground Service.
- **Permissions:** Accessibility, Overlay (System Alert Window), Notifications, Foreground Service (Special Use), Wake Lock.

## Progress Timeline

### [2025-05-22 – 10:00]
**Context:**
Initialization of core project logic.

**Issue / Requirement:**
Implement a mathematical engine to calculate ride profitability and a way to store vehicle costs.

**Action Taken:**
- Created data models: `ParsedRide`, `ProfitResult`, `RiderProfile`, and `RideResult`.
- Implemented `ProfitCalculator` handling fuel, maintenance, and depreciation costs.
- Configured `ProfileRepository` using Preferences DataStore.
- Built `ProfileSetupScreen` for user onboarding.

**Outcome:**
Core calculation engine and persistence layer verified working.

---

### [2025-05-22 – 11:30]
**Context:**
Data extraction from external ride-hailing apps.

**Issue / Requirement:**
Real-time extraction of fare and distance from the screen and system notifications.

**Action Taken:**
- Created `RideSmartService` (Accessibility Service) with multi-window scanning logic.
- Implemented `RideDataParser` using Regex patterns for Uber and Rapido.
- Created `RideNotificationListener` to capture background offers.
- Added auto-expansion logic to automatically pull down the notification shade to read collapsed ride offers.

**Outcome:**
Automated data pipeline (Detection -> Parsing -> Calculation) established.

---

### [2025-05-22 – 13:00]
**Context:**
User interface for real-time alerts.

**Issue / Requirement:**
Display the calculation result over the active ride app without user intervention.

**Action Taken:**
- Implemented `OverlayManager` using `WindowManager`.
- Created `overlay_ride_result.xml` layout with signal colors (🟢/🟡/🔴).
- Added lock screen support (`FLAG_SHOW_WHEN_LOCKED`, `FLAG_TURN_SCREEN_ON`).
- Implemented a 3-second deduplication guard to prevent UI flickering.

**Outcome:**
Floating verdict card successfully appears over Uber/Rapido, including on the lock screen.

---

### [2025-05-22 – 14:30]
**Context:**
Stability and Android 14+ system compliance.

**Issue / Requirement:**
The app was being killed in the background and required complex manual permission setup.

**Action Taken:**
- Converted the monitoring service to a **Foreground Service** with a persistent notification.
- Updated Manifest to use `foregroundServiceType="specialUse"` for API 34+ compliance.
- Redesigned `MainActivity` into a centralized **Permission Setup Hub**.
- Added automatic permission status refreshing in `onResume`.
- Downgraded `compileSdk` and `targetSdk` to 35 for stability.

**Outcome:**
App is persistent in background; setup process is user-friendly and compliant with latest Android standards.

---

### [2025-05-22 – 15:15]
**Context:**
Bug fixing and background delivery optimization.

**Issue / Requirement:**
Overlay was disappearing too fast (30ms bug) and accessibility events paused when the screen was off.

**Action Taken:**
- Fixed variable capture bug in `OverlayManager` auto-dismiss logic.
- Added `PARTIAL_WAKE_LOCK` to keep the CPU active during screen-off states.
- Implemented a `wakeScreen()` helper triggered by notification events.
- Cleaned up all diagnostic "Window scan" logging for production readiness.

**Outcome:**
Reliable overlay display (12s duration) and detection now functions even when the phone is locked.

---

### [2025-05-23 – 10:00]
**Context:**
Handling Simultaneous Multiple Ride Cards (Rapido Update).

**Issue / Requirement:**
Rapido now shows multiple ride cards on one screen. The parser was mixing values between cards, and the service was only showing the first one.

**Action Taken:**
- **RideDataParser.kt:** Implemented `parseAll()` to segment screen text by vehicle-type keywords (`Bike`, `Auto`, etc.).
- **RideDataParser.kt:** Fixed distance parsing to be position-aware (First KM after fare = Pickup, Second KM = Ride).
- **RideDataParser.kt:** Added support for "₹X + ₹Y" tip/bonus format.
- **RideSmartService.kt:** Rewrote `processScreen` to parse all visible rides and select the best using a new **Smart Score** formula.
- **RideSmartService.kt:** Moved heavy window scanning work to `Dispatchers.IO` to prevent UI lag.
- **OverlayManager.kt:** Added "BEST OF X" indicator when multiple rides are considered.

**Outcome:**
Successfully parses and compares multiple simultaneous offers, picking the one with the best profit-to-pickup ratio.

---

### [2025-05-23 – 11:30]
**Context:**
Intelligent Ride Selection and Platform Commission.

**Issue / Requirement:**
Selecting based on raw profit ignored the "hidden cost" of long pickups (risk of cancellation, lost position). Also, different platforms take different cuts.

**Action Taken:**
- **PlatformConfig.kt:** Created a central config for platform commissions (Uber 25%, Ola 20%, Rapido subscription).
- **ProfitCalculator.kt:** Integrated `PlatformConfig` to calculate **Effective Payout** instead of gross fare.
- **RideSmartService.kt:** Implemented **Smart Score** with non-linear **Cancellation Risk Penalty** (pickups over 4km are heavily penalized).
- **OverlayManager.kt:** Updated UI to show effective payout (e.g., `₹100 → ₹75`) and color-coded pickup efficiency.

**Outcome:**
Selection logic now favors high-quality, low-risk rides over high-fare, long-pickup "trap" rides. Persistent overlay issues fixed via improved deduplication.
