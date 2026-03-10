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
    fun `ola driver package routes to OlaParser`() {
        val parser = ParserFactory.getParser("com.olacabs.driver")
        assertTrue("Ola should use OlaParser", parser is OlaParser)
    }

    @Test
    fun `ola package routes to OlaParser`() {
        val parser = ParserFactory.getParser("com.ola.driver")
        assertTrue("Ola driver should use OlaParser", parser is OlaParser)
    }

    @Test
    fun `shadowfax driver package routes to ShadowfaxParser`() {
        val parser = ParserFactory.getParser("com.shadowfax.driver")
        assertTrue("Shadowfax should use ShadowfaxParser", parser is ShadowfaxParser)
    }

    @Test
    fun `shadowfax zeus package routes to ShadowfaxParser`() {
        val parser = ParserFactory.getParser("com.shadowfax.zeus")
        assertTrue("Shadowfax zeus should use ShadowfaxParser", parser is ShadowfaxParser)
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
    fun `isOla returns true for ola packages`() {
        assertTrue(ParserFactory.isOla("com.olacabs.driver"))
        assertTrue(ParserFactory.isOla("com.ola.driver"))
    }

    @Test
    fun `isOla returns false for non-ola packages`() {
        assertFalse(ParserFactory.isOla("com.ubercab.driver"))
        assertFalse(ParserFactory.isOla("com.rapido.rider"))
    }

    @Test
    fun `isShadowfax returns true for shadowfax packages`() {
        assertTrue(ParserFactory.isShadowfax("com.shadowfax.driver"))
        assertTrue(ParserFactory.isShadowfax("com.shadowfax.zeus"))
    }

    @Test
    fun `isShadowfax returns false for non-shadowfax packages`() {
        assertFalse(ParserFactory.isShadowfax("com.ubercab.driver"))
        assertFalse(ParserFactory.isShadowfax("com.rapido.rider"))
    }

    @Test
    fun `getUberParser returns UberOcrEngine`() {
        assertTrue(ParserFactory.getUberParser() is UberOcrEngine)
    }

    @Test
    fun `getOlaParser returns OlaParser`() {
        assertTrue(ParserFactory.getOlaParser() is OlaParser)
    }

    @Test
    fun `getShadowfaxParser returns ShadowfaxParser`() {
        assertTrue(ParserFactory.getShadowfaxParser() is ShadowfaxParser)
    }

    @Test
    fun `getFallbackParser returns RideDataParser`() {
        assertTrue(ParserFactory.getFallbackParser() is RideDataParser)
    }
}
