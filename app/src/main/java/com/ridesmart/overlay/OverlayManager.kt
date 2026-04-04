package com.ridesmart.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
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
import com.ridesmart.engine.CrossPlatformRanker
import com.ridesmart.model.RideResult
import com.ridesmart.model.Signal
import com.ridesmart.model.VehicleType
import kotlin.math.roundToInt

/**
 * Manages two permanent side strips that show ride data without ever
 * blocking the center of the screen.
 */
class OverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val powerManager  = context.getSystemService(Context.POWER_SERVICE)  as PowerManager
    private val handler       = Handler(Looper.getMainLooper())

    private val activePlatforms = LinkedHashMap<String, RideResult>()
    private var featuredPlatform: String? = null

    private var rightStripView:   View? = null
    private var rightStripParams: WindowManager.LayoutParams? = null

    private var leftStripView:   View? = null
    private var leftStripParams: WindowManager.LayoutParams? = null

    private val dismissRunnables = mutableMapOf<String, Runnable>()
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        private const val TAG             = "RideSmart"
        private const val AUTO_DISMISS_MS = 12_000L
        private const val RIGHT_STRIP_DP  = 76
        private const val LEFT_STRIP_DP   = 68
        private const val VERTICAL_ANCHOR = 0.38
    }

    var onAutoDismiss: ((String) -> Unit)? = null

    fun showResult(result: RideResult) {
        val platform = result.parsedRide.platform
        handler.post {
            wakeScreen()
            dismissRunnables[platform]?.let { handler.removeCallbacks(it) }
            activePlatforms[platform] = result
            ensureViews()
            renderRightStrip()
            renderLeftStrip()

            val runnable = Runnable {
                dismissPlatform(platform)
                onAutoDismiss?.invoke(platform)
            }
            dismissRunnables[platform] = runnable
            handler.postDelayed(runnable, AUTO_DISMISS_MS)
        }
    }

    private fun ensureViews() {
        val dm      = context.resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val yPos    = (screenH * VERTICAL_ANCHOR).toInt()

        val rightStripPx = (RIGHT_STRIP_DP * dm.density).toInt()
        val leftStripPx  = (LEFT_STRIP_DP  * dm.density).toInt()

        if (rightStripView == null) {
            val view = LayoutInflater.from(context).inflate(R.layout.overlay_right_strip, null)
            val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH

            val finalFlags = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
                flags or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            } else flags

            val params = WindowManager.LayoutParams(
                rightStripPx,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                finalFlags,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = screenW - rightStripPx
                y = yPos
            }
            try {
                windowManager.addView(view, params)
                rightStripView   = view
                rightStripParams = params
            } catch (e: Exception) { Log.e(TAG, "addView rightStrip: ${e.message}") }
        }

        if (leftStripView == null) {
            val view = LayoutInflater.from(context).inflate(R.layout.overlay_left_strip, null)
            val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH

            val finalFlags = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
                flags or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            } else flags

            val params = WindowManager.LayoutParams(
                leftStripPx,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                finalFlags,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = yPos
            }
            try {
                windowManager.addView(view, params)
                leftStripView   = view
                leftStripParams = params
            } catch (e: Exception) { Log.e(TAG, "addView leftStrip: ${e.message}") }
        }
    }

    private fun tearDownViews() {
        rightStripView?.let { try { windowManager.removeView(it) } catch (_: Exception) { } }
        rightStripView   = null
        rightStripParams = null
        leftStripView?.let { try { windowManager.removeView(it) } catch (_: Exception) { } }
        leftStripView   = null
        leftStripParams = null
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) { }
    }

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
        } else tvTimeCtx.visibility = View.GONE

        view.findViewById<TextView>(R.id.rs_net).text = "₹${result.netProfit.roundToInt()}"
        view.findViewById<TextView>(R.id.rs_fare).text = "₹${result.totalFare.roundToInt()}"

        val tvExtras = view.findViewById<TextView>(R.id.rs_extras)
        val extras   = buildExtras(result)
        if (extras.isNotEmpty()) {
            tvExtras.text       = extras
            tvExtras.visibility = View.VISIBLE
        } else tvExtras.visibility = View.GONE

        view.findViewById<TextView>(R.id.rs_platform).text = result.parsedRide.platform.uppercase()

        val tvPassTag = view.findViewById<TextView>(R.id.rs_pass_tag)
        tvPassTag.visibility = if (result.parsedRide.packageName == "com.rapido.rider") View.VISIBLE else View.GONE

        val tvVehicle   = view.findViewById<TextView>(R.id.rs_vehicle)
        if (result.parsedRide.vehicleType != VehicleType.UNKNOWN) {
            tvVehicle.text       = result.parsedRide.vehicleType.displayName.uppercase()
            tvVehicle.visibility = View.VISIBLE
        } else tvVehicle.visibility = View.GONE

        view.findViewById<TextView>(R.id.rs_per_km).text = "₹${"%.1f".format(result.efficiencyPerKm)}/km"

        val tvPerHour = view.findViewById<TextView>(R.id.rs_per_hour)
        if (result.earningPerHour > 0) {
            tvPerHour.text       = "₹${result.earningPerHour.roundToInt()}/hr"
            tvPerHour.visibility = View.VISIBLE
        } else tvPerHour.visibility = View.GONE

        val pickupKm = result.parsedRide.pickupDistanceKm
        val pickupColor = when {
            pickupKm <= 0.8 -> 0xFF16A34A.toInt()
            pickupKm <= 1.8 -> 0xFFCA8A04.toInt()
            else            -> 0xFFDC2626.toInt()
        }
        val tvPickup = view.findViewById<TextView>(R.id.rs_pickup)
        tvPickup.text = "${"%.1f".format(pickupKm)}km"
        tvPickup.setTextColor(pickupColor)

        val tvPickupPct = view.findViewById<TextView>(R.id.rs_pickup_pct)
        tvPickupPct.text = "${(result.pickupRatio * 100).roundToInt()}% pkup"
        tvPickupPct.setTextColor(pickupColor)

        val tvPayment    = view.findViewById<TextView>(R.id.rs_payment)
        val paymentLabel = normalisePaymentType(result.parsedRide.paymentType)
        if (paymentLabel.isNotBlank()) {
            tvPayment.text       = paymentLabel
            tvPayment.visibility = View.VISIBLE
        } else tvPayment.visibility = View.GONE

        view.findViewById<TextView>(R.id.rs_fuel).text = "fuel ₹${"%.0f".format(result.fuelCost)}"
        view.findViewById<TextView>(R.id.rs_wear).text = "wear ₹${"%.0f".format(result.wearCost)}"

        val tvScore = view.findViewById<TextView>(R.id.rs_score)
        if (result.decisionScore > 0.0) {
            val scoreInt   = result.decisionScore.toInt().coerceIn(0, 100)
            val scoreColor = when {
                scoreInt >= 75 -> 0xFF16A34A.toInt()
                scoreInt >= 45 -> 0xFFCA8A04.toInt()
                else           -> 0xFFDC2626.toInt()
            }
            tvScore.text       = "S:$scoreInt"
            tvScore.setTextColor(scoreColor)
            tvScore.visibility = View.VISIBLE
        } else tvScore.visibility = View.GONE

        val tvBestNote = view.findViewById<TextView?>(R.id.rs_best_note)
        if (tvBestNote != null) {
            val note = result.bestSeenNote
            tvBestNote.text       = note ?: ""
            tvBestNote.visibility = if (note != null) View.VISIBLE else View.GONE
        }

        val tvToday = view.findViewById<TextView>(R.id.rs_today)
        if (result.dailyTargetAmount > 0.0) {
            tvToday.text = "₹${result.todayEarnings.roundToInt()} / ₹${result.dailyTargetAmount.roundToInt()}"
            tvToday.visibility = View.VISIBLE
        } else tvToday.visibility = View.GONE

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

    private fun renderLeftStrip() {
        val view = leftStripView ?: return
        if (activePlatforms.size < 2) {
            view.visibility = View.GONE
            return
        }
        view.visibility = View.VISIBLE
        val container      = view.findViewById<LinearLayout>(R.id.ls_cards_container)
        val featuredResult = getFeaturedResult()
        val inflater       = LayoutInflater.from(context)
        container.removeAllViews()

        val ranking = CrossPlatformRanker.rank(activePlatforms.values.toList())
        ranking.ranked.forEachIndexed { index, result ->
            val cardView = inflater.inflate(R.layout.overlay_left_card, container, false)
            bindLeftCard(
                view       = cardView,
                result     = result,
                rank       = index + 1,
                isFeatured = (result == featuredResult)
            )

            if (index == 0 && activePlatforms.size >= 2) {
                cardView.findViewById<TextView>(R.id.lc_rank_platform).text = ranking.summaryLine
                if (ranking.runnerUp != null) {
                    val tvNet    = cardView.findViewById<TextView>(R.id.lc_net)
                    val deltaStr = if (ranking.profitDelta >= 0)
                        "+₹${ranking.profitDelta.toInt()}"
                        else "-₹${(-ranking.profitDelta).toInt()}"
                    tvNet.text = "₹${result.netProfit.roundToInt()} ($deltaStr vs ${ranking.runnerUp!!.parsedRide.platform})"
                }
            }

            cardView.setOnClickListener {
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
        val score = result.decisionScore.toInt().coerceIn(0, 100)
        
        val tvRankLabel = view.findViewById<TextView>(R.id.lc_rank_platform)
        if (rank == 1 && isFeatured) {
            // Will be set by caller via ranking.summaryLine — keep default for now
            tvRankLabel.text = "#$rank $platformAbbr S:$score"
        } else {
            tvRankLabel.text = "#$rank $platformAbbr S:$score"
        }

        val tvNet = view.findViewById<TextView>(R.id.lc_net)
        tvNet.text = "₹${result.netProfit.roundToInt()}"
        tvNet.setTextColor(if (isFeatured) accentColor else 0xFFFFFFFF.toInt())

        view.findViewById<TextView>(R.id.lc_ride_km).text = "${"%.1f".format(result.parsedRide.rideDistanceKm)}k"
        view.findViewById<TextView>(R.id.lc_selected_label).visibility = if (isFeatured) View.VISIBLE else View.GONE
    }

    fun dismissPlatform(platform: String, immediate: Boolean = false) {
        handler.post {
            dismissRunnables[platform]?.let { handler.removeCallbacks(it) }
            dismissRunnables.remove(platform)
            activePlatforms.remove(platform)
            if (featuredPlatform == platform) featuredPlatform = null
            if (activePlatforms.isEmpty()) tearDownViews()
            else { renderRightStrip(); renderLeftStrip() }
            if (!immediate) onAutoDismiss?.invoke(platform)
        }
    }

    fun dismissAll() {
        handler.post {
            dismissRunnables.values.forEach { handler.removeCallbacks(it) }
            dismissRunnables.clear()
            activePlatforms.clear()
            featuredPlatform = null
            tearDownViews()
        }
    }

    fun isShowing():                  Boolean = activePlatforms.isNotEmpty()
    fun isPlatformShowing(p: String): Boolean = activePlatforms.containsKey(p)

    private fun signalColor(signal: Signal) = when (signal) {
        Signal.GREEN  -> 0xFF16A34A.toInt()
        Signal.YELLOW -> 0xFFCA8A04.toInt()
        Signal.RED    -> 0xFFDC2626.toInt()
    }

    private fun normalisePaymentType(raw: String): String {
        val up = raw.trim().uppercase()
        return when {
            up.isBlank()          -> ""
            up.contains("ONLINE") -> "ONLINE"
            up.contains("UPI")    -> "UPI"
            up.contains("COD")    || up.contains("CASH")   -> "CASH"
            up.contains("WALLET") -> "WALLET"
            else                  -> up.take(8)
        }
    }

    private fun buildExtras(result: RideResult): String = buildString {
        if (result.parsedRide.tipAmount > 0.0) append("+₹${result.parsedRide.tipAmount.roundToInt()} tip")
        if (result.parsedRide.premiumAmount > 0.0) {
            if (isNotEmpty()) append("\n")
            append("+₹${result.parsedRide.premiumAmount.roundToInt()} prm")
        }
    }

    private fun abbreviateCheck(check: String): String = when {
        check.startsWith("Profit") || check.startsWith("Losing") -> check.substringBefore("—").trim().take(12)
        check.contains("/km")                             -> "low ₹/km"
        check.contains("/hr") || check.contains("hour")  -> "low ₹/hr"
        check.contains("pickup") || check.contains("far")  -> "far pkup"
        else                                              -> check.take(12)
    }

    private fun timeContextHint(): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
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
        if (powerManager.isInteractive) return
        try {
            val level = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP
            } else {
                @Suppress("DEPRECATION")
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP
            }
            if (wakeLock == null) wakeLock = powerManager.newWakeLock(level, "RideSmart::OverlayWake")
            if (wakeLock?.isHeld == false) wakeLock?.acquire(10_000L)
        } catch (e: Exception) { Log.e(TAG, "wakeScreen failed: ${e.message}") }
    }
}
