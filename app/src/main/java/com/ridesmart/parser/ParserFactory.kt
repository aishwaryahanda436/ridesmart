package com.ridesmart.parser

import com.ridesmart.service.UberOcrEngine

object ParserFactory {

    private val rapidoPackages = setOf(
        "com.rapido.rider"
    )

    private val uberPackages = setOf(
        "com.ubercab.driver",
        "com.ubercab"
    )

    private val olaPackages = setOf(
        "com.olacabs.oladriver"
    )

    private val shadowfaxPackages = setOf(
        "in.shadowfax.gandalf"
    )

    private val nammayatriPackages = setOf(
        "in.juspay.nammayatri",
        "net.openkochi.yatri",
        "in.juspay.nammayatripartner"
    )

    // ── Singleton parser instances ────────────────────────────────────────────
    // Parsers are stateless (regex-only logic); UberOcrEngine's TextRecognizer is
    // thread-safe per ML Kit docs. Caching avoids recreating a TextRecognizer
    // (JNI/native init cost) on every accessibility event.
    private val rapido     = RapidoParser()
    private val uber       = UberOcrEngine()
    private val ola        = OlaParser()
    private val shadowfax  = ShadowfaxParser()
    private val nammaYatri = NammaYatriParser()
    private val fallback   = RideDataParser()

    // Returns the correct parser for a given package name.
    fun getParser(packageName: String): IPlatformParser = when {
        isRapido(packageName)     -> rapido
        isUber(packageName)       -> uber
        isOla(packageName)        -> ola
        isShadowfax(packageName)  -> shadowfax
        isNammaYatri(packageName) -> nammaYatri
        else                      -> fallback
    }

    fun getRapidoParser(): RapidoParser     = rapido
    fun getUberParser(): UberOcrEngine      = uber
    fun getOlaParser(): OlaParser           = ola
    fun getShadowfaxParser(): ShadowfaxParser   = shadowfax
    fun getNammaYatriParser(): NammaYatriParser = nammaYatri
    fun getFallbackParser(): RideDataParser     = fallback

    fun isRapido(packageName: String): Boolean     = packageName in rapidoPackages
    fun isUber(packageName: String): Boolean       = packageName in uberPackages
    fun isOla(packageName: String): Boolean        = packageName in olaPackages
    fun isShadowfax(packageName: String): Boolean  = packageName in shadowfaxPackages
    fun isNammaYatri(packageName: String): Boolean = packageName in nammayatriPackages
}
