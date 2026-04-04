package com.ridesmart.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import com.ridesmart.R
import com.ridesmart.data.ProfileRepository
import com.ridesmart.data.RideHistoryRepository
import com.ridesmart.data.RideEntry
import com.ridesmart.engine.ProfitCalculator
import com.ridesmart.engine.RideSessionCache
import com.ridesmart.model.ParsedRide
import com.ridesmart.model.ParseResult
import com.ridesmart.model.PlatformConfig
import com.ridesmart.model.ProfitResult
import com.ridesmart.model.RideResult
import com.ridesmart.model.IncentiveProfile
import com.ridesmart.model.PlanType
import com.ridesmart.model.Signal
import com.ridesmart.overlay.OverlayManager
import com.ridesmart.parser.OlaParser
import com.ridesmart.parser.ParserFactory
import com.ridesmart.parser.UberParser
import com.ridesmart.parser.RapidoParser
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class RideSmartService : AccessibilityService() {

    sealed class NotificationEvent {
        data class Posted(val pkg: String, val title: String, val text: String) : NotificationEvent()
        data class Removed(val pkg: String) : NotificationEvent()
    }

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
            "sinet.startup.inDriver"
        )

        private const val SCAN_THROTTLE_MS       = 500L
        private const val UBER_POLL_BASE_MS      = 1500L
        private const val UBER_POLL_MAX_MS       = 5000L
        private const val MIN_HISTORY_SAVE_INTERVAL_MS = 5 * 60 * 1000L

        val externalNotificationFlow = MutableSharedFlow<NotificationEvent>(
            extraBufferCapacity  = 20,
            onBufferOverflow     = BufferOverflow.DROP_OLDEST
        )
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var calculator:        ProfitCalculator
    private lateinit var repository:        ProfileRepository
    private lateinit var cachedProfile:     StateFlow<com.ridesmart.model.RiderProfile>
    private lateinit var overlayManager:    OverlayManager
    private lateinit var historyRepository: RideHistoryRepository
    
    private val screenshotExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private lateinit var powerManager: PowerManager
    @Volatile private var lastRapidoBundle: RapidoNodeBundle? = null
    @Volatile private var isOnline               = true
    private val uberOcrEngine                    = UberOcrEngine()
    private val uberParser                       = UberParser()
    private val rapidoSessionCache               = RideSessionCache()

    private val eventFlow = MutableSharedFlow<String>(
        extraBufferCapacity = 30,
        onBufferOverflow    = BufferOverflow.DROP_OLDEST
    )

    private val lastScanTime                     = mutableMapOf<String, Long>()
    @Volatile private var uberAppInForeground    = false
    @Volatile private var uberOfferSignalDetected = false
    private var uberPollingJob: Job?             = null
    @Volatile private var uberPollingIntervalMs  = UBER_POLL_MAX_MS
    @Volatile private var isScreenshotProcessing = false
    @Volatile private var consecutiveOcrFailures = 0

    @Volatile private var lastSavedRideKey       = ""
    @Volatile private var lastSavedRideTimeMs    = 0L

    @Volatile private var todayEarningsSoFar: Double = 0.0
    @Volatile private var todayRideCount: Int        = 0

    private fun getStartOfDayMs(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun buildRideKey(ride: ParsedRide, pkg: String): String {
        val p = (ride.pickupDistanceKm * 100).toInt()
        val r = (ride.rideDistanceKm   * 100).toInt()
        val pickupHash = ride.pickupAddress.trim().lowercase().hashCode()
        val dropHash   = ride.dropAddress.trim().lowercase().hashCode()
        return "${pkg}_${(ride.baseFare * 10).toInt()}_${p}_${r}_${pickupHash}_${dropHash}"
    }

    @OptIn(FlowPreview::class)
    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo?.also { info ->
            info.flags = (info.flags or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS)
        }

        calculator        = ProfitCalculator()
        repository        = ProfileRepository(this)
        cachedProfile     = repository.profileFlow.stateIn(
            serviceScope, SharingStarted.Eagerly, com.ridesmart.model.RiderProfile()
        )
        overlayManager    = OverlayManager(this)
        powerManager      = getSystemService(Context.POWER_SERVICE) as PowerManager
        historyRepository = RideHistoryRepository(this)

        serviceScope.launch(Dispatchers.IO) {
            repository.resetIncentiveProgressIfNewDay()
        }

        serviceScope.launch {
            repository.isOnline.collect { online ->
                isOnline = online
                if (!online) {
                    overlayManager.dismissAll()
                    uberPollingJob?.cancel()
                    uberPollingJob = null
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    } else {
                        @Suppress("DEPRECATION")
                        stopForeground(true)
                    }
                } else {
                    startForegroundService()
                    startUberPolling()
                }
            }
        }

        serviceScope.launch {
            historyRepository.getTodayEarnings(getStartOfDayMs())
                .collect { total -> todayEarningsSoFar = total }
        }
        
        serviceScope.launch {
            historyRepository.getTodayRideCount(getStartOfDayMs())
                .collect { count -> todayRideCount = count }
        }

        serviceScope.launch {
            while (isActive) {
                delay(5_000L)
                if (!isOnline) continue
                rapidoSessionCache.evictExpired()
                val cacheStatus = rapidoSessionCache.getBestSeenStatus()
                if (cacheStatus is RideSessionCache.BestSeenStatus.Empty &&
                    overlayManager.isPlatformShowing("Rapido")) {
                    withContext(Dispatchers.Main) {
                        overlayManager.dismissPlatform("Rapido", immediate = true)
                    }
                    rapidoSessionCache.reset()
                }
            }
        }

        serviceScope.launch {
            eventFlow.debounce(200L).collect { pkg ->
                if (!isOnline) return@collect
                withContext(Dispatchers.IO) {
                    val nodes = findNodesForPackage(pkg)
                    if (nodes.isNotEmpty()) {
                        processScreen(nodes, pkg)
                    }
                }
            }
        }

        serviceScope.launch {
            externalNotificationFlow.collect { event ->
                if (!isOnline) return@collect
                when (event) {
                    is NotificationEvent.Posted -> handleExternalNotification(event.pkg, event.title, event.text)
                    is NotificationEvent.Removed -> {
                        val displayName = PlatformConfig.get(event.pkg).displayName
                        overlayManager.dismissPlatform(displayName, immediate = true)
                    }
                }
            }
        }
    }

    private fun startForegroundService() {
        ensureNotificationChannel()
        val notification = buildStatusNotification("Monitoring Active")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun ensureNotificationChannel() {
        val channel = NotificationChannel(
            NOTIF_CHANNEL_ID, "RideSmart Service", NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildStatusNotification(text: String) =
        NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("RideSmart")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()

    private fun startUberPolling() {
        uberPollingJob?.cancel()
        uberPollingJob = serviceScope.launch {
            while (isActive) {
                delay(uberPollingIntervalMs)
                if (!isOnline || !uberOfferSignalDetected) {
                    if (uberPollingIntervalMs != UBER_POLL_MAX_MS) {
                        uberPollingIntervalMs = UBER_POLL_MAX_MS
                    }
                    continue
                }

                if (!overlayManager.isPlatformShowing("Uber")) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        triggerUberScreenshot()
                    }
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!isOnline) return

        val pkg = event.packageName?.toString() ?: ""
        val isSupported = SUPPORTED_PACKAGES.any { pkg.contains(it) }
        if (!isSupported && pkg != "android") return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg.isNotBlank() && pkg != "android") {
            if (pkg.contains("uber", ignoreCase = true)) {
                uberAppInForeground = true
            } else if (!pkg.contains("launcher", ignoreCase = true) && 
                       !pkg.contains("systemui", ignoreCase = true)) {
                uberAppInForeground = false
                if (uberOfferSignalDetected) {
                    uberOfferSignalDetected = false
                    uberPollingIntervalMs = UBER_POLL_MAX_MS
                }
                
                if (!pkg.contains("rapido", ignoreCase = true)) {
                    rapidoSessionCache.reset()
                }
            }
        }

        when {
            pkg.contains("uber", ignoreCase = true) -> {
                handleUberEvent(event, pkg)
            }
            pkg.isNotBlank() && pkg != "android" && !pkg.contains("systemui") -> {
                handleThrottledScan(pkg)
            }
        }

        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            handleInlineNotification(event, pkg)
        }
    }

    private fun handleUberEvent(event: AccessibilityEvent, pkg: String) {
        uberAppInForeground = true
        val eventTexts = event.text?.map { it.toString() } ?: emptyList()
        val eventFlat  = eventTexts.joinToString(" ")
        val hasFareInEvent = eventFlat.contains("₹") ||
                             eventTexts.any { it.contains("km", true) && it.length < 20 }

        when (event.eventType) {
            AccessibilityEvent.TYPE_ANNOUNCEMENT -> {
                val text = event.contentDescription?.toString() ?: eventFlat
                if (text.contains("₹") || text.contains("request", true) ||
                    text.contains("trip", true) || text.contains("min", true)) {
                    setUberOfferSignal()
                    scheduleImmediateOcr()
                }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (hasFareInEvent) {
                    setUberOfferSignal()
                    scheduleImmediateOcr()
                }
                handleThrottledScan(pkg)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                if (hasFareInEvent) setUberOfferSignal()
                handleThrottledScan(pkg)
            }
            else -> handleThrottledScan(pkg)
        }
    }

    private fun setUberOfferSignal() {
        uberOfferSignalDetected  = true
        uberPollingIntervalMs    = UBER_POLL_BASE_MS
    }

    private fun scheduleImmediateOcr() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (isScreenshotProcessing || overlayManager.isPlatformShowing("Uber") || !isOnline) return
        serviceScope.launch(Dispatchers.Main) {
            delay(80L)
            triggerUberScreenshot()
        }
    }

    private suspend fun processScreen(nodes: List<String>, pkg: String) {
        if (!isOnline) return

        if (pkg.contains("uber", ignoreCase = true)) {
            processUberNodes(nodes, pkg)
            return
        }

        val parser = ParserFactory.getParser(pkg)
        val result = parser.parseAll(nodes, pkg)

        when (result) {
            is ParseResult.Success -> {
                val profile = cachedProfile.value
                val best = result.rides
                    .filter { isValidRideOffer(it) }
                    .map { r ->
                        val platformName = PlatformConfig.get(pkg).displayName
                        val incentive    = profile.incentiveProfiles[platformName]
                                            ?: com.ridesmart.model.IncentiveProfile()
                        val bMarg        = calculator.marginalBonusValue(incentive)
                        val res          = calculator.calculate(r, profile, bMarg, todayRideCount)
                        Triple(r, res, res.decisionScore)
                    }.filter { it.second.hardRejectReason == null }
                    .maxByOrNull { it.third } ?: return
                showRideOverlay(best.first, pkg, best.second)
            }
            is ParseResult.Idle -> {
                if (pkg.contains("rapido", true)) rapidoSessionCache.reset()
                NotificationDataCache.invalidate(pkg)
                withContext(Dispatchers.Main) {
                    overlayManager.dismissPlatform(PlatformConfig.get(pkg).displayName, true)
                }
            }
            else -> {}
        }
    }

    private suspend fun processUberNodes(nodes: List<String>, pkg: String) {
        val combined = nodes.joinToString("|")
        
        val hasOfferButton = nodes.any {
            it.contains("Accept", true) || it.contains("Decline", true) ||
            it.contains("Confirm", true) || it.contains("Offline", true) || it.contains("Match", true)
        }
        
        val hasFare = combined.contains("₹") || combined.contains("Rs") || combined.contains("F") || combined.contains("Tt")
        val hasKm   = combined.contains(" km", ignoreCase = true) || combined.contains(" min", ignoreCase = true)

        if (hasOfferButton || hasFare) setUberOfferSignal()

        if (hasOfferButton && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !isScreenshotProcessing && !overlayManager.isPlatformShowing("Uber") && isOnline) {
            withContext(Dispatchers.Main) { triggerUberScreenshot() }
        }

        if (hasFare && hasKm) {
            val result = uberParser.parseAll(nodes, pkg)
            if (result is ParseResult.Success) {
                val profile = cachedProfile.value
                val best = result.rides
                    .filter { isValidRideOffer(it) }
                    .map { r ->
                        val platformName = PlatformConfig.get(pkg).displayName
                        val incentive    = profile.incentiveProfiles[platformName]
                                            ?: com.ridesmart.model.IncentiveProfile()
                        val bMarg        = calculator.marginalBonusValue(incentive)
                        val res          = calculator.calculate(r, profile, bMarg, todayRideCount)
                        Triple(r, res, res.decisionScore)
                    }.filter { it.second.hardRejectReason == null }
                    .maxByOrNull { it.third } ?: return
                showRideOverlay(best.first, pkg, best.second)
                return
            }
        }

        val uberHomeMarkers = listOf(
            "you're online", "finding trips", "you're offline",
            "see weekly summary", "upcoming promotions", "no requests right now"
        )
        if (uberHomeMarkers.any { combined.contains(it, ignoreCase = true) }) {
            uberOfferSignalDetected = false
            uberPollingIntervalMs   = UBER_POLL_MAX_MS
            withContext(Dispatchers.Main) {
                overlayManager.dismissPlatform("Uber", immediate = true)
            }
        }
    }

    private fun handleInlineNotification(event: AccessibilityEvent, pkg: String) {
        if (!isOnline) return
        val notification = event.parcelableData as? Notification ?: return
        val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text  = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()  ?: ""
        serviceScope.launch(Dispatchers.Default) {
            handleExternalNotification(pkg, title, text)
        }
    }

    private fun handleExternalNotification(pkg: String, title: String, text: String) {
        NotificationDataCache.store(pkg, title, text)
        serviceScope.launch(Dispatchers.Default) {
            if (pkg.contains("ubercab", true)) {
                val combined = "$title $text"
                if (combined.contains("₹") || combined.contains("request", true) ||
                    combined.contains("min", true) || combined.contains("km", true)) {
                    setUberOfferSignal()
                    scheduleImmediateOcr()
                }
                return@launch
            }
            val parser = ParserFactory.getParser(pkg)
            val result = if (pkg.contains("ola", true)) {
                (parser as? OlaParser)?.parseFromNotification(title, text, pkg)
                    ?.let { ParseResult.Success(listOf(it)) } ?: ParseResult.Idle
            } else {
                parser.parseAll(listOf(title, text), pkg)
            }
            if (result is ParseResult.Success) {
                result.rides
                    .filter { isValidRideOffer(it) }
                    .firstOrNull()
                    ?.let { showRideOverlay(it, pkg) }
            }
        }
    }

    private fun showRideOverlay(ride: ParsedRide, pkg: String, result: ProfitResult? = null) {
        serviceScope.launch {
            val profile = cachedProfile.value
            val platformName = PlatformConfig.get(pkg).displayName
            
            val res = result ?: run {
                val incentive = profile.incentiveProfiles[platformName]
                                    ?: com.ridesmart.model.IncentiveProfile()
                val bMarg = calculator.marginalBonusValue(incentive)
                calculator.calculate(ride, profile, bMarg, todayRideCount)
            }

            // Use bundle-enriched parse for Rapido when available
            if (pkg.contains("rapido", ignoreCase = true)) {
                val bundle = lastRapidoBundle
                lastRapidoBundle = null
                if (bundle != null) {
                    val bundleResult = RapidoParser().parseFromBundle(bundle, pkg)
                    if (bundleResult is ParseResult.Success) {
                        val enrichedRide = bundleResult.rides.firstOrNull()
                        if (enrichedRide != null && enrichedRide.rideDistanceKm > 0.0) {
                            Log.d(TAG, "Rapido: using bundle-enriched ride fare=${enrichedRide.baseFare} vehicle=${enrichedRide.vehicleType}")
                            // re-calculate with enriched ride and show overlay
                            val enrichedIncentive = profile.incentiveProfiles[platformName]
                                                        ?: com.ridesmart.model.IncentiveProfile()
                            val enrichedBMarg = calculator.marginalBonusValue(enrichedIncentive)
                            val enrichedRes   = calculator.calculate(enrichedRide, profile, enrichedBMarg, todayRideCount)
                            showRideOverlay(enrichedRide, pkg, enrichedRes)
                            return@launch
                        }
                    }
                }
            }
            
            val status = if (pkg.contains("rapido", true)) {
                rapidoSessionCache.addOrRefresh(ride, res.netProfit, res.decisionScore)
                rapidoSessionCache.getBestSeenStatus()
            } else null

            val best = (status as? RideSessionCache.BestSeenStatus.Active)?.result
                    ?: (status as? RideSessionCache.BestSeenStatus.Stale)?.result

            val current = if (pkg.contains("rapido", true))
                rapidoSessionCache.getResultFor(ride) else null

            val bestSeenNote: String? = when (status) {
                is RideSessionCache.BestSeenStatus.Stale ->
                    "⚠ Best ride (card #${status.result.cardIndex}) may be gone"
                is RideSessionCache.BestSeenStatus.Active ->
                    if (best?.ride != ride)
                        "↑ Better at card #${best!!.cardIndex} • ₹${best.netProfit.toInt()}"
                    else null
                else -> null
            }

            val rideResult = RideResult(
                parsedRide        = ride,
                totalFare         = res.totalFare,
                actualPayout      = res.actualPayout,
                fuelCost          = res.fuelCost,
                wearCost          = res.wearCost,
                netProfit         = res.netProfit,
                netProfitCash     = res.netProfitCash,
                efficiencyPerKm   = res.efficiencyPerKm,
                earningPerHour    = res.earningPerHour,
                pickupRatio       = res.pickupRatio,
                hardRejectReason  = res.hardRejectReason,
                signal            = res.signal,
                failedChecks      = res.failedChecks,
                todayEarnings     = todayEarningsSoFar,
                dailyTargetAmount = profile.dailyEarningTarget,
                decisionScore     = res.decisionScore,
                isBestSoFar       = best == null || res.decisionScore >= (best.decisionScore),
                bestNetProfit     = best?.netProfit ?: res.netProfit,
                cardIndex         = current?.cardIndex ?: 1,
                totalCardsSeen    = rapidoSessionCache.getTotalCardsSeen(),
                bestSeenNote      = bestSeenNote
            )
            
            withContext(Dispatchers.Main) {
                overlayManager.showResult(rideResult)
            }
            val key = buildRideKey(ride, pkg)
            val now = System.currentTimeMillis()
            if (key != lastSavedRideKey || now - lastSavedRideTimeMs > MIN_HISTORY_SAVE_INTERVAL_MS) {
                lastSavedRideKey    = key
                lastSavedRideTimeMs = now
                saveRideToHistory(ride, res, pkg)
            }
        }
    }

    private fun saveRideToHistory(ride: ParsedRide, result: ProfitResult, pkg: String) {
        serviceScope.launch(Dispatchers.IO) {
            val cal = Calendar.getInstance()

            historyRepository.saveRide(
                RideEntry(
                    timestampMs          = System.currentTimeMillis(),
                    hourOfDay            = cal.get(Calendar.HOUR_OF_DAY),
                    dayOfWeek            = cal.get(Calendar.DAY_OF_WEEK),
                    platform             = PlatformConfig.get(pkg).displayName,
                    packageName          = pkg,
                    baseFare             = ride.baseFare,
                    tipAmount            = ride.tipAmount,
                    premiumAmount        = ride.premiumAmount,
                    actualPayout         = result.actualPayout,
                    rideKm               = ride.rideDistanceKm,
                    pickupKm             = ride.pickupDistanceKm,
                    totalKm              = ride.rideDistanceKm + ride.pickupDistanceKm,
                    pickupRatioPct       = if (ride.rideDistanceKm > 0)
                        (ride.pickupDistanceKm / (ride.rideDistanceKm + ride.pickupDistanceKm) * 100).toInt()
                    else 0,
                    estimatedDurationMin = ride.estimatedDurationMin,
                    fuelCost             = result.fuelCost,
                    wearCost             = result.wearCost,
                    totalCost            = result.fuelCost + result.wearCost,
                    netProfit            = result.netProfit,
                    efficiencyPerKm      = result.efficiencyPerKm,
                    pickupPenaltyPct     = 0.0,
                    earningPerHour       = result.earningPerHour,
                    signal               = result.signal,
                    failedChecks         = result.failedChecks.joinToString("|"),
                    decisionScore        = result.decisionScore,
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
        if (!isOnline || isScreenshotProcessing) return
        isScreenshotProcessing = true

        takeScreenshot(Display.DEFAULT_DISPLAY, screenshotExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(res: ScreenshotResult) {
                    val hardwareBuffer = res.hardwareBuffer
                    val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, res.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                    hardwareBuffer.close()
                    bitmap?.let { b ->
                        serviceScope.launch(Dispatchers.Default) {
                            val lines     = uberOcrEngine.extractLines(b)
                            val ocrResult = uberParser.parseAll(lines, "com.ubercab.driver")
                            when {
                                ocrResult is ParseResult.Success -> {
                                    consecutiveOcrFailures = 0 
                                    ocrResult.rides
                                        .filter { isValidRideOffer(it) }
                                        .firstOrNull()
                                        ?.let {
                                            showRideOverlay(it, "com.ubercab.driver")
                                        }
                                }
                                ocrResult is ParseResult.Failure -> {
                                    consecutiveOcrFailures++
                                    if (consecutiveOcrFailures >= 2) {
                                        uberOfferSignalDetected = false
                                        uberPollingIntervalMs   = UBER_POLL_MAX_MS
                                        consecutiveOcrFailures = 0
                                    } else {
                                        uberPollingIntervalMs = UBER_POLL_BASE_MS
                                    }
                                }
                                else -> {}
                            }
                            b.recycle()
                            isScreenshotProcessing = false
                        }
                    } ?: run { isScreenshotProcessing = false }
                }
                override fun onFailure(err: Int) {
                    isScreenshotProcessing = false
                }
            }
        )
    }

    private fun findNodesForPackage(packageName: String): List<String> {
        return try {
            val liveNodes: List<String> = run {
                val rootNode = rootInActiveWindow
                if (rootNode?.packageName?.toString() == packageName) {
                    if (packageName.contains("rapido", ignoreCase = true)) {
                        val bundle = collectRapidoNodesSafely(rootNode)
                        lastRapidoBundle = bundle
                        bundle.allTextNodes
                    } else {
                        collectAllTextSafely(rootNode)
                    }
                } else {
                    val uberWindow = windows.find { w ->
                        w.root?.packageName?.toString() == packageName ||
                        w.title?.toString()?.contains("uber", ignoreCase = true) == true
                    }
                    val nodes = uberWindow?.root?.let { collectAllTextSafely(it) } ?: emptyList()
                    if (nodes.isEmpty() && uberWindow != null &&
                        packageName.contains("uber", true)) {
                        setUberOfferSignal()
                        scheduleImmediateOcr()
                    }
                    nodes
                }
            }
            if (liveNodes.isEmpty() && !powerManager.isInteractive) {
                val cached = NotificationDataCache.getFreshNodes(packageName)
                if (cached != null) {
                    Log.d(TAG, "findNodes: screen locked, using cached for $packageName")
                    return cached
                }
            }
            liveNodes
        } catch (e: Exception) { emptyList() }
    }

    private fun collectRapidoNodesSafely(root: AccessibilityNodeInfo?): RapidoNodeBundle {
        val empty = RapidoNodeBundle("", "", "", "", "", "", "", emptyList())
        if (root == null) return empty

        var fare               = ""
        var vehicleType        = ""
        var offerAgeText       = ""
        var pickupAddress      = ""
        var pickupSubText      = ""
        var pickupDistanceText = ""
        var dropAddress        = ""
        val allText            = mutableListOf<String>()

        val toVisit   = ArrayDeque<AccessibilityNodeInfo>()
        val toRecycle = mutableListOf<AccessibilityNodeInfo>()
        toVisit.add(root)
        var visited = 0

        while (toVisit.isNotEmpty() && visited < 300) {
            val node = toVisit.removeFirst()
            toRecycle.add(node)
            visited++

            // ── Read node text ───────────────────────────────────────────
            val nodeText = node.text?.toString()?.trim() ?: ""
            val nodeDesc = node.contentDescription?.toString()?.trim() ?: ""

            // Collect all text for the flat fallback list
            if (nodeText.isNotBlank()) allText.add(nodeText)
            if (nodeDesc.isNotBlank() && nodeDesc != nodeText) allText.add(nodeDesc)

            // ── Match by resource ID suffix ──────────────────────────────
            val idName = node.viewIdResourceName
                ?.substringAfter("/", "") ?: ""

            when (idName) {
                "service_name_tv" -> {
                    if (nodeText.isNotBlank()) vehicleType = nodeText
                }
                "order_accept_time_tv" -> {
                    if (nodeText.isNotBlank()) offerAgeText = nodeText
                }
                "amount_tv" -> {
                    // ComposeView — text not in node.text; try 3 strategies
                    val composeText = when {
                        nodeDesc.isNotBlank() -> nodeDesc
                        nodeText.isNotBlank() -> nodeText
                        else -> {
                            // Strategy 3: walk virtual semantic children
                            val sb = StringBuilder()
                            for (i in 0 until node.childCount) {
                                val child = node.getChild(i) ?: continue
                                val ct = child.text?.toString()?.trim() ?: ""
                                val cd = child.contentDescription?.toString()?.trim() ?: ""
                                if (ct.isNotBlank()) sb.append(ct).append(" ")
                                if (cd.isNotBlank() && cd != ct) sb.append(cd).append(" ")
                                try { child.recycle() } catch (_: Exception) {}
                            }
                            sb.toString().trim()
                        }
                    }
                    if (composeText.isNotBlank()) {
                        fare = composeText
                        // Also add to allText so fallback parsers can see it
                        if (!allText.contains(composeText)) allText.add(composeText)
                    }
                }
                "pickup_location_tv" -> {
                    if (nodeText.isNotBlank()) pickupAddress = nodeText
                }
                "pickup_location_sub_text" -> {
                    if (nodeText.isNotBlank()) pickupSubText = nodeText
                }
                "distanceTv" -> {
                    if (nodeText.isNotBlank()) pickupDistanceText = nodeText
                }
                "drop_location_tv" -> {
                    if (nodeText.isNotBlank()) dropAddress = nodeText
                }
            }

            // ── Queue children ───────────────────────────────────────────
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { toVisit.add(it) }
            }
        }

        toRecycle.forEach { try { it.recycle() } catch (_: Exception) {} }

        Log.d(TAG, "RapidoBundle: fare='$fare' vehicle='$vehicleType' pickupDist='$pickupDistanceText' offerAge='$offerAgeText'")

        return RapidoNodeBundle(
            fare               = fare,
            vehicleType        = vehicleType,
            offerAgeText       = offerAgeText,
            pickupAddress      = pickupAddress,
            pickupSubText      = pickupSubText,
            pickupDistanceText = pickupDistanceText,
            dropAddress        = dropAddress,
            allTextNodes       = allText
        )
    }

    private fun collectAllTextSafely(root: AccessibilityNodeInfo?): List<String> {
        if (root == null) return emptyList()
        val results   = mutableListOf<String>()
        val toVisit   = ArrayDeque<AccessibilityNodeInfo>()
        val toRecycle = mutableListOf<AccessibilityNodeInfo>()
        toVisit.add(root)
        var visited = 0

        while (toVisit.isNotEmpty() && visited < 300) {
            val node = toVisit.removeFirst()
            toRecycle.add(node)
            visited++

            val text = node.text?.toString()?.trim()
            if (!text.isNullOrEmpty()) results.add(text)
            val cd = node.contentDescription?.toString()?.trim()
            if (!cd.isNullOrEmpty() && cd != text) results.add(cd)

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { toVisit.add(it) }
            }
        }
        toRecycle.forEach { try { it.recycle() } catch (_: Exception) {} }
        return results
    }

    private fun isValidRideOffer(ride: ParsedRide): Boolean {
        if (ride.rideDistanceKm < 0.3) return false
        if (ride.baseFare < 10.0) return false
        if ((ride.pickupDistanceKm + ride.rideDistanceKm) < 0.3) return false
        val maxReasonableFare = when {
            ride.vehicleType == com.ridesmart.model.VehicleType.CAR -> 2000.0
            ride.vehicleType == com.ridesmart.model.VehicleType.DELIVERY -> 1500.0
            else -> 800.0
        }
        if (ride.baseFare > maxReasonableFare) return false
        val junkAddressKeywords = listOf(
            "performance icon", "right on track", "quick actions",
            "choose your earning plan", "view rate card", "view all plans",
            "government taxes", "commission", "on-ride booking",
            "select your next plan", "terms and conditions",
            "incentives and more", "portrait image card",
            "fixed commission", "earning (minimum)", "plan is active"
        )
        val pickupLower = ride.pickupAddress.lowercase()
        val dropLower = ride.dropAddress.lowercase()
        if (junkAddressKeywords.any { pickupLower.contains(it) }) return false
        if (junkAddressKeywords.any { dropLower.contains(it) }) return false
        return true
    }

    private fun handleThrottledScan(pkg: String) {
        val now = System.currentTimeMillis()
        if (now - (lastScanTime[pkg] ?: 0L) < SCAN_THROTTLE_MS) return
        lastScanTime[pkg] = now
        serviceScope.launch { eventFlow.emit(pkg) }
    }

    override fun onInterrupt() {}
    override fun onDestroy() {
        uberPollingJob?.cancel()
        serviceScope.cancel()
        screenshotExecutor.shutdown()
        NotificationDataCache.clear()
        super.onDestroy()
    }
}
