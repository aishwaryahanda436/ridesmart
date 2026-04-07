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

    @TypeConverter
    fun fromStringList(value: List<String>): String {
        return value.joinToString("|||")
    }

    @TypeConverter
    fun toStringList(value: String): List<String> {
        return if (value.isBlank()) emptyList() else value.split("|||")
    }
}
