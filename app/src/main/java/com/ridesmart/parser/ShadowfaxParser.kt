package com.ridesmart.parser

import android.util.Log
import com.ridesmart.model.ParsedRide
import com.ridesmart.model.ParseResult
import com.ridesmart.model.ScreenState
import com.ridesmart.model.VehicleType

class ShadowfaxParser : IPlatformParser {

    companion object {
        private const val TAG = "RideSmart"

        private val FARE_REGEX  = Regex("""₹\s*(\d+(?:\.\d{1,2})?)""")
        private val KM_REGEX    = Regex("""(\d+(?:\.\d{1,2})?)\s*km[s]?""", RegexOption.IGNORE_CASE)
        private val MIN_REGEX   = Regex("""(\d+)\s*min""", RegexOption.IGNORE_CASE)
        private val BOOST_REGEX = Regex("""\+\s*₹\s*(\d+(?:\.\d{1,2})?)""")

        private val ACTIVE_KEYWORDS = listOf(
            "otp", "start delivery", "picked up", "delivered",
            "arrived at pickup", "navigate to drop", "trip started"
        )
    }

    override fun detectScreenState(nodes: List<String>): ScreenState {
        val combined = nodes.joinToString(" ").lowercase()

        if (ACTIVE_KEYWORDS.any { combined.contains(it) }) return ScreenState.ACTIVE_RIDE

        val hasFare   = combined.contains("₹")
        val hasAccept = combined.contains("accept") || combined.contains("choose order")
        val hasKm     = combined.contains("km")

        return when {
            hasFare && hasAccept && hasKm -> ScreenState.OFFER_LOADED
            hasFare && hasKm             -> ScreenState.OFFER_LOADING
            hasFare                      -> ScreenState.OFFER_LOADING
            else                         -> ScreenState.IDLE
        }
    }

    override fun parseAll(nodes: List<String>, packageName: String): ParseResult {
        val screenState = detectScreenState(nodes)
        if (screenState == ScreenState.IDLE) return ParseResult.Idle
        if (screenState == ScreenState.ACTIVE_RIDE) return ParseResult.Failure("Active ride in progress")

        val isDelivery = nodes.any { it.equals("Choose Order", ignoreCase = true) } ||
                         nodes.any { it.contains("Sort By:", ignoreCase = true) } ||
                         nodes.any { it.contains("Package", ignoreCase = true) }

        val ride = if (isDelivery) {
            parseDelivery(nodes, packageName, screenState)
        } else {
            parseBikeTaxi(nodes, packageName, screenState)
        }

        return if (ride != null) {
            ParseResult.Success(listOf(ride))
        } else {
            ParseResult.Failure("Shadowfax: No rides parsed", confidence = 0.3f)
        }
    }

