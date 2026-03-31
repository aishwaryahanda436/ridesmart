package com.ridesmart.parser

import android.util.Log
import com.ridesmart.model.ParsedRide
import com.ridesmart.model.ParseResult
import com.ridesmart.model.ScreenState
import com.ridesmart.model.VehicleType

class OlaParser : IPlatformParser {

    companion object {
        private const val TAG = "RideSmart"

        private val FARE_REGEX       = Regex("""₹\s*(\d+(?:\.\d{1,2})?)""")
        private val PICKUP_KM_REGEX  = Regex("""(\d+(?:\.\d{1,2})?)\s*km""", RegexOption.IGNORE_CASE)
        private val PICKUP_MIN_REGEX = Regex("""(\d+)\s*min""", RegexOption.IGNORE_CASE)
        private val TRIP_MIN_REGEX   = Regex("""(\d+)\s*min[s]?\s*trip""", RegexOption.IGNORE_CASE)
        private val RIDE_KM_REGEX    = Regex("""(\d+(?:\.\d{1,2})?)\s*km\s*Distance""", RegexOption.IGNORE_CASE)
        private val FALLBACK_KM_REGEX = Regex("""(\d+(?:\.\d{1,2})?)\s*km""", RegexOption.IGNORE_CASE)

        private val OFFER_KEYWORDS = listOf(
            "incoming bookings", "new booking", "ride request",
            "new request", "incoming ride", "booking request"
        )
        private val ACTIVE_RIDE_KEYWORDS = listOf(
            "start ride", "end ride", "arrived", "otp", "on a trip",
            "waiting at pickup", "drop otp", "complete ride",
            "start trip", "end trip", "trip started", "enter otp",
            "navigate to pickup", "navigate to drop"
        )
        
        // Safety range for bike taxi ride distances in India
        private const val MAX_REASONABLE_RIDE_KM = 60.0 
    }

    override fun detectScreenState(nodes: List<String>): ScreenState {
        val combined = nodes.joinToString(" ").lowercase()

        if (ACTIVE_RIDE_KEYWORDS.any { combined.contains(it) }) {
            return ScreenState.ACTIVE_RIDE
        }

        val hasAccept  = combined.contains("accept")
        val hasFare    = combined.contains("₹")
        val hasKm      = combined.contains("km")
        val isOffer    = OFFER_KEYWORDS.any { combined.contains(it) }

        return when {
            isOffer && hasFare && hasKm && hasAccept -> ScreenState.OFFER_LOADED
            isOffer && hasFare                       -> ScreenState.OFFER_LOADING
            hasAccept && hasFare && hasKm            -> ScreenState.OFFER_LOADED
            hasFare                                  -> ScreenState.OFFER_LOADING
            else                                     -> ScreenState.IDLE
        }
    }

