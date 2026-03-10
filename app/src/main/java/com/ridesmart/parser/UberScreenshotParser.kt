package com.ridesmart.parser

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.ridesmart.model.ParsedRide
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Parses Uber ride offer data from a screenshot bitmap using ML Kit OCR.
 * Used as fallback when accessibility tree returns 0 nodes (GPU-rendered overlay).
 * GigU uses the same approach via AccessibilityService.takeScreenshot().
 */
class UberScreenshotParser {

    companion object {
        private const val TAG = "RideSmart"
    }

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun parse(bitmap: Bitmap): ParsedRide? {
        val text = extractText(bitmap) ?: return null
        if (text.isBlank()) return null

        Log.d(TAG, "📸 OCR TEXT: $text")

        // Split into lines for parsing
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        Log.d(TAG, "📸 OCR LINES: ${lines.joinToString(" | ")}")

        // Parse fare — look for ₹ followed by digits
        val fareRegex = Regex("""[₹Rs\.]*\s*(\d+(?:\.\d{1,2})?)""")
        val fare = lines.firstNotNullOfOrNull { line ->
            fareRegex.find(line)?.groupValues?.get(1)?.toDoubleOrNull()
        }?.takeIf { it > 10.0 } ?: return null  // must be >₹10 to be a real fare

        // Parse distance — look for X.X km or X km
        val distanceRegex = Regex("""(\d+(?:\.\d+)?)\s*km""", RegexOption.IGNORE_CASE)
        val distance = lines.firstNotNullOfOrNull { line ->
            distanceRegex.find(line)?.groupValues?.get(1)?.toDoubleOrNull()
        } ?: 0.0

        // Parse pickup distance — look for lines containing "away" or "pickup"
        val pickupRegex = Regex("""(\d+(?:\.\d+)?)\s*km""", RegexOption.IGNORE_CASE)
        val pickupDistance = lines.firstNotNullOfOrNull { line ->
            if (line.contains("away", ignoreCase = true) ||
                line.contains("pickup", ignoreCase = true) ||
                line.contains("board", ignoreCase = true)) {
                pickupRegex.find(line)?.groupValues?.get(1)?.toDoubleOrNull()
            } else null
        } ?: 0.0

        // Parse duration — look for X min or X mins
        val durationRegex = Regex("""(\d+)\s*min""", RegexOption.IGNORE_CASE)
        val duration = lines.firstNotNullOfOrNull { line ->
            durationRegex.find(line)?.groupValues?.get(1)?.toIntOrNull()
        } ?: 0

        // Parse pickup address — first non-numeric line after fare
        val pickupAddress = lines.firstOrNull { line ->
            line.length > 5 &&
            !line.contains(Regex("""^\d""")) &&
            !line.contains("km", ignoreCase = true) &&
            !line.contains("min", ignoreCase = true) &&
            !line.contains("₹")
        } ?: ""

        Log.d(TAG, "📸 OCR PARSED: fare=₹$fare dist=${distance}km pickup=${pickupDistance}km dur=${duration}min addr=$pickupAddress")

        return ParsedRide(
            baseFare           = fare,
            tipAmount          = 0.0,
            premiumAmount      = 0.0,
            rideDistanceKm     = distance,
            pickupDistanceKm   = pickupDistance,
            estimatedDurationMin = duration,
            pickupAddress      = pickupAddress,
            dropAddress        = "",
            riderRating        = 0.0,
            paymentType        = "cash"
        )
    }

    private suspend fun extractText(bitmap: Bitmap): String? =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    cont.resume(visionText.text)
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "OCR failed: ${e.message}")
                    cont.resume(null)
                }
        }
}
