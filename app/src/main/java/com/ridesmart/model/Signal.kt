package com.ridesmart.model

enum class Signal {
    GREEN,   // All checks passed — good ride
    YELLOW,  // Some checks failed — borderline
    RED      // Most checks failed — skip this ride
}
