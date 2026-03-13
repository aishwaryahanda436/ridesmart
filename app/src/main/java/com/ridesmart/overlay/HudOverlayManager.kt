package com.ridesmart.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.AnimationUtils
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.ridesmart.R
import com.ridesmart.model.PlatformConfig
import com.ridesmart.model.RideResult
import com.ridesmart.model.Signal
import kotlin.math.roundToInt

/**
 * Smart Driver HUD (Heads-Up Display) overlay manager.
 *
 * Displays a compact floating card on the right-center of the screen showing
 * the most profitable ride detected. Designed for instant readability (< 1 second)
 * without blocking ride popups or accept buttons.
 *
 * Key behaviours:
 * - Only the **best** ride is displayed; new better rides replace instantly.
 * - Auto-dismisses after [HUD_DISPLAY_MS] with a fade-out animation.
 * - Slides in from the right edge with a smooth animation.
 * - Vibrates briefly for highly profitable (GREEN) rides.
 * - Uses [FLAG_NOT_TOUCHABLE] so it never intercepts driver taps.
 */
class HudOverlayManager(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    /** Currently displayed HUD view, or null. */
    private var activeView: View? = null

    /** Layout params for the active view. */
    private var activeParams: WindowManager.LayoutParams? = null

    /** Pending auto-dismiss runnable. */
    private var dismissRunnable: Runnable? = null

    /** The best ride currently shown on the HUD. */
    private var currentBestProfit: Double = Double.MIN_VALUE

    /** Callback invoked after the HUD is dismissed. */
    var onDismiss: (() -> Unit)? = null

    companion object {
        private const val TAG = "RideSmart"

        /** HUD remains visible for 3 seconds after ride detection. */
        const val HUD_DISPLAY_MS = 3_000L

        /** Right-side margin so the card doesn't touch the screen edge. */
        private const val RIGHT_MARGIN_DP = 8
    }

    /**
     * Show or update the HUD with a ride result.
     *
     * If the HUD is already visible:
     * - If [result] has higher profit than the current best, the HUD updates in place.
     * - The dismiss timer resets (the timer does NOT restart; it extends from last detection).
     *
     * @param result              The evaluated ride to display.
     * @param totalRidesConsidered Number of rides seen in the current session.
     */
    fun showResult(result: RideResult, totalRidesConsidered: Int = 1) {
        handler.post {
            // Only update if this ride is better than what's currently shown
            if (activeView != null && result.netProfit <= currentBestProfit) {
                // New ride is not better; just extend the dismiss timer
                resetDismissTimer()
                Log.d(TAG, "HUD: lower profit ₹${"%.0f".format(result.netProfit)} " +
                    "vs current ₹${"%.0f".format(currentBestProfit)}, timer extended")
                return@post
            }

            currentBestProfit = result.netProfit

            val isUpdate = activeView != null

            // Remove existing HUD if present (no animation — instant swap)
            removeActiveView()

            // Inflate new HUD card
            val view = LayoutInflater.from(context)
                .inflate(R.layout.overlay_hud, null)

            bindHud(view, result, totalRidesConsidered)

            val params = buildParams()

            windowManager.addView(view, params)
            activeView = view
            activeParams = params

            // Slide-in animation (only for fresh appearance, not updates)
            if (!isUpdate) {
                val slideIn = AnimationUtils.loadAnimation(context, R.anim.hud_slide_in_right)
                view.startAnimation(slideIn)
            }

            // Vibrate briefly for highly profitable rides
            if (result.signal == Signal.GREEN) {
                vibrateShort()
            }

            Log.d(TAG, "HUD: showing ₹${"%.0f".format(result.netProfit)} " +
                "${PlatformConfig.get(result.parsedRide.packageName).displayName} " +
                "signal=${result.signal} rides=$totalRidesConsidered")

            // Schedule auto-dismiss
            resetDismissTimer()
        }
    }

    /**
     * Immediately dismiss the HUD and reset state.
     * Called from [RideSmartService.onDestroy].
     */
    fun dismiss() {
        handler.post {
            cancelDismissTimer()
            removeActiveView()
            currentBestProfit = Double.MIN_VALUE
        }
    }

    /** Whether a HUD card is currently visible. */
    fun isVisible(): Boolean = activeView != null

    /** Reset the best-ride tracker so the next ride always shows. */
    fun resetBest() {
        currentBestProfit = Double.MIN_VALUE
    }

    // ── PRIVATE ─────────────────────────────────────────────────────────

    private fun bindHud(view: View, result: RideResult, totalRidesConsidered: Int) {
        val parsedRide = result.parsedRide
        val platform = PlatformConfig.get(parsedRide.packageName)

        // Signal-based background and colors
        val (signalEmoji, signalLabel, bgDrawable, signalColor) = when (result.signal) {
            Signal.GREEN  -> SignalInfo("🟢", "TAKE IT",  R.drawable.hud_card_green,  ContextCompat.getColor(context, R.color.signal_green))
            Signal.YELLOW -> SignalInfo("🟡", "OK",       R.drawable.hud_card_yellow, ContextCompat.getColor(context, R.color.signal_yellow))
            Signal.RED    -> SignalInfo("🔴", "SKIP",     R.drawable.hud_card_red,    ContextCompat.getColor(context, R.color.signal_red))
        }

        // Card background
        view.findViewById<LinearLayout>(R.id.hud_root)
            .setBackgroundResource(bgDrawable)

        // "BEST OF X RIDES" label — only shown when multiple rides detected
        val bestOfView = view.findViewById<TextView>(R.id.hud_best_of)
        if (totalRidesConsidered > 1) {
            bestOfView.text = "BEST OF $totalRidesConsidered RIDES"
            bestOfView.visibility = View.VISIBLE
        } else {
            bestOfView.visibility = View.GONE
        }

        // Row 1: Fare | Distance  (e.g. "₹180 | 6 km")
        val fareStr = "₹${parsedRide.baseFare.roundToInt()}"
        val distStr = "${"%.1f".format(parsedRide.rideDistanceKm)} km"
        view.findViewById<TextView>(R.id.hud_fare_distance).text = "$fareStr | $distStr"

        // Row 2: Profit  (e.g. "Profit ₹85")
        val tvProfit = view.findViewById<TextView>(R.id.hud_profit)
        tvProfit.text = "Profit ₹${"%.0f".format(result.netProfit)}"
        tvProfit.setTextColor(signalColor)

        // Row 3: ₹/km · Platform  (e.g. "₹14/km · Uber")
        view.findViewById<TextView>(R.id.hud_per_km_platform).text =
            "₹${"%.1f".format(result.earningPerKm)}/km · ${platform.displayName}"

        // Signal recommendation — large, visually dominant
        val tvSignal = view.findViewById<TextView>(R.id.hud_signal)
        tvSignal.text = "$signalEmoji $signalLabel"
        tvSignal.setTextColor(signalColor)
    }

    private fun buildParams(): WindowManager.LayoutParams {
        val rightMarginPx = (RIGHT_MARGIN_DP * context.resources.displayMetrics.density).toInt()

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            x = rightMarginPx
            y = 0
        }
    }

    private fun resetDismissTimer() {
        cancelDismissTimer()
        val runnable = Runnable { dismissWithAnimation() }
        dismissRunnable = runnable
        handler.postDelayed(runnable, HUD_DISPLAY_MS)
    }

    private fun cancelDismissTimer() {
        dismissRunnable?.let { handler.removeCallbacks(it) }
        dismissRunnable = null
    }

    private fun dismissWithAnimation() {
        val view = activeView ?: return
        val fadeOut = AnimationUtils.loadAnimation(context, R.anim.hud_fade_out)
        fadeOut.setAnimationListener(object : android.view.animation.Animation.AnimationListener {
            override fun onAnimationStart(animation: android.view.animation.Animation?) {}
            override fun onAnimationRepeat(animation: android.view.animation.Animation?) {}
            override fun onAnimationEnd(animation: android.view.animation.Animation?) {
                handler.post {
                    removeActiveView()
                    currentBestProfit = Double.MIN_VALUE
                    Log.d(TAG, "HUD: auto-dismissed")
                    onDismiss?.invoke()
                }
            }
        })
        view.startAnimation(fadeOut)
    }

    private fun removeActiveView() {
        activeView?.let {
            it.clearAnimation()
            try { windowManager.removeView(it) } catch (_: Exception) { }
        }
        activeView = null
        activeParams = null
    }

    @Suppress("DEPRECATION")
    private fun vibrateShort() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val mgr = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                mgr?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(
                        VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE)
                    )
                } else {
                    vibrator?.vibrate(80)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "HUD vibration failed: ${e.message}")
        }
    }

    /** Helper data class for signal information destructuring. */
    private data class SignalInfo(
        val emoji: String,
        val label: String,
        val backgroundRes: Int,
        val color: Int
    )
}