    private fun parseBikeTaxi(
        nodes: List<String>,
        packageName: String,
        screenState: ScreenState
    ): ParsedRide? {
        val combinedLower = nodes.joinToString(" ").lowercase()

        var fare = 0.0
        var boost = 0.0
        for (node in nodes) {
            val trimmed = node.trim()
            if (trimmed.startsWith("+")) {
                val b = BOOST_REGEX.find(trimmed)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
                if (b > 0.0 && boost == 0.0) boost = b
                continue
            }
            if (trimmed.startsWith("*") && trimmed.contains("₹", ignoreCase = false)) {
                val b = FARE_REGEX.find(trimmed)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
                if (b > 0.0 && boost == 0.0) boost = b
                continue
            }
            if (trimmed.contains("+")) continue
            if (trimmed.length > 15) continue
            val v = FARE_REGEX.find(trimmed)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
            if (v > 5.0 && fare == 0.0) fare = v
        }
        if (fare == 0.0) return null

        val kmValues = nodes.mapNotNull { node ->
            val t = node.trim()
            if (t.startsWith("+")) return@mapNotNull null
            if (t.contains("+")) return@mapNotNull null
            KM_REGEX.find(t)?.groupValues?.get(1)?.toDoubleOrNull()
        }
        val pickupKm = if (kmValues.size >= 2) kmValues[0] else 0.0
        val rideKm   = if (kmValues.size >= 2) kmValues[1] else kmValues.firstOrNull() ?: 0.0
        if (rideKm <= 0.0) return null

        val durationMin = nodes.mapNotNull { MIN_REGEX.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            .firstOrNull() ?: 0

        val vehicleType = when {
            combinedLower.contains("bike") -> VehicleType.BIKE
            combinedLower.contains("auto") -> VehicleType.AUTO
            else                           -> VehicleType.BIKE
        }

        val addressCandidates = nodes.filter { n ->
            n.length > 15 &&
            !n.contains("₹") &&
            !n.contains("km", ignoreCase = true) &&
            !n.contains("min", ignoreCase = true) &&
            !n.contains("+") &&
            !n.equals("Accept", ignoreCase = true) &&
            !n.equals("Reject", ignoreCase = true) &&
            !n.equals("Go To", ignoreCase = true) &&
            !n.equals("Bike", ignoreCase = true) &&
            !n.equals("Auto", ignoreCase = true) &&
            !n.equals("Home", ignoreCase = true) &&
            !n.equals("Orders", ignoreCase = true) &&
            !n.contains("OFF", ignoreCase = true)
        }
        val pickupAddress = addressCandidates.getOrElse(0) { "" }
        val dropAddress   = addressCandidates.getOrElse(1) { "" }

        return ParsedRide(
            baseFare = fare, premiumAmount = boost, tipAmount = 0.0,
            rideDistanceKm = rideKm, pickupDistanceKm = pickupKm,
            estimatedDurationMin = durationMin, platform = "Shadowfax",
            packageName = packageName, vehicleType = vehicleType,
            paymentType = "cash", pickupAddress = pickupAddress,
            dropAddress = dropAddress, screenState = screenState, rawTextNodes = nodes
        )
    }

    private fun parseDelivery(
        nodes: List<String>,
        packageName: String,
        screenState: ScreenState
    ): ParsedRide? {
        val combinedLower = nodes.joinToString(" ").lowercase()

        var fare = 0.0
        var boost = 0.0
        for (node in nodes) {
            val trimmed = node.trim()
            if (trimmed.startsWith("+")) {
                val b = BOOST_REGEX.find(trimmed)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
                if (b > 0.0 && boost == 0.0) boost = b
                continue
            }
            if (trimmed.startsWith("*") && trimmed.contains("₹", ignoreCase = false)) {
                val b = FARE_REGEX.find(trimmed)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
                if (b > 0.0 && boost == 0.0) boost = b
                continue
            }
            if (trimmed.contains("taxes", ignoreCase = true)) continue
            val v = FARE_REGEX.find(trimmed)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
            if (v > 5.0 && fare == 0.0) fare = v
        }
        if (fare == 0.0) return null

        var pickupKm = 0.0
        var dropKm   = 0.0
        var pickupAddress = ""
        var dropAddress   = ""

        for (i in nodes.indices) {
            when {
                nodes[i].contains("Pickup", ignoreCase = true) && (nodes[i].contains(":") || nodes.getOrNull(i+1)?.contains("km", true) == true) -> {
                    pickupKm = nodes.getOrNull(i + 1)?.let {
                        KM_REGEX.find(it)?.groupValues?.get(1)?.toDoubleOrNull()
                    } ?: 0.0
                    val next = nodes.getOrElse(i + 2) { "" }
                    pickupAddress = if (next.equals("Low Wait Time", ignoreCase = true))
                        nodes.getOrElse(i + 3) { "" } else next
                }
                nodes[i].contains("Drop", ignoreCase = true) && (nodes[i].contains(":") || nodes.getOrNull(i+1)?.contains("km", true) == true) -> {
                    dropKm = nodes.getOrNull(i + 1)?.let {
                        KM_REGEX.find(it)?.groupValues?.get(1)?.toDoubleOrNull()
                    } ?: 0.0
                    dropAddress = nodes.getOrElse(i + 2) { "" }
                }
            }
        }

        if (pickupKm == 0.0 && dropKm == 0.0) {
            val allKm = KM_REGEX.findAll(nodes.joinToString(" "))
                .mapNotNull { it.groupValues[1].toDoubleOrNull() }
                .filter { it > 0.0 }.toList()
            pickupKm = allKm.minOrNull() ?: 0.0
            dropKm   = allKm.maxOrNull() ?: 0.0
        }

        val rideKm = dropKm
        if (rideKm <= 0.0) return null

        val paymentType = when {
            combinedLower.contains("cod")     -> "cash"
            combinedLower.contains("prepaid") -> "digital"
            combinedLower.contains("cash")    -> "cash"
            else                              -> "digital"
        }

        return ParsedRide(
            baseFare = fare, premiumAmount = boost, tipAmount = 0.0,
            rideDistanceKm = rideKm, pickupDistanceKm = pickupKm,
            estimatedDurationMin = 0, platform = "Shadowfax",
            packageName = packageName, vehicleType = VehicleType.DELIVERY,
            paymentType = paymentType, pickupAddress = pickupAddress,
            dropAddress = dropAddress, screenState = screenState, rawTextNodes = nodes
        )
    }
}
