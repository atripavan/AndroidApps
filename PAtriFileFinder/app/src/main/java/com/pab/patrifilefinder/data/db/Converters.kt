package com.pab.patrifilefinder.data.db

import androidx.room.TypeConverter
import com.pab.patrifilefinder.data.model.Source

class Converters {
    @TypeConverter
    fun fromSource(source: Source): String = source.name

    @TypeConverter
    fun toSource(value: String): Source = Source.valueOf(value)
}
