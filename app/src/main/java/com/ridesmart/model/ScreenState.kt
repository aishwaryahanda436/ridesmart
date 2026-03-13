package com.ridesmart.model

enum class ScreenState {
    OFFER_LOADING,   // Cards visible but distances not loaded yet
    OFFER_LOADED,    // Cards visible with full distance data
    RIDE_LIST,       // Multiple ride cards in a scrollable list (Trip Radar, stacked offers)
    TRIP_RADAR,      // Trip Radar / opportunity list — grid or map-based ride suggestions
    ACTIVE_RIDE,     // Driver is mid-ride — suppress all overlays
    IDLE             // Map screen, no offers visible
}

