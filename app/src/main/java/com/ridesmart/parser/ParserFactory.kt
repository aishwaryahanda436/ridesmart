package com.ridesmart.parser

object ParserFactory {

    private val rapidoPackages = setOf(
        "com.rapido.rider",
        "in.rapido.captain",
        "com.rapido.captain"
    )

    // Returns the correct parser for a given package name.
    fun getParser(packageName: String): IPlatformParser {
        return if (isRapido(packageName)) {
            RapidoParser()
        } else {
            RideDataParser()
        }
    }

    fun getRapidoParser(): RapidoParser = RapidoParser()

    fun getFallbackParser(): RideDataParser = RideDataParser()

    fun isRapido(packageName: String): Boolean = packageName in rapidoPackages
}
