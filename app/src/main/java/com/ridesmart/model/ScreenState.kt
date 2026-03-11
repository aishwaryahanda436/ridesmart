package com.ridesmart.model

enum class ScreenState {
    OFFER_LOADING,   // Cards visible but distances not loaded yet
    OFFER_LOADED,    // Cards visible with full distance data
    ACTIVE_RIDE,     // Driver is mid-ride — suppress all overlays
    IDLE             // Map screen, no offers visible
}

