package com.ridesmart.parser

import android.util.Log
import com.ridesmart.model.ParsedRide
import com.ridesmart.model.ScreenState
import com.ridesmart.model.VehicleType

class RapidoParser : IPlatformParser {

    companion object {
        private const val TAG = "RideSmart"

        private val FARE_REGEX = Regex("""₹\s*(\d+(?:\.\d{1,2})?)""")
        private val KM_REGEX   = Regex("""(\d+(?:\.\d{1,2})?)\s*km""", RegexOption.IGNORE_CASE)
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

        var baseFare = 0.0
        var fareIndex = -1
        for (i in activeNodes.indices) {
            val v = FARE_REGEX.find(activeNodes[i])?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            if (v > baseFare) {
                baseFare = v
                fareIndex = i
            }
        }
        if (baseFare == 0.0) return null

        var premiumAmount = 0.0
        activeNodes.forEach { node ->
            val boostMatch = BOOST_REGEX.find(node.trim())
            if (boostMatch != null) {
                val bonus = boostMatch.groupValues[1].toDoubleOrNull() ?: 0.0
                premiumAmount += bonus
            }
        }

        var tipAmount = 0.0
        activeNodes.forEach { node ->
            if (node.contains("Tip", ignoreCase = true)) {
                tipAmount = FARE_REGEX.find(node)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            }
        }

        val kmValues = activeNodes
            .drop(if (fareIndex > 0) fareIndex else 0)
            .mapNotNull { node -> KM_REGEX.find(node)?.groupValues?.get(1)?.toDoubleOrNull() }

        var pickupDistanceKm = 0.0
        var rideDistanceKm   = 0.0

        when (kmValues.size) {
            0    -> { /* loading state */ }
            1    -> { rideDistanceKm = kmValues[0] }
            else -> { pickupDistanceKm = kmValues[0]; rideDistanceKm = kmValues[1] }
        }

        if (pickupDistanceKm > 5.0 && pickupDistanceKm > rideDistanceKm) {
            val tmp = pickupDistanceKm
            pickupDistanceKm = rideDistanceKm
            rideDistanceKm = tmp
            Log.d(TAG, "🔄 Swapped pickup/ride: pickup=${pickupDistanceKm}km ride=${rideDistanceKm}km")
        }

        if (rideDistanceKm > 0.0 && baseFare / rideDistanceKm > 80.0) {
            Log.d(TAG, "⚠️ Sanity check failed: ₹$baseFare for ${rideDistanceKm}km " +
                "(₹${"%.0f".format(baseFare / rideDistanceKm)}/km) — skipping misparse")
            return null
        }

        val durationMin = activeNodes.mapNotNull { node ->
            MIN_REGEX.find(node)?.groupValues?.get(1)?.toIntOrNull()
        }.firstOrNull() ?: 0

        val addressCandidates = activeNodes.filter { node ->
            node.length > 15 &&
            !KM_REGEX.containsMatchIn(node) &&
            !MIN_REGEX.containsMatchIn(node) &&
            !node.equals("Accept", ignoreCase = true) &&
            !node.equals("Match", ignoreCase = true) &&
            !node.startsWith("+") &&
            !node.contains("₹")
        }
        val pickupAddress = addressCandidates.getOrElse(0) { "" }
        val dropAddress   = addressCandidates.getOrElse(1) { "" }

        val paymentKeywords = listOf("cash", "upi", "online", "wallet", "card")
        val paymentType = activeNodes.firstOrNull { node ->
            paymentKeywords.any { node.contains(it, ignoreCase = true) }
        } ?: ""

        Log.d(TAG, "🔍 Rapido parsed: vehicle=$vehicleType fare=₹$baseFare " +
            "boost=₹$premiumAmount pickup=${pickupDistanceKm}km ride=${rideDistanceKm}km " +
            "state=$screenState")

        return ParsedRide(
            baseFare             = baseFare,
            tipAmount            = tipAmount,
            premiumAmount        = premiumAmount,
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
            screenState          = screenState
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
