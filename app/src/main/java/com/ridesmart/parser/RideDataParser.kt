package com.ridesmart.parser

import android.util.Log
import com.ridesmart.model.ParsedRide
import com.ridesmart.model.ParseResult
import com.ridesmart.model.ScreenState
import com.ridesmart.model.VehicleType

class RideDataParser : IPlatformParser {

    companion object {
        private const val TAG = "RideSmart"

        private val FARE_REGEX = Regex("""₹\s*(\d+(?:\.\d{1,2})?)""")
        private val BONUS_REGEX = Regex("""\+₹\s*(\d+(?:\.\d{1,2})?)""")
        private val KM_REGEX = Regex("""(\d+(?:\.\d{1,2})?)\s*km""", RegexOption.IGNORE_CASE)
        private val MIN_REGEX = Regex("""(\d+)\s*mins?""", RegexOption.IGNORE_CASE)

        private val UBER_HOME_MARKERS = listOf(
            "SEE WEEKLY SUMMARY", "SEE DETAILS", "SEE PROGRESS", "Finding trips",
            "You're online", "Upcoming promotions", "Refer friends and earn",
            "No insights to show", "Cash out and more", "LAST TRIP",
            "Bar Chart", "Next payout", "Points reset", "More ways to earn"
        )
    }

    override fun detectScreenState(nodes: List<String>): ScreenState {
        if (isUberHomeScreen(nodes)) return ScreenState.IDLE
        
        val combined = nodes.joinToString(" ")
        val hasConfirm = nodes.any {
            it.equals("Confirm", ignoreCase = true) ||
            it.equals("Match", ignoreCase = true) ||
            it.equals("Accept", ignoreCase = true)
        }
        val hasFare = FARE_REGEX.containsMatchIn(combined)
        val hasKm = KM_REGEX.containsMatchIn(combined)

        return when {
            hasFare && hasConfirm && hasKm -> ScreenState.OFFER_LOADED
            hasFare && hasConfirm -> ScreenState.OFFER_LOADING
            else -> ScreenState.IDLE
        }
    }

    private fun isUberHomeScreen(nodes: List<String>): Boolean {
        val combined = nodes.joinToString("|")
        val isHomeScreen = UBER_HOME_MARKERS.any { combined.contains(it, ignoreCase = true) }

        val hasQuestText = Regex("Complete \\d+ more trips", RegexOption.IGNORE_CASE)
            .containsMatchIn(combined) ||
            Regex("Collect \\d+ more points", RegexOption.IGNORE_CASE)
            .containsMatchIn(combined)

        return isHomeScreen || hasQuestText
    }

