package com.ridesmart.parser

import android.util.Log
import com.ridesmart.model.ParsedRide
import com.ridesmart.model.ScreenState
import com.ridesmart.model.VehicleType
import com.ridesmart.model.PlatformConfig

class RideDataParser : IPlatformParser {

    companion object {
        private const val TAG = "RideDataParser"

        // Regex for Fare: supports "₹45.83", "₹ 123"
        private val FARE_REGEX = Regex("""₹\s*(\d+(?:\.\d{1,2})?)""")

        // Regex for Bonus/Premium: matches "+₹8.00"
        private val BONUS_REGEX = Regex("""\+₹\s*(\d+(?:\.\d{1,2})?)""")

        // Regex for Distance: "4.2 km", "(0.5km)"
        private val KM_REGEX = Regex("""(\d+(?:\.\d{1,2})?)\s*km""", RegexOption.IGNORE_CASE)

        // Regex for Duration: "12 min", "13 mins"
        private val MIN_REGEX = Regex("""(\d+)\s*mins?""", RegexOption.IGNORE_CASE)

        private val UBER_HOME_MARKERS = listOf(
            "SEE WEEKLY SUMMARY",
            "SEE DETAILS",
            "SEE PROGRESS",
            "Finding trips",
            "You're online",
            "You're Online",
            "Upcoming promotions",
            "Refer friends and earn",
            "No insights to show",
            "Cash out and more",
            "LAST TRIP",
            "Bar Chart",
            "Next payout",
            "Points reset",
            "More ways to earn"
        )

        // Pre-compiled to avoid regex compilation cost on every detectScreenState call.
        private val QUEST_TRIPS_REGEX  = Regex("Complete \\d+ more trips", RegexOption.IGNORE_CASE)
        private val QUEST_POINTS_REGEX = Regex("Collect \\d+ more points", RegexOption.IGNORE_CASE)

        private val RATING_REGEX = Regex("""^[1-5]\.\d{1,2}$""")

        private val PAYMENT_KEYWORDS = listOf("cash", "upi", "online", "wallet", "card")
    }

    override fun detectScreenState(nodes: List<String>): ScreenState {
        if (isUberHomeScreen(nodes)) return ScreenState.IDLE
        
        val combined = nodes.joinToString(" ")
        val combinedLower = combined.lowercase()
        val hasConfirm = nodes.any {
            it.equals("Confirm", ignoreCase = true) ||
            it.equals("Match", ignoreCase = true) ||
            it.equals("Accept", ignoreCase = true)
        }
        val hasFare = FARE_REGEX.containsMatchIn(combined)
        val hasKm = KM_REGEX.containsMatchIn(combined)

        // Detect Trip Radar / ride list screens
        val isTripRadar = combinedLower.contains("trip radar") ||
                          combinedLower.contains("see all requests") ||
                          combinedLower.contains("opportunity")
        if (isTripRadar && hasFare) return ScreenState.TRIP_RADAR

        // Detect multiple fare signals → ride list
        val fareCount = FARE_REGEX.findAll(combined).count()
        if (fareCount >= 2 && !hasConfirm) return ScreenState.RIDE_LIST

        return when {
            hasFare && hasConfirm && hasKm -> ScreenState.OFFER_LOADED
            hasFare && hasConfirm -> ScreenState.OFFER_LOADING
            else -> ScreenState.IDLE
        }
    }

    fun isUberHomeScreen(nodes: List<String>): Boolean {
        val combined = nodes.joinToString("|")
        val isHomeScreen = UBER_HOME_MARKERS.any { combined.contains(it, ignoreCase = true) }
        val hasQuestText = QUEST_TRIPS_REGEX.containsMatchIn(combined) ||
                           QUEST_POINTS_REGEX.containsMatchIn(combined)
        return isHomeScreen || hasQuestText
    }

