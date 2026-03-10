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

    private val olaPackages = setOf(
        "com.olacabs.driver",
        "com.ola.driver"
    )

    private val shadowfaxPackages = setOf(
        "com.shadowfax.driver",
        "com.shadowfax.zeus"
    )

    private val nammayatriPackages = setOf(
        "in.juspay.nammayatri",
        "net.openkochi.yatri",
        "in.juspay.nammayatripartner"
    )

    // Returns the correct parser for a given package name.
    fun getParser(packageName: String): IPlatformParser {
        return when {
            isRapido(packageName)      -> RapidoParser()
            isUber(packageName)        -> UberOcrEngine()
            isOla(packageName)         -> OlaParser()
            isShadowfax(packageName)   -> ShadowfaxParser()
            isNammaYatri(packageName)  -> NammaYatriParser()
            else                       -> RideDataParser()
        }
    }

    fun getRapidoParser(): RapidoParser = RapidoParser()

    fun getUberParser(): UberOcrEngine = UberOcrEngine()

    fun getOlaParser(): OlaParser = OlaParser()

    fun getShadowfaxParser(): ShadowfaxParser = ShadowfaxParser()

    fun getNammaYatriParser(): NammaYatriParser = NammaYatriParser()

    fun getFallbackParser(): RideDataParser = RideDataParser()

    fun isRapido(packageName: String): Boolean = packageName in rapidoPackages

    fun isUber(packageName: String): Boolean = packageName in uberPackages

    fun isOla(packageName: String): Boolean = packageName in olaPackages

    fun isShadowfax(packageName: String): Boolean = packageName in shadowfaxPackages

    fun isNammaYatri(packageName: String): Boolean = packageName in nammayatriPackages
}
