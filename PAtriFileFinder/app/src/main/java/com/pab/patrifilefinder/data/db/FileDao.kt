package com.pab.patrifilefinder.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.pab.patrifilefinder.data.model.FileRecord
import com.pab.patrifilefinder.data.model.Source
import kotlinx.coroutines.flow.Flow

@Dao
interface FileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(file: FileRecord)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(files: List<FileRecord>)

    @Delete
    suspend fun delete(file: FileRecord)

    @Query("DELETE FROM files WHERE path = :path")
    suspend fun deleteByPath(path: String)

    @Query("DELETE FROM files")
    suspend fun deleteAll()

    @Query("SELECT * FROM files ORDER BY dateAdded DESC LIMIT :limit")
    fun observeRecent(limit: Int = 20): Flow<List<FileRecord>>

    @Query("SELECT * FROM files ORDER BY openCount DESC LIMIT :limit")
    fun observeFrequent(limit: Int = 20): Flow<List<FileRecord>>

    @Query("SELECT path FROM files")
    suspend fun getAllPaths(): List<String>

    @Query("SELECT * FROM files WHERE path = :path LIMIT 1")
    suspend fun getByPath(path: String): FileRecord?

    @Query("UPDATE files SET openCount = openCount + 1 WHERE id = :id")
    suspend fun incrementOpenCount(id: Long)

    @Query("UPDATE files SET embedding = :embedding WHERE id = :id")
    suspend fun updateEmbedding(id: Long, embedding: ByteArray)

    // Keyword search via FTS4 — joins back to main table to return full FileRecord rows.
    // Caller should append * to the query string for prefix matching (e.g. "tax*").
    @Query("""
        SELECT files.* FROM files
        INNER JOIN files_fts ON files.rowid = files_fts.rowid
        WHERE files_fts MATCH :query
        ORDER BY files.dateAdded DESC
    """)
    suspend fun searchByKeyword(query: String): List<FileRecord>

    @Query("SELECT * FROM files WHERE source = :source ORDER BY dateAdded DESC")
    fun observeBySource(source: Source): Flow<List<FileRecord>>

    @Query("SELECT * FROM files WHERE mimeType LIKE :mimePrefix || '%' ORDER BY dateAdded DESC")
    suspend fun getByMimePrefix(mimePrefix: String): List<FileRecord>

    @Query("SELECT * FROM files WHERE dateAdded BETWEEN :from AND :to ORDER BY dateAdded DESC")
    suspend fun getByDateRange(from: Long, to: Long): List<FileRecord>
}
