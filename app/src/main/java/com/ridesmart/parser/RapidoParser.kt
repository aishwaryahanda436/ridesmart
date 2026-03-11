package com.ridesmart.parser

import android.util.Log
import com.ridesmart.model.ParsedRide
import com.ridesmart.model.ScreenState
import com.ridesmart.model.VehicleType
import com.ridesmart.model.PlatformConfig

class RapidoParser : IPlatformParser {

    companion object {
        private const val TAG = "RideSmart"

        private val FARE_REGEX = Regex("""₹\s*(\d+(?:\.\d{1,2})?)""")
        private val KM_REGEX   = Regex("""(\d+(?:\.\d{1,2})?)\s*k?m""", RegexOption.IGNORE_CASE)
        private val MIN_REGEX  = Regex("""(\d+)\s*min""", RegexOption.IGNORE_CASE)
        private val BOOST_REGEX = Regex("""\+\s*₹\s*(\d+(?:\.\d{1,2})?)""")

        private val CARD_SPLIT_KEYWORDS = setOf(
            "Bike Boost", "Auto", "CNG Auto", "e-Bike", "Car",
            "UberGo", "Premier", "Uber XL", "Uber Moto", "Uber Auto"
        )

        private val ACTIVE_RIDE_KEYWORDS = listOf(
            "end ride", "complete ride", "drop otp", "arrived at pickup",
            "start ride", "otp to start", "you are on a trip"
        )

        private val PAYMENT_KEYWORDS = listOf("cash", "upi", "online", "wallet", "card")
    }

    override fun detectScreenState(nodes: List<String>): ScreenState {
        val combined = nodes.joinToString(" ").lowercase()

        if (ACTIVE_RIDE_KEYWORDS.any { combined.contains(it) }) {
            Log.d(TAG, "🚫 Rapido: ACTIVE_RIDE detected — suppressing overlay")
            return ScreenState.ACTIVE_RIDE
        }

        val hasAccept = nodes.any { it.equals("Accept", ignoreCase = true) }
        val hasKm     = nodes.any { KM_REGEX.containsMatchIn(it) }
        val hasFare   = nodes.any { FARE_REGEX.containsMatchIn(it) }

        return when {
            !hasFare && !hasAccept -> ScreenState.IDLE
            hasFare && hasAccept && !hasKm -> ScreenState.OFFER_LOADING
            hasFare && hasAccept && hasKm  -> ScreenState.OFFER_LOADED
            else -> ScreenState.IDLE
        }
    }

    fun detectVehicleType(nodes: List<String>): VehicleType {
        val combined = nodes.joinToString(" ")
        return when {
            combined.contains("Bike Boost", ignoreCase = true) -> VehicleType.BIKE_BOOST
            combined.contains("CNG Auto", ignoreCase = true)   -> VehicleType.CNG_AUTO
            combined.contains("e-Bike", ignoreCase = true)     -> VehicleType.EBIKE
            combined.contains("Auto", ignoreCase = true)       -> VehicleType.AUTO
            combined.contains("Car", ignoreCase = true) ||
            combined.contains("Prime", ignoreCase = true)      -> VehicleType.CAR
            combined.contains("Bike", ignoreCase = true)       -> VehicleType.BIKE
            else                                                -> VehicleType.UNKNOWN
        }
    }

