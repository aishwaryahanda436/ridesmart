package com.ridesmart.parser

import com.ridesmart.service.UberOcrEngine

object ParserFactory {

    fun getParser(packageName: String): IPlatformParser = when {
        packageName.contains("rapido", true)      -> RapidoParser()
        packageName.contains("uber", true)        -> UberParser()
        packageName.contains("shadowfax", true)   -> ShadowfaxParser()
        packageName.contains("olacabs", true)     -> OlaParser()
        else                                      -> RideDataParser()
    }

    fun isRapido(packageName: String): Boolean = packageName.contains("rapido", true)

    fun getRapidoParser(): RapidoParser = RapidoParser()

    fun getFallbackParser(): RideDataParser = RideDataParser()
}
