package com.ridesmart.parser

import android.util.Log
import com.ridesmart.model.ParsedRide
import com.ridesmart.model.ScreenState
import com.ridesmart.model.VehicleType

/**
 * Dedicated parser for Shadowfax Driver app ride/delivery requests.
 *
 * Shadowfax is a logistics and last-mile delivery platform.
 * Delivery requests show:
 *  - Delivery fee (₹)
 *  - Pickup and drop distance
 *  - Weight / package info (sometimes)
 *  - Estimated duration
 *
 * Shadowfax accessibility nodes are generally readable without OCR.
 */
class ShadowfaxParser : IPlatformParser {

    companion object {
        private const val TAG = "RideSmart"

        private val FARE_REGEX = Regex("""₹\s*(\d+(?:\.\d{1,2})?)""")
        private val KM_REGEX = Regex("""(\d+(?:\.\d{1,2})?)\s*km""", RegexOption.IGNORE_CASE)
        private val MIN_REGEX = Regex("""(\d+)\s*min""", RegexOption.IGNORE_CASE)
        private val BONUS_REGEX = Regex("""\+₹\s*(\d+(?:\.\d{1,2})?)""")

        private val ACTIVE_DELIVERY_KEYWORDS = listOf(
            "picked up", "on the way", "delivering", "reached drop",
            "complete delivery", "mark delivered", "drop otp"
        )

        private val IDLE_KEYWORDS = listOf(
            "no orders", "you are offline", "go online",
            "earnings", "my deliveries", "order history"
        )
    }

    override fun detectScreenState(nodes: List<String>): ScreenState {
        val combined = nodes.joinToString(" ").lowercase()

        if (ACTIVE_DELIVERY_KEYWORDS.any { combined.contains(it) }) {
            Log.d(TAG, "🚫 Shadowfax: ACTIVE_DELIVERY detected — suppressing overlay")
            return ScreenState.ACTIVE_RIDE
        }

        if (IDLE_KEYWORDS.any { combined.contains(it) }) return ScreenState.IDLE

        val hasAccept = nodes.any {
            it.equals("Accept", ignoreCase = true) ||
            it.equals("Confirm", ignoreCase = true) ||
            it.contains("accept order", ignoreCase = true)
        }
        val hasFare = nodes.any { FARE_REGEX.containsMatchIn(it) }
        val hasKm = nodes.any { KM_REGEX.containsMatchIn(it) }

        return when {
            !hasFare && !hasAccept -> ScreenState.IDLE
            hasFare && hasAccept && !hasKm -> ScreenState.OFFER_LOADING
            hasFare && hasAccept && hasKm -> ScreenState.OFFER_LOADED
            hasFare && hasKm -> ScreenState.OFFER_LOADED
            else -> ScreenState.IDLE
        }
    }

    override fun parseAll(nodes: List<String>, packageName: String): List<ParsedRide> {
        val activeNodes = nodes.filter { it.isNotBlank() }
        val screenState = detectScreenState(activeNodes)

        if (screenState == ScreenState.ACTIVE_RIDE || screenState == ScreenState.IDLE) {
            return emptyList()
        }

        val ride = parse(activeNodes, packageName) ?: return emptyList()
        return listOf(ride)
    }

    fun parse(nodes: List<String>, packageName: String): ParsedRide? {
        val activeNodes = nodes.filter { it.isNotBlank() }
        if (activeNodes.isEmpty()) return null

        val screenState = detectScreenState(activeNodes)
        if (screenState == ScreenState.ACTIVE_RIDE || screenState == ScreenState.IDLE) return null

        // ── EXTRACT FARE ────────────────────────────────────────────────
        val allFares = activeNodes.mapNotNull { node ->
            if (node.contains("km", ignoreCase = true) || node.startsWith("+")) null
            else FARE_REGEX.find(node)?.groupValues?.get(1)?.toDoubleOrNull()
        }.filter { it >= 5.0 }

        val baseFare = allFares.firstOrNull() ?: return null

        // ── EXTRACT BONUS ───────────────────────────────────────────────
        var bonusAmount = 0.0
        activeNodes.forEach { node ->
            val match = BONUS_REGEX.find(node)
            if (match != null) {
                bonusAmount += match.groupValues[1].toDoubleOrNull() ?: 0.0
            }
        }

        // ── EXTRACT DISTANCES ───────────────────────────────────────────
        val kmMatches = activeNodes.mapNotNull { node ->
            KM_REGEX.find(node)?.groupValues?.get(1)?.toDoubleOrNull()
        }

        var pickupDistanceKm = 0.0
        var rideDistanceKm = 0.0

        when (kmMatches.size) {
            1 -> rideDistanceKm = kmMatches[0]
            2 -> {
                pickupDistanceKm = kmMatches[0]
                rideDistanceKm = kmMatches[1]
            }
            else -> if (kmMatches.isNotEmpty()) {
                pickupDistanceKm = kmMatches[0]
                rideDistanceKm = kmMatches.last()
            }
        }

        // ── EXTRACT DURATION ────────────────────────────────────────────
        val durationMin = activeNodes.mapNotNull { node ->
            MIN_REGEX.find(node)?.groupValues?.get(1)?.toIntOrNull()
        }.firstOrNull() ?: 0

        // ── VEHICLE TYPE ────────────────────────────────────────────────
        // Shadowfax is primarily a bike delivery platform
        val fullText = activeNodes.joinToString(" ")
        val vehicleType = when {
            fullText.contains("Car", ignoreCase = true) ||
            fullText.contains("Van", ignoreCase = true) -> VehicleType.CAR
            fullText.contains("Auto", ignoreCase = true) -> VehicleType.AUTO
            else -> VehicleType.BIKE
        }

        // ── EXTRACT ADDRESSES ───────────────────────────────────────────
        val addressCandidates = activeNodes.filter { node ->
            node.length > 12 &&
            !KM_REGEX.containsMatchIn(node) &&
            !MIN_REGEX.containsMatchIn(node) &&
            !FARE_REGEX.containsMatchIn(node) &&
            !node.equals("Accept", ignoreCase = true) &&
            !node.equals("Confirm", ignoreCase = true) &&
            !node.startsWith("+")
        }
        val pickupAddress = addressCandidates.getOrElse(0) { "" }
        val dropAddress = addressCandidates.getOrElse(1) { "" }

        // ── EXTRACT PAYMENT TYPE ────────────────────────────────────────
        val paymentKeywords = listOf("cash", "upi", "online", "wallet", "card", "cod", "prepaid")
        val paymentType = activeNodes.firstOrNull { node ->
            paymentKeywords.any { node.contains(it, ignoreCase = true) }
        } ?: ""

        Log.d(TAG, "🔍 Shadowfax parsed: vehicle=$vehicleType fare=₹$baseFare " +
            "bonus=₹$bonusAmount pickup=${pickupDistanceKm}km ride=${rideDistanceKm}km " +
            "state=$screenState")

        return ParsedRide(
            baseFare = baseFare,
            premiumAmount = bonusAmount,
            rideDistanceKm = rideDistanceKm,
            pickupDistanceKm = pickupDistanceKm,
            estimatedDurationMin = durationMin,
            platform = packageName,
            packageName = packageName,
            rawTextNodes = activeNodes,
            pickupAddress = pickupAddress,
            dropAddress = dropAddress,
            paymentType = paymentType,
            vehicleType = vehicleType,
            screenState = screenState,
            bonus = bonusAmount,
            fare = baseFare + bonusAmount
        )
    }
}