    fun parseExpandedCard(nodes: List<String>, packageName: String): ParsedRide? {
        val activeNodes = nodes.filter { it.isNotBlank() }
        val screenState = detectScreenState(activeNodes)

        if (screenState == ScreenState.ACTIVE_RIDE || screenState == ScreenState.IDLE) return null

        val vehicleType = detectVehicleType(activeNodes)

        // ── SINGLE-PASS EXTRACTION ────────────────────────────────────────
        var baseFare = 0.0
        var fareIndex = -1
        var premiumAmount = 0.0
        var tipAmount = 0.0
        var durationMin = 0
        var paymentType = ""
        val addressCandidates = mutableListOf<String>()
        val allKmValues = mutableListOf<Pair<Int, Double>>() // (nodeIndex, value)

        for (i in activeNodes.indices) {
            val node = activeNodes[i]
            val trimmed = node.trim()

            // Handle combined fare + boost format: "₹42 + ₹12"
            if (trimmed.contains("+") && trimmed.contains("₹")) {
                val parts = trimmed.split("+")
                if (parts.size >= 2) {
                    val part1 = FARE_REGEX.find(parts[0])?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
                    val part2 = FARE_REGEX.find(parts[1])?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
                    if (part1 > 0) {
                        baseFare = part1
                        fareIndex = i
                        premiumAmount += part2
                        continue // Skip normal regex processing for this node
                    }
                }
            }

            // Normal Fare extraction: skip boost lines (+₹...) and distance lines
            if (!trimmed.startsWith("+") && !KM_REGEX.containsMatchIn(trimmed)) {
                FARE_REGEX.find(trimmed)?.groupValues?.get(1)?.toDoubleOrNull()?.let { v ->
                    if (v > baseFare) { baseFare = v; fareIndex = i }
                }
            }

            // Boost / premium (standalone node format)
            BOOST_REGEX.find(trimmed)?.groupValues?.get(1)?.toDoubleOrNull()?.let {
                premiumAmount += it
            }

            // Tip (first match wins)
            if (tipAmount == 0.0 && node.contains("Tip", ignoreCase = true)) {
                FARE_REGEX.find(node)?.groupValues?.get(1)?.toDoubleOrNull()?.let { tipAmount = it }
            }

            // Distance
            KM_REGEX.find(node)?.groupValues?.get(1)?.toDoubleOrNull()?.let {
                allKmValues.add(Pair(i, it))
            }

            // Duration
            if (durationMin == 0) {
                MIN_REGEX.find(node)?.groupValues?.get(1)?.toIntOrNull()?.let { durationMin = it }
            }

            // Payment type
            if (paymentType.isEmpty() && PAYMENT_KEYWORDS.any { node.contains(it, ignoreCase = true) }) {
                paymentType = node
            }

            // Address candidates
            if (node.length > 15 &&
                !KM_REGEX.containsMatchIn(node) &&
                !MIN_REGEX.containsMatchIn(node) &&
                !node.equals("Accept", ignoreCase = true) &&
                !node.equals("Match", ignoreCase = true) &&
                !node.startsWith("+") &&
                !node.contains("₹")) {
                addressCandidates.add(node)
            }
        }

        if (baseFare == 0.0) return null

        // ── DISTANCE RESOLUTION ───────────────────────────────────────────
        val kmValues = allKmValues
            .filter { (idx, _) -> idx >= (if (fareIndex > 0) fareIndex else 0) || idx == 0 } // km might be at top
            .map { (_, v) -> v }

        var pickupDistanceKm = 0.0
        var rideDistanceKm   = 0.0

        when (kmValues.size) {
            0    -> { /* loading */ }
            1    -> { rideDistanceKm = kmValues[0] }
            else -> { pickupDistanceKm = kmValues[0]; rideDistanceKm = kmValues[1] }
        }

        if (pickupDistanceKm > 5.0 && pickupDistanceKm > rideDistanceKm && rideDistanceKm > 0) {
            val tmp = pickupDistanceKm
            pickupDistanceKm = rideDistanceKm
            rideDistanceKm = tmp
        }

        val pickupAddress = addressCandidates.getOrElse(0) { "" }
        val dropAddress   = addressCandidates.getOrElse(1) { "" }

        Log.d(TAG, "🔍 Rapido parsed: vehicle=$vehicleType fare=₹$baseFare " +
            "boost=₹$premiumAmount pickup=${pickupDistanceKm}km ride=${rideDistanceKm}km")

        return ParsedRide(
            baseFare             = baseFare,
            tipAmount            = tipAmount,
            premiumAmount        = premiumAmount,
            rideDistanceKm       = rideDistanceKm,
            pickupDistanceKm     = pickupDistanceKm,
            estimatedDurationMin = durationMin,
            platform              = PlatformConfig.get(packageName).displayName,
            packageName           = packageName,
            rawTextNodes         = activeNodes,
            pickupAddress        = pickupAddress,
            dropAddress          = dropAddress,
            paymentType          = paymentType,
            vehicleType          = vehicleType,
            screenState          = screenState,
            bonus                = premiumAmount,
            fare                 = baseFare + premiumAmount
        )
    }

    override fun parseAll(nodes: List<String>, packageName: String): List<ParsedRide> {
        val activeNodes = nodes.filter { it.isNotBlank() }
        val screenState = detectScreenState(activeNodes)

        if (screenState == ScreenState.ACTIVE_RIDE || screenState == ScreenState.IDLE) {
            return emptyList()
        }

        val cards = mutableListOf<List<String>>()
        var current = mutableListOf<String>()

        for (node in activeNodes) {
            if (node.trim() in CARD_SPLIT_KEYWORDS && current.isNotEmpty()) {
                cards.add(current)
                current = mutableListOf()
            }
            current.add(node)
        }
        if (current.isNotEmpty()) cards.add(current)

        if (cards.size <= 1) {
            val ride = parseExpandedCard(activeNodes, packageName) ?: return emptyList()
            return listOf(ride)
        }

        return cards.mapNotNull { parseExpandedCard(it, packageName) }
    }
}
