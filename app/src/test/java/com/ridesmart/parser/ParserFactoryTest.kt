package com.ridesmart.parser

import org.junit.Assert.*
import org.junit.Test

class ParserFactoryTest {

    @Test
    fun `rapido package name returns RapidoParser`() {
        val parser = ParserFactory.getParser("com.rapido.rider")
        assertTrue("Should return RapidoParser", parser is RapidoParser)
    }

    @Test
    fun `uber package name returns UberParser`() {
        val parser = ParserFactory.getParser("com.ubercab.driver")
        assertTrue("Should return UberParser", parser is UberParser)
    }

    @Test
    fun `shadowfax package name returns ShadowfaxParser`() {
        val parser = ParserFactory.getParser("in.shadowfax.gandalf")
        assertTrue("Should return ShadowfaxParser", parser is ShadowfaxParser)
    }

    @Test
    fun `ola package name returns OlaParser`() {
        val parser = ParserFactory.getParser("com.olacabs.oladriver")
        assertTrue("Should return OlaParser", parser is OlaParser)
    }

    @Test
    fun `unknown package name returns RideDataParser fallback`() {
        val parser = ParserFactory.getParser("com.unknown.app")
        assertTrue("Unknown package should return RideDataParser", parser is RideDataParser)
    }

    @Test
    fun `package name case insensitivity for rapido`() {
        val parser = ParserFactory.getParser("com.Rapido.Rider")
        assertTrue("Rapido match should be case-insensitive", parser is RapidoParser)
    }

    @Test
    fun `package name case insensitivity for uber`() {
        val parser = ParserFactory.getParser("com.UberCab.Driver")
        assertTrue("Uber match should be case-insensitive", parser is UberParser)
    }

    @Test
    fun `isRapido returns true for rapido package`() {
        assertTrue(ParserFactory.isRapido("com.rapido.rider"))
    }

    @Test
    fun `isRapido returns false for non-rapido package`() {
        assertFalse(ParserFactory.isRapido("com.ubercab.driver"))
        assertFalse(ParserFactory.isRapido("com.olacabs.oladriver"))
        assertFalse(ParserFactory.isRapido("in.shadowfax.gandalf"))
    }

    @Test
    fun `getRapidoParser returns a RapidoParser instance`() {
        val parser = ParserFactory.getRapidoParser()
        assertTrue("getRapidoParser should return RapidoParser", parser is RapidoParser)
    }

    @Test
    fun `getFallbackParser returns a RideDataParser instance`() {
        val parser = ParserFactory.getFallbackParser()
        assertTrue("getFallbackParser should return RideDataParser", parser is RideDataParser)
    }
}
