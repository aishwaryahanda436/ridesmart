package com.ridesmart.service

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.ridesmart.model.ParsedRide
import com.ridesmart.model.ScreenState
import com.ridesmart.model.VehicleType
import com.ridesmart.parser.IPlatformParser
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Uber OCR text parser — reverse engineered from GigU v1.0.55 USUberSanitizer.
 * Adapted for India (₹ currency and km distance).
 *
 * Handles two input paths:
 *  1. parseFromNodes() — text lines from accessibility tree or notification
 *  2. parse(bitmap)    — screenshot OCR via ML Kit (fallback when accessibility is blocked)
 *
 * Uber frequently obfuscates or blocks accessibility node data, so:
 *  - Accessibility is used for *detection* (does a popup exist?)
 *  - OCR is used for *extraction* (what ride data does it contain?)
 */
class UberOcrEngine : IPlatformParser {

    companion object {
        private const val TAG = "RideSmart"
        private const val MIN_OFFER_SIGNALS = 2

        // Screen-level rejection phrases (not an offer card)
        private val SCREEN_REJECT = listOf(
            "you're online", "you are online", "finding trips", "you're offline", "you are offline",
            "going offline", "no requests", "waybill", "trip planner", "driving time"
        )

        // ── GigU clinit blacklist ─────────────────────────────────────────────
        private val SCREEN_BLACKLIST = listOf(
            "copied to", 
            "all caught", "we'll let", "toward your", "let's go",
            "long trip", "multiple stops", "reservation",
            "get priority", "press & hold",
            "you're in a quiet zone"
        )

        // ── GigU method_89611 line blacklist ──────────────────────────────────
        private val FARE_LINE_SKIP = listOf(
            "gigu", "aigu", "uber", "comfort",
            "premier", "auto", "moto", "connect",
            "pool", "intercity", "hourly"
        )

        // ── GigU AbstractC3844b address keywords ─────────────────────────
        private val ADDRESS_KEYWORDS = listOf(
            "st", "street", "ave", "avenue", "rd", "road", "blvd", "boulevard",
            "dr", "drive", "ln", "lane", "cir", "circle", "ct", "court",
            "pl", "place", "sq", "square", "pkwy", "parkway", "ter", "terrace",
            "hwy", "highway", "expy", "expressway", "fwy", "freeway",
            "route", "trail", "loop", "mall", "crescent", "crossing",
            "hospital", "school", "college", "university", "church", "tempel",
            "mosque", "synagogue", "park", "garden", "zoo", "museum",
            "library", "theater", "cinema", "stadium", "arena", "gym",
            "beach", "lake", "river", "airport", "hotel", "metro", "train",
            "nagar", "colony", "sector", "phase", "block", "marg",
            "chowk", "bazar", "bazaar", "market", "complex", "enclave", 
            "vihar", "puram", "ganj", "bagh", "road no", "main road", 
            "cross", "layout", "extension", "town", "city", "air", "trl", "pt", "inn"
        )

        private val ADDRESS_SKIP = listOf(
            "girl", "tools", "tap", "paid", "after", "boost", "near", "not", 
            "well let", "know when", "caught", "uber", "lyft", "busy", "your", 
            "request", "drop-off", "preferences", "become", "available", "are not", 
            "battery", "connecting", "accept", "decline", "km", "min", "away", "$"
        )

        private val PREMIUM_REGEX = Regex("""\+?[₹TtFf]\s*(\d+(?:\.\d{1,2})?)\s*[Pp]remium""")
    }

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override fun detectScreenState(nodes: List<String>): ScreenState {
        val combined = nodes.joinToString(" ").lowercase()
        if (SCREEN_REJECT.any { combined.contains(it) }) return ScreenState.IDLE

        val hasFare = Regex("""(?:₹|Rs\.?)\s*(\d+)""").containsMatchIn(combined)
        val hasButton = combined.contains("match") || combined.contains("confirm") || combined.contains("accept")

        // Detect Trip Radar / ride list screens
        val isTripRadar = combined.contains("trip radar") ||
                          combined.contains("see all requests") ||
                          combined.contains("opportunity")

        if (isTripRadar && hasFare) return ScreenState.TRIP_RADAR

        // Detect multiple fare signals → ride list
        val fareCount = Regex("""(?:₹|Rs\.?)\s*\d+""").findAll(combined).count()
        if (fareCount >= 2 && !hasButton) return ScreenState.RIDE_LIST

        return if (hasFare && hasButton) ScreenState.OFFER_LOADED else ScreenState.IDLE
    }

