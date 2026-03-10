package com.ridesmart.parser

import com.ridesmart.model.ParsedRide
import com.ridesmart.model.ScreenState

interface IPlatformParser {
    fun detectScreenState(nodes: List<String>): ScreenState
    fun parseAll(nodes: List<String>, packageName: String): List<ParsedRide>
}