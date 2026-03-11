package com.ridesmart.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

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
        private const val CONFIG_URL = "https://raw.githubusercontent.com/rajat/ridesmart-config/main/patterns.json"
        
        // Static defaults for cold-start (Spec v2.0 values)
        val DEFAULT_UBER = PlatformPatterns(listOf("""[₹$]\s*(\d{1,4}(?:[.,]\d{1,2})?)"""), listOf("""(\d+(?:\.\d+)?)\s*km"""), 0.4f)
        val DEFAULT_OLA = PlatformPatterns(listOf("""[Rr]s\s*(\d+(?:[.,]\d{1,2})?)"""), listOf("""(\d+(?:\.\d+)?)\s*km"""), 0.6f)
        val DEFAULT_RAPIDO = PlatformPatterns(emptyList(), listOf("""(\d+(?:\.\d+)?)\s*km"""), 0.7f)
        val DEFAULT_SHADOWFAX = PlatformPatterns(listOf("""Guaranteed Pay:\s*[₹]\s*(\d+)""", """Surge Bonus:\s*[₹]\s*(\d+)"""), listOf("""(\d+(?:\.\d+)?)\s*km"""), 0.5f)
    }

    suspend fun fetchConfig() {
        withContext(Dispatchers.IO) {
            try {
                // In a real app, use Retrofit or Firebase Remote Config
                // This is a simplified fetch logic for the spec
                val response = URL(CONFIG_URL).readText()
                val json = JSONObject(response)
                
                val version = json.getInt("version")
                val platforms = json.getJSONObject("platforms")

                fun parsePlatform(pkg: String, default: PlatformPatterns): PlatformPatterns {
                    if (!platforms.has(pkg)) return default
                    val p = platforms.getJSONObject(pkg)
                    return PlatformPatterns(
                        fare = p.getJSONArray("farePatterns").let { arr -> List(arr.length()) { arr.getString(it) } },
                        distance = p.getJSONArray("distancePatterns").let { arr -> List(arr.length()) { arr.getString(it) } },
                        ocrThreshold = p.getDouble("ocrThreshold").toFloat()
                    )
                }

                _config.value = ConfigData(
                    version = version,
                    uber = parsePlatform("com.ubercab.driver", DEFAULT_UBER),
                    ola = parsePlatform("com.olacabs.oladriver", DEFAULT_OLA),
                    rapido = parsePlatform("com.rapido.rider", DEFAULT_RAPIDO),
                    shadowfax = parsePlatform("in.shadowfax.gandalf", DEFAULT_SHADOWFAX)
                )
                Log.d(TAG, "🌐 Remote config updated to version $version")
            } catch (e: Exception) {
                Log.e(TAG, "🌐 Failed to fetch remote config: ${e.message}")
                // Ensure we have at least defaults
                if (_config.value == null) {
                    _config.value = ConfigData(0, DEFAULT_UBER, DEFAULT_OLA, DEFAULT_RAPIDO, DEFAULT_SHADOWFAX)
                }
            }
        }
    }
}
