package com.pab.patrifilefinder.data.model

import androidx.room.Entity
import androidx.room.Fts4

@Entity(tableName = "files_fts")
@Fts4(contentEntity = FileRecord::class)
data class FileRecordFts(
    val name: String,
    val textSnippet: String?
)
