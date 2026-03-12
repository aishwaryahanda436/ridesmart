package com.ridesmart.model

/**
 * Unified ride offer wrapper.
 *
 * Wraps a [ParsedRide] with metadata needed for multi-ride comparison:
 * platform source, detection timestamp, and expiry window.
 *
 * Every ride detected by an accessibility service or OCR engine is
 * converted into a [RideOffer] before being submitted to the [RidePool].
 */
data class RideOffer(
    /** Unique identifier for this offer (e.g. UUID or monotonic counter). */
    val id: String,

    /** The parsed ride data extracted from the platform popup. */
    val parsedRide: ParsedRide,

    /** Display name of the source platform (e.g. "Uber", "Rapido"). */
    val platform: String = parsedRide.platform,

    /** System time in millis when the offer was detected. */
    val detectedAtMs: Long = System.currentTimeMillis(),

    /** Milliseconds after detection before the offer expires. Default 10 s. */
    val expiryMs: Long = 10_000L,

    /** True if this is a delivery order rather than a passenger ride. */
    val isDelivery: Boolean = parsedRide.isDelivery
) {
    /** Whether this offer has expired relative to [now]. */
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean =
        now - detectedAtMs > expiryMs

    /** Whether essential data is present for evaluation. */
    fun hasMinimumData(): Boolean =
        parsedRide.baseFare > 0.0 && parsedRide.rideDistanceKm > 0.0
}