    override fun parseAll(nodes: List<String>, packageName: String): List<ParsedRide> {
        val screenState = detectScreenState(nodes)

        // For ride lists and Trip Radar, attempt multi-card parsing
        if (screenState == ScreenState.RIDE_LIST || screenState == ScreenState.TRIP_RADAR) {
            val rides = parseMultipleCards(nodes)
            if (rides.isNotEmpty()) return rides
        }

        val ride = parseFromNodes(nodes) ?: return emptyList()
        return listOf(ride)
    }

    /**
     * Parses multiple ride cards from a ride list / Trip Radar screen.
     * Splits nodes into card groups based on fare signal boundaries.
     */
    fun parseMultipleCards(lines: List<String>): List<ParsedRide> {
        if (lines.isEmpty()) return emptyList()

        // Strategy: Split lines into card groups at each fare signal boundary
        val fareRegex = Regex("""(?:₹|Rs\.?)\s*\d+""")
        val cards = mutableListOf<MutableList<String>>()
        var currentCard = mutableListOf<String>()

        for (line in lines) {
            if (fareRegex.containsMatchIn(line) && currentCard.isNotEmpty()) {
                // Check if current card already has a fare — start new card
                val currentHasFare = currentCard.any { fareRegex.containsMatchIn(it) }
                if (currentHasFare) {
                    cards.add(currentCard)
                    currentCard = mutableListOf()
                }
            }
            currentCard.add(line)
        }
        if (currentCard.isNotEmpty()) cards.add(currentCard)

        return cards.mapNotNull { cardLines ->
            parseFromNodes(cardLines)
        }
    }

    fun parseFromNodes(lines: List<String>): ParsedRide? {
        if (lines.isEmpty()) return null
        val combinedRaw = lines.joinToString(" ").lowercase()
        
        for (line in lines) {
            val lower = line.lowercase()
            if (SCREEN_BLACKLIST.any { if (it == "accept") lower == it else lower.contains(it) }) return null
        }

        val identifiers = listOf("uber", "requests", "match", "incentive", "premium", "upfront", "confirm", "cash payment")
        val hasFareSignal = Regex("""(?:₹|Rs\.?)\s*\d+""").containsMatchIn(combinedRaw)
        if (!hasFareSignal && identifiers.none { combinedRaw.contains(it) }) return null

        val fare = extractFare(lines) ?: return null
        val premium = extractPremium(lines)
        val bonus = extractBonus(lines)
        val effectiveFare = fare + bonus
        val (rideDistKm, pickupDistKm, durationMin) = extractTimeAndDistance(lines)
        val (pickupAddr, dropAddr) = extractAddresses(lines)
        val rating = extractRating(ratingLines = lines)
        val vehicleType = extractVehicleType(combinedRaw)

        return ParsedRide(
            baseFare             = effectiveFare,
            rideDistanceKm       = rideDistKm,
            pickupDistanceKm     = pickupDistKm,
            estimatedDurationMin = durationMin,
            platform             = "Uber",
            packageName          = "com.ubercab.driver",
            rawTextNodes          = lines,
            pickupAddress        = pickupAddr,
            dropAddress          = dropAddr,
            riderRating          = rating ?: 0.0,
            paymentType          = if (combinedRaw.contains("cash")) "cash" else "digital",
            premiumAmount        = premium,
            bonus                = bonus,
            fare                 = effectiveFare,
            vehicleType          = vehicleType
        )
    }

