package com.ridesmart.parser

import android.util.Log
import com.ridesmart.model.ParsedRide
import com.ridesmart.model.ParseResult
import com.ridesmart.model.ScreenState
import com.ridesmart.model.VehicleType

class UberParser : IPlatformParser {

    companion object {
        private const val TAG = "RideSmart"

        private val SCREEN_REJECT = listOf(
            "you're online", "you are online", "finding trips", "you're offline",
            "going offline", "no requests right now", "requests paused"
        )

        private val SCREEN_BLACKLIST = listOf(
            "all caught", "toward your", "get priority",
            "confirming if you're the best match", "no requests right now"
        )

        private val FARE_LINE_SKIP = listOf("gigu", "comfort", "premier", "connect", "pool")
        private val EXTRA_LINE_SKIP = listOf("active hr", "est.", "verified", "included")

        private val ADDRESS_KEYWORDS = listOf(
            "st", "rd", "road", "ave", "blvd", "dr", "ln", "cir", "ct", "pl", "sq", "pkwy",
            "nagar", "colony", "sector", "phase", "block", "marg", "chowk", "bazar", "bazaar",
            "market", "complex", "enclave", "vihar", "puram", "ganj", "bagh", "cross", "layout"
        )

        private val ADDRESS_SKIP = listOf(
            "preferences", "accept", "decline", "km", "min", "away", "payment", "rating"
        )
    }

    override fun detectScreenState(nodes: List<String>): ScreenState {
        val combined = nodes.joinToString(" ").lowercase()
        // Broaden fare detection to include common OCR artifacts and '+' prefix
        // FIX: Removed 'F' and 'r' which caused false positives on words like 'For' or 'Airport'
        val hasFareQuick = Regex("""(?<![a-zA-Z])(?:₹|Rs\.?|Tt|[+])\s*\d+""").containsMatchIn(combined)
        
        if (SCREEN_REJECT.any { combined.contains(it) } && !hasFareQuick) return ScreenState.IDLE
        
        val isMatching = combined.contains("matching") || combined.contains("confirming match")
        val hasButton = combined.contains("accept") || combined.contains("decline") || 
                        combined.contains("confirm") || combined.contains("match")

        return when {
            isMatching           -> ScreenState.OFFER_LOADING
            hasFareQuick && hasButton -> ScreenState.OFFER_LOADED
            hasFareQuick              -> ScreenState.OFFER_LOADING
            else                 -> ScreenState.IDLE
        }
    }

    override fun parseAll(nodes: List<String>, packageName: String): ParseResult {
        Log.d(TAG, "UberParser: Parsing ${nodes.size} nodes")

        val ride = parseFromNodes(nodes)
        return if (ride != null) {
            Log.d(TAG, "UberParser: Successfully extracted Uber ride: ₹${ride.baseFare}")
            ParseResult.Success(listOf(ride))
        } else {
            if (nodes.isNotEmpty()) {
                val dump = nodes.joinToString("|")
                Log.d(TAG, "UberParser: Failed to parse nodes. Dump: ${dump.take(300)}")
            }
            ParseResult.Failure("Uber: No ride parsed from nodes", confidence = 0.1f)
        }
    }

    private fun parseFromNodes(lines: List<String>): ParsedRide? {
        if (lines.isEmpty()) return null
        val combinedRaw = lines.joinToString(" ").lowercase()
        
        for (line in lines) {
            val lower = line.lowercase()
            val blacklisted = SCREEN_BLACKLIST.find { lower.contains(it) }
            if (blacklisted != null) return null
        }

        val identifiers = listOf(
            "uber", "accept", "decline", "trip fare", "match",
            "bike", "cash", "confirm", "package", "delivery", 
            "moto", "digital", "upi", "online", "min", "km", "away"
        )
        if (identifiers.none { combinedRaw.contains(it) }) return null

        // Sum all fare components (Base + Bonus + Premium)
        val fare = extractTotalFare(lines)
        if (fare == null || fare < 10.0) return null

        val (rideDistKm, pickupDistKm, durationMin) = extractTimeAndDistance(lines)
        val (pickupAddr, dropAddr) = extractAddresses(lines)
        val rating = extractRating(lines)
        val vehicleType = extractVehicleType(lines)

        return ParsedRide(
            baseFare             = fare,
            rideDistanceKm       = rideDistKm,
            pickupDistanceKm     = pickupDistKm,
            estimatedDurationMin = durationMin,
            platform             = "Uber",
            packageName          = "com.ubercab.driver",
            rawTextNodes         = lines,
            pickupAddress        = pickupAddr,
            dropAddress          = dropAddr,
            riderRating          = rating,
            paymentType          = if (combinedRaw.contains("cash")) "cash" else "digital",
            premiumAmount        = 0.0, 
            vehicleType          = vehicleType
        )
    }