    fun parse(nodes: List<String>, packageName: String): ParsedRide? {
        val activeNodes = nodes.filter { it.isNotBlank() }
        if (activeNodes.isEmpty()) return null

        val isUber = packageName.contains("ubercab") || packageName.contains("uber")

        // ── UBER HOME SCREEN GUARD ──────────────────────────────────
        if (isUber) {
            if (isUberHomeScreen(activeNodes)) {
                Log.d("RideSmart", "🚫 UBER HOME SCREEN — skipping, not a ride offer")
                return null
            }

            val hasConfirm = activeNodes.any {
                it.equals("Confirm", ignoreCase = true) ||
                it.equals("Match", ignoreCase = true) ||
                it.equals("Accept", ignoreCase = true)
            }
            val combinedText = activeNodes.joinToString(" ")
            val hasFareWithDecimal = Regex("₹\\s*\\d+\\.\\d+").containsMatchIn(combinedText)
            val hasKm = KM_REGEX.containsMatchIn(combinedText)

            if (!hasConfirm && !hasFareWithDecimal && !hasKm) {
                return null
            }
        }

        // ── EXTRACT FARE ─────────────────────────────────────────────────
        val minFareThreshold = if (isUber) 30.0 else 10.0
        val allFares = activeNodes.mapNotNull { node ->
            if (node.contains("km", ignoreCase = true) || node.contains("+")) null
            else FARE_REGEX.find(node)?.groupValues?.get(1)?.toDoubleOrNull()
        }.filter { it > minFareThreshold }

        val baseFare = when {
            packageName.contains("olacabs") -> 
                allFares.minOrNull() ?: 0.0
            packageName.contains("nammayatri") || packageName.contains("juspay") -> 
                allFares.minOrNull() ?: 0.0
            else -> allFares.firstOrNull() ?: 0.0
        }

        if (baseFare == 0.0) return null

        var bonusAmount = 0.0
        activeNodes.forEach { node ->
            val match = BONUS_REGEX.find(node)
            if (match != null) {
                bonusAmount += match.groupValues[1].toDoubleOrNull() ?: 0.0
            }
        }
        
        val effectiveFare = if (isUber) baseFare + bonusAmount else baseFare
        if (isUber && bonusAmount > 0) {
            Log.d(TAG, "💰 Premium bonus ₹$bonusAmount added → effective ₹$effectiveFare")
        }

        // ── EXTRACT DISTANCES & TIMES ────────────────────────────────────
        val kmMatches = activeNodes.mapNotNull { node ->
            KM_REGEX.find(node)?.groupValues?.get(1)?.toDoubleOrNull()
        }
        val minMatches = activeNodes.mapNotNull { node ->
            MIN_REGEX.find(node)?.groupValues?.get(1)?.toIntOrNull()
        }

        var pickupDistanceKm = 0.0
        var rideDistanceKm = 0.0
        var pickupTimeMin = 0
        var rideTimeMin = 0

        if (isUber) {
            if (kmMatches.size >= 2) {
                pickupDistanceKm = kmMatches[0]
                rideDistanceKm = kmMatches[1]
            } else if (kmMatches.size == 1) {
                rideDistanceKm = kmMatches[0]
            }

            if (minMatches.size >= 2) {
                pickupTimeMin = minMatches[0]
                rideTimeMin = minMatches[1]
            } else if (minMatches.size == 1) {
                rideTimeMin = minMatches[0]
            }
        } else {
            when (kmMatches.size) {
                1 -> rideDistanceKm = kmMatches[0]
                2 -> {
                    pickupDistanceKm = kmMatches[0]
                    rideDistanceKm = kmMatches[1]
                }
                else -> if (kmMatches.isNotEmpty()) {
                    pickupDistanceKm = kmMatches[0]
                    rideDistanceKm = kmMatches.last()
                }
            }
        }

        if (isUber && rideDistanceKm == 0.0 && pickupDistanceKm == 0.0) {
            return null
        }

        // ── EXTRACT ADDRESSES ────────────────────────────────────────────
        var pickupAddress = ""
        var dropAddress = ""

        val kmNodeIndices = activeNodes.mapIndexedNotNull { i, node ->
            if (KM_REGEX.containsMatchIn(node)) i else null
        }

        if (kmNodeIndices.isNotEmpty()) {
            val firstKmIdx = kmNodeIndices[0]
            for (i in (firstKmIdx + 1) until activeNodes.size) {
                val node = activeNodes[i]
                if (node.length > 12 && 
                    !KM_REGEX.containsMatchIn(node) && 
                    !FARE_REGEX.containsMatchIn(node) &&
                    !node.contains("Premium", ignoreCase = true)) {
                    pickupAddress = node
                    break
                }
            }
        }

        if (isUber) {
            // Comprehensive Indian city list for address detection
            val cityPatterns = listOf(
                // Delhi NCR
                "New Delhi", "Delhi", "Gurugram", "Gurgaon", "Noida", "Faridabad", "Ghaziabad",
                // Major metros
                "Mumbai", "Pune", "Bengaluru", "Bangalore", "Hyderabad", "Chennai", "Kolkata",
                "Ahmedabad", "Surat", "Jaipur", "Lucknow", "Kanpur", "Nagpur", "Indore",
                "Bhopal", "Patna", "Visakhapatnam", "Vadodara", "Chandigarh", "Coimbatore",
                "Kochi", "Thiruvananthapuram", "Bhubaneswar", "Agra", "Varanasi",
                // Common address keywords that appear in Indian addresses
                "Nagar", "Colony", "Road", "Street", "Layout", "Extension", "Sector", "Phase",
                "Block", "Area", "Cross", "Main", "Circle", "Junction", "Chowk", "Marg"
            )

            val longNodes = activeNodes.filter { node ->
                node.length > 15 &&
                (cityPatterns.any { node.contains(it, ignoreCase = true) } || 
                 node.contains("|") || 
                 Regex("\\d{6}").containsMatchIn(node) || // 6-digit PIN code
                 Regex("\\d+[,\\s]+\\w").containsMatchIn(node)) // starts with house number
            }
            if (longNodes.size >= 2) {
                dropAddress = longNodes.last()
            }
        } else if (kmNodeIndices.size >= 2) {
            val secondKmIdx = kmNodeIndices[1]
            for (i in (secondKmIdx + 1) until activeNodes.size) {
                val node = activeNodes[i]
                if (node.length > 12 && 
                    !KM_REGEX.containsMatchIn(node) && 
                    !FARE_REGEX.containsMatchIn(node) &&
                    !node.contains("Premium", ignoreCase = true)) {
                    dropAddress = node
                    break
                }
            }
        }

        // ── EXTRACT RIDER RIDER RATING ───────────────────────────────────
        val riderRating = activeNodes.mapNotNull { node ->
            val cleaned = node.replace("★", "").trim()
            if (RATING_REGEX.matches(cleaned)) cleaned.toDoubleOrNull() else null
        }.firstOrNull() ?: 0.0

        // ── EXTRACT PAYMENT TYPE ─────────────────────────────────────────
        val paymentType = activeNodes.firstOrNull { node ->
            PAYMENT_KEYWORDS.any { node.contains(it, ignoreCase = true) }
        } ?: ""

        // ── EXTRACT DURATION ─────────────────────────────────────────────
        val durationMinutes = if (isUber) rideTimeMin else {
            activeNodes.mapNotNull { node ->
                MIN_REGEX.find(node)?.groupValues?.get(1)?.toIntOrNull()
            }.maxOrNull() ?: 0
        }

        // ── EXTRACT SECONDARY FARES (Tips/Premiums) ──────────────────────
        var tipAmount = 0.0
        var premiumAmount = if (isUber) bonusAmount else 0.0
        activeNodes.forEach { node ->
            if (node.contains("Tip", ignoreCase = true)) {
                tipAmount = FARE_REGEX.find(node)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            }
            if (!isUber && node.contains("Premium", ignoreCase = true)) {
                premiumAmount = FARE_REGEX.find(node)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            }
        }

        // ── DETECT VEHICLE TYPE ──────────────────────────────────────────
        var vehicleType = VehicleType.UNKNOWN
        val fullText = activeNodes.joinToString(" ")
        when {
            fullText.contains("Moto", ignoreCase = true) || fullText.contains("Bike", ignoreCase = true) -> 
                vehicleType = VehicleType.BIKE
            fullText.contains("Auto", ignoreCase = true) -> 
                vehicleType = VehicleType.AUTO
            else -> vehicleType = VehicleType.CAR
        }

        return ParsedRide(
            baseFare              = baseFare,
            tipAmount             = tipAmount,
            premiumAmount         = premiumAmount,
            rideDistanceKm        = rideDistanceKm,
            pickupDistanceKm      = pickupDistanceKm,
            estimatedDurationMin  = durationMinutes,
            platform              = PlatformConfig.get(packageName).displayName,
            packageName           = packageName,
            rawTextNodes          = activeNodes,
            pickupAddress         = pickupAddress,
            dropAddress           = dropAddress,
            riderRating           = riderRating,
            paymentType           = paymentType,
            pickupTimeMin         = pickupTimeMin,
            rideTimeMin           = rideTimeMin,
            bonus                 = bonusAmount,
            vehicleType           = vehicleType
        )
    }

    override fun parseAll(nodes: List<String>, packageName: String): List<ParsedRide> {
        val activeNodes = nodes.filter { it.isNotBlank() }
        
        // Split logic: Uber cards often start with vehicle name or "See all requests"
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

        return cards.mapNotNull { parse(it, packageName) }
    }
}