    suspend fun parse(bitmap: Bitmap): ParsedRide? {
        // CROP 1: Fare Row — Uber popup is a bottom sheet occupying ~bottom 45% of screen.
        // Target the top area of the bottom sheet where fare is displayed.
        val fareBitmap = crop(bitmap, 0.00, 0.55, 1.00, 0.72)
        val fareText = extractText(fareBitmap) ?: ""
        Log.d(TAG, "UberOCR_FARE: $fareText")
        val fare = extractFare(fareText.lines()) ?: return null
        
        // Validation: reject implausible fares from phantom OCR reads
        if (fare > 2000.0) {
            Log.d(TAG, "🚫 UberOCR rejected implausible fare: $fare")
            return null
        }
        
        // CROP 2: Distance/Time Rows — mid section of the bottom sheet
        val distanceBitmap = crop(bitmap, 0.00, 0.68, 1.00, 0.82)
        val distanceOcrText = extractText(distanceBitmap) ?: ""
        Log.d(TAG, "UberOCR_DIST: $distanceOcrText")
        
        val distTimeRegex = Regex("""(\d+)\s*min\s*(?:\(([\d.]+)\s*km\))?""", RegexOption.IGNORE_CASE)
        val matches = distTimeRegex.findAll(distanceOcrText).toList()
        
        var rideDistanceKm = 0.0
        var pickupDistanceKm = 0.0
        var estimatedDurationMin = 0
        var pickupTimeMin = 0

        if (matches.size >= 2) {
            // First row is pickup, second is ride
            pickupDistanceKm = matches[0].groupValues[2].toDoubleOrNull() ?: 0.0
            pickupTimeMin    = matches[0].groupValues[1].toIntOrNull()    ?: 0
            
            rideDistanceKm       = matches[1].groupValues[2].toDoubleOrNull() ?: 0.0
            estimatedDurationMin = matches[1].groupValues[1].toIntOrNull() ?: 0
        } else if (matches.size == 1) {
            rideDistanceKm       = matches[0].groupValues[2].toDoubleOrNull() ?: 0.0
            estimatedDurationMin = matches[0].groupValues[1].toIntOrNull() ?: 0
        }

        // CROP 3: Addresses & Rating — lower area of the bottom sheet
        val detailsBitmap = crop(bitmap, 0.00, 0.75, 1.00, 0.95)
        val detailsText = extractText(detailsBitmap) ?: ""
        Log.d(TAG, "UberOCR_DETAILS: $detailsText")
        val lines = detailsText.lines()
        
        val rating = extractRating(ratingLines = lines)
        val (pickupAddr, dropAddr) = extractAddresses(lines)
        val bonus = extractBonus(lines)
        val premium = extractPremium(lines)
        
        fareBitmap.recycle()
        distanceBitmap.recycle()
        detailsBitmap.recycle()

        return ParsedRide(
            baseFare             = fare + bonus,
            rideDistanceKm       = rideDistanceKm,
            pickupDistanceKm     = pickupDistanceKm,
            estimatedDurationMin = estimatedDurationMin,
            pickupTimeMin        = pickupTimeMin,
            platform             = "Uber",
            packageName          = "com.ubercab.driver",
            rawTextNodes          = detailsText.lines(),
            pickupAddress        = pickupAddr,
            dropAddress          = dropAddr,
            riderRating          = rating ?: 0.0,
            paymentType          = if (detailsText.lowercase().contains("cash")) "cash" else "digital",
            premiumAmount        = premium,
            bonus                = bonus,
            fare                 = fare + bonus,
            vehicleType          = extractVehicleType(detailsText.lowercase())
        )
    }

