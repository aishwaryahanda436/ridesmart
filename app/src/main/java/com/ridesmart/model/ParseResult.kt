package com.ridesmart.model

/**
 * A wrapper to provide context on why parsing might have failed or succeeded.
 */
sealed class ParseResult {
    data class Success(val rides: List<ParsedRide>) : ParseResult()
    data class Failure(val reason: String, val confidence: Float = 0f) : ParseResult()
    object Idle : ParseResult()
}
