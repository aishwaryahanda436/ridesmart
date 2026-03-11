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
        private const val OFFER_CROP_RATIO = 0.60
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
            "hospital", "school", "college", "university", "church", "temple",
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
        val hasButton = combined.contains("match") || combined.contains("confirm")
        
        return if (hasFare && hasButton) ScreenState.OFFER_LOADED else ScreenState.IDLE
    }

    override fun parseAll(nodes: List<String>, packageName: String): List<ParsedRide> {
        val ride = parseFromNodes(nodes) ?: return emptyList()
        return listOf(ride)
    }

    fun parseFromNodes(lines: List<String>): ParsedRide? {
        if (lines.isEmpty()) return null
        val combinedRaw = lines.joinToString(" ").lowercase()
        
        for (line in lines) {
            val lower = line.lowercase()
            if (SCREEN_BLACKLIST.any { if (it == "accept") lower == it else lower.contains(it) }) return null
        }

        val identifiers = listOf("uber", "requests", "match", "incentive", "premium", "upfront", "confirm", "cash payment")
        if (identifiers.none { combinedRaw.contains(it) }) return null

        val fare = extractFare(lines) ?: return null
        val premium = extractPremium(lines)
        val bonus = extractBonus(lines)
        val effectiveFare = fare + bonus
        val (rideDistKm, pickupDistKm, durationMin) = extractTimeAndDistance(lines)
        val (pickupAddr, dropAddr) = extractAddresses(lines)
        val rating = extractRating(lines)
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
        val cropped = cropOfferRegion(bitmap)
        val target = cropped ?: bitmap
        val rawText = extractText(target) ?: run {
            cropped?.recycle()
            return null
        }
        cropped?.recycle()
        if (rawText.isBlank()) return null
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotBlank() }
        return parseFromNodes(lines)
    }

    fun hasOfferSignals(nodes: List<String>): Boolean {
        if (nodes.isEmpty()) return false
        val combined = nodes.joinToString(" ").lowercase()

        if (SCREEN_REJECT.any { combined.contains(it) }) return false

        val signals = listOf(
            "₹", "rs.", "match", "confirm", "accept",
            "min", "km", "away", "trip", "request",
            "upfront", "incentive", "premium", "cash payment",
            "pickup", "drop", "ride", "destination", "see all requests"
        )
        var matchCount = signals.count { combined.contains(it) }

        val idSignals = nodes.count { it.startsWith("[id:") }
        matchCount += idSignals

        return matchCount >= MIN_OFFER_SIGNALS
    }

    fun cropOfferRegion(bitmap: Bitmap): Bitmap? {
        return try {
            val offerHeight = (bitmap.height * OFFER_CROP_RATIO).toInt()
            if (offerHeight <= 0 || bitmap.width <= 0) return null
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, offerHeight)
        } catch (e: Exception) {
            Log.d(TAG, "📸 Bitmap crop failed: ${e.message}")
            null
        }
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
                       .replace(",", ".")
                       .replace(Regex("""(?<=\d)[lI]$"""), "")

            if ((line.lowercase().contains("min") || line.lowercase().contains("km") || line.lowercase().contains("away")) && !line.contains("₹")) continue

            val fareRegex = Regex("""(?:₹|Rs\.?)\s*(\d{1,5}(?:\.\d{1,2})?)""")
            val value = fareRegex.find(line)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
            if (value in 25.0..9999.0) return value
        }
        return null
    }

    private fun extractBonus(lines: List<String>): Double {
        val bonusRegex = Regex("""(?:\s|^)\+\s*[₹TtFf]?\s*(\d+(?:\.\d{1,2})?)""")
        for (line in lines) {
            val fixedLine = line.replace(",", ".")
            val m = bonusRegex.find(fixedLine) ?: continue
            val v = m.groupValues[1].toDoubleOrNull() ?: continue
            if (v > 0) return v
        }
        return 0.0
    }

    private fun extractPremium(lines: List<String>): Double {
        for (line in lines) {
            val fixedLine = line.replace(",", ".")
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
        var rideDist = 0.0
        var pickupDist = 0.0
        var duration = 0
        
        val distRegex = Regex("""(\d+(?:\.\d{1,2})?)\s*km""")
        val timeRegex = Regex("""(\d+)\s*min""")
        
        for (line in lines) {
            val lower = line.lowercase()
            if (lower.contains("away")) {
                pickupDist = distRegex.find(lower)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            } else if (distRegex.containsMatchIn(lower)) {
                rideDist = distRegex.find(lower)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            }
            
            if (timeRegex.containsMatchIn(lower)) {
                val mins = timeRegex.find(lower)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                if (mins > duration) duration = mins
            }
        }
        return Triple(rideDist, pickupDist, duration)
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

    private fun extractRating(lines: List<String>): Double? {
        val ratingRegex = Regex("""([45]\.\d{1,2})""")
        for (line in lines) {
            val match = ratingRegex.find(line)
            if (match != null) return match.groupValues[1].toDoubleOrNull()
        }
        return null
    }

    private suspend fun extractText(bitmap: Bitmap): String? = suspendCancellableCoroutine { cont ->
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
