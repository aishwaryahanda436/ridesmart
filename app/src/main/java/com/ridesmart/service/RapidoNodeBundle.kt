package com.ridesmart.service

/**
 * Structured result of a single Rapido UI tree traversal.
 * Fields map 1-to-1 with confirmed APK resource IDs from order_list_item.xml.
 * Empty string = node not found in tree (use allTextNodes fallback).
 */
data class RapidoNodeBundle(
    val fare: String,               // id/amount_tv  (ComposeView — special extraction)
    val vehicleType: String,        // id/service_name_tv  e.g. "Bike Taxi"
    val offerAgeText: String,       // id/order_accept_time_tv  e.g. "2s ago"
    val pickupAddress: String,      // id/pickup_location_tv
    val pickupSubText: String,      // id/pickup_location_sub_text
    val pickupDistanceText: String, // id/distanceTv  e.g. "1.2 km"
    val dropAddress: String,        // id/drop_location_tv
    val allTextNodes: List<String>  // full flat list — used as fallback by existing parsers
)
