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
    fun `rapido rider package routes to RapidoParser`() {
        val parser = ParserFactory.getParser("com.rapido.rider")
        assertTrue("Rapido rider should use RapidoParser", parser is RapidoParser)
    }

    @Test
    fun `ola driver package routes to OlaParser`() {
        val parser = ParserFactory.getParser("com.olacabs.oladriver")
        assertTrue("Ola should use OlaParser", parser is OlaParser)
    }

    @Test
    fun `shadowfax gandalf package routes to ShadowfaxParser`() {
        val parser = ParserFactory.getParser("in.shadowfax.gandalf")
        assertTrue("Shadowfax should use ShadowfaxParser", parser is ShadowfaxParser)
    }

    @Test
    fun `namma yatri routes to NammaYatriParser`() {
        val parser = ParserFactory.getParser("in.juspay.nammayatripartner")
        assertTrue("Namma Yatri should use NammaYatriParser", parser is NammaYatriParser)
    }

    @Test
    fun `in juspay nammayatri routes to NammaYatriParser`() {
        val parser = ParserFactory.getParser("in.juspay.nammayatri")
        assertTrue("in.juspay.nammayatri should use NammaYatriParser", parser is NammaYatriParser)
    }

    @Test
    fun `net openkochi yatri routes to NammaYatriParser`() {
        val parser = ParserFactory.getParser("net.openkochi.yatri")
        assertTrue("net.openkochi.yatri should use NammaYatriParser", parser is NammaYatriParser)
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
        assertFalse(ParserFactory.isUber("com.olacabs.oladriver"))
    }

    @Test
    fun `isOla returns true for ola packages`() {
        assertTrue(ParserFactory.isOla("com.olacabs.oladriver"))
    }

    @Test
    fun `isOla returns false for non-ola packages`() {
        assertFalse(ParserFactory.isOla("com.ubercab.driver"))
        assertFalse(ParserFactory.isOla("com.rapido.rider"))
    }

    @Test
    fun `isShadowfax returns true for shadowfax packages`() {
        assertTrue(ParserFactory.isShadowfax("in.shadowfax.gandalf"))
    }

    @Test
    fun `isShadowfax returns false for non-shadowfax packages`() {
        assertFalse(ParserFactory.isShadowfax("com.ubercab.driver"))
        assertFalse(ParserFactory.isShadowfax("com.rapido.rider"))
    }

    @Test
    fun `isNammaYatri returns true for nammayatri packages`() {
        assertTrue(ParserFactory.isNammaYatri("in.juspay.nammayatri"))
        assertTrue(ParserFactory.isNammaYatri("net.openkochi.yatri"))
        assertTrue(ParserFactory.isNammaYatri("in.juspay.nammayatripartner"))
    }

    @Test
    fun `isNammaYatri returns false for non-nammayatri packages`() {
        assertFalse(ParserFactory.isNammaYatri("com.ubercab.driver"))
        assertFalse(ParserFactory.isNammaYatri("com.rapido.rider"))
        assertFalse(ParserFactory.isNammaYatri("com.unknown.app"))
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
    fun `getNammaYatriParser returns NammaYatriParser`() {
        assertTrue(ParserFactory.getNammaYatriParser() is NammaYatriParser)
    }

    @Test
    fun `getFallbackParser returns RideDataParser`() {
        assertTrue(ParserFactory.getFallbackParser() is RideDataParser)
    }

    @Test
    fun `shadowfax driver package routes to ShadowfaxParser`() {
        assertTrue("com.shadowfax.driver should route to ShadowfaxParser",
            ParserFactory.getParser("com.shadowfax.driver") is ShadowfaxParser)
    }

    @Test
    fun `unknown shadowfax package routes to fallback`() {
        assertTrue("com.shadowfax.captain should NOT route to ShadowfaxParser",
            ParserFactory.getParser("com.shadowfax.captain") is RideDataParser)
    }

    @Test
    fun `ola driver package routes to OlaParser`() {
        assertTrue("com.ola.driver should route to OlaParser",
            ParserFactory.getParser("com.ola.driver") is OlaParser)
    }

    @Test
    fun `rapido captain package routes to RapidoParser`() {
        assertTrue("com.rapido.captain should route to RapidoParser",
            ParserFactory.getParser("com.rapido.captain") is RapidoParser)
    }
}
