package com.ridesmart.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.ridesmart.model.Signal

@Entity(tableName = "ride_history")
data class RideEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    // ── TIME ──────────────────────────────────────────
    val timestampMs: Long,
    val hourOfDay: Int,
    val dayOfWeek: Int,

    // ── PLATFORM ──────────────────────────────────────
    val platform: String,
    val packageName: String,

    // ── FARE BREAKDOWN ────────────────────────────────
    val baseFare: Double,
    val tipAmount: Double,
    val premiumAmount: Double,
    val actualPayout: Double,

    // ── DISTANCES ─────────────────────────────────────
    val rideKm: Double,
    val pickupKm: Double,
    val totalKm: Double,
    val pickupRatioPct: Int,

    // ── DURATION ──────────────────────────────────────
    val estimatedDurationMin: Int,

    // ── COSTS ─────────────────────────────────────────
    val fuelCost: Double,
    val wearCost: Double,
    val totalCost: Double,

    // ── PROFIT ────────────────────────────────────────
    val netProfit: Double,

    @ColumnInfo(name = "earningPerKm")
    val efficiencyPerKm: Double,

    val pickupPenaltyPct: Double = 0.0,
    // Penalty that was applied to this ride's ₹/km efficiency.
    // Stored for history analysis — lets the captain see which saved
    // rides were penalized due to long pickup and by how much.

    val earningPerHour: Double,

    // ── DECISION ──────────────────────────────────────
    val signal: Signal,
    val failedChecks: String,

    @ColumnInfo(name = "smartScore")
    val decisionScore: Double,

    // ── PII (Should be encrypted or handled carefully) ──
    val pickupAddress: String = "",
    val dropAddress: String = "",
    
    val riderRating: Double = 0.0,
    val paymentType: String = ""
)
