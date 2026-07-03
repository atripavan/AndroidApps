package com.pab.patrifilefinder.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.pab.patrifilefinder.data.model.FileRecord
import com.pab.patrifilefinder.data.model.FileRecordFts

@Database(
    entities = [FileRecord::class, FileRecordFts::class],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun fileDao(): FileDao
}
