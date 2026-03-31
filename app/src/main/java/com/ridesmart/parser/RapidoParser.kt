package com.ridesmart.parser

import android.util.Log
import com.ridesmart.model.ParsedRide
import com.ridesmart.model.ParseResult
import com.ridesmart.model.ScreenState
import com.ridesmart.model.VehicleType

class RapidoParser : IPlatformParser {

    companion object {
        private const val TAG = "RideSmart"

        private val FARE_REGEX = Regex("""(?:₹|Rs\.?)\s*(\d+(?:\.\d{1,2})?)""")
        private val KM_REGEX   = Regex("""(\d+(?:\.\d{1,2})?)\s*km""", RegexOption.IGNORE_CASE)
        private val MIN_REGEX  = Regex("""(\d+)\s*min""", RegexOption.IGNORE_CASE)
        private val BOOST_REGEX = Regex("""\+\s*(?:₹|Rs\.?)\s*(\d+(?:\.\d{1,2})?)""")

        private val ACTIVE_RIDE_KEYWORDS = listOf(
            "end ride", "complete ride", "drop otp", "arrived at pickup",
            "start ride", "otp to start", "you are on a trip"
        )

        private val ADDRESS_BLACKLIST = listOf(
            "Accept", "Match", "Go To", "See", "Includes", "Demand", "Ride for you", 
            "Customer added", "Tip", "Earnings", "Order", "History", "Support"
        )
        
        private val VEHICLE_KEYWORDS = listOf("Bike", "Auto", "Car", "Prime", "Metro", "Taxi", "Boost", "e-Bike")
    }

    override fun detectScreenState(nodes: List<String>): ScreenState {
        val combined = nodes.joinToString(" ").lowercase()

        if (ACTIVE_RIDE_KEYWORDS.any { combined.contains(it) }) {
            return ScreenState.IDLE
        }

        val hasAccept = nodes.any { it.equals("Accept", ignoreCase = true) || it.equals("Match", ignoreCase = true) }
        val hasKm     = nodes.any { KM_REGEX.containsMatchIn(it) }
        val hasFare   = nodes.any { FARE_REGEX.containsMatchIn(it) }

        return when {
            !hasFare && !hasAccept           -> ScreenState.IDLE
            hasFare && hasAccept && !hasKm   -> ScreenState.OFFER_LOADING
            hasFare && hasAccept && hasKm    -> ScreenState.OFFER_LOADED
            hasFare && hasKm && !hasAccept   -> ScreenState.OFFER_LOADING
            hasFare && !hasKm && !hasAccept  -> ScreenState.OFFER_LOADING
            else                             -> ScreenState.IDLE
        }
    }

    private fun detectVehicleType(nodes: List<String>): VehicleType {
        val combined = nodes.joinToString(" ")
        return when {
            combined.contains("Bike Boost", ignoreCase = true)  -> VehicleType.BIKE_BOOST
            combined.contains("Bike Metro", ignoreCase = true)  -> VehicleType.BIKE
            combined.contains("Bike Taxi",  ignoreCase = true)  -> VehicleType.BIKE
            combined.contains("CNG Auto",   ignoreCase = true)  -> VehicleType.CNG_AUTO
            combined.contains("e-Bike",     ignoreCase = true)  -> VehicleType.EBIKE
            nodes.any { it.equals("Auto", ignoreCase = true) }  -> VehicleType.AUTO
            combined.contains("Car",        ignoreCase = true) ||
            combined.contains("Prime",      ignoreCase = true)  -> VehicleType.CAR
            combined.contains("Bike",       ignoreCase = true)  -> VehicleType.BIKE
            else                                                 -> VehicleType.UNKNOWN
        }
    }

