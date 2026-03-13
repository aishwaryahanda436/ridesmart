package com.ridesmart.parser

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import com.ridesmart.model.ScreenState
import com.ridesmart.model.VehicleType

/**
 * Unit tests for NammaYatriParser.
 * Tests ride offer parsing for NammaYatri (JUSPAY) app accessibility nodes.
 */
class NammaYatriParserTest {

    private lateinit var parser: NammaYatriParser

    @Before
    fun setUp() {
        parser = NammaYatriParser()
    }

    // ── Screen state detection ───────────────────────────────────────

    @Test
    fun `idle screen with offline keywords returns IDLE`() {
        val nodes = listOf("You are offline", "Go online", "Earnings today")
        assertEquals(ScreenState.IDLE, parser.detectScreenState(nodes))
    }

    @Test
    fun `active ride with trip keywords returns ACTIVE_RIDE`() {
        val nodes = listOf("₹120", "Start Ride", "Arrived at pickup", "3 km")
        assertEquals(ScreenState.ACTIVE_RIDE, parser.detectScreenState(nodes))
    }

    @Test
    fun `offer with fare accept and km returns OFFER_LOADED`() {
        val nodes = listOf("New Ride Request", "₹85", "1.2 km", "4.5 km", "Accept", "12 min")
        assertEquals(ScreenState.OFFER_LOADED, parser.detectScreenState(nodes))
    }

    @Test
    fun `offer with fare and accept but no km returns OFFER_LOADING`() {
        val nodes = listOf("₹85", "Accept")
        assertEquals(ScreenState.OFFER_LOADING, parser.detectScreenState(nodes))
    }

    @Test
    fun `no fare no accept no offer keyword returns IDLE`() {
        val nodes = listOf("Settings", "Profile", "Help")
        assertEquals(ScreenState.IDLE, parser.detectScreenState(nodes))
    }

    @Test
    fun `offer keyword alone triggers OFFER_LOADING if fare present`() {
        val nodes = listOf("New ride request", "₹70")
        assertEquals(ScreenState.OFFER_LOADING, parser.detectScreenState(nodes))
    }

    @Test
    fun `fare and km without accept keyword returns OFFER_LOADED`() {
        val nodes = listOf("₹100", "5.0 km", "1.5 km")
        assertEquals(ScreenState.OFFER_LOADED, parser.detectScreenState(nodes))
    }

    // ── Ride parsing ────────────────────────────────────────────────

    @Test
    fun `basic NammaYatri ride parses correctly`() {
        val nodes = listOf(
            "New Ride Request",
            "₹120",
            "1.5 km",
            "6.0 km",
            "15 min",
            "Accept",
            "Majestic, Bangalore"
        )
        val result = parser.parse(nodes, "in.juspay.nammayatripartner")

        assertNotNull("Should parse valid NammaYatri offer", result)
        assertEquals("Fare", 120.0, result!!.baseFare, 0.01)
        assertEquals("Pickup distance", 1.5, result.pickupDistanceKm, 0.01)
        assertEquals("Ride distance", 6.0, result.rideDistanceKm, 0.01)
        assertEquals("Duration", 15, result.estimatedDurationMin)
        assertEquals("Package", "in.juspay.nammayatripartner", result.packageName)
    }

    @Test
    fun `NammaYatri ride with single distance parses as ride distance`() {
        val nodes = listOf("₹75", "4.5 km", "10 min", "Accept")
        val result = parser.parse(nodes, "in.juspay.nammayatripartner")

        assertNotNull(result)
        assertEquals("Ride distance", 4.5, result!!.rideDistanceKm, 0.01)
        assertEquals("No pickup distance", 0.0, result.pickupDistanceKm, 0.01)
    }

    @Test
    fun `NammaYatri Auto detects AUTO vehicle type`() {
        val nodes = listOf("Auto", "₹80", "3.0 km", "Accept")
        val result = parser.parse(nodes, "in.juspay.nammayatripartner")

        assertNotNull(result)
        assertEquals(VehicleType.AUTO, result!!.vehicleType)
    }

    @Test
    fun `NammaYatri CNG Auto detects CNG_AUTO vehicle type`() {
        val nodes = listOf("CNG Auto", "₹90", "3.0 km", "Accept")
        val result = parser.parse(nodes, "in.juspay.nammayatripartner")

        assertNotNull(result)
        assertEquals(VehicleType.CNG_AUTO, result!!.vehicleType)
    }

    @Test
    fun `NammaYatri Bike detects BIKE vehicle type`() {
        val nodes = listOf("Bike", "₹35", "2.0 km", "Accept")
        val result = parser.parse(nodes, "in.juspay.nammayatripartner")

        assertNotNull(result)
        assertEquals(VehicleType.BIKE, result!!.vehicleType)
    }

