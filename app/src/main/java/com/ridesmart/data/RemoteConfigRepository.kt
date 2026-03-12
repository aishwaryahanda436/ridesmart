package com.ridesmart.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Spec v2.0 Section 10: Remote Pattern Configuration.
 * 
 * Fetches and stores regex patterns for ride apps so that 
 * breaking UI changes can be fixed without an app update.
 */
class RemoteConfigRepository(private val context: Context) {

    private val _config = MutableStateFlow<ConfigData?>(null)
    val config: StateFlow<ConfigData?> = _config

    // Default patterns as per Spec v2.0
    data class PlatformPatterns(
        val fare: List<String>,
        val distance: List<String>,
        val ocrThreshold: Float
    )

    data class ConfigData(
        val version: Int,
        val uber: PlatformPatterns,
        val ola: PlatformPatterns,
        val rapido: PlatformPatterns,
        val shadowfax: PlatformPatterns
    )

    companion object {
        private const val TAG = "RideSmart"
        
        // Static defaults for cold-start (Spec v2.0 values)
        val DEFAULT_UBER = PlatformPatterns(listOf("""[₹$]\s*(\d{1,4}(?:[.,]\d{1,2})?)"""), listOf("""(\d+(?:\.\d+)?)\s*km"""), 0.4f)
        val DEFAULT_OLA = PlatformPatterns(listOf("""[Rr]s\s*(\d+(?:[.,]\d{1,2})?)"""), listOf("""(\d+(?:\.\d+)?)\s*km"""), 0.6f)
        val DEFAULT_RAPIDO = PlatformPatterns(emptyList(), listOf("""(\d+(?:\.\d+)?)\s*km"""), 0.7f)
        val DEFAULT_SHADOWFAX = PlatformPatterns(listOf("""Guaranteed Pay:\s*[₹]\s*(\d+)""", """Surge Bonus:\s*[₹]\s*(\d+)"""), listOf("""(\d+(?:\.\d+)?)\s*km"""), 0.5f)
    }

    suspend fun fetchConfig() {
        // TODO: Re-enable remote config when backend endpoint is ready
        if (_config.value == null) {
            _config.value = ConfigData(0, DEFAULT_UBER, DEFAULT_OLA, DEFAULT_RAPIDO, DEFAULT_SHADOWFAX)
            Log.d(TAG, "🌐 Using built-in remote configuration patterns")
        }
    }
}
