# 🏍️ RideSmart

**Real-time ride profitability analyzer for bike taxi and delivery drivers in India.**

RideSmart automatically monitors ride offers from apps like Uber, Rapido, Ola, and others, calculates net profit after fuel, wear & tear, and platform commissions, and displays an instant color-coded verdict (🟢 ACCEPT / 🟡 BORDERLINE / 🔴 SKIP) via a floating overlay — all in real time while the driver views ride offers.

---

## 📋 Table of Contents

- [Features](#-features)
- [Supported Platforms](#-supported-platforms)
- [How It Works](#-how-it-works)
- [Project Structure](#-project-structure)
- [Tech Stack](#-tech-stack)
- [Prerequisites](#-prerequisites)
- [Getting Started](#-getting-started)
- [Permissions](#-permissions)
- [Configuration](#-configuration)
- [Architecture](#-architecture)
- [Testing](#-testing)
- [Contributing](#-contributing)

---

## ✨ Features

### Real-Time Ride Analysis
- **Automatic detection** of ride offers using Android Accessibility Services
- **Instant profit calculation** considering fuel costs, maintenance, depreciation, and platform commissions
- **Color-coded floating overlay** displayed over ride-hailing apps with accept/skip verdicts
- **Notification monitoring** to capture background ride offers

### Smart Profit Engine
- **Net profit breakdown**: fare → payout (after commission) → minus fuel → minus wear & tear → net profit
- **Earning metrics**: ₹/km (earning per kilometer) and ₹/hr (earning per hour)
- **Pickup ratio analysis**: flags rides with excessive unpaid dead miles
- **Configurable thresholds**: minimum net profit, minimum ₹/km, target ₹/hr, and maximum pickup ratio

### Signal Decision System
| Signal | Condition | Meaning |
|--------|-----------|---------|
| 🟢 **GREEN** | 0 failed checks | All metrics met — accept the ride |
| 🟡 **YELLOW** | 1–2 failed checks | Borderline — use judgment |
| 🔴 **RED** | 3+ failed checks | Poor profitability — skip |

### Overlay & HUD System
- **Floating verdict card** with fare breakdown, net profit, ₹/km, and pickup distance
- **Commission visualization**: shows fare before and after commission (e.g., ₹45 → ₹36)
- **Platform-specific positioning**: Uber overlays at the top, others at the bottom
- **Auto-dismiss** with a 12-second animated countdown
- **Lock screen support**: overlays display even when the device is locked
- **Screen wake**: automatically wakes the device when a new offer arrives
- **Compact HUD mode**: minimal right-center floating card with 3-second auto-dismiss and vibration for high-value rides
- **Draggable overlays**: reposition the verdict card anywhere on screen

### Smart Scoring & Session Tracking
- **Session caching**: remembers the best offer seen in the last 90 seconds
- **Comparison messaging**: shows "⭐ Best: ₹X = ₹Y net" when a better card was previously seen
- **Card counter**: displays "CARD N SEEN" across multiple offers
- **Deduplication**: 15-second cooldown prevents duplicate overlays for the same fare

### Vehicle Cost Profiles
Customize your vehicle's cost parameters for accurate calculations:

| Vehicle Type | Default Mileage | Fuel Type | Wear Multiplier |
|-------------|----------------|-----------|-----------------|
| Bike | 45 km/L | Petrol | 1.0× |
| Bike Boost | 45 km/L | Petrol | 1.0× |
| Auto | 22 km/L | Petrol | 1.6× |
| CNG Auto | 28 km/kg | CNG | 1.6× |
| e-Bike | — | Electric | 0.7× |
| Car | 15 km/L | Petrol | 2.3× |

### Additional Features
- **Foreground Service** with persistent notification (Android 14+ compliant)
- **Boot auto-start**: resumes monitoring after device restart
- **Ride history storage** for analytics and tracking
- **Battery optimization** handling with wake locks
- **Multi-window accessibility scanning** across all visible windows

---

## 🚗 Supported Platforms

| Platform | Commission | Notes |
|----------|-----------|-------|
| **Rapido** | 0% | Subscription-based model |
| **Uber** | 0% | Uses OCR engine for data extraction |
| **Ola** | 20% | Standard platform commission |
| **Namma Yatri** | 0% | Open-source ride platform |
| **Swiggy** | 0% | Food delivery |
| **Zomato** | 0% | Food delivery |
| **Porter** | 15% | Logistics and delivery |

---

## 🔧 How It Works

```
┌─────────────────────────────────────────────────────────────┐
│                     Ride-Hailing App                        │
│                  (Uber, Rapido, Ola, …)                     │
└──────────────────────┬──────────────────────────────────────┘
                       │ Accessibility Events / Notifications
                       ▼
┌─────────────────────────────────────────────────────────────┐
│              RideSmartService (AccessibilityService)         │
│  • Scans accessibility tree across all visible windows      │
│  • Detects ride offers by screen state                      │
│  • Polls Uber with adaptive backoff (2.5s → 10s)           │
└──────────────────────┬──────────────────────────────────────┘
                       │ Raw screen nodes
                       ▼
┌─────────────────────────────────────────────────────────────┐
│                    ParserFactory                             │
│  Routes to platform-specific parser:                        │
│  • RapidoParser — label-guided + regex extraction           │
│  • NammaYatriParser — two-pass label + regex fallback       │
│  • UberOcrEngine — ML Kit OCR + accessibility fallback      │
│  • RideDataParser — generic regex-based parser              │
└──────────────────────┬──────────────────────────────────────┘
                       │ ParsedRide
                       ▼
┌─────────────────────────────────────────────────────────────┐
│                   ProfitCalculator                           │
│  1. Effective payout (after commission)                     │
│  2. Total distance (pickup + ride)                          │
│  3. Fuel cost (mileage × fuel price)                        │
│  4. Wear & tear (maintenance + depreciation × multiplier)   │
│  5. Net profit                                              │
│  6. ₹/km, ₹/hr, pickup ratio                               │
│  7. Threshold checks → Signal (GREEN/YELLOW/RED)            │
└──────────────────────┬──────────────────────────────────────┘
                       │ ProfitResult + Signal
                       ▼
┌─────────────────────────────────────────────────────────────┐
│              OverlayManager / HudOverlayManager             │
│  • Floating verdict card over the ride app                  │
│  • Color-coded signal bar + fare breakdown                  │
│  • Auto-dismiss with countdown                              │
│  • Lock screen + screen wake support                        │
└─────────────────────────────────────────────────────────────┘
```

### Example Calculation

```
Ride: ₹37 fare, 5.2 km ride, 1.1 km pickup
Vehicle: Bike — 45 km/L petrol at ₹102, ₹0.80 maintenance, ₹0.30 depreciation

Payout:       ₹37.00  (0% commission)
Total dist:   6.3 km  (5.2 + 1.1)
Fuel cost:   −₹14.28  (6.3 ÷ 45 × ₹102)
Wear cost:   −₹6.93   (6.3 × (₹0.80 + ₹0.30) × 1.0)
─────────────────────
Net profit:   ₹15.79
₹/km:         ₹3.04   (₹15.79 ÷ 5.2 km)
Pickup ratio: 21%      (1.1 ÷ 5.2)

Checks:
  ✗ Net ₹15.79 < minimum ₹30     → FAIL
  ✗ ₹/km ₹3.04 < target ₹3.50   → FAIL
  ✓ Pickup 21% < max 40%          → PASS

Signal: 🟡 YELLOW (2 failed checks)
```

---

## 📁 Project Structure

```
app/src/main/java/com/ridesmart/
├── MainActivity.kt                  # Permission setup hub & main UI
├── data/
│   ├── ProfileRepository.kt        # Rider profile persistence (DataStore)
│   └── RideHistoryRepository.kt    # Ride history storage
├── engine/
│   ├── ProfitCalculator.kt         # Core profit calculation engine
│   ├── RideSessionCache.kt         # Session-based offer caching
│   └── RideResult.kt               # Combined result DTO
├── model/
│   ├── ParsedRide.kt               # Extracted ride data
│   ├── ProfitResult.kt             # Calculation output
│   ├── RiderProfile.kt             # Driver cost settings
│   ├── PlatformConfig.kt           # Platform definitions & commissions
│   ├── Signal.kt                   # GREEN / YELLOW / RED enum
│   ├── VehicleType.kt              # Vehicle types & fuel types
│   └── ScreenState.kt              # Screen detection states
├── overlay/
│   ├── OverlayManager.kt           # Full floating verdict overlay
│   └── HudOverlayManager.kt        # Compact HUD overlay
├── parser/
│   ├── ParserFactory.kt            # Routes to platform-specific parsers
│   ├── RapidoParser.kt             # Rapido-specific extraction
│   ├── NammaYatriParser.kt         # Namma Yatri extraction
│   └── RideDataParser.kt           # Generic regex-based parser
├── receiver/
│   └── BootReceiver.kt             # Auto-start on device boot
├── service/
│   ├── RideSmartService.kt         # Core accessibility service
│   ├── UberOcrEngine.kt            # ML Kit OCR for Uber
│   └── SpatialReconstructor.kt     # Spatial layout reconstruction
└── ui/
    ├── LockScreenWakeActivity.kt   # Lock screen wake handler
    └── screens/                    # Jetpack Compose UI screens
```

---

## 🛠️ Tech Stack

| Category | Technology |
|----------|-----------|
| **Language** | Kotlin |
| **UI Framework** | Jetpack Compose with Material 3 |
| **Min SDK** | API 26 (Android 8.0) |
| **Target SDK** | API 36 |
| **Data Storage** | DataStore Preferences |
| **Async** | Kotlin Coroutines |
| **OCR** | Google ML Kit Text Recognition |
| **Analytics** | Firebase Crashlytics |
| **Build System** | Gradle with Kotlin DSL |
| **Testing** | JUnit 4, AndroidX Test, Espresso, Compose Testing |

---

## 📦 Prerequisites

- **Android Studio** Ladybug or newer
- **JDK 11** or higher
- **Android SDK** with API 36 installed
- An Android device or emulator running **Android 8.0 (API 26)** or higher

---

## 🚀 Getting Started

### 1. Clone the Repository

```bash
git clone https://github.com/aishwaryahanda436/ridesmart.git
cd ridesmart
```

### 2. Build the Project

```bash
./gradlew assembleDebug
```

### 3. Run Tests

```bash
./gradlew testDebugUnitTest
```

### 4. Install on Device

```bash
./gradlew installDebug
```

### 5. Setup on Device

1. **Open RideSmart** — the app launches into a Permission Setup Hub
2. **Grant Accessibility Service** permission for RideSmart
3. **Grant Overlay (Draw Over Other Apps)** permission
4. **Grant Notification Access** (optional, for background offers)
5. **Disable Battery Optimization** for RideSmart
6. **Configure your vehicle profile** — set fuel type, mileage, fuel price, and cost parameters
7. **Open your ride-hailing app** — RideSmart will automatically analyze incoming ride offers

---

## 🔐 Permissions

| Permission | Purpose |
|-----------|---------|
| `BIND_ACCESSIBILITY_SERVICE` | Monitor ride-hailing app screens to extract offer data |
| `SYSTEM_ALERT_WINDOW` | Display floating overlay verdict cards over other apps |
| `FOREGROUND_SERVICE` | Keep the monitoring service running in the background |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Android 14+ foreground service compliance |
| `POST_NOTIFICATIONS` | Show persistent service notification |
| `WAKE_LOCK` | Keep CPU active during screen-off to process offers |
| `RECEIVE_BOOT_COMPLETED` | Auto-start monitoring after device reboot |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Prevent the system from killing the service |
| `INTERNET` / `ACCESS_NETWORK_STATE` | Firebase Crashlytics and ML Kit OCR |

---

## ⚙️ Configuration

After installing, configure your profile via the setup screen:

| Setting | Default | Description |
|---------|---------|-------------|
| Mileage | 45 km/L | Your vehicle's fuel efficiency |
| Fuel Price | ₹102/L | Current petrol price |
| CNG Price | ₹85/kg | Current CNG price (if applicable) |
| Maintenance Cost | ₹0.80/km | Per-km maintenance cost |
| Depreciation | ₹0.30/km | Per-km vehicle depreciation |
| Min Net Profit | ₹30 | Minimum acceptable net profit per ride |
| Min ₹/km | ₹3.50 | Minimum acceptable earning per km |
| Target ₹/hr | ₹200 | Target hourly earning rate |
| Platform Commission | 0% | Override platform commission if needed |

---

## 🏗️ Architecture

The app follows a pipeline architecture:

1. **Detection Layer** — `RideSmartService` (AccessibilityService) monitors ride apps and captures screen content via the accessibility tree
2. **Parsing Layer** — `ParserFactory` routes data to platform-specific parsers (`RapidoParser`, `NammaYatriParser`, `UberOcrEngine`, `RideDataParser`) that extract structured `ParsedRide` data
3. **Calculation Layer** — `ProfitCalculator` computes net profit, earning metrics, and signal decisions using the driver's `RiderProfile`
4. **Presentation Layer** — `OverlayManager` and `HudOverlayManager` render floating verdict cards with color-coded signals
5. **Persistence Layer** — `ProfileRepository` and `RideHistoryRepository` store driver settings and ride history using DataStore

### Key Design Decisions
- **Accessibility Service over Screen Capture**: Uses the accessibility tree for data extraction, which is more reliable and battery-efficient than screenshot-based approaches
- **OCR Fallback for Uber**: Uber marks some views as `importantForAccessibility=no`, so ML Kit OCR is used as a fallback
- **Session Caching**: Prevents re-evaluation of the same ride and enables cross-offer comparison within a 90-second window
- **Adaptive Polling**: Uber polling uses exponential backoff (2.5s → 10s) to balance responsiveness with battery usage

---

## 🧪 Testing

Run the unit test suite:

```bash
./gradlew testDebugUnitTest
```

Run instrumented tests on a connected device:

```bash
./gradlew connectedDebugAndroidTest
```

---

## 🤝 Contributing

Contributions are welcome! To contribute:

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/my-feature`)
3. Commit your changes (`git commit -m 'Add my feature'`)
4. Push to the branch (`git push origin feature/my-feature`)
5. Open a Pull Request

Please ensure your changes:
- Follow the existing code style and conventions
- Include appropriate tests
- Keep the supported platform lists in sync across `RideSmartService`, `ParserFactory`, and `PlatformConfig`
