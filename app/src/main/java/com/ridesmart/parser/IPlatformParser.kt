package com.ridesmart.parser

import com.ridesmart.model.ParseResult
import com.ridesmart.model.ScreenState

interface IPlatformParser {
    fun detectScreenState(nodes: List<String>): ScreenState
    fun parseAll(nodes: List<String>, packageName: String): ParseResult
}