    /**
     * Parses a full screenshot for multiple ride cards (Trip Radar / ride list).
     * Uses full-screen OCR and splits by fare signals to detect multiple offers.
     */
    suspend fun parseFullScreen(bitmap: Bitmap): List<ParsedRide> {
        val fullText = extractText(bitmap) ?: return emptyList()
        val lines = fullText.lines().filter { it.isNotBlank() }

        if (lines.isEmpty()) return emptyList()

        // Check if this looks like a multi-ride screen
        val fareRegex = Regex("""(?:₹|Rs\.?)\s*\d+""")
        val fareCount = lines.count { fareRegex.containsMatchIn(it) }

        if (fareCount <= 1) {
            // Single ride — delegate to normal parse
            val ride = parse(bitmap) ?: return emptyList()
            return listOf(ride)
        }

        // Multiple fares detected — split into card groups and parse each
        return parseMultipleCards(lines)
    }

    private fun crop(bitmap: Bitmap, left: Double, top: Double, right: Double, bottom: Double): Bitmap {
        val x = (bitmap.width * left).toInt().coerceIn(0, bitmap.width - 1)
        val y = (bitmap.height * top).toInt().coerceIn(0, bitmap.height - 1)
        val w = (bitmap.width * (right - left)).toInt().coerceIn(1, bitmap.width - x)
        val h = (bitmap.height * (bottom - top)).toInt().coerceIn(1, bitmap.height - y)
        return Bitmap.createBitmap(bitmap, x, y, w, h)
    }

    fun hasOfferSignals(nodes: List<String>): Boolean {
        if (nodes.isEmpty()) return false
        val combined = nodes.joinToString(" ").lowercase()

        if (SCREEN_REJECT.any { combined.contains(it) }) return false

        val signals = listOf(
            "₹", "rs.", "match", "confirm", "accept",
            "min", "km", "away", "trip", "request",
            "upfront", "incentive", "premium", "cash payment",
            "pickup", "drop", "ride", "destination", "see all requests",
            "trip radar", "opportunity", "stacked"
        )
        val matchCount = signals.count { combined.contains(it) }

        val idSignals = nodes.count { it.startsWith("[id:") }
        val totalSignals = matchCount + idSignals

        return totalSignals >= MIN_OFFER_SIGNALS
    }

    private fun extractVehicleType(combinedLower: String): VehicleType = when {
        combinedLower.contains("moto") || combinedLower.contains("bike") -> VehicleType.BIKE
        combinedLower.contains("auto")                                    -> VehicleType.AUTO
        combinedLower.contains("xl") || combinedLower.contains("suv")    -> VehicleType.CAR
        combinedLower.contains("go") || combinedLower.contains("premier") -> VehicleType.CAR
        else                                                               -> VehicleType.UNKNOWN
    }

    private fun extractFare(lines: List<String>): Double? {
        for (rawLine in lines) {
            if (rawLine.trimStart().startsWith("+")) continue
            var line = rawLine
            val lower = line.lowercase()
            if (FARE_LINE_SKIP.any { lower.contains(it) }) continue

            line = line.replace(Regex("""(?<![A-Za-z])[TtFf](?=\d)"""), "₹")
                       .replace(Regex("""(?<![A-Za-z])[TtFf]\s+(?=\d)"""), "₹")
                       .replace(",", "")
                       .replace(Regex("""(?<=\d)[lI]$"""), "")

            if ((line.lowercase().contains("min") || line.lowercase().contains("km") || line.lowercase().contains("away")) && !line.contains("₹")) continue

            val fareRegex = Regex("""(?:₹|Rs\.?)\s*(\d{1,4}(?:\.\d{1,2})?)""")
            val value = fareRegex.find(line)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
            if (value in 25.0..2000.0) return value
        }
        return null
    }

    private fun extractBonus(lines: List<String>): Double {
        val bonusRegex = Regex("""(?:\s|^)\+\s*[₹TtFf]?\s*(\d+(?:\.\d{1,2})?)""")
        for (line in lines) {
            val fixedLine = line.replace(",", "")
            val m = bonusRegex.find(fixedLine) ?: continue
            val v = m.groupValues[1].toDoubleOrNull() ?: continue
            if (v > 0) return v
        }
        return 0.0
    }

