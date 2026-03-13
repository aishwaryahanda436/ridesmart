package com.ridesmart.parser

import android.util.Log
import com.ridesmart.model.ParsedRide
import com.ridesmart.model.ScreenState
import com.ridesmart.model.VehicleType
import com.ridesmart.model.PlatformConfig

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

        private val NY_FARE_LABELS     = listOf("Base Fare", "Offer Price", "Ride Fare", "₹")
        private val NY_PICKUP_LABELS   = listOf("Pickup", "From", "Pick Up Point")
        private val NY_DROP_LABELS     = listOf("Drop", "To", "Destination", "Drop Point")
        private val NY_DISTANCE_LABELS = listOf("Distance", "Trip Distance", "km away")
        private val NY_ACCEPT_LABELS   = listOf("Accept", "Accept Offer", "Confirm Ride")

        private val ACTIVE_RIDE_KEYWORDS = listOf(
            "start ride", "end ride", "trip started", "arrived at pickup",
            "reached pickup", "drop otp", "you are on a trip", "trip ongoing",
            "you are on a ride", "ride started"
        )

        private val IDLE_KEYWORDS = listOf(
            "you are offline", "go online", "no rides available",
            "earnings today", "my rides", "ride history", "you're offline"
        )

        private val OFFER_KEYWORDS = listOf(
            "new ride request", "new request", "accept", "decline",
            "confirm", "ride request"
        )

        private val PAYMENT_KEYWORDS = listOf("cash", "upi", "online", "wallet", "card")
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

        // ── PASS 1: LABEL-GUIDED EXTRACTION ──────────────────────────────────
        // NammaYatri uses Jetpack Compose with explicit semantic labels.
        // Each value node is preceded by a label node in the accessibility tree.
        var labelFare: Double? = null
        var labelPickup: String? = null
        var labelDrop: String? = null

        for (i in 1 until activeNodes.size) {
            val prev = activeNodes[i - 1].trim()
            val curr = activeNodes[i].trim()

            when {
                NY_FARE_LABELS.any { prev.equals(it, ignoreCase = true) } &&
                    FARE_REGEX.containsMatchIn(curr) -> {
                    if (labelFare == null) {
                        FARE_REGEX.find(curr)?.groupValues?.get(1)?.toDoubleOrNull()
                            ?.takeIf { it >= 10.0 }?.let { labelFare = it }
                    }
                }
                NY_PICKUP_LABELS.any { prev.equals(it, ignoreCase = true) } &&
                    curr.length > 3 -> {
                    if (labelPickup == null) labelPickup = curr
                }
                NY_DROP_LABELS.any { prev.equals(it, ignoreCase = true) } &&
                    curr.length > 3 -> {
                    if (labelDrop == null) labelDrop = curr
                }
            }
        }

        if (labelFare != null || labelPickup != null || labelDrop != null) {
            Log.d(TAG, "NammaYatri label-guided: fare=$labelFare pickup=$labelPickup drop=$labelDrop")
        }

        // ── PASS 2: REGEX FALLBACK ────────────────────────────────────────────
        // Only extracts fields that label-guided pass missed.
        // NammaYatri shows driver payout directly (subscription model, no per-ride commission).
        // Use the minimum fare to avoid surge/display price confusion.
        var bonusAmount = 0.0
        var durationMin = 0
        var tipAmount = 0.0
        var paymentType = ""
        val fareCandidates = mutableListOf<Double>()
        val kmMatches = mutableListOf<Double>()
        val addressCandidates = mutableListOf<String>()
        // Boolean flags for vehicle type — resolved after the loop with the same
        // priority order as the original: CNG Auto > Auto > Bike/Moto > Cab/Car > AUTO default.
        var foundCngAuto     = false
        var foundBike        = false
        var foundCar         = false

        for (node in activeNodes) {
            val trimmed = node.trim()

            // Fare: only needed if label-guided pass missed it; skip distance/boost lines
            if (labelFare == null && !trimmed.startsWith("+") && !KM_REGEX.containsMatchIn(trimmed)) {
                FARE_REGEX.find(trimmed)?.groupValues?.get(1)?.toDoubleOrNull()?.let {
                    if (it >= 10.0) fareCandidates.add(it)
                }
            }

            // Bonus
            BONUS_REGEX.find(trimmed)?.groupValues?.get(1)?.toDoubleOrNull()?.let {
                bonusAmount += it
            }

            // Distance
            KM_REGEX.find(node)?.groupValues?.get(1)?.toDoubleOrNull()?.let { kmMatches.add(it) }

            // Duration (first match wins)
            if (durationMin == 0) {
                MIN_REGEX.find(node)?.groupValues?.get(1)?.toIntOrNull()?.let { durationMin = it }
            }

            // Vehicle type keywords — collect flags; resolved after the loop.
            // CNG Auto is checked before Auto to avoid the substring match taking precedence.
            when {
                node.contains("CNG Auto", ignoreCase = true) -> foundCngAuto = true
                node.contains("Bike",     ignoreCase = true) ||
                node.contains("Moto",     ignoreCase = true) -> foundBike = true
                node.contains("Cab",      ignoreCase = true) ||
                node.contains("Car",      ignoreCase = true) -> foundCar  = true
                // Plain "Auto" keeps the platform default (AUTO) — no flag needed.
            }

            // Payment type (first match wins)
            if (paymentType.isEmpty() && PAYMENT_KEYWORDS.any { node.contains(it, ignoreCase = true) }) {
                paymentType = node
            }

            // Tip (first match wins)
            if (tipAmount == 0.0 && node.contains("Tip", ignoreCase = true)) {
                tipAmount = FARE_REGEX.find(node)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            }

            // Address candidates (only if label-guided pass missed pickup or drop)
            if (labelPickup == null || labelDrop == null) {
                if (node.length > 12 &&
                    !KM_REGEX.containsMatchIn(node) &&
                    !MIN_REGEX.containsMatchIn(node) &&
                    !FARE_REGEX.containsMatchIn(node) &&
                    !node.equals("Accept", ignoreCase = true) &&
                    !node.equals("Decline", ignoreCase = true) &&
                    !node.equals("Confirm", ignoreCase = true) &&
                    !node.startsWith("+") &&
                    !node.contains("New Ride Request", ignoreCase = true) &&
                    !node.contains("New Request", ignoreCase = true)) {
                    addressCandidates.add(node)
                }
            }
        }

        val baseFare = labelFare ?: fareCandidates.minOrNull() ?: return null

        // NammaYatri is primarily an auto-rickshaw platform; default to AUTO.
        val vehicleType = when {
            foundCngAuto -> VehicleType.CNG_AUTO
            foundBike    -> VehicleType.BIKE
            foundCar     -> VehicleType.CAR
            else         -> VehicleType.AUTO
        }

        // ── DISTANCE RESOLUTION ───────────────────────────────────────────
        var pickupDistanceKm = 0.0
        var rideDistanceKm   = 0.0

        when (kmMatches.size) {
            1    -> rideDistanceKm = kmMatches[0]
            2    -> { pickupDistanceKm = kmMatches[0]; rideDistanceKm = kmMatches[1] }
            else -> if (kmMatches.isNotEmpty()) {
                pickupDistanceKm = kmMatches[0]; rideDistanceKm = kmMatches.last()
            }
        }

        val pickupAddress = labelPickup ?: addressCandidates.getOrElse(0) { "" }
        val dropAddress   = labelDrop   ?: addressCandidates.getOrElse(1) { "" }

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
            platform              = PlatformConfig.get(packageName).displayName,
            packageName          = packageName,
            rawTextNodes         = activeNodes,
            pickupAddress        = pickupAddress,
            dropAddress          = dropAddress,
            paymentType          = paymentType,
            vehicleType          = vehicleType,
            screenState          = screenState,
            bonus                = bonusAmount
        )
    }
}
