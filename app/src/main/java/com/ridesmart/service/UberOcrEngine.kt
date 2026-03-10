package com.ridesmart.service

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.ridesmart.model.ParsedRide
import com.ridesmart.model.ScreenState
import com.ridesmart.parser.IPlatformParser
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
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
        private const val OCR_TIMEOUT_MS = 1500L
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

        // ── GigU method_89614 boost/extra line blacklist ──────────────────────
        private val EXTRA_LINE_SKIP = listOf(
            "active hr", "est.", "est,",
            "verified", "included", "boost", "bost+", "t+", "destination"
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
        // UberOcrEngine's primary entry point is parse(Bitmap), 
        // but it can implement parseAll for compatibility.
        val ride = parseFromNodes(nodes) ?: return emptyList()
        return listOf(ride)
    }

    fun parseFromNodes(lines: List<String>): ParsedRide? {
        if (lines.isEmpty()) return null
        val combinedRaw = lines.joinToString(" ").lowercase()
        
        // STEP 1: Blacklist Check
        for (line in lines) {
            val lower = line.lowercase()
            if (SCREEN_BLACKLIST.any { if (it == "accept") lower == it else lower.contains(it) }) return null
        }

        val identifiers = listOf("uber", "requests", "match", "incentive", "premium", "upfront", "confirm", "cash payment")
        if (identifiers.none { combinedRaw.contains(it) }) return null

        val fare = extractFare(lines) ?: return null
        val bonus = extractBonus(lines)
        val effectiveFare = fare + bonus
        val (rideDistKm, pickupDistKm, durationMin) = extractTimeAndDistance(lines)
        val (pickupAddr, dropAddr) = extractAddresses(lines)
        val rating = extractRating(lines)

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
            riderRating          = rating,
            paymentType          = if (combinedRaw.contains("cash")) "cash" else "digital",
            premiumAmount        = bonus,
            bonus                = bonus,
            fare                 = effectiveFare
        )
    }

    suspend fun parse(bitmap: Bitmap): ParsedRide? {
        // Crop to the offer popup region (top 60% of screen) to reduce
        // OCR latency and avoid parsing status bar / navigation bar text.
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

    /**
     * Quick signal check — returns true if the text nodes contain enough
     * Uber offer indicators to warrant a full OCR screenshot, even when
     * the accessibility tree is mostly obfuscated.
     *
     * This is the "detection" half of the hybrid approach:
     *  - accessibility detects the popup  →  hasOfferSignals() == true
     *  - OCR extracts the actual data     →  parse(bitmap) returns ParsedRide
     */
    fun hasOfferSignals(nodes: List<String>): Boolean {
        if (nodes.isEmpty()) return false
        val combined = nodes.joinToString(" ").lowercase()

        // Reject known non-offer screens first
        if (SCREEN_REJECT.any { combined.contains(it) }) return false

        // Offer indicators — even partial data from obfuscated trees
        val signals = listOf(
            "₹", "rs.", "match", "confirm", "accept",
            "min", "km", "away", "trip", "request",
            "upfront", "incentive", "premium", "cash payment",
            "pickup", "drop", "ride", "destination", "see all requests"
        )
        var matchCount = signals.count { combined.contains(it) }

        // View ID signals from obfuscated Uber nodes (e.g. "[id:fare_text]")
        val idSignals = nodes.count { it.startsWith("[id:") }
        matchCount += idSignals

        return matchCount >= MIN_OFFER_SIGNALS
    }

    /**
     * Crops the bitmap to the top 60% of the screen where Uber ride
     * offer popups typically appear. This reduces OCR processing time
     * by ~40% and avoids false matches from navigation/status bars.
     */
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
        val bonusRegex = Regex("""(?:\s|^)\+\s*[₹\u20b9TtFf]?\s*(\d+(?:\.\d{1,2})?)""")
        for (line in lines) {
            val fixedLine = line.replace(",", ".")
            val m = bonusRegex.find(fixedLine) ?: continue
            val v = m.groupValues[1].toDoubleOrNull() ?: continue
            if (v > 0) return v
        }
        return 0.0
    }

    private fun extractTimeAndDistance(lines: List<String>): Triple<Double, Double, Int> {
        var rideDist = 0.0; var pickupDist = 0.0
        var rideDuration = 0
        var foundFirst = false

        // Primary pattern: "5 mins (4.2 km)" — parenthesized distance after time
        val pattern = Regex(
            """(\d+)\s*min[s]?\s*[(\[]\s*([0-9]+(?:\.[0-9]+)?)\s*km""",
            RegexOption.IGNORE_CASE
        )

        // Secondary pattern: standalone "4.2 km" — distance without time context
        val standaloneKm = Regex(
            """([0-9]+(?:\.[0-9]+)?)\s*km""",
            RegexOption.IGNORE_CASE
        )

        // Tertiary pattern: "3 min away" — pickup time without distance
        val awayMinPattern = Regex(
            """(\d+)\s*min[s]?\s*away""",
            RegexOption.IGNORE_CASE
        )

        for (rawLine in lines) {
            val line = fixOcrErrors(rawLine)
            val lower = line.lowercase()
            if (EXTRA_LINE_SKIP.any { lower.contains(it) }) continue

            pattern.find(line)?.let { m ->
                val t = m.groupValues[1].toIntOrNull() ?: 0
                val d = m.groupValues[2].toDoubleOrNull() ?: 0.0
                if (!foundFirst) {
                    pickupDist = d
                    foundFirst = true
                } else {
                    rideDuration = t
                    rideDist = d
                }
            }

            if (lower.contains("away") && pickupDist == 0.0) {
                standaloneKm.find(line)?.let {
                    pickupDist = it.groupValues[1].toDoubleOrNull() ?: pickupDist
                }
                awayMinPattern.find(line)?.let {
                    // Store pickup time in rideDuration temporarily if no ride duration yet
                }
            }
        }

        // Fallback: if primary pattern found nothing, try standalone km values
        if (rideDist == 0.0 && pickupDist == 0.0) {
            val kmValues = mutableListOf<Double>()
            for (rawLine in lines) {
                val line = fixOcrErrors(rawLine)
                val lower = line.lowercase()
                if (EXTRA_LINE_SKIP.any { lower.contains(it) }) continue
                standaloneKm.find(line)?.let { m ->
                    val km = m.groupValues[1].toDoubleOrNull()
                    if (km != null && km > 0.0) kmValues.add(km)
                }
            }
            when (kmValues.size) {
                1 -> rideDist = kmValues[0]
                else -> if (kmValues.size >= 2) {
                    pickupDist = kmValues[0]
                    rideDist = kmValues[1]
                }
            }

            // Also try to extract standalone duration
            if (rideDuration == 0) {
                for (rawLine in lines) {
                    val line = fixOcrErrors(rawLine)
                    val lower = line.lowercase()
                    if (EXTRA_LINE_SKIP.any { lower.contains(it) }) continue
                    if (lower.contains("away")) continue  // skip pickup time
                    Regex("""(\d+)\s*min""", RegexOption.IGNORE_CASE).find(line)?.let {
                        rideDuration = it.groupValues[1].toIntOrNull() ?: 0
                    }
                }
            }
        }

        return Triple(rideDist, pickupDist, rideDuration)
    }

    private fun extractRating(lines: List<String>): Double {
        val ratingRegex = Regex("""([1-5][.,][0-9]{1,2})""")
        for (line in lines) {
            val fixed = if (line.contains("★") || line.length <= 8) {
                line.lowercase().replace('i', '1').replace('l', '1').replace('a', '4').replace('o', '0').replace('g', '6').replace('&', '8').replace(',', '.')
            } else {
                line.lowercase()
            }
            ratingRegex.find(fixed)?.let { return it.groupValues[1].toDoubleOrNull()?.takeIf { v -> v in 1.0..5.0 } ?: 0.0 }
        }
        return 0.0
    }

    private fun extractAddresses(lines: List<String>): Pair<String, String> {
        var firstFound = ""; var secondFound = ""
        for (line in lines.reversed()) {
            val lower = line.lowercase()
            if (ADDRESS_SKIP.any { lower.contains(it) } || line.length < 6 || line.all { it.isDigit() || it in "., " }) continue
            if (ADDRESS_KEYWORDS.any { lower.contains(Regex("\\b${Regex.escape(it)}\\b")) } || line.length >= 15) {
                if (firstFound.isEmpty()) firstFound = line 
                else if (secondFound.isEmpty()) secondFound = line 
                else secondFound = "$line $secondFound"
                if (firstFound.isNotEmpty() && secondFound.length > 20) break
            }
        }
        return Pair(secondFound, firstFound)
    }

    private fun fixOcrErrors(line: String): String = line
        .replace("krn", "km")
        .replace("knn", "km")
        .replace("lmin", "1min")
        .replace("l min", "1 min")
        .replace("mnin", "min")
        .replace("rmin", "min")
        .replace("miin", "min")
        .replace("rnin", "min")
        .replace("mlns", "mins")
        .replace("Kms", "km")
        .replace("Km", "km")
        .replace("KM", "km")
        .replace(Regex("""(?<=\d)l(?=\d)"""), "1")
        .replace(Regex("""(?<=\d)l(?=\s*min)"""), "1")
        .replace(Regex("""(?<=\d)O(?=\d)"""), "0")
        .replace(Regex("""(?<=\d)A(?=\d)"""), "4")
        .replace(Regex("""^\.\s*([89])\s*km"""), "0.$1 km")
        .replace(Regex("""\(\.\s*([0-9])\s*km\)"""), "(0.$1 km)")
        .replace(Regex("""\s{2,}"""), " ")


    private suspend fun extractText(bitmap: Bitmap): String? = withTimeoutOrNull(OCR_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { cont.resume(it.text) }
                .addOnFailureListener { cont.resume(null) }
        }
    }
}