    override fun parseAll(nodes: List<String>, packageName: String): ParseResult {
        val screenState = detectScreenState(nodes)
        if (screenState == ScreenState.IDLE) return ParseResult.Idle
        if (screenState == ScreenState.ACTIVE_RIDE) return ParseResult.Failure("Active ride in progress")

        val combined = nodes.joinToString(" ")
        val combinedLower = combined.lowercase()

        // ── FARE ─────────────────────────────────────────────────────
        var fare = 0.0
        for (node in nodes) {
            val trimmed = node.trim()
            if (trimmed.length > 20) continue
            if (trimmed.contains("•")) continue
            if (trimmed.contains("km", ignoreCase = true)) continue
            if (trimmed.contains("min", ignoreCase = true)) continue
            val v = FARE_REGEX.find(trimmed)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
            if (v > 5.0) { fare = v; break }
        }
        if (fare == 0.0) {
            return ParseResult.Failure("Ola: No fare found", confidence = 0.3f)
        }

        // ── PICKUP KM AND MINUTES FROM BULLET NODE ────────────────────
        var pickupKm = 0.0
        var pickupMin = 0
        for (node in nodes) {
            if (node.contains("•") && node.contains("km", ignoreCase = true)) {
                pickupKm  = PICKUP_KM_REGEX.find(node)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
                pickupMin = PICKUP_MIN_REGEX.find(node)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                break
            }
        }

        // ── TRIP DURATION ─────────────────────────────────────────────
        var tripMin = 0
        for (node in nodes) {
            val m = TRIP_MIN_REGEX.find(node)
            if (m != null) { tripMin = m.groupValues[1].toIntOrNull() ?: 0; break }
        }

        // ── RIDE DISTANCE ─────────────────────────────────────────────
        var rideKm = 0.0
        for (node in nodes) {
            val m = RIDE_KM_REGEX.find(node)
            if (m != null) { rideKm = m.groupValues[1].toDoubleOrNull() ?: 0.0; break }
        }
        
        // Fallback with safety guard: 
        // 1. Don't pick up weekly goals (>60km)
        // 2. Don't pick up the pickup distance
        // 3. Prefer values that are NOT preceded by "goal" or "target"
        if (rideKm == 0.0) {
            val kmMatches = FALLBACK_KM_REGEX.findAll(combined).toList()
            rideKm = kmMatches
                .filter { m ->
                    val value = m.groupValues[1].toDoubleOrNull() ?: 0.0
                    val context = combined.substring(0, m.range.first).lowercase()
                    val isGoal = context.contains("goal") || context.contains("target")
                    value > 0.0 && value != pickupKm && value < MAX_REASONABLE_RIDE_KM && !isGoal
                }
                .mapNotNull { it.groupValues[1].toDoubleOrNull() }
                .maxOrNull() ?: 0.0
        }

        if (rideKm <= 0.0) {
            return ParseResult.Failure("Ola: No ride distance found", confidence = 0.4f)
        }

        // ── VEHICLE TYPE ──────────────────────────────────────────────
        val vehicleType = when {
            combinedLower.contains("bike taxi") -> VehicleType.BIKE
            combinedLower.contains("bike")      -> VehicleType.BIKE
            combinedLower.contains("cng auto")  -> VehicleType.CNG_AUTO
            combinedLower.contains("auto")      -> VehicleType.AUTO
            combinedLower.contains("e-bike")    -> VehicleType.EBIKE
            combinedLower.contains("sedan")     -> VehicleType.CAR
            combinedLower.contains("mini")      -> VehicleType.CAR
            combinedLower.contains("car")       -> VehicleType.CAR
            else                                -> VehicleType.BIKE
        }

        // ── PAYMENT TYPE ──────────────────────────────────────────────
        val paymentType = when {
            combinedLower.contains("cash")    -> "cash"
            combinedLower.contains("digital") -> "digital"
            else                              -> "digital"
        }

        // ── ADDRESSES ─────────────────────────────────────────────────
        val addressCandidates = nodes.filter { n ->
            n.length > 20 &&
            !n.contains("₹") &&
            !n.contains("km", ignoreCase = true) &&
            !n.contains("min", ignoreCase = true) &&
            !n.contains("•") &&
            !n.equals("Accept", ignoreCase = true) &&
            !n.equals("TODO", ignoreCase = true) &&
            !n.equals("Bike", ignoreCase = true) &&
            !n.equals("Auto", ignoreCase = true) &&
            !n.contains("PAYMENT", ignoreCase = true) &&
            !n.contains("DUTY", ignoreCase = true) &&
            !OFFER_KEYWORDS.any { n.lowercase().contains(it) }
        }
        val pickupAddress = addressCandidates.getOrElse(0) { "" }
        val dropAddress   = addressCandidates.getOrElse(1) { "" }

        return ParseResult.Success(listOf(
            ParsedRide(
                baseFare             = fare,
                rideDistanceKm       = rideKm,
                pickupDistanceKm     = pickupKm,
                estimatedDurationMin = tripMin,
                platform             = "Ola",
                packageName          = packageName,
                vehicleType          = vehicleType,
                paymentType          = paymentType,
                pickupAddress        = pickupAddress,
                dropAddress          = dropAddress,
                screenState          = screenState,
                rawTextNodes         = nodes
            )
        ))
    }

    fun parseFromNotification(title: String, text: String, packageName: String): ParsedRide? {
        val combined = "$title $text"
        val isOlaOffer = title.contains("booking", ignoreCase = true) ||
                         title.contains("ride", ignoreCase = true) ||
                         title.contains("request", ignoreCase = true)
        if (!isOlaOffer) return null

        val baseFare = FARE_REGEX.find(combined)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        val kmValues = FALLBACK_KM_REGEX.findAll(combined)
            .mapNotNull { it.groupValues[1].toDoubleOrNull() }
            .filter { it > 0.0 && it < MAX_REASONABLE_RIDE_KM }.toList()

        val rideKm   = kmValues.maxOrNull() ?: return null
        val pickupKm = if (kmValues.size >= 2) kmValues.minOrNull() ?: 0.0 else 0.0

        return ParsedRide(
            baseFare = baseFare,
            rideDistanceKm = rideKm, pickupDistanceKm = pickupKm,
            estimatedDurationMin = 0, platform = "Ola", packageName = packageName,
            rawTextNodes = listOf(title, text), vehicleType = VehicleType.BIKE,
            screenState = ScreenState.OFFER_LOADED
        )
    }
}