    internal fun parse(nodes: List<String>, packageName: String): ParsedRide? {
        val activeNodes = nodes.filter { it.isNotBlank() }
        if (activeNodes.isEmpty()) return null

        val isUber = packageName.contains("ubercab") || packageName.contains("uber")

        if (isUber) {
            if (isUberHomeScreen(activeNodes)) return null
            val hasConfirm = activeNodes.any {
                it.equals("Confirm", ignoreCase = true) ||
                it.equals("Match", ignoreCase = true) ||
                it.equals("Accept", ignoreCase = true)
            }
            val combinedText = activeNodes.joinToString(" ")
            val hasFareWithDecimal = Regex("₹\\s*\\d+\\.\\d+").containsMatchIn(combinedText)
            val hasKm = KM_REGEX.containsMatchIn(combinedText)
            if (!hasConfirm && !hasFareWithDecimal && !hasKm) return null
        }

        val minFareThreshold = if (isUber) 30.0 else 10.0
        val allFares = activeNodes.mapNotNull { node ->
            if (node.contains("km", ignoreCase = true) || node.contains("+")) null
            else FARE_REGEX.find(node)?.groupValues?.get(1)?.toDoubleOrNull()
        }.filter { it > minFareThreshold }

        val baseFare = when {
            packageName.contains("olacabs") || packageName.contains("ola") -> allFares.minOrNull() ?: 0.0
            packageName.contains("nammayatri") || packageName.contains("juspay") -> allFares.minOrNull() ?: 0.0
            else -> allFares.firstOrNull() ?: 0.0
        }
        if (baseFare == 0.0) return null

        var bonusAmount = 0.0
        activeNodes.forEach { node ->
            val match = BONUS_REGEX.find(node)
            if (match != null) bonusAmount += match.groupValues[1].toDoubleOrNull() ?: 0.0
        }
        
        val kmMatches = activeNodes.mapNotNull { KM_REGEX.find(it)?.groupValues?.get(1)?.toDoubleOrNull() }
        val minMatches = activeNodes.mapNotNull { MIN_REGEX.find(it)?.groupValues?.get(1)?.toIntOrNull() }

        var pickupDistanceKm = 0.0
        var rideDistanceKm = 0.0
        var pickupTimeMin = 0
        var rideTimeMin = 0

        if (isUber) {
            if (kmMatches.size >= 2) {
                // First value is usually the trip length, second is "km away"
                rideDistanceKm = kmMatches[0]
                pickupDistanceKm = kmMatches[1]
            } else if (kmMatches.size == 1) {
                rideDistanceKm = kmMatches[0]
            }
            if (minMatches.size >= 2) {
                // First value is usually the trip duration, second is pickup time
                rideTimeMin = minMatches[0]
                pickupTimeMin = minMatches[1]
            } else if (minMatches.size == 1) {
                rideTimeMin = minMatches[0]
            }
        } else {
            when (kmMatches.size) {
                1 -> rideDistanceKm = kmMatches[0]
                2 -> { pickupDistanceKm = kmMatches[0]; rideDistanceKm = kmMatches[1] }
                else -> if (kmMatches.isNotEmpty()) {
                    pickupDistanceKm = kmMatches[0]
                    rideDistanceKm = kmMatches.last()
                }
            }
        }

        if (isUber && rideDistanceKm == 0.0 && pickupDistanceKm == 0.0) return null

        var pickupAddress = ""
        var dropAddress = ""
        val kmNodeIndices = activeNodes.mapIndexedNotNull { i, node -> if (KM_REGEX.containsMatchIn(node)) i else null }

        if (kmNodeIndices.isNotEmpty()) {
            val firstKmIdx = kmNodeIndices[0]
            for (i in (firstKmIdx + 1) until activeNodes.size) {
                val node = activeNodes[i]
                if (node.length > 12 && !KM_REGEX.containsMatchIn(node) && !FARE_REGEX.containsMatchIn(node)) {
                    pickupAddress = node
                    break
                }
            }
        }

        if (isUber) {
            val cityPatterns = listOf("New Delhi", "Gurugram", "Noida", "Delhi")
            val longNodes = activeNodes.filter { node ->
                node.length > 20 && (cityPatterns.any { node.contains(it, ignoreCase = true) } || node.contains("|") || Regex("\\d{6}").containsMatchIn(node))
            }
            if (longNodes.size >= 2) dropAddress = longNodes.last()
        } else if (kmNodeIndices.size >= 2) {
            val secondKmIdx = kmNodeIndices[1]
            for (i in (secondKmIdx + 1) until activeNodes.size) {
                val node = activeNodes[i]
                if (node.length > 12 && !KM_REGEX.containsMatchIn(node) && !FARE_REGEX.containsMatchIn(node)) {
                    dropAddress = node
                    break
                }
            }
        }

        val ratingRegex = Regex("""^[1-5]\.\d{1,2}$""")
        val riderRating = activeNodes.mapNotNull { node ->
            val cleaned = node.replace("★", "").trim()
            if (ratingRegex.matches(cleaned)) cleaned.toDoubleOrNull() else null
        }.firstOrNull() ?: 0.0

        val paymentKeywords = listOf("cash", "upi", "online", "wallet", "card")
        val paymentType = activeNodes.firstOrNull { node -> paymentKeywords.any { node.contains(it, ignoreCase = true) } } ?: ""

        val durationMinutes = if (isUber) rideTimeMin else activeNodes.mapNotNull { MIN_REGEX.find(it)?.groupValues?.get(1)?.toIntOrNull() }.sum()

        var tipAmount = 0.0
        var premiumAmount = if (isUber) bonusAmount else 0.0
        activeNodes.forEach { node ->
            if (node.contains("Tip", ignoreCase = true)) tipAmount = FARE_REGEX.find(node)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            if (!isUber && node.contains("Premium", ignoreCase = true)) premiumAmount = FARE_REGEX.find(node)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        }

        var vehicleType = VehicleType.UNKNOWN
        val fullText = activeNodes.joinToString(" ")
        when {
            fullText.contains("Moto", ignoreCase = true) || fullText.contains("Bike", ignoreCase = true) -> vehicleType = VehicleType.BIKE
            fullText.contains("Auto", ignoreCase = true) -> vehicleType = VehicleType.AUTO
            else -> vehicleType = VehicleType.CAR
        }

        return ParsedRide(
            baseFare              = baseFare,
            tipAmount             = tipAmount,
            premiumAmount         = premiumAmount,
            rideDistanceKm        = rideDistanceKm,
            pickupDistanceKm      = pickupDistanceKm,
            estimatedDurationMin  = durationMinutes,
            platform              = packageName,
            packageName           = packageName,
            rawTextNodes          = activeNodes,
            pickupAddress         = pickupAddress,
            dropAddress           = dropAddress,
            riderRating           = riderRating,
            paymentType           = paymentType,
            pickupTimeMin         = pickupTimeMin,
            vehicleType           = vehicleType
        )
    }

    override fun parseAll(nodes: List<String>, packageName: String): ParseResult {
        val activeNodes = nodes.filter { it.isNotBlank() }
        val screenState = detectScreenState(activeNodes)
        if (screenState == ScreenState.IDLE) return ParseResult.Idle

        val splitKeywords = listOf("Bike", "Auto", "Moto", "UberGo", "Premier", "XL", "Intercity")
        val cards = mutableListOf<List<String>>()
        var currentCard = mutableListOf<String>()

        for (node in activeNodes) {
            val isVehicleNode = splitKeywords.any { node.contains(it, ignoreCase = true) } && node.length < 30
            if (isVehicleNode && currentCard.isNotEmpty()) {
                cards.add(currentCard)
                currentCard = mutableListOf()
            }
            currentCard.add(node)
        }
        if (currentCard.isNotEmpty()) cards.add(currentCard)

        val results = cards.mapNotNull { parse(it, packageName) }
        return if (results.isNotEmpty()) {
            ParseResult.Success(results)
        } else {
            ParseResult.Failure("No rides parsed for $packageName", confidence = 0.2f)
        }
    }
}
