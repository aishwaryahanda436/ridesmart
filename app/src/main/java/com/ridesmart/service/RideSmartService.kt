package com.ridesmart.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import com.ridesmart.MainActivity
import com.ridesmart.R
import com.ridesmart.data.ProfileRepository
import com.ridesmart.data.RideHistoryRepository
import com.ridesmart.data.RideEntry
import com.ridesmart.engine.ProfitCalculator
import com.ridesmart.engine.RideSessionCache
import com.ridesmart.model.ParsedRide
import com.ridesmart.model.PlatformConfig
import com.ridesmart.model.ProfitResult
import com.ridesmart.model.RideResult
import com.ridesmart.overlay.OverlayManager
import com.ridesmart.parser.ParserFactory
import com.ridesmart.parser.UberNotificationParser
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import java.util.concurrent.Executors

class RideSmartService : AccessibilityService() {

    companion object {
        const val TAG = "RideSmart"
        const val NOTIF_CHANNEL_ID = "ridesmart_service"
        const val NOTIF_ID = 1

        val SUPPORTED_PACKAGES = setOf(
            "com.rapido.rider",
            "com.ubercab.driver",
            "com.ubercab",
            "com.olacabs.oladriver",
            "in.juspay.nammayatri",
            "net.openkochi.yatri",
            "in.juspay.nammayatripartner",
            "in.shadowfax.gandalf"
        )

        private const val PICKUP_PENALTY_PER_KM = 1.5
        private const val MIN_SCORE_IMPROVEMENT  = 5.0
        private const val SAME_FARE_COOLDOWN_MS  = 15_000L
        private const val MAX_PLATFORM_STATES    = 8
        private const val UBER_POLL_BASE_MS      = 1000L
        private const val UBER_POLL_MAX_MS       = 10_000L
        private const val MAX_TREE_DEPTH         = 20
        // 20 levels covers all observed real-world ride offer UIs (typically 5–10 levels deep)
        // while preventing O(n²) worst-case performance on pathologically deep trees.

        // Pre-compiled for the window scoring path; called on every accessibility event.
        private val FARE_SIGNAL_REGEX = Regex("""₹\d+""")
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var calculator: ProfitCalculator
    private lateinit var repository: ProfileRepository
    private lateinit var overlayManager: OverlayManager
    private lateinit var historyRepository: RideHistoryRepository
    private val uberOcrEngine = UberOcrEngine()

    // Debounced flow for Uber: batches rapid accessibility events before triggering OCR.
    // Capacity of 30 prevents back-pressure while staying well within memory budget.
    private val eventFlow = MutableSharedFlow<Pair<String, List<String>>>(
        extraBufferCapacity = 30,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // Fast path: non-Uber events are dispatched immediately (no debounce needed because
    // the fingerprint dedup in onAccessibilityEvent already prevents redundant processing).
    // Same capacity as eventFlow — at most one platform is active at a time in practice.
    private val fastEventFlow = MutableSharedFlow<Pair<String, List<String>>>(
        extraBufferCapacity = 30,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // ── PER-PLATFORM STATE ────────────────────────────────────────────────────
    private data class PlatformState(
        var lastFare: Float = 0f,
        var lastRideTime: Long = 0L,
        var lastProcessedText: String = "",
        var lastShownSmartScore: Double = Double.MIN_VALUE,
        val sessionCache: RideSessionCache = RideSessionCache()
    )
    private val platformStates = mutableMapOf<String, PlatformState>()
    private fun stateFor(pkg: String): PlatformState {
        val key = normalizePlatform(pkg)
        return platformStates.getOrPut(key) {
            // Evict oldest entry if map grows beyond supported platform count
            if (platformStates.size >= MAX_PLATFORM_STATES) {
                val oldest = platformStates.entries.minByOrNull { it.value.lastRideTime }
                oldest?.let { platformStates.remove(it.key) }
            }
            Log.d(TAG, "🗂 Created PlatformState for: $key")
            PlatformState()
        }
    }
    private fun normalizePlatform(pkg: String): String = when {
        pkg.contains("ubercab", ignoreCase = true) ||
        pkg.contains("uber", ignoreCase = true)       -> "uber"
        pkg.contains("rapido", ignoreCase = true)     -> "rapido"
        pkg.contains("olacabs", ignoreCase = true)      -> "ola"
        pkg.contains("nammayatri", ignoreCase = true) ||
        pkg.contains("juspay", ignoreCase = true) ||
        pkg.contains("yatri", ignoreCase = true)      -> "nammayatri"
        pkg.contains("shadowfax", ignoreCase = true)  -> "shadowfax"
        // Launcher / System Home Screen is neutral — should not block Uber
        pkg.contains("launcher", ignoreCase = true) ||
        pkg.contains("systemui", ignoreCase = true)   -> ""
        else -> pkg
    }
    private var activeForegroundPlatform: String = ""

    private var lastUberNotifHash = ""

    private val screenshotCooldownMs = 800L

    private val screenshotExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var isScreenshotProcessing = false
    private var uberPollingJob: Job? = null
    private var uberAppInForeground = false
    private var uberPollingIntervalMs = UBER_POLL_BASE_MS
    private var consecutiveEmptyPolls = 0

    @OptIn(FlowPreview::class)
    override fun onServiceConnected() {
        super.onServiceConnected()

        // Force-enable flags at runtime in addition to XML config.
        // This is required because Uber marks offer card views as
        // importantForAccessibility="no" — without FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        // those nodes are completely invisible and we get 0 results.
        serviceInfo = serviceInfo?.also { info ->
            info.flags = info.flags or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }

        calculator = ProfitCalculator()
        repository = ProfileRepository(this)
        overlayManager = OverlayManager(this).apply {
            onAutoDismiss = { platform ->
                stateFor(platform).lastShownSmartScore = Double.MIN_VALUE
            }
        }
        historyRepository = RideHistoryRepository(this)

        serviceScope.launch {
            // Fast path: non-Uber platforms processed immediately (no debounce).
            fastEventFlow.collect { (pkg, nodes) ->
                processScreen(nodes, pkg)
            }
        }

        serviceScope.launch {
            eventFlow
                .debounce(100L)
                .collect { (pkg, nodes) ->
                    processScreen(nodes, pkg)
                }
        }

        startUberPolling()
        startForegroundService()
        Log.d(TAG, "✅ RideSmartService connected — pipeline ready")
    }

    private fun startUberPolling() {
        uberPollingJob?.cancel()
        uberPollingJob = serviceScope.launch {
            while (isActive) {
                delay(uberPollingIntervalMs)
                val anotherActive = activeForegroundPlatform.isNotBlank() &&
                                    activeForegroundPlatform != "uber"
                if (anotherActive) {
                    Log.d(TAG, "📸 Uber OCR timer: skipped — $activeForegroundPlatform in foreground")
                    continue
                }
                if (uberAppInForeground && !isScreenshotProcessing) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        triggerUberScreenshot()
                    }
                }
            }
        }
    }

    /** Reset polling to fast interval when a window change hints at a new offer. */
    private fun resetUberPollingBackoff() {
        consecutiveEmptyPolls = 0
        uberPollingIntervalMs = UBER_POLL_BASE_MS
    }

    /** Increase polling interval after empty OCR results to save battery. */
    private fun increaseUberPollingBackoff() {
        consecutiveEmptyPolls++
        // Exponential backoff: base × 2^n, capped at max (2.5s → 5s → 10s → 10s)
        uberPollingIntervalMs = (UBER_POLL_BASE_MS * (1L shl consecutiveEmptyPolls.coerceAtMost(3)))
            .coerceAtMost(UBER_POLL_MAX_MS)
    }

    private fun stopUberPolling() {
        uberPollingJob?.cancel()
        uberPollingJob = null
    }

    private fun startForegroundService() {
        val channel = NotificationChannel(NOTIF_CHANNEL_ID, "RideSmart Service", NotificationManager.IMPORTANCE_LOW)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)

        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("RideSmart Active")
            .setContentText("Monitoring ride offers")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val evtPkg = event.packageName?.toString() ?: ""
        if (evtPkg.contains("uber", ignoreCase = true)) {
            Log.d(TAG, "📥 UBER EVENT type=${event.eventType} pkg=$evtPkg")
            uberAppInForeground = true
            activeForegroundPlatform = "uber"
            
            // ── STAGE 1: EARLY DETECTION via window/content change ──────────
            // TYPE_WINDOW_STATE_CHANGED (32): a new screen/dialog appeared —
            // highest-priority signal; trigger OCR immediately without waiting
            // for the debounced accessibility-tree scan.
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                resetUberPollingBackoff()
                Log.d(TAG, "📥 UBER WINDOW CHANGED — triggering screenshot immediately")
                if (!isScreenshotProcessing) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        triggerUberScreenshot()
                    }
                }
                return
            }

            // TYPE_WINDOW_CONTENT_CHANGED (2048): offer UI updated.
            // TYPE_VIEW_TEXT_CHANGED (16): a text field on the offer card changed.
            // Both reset backoff so the polling loop stays fast during an offer.
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
                resetUberPollingBackoff()
            }
        } else if (evtPkg.isNotBlank() && evtPkg != "android" && !evtPkg.contains("systemui")) {
            uberAppInForeground = false
            activeForegroundPlatform = normalizePlatform(evtPkg)
        }

        // ── UBER NOTIFICATION INTERCEPTION ──────────────────────────────
        // Dedup by content hash (not key) so notification UPDATES fire through.
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: ""

            if (pkg.contains("ubercab") || pkg.contains("uber")) {
                val notification = event.parcelableData as? Notification ?: return

                // Try UberNotificationParser first — it extracts richer data from
                // RemoteViews when standard extras are sparse or obfuscated.
                val lines = UberNotificationParser.extractFromNotification(notification)

                val extras = notification.extras
                fun getExtra(key: String) = extras?.getCharSequence(key)?.toString()?.trim() ?: ""
                val title = getExtra(Notification.EXTRA_TITLE)
                val text  = getExtra(Notification.EXTRA_TEXT)
                val combined = "$title $text"

                if (combined.isNotBlank() && combined != lastUberNotifHash) {
                    lastUberNotifHash = combined
                    val isOffer = combined.contains("trip", ignoreCase = true) ||
                                  combined.contains("request", ignoreCase = true) ||
                                  combined.contains("₹")
                    if (isOffer) {
                        // Prefer richer lines from UberNotificationParser; fall back to title+text.
                        val nodes = if (!lines.isNullOrEmpty()) lines else listOf(title, text)
                        serviceScope.launch { processScreen(nodes, "com.ubercab.driver") }
                    }
                }
                return
            }
        }
        // ── END UBER NOTIFICATION INTERCEPTION ─────────────────────────

        val pkg = event.packageName?.toString() ?: "null"

        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            val notification = event.parcelableData as? Notification
            val extras = notification?.extras
            val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

            val isRideApp = pkg in SUPPORTED_PACKAGES
            val isRideNotification = (title.contains("Ride", ignoreCase = true) || text.contains("₹")) &&
                                     (pkg.contains("rapido", ignoreCase = true) || pkg.contains("uber", ignoreCase = true))
            
            if (isRideApp || isRideNotification) {
                Log.d(TAG, "🔔 NOTIFICATION: pkg=$pkg title=\"$title\" text=\"$text\"")
                wakeScreen()
                // Notification data is fully available immediately — no delay needed.
                serviceScope.launch { processNotificationData(pkg, title, text) }
            }
            return
        }

        serviceScope.launch(Dispatchers.IO) {
            val allNodes = mutableListOf<String>()
            var detectedPackage = pkg

            data class WindowCandidate(
                val pkg: String,
                val nodes: List<String>,
                val score: Int
            )
            val candidates = mutableListOf<WindowCandidate>()

            try {
                for (window in windows) {
                    val root = window.root ?: continue
                    try {
                        val windowPkg = root.packageName?.toString() ?: ""
                        val nodes = collectAllText(root)

                        if (nodes.isEmpty()) continue

                        val combined = nodes.joinToString("|")

                        // Only care about known ride apps
                        val isRideApp = windowPkg in SUPPORTED_PACKAGES
                        val hasUberSignal = combined.contains("Uber Driver", ignoreCase = true) ||
                                            combined.contains("Uber Request", ignoreCase = true) ||
                                            combined.contains("See all requests", ignoreCase = true)
                        val isUberPkg = windowPkg.contains("ubercab", ignoreCase = true) ||
                                        windowPkg.contains("uber", ignoreCase = true)
                        if (!isRideApp && !hasUberSignal && !isUberPkg) continue

                        // Score this window: higher score = more likely to be a ride popup
                        var score = 0
                        if (FARE_SIGNAL_REGEX.containsMatchIn(combined)) score += 10
                        if (combined.contains("km", ignoreCase = true)) score += 5
                        if (combined.contains("min", ignoreCase = true)) score += 3
                        if (combined.contains("accept", ignoreCase = true) ||
                            combined.contains("match", ignoreCase = true) ||
                            combined.contains("confirm", ignoreCase = true)) score += 8

                        // For Uber packages, boost score even with minimal data
                        // since nodes may be partially obfuscated
                        if (isUberPkg && nodes.isNotEmpty()) score += 2

                        if (score > 0) {
                            candidates.add(WindowCandidate(windowPkg, nodes, score))
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing window: ${e.message}")
                    } finally {
                        @Suppress("DEPRECATION")
                        root.recycle()
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Window scan error: ${e.message}")
            }

            // Pick best scoring window
            val best = candidates.maxByOrNull { it.score }
            if (best != null) {
                allNodes.addAll(best.nodes)
                detectedPackage = if (best.pkg.contains("ubercab", ignoreCase = true))
                                    "com.ubercab.driver" else best.pkg
            }

            // Fallback: Check active window if primary check found nothing
            if (allNodes.isEmpty()) {
                val fallbackRoot = rootInActiveWindow
                if (fallbackRoot != null) {
                    try {
                        val fallbackNodes = collectAllText(fallbackRoot)
                        if (fallbackNodes.isNotEmpty()) {
                            allNodes.addAll(fallbackNodes)
                            detectedPackage = fallbackRoot.packageName?.toString() ?: pkg
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing fallback root: ${e.message}")
                    } finally {
                        @Suppress("DEPRECATION")
                        fallbackRoot.recycle()
                    }
                }
            }

            if (allNodes.isEmpty()) {
                // For Uber: even with 0 text nodes, a window change likely
                // means an offer appeared with fully obfuscated content.
                // Trigger OCR screenshot as immediate fallback.
                if (uberAppInForeground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val now = System.currentTimeMillis()
                    if (now - lastScreenshotMs > screenshotCooldownMs) {
                        Log.d(TAG, "🔍 UBER: empty accessibility tree — triggering OCR fallback")
                        withContext(Dispatchers.Main) {
                            triggerUberScreenshot()
                        }
                    }
                }
                return@launch
            }
            
            val combined = allNodes.joinToString("|")
            if (combined.contains("See all requests", ignoreCase = true)) {
                val now = System.currentTimeMillis()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    now - lastScreenshotMs > screenshotCooldownMs) {
                    withContext(Dispatchers.Main) {
                        triggerUberScreenshot()
                    }
                }
                return@launch
            }

            activeForegroundPlatform = if (detectedPackage.contains("uber", ignoreCase = true)) {
                "uber"
            } else {
                normalizePlatform(detectedPackage)
            }

            val fingerprint = allNodes.joinToString("|")
            val state = stateFor(detectedPackage)
            if (fingerprint == state.lastProcessedText) return@launch
            state.lastProcessedText = fingerprint

            // Route to fast path for non-Uber platforms, debounced path for Uber.
            // Non-Uber platforms (Rapido, Ola, NammaYatri, Shadowfax) use accessibility
            // tree text directly — no OCR involved, so no debounce is needed. Fingerprint
            // dedup above already prevents redundant processing.
            if (ParserFactory.isUber(detectedPackage)) {
                eventFlow.emit(Pair(detectedPackage, allNodes))
            } else {
                fastEventFlow.emit(Pair(detectedPackage, allNodes))
            }
        }
    }

    private fun wakeScreen() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) {
            @Suppress("DEPRECATION")
            val screenWake = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "RideSmart::ScreenWake"
            )
            screenWake.acquire(10_000L)
            Log.d(TAG, "🔆 Screen woken")
        }
    }

    private suspend fun processNotificationData(pkg: String, title: String, text: String) {
        val parser = ParserFactory.getParser(pkg)
        val allRides = parser.parseAll(listOf(title, text), pkg)
        if (allRides.isEmpty()) return
        val parsedRide = allRides.first()

        val state = stateFor(pkg)
        val now = System.currentTimeMillis()
        if (parsedRide.baseFare.toFloat() == state.lastFare && now - state.lastRideTime < 3000L) return

        state.lastFare = parsedRide.baseFare.toFloat()
        state.lastRideTime = now

        val profile = repository.profileFlow.first()
        val result = calculator.calculate(parsedRide, profile)
        
        if (Settings.canDrawOverlays(this)) {
            overlayManager.showResult(RideResult(parsedRide, result.totalFare, result.actualPayout, result.fuelCost, result.wearCost, result.netProfit, result.earningPerKm, result.earningPerHour, result.pickupRatio, result.signal, result.failedChecks))
            
            // Update suppression state
            val currentScore = result.netProfit - (parsedRide.pickupDistanceKm * PICKUP_PENALTY_PER_KM * cancelRiskMultiplier(parsedRide.pickupDistanceKm))
            state.lastShownSmartScore = currentScore
            state.lastFare = parsedRide.baseFare.toFloat()
            state.lastRideTime = System.currentTimeMillis()
        }
        saveRideToHistory(parsedRide, result, pkg)
    }

    private fun cancelRiskMultiplier(pickupKm: Double): Double = when {
        pickupKm <= 2.0 -> 1.0
        pickupKm <= 4.0 -> 1.3
        pickupKm <= 6.0 -> 1.7
        else            -> 2.2
    }

    private suspend fun processScreen(textNodes: List<String>, packageName: String) {
        val state = stateFor(packageName)
        val isUber = packageName.contains("uber", ignoreCase = true)
        val parser = ParserFactory.getParser(packageName)

        val screenState = parser.detectScreenState(textNodes)
        if (screenState == com.ridesmart.model.ScreenState.IDLE) {
            if (!state.sessionCache.isEmpty()) state.sessionCache.reset()
            state.lastShownSmartScore = Double.MIN_VALUE
            return
        }
        if (screenState == com.ridesmart.model.ScreenState.ACTIVE_RIDE) return

        var allRides = parser.parseAll(textNodes, packageName)

        // ── HYBRID FALLBACK FOR UBER ────────────────────────────────────
        // When accessibility parsing returns no rides but we detect offer
        // signals in the (possibly obfuscated) nodes, trigger an immediate
        // OCR screenshot. Also try the generic RideDataParser as a secondary
        // fallback since it may extract data the UberOcrEngine missed.
        if (allRides.isEmpty() && isUber) {
            // Secondary parser fallback: try the generic parser on same nodes
            val fallbackRides = ParserFactory.getFallbackParser().parseAll(textNodes, packageName)
            if (fallbackRides.isNotEmpty()) {
                allRides = fallbackRides
            } else if (uberOcrEngine.hasOfferSignals(textNodes)) {
                // Nodes suggest an offer exists but data is obfuscated → OCR
                Log.d(TAG, "🔍 UBER HYBRID: offer signals detected but parsing failed — triggering OCR")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val now = System.currentTimeMillis()
                    if (now - lastScreenshotMs > screenshotCooldownMs) {
                        lastScreenshotMs = now
                        withContext(Dispatchers.Main) {
                            triggerUberScreenshot()
                        }
                    }
                }
                return
            }
        }

        if (allRides.isEmpty()) {
            if (isUber) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val now = System.currentTimeMillis()
                    if (now - lastScreenshotMs > screenshotCooldownMs) {
                        lastScreenshotMs = now
                        withContext(Dispatchers.Main) {
                            triggerUberScreenshot()
                        }
                    }
                }
            }
            return
        }

        val profile = repository.profileFlow.first()
        
        val scoredRides = allRides.map { ride ->
            val result = calculator.calculate(ride, profile)
            val score = result.netProfit - (ride.pickupDistanceKm * PICKUP_PENALTY_PER_KM * cancelRiskMultiplier(ride.pickupDistanceKm))
            Triple(ride, result, score)
        }

        val best = scoredRides.maxByOrNull { it.third } ?: return
        val (bestRide, bestResult, currentScore) = best

        if (bestRide.rideDistanceKm > 0.0) {
            if (state.sessionCache.isExpired()) state.sessionCache.reset()
            state.sessionCache.addResult(bestRide, bestResult.netProfit, currentScore)
        }

        val bestSeen = state.sessionCache.getBestSeen()
        val totalCardsSeen = state.sessionCache.getTotalCardsSeen()
        val isBestSoFar = bestSeen == null || currentScore >= (bestSeen.smartScore - 0.01)

        val now = System.currentTimeMillis()

        // Suppress same fare re-firing
        val sameFareTooSoon = bestRide.baseFare.toFloat() == state.lastFare &&
                              now - state.lastRideTime < SAME_FARE_COOLDOWN_MS
        if (sameFareTooSoon) return

        val hasShownThisSession = state.lastShownSmartScore > Double.MIN_VALUE
        if (hasShownThisSession) {
            if (currentScore < state.lastShownSmartScore + MIN_SCORE_IMPROVEMENT) return
        }

        // All guards passed — show this card
        state.lastFare = bestRide.baseFare.toFloat()
        state.lastRideTime = now
        state.lastShownSmartScore = currentScore

        if (Settings.canDrawOverlays(this)) {
            overlayManager.showResult(
                RideResult(bestRide, bestResult.totalFare, bestResult.actualPayout, bestResult.fuelCost, bestResult.wearCost, bestResult.netProfit, bestResult.earningPerKm, bestResult.earningPerHour, bestResult.pickupRatio, bestResult.signal, bestResult.failedChecks),
                totalRidesConsidered = totalCardsSeen,
                isBestSoFar          = isBestSoFar,
                bestSeenFare         = bestSeen?.ride?.baseFare ?: bestRide.baseFare,
                bestSeenNetProfit    = bestSeen?.netProfit ?: bestResult.netProfit
            )
        }
        saveRideToHistory(bestRide, bestResult, packageName)
    }

    private fun saveRideToHistory(ride: ParsedRide, result: ProfitResult, pkg: String) {
        serviceScope.launch {
            historyRepository.saveRide(
                RideEntry(
                    timestampMs          = System.currentTimeMillis(),
                    hourOfDay            = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY),
                    dayOfWeek            = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK),
                    platform             = PlatformConfig.get(pkg).displayName,
                    packageName          = pkg,
                    baseFare             = ride.baseFare,
                    tipAmount            = ride.tipAmount,
                    premiumAmount        = ride.premiumAmount,
                    actualPayout         = result.actualPayout,
                    rideKm               = ride.rideDistanceKm,
                    pickupKm             = ride.pickupDistanceKm,
                    totalKm              = ride.rideDistanceKm + ride.pickupDistanceKm,
                    pickupRatioPct       = (result.pickupRatio * 100).toInt(),
                    estimatedDurationMin = ride.estimatedDurationMin,
                    fuelCost             = result.fuelCost,
                    wearCost             = result.wearCost,
                    totalCost            = result.fuelCost + result.wearCost,
                    netProfit            = result.netProfit,
                    earningPerKm         = result.earningPerKm,
                    earningPerHour       = result.earningPerHour,
                    signal               = result.signal,
                    failedChecks         = result.failedChecks.joinToString("|"),
                    smartScore           = result.netProfit - (ride.pickupDistanceKm * PICKUP_PENALTY_PER_KM * cancelRiskMultiplier(ride.pickupDistanceKm)),
                    pickupAddress        = ride.pickupAddress,
                    dropAddress          = ride.dropAddress,
                    riderRating          = ride.riderRating,
                    paymentType          = ride.paymentType
                )
            )
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun triggerUberScreenshot() {
        if (activeForegroundPlatform.isNotBlank() && activeForegroundPlatform != "uber") return
        if (isScreenshotProcessing) return
        val now = System.currentTimeMillis()
        if (now - lastScreenshotMs < screenshotCooldownMs) return
        isScreenshotProcessing = true
        lastScreenshotMs = now

        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                screenshotExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val bitmap = try {
                            Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                                .also { result.hardwareBuffer.close() }
                        } catch (e: Exception) {
                            result.hardwareBuffer.close()
                            null
                        }
                        if (bitmap == null) {
                            isScreenshotProcessing = false
                            increaseUberPollingBackoff()
                            return
                        }
                        serviceScope.launch {
                            try {
                                val parsed = uberOcrEngine.parse(bitmap)
                                if (parsed != null && parsed.baseFare > 0) {
                                    resetUberPollingBackoff()
                                    val fakeNodes = mutableListOf<String>().apply {
                                        add("₹${"%.2f".format(parsed.baseFare)}")

                                        if (parsed.estimatedDurationMin > 0 && parsed.rideDistanceKm > 0)
                                            add("${parsed.estimatedDurationMin} mins (${parsed.rideDistanceKm} km)")
                                        else if (parsed.rideDistanceKm > 0)
                                            add("${parsed.rideDistanceKm} km")
                                        if (parsed.pickupDistanceKm > 0) {
                                            if (parsed.pickupTimeMin > 0)
                                                add("${parsed.pickupTimeMin} min (${parsed.pickupDistanceKm} km)")
                                            else
                                                add("${parsed.pickupDistanceKm} km away")
                                        }
                                        add("Match")
                                        if (parsed.pickupAddress.isNotBlank()) add(parsed.pickupAddress)
                                        if (parsed.dropAddress.isNotBlank()) add(parsed.dropAddress)
                                    }
                                    processScreen(fakeNodes, "com.ubercab.driver")
                                } else {
                                    increaseUberPollingBackoff()
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error processing screenshot bitmap: ${e.message}")
                                increaseUberPollingBackoff()
                            } finally {
                                bitmap.recycle()
                                isScreenshotProcessing = false
                            }
                        }
                    }
                    override fun onFailure(errorCode: Int) {
                        isScreenshotProcessing = false
                        increaseUberPollingBackoff()
                    }
                }
            )
        } catch (e: Exception) {
            isScreenshotProcessing = false
            increaseUberPollingBackoff()
        }
    }

    @Volatile private var lastScreenshotMs = 0L

    /**
     * Recursively extracts all text from the accessibility node tree.
     *
     * Enhanced for Uber obfuscation: in addition to node.text and
     * contentDescription, also extracts tooltipText (API 28+) and
     * hintText. Uber marks many views as not-important-for-accessibility,
     * but the FLAG_INCLUDE_NOT_IMPORTANT_VIEWS flag (set in onServiceConnected)
     * forces their inclusion. This method then extracts whatever text
     * properties those nodes still expose.
     *
     * Also extracts meaningful viewIdResourceNames — Uber's view IDs
     * sometimes contain keywords like "fare", "distance", "pickup" that
     * help signal an offer is present even when text content is empty.
     */
    private fun collectAllText(node: AccessibilityNodeInfo?, depth: Int = 0): List<String> {
        if (node == null || depth > MAX_TREE_DEPTH) return emptyList()
        val texts = mutableListOf<String>()
        val nodeText = node.text?.toString()?.trim()
        if (!nodeText.isNullOrBlank()) texts.add(nodeText)
        val contentDesc = node.contentDescription?.toString()?.trim()
        if (!contentDesc.isNullOrBlank() && contentDesc != nodeText) texts.add(contentDesc)

        // API 28+: tooltipText can carry ride data on some Uber UI elements
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val tooltip = node.tooltipText?.toString()?.trim()
            if (!tooltip.isNullOrBlank() && tooltip != nodeText && tooltip != contentDesc) {
                texts.add(tooltip)
            }
        }
        // API 26+: hintText may carry placeholder values with ride info
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val hint = node.hintText?.toString()?.trim()
            if (!hint.isNullOrBlank() && hint !in texts) {
                texts.add(hint)
            }
        }

        // Extract viewIdResourceName — useful for Uber where text is blocked
        // but view IDs like "fare_text", "distance_value" hint at offer presence
        val viewId = node.viewIdResourceName?.toString()?.trim()
        if (!viewId.isNullOrBlank()) {
            val idPart = viewId.substringAfterLast("/").lowercase()
            val offerIdKeywords = listOf("fare", "price", "amount", "distance", "pickup",
                "drop", "trip", "ride", "accept", "match", "confirm", "offer", "request")
            if (offerIdKeywords.any { idPart.contains(it) }) {
                texts.add("[id:$idPart]")
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                try {
                    texts.addAll(collectAllText(child, depth + 1))
                } finally {
                    @Suppress("DEPRECATION")
                    child.recycle()
                }
            }
        }
        return texts
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        stopUberPolling()
        screenshotExecutor.shutdown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        overlayManager.dismiss()
        serviceScope.cancel()
        super.onDestroy()
    }
}
