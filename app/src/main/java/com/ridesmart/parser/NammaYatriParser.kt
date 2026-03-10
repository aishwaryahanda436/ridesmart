package com.ridesmart.parser

import android.util.Log
import com.ridesmart.model.ParsedRide
import com.ridesmart.model.ScreenState
import com.ridesmart.model.VehicleType

/**
 * Dedicated parser for NammaYatri (JUSPAY) ride requests.
 *
 * NammaYatri is an open-source ride-hailing app for India (Bangalore, Kochi, etc.).
 * Supported packages:
 *  - in.juspay.nammayatri
 *  - net.openkochi.yatri
 *  - in.juspay.nammayatripartner
 *
 * Accessibility nodes are generally readable without OCR.
 * Ride offers show:
 *  - Fare in ₹ (driver payout — NammaYatri uses subscription, no per-ride commission)
 *  - Pickup distance in km
 *  - Ride distance in km
 *  - Duration in minutes
 *  - Vehicle type (Auto, Bike, Cab)
 *  - Pickup and drop addresses
 */
class NammaYatriParser : IPlatformParser {

    companion object {
        private const val TAG = "RideSmart"

        private val FARE_REGEX  = Regex("""₹\s*(\d+(?:\.\d{1,2})?)""")
        private val KM_REGEX    = Regex("""(\d+(?:\.\d{1,2})?)\s*km""", RegexOption.IGNORE_CASE)
        private val MIN_REGEX   = Regex("""(\d+)\s*min""", RegexOption.IGNORE_CASE)
        private val BONUS_REGEX = Regex("""\+₹\s*(\d+(?:\.\d{1,2})?)""")

        private val ACTIVE_RIDE_KEYWORDS = listOf(
            "start ride", "end ride", "trip started", "arrived at pickup",
            "reached pickup", "drop otp", "you are on a trip", "trip ongoing"
        )

        private val IDLE_KEYWORDS = listOf(
            "you are offline", "go online", "no rides available",
            "earnings today", "my rides", "ride history", "you're offline"
        )

        private val OFFER_KEYWORDS = listOf(
            "new ride request", "new request", "accept", "decline",
            "confirm", "ride request"
        )
    }

