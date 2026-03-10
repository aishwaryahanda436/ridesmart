package com.ridesmart.parser

import com.ridesmart.service.UberOcrEngine
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests that ParserFactory routes each platform to the correct parser.
 * Critical for Uber: must route to UberOcrEngine (not generic RideDataParser)
 * so that the hybrid accessibility + OCR detection pipeline is used.
 */
class ParserFactoryTest {

    @Test
    fun `uber driver package routes to UberOcrEngine`() {
        val parser = ParserFactory.getParser("com.ubercab.driver")
        assertTrue("Uber driver should use UberOcrEngine", parser is UberOcrEngine)
    }

    @Test
    fun `uber package routes to UberOcrEngine`() {
        val parser = ParserFactory.getParser("com.ubercab")
        assertTrue("Uber should use UberOcrEngine", parser is UberOcrEngine)
    }

    @Test
    fun `rapido captain package routes to RapidoParser`() {
        val parser = ParserFactory.getParser("in.rapido.captain")
        assertTrue("Rapido should use RapidoParser", parser is RapidoParser)
    }

    @Test
    fun `rapido rider package routes to RapidoParser`() {
        val parser = ParserFactory.getParser("com.rapido.rider")
        assertTrue("Rapido rider should use RapidoParser", parser is RapidoParser)
    }

    @Test
    fun `ola package routes to RideDataParser`() {
        val parser = ParserFactory.getParser("com.olacabs.driver")
        assertTrue("Ola should use generic RideDataParser", parser is RideDataParser)
    }

    @Test
    fun `namma yatri routes to RideDataParser`() {
        val parser = ParserFactory.getParser("in.juspay.nammayatripartner")
        assertTrue("Namma Yatri should use RideDataParser", parser is RideDataParser)
    }

    @Test
    fun `unknown package routes to RideDataParser`() {
        val parser = ParserFactory.getParser("com.unknown.app")
        assertTrue("Unknown package should use RideDataParser", parser is RideDataParser)
    }

    @Test
    fun `isUber returns true for uber packages`() {
        assertTrue(ParserFactory.isUber("com.ubercab.driver"))
        assertTrue(ParserFactory.isUber("com.ubercab"))
    }

    @Test
    fun `isUber returns false for non-uber packages`() {
        assertFalse(ParserFactory.isUber("com.rapido.rider"))
        assertFalse(ParserFactory.isUber("com.olacabs.driver"))
    }

    @Test
    fun `getUberParser returns UberOcrEngine`() {
        assertTrue(ParserFactory.getUberParser() is UberOcrEngine)
    }

    @Test
    fun `getFallbackParser returns RideDataParser`() {
        assertTrue(ParserFactory.getFallbackParser() is RideDataParser)
    }
}
