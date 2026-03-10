package com.ridesmart.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.ridesmart.model.Signal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.historyDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "ride_history")

data class RideEntry(
    // ── TIME ──────────────────────────────────────────
    val timestampMs: Long,
    val hourOfDay: Int,          // 0-23, for hourly analysis
    val dayOfWeek: Int,          // 1=Mon ... 7=Sun (Calendar.DAY_OF_WEEK)

    // ── PLATFORM ──────────────────────────────────────
    val platform: String,        // display name e.g. "Rapido"
    val packageName: String,     // e.g. "com.rapido.rider"

    // ── FARE BREAKDOWN ────────────────────────────────
    val baseFare: Double,
    val tipAmount: Double,
    val premiumAmount: Double,
    val actualPayout: Double,    // after commission

    // ── DISTANCES ─────────────────────────────────────
    val rideKm: Double,
    val pickupKm: Double,
    val totalKm: Double,         // rideKm + pickupKm
    val pickupRatioPct: Int,     // pickupKm/totalKm * 100

    // ── DURATION ──────────────────────────────────────
    val estimatedDurationMin: Int,

    // ── COSTS ─────────────────────────────────────────
    val fuelCost: Double,
    val wearCost: Double,
    val totalCost: Double,       // fuelCost + wearCost

    // ── PROFIT ────────────────────────────────────────
    val netProfit: Double,
    val earningPerKm: Double,
    val earningPerHour: Double,

    // ── DECISION ──────────────────────────────────────
    val signal: Signal,
    val failedChecks: String,    // joined with "|" for storage, split on "|" to read
    val smartScore: Double,      // netProfit minus pickup penalty

    // New fields for full data capture
    val pickupAddress: String = "",
    val dropAddress: String = "",
    val riderRating: Double = 0.0,
    val paymentType: String = ""
)

class RideHistoryRepository(private val context: Context) {

    companion object {
        private val KEY_HISTORY = stringPreferencesKey("history_csv")
        private const val MAX_ENTRIES = 200
        private const val SEP_FIELD = ","
        private const val SEP_ROW = "\n"
    }

    // Flow of all saved ride entries, newest first
    val historyFlow: Flow<List<RideEntry>> = context.historyDataStore.data
        .map { prefs ->
            val raw = prefs[KEY_HISTORY] ?: return@map emptyList()
            raw.split(SEP_ROW)
                .filter { it.isNotBlank() }
                .mapNotNull { parseRow(it) }
                .sortedByDescending { it.timestampMs }
        }

    suspend fun saveRide(entry: RideEntry) {
        context.historyDataStore.edit { prefs ->
            val existing = prefs[KEY_HISTORY] ?: ""
            val rows = existing.split(SEP_ROW).filter { it.isNotBlank() }.toMutableList()
            rows.add(0, encodeRow(entry))
            // Keep only the most recent MAX_ENTRIES
            val trimmed = rows.take(MAX_ENTRIES)
            prefs[KEY_HISTORY] = trimmed.joinToString(SEP_ROW)
        }
    }

    suspend fun clearHistory() {
        context.historyDataStore.edit { it.remove(KEY_HISTORY) }
    }

    private fun encodeRow(e: RideEntry): String = listOf(
        e.timestampMs, e.hourOfDay, e.dayOfWeek,
        e.platform, e.packageName,
        e.baseFare, e.tipAmount, e.premiumAmount, e.actualPayout,
        e.rideKm, e.pickupKm, e.totalKm, e.pickupRatioPct,
        e.estimatedDurationMin,
        e.fuelCost, e.wearCost, e.totalCost,
        e.netProfit, e.earningPerKm, e.earningPerHour,
        e.signal.name,
        e.failedChecks.replace(",", ";"),  // guard against field separator
        e.smartScore,
        e.pickupAddress.replace(",", "~"),
        e.dropAddress.replace(",", "~"),
        e.riderRating,
        e.paymentType.replace(",", ";")
    ).joinToString(SEP_FIELD)

    private fun parseRow(row: String): RideEntry? {
        return try {
            val p = row.split(SEP_FIELD)
            if (p.size < 23) return null  // reject old short-format rows
            RideEntry(
                timestampMs        = p[0].toLong(),
                hourOfDay          = p[1].toInt(),
                dayOfWeek          = p[2].toInt(),
                platform           = p[3],
                packageName        = p[4],
                baseFare           = p[5].toDouble(),
                tipAmount          = p[6].toDouble(),
                premiumAmount      = p[7].toDouble(),
                actualPayout       = p[8].toDouble(),
                rideKm             = p[9].toDouble(),
                pickupKm           = p[10].toDouble(),
                totalKm            = p[11].toDouble(),
                pickupRatioPct     = p[12].toInt(),
                estimatedDurationMin = p[13].toInt(),
                fuelCost           = p[14].toDouble(),
                wearCost           = p[15].toDouble(),
                totalCost          = p[16].toDouble(),
                netProfit          = p[17].toDouble(),
                earningPerKm       = p[18].toDouble(),
                earningPerHour     = p[19].toDouble(),
                signal             = Signal.valueOf(p[20]),
                failedChecks       = p[21].replace(";", ","),
                smartScore         = p[22].toDouble(),
                pickupAddress      = if (p.size > 23) p[23].replace("~", ",") else "",
                dropAddress        = if (p.size > 24) p[24].replace("~", ",") else "",
                riderRating        = if (p.size > 25) p[25].toDoubleOrNull() ?: 0.0 else 0.0,
                paymentType        = if (p.size > 26) p[26].replace(";", ",") else ""
            )
        } catch (e: Exception) { null }
    }
}