    private fun extractTotalFare(lines: List<String>): Double? {
        // Updated regex with negative lookbehind to avoid matching 'r' in words like 'Uber' or 'Airport'
        // FIX: Removed 'F', 'Ff', and 'r' as standalone currency symbols to prevent false positives
        val fareRegex = Regex("""(?<![a-zA-Z])(?:₹|Rs\.?|Tt|[+])\s*(\d{1,5}(?:\.\d{1,2})?)""")
        var total = 0.0
        var found = false

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.contains("km", true) || line.contains("min", true)) continue
            
            fareRegex.findAll(line).forEach { m ->
                val v = m.groupValues[1].toDoubleOrNull() ?: 0.0
                if (v in 4.0..9999.0) {
                    total += v
                    found = true
                }
            }
        }
        
        if (!found) {
            for (rawLine in lines) {
                val trimmed = rawLine.trim().replace(",", ".")
                if (trimmed.length in 2..5) {
                    trimmed.toDoubleOrNull()?.let {
                        if (it in 25.0..2500.0 && !rawLine.contains("km", true) && !rawLine.contains("min", true)) {
                            return it
                        }
                    }
                }
            }
        }

        return if (found) total else null
    }

    private fun extractTimeAndDistance(lines: List<String>): Triple<Double, Double, Int> {
        var rideDistKm = 0.0; var pickupDistKm = 0.0; var rideDuration = 0
        
        // Handle potential spaces inside parentheses: "5 min ( 0.9 km )"
        val pattern = Regex("""(\d+)\s*min[s]?\s*\(\s*([0-9]+(?:\.[0-9]+)?)\s*km\s*\)""", RegexOption.IGNORE_CASE)
        val extractedPairs = mutableListOf<Pair<Int, Double>>()

        for (rawLine in lines) {
            val line = fixOcrErrors(rawLine)
            
            if (line.lowercase().contains("pickup") || line.lowercase().contains("away")) {
                Regex("""([0-9]+(?:\.[0-9]+)?)\s*km""").find(line)?.let {
                    pickupDistKm = it.groupValues[1].toDoubleOrNull() ?: 0.0
                }
            }

            pattern.find(line)?.let { m ->
                val t = m.groupValues[1].toIntOrNull() ?: 0
                val d = m.groupValues[2].toDoubleOrNull() ?: 0.0
                if (d > 0) extractedPairs.add(Pair(t, d))
            }
        }

        // Logic based on Uber's vertical flow: 1st is Pickup, 2nd is Ride
        if (extractedPairs.size >= 2) {
            pickupDistKm = extractedPairs[0].second
            rideDistKm   = extractedPairs[1].second
            rideDuration = extractedPairs[1].first
        } else if (extractedPairs.size == 1) {
            if (pickupDistKm == 0.0) {
                if (extractedPairs[0].first < 10 && extractedPairs[0].second < 3.5) {
                    pickupDistKm = extractedPairs[0].second
                } else {
                    rideDistKm = extractedPairs[0].second
                    rideDuration = extractedPairs[0].first
                }
            } else {
                rideDistKm = extractedPairs[0].second
                rideDuration = extractedPairs[0].first
            }
        }

        return Triple(rideDistKm, pickupDistKm, rideDuration)
    }

    private fun extractVehicleType(lines: List<String>): VehicleType {
        val combined = lines.joinToString(" ").lowercase()
        return when {
            combined.contains("moto") || combined.contains("bike") -> VehicleType.BIKE
            combined.contains("auto") -> VehicleType.AUTO
            else -> VehicleType.CAR
        }
    }

    private fun extractRating(lines: List<String>): Double {
        val ratingRegex = Regex("""([1-5][.,][0-9]{1,2})""")
        for (line in lines) {
            val fixed = line.lowercase().replace('i', '1').replace('l', '1').replace('a', '4').replace(',', '.')
            ratingRegex.find(fixed)?.let { return it.groupValues[1].toDoubleOrNull()?.takeIf { v -> v in 1.0..5.0 } ?: 0.0 }
        }
        return 0.0
    }

    private fun extractAddresses(lines: List<String>): Pair<String, String> {
        var firstFound = ""; var secondFound = ""
        for (line in lines.reversed()) {
            val lower = line.lowercase()
            if (ADDRESS_SKIP.any { lower.contains(it) } || line.length < 8) continue
            if (ADDRESS_KEYWORDS.any { lower.contains(it) } || line.length >= 18) {
                if (firstFound.isEmpty()) firstFound = line else if (secondFound.isEmpty()) secondFound = line
                if (firstFound.isNotEmpty() && secondFound.isNotEmpty()) break
            }
        }
        return Pair(secondFound, firstFound)
    }

    private fun fixOcrErrors(line: String): String = line.replace("krn", "km").replace("knn", "km")
        .replace("lmin", "1min").replace("l min", "1 min")
        .replace("Tt", "₹")
        .replace(",", ".")
}
