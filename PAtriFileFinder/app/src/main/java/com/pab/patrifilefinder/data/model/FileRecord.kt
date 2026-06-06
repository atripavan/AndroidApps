package com.pab.patrifilefinder.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class Source { WHATSAPP, TELEGRAM, DOWNLOADS, OTHER }

@Entity(tableName = "files")
data class FileRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val path: String,
    val source: Source,
    val mimeType: String,
    val sizeBytes: Long,
    val dateAdded: Long,
    val textSnippet: String? = null,
    val embedding: ByteArray? = null,
    val openCount: Int = 0
) {
    // ByteArray uses referential equality in data classes by default — override for correctness
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FileRecord) return false
        return id == other.id && path == other.path &&
            embedding.contentEqualsNullable(other.embedding)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + path.hashCode()
        result = 31 * result + (embedding?.contentHashCode() ?: 0)
        return result
    }
}

private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean =
    if (this == null && other == null) true
    else if (this == null || other == null) false
    else contentEquals(other)
