package com.ridesmart.parser

import com.ridesmart.service.UberOcrEngine

object ParserFactory {

    private val rapidoPackages = setOf(
        "com.rapido.rider",
        "in.rapido.captain",
        "com.rapido.captain"
    )

    private val uberPackages = setOf(
        "com.ubercab.driver",
        "com.ubercab"
    )

    // Returns the correct parser for a given package name.
    fun getParser(packageName: String): IPlatformParser {
        return when {
            isRapido(packageName) -> RapidoParser()
            isUber(packageName)   -> UberOcrEngine()
            else                  -> RideDataParser()
        }
    }

    fun getRapidoParser(): RapidoParser = RapidoParser()

    fun getUberParser(): UberOcrEngine = UberOcrEngine()

    fun getFallbackParser(): RideDataParser = RideDataParser()

    fun isRapido(packageName: String): Boolean = packageName in rapidoPackages

    fun isUber(packageName: String): Boolean = packageName in uberPackages
}
