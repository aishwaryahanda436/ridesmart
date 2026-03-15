package com.ridesmart.parser

object ParserFactory {

    fun getParser(packageName: String): IPlatformParser = when (packageName) {
        "com.rapido.rider"      -> RapidoParser()
        else                    -> RideDataParser()
    }

    fun isRapido(packageName: String): Boolean = packageName == "com.rapido.rider"

    fun getRapidoParser(): RapidoParser = RapidoParser()

    fun getFallbackParser(): RideDataParser = RideDataParser()
}