    private fun extractPremium(lines: List<String>): Double {
        for (line in lines) {
            val fixedLine = line.replace(",", "")
            val match = PREMIUM_REGEX.find(fixedLine)
            if (match != null) {
                val premium = match.groupValues[1].toDoubleOrNull() ?: 0.0
                if (premium > 0) {
                    Log.d(TAG, "🔍 UberOCR premium=₹${premium}")
                    return premium
                }
            }
        }
        return 0.0
    }

    private fun extractTimeAndDistance(lines: List<String>): Triple<Double, Double, Int> {
        var rideKm = 0.0
        var pickupKm = 0.0
        var rideMin = 0
        var pickupMin = 0

        val pickupPattern = Regex("""(\d+\.?\d*)\s*km.*pickup""", RegexOption.IGNORE_CASE)
        val kmPattern = Regex("""(\d+(?:\.\d{1,2})?)\s*km""", RegexOption.IGNORE_CASE)
        val minPattern = Regex("""(\d+)\s*min""", RegexOption.IGNORE_CASE)

        val allKms = mutableListOf<Double>()
        val allMins = mutableListOf<Int>()

        for (line in lines) {
            val lower = line.lowercase()

            // 1. Broaden pickup search via explicit markers
            if (pickupKm == 0.0) {
                val pm = pickupPattern.find(lower)
                if (pm != null) {
                    pickupKm = pm.groupValues[1].toDoubleOrNull() ?: 0.0
                } else if (lower.contains("away")) {
                    pickupKm = kmPattern.find(lower)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
                }
            }

            // 3. Search full text for distances and times
            kmPattern.findAll(lower).forEach { allKms.add(it.groupValues[1].toDoubleOrNull() ?: 0.0) }
            minPattern.findAll(lower).forEach { allMins.add(it.groupValues[1].toIntOrNull() ?: 0) }
        }

        // 2. Ride distance: store only FIRST match, and handle UI order (pickup is first)
        if (allKms.size >= 2) {
            if (pickupKm == 0.0) {
                pickupKm = allKms[0]
                rideKm = allKms[1]
            } else {
                rideKm = allKms.find { it != pickupKm } ?: 0.0
            }
        } else if (allKms.isNotEmpty()) {
            if (pickupKm == 0.0) rideKm = allKms[0]
            else if (allKms[0] != pickupKm) rideKm = allKms[0]
        }

        // 3. Ride time (rideMin): search full text, handle UI order
        if (allMins.size >= 2) {
            pickupMin = allMins[0]
            rideMin = allMins[1]
        } else if (allMins.isNotEmpty()) {
            rideMin = allMins[0]
        }

        // 4. Log fix verification
        Log.d("RideSmart", "OCR distances: pickup=${pickupKm}km ride=${rideKm}km pickupMin=${pickupMin} rideMin=${rideMin}")

        return Triple(rideKm, pickupKm, rideMin)
    }

    private fun extractAddresses(lines: List<String>): Pair<String, String> {
        val candidates = lines.filter { line ->
            val lower = line.lowercase()
            ADDRESS_KEYWORDS.any { lower.contains(it) } && 
            ADDRESS_SKIP.none { lower.contains(it) }
        }
        
        val pickup = candidates.getOrNull(0) ?: "Unknown Pickup"
        val drop = candidates.getOrNull(candidates.size - 1).takeIf { it != pickup } ?: "Unknown Destination"
        
        return Pair(pickup, drop)
    }

    private fun extractRating(ratingLines: List<String>): Double? {
        val ratingRegex = Regex("""([45]\.\d{1,2})""")
        for (line in ratingLines) {
            val lower = line.lowercase()
            if (lower.contains("km") || lower.contains("min") || lower.contains("away")) continue
            val match = ratingRegex.find(line)
            if (match != null) return match.groupValues[1].toDoubleOrNull()
        }
        return null
    }

    private suspend fun extractText(bitmap: Bitmap): String? = suspendCancellableCoroutine { cont ->
        if (bitmap.width < 32 || bitmap.height < 32) {
            cont.resume("")
            return@suspendCancellableCoroutine
        }
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                cont.resume(visionText.text)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "OCR failed", e)
                cont.resume(null)
            }
    }
}