    private fun parseExpandedCard(nodes: List<String>, packageName: String): ParsedRide? {
        val activeNodes = nodes.filter { it.isNotBlank() }
        val screenState = detectScreenState(activeNodes)

        if (screenState == ScreenState.ACTIVE_RIDE || screenState == ScreenState.IDLE) return null

        val vehicleType = detectVehicleType(activeNodes).let { detected ->
            if (detected == VehicleType.UNKNOWN) {
                detectVehicleType(listOf(activeNodes.joinToString(" ")).plus(activeNodes))
            } else {
                detected
            }
        }

        var baseFare = 0.0
        var fareIndex = -1
        for (i in activeNodes.indices) {
            val node = activeNodes[i]
            if (node.trim().startsWith("+")) continue
            val v = FARE_REGEX.find(node)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            if (v > 5.0) {
                baseFare = v
                fareIndex = i
                break
            }
        }
        if (baseFare == 0.0) return null

        var premiumAmount = 0.0
        if (fareIndex >= 0) {
            val fareNode = activeNodes[fareIndex]
            if (fareNode.contains("+")) {
                val inlineBonus = BOOST_REGEX.find(fareNode)
                    ?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
                if (inlineBonus > 0.0) premiumAmount = inlineBonus
            }
        }
        if (premiumAmount == 0.0) {
            val nodesAfterFare = if (fareIndex >= 0) activeNodes.drop(fareIndex + 1) else activeNodes
            for (node in nodesAfterFare) {
                if (node.trim().startsWith("+")) {
                    val bonus = BOOST_REGEX.find(node.trim())
                        ?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
                    if (bonus > 0.0) {
                        premiumAmount = bonus
                        break
                    }
                }
            }
        }
        if (premiumAmount > baseFare) premiumAmount = baseFare

        var tipAmount = 0.0
        activeNodes.forEach { node ->
            if (node.contains("Tip", ignoreCase = true) || node.contains("Customer added", ignoreCase = true)) {
                val extracted = FARE_REGEX.find(node)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
                if (extracted > 0.0 && premiumAmount == 0.0) tipAmount = extracted
            }
        }

        val kmValues = activeNodes
            .drop(if (fareIndex > 0) fareIndex else 0)
            .mapNotNull { node -> KM_REGEX.find(node)?.groupValues?.get(1)?.toDoubleOrNull() }

        var pickupDistanceKm = 0.0
        var rideDistanceKm   = 0.0

        if (kmValues.size >= 2) {
            val km0 = kmValues[0]
            val km1 = kmValues[1]

            // Swap if pickup looks clearly larger than ride AND is suspiciously long.
            // Rapido card order is: pickup distance first, ride distance second.
            // Only swap when pickup is more than 1.5x the ride AND exceeds 4km.
            if (km0 > km1 * 1.5 && km0 > 4.0) {
                pickupDistanceKm = km1
                rideDistanceKm   = km0
            } else {
                // Trust the card order
                pickupDistanceKm = km0
                rideDistanceKm   = km1
            }
        } else if (kmValues.size == 1) {
            rideDistanceKm = kmValues[0]
        }

        if (rideDistanceKm > 0.0 && baseFare / rideDistanceKm > 100.0) return null

        val durationMin = activeNodes.mapNotNull { node ->
            MIN_REGEX.find(node)?.groupValues?.get(1)?.toIntOrNull()
        }.firstOrNull() ?: 0

        val addressCandidates = activeNodes.filter { node ->
            node.length > 12 &&
            !KM_REGEX.containsMatchIn(node) &&
            !MIN_REGEX.containsMatchIn(node) &&
            !node.contains("₹") &&
            !ADDRESS_BLACKLIST.any { node.contains(it, ignoreCase = true) }
        }
        val pickupAddress = addressCandidates.getOrElse(0) { "" }
        val dropAddress   = addressCandidates.getOrElse(1) { "" }

        val paymentKeywords = listOf("cash", "upi", "online", "wallet", "card")
        val paymentType = activeNodes.firstOrNull { node ->
            paymentKeywords.any { node.contains(it, ignoreCase = true) }
        } ?: ""

        return ParsedRide(
            baseFare             = baseFare,
            tipAmount            = tipAmount,
            premiumAmount        = premiumAmount,
            rideDistanceKm       = rideDistanceKm,
            pickupDistanceKm     = pickupDistanceKm,
            estimatedDurationMin = durationMin,
            platform             = "Rapido",
            packageName          = packageName,
            rawTextNodes         = activeNodes,
            pickupAddress        = pickupAddress,
            dropAddress          = dropAddress,
            paymentType          = paymentType,
            vehicleType          = vehicleType,
            screenState          = screenState
        )
    }

    override fun parseAll(nodes: List<String>, packageName: String): ParseResult {
        val activeNodes = nodes.filter { it.isNotBlank() }
        val screenState = detectScreenState(activeNodes)

        if (screenState == ScreenState.IDLE) return ParseResult.Idle
        if (screenState == ScreenState.ACTIVE_RIDE) return ParseResult.Failure("Active ride in progress")

        val cardSegments = mutableListOf<List<String>>()
        var currentSegment = mutableListOf<String>()

        for (node in activeNodes) {
            val trimmed = node.trim()
            val startsWithFareSymbol = trimmed.startsWith("₹") || trimmed.startsWith("Rs.") || trimmed.startsWith("Rs ")
            val fareVal = if (startsWithFareSymbol) FARE_REGEX.find(trimmed)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0 else 0.0
            
            // Refined Multi-Card Split Logic:
            // A new card starts if we see a fare, AND either:
            // 1. We previously saw an action button (Accept/Match)
            // 2. We previously saw a vehicle type keyword (Bike/Auto/etc)
            val isNewCardStart = fareVal > 5.0 && startsWithFareSymbol && !trimmed.contains("+") && (
                currentSegment.any { it.equals("Accept", true) || it.equals("Match", true) } ||
                currentSegment.any { seg -> VEHICLE_KEYWORDS.any { kw -> seg.contains(kw, true) } }
            )

            if (isNewCardStart && currentSegment.isNotEmpty()) {
                cardSegments.add(currentSegment.toList())
                currentSegment = mutableListOf()
            }
            currentSegment.add(node)
        }
        if (currentSegment.isNotEmpty()) cardSegments.add(currentSegment.toList())

        val segmentsToTry = if (cardSegments.isEmpty()) listOf(activeNodes) else cardSegments
        val results = mutableListOf<ParsedRide>()

        for (segment in segmentsToTry) {
            val ride = parseExpandedCard(segment, packageName) ?: continue
            results.add(ride)
        }

        return if (results.isNotEmpty()) {
            ParseResult.Success(results)
        } else {
            ParseResult.Failure("No Rapido rides parsed", confidence = 0.2f)
        }
    }
}
