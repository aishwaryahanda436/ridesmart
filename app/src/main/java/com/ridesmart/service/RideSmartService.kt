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
import com.ridesmart.data.RemoteConfigRepository
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class RideSmartService : AccessibilityService() {

    companion object {
        const val TAG = "RideSmart"
        const val NOTIF_CHANNEL_ID = "ridesmart_service"
        const val NOTIF_ID = 1

        val SUPPORTED_PACKAGES = setOf(
            "com.rapido.rider",
            "in.rapido.captain",
            "com.rapido.captain",
            "com.ubercab.driver",
            "com.ubercab",
            "com.olacabs.oladriver",
            "com.olacabs.driver",
            "com.ola.driver",
            "in.juspay.nammayatri",
            "net.openkochi.yatri",
            "in.juspay.nammayatripartner",
            "in.shadowfax.gandalf",
            "com.shadowfax.driver",
            "com.shadowfax.zeus"
        )

        private const val PICKUP_PENALTY_PER_KM = 1.5
        private const val MIN_SCORE_IMPROVEMENT  = 5.0
        private const val SAME_FARE_COOLDOWN_MS  = 15_000L
        private const val MAX_PLATFORM_STATES    = 8
        private const val UBER_POLL_BASE_MS      = 1000L
        private const val UBER_POLL_MAX_MS       = 10_000L
        
        // Spec v2.0 Section 8.2: BFS depth cap 8
        private const val MAX_TREE_DEPTH         = 8

        private val FARE_SIGNAL_REGEX = Regex("""₹\d+""")
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var calculator: ProfitCalculator
    private lateinit var repository: ProfileRepository
    private lateinit var historyRepository: RideHistoryRepository
    private lateinit var remoteConfig: RemoteConfigRepository
    private lateinit var overlayManager: OverlayManager
    private val uberOcrEngine by lazy { UberOcrEngine() }

    // Stage 1 & 2 Isolation: Dedicated Pipeline Context (Spec v2.0 Section 8.3)
    private val pipelineContext = Dispatchers.Default

    private val eventFlow = MutableSharedFlow<Pair<String, List<String>>>(
        extraBufferCapacity = 30,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val fastEventFlow = MutableSharedFlow<Pair<String, List<String>>>(
        extraBufferCapacity = 30,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

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
        pkg.contains("olacabs", ignoreCase = true) ||
        pkg.contains("ola.driver", ignoreCase = true) -> "ola"
        pkg.contains("nammayatri", ignoreCase = true) ||
        pkg.contains("juspay", ignoreCase = true) ||
        pkg.contains("yatri", ignoreCase = true) ||
        pkg.contains("net.openkochi", ignoreCase = true) -> "nammayatri"
        pkg.contains("shadowfax", ignoreCase = true) ||
        pkg.contains("gandalf", ignoreCase = true)    -> "shadowfax"
        pkg.contains("launcher", ignoreCase = true) ||
        pkg.contains("systemui", ignoreCase = true)   -> ""
        else -> pkg
    }
    private var activeForegroundPlatform: String = ""

    private var lastUberNotifHash = ""

    private val screenshotCooldownMs = 800L

    private val screenshotExecutor = Executors.newSingleThreadExecutor()
    private val isScreenshotProcessing = AtomicBoolean(false)
    private var uberPollingJob: Job? = null
    private var uberAppInForeground = false
    private var uberPollingIntervalMs = UBER_POLL_BASE_MS
    private var consecutiveEmptyPolls = 0
    @Volatile
    private var uberOfferActiveMs = 0L
    private var recentForegroundPackage: String = ""
    private var uberOcrActive = false
    private var uberScreenshotJob: Job? = null

    private val screenWakeLock: PowerManager.WakeLock by lazy {
        (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
            PowerManager.ACQUIRE_CAUSES_WAKEUP or
            PowerManager.ON_AFTER_RELEASE,
            "RideSmart::ScreenWake"
        ).apply { setReferenceCounted(false) }
    }

    @OptIn(FlowPreview::class)
    override fun onServiceConnected() {
        super.onServiceConnected()

        // MUST be called first — Android 12+ requires startForeground() within 5 seconds
        startForegroundService()

        serviceInfo = serviceInfo?.also { info ->
            info.flags = info.flags or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }

        calculator = ProfitCalculator()
        repository = ProfileRepository(this)
        historyRepository = RideHistoryRepository(this)
        remoteConfig = RemoteConfigRepository(this)
        overlayManager = OverlayManager(this).apply {
            onDismiss = { platform ->
                stateFor(platform).lastShownSmartScore = Double.MIN_VALUE
                stateFor(platform).sessionCache.resetBest()
                if (platform.equals("Uber", ignoreCase = true)) {
                    uberOfferActiveMs = 0L
                    uberOcrActive = false
                    uberScreenshotJob?.cancel()
                    uberScreenshotJob = null
                }
            }
        }

        serviceScope.launch(pipelineContext) {
            fastEventFlow.collect { (pkg, nodes) -> processScreen(nodes, pkg) }
        }

        serviceScope.launch(pipelineContext) {
            eventFlow.debounce(100L).collect { (pkg, nodes) -> processScreen(nodes, pkg) }
        }

        serviceScope.launch { remoteConfig.fetchConfig() }

        startUberPolling()
        prewarmOcr()
        Log.d(TAG, "✅ RideSmartService connected — pipeline ready")
    }

    private fun prewarmOcr() {
        serviceScope.launch(Dispatchers.Default) {
            val dummy = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            uberOcrEngine.parse(dummy)
            dummy.recycle()
            Log.d(TAG, "🧠 ML Kit OCR pre-warmed")
        }
    }

    private fun startUberPolling() {
        uberPollingJob?.cancel()
        uberPollingJob = serviceScope.launch {
            while (isActive) {
                delay(uberPollingIntervalMs)
                val anotherActive = activeForegroundPlatform.isNotBlank() &&
                                    activeForegroundPlatform != "uber"
                if (anotherActive) continue
                if (uberAppInForeground && !isScreenshotProcessing.get()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        withContext(Dispatchers.Main) {
                            triggerUberScreenshot()
                        }
                    }
                }
            }
        }
    }

    private fun resetUberPollingBackoff() {
        consecutiveEmptyPolls = 0
        uberPollingIntervalMs = UBER_POLL_BASE_MS
    }

    private fun increaseUberPollingBackoff() {
        consecutiveEmptyPolls++
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
        if (evtPkg.isNotEmpty()) recentForegroundPackage = evtPkg
        
        // Skip our own package — we don't need to analyse ourselves
        if (evtPkg == packageName) return

        // ── Spec v2.0 Section 3.3: Rapido Animation Debounce ──
        if (evtPkg == "com.rapido.rider") {
            // Note: Debounce still needed, but extraction must be on main thread.
            // We can delay and then capture on main thread.
            serviceScope.launch(Dispatchers.Main) {
                delay(180L)
                triggerNodeCollection(evtPkg)
            }
            return
        }

        if (evtPkg.contains("uber", ignoreCase = true)) {
            Log.d(TAG, "📥 UBER EVENT type=${event.eventType} pkg=$evtPkg")
            uberAppInForeground = true
            activeForegroundPlatform = "uber"
            
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                resetUberPollingBackoff()
                uberOfferActiveMs = 0L
                uberOcrActive = false
                uberScreenshotJob?.cancel()
                uberScreenshotJob = null
                Log.d(TAG, "📥 UBER WINDOW CHANGED — triggering screenshot immediately")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    triggerUberScreenshot()
                }
                return
            }

            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
                resetUberPollingBackoff()
            }
        } else if (evtPkg.isNotBlank() && evtPkg != "android" && !evtPkg.contains("systemui")) {
            val platform = normalizePlatform(evtPkg)
            if (ParserFactory.isRideApp(evtPkg)) {
                Log.d(TAG, "📥 $platform EVENT type=${event.eventType} pkg=$evtPkg")
            }
            uberAppInForeground = false
            activeForegroundPlatform = platform
        }

        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: ""

            if (pkg.contains("ubercab") || pkg.contains("uber")) {
                val notification = event.parcelableData as? Notification ?: return
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
                        Log.d(TAG, "🔔 Uber Notification Offer detected")
                        val nodes = if (!lines.isNullOrEmpty()) lines else listOf(title, text)
                        serviceScope.launch(pipelineContext) { processScreen(nodes, "com.ubercab.driver") }
                    }
                }
                return
            }
        }

        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: ""
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
                serviceScope.launch(pipelineContext) { processNotificationData(pkg, title, text) }
            }
            return
        }

        triggerNodeCollection(evtPkg)
    }

    private fun triggerNodeCollection(pkg: String) {
        // Extraction happens on Main thread (this function is called from Main thread)
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
                    
                    val rawNodes = mutableListOf<AccessibilityNodeInfo>()
                    collectRawNodes(root, rawNodes)
                    val reconstructedText = SpatialReconstructor.reconstruct(rawNodes)
                    rawNodes.forEach { it.safeRecycle() }

                    if (reconstructedText.isEmpty()) continue

                    val combined = reconstructedText.joinToString("|")

                    val isRideApp = ParserFactory.isRideApp(windowPkg)
                    val hasUberSignal = combined.contains("Uber Driver", ignoreCase = true) ||
                                        combined.contains("Uber Request", ignoreCase = true) ||
                                        combined.contains("See all requests", ignoreCase = true)
                    val isUberPkg = windowPkg.contains("ubercab", ignoreCase = true) ||
                                    windowPkg.contains("uber", ignoreCase = true)
                    if (!isRideApp && !hasUberSignal && !isUberPkg) continue

                    var score = 0
                    if (FARE_SIGNAL_REGEX.containsMatchIn(combined)) score += 10
                    if (combined.contains("km", ignoreCase = true)) score += 5
                    if (combined.contains("min", ignoreCase = true)) score += 3
                    if (combined.contains("accept", ignoreCase = true) ||
                        combined.contains("match", ignoreCase = true) ||
                        combined.contains("confirm", ignoreCase = true)) score += 8

                    if (isUberPkg && reconstructedText.isNotEmpty()) score += 2

                    if (score > 0) {
                        candidates.add(WindowCandidate(windowPkg, reconstructedText, score))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing window: ${e.message}")
                } finally {
                    root.safeRecycle()
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Window scan error: ${e.message}")
        }

        val best = candidates.maxByOrNull { it.score }
        if (best != null) {
            allNodes.addAll(best.nodes)
            detectedPackage = if (best.pkg.contains("ubercab", ignoreCase = true))
                                "com.ubercab.driver" else best.pkg
        }

        if (allNodes.isEmpty()) {
            val fallbackRoot = rootInActiveWindow
            if (fallbackRoot != null) {
                try {
                    val rawNodes = mutableListOf<AccessibilityNodeInfo>()
                    collectRawNodes(fallbackRoot, rawNodes)
                    val reconstructedText = SpatialReconstructor.reconstruct(rawNodes)
                    rawNodes.forEach { it.safeRecycle() }

                    if (reconstructedText.isNotEmpty()) {
                        allNodes.addAll(reconstructedText)
                        detectedPackage = fallbackRoot.packageName?.toString() ?: pkg
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing fallback root: ${e.message}")
                } finally {
                    fallbackRoot.safeRecycle()
                }
            }
        }

        if (allNodes.isEmpty()) {
            if (uberAppInForeground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Log.d(TAG, "🔍 UBER: empty accessibility tree — triggering OCR fallback")
                triggerUberScreenshot()
            }
            return
        }
        
        val combined = allNodes.joinToString("|")
        if (combined.contains("See all requests", ignoreCase = true)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                triggerUberScreenshot()
            }
            return
        }

        activeForegroundPlatform = if (detectedPackage.contains("uber", ignoreCase = true)) {
            "uber"
        } else {
            normalizePlatform(detectedPackage)
        }

        val fingerprint = allNodes.joinToString("|")
        val state = stateFor(detectedPackage)
        if (fingerprint == state.lastProcessedText) return
        state.lastProcessedText = fingerprint

        // Dispatch heavy processing to pipelineContext (Dispatchers.Default)
        serviceScope.launch(pipelineContext) {
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
            if (screenWakeLock.isHeld) screenWakeLock.release()
            screenWakeLock.acquire(10_000L)
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
        
        Log.d(TAG, "✅ Notification result for $pkg: fare=${parsedRide.baseFare} profit=${result.netProfit}")

        // Integrate with session cache for "best so far" comparison
        if (parsedRide.rideDistanceKm > 0.0) {
            if (state.sessionCache.isExpired()) state.sessionCache.reset()
            val smartScore = result.netProfit - (parsedRide.pickupDistanceKm * PICKUP_PENALTY_PER_KM * cancelRiskMultiplier(parsedRide.pickupDistanceKm))
            state.sessionCache.addResult(parsedRide, result.netProfit, smartScore)
        }

        val bestSeen = state.sessionCache.getBestSeen()
        val totalCardsSeen = state.sessionCache.getTotalCardsSeen()
        val currentScore = result.netProfit - (parsedRide.pickupDistanceKm * PICKUP_PENALTY_PER_KM * cancelRiskMultiplier(parsedRide.pickupDistanceKm))
        val isBestSoFar = bestSeen == null || currentScore >= (bestSeen.smartScore - 0.01)

        withContext(Dispatchers.Main) {
            if (Settings.canDrawOverlays(this@RideSmartService)) {
                overlayManager.showResult(
                    RideResult(parsedRide, result.totalFare, result.actualPayout, result.fuelCost, result.wearCost, result.netProfit, result.earningPerKm, result.earningPerHour, result.pickupRatio, result.signal, result.failedChecks),
                    totalRidesConsidered = totalCardsSeen,
                    isBestSoFar          = isBestSoFar,
                    bestSeenFare         = bestSeen?.ride?.baseFare ?: parsedRide.baseFare,
                    bestSeenNetProfit    = bestSeen?.netProfit ?: result.netProfit
                )
                
                state.lastShownSmartScore = currentScore
                state.lastFare = parsedRide.baseFare.toFloat()
                state.lastRideTime = System.currentTimeMillis()

                if (normalizePlatform(pkg) == "uber") {
                    uberOfferActiveMs = System.currentTimeMillis()
                }
            }
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

        if (allRides.isEmpty() && isUber) {
            val fallbackRides = ParserFactory.getFallbackParser().parseAll(textNodes, packageName)
            if (fallbackRides.isNotEmpty()) {
                allRides = fallbackRides
            } else if (uberOcrEngine.hasOfferSignals(textNodes)) {
                Log.d(TAG, "🔍 UBER HYBRID: offer signals detected but parsing failed — triggering OCR")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    withContext(Dispatchers.Main) {
                        triggerUberScreenshot()
                    }
                }
                return
            }
        }

        if (allRides.isEmpty()) {
            if (isUber) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    withContext(Dispatchers.Main) {
                        triggerUberScreenshot()
                    }
                }
            }
            return
        }

        val profile = repository.profileFlow.first()
        
        val idleMinutes = if (state.sessionCache.isEmpty()) 0.0
                          else ((System.currentTimeMillis() - state.lastRideTime) / 60_000.0).coerceAtLeast(0.0)

        val scoredRides = allRides.map { ride ->
            val result = calculator.calculate(ride, profile, java.time.LocalTime.now().hour, idleMinutes)
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

        val sameFareTooSoon = bestRide.baseFare.toFloat() == state.lastFare &&
                              now - state.lastRideTime < SAME_FARE_COOLDOWN_MS
        if (sameFareTooSoon) {
            Log.d(TAG, "Duplicate fare suppressed for $packageName")
            if (isUber) uberOfferActiveMs = now
            return
        }

        val hasShownThisSession = state.lastShownSmartScore > Double.MIN_VALUE
        if (hasShownThisSession) {
            if (currentScore < state.lastShownSmartScore + MIN_SCORE_IMPROVEMENT) {
                Log.d(TAG, "Low score improvement for $packageName: $currentScore vs ${state.lastShownSmartScore}")
                if (isUber) uberOfferActiveMs = now
                return
            }
        }

        Log.d(TAG, "✅ Showing overlay for $packageName: " +
            "fare=${bestRide.baseFare} profit=${bestResult.netProfit} " +
            "rideKm=${bestRide.rideDistanceKm} epk=${bestResult.earningPerKm} " +
            "signal=${bestResult.signal}")

        state.lastFare = bestRide.baseFare.toFloat()
        state.lastRideTime = now
        state.lastShownSmartScore = currentScore

        withContext(Dispatchers.Main) {
            if (Settings.canDrawOverlays(this@RideSmartService)) {
                overlayManager.showResult(
                    RideResult(bestRide, bestResult.totalFare, bestResult.actualPayout, bestResult.fuelCost, bestResult.wearCost, bestResult.netProfit, bestResult.earningPerKm, bestResult.earningPerHour, bestResult.pickupRatio, bestResult.signal, bestResult.failedChecks),
                    totalRidesConsidered = totalCardsSeen,
                    isBestSoFar          = isBestSoFar,
                    bestSeenFare         = bestSeen?.ride?.baseFare ?: bestRide.baseFare,
                    bestSeenNetProfit    = bestSeen?.netProfit ?: bestResult.netProfit
                )
                if (isUber) {
                    uberOfferActiveMs = System.currentTimeMillis()
                }
            }
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
        if (!isScreenshotProcessing.compareAndSet(false, true)) return
        
        if (!uberOcrActive) {
            uberOcrActive = true
        }

        val now = System.currentTimeMillis()
        
        if (uberOfferActiveMs > 0 && now - uberOfferActiveMs < 30_000L) {
            isScreenshotProcessing.set(false)
            return
        }

        val currentPkg = recentForegroundPackage
        if (currentPkg != "com.ubercab.driver") {
            isScreenshotProcessing.set(false)
            Log.d(TAG, "⏭ Skipping Uber OCR — foreground is $currentPkg")
            return
        }

        val last = lastScreenshotMs.get()
        if (now - last < screenshotCooldownMs) {
            isScreenshotProcessing.set(false)
            return
        }
        lastScreenshotMs.set(now)

        uberScreenshotJob = serviceScope.launch {
            if (overlayManager.hasActiveOverlays()) {
                overlayManager.hideAllTemporarily()
                delay(150)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
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
                                    isScreenshotProcessing.set(false)
                                    increaseUberPollingBackoff()
                                    overlayManager.restoreAll()
                                    return
                                }
                                serviceScope.launch {
                                    try {
                                        if (!uberOcrActive) {
                                            bitmap.recycle()
                                            isScreenshotProcessing.set(false)
                                            overlayManager.restoreAll()
                                            return@launch
                                        }
                                        
                                        val parsed = uberOcrEngine.parse(bitmap)
                                        if (parsed != null && parsed.baseFare > 0) {
                                            Log.d(TAG, "📸 Uber OCR SUCCESS: fare=${parsed.baseFare} " +
                                                "rideKm=${parsed.rideDistanceKm} pickupKm=${parsed.pickupDistanceKm} " +
                                                "pickupMin=${parsed.pickupTimeMin} rideMin=${parsed.estimatedDurationMin} " +
                                                "premium=${parsed.premiumAmount}")
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
                                                if (parsed.premiumAmount > 0) add("+₹${"%.2f".format(parsed.premiumAmount)} Premium")
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
                                        isScreenshotProcessing.set(false)
                                        overlayManager.restoreAll()
                                    }
                                }
                            }
                            override fun onFailure(errorCode: Int) {
                                Log.e(TAG, "Screenshot failed: $errorCode")
                                isScreenshotProcessing.set(false)
                                increaseUberPollingBackoff()
                                overlayManager.restoreAll()
                            }
                        }
                    )
                } else {
                    // TODO: Implement MediaProjection fallback for API 26-29
                    isScreenshotProcessing.set(false)
                    overlayManager.restoreAll()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error triggering screenshot: ${e.message}")
                isScreenshotProcessing.set(false)
                increaseUberPollingBackoff()
                overlayManager.restoreAll()
            }
        }
    }

    private val lastScreenshotMs = AtomicLong(0L)

    private fun collectRawNodes(node: AccessibilityNodeInfo?, nodes: MutableList<AccessibilityNodeInfo>, depth: Int = 0) {
        if (node == null || depth > MAX_TREE_DEPTH) return
        
        // Spec v2.0 Section 8.2: Skip invisible nodes to eliminate ~30% of nodes
        if (!node.isVisibleToUser) return

        if (!node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()) {
            nodes.add(node.copyNode())
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                collectRawNodes(child, nodes, depth + 1)
                // Spec v2.0 Section 8.4: ALWAYS recycle() immediately
                child.safeRecycle()
            }
        }
    }

    private fun AccessibilityNodeInfo.safeRecycle() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            @Suppress("DEPRECATION")
            this.recycle()
        }
    }

    private fun AccessibilityNodeInfo.copyNode(): AccessibilityNodeInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            AccessibilityNodeInfo(this)
        } else {
            @Suppress("DEPRECATION")
            AccessibilityNodeInfo.obtain(this)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (screenWakeLock.isHeld) screenWakeLock.release()
        stopUberPolling()
        screenshotExecutor.shutdown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        overlayManager.dismiss()
        serviceScope.cancel()
        super.onDestroy()
    }
}
