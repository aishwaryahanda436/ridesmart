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
            "com.ubercab.driver",
            "com.olacabs.oladriver",
            "com.rapido.rider",
            "in.shadowfax.gandalf",
            "in.juspay.nammayatripartner",
            "net.openkochi.yatripartner",
            "sinet.startup.inDriver",
            "com.meru.merumobile"
        )

        private const val PICKUP_PENALTY_PER_KM = 1.5
        private const val MIN_SCORE_IMPROVEMENT  = 5.0
        private const val SAME_FARE_COOLDOWN_MS  = 15_000L
        private const val MAX_PLATFORM_STATES    = 8
        private const val UBER_POLL_BASE_MS      = 2500L
        private const val UBER_POLL_MAX_MS       = 10_000L

        data class NodeSnapshot(
            val text: String?,
            val contentDesc: String?,
            val viewId: String?,
            val className: String?,
            val boundsTop: Int,
            val boundsBottom: Int,
            val boundsLeft: Int,
            val boundsRight: Int
        ) {
            fun bestText(): String? = when {
                !text.isNullOrBlank() -> text
                !contentDesc.isNullOrBlank() -> contentDesc
                else -> null
            }
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var calculator: ProfitCalculator
    private lateinit var repository: ProfileRepository
    private lateinit var overlayManager: OverlayManager
    private lateinit var historyRepository: RideHistoryRepository
    private val uberOcrEngine = UberOcrEngine()

    private val eventFlow = MutableSharedFlow<Pair<String, List<String>>>(
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
        if (pkg.isBlank()) return PlatformState()
        val key = normalizePlatform(pkg)
        if (key.isBlank()) return PlatformState()
        return platformStates.getOrPut(key) {
            if (platformStates.size >= MAX_PLATFORM_STATES) {
                val oldest = platformStates.entries.minByOrNull { it.value.lastRideTime }
                oldest?.let { platformStates.remove(it.key) }
            }
            Log.d(TAG, "🗂 Created PlatformState for: $key")
            PlatformState()
        }
    }
    private fun normalizePlatform(pkg: String): String = when {
        pkg == "com.ubercab.driver" -> "uber"
        pkg == "com.olacabs.oladriver" -> "ola"
        pkg == "com.rapido.rider" -> "rapido"
        pkg == "in.shadowfax.gandalf" -> "shadowfax"
        pkg == "in.juspay.nammayatripartner" || pkg == "net.openkochi.yatripartner" -> "nammayatri"
        pkg == "sinet.startup.inDriver" -> "indrive"
        pkg == "com.meru.merumobile" -> "meru"
        pkg.contains("launcher", ignoreCase = true) || pkg.contains("systemui", ignoreCase = true) -> ""
        else -> pkg
    }
    private var activeForegroundPlatform: String = ""

    private var lastUberNotifHash = ""

    private val screenshotCooldownMs = 2000L

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
            eventFlow
                .debounce(250L)
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
            
            // On TYPE_WINDOW_STATE_CHANGED (type=32) specifically,
            // trigger an immediate screenshot for faster offer detection.
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
        } else if (evtPkg.isNotBlank() && evtPkg != "android" && !evtPkg.contains("systemui")) {
            uberAppInForeground = false
            val normalized = normalizePlatform(event.packageName?.toString() ?: "")
            if (normalized.isNotBlank()) {
                activeForegroundPlatform = normalized
            }
        }

        // ── UBER NOTIFICATION INTERCEPTION ──────────────────────────────
        // Dedup by content hash (not key) so notification UPDATES fire through.
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: ""

            if (pkg.contains("ubercab") || pkg.contains("uber")) {
                val notification = event.parcelableData as? Notification
                val extras = notification?.extras

                fun getExtra(key: String) = extras?.getCharSequence(key)?.toString()?.trim() ?: ""
                val title   = getExtra(Notification.EXTRA_TITLE)
                val text    = getExtra(Notification.EXTRA_TEXT)
                val combined = "$title $text"

                if (combined.isNotBlank() && combined != lastUberNotifHash) {
                    lastUberNotifHash = combined
                    val isOffer = combined.contains("trip", ignoreCase = true) ||
                                  combined.contains("request", ignoreCase = true) ||
                                  combined.contains("₹")
                    if (isOffer) {
                        serviceScope.launch { processScreen(listOf(title, text), "com.ubercab.driver") }
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
                serviceScope.launch {
                    delay(300)
                    processNotificationData(pkg, title, text)
                }
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
                        if (!isRideApp && !hasUberSignal) continue

                        // Score this window: higher score = more likely to be a ride popup
                        var score = 0
                        if (Regex("""₹\d+""").containsMatchIn(combined)) score += 10
                        if (combined.contains("km", ignoreCase = true)) score += 5

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
                        val packageName = fallbackRoot.packageName?.toString() ?: pkg
                        val fallbackNodes = if (packageName == "in.shadowfax.gandalf") {
                            findNodesForPackage(packageName)
                        } else {
                            collectAllText(fallbackRoot)
                        }
                        if (fallbackNodes.isNotEmpty()) {
                            allNodes.addAll(fallbackNodes)
                            detectedPackage = packageName
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing fallback root: ${e.message}")
                    } finally {
                        @Suppress("DEPRECATION")
                        fallbackRoot.recycle()
                    }
                }
            }

            if (allNodes.isEmpty()) return@launch
            
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

            eventFlow.emit(Pair(detectedPackage, allNodes))
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
        val parser = ParserFactory.getParser(packageName)

        val screenState = parser.detectScreenState(textNodes)
        if (screenState == com.ridesmart.model.ScreenState.IDLE) {
            if (!state.sessionCache.isEmpty()) state.sessionCache.reset()
            state.lastShownSmartScore = Double.MIN_VALUE
            return
        }
        if (screenState == com.ridesmart.model.ScreenState.ACTIVE_RIDE) return

        val allRides = parser.parseAll(textNodes, packageName)

        if (allRides.isEmpty()) {
            if (packageName.contains("uber", ignoreCase = true)) {
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
                    smartScore           = result.netProfit - (ride.pickupDistanceKm * PICKUP_PENALTY_PER_KM * cancelRiskMultiplier(pickupKm = ride.pickupDistanceKm)),
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

    private fun collectAllText(node: AccessibilityNodeInfo?): List<String> {
        if (node == null) return emptyList()
        val texts = mutableListOf<String>()
        val nodeText = node.text?.toString()?.trim()
        if (!nodeText.isNullOrBlank()) texts.add(nodeText)
        val contentDesc = node.contentDescription?.toString()?.trim()
        if (!contentDesc.isNullOrBlank() && contentDesc != nodeText) texts.add(contentDesc)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                try {
                    texts.addAll(collectAllText(child))
                } finally {
                    @Suppress("DEPRECATION")
                    child.recycle()
                }
            }
        }
        return texts
    }

    private fun snapshotTree(
        root: AccessibilityNodeInfo?,
        depth: Int = 0
    ): List<NodeSnapshot> {
        if (root == null || depth > 8) return emptyList()
        val result = mutableListOf<NodeSnapshot>()
        val bounds = android.graphics.Rect()
        root.getBoundsInScreen(bounds)
        val t = root.text?.toString()
        val cd = root.contentDescription?.toString()
        if (!t.isNullOrBlank() || !cd.isNullOrBlank()) {
            result += NodeSnapshot(
                text        = t,
                contentDesc = cd,
                viewId      = root.viewIdResourceName,
                className   = root.className?.toString(),
                boundsTop   = bounds.top,
                boundsBottom= bounds.bottom,
                boundsLeft  = bounds.left,
                boundsRight = bounds.right
            )
        }
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            try {
                result += snapshotTree(child, depth + 1)
            } finally {
                child.recycle()
            }
        }
        return result
    }

    private fun findNodesForPackage(packageName: String): List<String> {
        // Strategy 1: scan all windows for the matching package
        // This is required for apps that draw overlays over the launcher
        // (e.g. Shadowfax offer popup appears over home screen)
        try {
            val allWindows = windows
            for (window in allWindows) {
                val windowPkg = window.root?.packageName?.toString() ?: continue
                if (windowPkg == packageName) {
                    val nodes = collectAllText(window.root)
                    if (nodes.isNotEmpty()) {
                        Log.d(TAG, "🪟 findNodesForPackage: found $packageName " +
                            "in window ${window.id} nodes=${nodes.size}")
                        window.root?.recycle()
                        return nodes
                    }
                }
                window.root?.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "🪟 findNodesForPackage error: ${e.message}")
        }

        // Strategy 2: fall back to rootInActiveWindow
        return collectAllText(rootInActiveWindow)
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
