package com.ridesmart.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.ridesmart.R
import com.ridesmart.model.RideResult
import com.ridesmart.model.Signal
import com.ridesmart.model.VehicleType
import kotlin.math.roundToInt

/**
 * Manages two permanent side strips that show ride data without ever
 * blocking the center of the screen.
 *
 * RIGHT STRIP (76dp, right edge):
 *   Always visible when any offer is active.
 *   Shows full details of the "featured" offer (highest decisionScore by
 *   default, or whichever the driver last tapped on the left strip).
 *
 * LEFT STRIP (68dp, left edge):
 *   Only visible when 2+ platforms have active offers simultaneously.
 *   Shows one compact ranked card per platform sorted by decisionScore.
 *   Driver taps a card to pin that platform to the right strip.
 *
 * Neither strip ever expands, moves, or covers the center.
 */
class OverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val powerManager  = context.getSystemService(Context.POWER_SERVICE)  as PowerManager
    private val handler       = Handler(Looper.getMainLooper())

    // All currently active ride offers keyed by platform display name
    // e.g. "Rapido", "Ola", "Uber", "Shadowfax"
    private val activePlatforms = LinkedHashMap<String, RideResult>()

    // Which platform is pinned to the right strip by driver tap.
    // null = auto mode — always show platform with highest decisionScore.
    private var featuredPlatform: String? = null

    // Right strip — present whenever any offer is active
    private var rightStripView:   View? = null
    private var rightStripParams: WindowManager.LayoutParams? = null

    // Left strip — present only when 2+ offers active simultaneously
    private var leftStripView:   View? = null
    private var leftStripParams: WindowManager.LayoutParams? = null

    // Auto-dismiss runnable per platform
    private val dismissRunnables = mutableMapOf<String, Runnable>()

    // WakeLock to light up screen when offer arrives
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        private const val TAG             = "RideSmart"
        private const val AUTO_DISMISS_MS = 12_000L
        private const val RIGHT_STRIP_DP  = 76
        private const val LEFT_STRIP_DP   = 68
        // Start strips 38% down from top — avoids Reject/X buttons on
        // Shadowfax and Uber which sit at the top-right of the screen.
        private const val VERTICAL_ANCHOR = 0.38
    }

    // Kept for compatibility with RideSmartService
    var onAutoDismiss: ((String) -> Unit)? = null

    // ── Public entry point ────────────────────────────────────────────────
    // Called by RideSmartService every time a new or updated ride arrives.
    // Signature matches existing RideSmartService call: showResult(rideResult)

    fun showResult(result: RideResult) {
        val platform = result.parsedRide.platform
        handler.post {
            wakeScreen()

            // Cancel any existing auto-dismiss for this platform
            dismissRunnables[platform]?.let { handler.removeCallbacks(it) }

            // Store / update the result
            activePlatforms[platform] = result

            // Create strip views in WindowManager if not yet present
            ensureViews()

            // Re-render both strips with updated data
            renderRightStrip()
            renderLeftStrip()

            // Schedule auto-dismiss
            val runnable = Runnable {
                dismissPlatform(platform)
                onAutoDismiss?.invoke(platform)
            }
            dismissRunnables[platform] = runnable
            handler.postDelayed(runnable, AUTO_DISMISS_MS)
        }
    }

    // ── View lifecycle ────────────────────────────────────────────────────

    private fun ensureViews() {
        val dm      = context.resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val yPos    = (screenH * VERTICAL_ANCHOR).toInt()

        val rightStripPx = (RIGHT_STRIP_DP * dm.density).toInt()
        val leftStripPx  = (LEFT_STRIP_DP  * dm.density).toInt()

        if (rightStripView == null) {
            val view = LayoutInflater.from(context).inflate(R.layout.overlay_right_strip, null)
            val params = WindowManager.LayoutParams(
                rightStripPx,                                        // ← exact 76dp width
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = screenW - rightStripPx                          // ← pinned to right edge
                y = yPos
            }
            try {
                windowManager.addView(view, params)
                rightStripView   = view
                rightStripParams = params
            } catch (e: Exception) {
                Log.e(TAG, "addView rightStrip: ${e.message}")
            }
        }

        if (leftStripView == null) {
            val view = LayoutInflater.from(context).inflate(R.layout.overlay_left_strip, null)
            val params = WindowManager.LayoutParams(
                leftStripPx,                                         // ← exact 68dp width
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0                                                // ← pinned to left edge
                y = yPos
            }
            try {
                windowManager.addView(view, params)
                leftStripView   = view
                leftStripParams = params
            } catch (e: Exception) {
                Log.e(TAG, "addView leftStrip: ${e.message}")
            }
        }
    }

    private fun tearDownViews() {
        rightStripView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) { }
        }
        rightStripView   = null
        rightStripParams = null

        leftStripView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) { }
        }
        leftStripView   = null
        leftStripParams = null

        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) { }
    }

    // ── Right strip rendering ─────────────────────────────────────────────

    private fun renderRightStrip() {
        val view = rightStripView ?: return
        if (activePlatforms.isEmpty()) {
            view.visibility = View.GONE
            return
        }
        view.visibility = View.VISIBLE
        val result = getFeaturedResult() ?: return
        bindRightStrip(view, result)
    }

    // Returns the result to feature on the right strip.
    // Uses the driver-pinned platform if set, otherwise picks highest decisionScore.
    private fun getFeaturedResult(): RideResult? {
        if (activePlatforms.isEmpty()) return null
        val pinned = featuredPlatform
        if (pinned != null && activePlatforms.containsKey(pinned)) return activePlatforms[pinned]
        return activePlatforms.values.maxByOrNull { it.decisionScore }
    }

    private fun bindRightStrip(view: View, result: RideResult) {
        val accentColor = signalColor(result.signal)

        view.findViewById<View>(R.id.rs_accent_bar).setBackgroundColor(accentColor)

        val tvBadge = view.findViewById<TextView>(R.id.rs_badge)
        tvBadge.text = when (result.signal) {
            Signal.GREEN  -> "BEST"
            Signal.YELLOW -> "WARN"
            Signal.RED    -> "SKIP"
        }
        tvBadge.setTextColor(accentColor)

        val tvTimeCtx = view.findViewById<TextView>(R.id.rs_time_context)
        val hint      = timeContextHint()
        if (hint.isNotEmpty()) {
            tvTimeCtx.text       = hint
            tvTimeCtx.visibility = View.VISIBLE
        } else {
            tvTimeCtx.visibility = View.GONE
        }

        // Net profit — hero number
        view.findViewById<TextView>(R.id.rs_net).text =
            "₹${result.netProfit.roundToInt()}"

        // Gross fare
        view.findViewById<TextView>(R.id.rs_fare).text =
            "₹${result.totalFare.roundToInt()}"

        // Extras: tip and/or premium
        val tvExtras = view.findViewById<TextView>(R.id.rs_extras)
        val extras   = buildExtras(result)
        if (extras.isNotEmpty()) {
            tvExtras.text       = extras
            tvExtras.visibility = View.VISIBLE
        } else {
            tvExtras.visibility = View.GONE
        }

        // Platform
        view.findViewById<TextView>(R.id.rs_platform).text =
            result.parsedRide.platform.uppercase()

        // Pass tag — shown for subscription-model platforms
        val tvPassTag = view.findViewById<TextView>(R.id.rs_pass_tag)
        val isPassPlatform = when (result.parsedRide.packageName) {
            "com.rapido.rider" -> true
            else               -> false
        }
        tvPassTag.visibility = if (isPassPlatform) View.VISIBLE else View.GONE

        // Vehicle type
        val tvVehicle   = view.findViewById<TextView>(R.id.rs_vehicle)
        val vehicleType = result.parsedRide.vehicleType
        if (vehicleType != VehicleType.UNKNOWN) {
            tvVehicle.text       = vehicleType.displayName.uppercase()
            tvVehicle.visibility = View.VISIBLE
        } else {
            tvVehicle.visibility = View.GONE
        }

        // ₹/km — honest metric
        view.findViewById<TextView>(R.id.rs_per_km).text =
            "₹${"%.1f".format(result.efficiencyPerKm)}/km"

        // ₹/hr — hidden when 0 (Rapido loading state has no duration)
        val tvPerHour = view.findViewById<TextView>(R.id.rs_per_hour)
        if (result.earningPerHour > 0) {
            tvPerHour.text       = "₹${result.earningPerHour.roundToInt()}/hr"
            tvPerHour.visibility = View.VISIBLE
        } else {
            tvPerHour.visibility = View.GONE
        }

        // Pickup distance — absolute km thresholds
        val pickupKm = result.parsedRide.pickupDistanceKm
        val pickupColor = when {
            pickupKm <= 0.8 -> 0xFF16A34A.toInt()  // green  — short, fine
            pickupKm <= 1.8 -> 0xFFCA8A04.toInt()  // amber  — medium, driver decides
            else            -> 0xFFDC2626.toInt()  // red    — long, meaningful warning
        }
        val tvPickup = view.findViewById<TextView>(R.id.rs_pickup)
        tvPickup.text = "${"%.1f".format(pickupKm)}km"
        tvPickup.setTextColor(pickupColor)

        val tvPickupPct = view.findViewById<TextView>(R.id.rs_pickup_pct)
        val pickupPct = (result.pickupRatio * 100).roundToInt()
        tvPickupPct.text = "${pickupPct}% pkup"
        tvPickupPct.setTextColor(pickupColor)

        // Payment type badge
        val tvPayment    = view.findViewById<TextView>(R.id.rs_payment)
        val paymentLabel = normalisePaymentType(result.parsedRide.paymentType)
        if (paymentLabel.isNotBlank()) {
            tvPayment.text       = paymentLabel
            tvPayment.visibility = View.VISIBLE
        } else {
            tvPayment.visibility = View.GONE
        }

        // Costs
        view.findViewById<TextView>(R.id.rs_fuel).text =
            "fuel ₹${"%.0f".format(result.fuelCost)}"
        view.findViewById<TextView>(R.id.rs_wear).text =
            "wear ₹${"%.0f".format(result.wearCost)}"

        val tvScore = view.findViewById<TextView>(R.id.rs_score)
        if (result.decisionScore > 0.0) {
            val scoreInt   = result.decisionScore.toInt().coerceIn(0, 100)
            val scoreColor = when {
                scoreInt >= 75 -> 0xFF16A34A.toInt()   // green  — great ride
                scoreInt >= 45 -> 0xFFCA8A04.toInt()   // amber  — acceptable
                else           -> 0xFFDC2626.toInt()   // red    — poor ride
            }
            tvScore.text       = "S:$scoreInt"
            tvScore.setTextColor(scoreColor)
            tvScore.visibility = View.VISIBLE
        } else {
            tvScore.visibility = View.GONE
        }

        // Daily progress — shown only when a daily target is set
        val tvToday = view.findViewById<TextView>(R.id.rs_today)
        if (result.dailyTargetAmount > 0.0) {
            val done = result.todayEarnings.roundToInt()
            val target = result.dailyTargetAmount.roundToInt()
            tvToday.text = "₹$done / ₹$target"
            tvToday.visibility = View.VISIBLE
        } else {
            tvToday.visibility = View.GONE
        }

        // Failed checks — abbreviated to fit 76dp width
        val tvChecksDivider = view.findViewById<View>(R.id.rs_checks_divider)
        val tvChecks        = view.findViewById<TextView>(R.id.rs_checks)
        if (result.failedChecks.isNotEmpty()) {
            tvChecks.text              = result.failedChecks.joinToString("\n") { abbreviateCheck(it) }
            tvChecks.visibility        = View.VISIBLE
            tvChecksDivider.visibility = View.VISIBLE
        } else {
            tvChecks.visibility        = View.GONE
            tvChecksDivider.visibility = View.GONE
        }
    }

    // ── Left strip rendering ──────────────────────────────────────────────

    private fun renderLeftStrip() {
        val view = leftStripView ?: return

        // Only show left strip when there are 2 or more competing offers
        if (activePlatforms.size < 2) {
            view.visibility = View.GONE
            return
        }

        view.visibility = View.VISIBLE

        val container      = view.findViewById<LinearLayout>(R.id.ls_cards_container)
        val featuredResult = getFeaturedResult()
        val inflater       = LayoutInflater.from(context)

        container.removeAllViews()

        // Sort by decisionScore descending and render one card per platform
        activePlatforms.values
            .sortedByDescending { it.decisionScore }
            .forEachIndexed { index, result ->
                val cardView = inflater.inflate(R.layout.overlay_left_card, container, false)
                bindLeftCard(
                    view       = cardView,
                    result     = result,
                    rank       = index + 1,
                    isFeatured = (result == featuredResult)
                )
                cardView.setOnClickListener {
                    // Pin this platform to the right strip
                    featuredPlatform = result.parsedRide.platform
                    renderRightStrip()
                    renderLeftStrip()
                }
                container.addView(cardView)
            }
    }

    private fun bindLeftCard(view: View, result: RideResult, rank: Int, isFeatured: Boolean) {
        val accentColor = signalColor(result.signal)
        val tintBg = when (result.signal) {
            Signal.GREEN  -> 0x2216A34A.toInt()
            Signal.YELLOW -> 0x22CA8A04.toInt()
            Signal.RED    -> 0x22DC2626.toInt()
        }

        view.findViewById<View>(R.id.lc_root).setBackgroundColor(tintBg)
        view.findViewById<View>(R.id.lc_accent_bar).setBackgroundColor(accentColor)

        val platformAbbr = result.parsedRide.platform.take(3).uppercase()
        // Show rank, platform and decisionScore
        val score = result.decisionScore.toInt().coerceIn(0, 100)
        view.findViewById<TextView>(R.id.lc_rank_platform).text = "#$rank $platformAbbr S:$score"

        val tvNet = view.findViewById<TextView>(R.id.lc_net)
        tvNet.text = "₹${result.netProfit.roundToInt()}"
        tvNet.setTextColor(if (isFeatured) accentColor else 0xFFFFFFFF.toInt())

        view.findViewById<TextView>(R.id.lc_ride_km).text =
            "${"%.1f".format(result.parsedRide.rideDistanceKm)}k"

        val tvSelected = view.findViewById<TextView>(R.id.lc_selected_label)
        tvSelected.visibility = if (isFeatured) View.VISIBLE else View.GONE
    }

    // ── Dismissal ─────────────────────────────────────────────────────────

    // Called by RideSmartService as: dismissPlatform(displayName, immediate = true)
    fun dismissPlatform(platform: String, immediate: Boolean = false) {
        handler.post {
            dismissRunnables[platform]?.let { handler.removeCallbacks(it) }
            dismissRunnables.remove(platform)
            activePlatforms.remove(platform)

            // If driver had pinned this platform, revert to auto mode
            if (featuredPlatform == platform) featuredPlatform = null

            if (activePlatforms.isEmpty()) {
                tearDownViews()
            } else {
                renderRightStrip()
                renderLeftStrip()
            }

            if (!immediate) onAutoDismiss?.invoke(platform)
        }
    }

    // Called by RideSmartService as: dismissAll()
    fun dismissAll() {
        handler.post {
            dismissRunnables.values.forEach { handler.removeCallbacks(it) }
            dismissRunnables.clear()
            activePlatforms.clear()
            featuredPlatform = null
            tearDownViews()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    fun isShowing():                  Boolean = activePlatforms.isNotEmpty()
    fun isPlatformShowing(p: String): Boolean = activePlatforms.containsKey(p)

    private fun signalColor(signal: Signal) = when (signal) {
        Signal.GREEN  -> 0xFF16A34A.toInt()
        Signal.YELLOW -> 0xFFCA8A04.toInt()
        Signal.RED    -> 0xFFDC2626.toInt()
    }

    // Normalises raw payment-type strings from all parsers
    // into a short uppercase badge label.
    private fun normalisePaymentType(raw: String): String {
        val up = raw.trim().uppercase()
        return when {
            up.isBlank()          -> ""
            up.contains("ONLINE") -> "ONLINE"
            up.contains("UPI")    -> "UPI"
            up.contains("COD") -> "COD"
            up.contains("CASH")   -> "CASH"
            up.contains("WALLET") -> "WALLET"
            else                  -> up.take(8)
        }
    }

    private fun buildExtras(result: RideResult): String = buildString {
        if (result.parsedRide.tipAmount > 0.0)
            append("+₹${result.parsedRide.tipAmount.roundToInt()} tip")
        if (result.parsedRide.premiumAmount > 0.0) {
            if (isNotEmpty()) append("\n")
            append("+₹${result.parsedRide.premiumAmount.roundToInt()} prm")
        }
    }

    // Abbreviates failed-check messages to fit the 76dp right strip.
    private fun abbreviateCheck(check: String): String = when {
        check.contains("below your") && check.contains("minimum") -> {
            val profit = check.substringAfter("Profit ").substringBefore(" below")
            "$profit<min"
        }
        check.contains("/km")                            -> "low ₹/km"
        check.contains("/hr") || check.contains("hour") -> "low ₹/hr"
        check.contains("pickup") || check.contains("far") -> "far pkup"
        else                                             -> check.take(10)
    }

    /**
     * Short time-of-day note based on Indian bike-taxi peak/slump patterns.
     * Returns empty string outside notable windows — overlay hides the view.
     */
    private fun timeContextHint(): String {
        val hour = java.util.Calendar.getInstance()
            .get(java.util.Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 7..10  -> "⚡ Peak"
            in 11..12 -> "🟡 Slowing"
            in 13..16 -> "💤 Slump"
            in 17..20 -> "⚡ Evening"
            in 21..23 -> "🌙 Late"
            else      -> ""
        }
    }

    private fun wakeScreen() {
        if (!powerManager.isInteractive) {
            if (wakeLock == null) {
                wakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "RideSmart::OverlayWake"
                )
            }
            if (wakeLock?.isHeld == false) wakeLock?.acquire(10_000L)
        }
    }
}
