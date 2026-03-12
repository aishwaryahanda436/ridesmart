package com.ridesmart.parser

import android.util.Log
import com.ridesmart.model.ParsedRide
import com.ridesmart.model.ScreenState
import com.ridesmart.model.VehicleType
import com.ridesmart.model.PlatformConfig

/**
 * Dedicated parser for Ola Driver app ride requests.
 *
 * Ola Driver shows ride offers with:
 *  - Fare after 20% commission deduction (actual driver payout)
 *  - Pickup distance and ride distance
 *  - Duration estimate
 *  - Vehicle type (Mini, Prime, Auto, Bike)
 *
 * Ola accessibility nodes are generally readable without OCR.
 */
class OlaParser : IPlatformParser {

    companion object {
        private const val TAG = "RideSmart"

        private val FARE_REGEX = Regex("""₹\s*(\d+(?:\.\d{1,2})?)""")
        private val RS_FARE_REGEX = Regex("""[Rr]s\s*(\d+(?:[.,]\d{1,2})?)""")
        private val KM_REGEX = Regex("""(\d+(?:\.\d{1,2})?)\s*km""", RegexOption.IGNORE_CASE)
        private val MIN_REGEX = Regex("""(\d+)\s*min""", RegexOption.IGNORE_CASE)
        private val BONUS_REGEX = Regex("""\+₹\s*(\d+(?:\.\d{1,2})?)""")

        private val ACTIVE_RIDE_KEYWORDS = listOf(
            "drop passenger", "end trip", "arriving now", "reached pickup",
            "start ride", "on trip", "drop otp", "trip started"
        )

        private val IDLE_KEYWORDS = listOf(
            "you are offline", "go online", "no rides available",
            "earnings today", "my earnings", "ride history"
        )

        private val PAYMENT_KEYWORDS = listOf("cash", "upi", "online", "wallet", "card")
    }

    override fun detectScreenState(nodes: List<String>): ScreenState {
        val combined = nodes.joinToString(" ").lowercase()

        if (ACTIVE_RIDE_KEYWORDS.any { combined.contains(it) }) {
            Log.d(TAG, "🚫 Ola: ACTIVE_RIDE detected — suppressing overlay")
            return ScreenState.ACTIVE_RIDE
        }

        if (IDLE_KEYWORDS.any { combined.contains(it) }) return ScreenState.IDLE

        val hasAccept = nodes.any {
            it.equals("Accept", ignoreCase = true) ||
            it.equals("Confirm", ignoreCase = true)
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

        // ── SINGLE-PASS EXTRACTION ────────────────────────────────────────
        // All fields are collected in one iteration to minimise processing time.
        // Ola shows the driver's payout (post-commission) — we take the minimum fare.
        var bonusAmount = 0.0
        var durationMin = 0
        var tipAmount = 0.0
        var paymentType = ""
        val fareCandidates = mutableListOf<Double>()
        val kmMatches = mutableListOf<Double>()
        val addressCandidates = mutableListOf<String>()
        // Boolean flags for vehicle type detection — resolved after the loop with the
        // same priority order as the original single-pass `when` on full joined text.
        var foundVehicleCar  = false
        var foundVehicleAuto = false
        var foundVehicleBike = false

        for (node in activeNodes) {
            val trimmed = node.trim()

            // Fare: skip distance lines and boost lines. Check both ₹ and Rs formats
            // (Ola uses contentDescription="Rs 124.00" alongside getText()="₹124").
            if (!trimmed.startsWith("+") && !KM_REGEX.containsMatchIn(trimmed)) {
                val fareMatch = FARE_REGEX.find(trimmed) ?: RS_FARE_REGEX.find(trimmed)
                fareMatch?.groupValues?.get(1)?.replace(",", ".")?.toDoubleOrNull()?.let {
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

            // Vehicle type keywords — collect flags for priority resolution after the loop.
            // Priority order matches original: Mini/Prime/Sedan > Auto > Bike/Moto > else.
            when {
                node.contains("Mini",  ignoreCase = true) ||
                node.contains("Prime", ignoreCase = true) ||
                node.contains("Sedan", ignoreCase = true) -> foundVehicleCar  = true
                node.contains("Auto",  ignoreCase = true) -> foundVehicleAuto = true
                node.contains("Bike",  ignoreCase = true) ||
                node.contains("Moto",  ignoreCase = true) -> foundVehicleBike = true
            }

            // Payment type (first match wins)
            if (paymentType.isEmpty() && PAYMENT_KEYWORDS.any { node.contains(it, ignoreCase = true) }) {
                paymentType = node
            }

            // Tip (first match wins)
            if (tipAmount == 0.0 && node.contains("Tip", ignoreCase = true)) {
                tipAmount = FARE_REGEX.find(node)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            }

            // Address candidates
            if (node.length > 12 &&
                !KM_REGEX.containsMatchIn(node) &&
                !MIN_REGEX.containsMatchIn(node) &&
                !FARE_REGEX.containsMatchIn(node) &&
                !node.equals("Accept", ignoreCase = true) &&
                !node.equals("Confirm", ignoreCase = true) &&
                !node.startsWith("+")) {
                addressCandidates.add(node)
            }
        }

        val baseFare = fareCandidates.minOrNull() ?: return null

        val vehicleType = when {
            foundVehicleCar  -> VehicleType.CAR
            foundVehicleAuto -> VehicleType.AUTO
            foundVehicleBike -> VehicleType.BIKE
            else             -> VehicleType.UNKNOWN
        }

        // ── DISTANCE RESOLUTION ───────────────────────────────────────────
        var pickupDistanceKm = 0.0
        var rideDistanceKm = 0.0

        when (kmMatches.size) {
            1    -> rideDistanceKm = kmMatches[0]
            2    -> { pickupDistanceKm = kmMatches[0]; rideDistanceKm = kmMatches[1] }
            else -> if (kmMatches.isNotEmpty()) {
                pickupDistanceKm = kmMatches[0]; rideDistanceKm = kmMatches.last()
            }
        }

        val pickupAddress = addressCandidates.getOrElse(0) { "" }
        val dropAddress   = addressCandidates.getOrElse(1) { "" }

        Log.d(TAG, "🔍 Ola parsed: vehicle=$vehicleType fare=₹$baseFare " +
            "bonus=₹$bonusAmount pickup=${pickupDistanceKm}km ride=${rideDistanceKm}km")

        return ParsedRide(
            baseFare = baseFare,
            tipAmount = tipAmount,
            premiumAmount = bonusAmount,
            rideDistanceKm = rideDistanceKm,
            pickupDistanceKm = pickupDistanceKm,
            estimatedDurationMin = durationMin,
            platform = PlatformConfig.get(packageName).displayName,
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
