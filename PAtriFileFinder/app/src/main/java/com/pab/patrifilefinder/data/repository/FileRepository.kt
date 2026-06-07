package com.pab.patrifilefinder.data.repository

import com.pab.patrifilefinder.data.db.FileDao
import com.pab.patrifilefinder.data.model.FileRecord
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileRepository @Inject constructor(private val dao: FileDao) {

    fun recentFiles(limit: Int = 20): Flow<List<FileRecord>> = dao.observeRecent(limit)

    suspend fun search(query: String): List<FileRecord> {
        if (query.isBlank()) return emptyList()
        // Append * for prefix matching ("tax" → matches "tax2024.pdf", "taxes", etc.)
        return dao.searchByKeyword("${query.trim()}*")
    }

    suspend fun incrementOpenCount(id: Long) = dao.incrementOpenCount(id)
}
