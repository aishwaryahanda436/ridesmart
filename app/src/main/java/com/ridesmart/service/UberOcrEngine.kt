package com.ridesmart.service

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Uber OCR Engine — optimized for isolating the bottom offer card.
 */
class UberOcrEngine {

    companion object {
        private const val TAG = "RideSmart"
        private const val OCR_TIMEOUT_MS = 3000L
        
        // Uber offer popups are always at the bottom.
        // CROP_TOP_FRACTION = 0.55 means we remove the top 60% (Map + status bar).
        // CROP_BOTTOM_FRACTION = 0.08 means we remove the bottom 8% (System navigation bar).
        private const val CROP_TOP_FRACTION = 0.55f
        private const val CROP_BOTTOM_FRACTION = 0.08f
    }

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun extractLines(bitmap: Bitmap, cardBounds: Rect? = null): List<String> {
        val cropRect: Rect = if (cardBounds != null && isValidBounds(cardBounds, bitmap)) {
            Log.d(TAG, "UberOCR: precise crop to card bounds $cardBounds")
            cardBounds
        } else {
            // Optimized crop for bottom popup:
            val top = (bitmap.height * CROP_TOP_FRACTION).toInt()
            val bottomLimit = (bitmap.height * (1.0f - CROP_BOTTOM_FRACTION)).toInt()
            
            Rect(0, top, bitmap.width, bottomLimit).also {
                Log.d(TAG, "UberOCR: optimized bottom-crop top=${top}px")
            }
        }

        val cropped: Bitmap = try {
            Bitmap.createBitmap(bitmap, cropRect.left, cropRect.top, cropRect.width(), cropRect.height())
        } catch (e: Exception) {
            Log.e(TAG, "UberOCR: crop failed: ${e.message}")
            bitmap
        }

        val rawText = extractText(cropped)
        if (cropped !== bitmap) cropped.recycle()

        if (rawText.isNullOrBlank()) return emptyList()
        return rawText.lines().map { it.trim() }.filter { it.isNotBlank() }
    }

    private fun isValidBounds(rect: Rect, bitmap: Bitmap): Boolean {
        return rect.left >= 0 && rect.top >= 0 &&
               rect.right <= bitmap.width && rect.bottom <= bitmap.height &&
               rect.width() > 100 && rect.height() > 100
    }

    private suspend fun extractText(bitmap: Bitmap): String? = withTimeoutOrNull(OCR_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { cont.resume(it.text) }
                .addOnFailureListener { 
                    Log.e(TAG, "OCR Failed: ${it.message}")
                    cont.resume(null) 
                }
        }
    }
}
