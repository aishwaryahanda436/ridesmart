package com.ridesmart.parser

import android.util.Log
import com.ridesmart.model.ParsedRide
import com.ridesmart.model.ScreenState
import com.ridesmart.model.VehicleType

class ShadowfaxParser : IPlatformParser {

    companion object {
        private const val TAG = "RideSmart"
        private const val PACKAGE = "in.shadowfax.gandalf"

        // Fare: "₹166.01" — plain amount, may include decimals
        private val FARE_REGEX = Regex(
            """₹\s*(\d+(?:\.\d{1,2})?)"""
        )

        // Pickup distance: "Pickup: 0.2 km" or "Pickup: 0.2 kms"
        private val PICKUP_KM_REGEX = Regex(
            """(?i)pickup\s*[:\-]?\s*(\d+(?:\.\d{1,2})?)\s*kms?"""
        )

        // Drop distance: "Drop: 14.94 kms" or "Drop: 14.94 km"
        private val DROP_KM_REGEX = Regex(
            """(?i)drop\s*[:\-]?\s*(\d+(?:\.\d{1,2})?)\s*kms?"""
        )

        // Generic km fallback if labels not found
        private val KM_REGEX = Regex(
            """(\d+(?:\.\d{1,2})?)\s*kms?""",
            RegexOption.IGNORE_CASE
        )

        // Duration: "12 min"
        private val MIN_REGEX = Regex(
            """(\d+)\s*min""",
            RegexOption.IGNORE_CASE
        )

        // Payment type: "Amount: Cash" or "Amount: Online"
        private val PAYMENT_REGEX = Regex(
            """(?i)amount\s*[:\-]?\s*(\w+)"""
        )

        // ── IDLE screen keywords ──────────────────────────────────
        private val IDLE_KEYWORDS = listOf(
            "searching nearby orders",
            "searching nearby",
            "move to a nearby",
            "high demand area",
            "no orders",
            "you are offline",
            "go online"
        )

        // ── ACTIVE ride keywords ──────────────────────────────────
        private val ACTIVE_RIDE_KEYWORDS = listOf(
            "task completed",
            "delivered",
            "picked",
            "cancelled",
            "otp",
            "reached",
            "complete delivery",
            "delivery started",
            "order journey"
        )

        // ── Offer screen action keyword ───────────────────────────
        private val OFFER_ACTION_KEYWORDS = listOf(
            "accept order"
        )
    }

    override fun detectScreenState(nodes: List<String>): ScreenState {
        val combined = nodes.joinToString(" ").lowercase()

        // Check idle first
        if (IDLE_KEYWORDS.any { combined.contains(it) }) {
            Log.d(TAG, "🚫 Shadowfax: IDLE screen detected")
            return ScreenState.IDLE
        }

        // Check active ride
        if (ACTIVE_RIDE_KEYWORDS.any { combined.contains(it) }) {
            Log.d(TAG, "🚫 Shadowfax: ACTIVE_RIDE detected — suppressing overlay")
            return ScreenState.ACTIVE_RIDE
        }

        val hasAction = OFFER_ACTION_KEYWORDS.any { combined.contains(it) }
        val hasFare   = FARE_REGEX.containsMatchIn(combined)
        val hasKm     = KM_REGEX.containsMatchIn(combined)

        return when {
            hasAction && hasFare && hasKm  -> ScreenState.OFFER_LOADED
            hasAction && hasFare && !hasKm -> ScreenState.OFFER_LOADING
            else                           -> ScreenState.IDLE
        }
    }

    override fun parseAll(nodes: List<String>, packageName: String): List<ParsedRide> {
        val active = nodes.filter { it.isNotBlank() }
        if (detectScreenState(active) != ScreenState.OFFER_LOADED) return emptyList()

        // STRATEGY 1: labeled extraction (Pickup:/Drop: present)
        val labeledRide = parseLabeledNodes(active)
        if (labeledRide != null) {
            Log.d(TAG, "✅ Shadowfax LABELED strategy succeeded")
            return listOf(labeledRide)
        }

        // STRATEGY 2: merged Compose node fallback
        val combined = active.joinToString(" · ")
        val mergedRide = parseMergedText(combined, active)
        if (mergedRide != null) {
            Log.d(TAG, "✅ Shadowfax MERGED strategy succeeded")
            return listOf(mergedRide)
        }

        Log.d(TAG, "⚠️ Shadowfax: both strategies failed for nodes: $active")
        return emptyList()
    }

