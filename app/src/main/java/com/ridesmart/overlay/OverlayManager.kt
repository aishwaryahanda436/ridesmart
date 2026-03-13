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
import com.ridesmart.model.PlatformConfig
import com.ridesmart.model.RideResult
import com.ridesmart.model.Signal
import kotlin.math.roundToInt

class OverlayManager(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val powerManager =
        context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val handler = Handler(Looper.getMainLooper())

    // One slot per platform — keyed by platform name e.g. "Rapido", "Uber"
    private val activeCards   = mutableMapOf<String, View>()
    private val dismissTimers = mutableMapOf<String, Runnable>()
    private val activeAnimators = mutableMapOf<String, android.animation.ObjectAnimator>()

    private val AUTO_DISMISS_MS = 12_000L
    private val TAG = "RideSmart"

    var onDismiss: ((String) -> Unit)? = null

    fun showResult(
        result: RideResult,
        totalRidesConsidered: Int = 1,
        isBestSoFar: Boolean = true,
        bestSeenFare: Double = 0.0,
        bestSeenNetProfit: Double = 0.0
    ) {
        handler.post {
            val platform = result.parsedRide.platform
            val packageName = result.parsedRide.packageName

            // Cancel any pending dismiss for this platform
            dismissTimers[platform]?.let { handler.removeCallbacks(it) }
            dismissTimers.remove(platform)

            // Cancel existing animator for this platform
            activeAnimators[platform]?.cancel()
            activeAnimators.remove(platform)

            // Remove existing card for THIS platform only — other platforms unaffected
            activeCards[platform]?.let {
                try { windowManager.removeView(it) } catch (_: Exception) { }
            }
            activeCards.remove(platform)

            // Wake screen
            wakeScreen()

            // Inflate card
            val view = LayoutInflater.from(context)
                .inflate(R.layout.overlay_ride_result, null)
            bindResult(view, result, totalRidesConsidered, isBestSoFar, bestSeenFare, bestSeenNetProfit)

            // Position depends on platform
            val params = buildParams(packageName)

            // Tap to dismiss THIS card only
            view.setOnClickListener { dismissPlatform(platform) }

            windowManager.addView(view, params)

            activeCards[platform] = view

            Log.d(TAG, "📐 Overlay added: platform=$platform " +
                "gravity=${if (PlatformConfig.get(packageName).displayName.equals("Uber", ignoreCase = true)) "TOP" else "BOTTOM"} " +
                "active=${activeCards.keys}")

            // Auto-dismiss after 12 seconds — only this platform's card
            val cardToRemove = view

            // Animate countdown progress bar from 120 → 0 over 12 seconds
            val progress = view.findViewById<android.widget.ProgressBar>(R.id.dismiss_progress)
            val animator = android.animation.ObjectAnimator.ofInt(progress, "progress", 120, 0).apply {
                duration = AUTO_DISMISS_MS
                interpolator = android.view.animation.LinearInterpolator()
                start()
            }
            activeAnimators[platform] = animator

            val runnable = Runnable {
                try { windowManager.removeView(cardToRemove) } catch (_: Exception) { }
                if (activeCards[platform] === cardToRemove) {
                    activeCards.remove(platform)
                    activeAnimators[platform]?.cancel()
                    activeAnimators.remove(platform)
                }
                dismissTimers.remove(platform)
                Log.d(TAG, "⏱ Auto-dismissed: platform=$platform remaining=${activeCards.keys}")
                onDismiss?.invoke(platform)
            }
            dismissTimers[platform] = runnable
            handler.postDelayed(runnable, AUTO_DISMISS_MS)
        }
    }

    private fun buildParams(packageName: String): WindowManager.LayoutParams {
        val gravity = if (PlatformConfig.get(packageName).displayName
                .equals("Uber", ignoreCase = true)) {
            Gravity.TOP
        } else {
            Gravity.BOTTOM
        }

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            this.gravity = gravity
            y = 0
        }
    }

    private fun bindResult(
        view: View,
        result: RideResult,
        totalRidesConsidered: Int,
        isBestSoFar: Boolean,
        bestSeenFare: Double,
        bestSeenNetProfit: Double
    ) {
        val parsedRide = result.parsedRide

        val (_, accentColor, bodyBgColor) = when (result.signal) {
            Signal.GREEN  -> Triple("ACCEPT",    0xFF16A34A.toInt(), 0xFF060F08.toInt())
            Signal.YELLOW -> Triple("BORDERLINE", 0xFFCA8A04.toInt(), 0xFF100C00.toInt())
            Signal.RED    -> Triple("SKIP",       0xFFDC2626.toInt(), 0xFF0F0303.toInt())
        }

        // Background and accent bar
        view.findViewById<LinearLayout>(R.id.overlay_root)
            .setBackgroundColor(bodyBgColor)
        view.findViewById<View>(R.id.accent_bar)
            .setBackgroundColor(accentColor)

        // Signal text logic
        val tvSignal = view.findViewById<TextView>(R.id.tv_signal)
        val cardContext = if (totalRidesConsidered > 1) {
            " · CARD $totalRidesConsidered SEEN"
        } else ""

        val signalText = when (result.signal) {
            Signal.GREEN -> if (isBestSoFar)
                "✅ ACCEPT — BEST SO FAR$cardContext"
            else
                "✅ ACCEPT — (better offer seen earlier)$cardContext"
            Signal.YELLOW -> if (isBestSoFar)
                "🟡 BORDERLINE — BEST SO FAR$cardContext"
            else
                "🟡 BORDERLINE — better offer seen earlier$cardContext"
            Signal.RED -> "❌ SKIP$cardContext"
        }
        tvSignal.text = signalText
        tvSignal.setTextColor(accentColor)

        // Fare — show arrow if commission applies
        val platform = PlatformConfig.get(parsedRide.packageName)
        val effective = PlatformConfig.effectivePayout(parsedRide.baseFare, parsedRide.packageName)
        view.findViewById<TextView>(R.id.tv_fare).text =
            if (platform.commissionPercent > 0)
                "₹${parsedRide.baseFare.roundToInt()}→${effective.roundToInt()}"
            else
                "₹${parsedRide.baseFare.roundToInt()}"

        // Platform + distance or Best seen info
        val tvDetail = view.findViewById<TextView>(R.id.tv_detail)
        if (!isBestSoFar && bestSeenFare > 0.0) {
            tvDetail.text = "⭐ Best: ₹${bestSeenFare.toInt()} = ₹${bestSeenNetProfit.toInt()} net"
        } else {
            tvDetail.text = "${platform.displayName} · ${"%.1f".format(parsedRide.rideDistanceKm)}km"
        }

        // Net profit — color matches signal
        view.findViewById<TextView>(R.id.tv_net_profit).apply {
            text = "₹${"%.0f".format(result.netProfit)}"
            setTextColor(accentColor)
        }

        // ₹/km
        view.findViewById<TextView>(R.id.tv_per_km).text =
            "₹${"%.1f".format(result.earningPerKm)}"

        // Pickup — color based on distance
        val pickupRatioPct = (result.pickupRatio * 100).roundToInt()
        val pickupColor = when {
            pickupRatioPct <= 8  -> 0xFF3DDC84.toInt()
            pickupRatioPct <= 18 -> 0xFFF9AB00.toInt()
            else                 -> 0xFFDC2626.toInt()
        }
        view.findViewById<TextView>(R.id.tv_pickup_ratio).apply {
            text = "${"%.1f".format(parsedRide.pickupDistanceKm)}km ($pickupRatioPct%)"
            setTextColor(pickupColor)
        }

        // ₹/hr — hide row if no duration data
        val rowPerHour = view.findViewById<View>(R.id.row_per_hour)
        if (result.earningPerHour > 0) {
            view.findViewById<TextView>(R.id.tv_per_hour).text =
                "₹${"%.0f".format(result.earningPerHour)}"
            rowPerHour.visibility = View.VISIBLE
        } else {
            rowPerHour.visibility = View.GONE
        }
    }

    @Suppress("DEPRECATION")
    private val wakeLock: PowerManager.WakeLock by lazy {
        powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
            PowerManager.ACQUIRE_CAUSES_WAKEUP or
            PowerManager.ON_AFTER_RELEASE,
            "RideSmart::OverlayWake"
        ).apply { setReferenceCounted(false) }
    }

    private fun wakeScreen() {
        if (!powerManager.isInteractive) {
            wakeLock.acquire(15_000L)
        }
    }

    private fun dismissPlatform(platform: String) {
        handler.post {
            dismissTimers[platform]?.let { handler.removeCallbacks(it) }
            dismissTimers.remove(platform)
            activeAnimators[platform]?.cancel()
            activeAnimators.remove(platform)
            activeCards[platform]?.let {
                try { windowManager.removeView(it) } catch (_: Exception) { }
            }
            activeCards.remove(platform)
            Log.d(TAG, "👆 Dismissed by tap: platform=$platform remaining=${activeCards.keys}")
            onDismiss?.invoke(platform)
        }
    }

    // Called from onDestroy — removes ALL active cards cleanly
    fun dismiss() {
        handler.post {
            dismissTimers.values.forEach { handler.removeCallbacks(it) }
            dismissTimers.clear()
            activeAnimators.values.forEach { it.cancel() }
            activeAnimators.clear()
            activeCards.values.forEach {
                try { windowManager.removeView(it) } catch (_: Exception) { }
            }
            activeCards.clear()
        }
    }

    fun hasActiveOverlays(): Boolean = activeCards.isNotEmpty()

    fun hideAllTemporarily() {
        handler.post {
            activeCards.values.forEach { it.visibility = View.INVISIBLE }
        }
    }

    fun restoreAll() {
        handler.post {
            activeCards.values.forEach { it.visibility = View.VISIBLE }
        }
    }
}