    override fun detectScreenState(nodes: List<String>): ScreenState {
        val combined = nodes.joinToString(" ").lowercase()

        if (ACTIVE_RIDE_KEYWORDS.any { combined.contains(it) }) {
            Log.d(TAG, "🚫 NammaYatri: ACTIVE_RIDE detected — suppressing overlay")
            return ScreenState.ACTIVE_RIDE
        }

        if (IDLE_KEYWORDS.any { combined.contains(it) }) return ScreenState.IDLE

        val hasAccept = nodes.any {
            it.equals("Accept", ignoreCase = true) ||
            it.equals("Confirm", ignoreCase = true) ||
            it.contains("accept ride", ignoreCase = true)
        }
        val hasFare          = nodes.any { FARE_REGEX.containsMatchIn(it) }
        val hasKm            = nodes.any { KM_REGEX.containsMatchIn(it) }
        val hasOfferKeyword  = OFFER_KEYWORDS.any { combined.contains(it) }

        return when {
            !hasFare && !hasAccept && !hasOfferKeyword -> ScreenState.IDLE
            hasFare && (hasAccept || hasOfferKeyword) && !hasKm -> ScreenState.OFFER_LOADING
            hasFare && (hasAccept || hasOfferKeyword) && hasKm  -> ScreenState.OFFER_LOADED
            hasFare && hasKm                                     -> ScreenState.OFFER_LOADED
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
        // NammaYatri shows driver payout directly (subscription model, no per-ride commission).
        // Use the minimum fare to avoid surge/display price confusion.
        val allFares = activeNodes.mapNotNull { node ->
            if (node.contains("km", ignoreCase = true) || node.startsWith("+")) null
            else FARE_REGEX.find(node)?.groupValues?.get(1)?.toDoubleOrNull()
        }.filter { it >= 10.0 }

        val baseFare = allFares.minOrNull() ?: return null

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
        var rideDistanceKm   = 0.0

        when (kmMatches.size) {
            1    -> rideDistanceKm = kmMatches[0]
            2    -> {
                pickupDistanceKm = kmMatches[0]
                rideDistanceKm   = kmMatches[1]
            }
            else -> if (kmMatches.isNotEmpty()) {
                pickupDistanceKm = kmMatches[0]
                rideDistanceKm   = kmMatches.last()
            }
        }

        // ── EXTRACT DURATION ────────────────────────────────────────────
        val durationMin = activeNodes.mapNotNull { node ->
            MIN_REGEX.find(node)?.groupValues?.get(1)?.toIntOrNull()
        }.firstOrNull() ?: 0

        // ── DETECT VEHICLE TYPE ─────────────────────────────────────────
        // NammaYatri is primarily an auto-rickshaw platform; default to AUTO.
        val fullText = activeNodes.joinToString(" ")
        val vehicleType = when {
            fullText.contains("CNG Auto", ignoreCase = true) -> VehicleType.CNG_AUTO
            fullText.contains("Auto", ignoreCase = true)     -> VehicleType.AUTO
            fullText.contains("Bike", ignoreCase = true) ||
            fullText.contains("Moto", ignoreCase = true)     -> VehicleType.BIKE
            fullText.contains("Cab", ignoreCase = true) ||
            fullText.contains("Car", ignoreCase = true)      -> VehicleType.CAR
            else                                              -> VehicleType.AUTO
        }

        // ── EXTRACT ADDRESSES ───────────────────────────────────────────
        val addressCandidates = activeNodes.filter { node ->
            node.length > 12 &&
            !KM_REGEX.containsMatchIn(node) &&
            !MIN_REGEX.containsMatchIn(node) &&
            !FARE_REGEX.containsMatchIn(node) &&
            !node.equals("Accept", ignoreCase = true) &&
            !node.equals("Decline", ignoreCase = true) &&
            !node.equals("Confirm", ignoreCase = true) &&
            !node.startsWith("+") &&
            !node.contains("New Ride Request", ignoreCase = true) &&
            !node.contains("New Request", ignoreCase = true)
        }
        val pickupAddress = addressCandidates.getOrElse(0) { "" }
        val dropAddress   = addressCandidates.getOrElse(1) { "" }

        // ── EXTRACT PAYMENT TYPE ────────────────────────────────────────
        val paymentKeywords = listOf("cash", "upi", "online", "wallet", "card")
        val paymentType = activeNodes.firstOrNull { node ->
            paymentKeywords.any { node.contains(it, ignoreCase = true) }
        } ?: ""

        // ── EXTRACT TIP ────────────────────────────────────────────────
        var tipAmount = 0.0
        activeNodes.forEach { node ->
            if (node.contains("Tip", ignoreCase = true)) {
                tipAmount = FARE_REGEX.find(node)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            }
        }

        Log.d(TAG, "🔍 NammaYatri parsed: vehicle=$vehicleType fare=₹$baseFare " +
            "bonus=₹$bonusAmount pickup=${pickupDistanceKm}km ride=${rideDistanceKm}km " +
            "state=$screenState")

        return ParsedRide(
            baseFare             = baseFare,
            tipAmount            = tipAmount,
            premiumAmount        = bonusAmount,
            rideDistanceKm       = rideDistanceKm,
            pickupDistanceKm     = pickupDistanceKm,
            estimatedDurationMin = durationMin,
            platform             = packageName,
            packageName          = packageName,
            rawTextNodes         = activeNodes,
            pickupAddress        = pickupAddress,
            dropAddress          = dropAddress,
            paymentType          = paymentType,
            vehicleType          = vehicleType,
            screenState          = screenState,
            bonus                = bonusAmount,
            fare                 = baseFare + bonusAmount
        )
    }
}