    @Test
    fun `NammaYatri Cab detects CAR vehicle type`() {
        val nodes = listOf("Cab", "₹200", "8.0 km", "Accept")
        val result = parser.parse(nodes, "in.juspay.nammayatripartner")

        assertNotNull(result)
        assertEquals(VehicleType.CAR, result!!.vehicleType)
    }

    @Test
    fun `default vehicle type is AUTO when no vehicle keyword`() {
        val nodes = listOf("₹100", "5.0 km", "Accept")
        val result = parser.parse(nodes, "in.juspay.nammayatripartner")

        assertNotNull(result)
        assertEquals("Default to AUTO (NammaYatri is primarily auto platform)", VehicleType.AUTO, result!!.vehicleType)
    }

    @Test
    fun `empty input returns null`() {
        assertNull(parser.parse(emptyList(), "in.juspay.nammayatripartner"))
    }

    @Test
    fun `idle screen returns null from parse`() {
        val nodes = listOf("You are offline", "Go online")
        assertNull(parser.parse(nodes, "in.juspay.nammayatripartner"))
    }

    @Test
    fun `idle screen returns empty list from parseAll`() {
        val nodes = listOf("You are offline", "Go online")
        val results = parser.parseAll(nodes, "in.juspay.nammayatripartner")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `active ride returns empty list from parseAll`() {
        val nodes = listOf("₹120", "Start Ride", "3 km")
        val results = parser.parseAll(nodes, "in.juspay.nammayatripartner")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `fare below minimum returns null`() {
        val nodes = listOf("₹5", "1.0 km", "Accept")
        val result = parser.parse(nodes, "in.juspay.nammayatripartner")
        assertNull("Fare below ₹10 threshold should return null", result)
    }

    @Test
    fun `NammaYatri ride takes minimum fare for multi-fare nodes`() {
        val nodes = listOf("₹200", "₹120", "5.0 km", "Accept")
        val result = parser.parse(nodes, "in.juspay.nammayatripartner")

        assertNotNull(result)
        assertEquals("Should use minimum fare", 120.0, result!!.baseFare, 0.01)
    }

    @Test
    fun `NammaYatri ride with cash payment detected`() {
        val nodes = listOf("₹95", "3.0 km", "cash", "Accept")
        val result = parser.parse(nodes, "in.juspay.nammayatripartner")

        assertNotNull(result)
        assertEquals("cash", result!!.paymentType)
    }

    @Test
    fun `NammaYatri ride with bonus adds to premiumAmount`() {
        val nodes = listOf("₹100", "+₹20", "5.0 km", "Accept")
        val result = parser.parse(nodes, "in.juspay.nammayatripartner")

        assertNotNull(result)
        assertEquals("Base fare", 100.0, result!!.baseFare, 0.01)
        assertEquals("Bonus", 20.0, result.premiumAmount, 0.01)
        assertEquals("Effective fare (baseFare + premium)", 120.0, result.baseFare + result.premiumAmount, 0.01)
    }

    @Test
    fun `net openkochi yatri package parses correctly`() {
        val nodes = listOf("₹80", "3.0 km", "1.0 km", "Accept", "8 min")
        val result = parser.parse(nodes, "net.openkochi.yatri")

        assertNotNull("net.openkochi.yatri should parse correctly", result)
        assertEquals("Package name preserved", "net.openkochi.yatri", result!!.packageName)
    }

    @Test
    fun `new ride request keyword triggers offer loading state`() {
        val nodes = listOf("New Ride Request", "₹90", "Accept")
        assertEquals(ScreenState.OFFER_LOADING, parser.detectScreenState(nodes))
    }

    @Test
    fun `parseAll returns list with one ride for valid offer`() {
        val nodes = listOf("₹110", "4.0 km", "1.2 km", "Accept", "10 min")
        val results = parser.parseAll(nodes, "in.juspay.nammayatripartner")

        assertEquals("Should return exactly one ride", 1, results.size)
        assertEquals("Fare", 110.0, results[0].baseFare, 0.01)
        assertEquals("Ride distance", 4.0, results[0].rideDistanceKm, 0.01)
        assertEquals("Pickup distance", 1.2, results[0].pickupDistanceKm, 0.01)
        assertEquals("Duration", 10, results[0].estimatedDurationMin)
    }

    // ── Label-guided extraction ──────────────────────────────────────

    @Test
    fun `label-guided fare extraction uses Base Fare label`() {
        val nodes = listOf("New Ride Request", "Base Fare", "₹130", "1.0 km", "5.0 km", "Accept")
        val result = parser.parse(nodes, "in.juspay.nammayatripartner")

        assertNotNull(result)
        assertEquals("Label-guided fare via Base Fare label", 130.0, result!!.baseFare, 0.01)
    }

    @Test
    fun `label-guided fare extraction uses Offer Price label`() {
        val nodes = listOf("Offer Price", "₹95", "3.0 km", "Accept")
        val result = parser.parse(nodes, "in.juspay.nammayatripartner")

        assertNotNull(result)
        assertEquals("Label-guided fare via Offer Price label", 95.0, result!!.baseFare, 0.01)
    }

    @Test
    fun `label-guided pickup extraction uses Pickup label`() {
        val nodes = listOf("₹110", "3.0 km", "Accept", "Pickup", "Majestic Bus Stand, Bangalore")
        val result = parser.parse(nodes, "in.juspay.nammayatripartner")

        assertNotNull(result)
        assertEquals("Label-guided pickup address", "Majestic Bus Stand, Bangalore", result!!.pickupAddress)
    }

    @Test
    fun `label-guided pickup extraction uses From label`() {
        val nodes = listOf("₹85", "4.0 km", "Accept", "From", "Koramangala, Bangalore")
        val result = parser.parse(nodes, "in.juspay.nammayatripartner")

        assertNotNull(result)
        assertEquals("Label-guided pickup via From label", "Koramangala, Bangalore", result!!.pickupAddress)
    }

    @Test
    fun `label-guided drop extraction uses Drop label`() {
        val nodes = listOf("₹110", "3.0 km", "Accept", "Pickup", "Indiranagar, Bangalore", "Drop", "Whitefield, Bangalore")
        val result = parser.parse(nodes, "in.juspay.nammayatripartner")

        assertNotNull(result)
        assertEquals("Label-guided pickup", "Indiranagar, Bangalore", result!!.pickupAddress)
        assertEquals("Label-guided drop via Drop label", "Whitefield, Bangalore", result!!.dropAddress)
    }

    @Test
    fun `label-guided drop extraction uses Destination label`() {
        val nodes = listOf("₹120", "5.0 km", "Accept", "Destination", "Electronic City, Bangalore")
        val result = parser.parse(nodes, "in.juspay.nammayatripartner")

        assertNotNull(result)
        assertEquals("Label-guided drop via Destination label", "Electronic City, Bangalore", result!!.dropAddress)
    }

    @Test
    fun `label-guided extraction with all three fields`() {
        val nodes = listOf(
            "New Ride Request",
            "Base Fare", "₹150",
            "1.2 km", "6.5 km", "14 min",
            "Pickup", "MG Road, Bangalore",
            "Drop", "Airport Road, Bangalore",
            "Accept"
        )
        val result = parser.parse(nodes, "in.juspay.nammayatripartner")

        assertNotNull(result)
        assertEquals("Label-guided fare", 150.0, result!!.baseFare, 0.01)
        assertEquals("Label-guided pickup", "MG Road, Bangalore", result.pickupAddress)
        assertEquals("Label-guided drop", "Airport Road, Bangalore", result.dropAddress)
    }

    @Test
    fun `label-guided fare takes precedence over regex fare`() {
        // Base Fare label points to ₹130 but ₹80 also appears as a standalone node;
        // label-guided result (130) should win over the regex minimum (80).
        val nodes = listOf("Base Fare", "₹130", "₹80", "3.0 km", "Accept")
        val result = parser.parse(nodes, "in.juspay.nammayatripartner")

        assertNotNull(result)
        assertEquals("Label-guided fare wins over regex minimum", 130.0, result!!.baseFare, 0.01)
    }

    // ── New screen reject phrases ────────────────────────────────────

    @Test
    fun `you are on a ride returns ACTIVE_RIDE`() {
        val nodes = listOf("You are on a ride", "₹120", "3.0 km")
        assertEquals(ScreenState.ACTIVE_RIDE, parser.detectScreenState(nodes))
    }

    @Test
    fun `ride started returns ACTIVE_RIDE`() {
        val nodes = listOf("Ride Started", "₹95", "4.0 km")
        assertEquals(ScreenState.ACTIVE_RIDE, parser.detectScreenState(nodes))
    }

    @Test
    fun `end ride returns ACTIVE_RIDE`() {
        val nodes = listOf("₹150", "End Ride", "5.0 km")
        assertEquals(ScreenState.ACTIVE_RIDE, parser.detectScreenState(nodes))
    }

    @Test
    fun `arrived at pickup returns ACTIVE_RIDE`() {
        val nodes = listOf("Arrived at pickup", "₹80", "0.0 km")
        assertEquals(ScreenState.ACTIVE_RIDE, parser.detectScreenState(nodes))
    }

    @Test
    fun `active ride screen returns null from parse`() {
        val nodes = listOf("You are on a ride", "End Ride", "₹100", "3.0 km")
        assertNull(parser.parse(nodes, "in.juspay.nammayatripartner"))
    }
