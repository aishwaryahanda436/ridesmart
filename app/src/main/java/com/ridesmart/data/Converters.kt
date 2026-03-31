package com.ridesmart.data

import androidx.room.TypeConverter
import com.ridesmart.model.Signal

class Converters {
    @TypeConverter
    fun fromSignal(value: Signal): String {
        return value.name
    }

    @TypeConverter
    fun toSignal(value: String): Signal {
        return Signal.valueOf(value)
    }
}