    private fun parseLabeledNodes(nodes: List<String>): ParsedRide? {
        val combined = nodes.joinToString(" ")

        // Extract fare — take the FIRST ₹ amount (top of card)
        val allFares = FARE_REGEX.findAll(combined)
            .mapNotNull { it.groupValues[1].toDoubleOrNull() }
            .filter { it > 10.0 }
            .toList()
        val baseFare = allFares.firstOrNull() ?: return null

        // Extract labeled distances
        val pickupKm = PICKUP_KM_REGEX.find(combined)
            ?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        val dropKm = DROP_KM_REGEX.find(combined)
            ?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

        // Must have at least drop distance to proceed
        if (dropKm == 0.0 && pickupKm == 0.0) return null

        // Payment type
        val paymentType = PAYMENT_REGEX.find(combined)
            ?.groupValues?.get(1)?.lowercase() ?: ""

        // Duration
        val durationMin = MIN_REGEX.find(combined)
            ?.groupValues?.get(1)?.toIntOrNull() ?: 0

        // Addresses — node after "Pickup:" label and after "Drop:" label
        var pickupAddress = ""
        var dropAddress   = ""
        for (i in nodes.indices) {
            val n = nodes[i]
            if (n.contains("Pickup", ignoreCase = true) &&
                KM_REGEX.containsMatchIn(n)) {
                pickupAddress = nodes.getOrElse(i + 1) { "" }
            }
            if (n.contains("Drop", ignoreCase = true) &&
                KM_REGEX.containsMatchIn(n)) {
                dropAddress = nodes.getOrElse(i + 1) { "" }
            }
        }

        Log.d(TAG, "🔍 Shadowfax LABELED: fare=₹$baseFare " +
            "pickup=${pickupKm}km drop=${dropKm}km payment=$paymentType")

        return ParsedRide(
            baseFare             = baseFare,
            premiumAmount        = 0.0,
            tipAmount            = 0.0,
            rideDistanceKm       = dropKm,
            pickupDistanceKm     = pickupKm,
            estimatedDurationMin = durationMin,
            platform             = "Shadowfax",
            packageName          = PACKAGE,
            rawTextNodes         = nodes,
            pickupAddress        = pickupAddress,
            dropAddress          = dropAddress,
            paymentType          = paymentType,
            vehicleType          = VehicleType.DELIVERY,
            screenState          = ScreenState.OFFER_LOADED
        )
    }

    private fun parseMergedText(combined: String, raw: List<String>): ParsedRide? {
        val baseFare = FARE_REGEX.findAll(combined)
            .mapNotNull { it.groupValues[1].toDoubleOrNull() }
            .filter { it > 10.0 }
            .firstOrNull() ?: return null

        val allKm = KM_REGEX.findAll(combined)
            .mapNotNull { it.groupValues[1].toDoubleOrNull() }
            .toList()

        if (allKm.isEmpty()) return null

        val pickupKm = if (allKm.size >= 2) allKm.first() else 0.0
        val dropKm   = if (allKm.size >= 2) allKm.last() else allKm.first()

        val paymentType = PAYMENT_REGEX.find(combined)
            ?.groupValues?.get(1)?.lowercase() ?: ""

        val durationMin = MIN_REGEX.find(combined)
            ?.groupValues?.get(1)?.toIntOrNull() ?: 0

        Log.d(TAG, "🔍 Shadowfax MERGED: fare=₹$baseFare " +
            "pickup=${pickupKm}km drop=${dropKm}km")

        return ParsedRide(
            baseFare             = baseFare,
            premiumAmount        = 0.0,
            tipAmount            = 0.0,
            rideDistanceKm       = dropKm,
            pickupDistanceKm     = pickupKm,
            estimatedDurationMin = durationMin,
            platform             = "Shadowfax",
            packageName          = PACKAGE,
            rawTextNodes         = raw,
            paymentType          = paymentType,
            vehicleType          = VehicleType.DELIVERY,
            screenState          = ScreenState.OFFER_LOADED
        )
    }
}
