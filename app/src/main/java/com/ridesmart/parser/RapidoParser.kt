package com.ridesmart.parser

import android.util.Log
import com.ridesmart.model.ParsedRide
import com.ridesmart.model.ParseResult
import com.ridesmart.model.ScreenState
import com.ridesmart.model.VehicleType
import com.ridesmart.service.RapidoNodeBundle

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

        private val HOME_SCREEN_KEYWORDS = listOf(
            "performance icon", "right on track", "quick actions",
            "choose your earning plan", "view rate card", "view all plans",
            "select your next plan", "on-ride booking", "incentives and more",
            "portrait image card", "terms and conditions", "plan is active",
            "hi rajat", "good morning", "good afternoon", "good evening",
            "weekly earnings", "daily earnings target", "rides completed"
        )

        private val ADDRESS_BLACKLIST = listOf(
            "Accept", "Match", "Go To", "See", "Includes", "Demand", "Ride for you", 
            "Customer added", "Tip", "Earnings", "Order", "History", "Support"
        )
        
        private val VEHICLE_KEYWORDS = listOf("Bike", "Auto", "Car", "Prime", "Metro", "Taxi", "Boost", "e-Bike")
    }

    override fun detectScreenState(nodes: List<String>): ScreenState {
        val combined = nodes.joinToString(" ").lowercase()

        // Home/dashboard screen detection — return IDLE immediately
        if (HOME_SCREEN_KEYWORDS.any { combined.contains(it) }) {
            return ScreenState.IDLE
        }

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

    private fun parseVehicleTypeFromServiceName(text: String): VehicleType {
        return when {
            text.contains("Bike Boost", ignoreCase = true) -> VehicleType.BIKE_BOOST
            text.contains("CNG Auto",   ignoreCase = true) -> VehicleType.CNG_AUTO
            text.contains("e-Bike",     ignoreCase = true) -> VehicleType.EBIKE
            text.contains("Bike Taxi",  ignoreCase = true) -> VehicleType.BIKE
            text.contains("Bike Metro", ignoreCase = true) -> VehicleType.BIKE
            text.contains("Bike",       ignoreCase = true) -> VehicleType.BIKE
            text.contains("Auto",       ignoreCase = true) -> VehicleType.AUTO
            text.contains("Car",        ignoreCase = true) -> VehicleType.CAR
            text.contains("Prime",      ignoreCase = true) -> VehicleType.CAR
            else                                           -> VehicleType.UNKNOWN
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

    fun parseFromBundle(bundle: RapidoNodeBundle, packageName: String): ParseResult {
        // ── FARE ────────────────────────────────────────────────────────
        var baseFare = 0.0
        if (bundle.fare.isNotBlank()) {
            baseFare = FARE_REGEX.find(bundle.fare)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        }
        // Fallback: scan allTextNodes using existing logic
        if (baseFare == 0.0) {
            for (node in bundle.allTextNodes) {
                val trimmed = node.trim()
                if (trimmed.startsWith("+")) continue
                val v = FARE_REGEX.find(trimmed)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
                if (v > 5.0) { baseFare = v; break }
            }
        }
        if (baseFare == 0.0) return ParseResult.Failure("RapidoBundle: no fare", confidence = 0.2f)

        // ── VEHICLE TYPE ─────────────────────────────────────────────────
        val vehicleType = if (bundle.vehicleType.isNotBlank())
            parseVehicleTypeFromServiceName(bundle.vehicleType)
        else
            detectVehicleType(bundle.allTextNodes)

        // ── PICKUP DISTANCE ──────────────────────────────────────────────
        var pickupDistanceKm = 0.0
        if (bundle.pickupDistanceText.isNotBlank()) {
            pickupDistanceKm = KM_REGEX.find(bundle.pickupDistanceText)
                ?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        }

        // ── ADDRESSES ────────────────────────────────────────────────────
        val pickupAddress = bundle.pickupAddress.ifBlank {
            bundle.allTextNodes.firstOrNull { n ->
                n.length > 12 && !KM_REGEX.containsMatchIn(n) &&
                !n.contains("₹") &&
                ADDRESS_BLACKLIST.none { n.contains(it, ignoreCase = true) }
            } ?: ""
        }
        val dropAddress = bundle.dropAddress.ifBlank {
            bundle.allTextNodes.firstOrNull { n ->
                n.length > 12 && !KM_REGEX.containsMatchIn(n) &&
                !n.contains("₹") && n != pickupAddress &&
                ADDRESS_BLACKLIST.none { n.contains(it, ignoreCase = true) }
            } ?: ""
        }

        // ── OFFER AGE → CONFIDENCE ───────────────────────────────────────
        val offerAgeSeconds = if (bundle.offerAgeText.isNotBlank())
            Regex("""(\d+)\s*s""").find(bundle.offerAgeText)
                ?.groupValues?.get(1)?.toIntOrNull() ?: 0
        else 0
        val confidence = if (offerAgeSeconds >= 12) 0.5f else 1.0f

        // ── RIDE DISTANCE + DURATION via existing multi-card logic ────────
        // Run the full parseAll on allTextNodes to get rideDistanceKm,
        // premiumAmount, tipAmount, durationMin, and catch extra cards.
        val fullResult = parseAll(bundle.allTextNodes, packageName)

        return when (fullResult) {
            is ParseResult.Success -> {
                // Enrich the first parsed ride with bundle-sourced fields
                val enriched = fullResult.rides.mapIndexed { i, ride ->
                    if (i == 0) ride.copy(
                        pickupDistanceKm = if (pickupDistanceKm > 0.0) pickupDistanceKm else ride.pickupDistanceKm,
                        vehicleType      = if (vehicleType != VehicleType.UNKNOWN) vehicleType else ride.vehicleType,
                        pickupAddress    = pickupAddress.ifBlank { ride.pickupAddress },
                        dropAddress      = dropAddress.ifBlank  { ride.dropAddress  },
                        confidence       = confidence
                    ) else ride
                }
                ParseResult.Success(enriched)
            }
            else -> {
                // fullResult failed but we have a fare — build a minimal ride
                ParseResult.Success(listOf(
                    ParsedRide(
                        baseFare             = baseFare,
                        rideDistanceKm       = 0.0,
                        pickupDistanceKm     = pickupDistanceKm,
                        platform             = "Rapido",
                        packageName          = packageName,
                        vehicleType          = vehicleType,
                        pickupAddress        = pickupAddress,
                        dropAddress          = dropAddress,
                        rawTextNodes         = bundle.allTextNodes,
                        confidence           = confidence
                    )
                ))
            }
        }
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
