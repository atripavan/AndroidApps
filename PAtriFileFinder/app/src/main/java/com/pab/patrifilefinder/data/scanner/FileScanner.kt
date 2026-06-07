package com.pab.patrifilefinder.data.scanner

import android.content.Context
import android.provider.MediaStore
import com.pab.patrifilefinder.data.db.FileDao
import com.pab.patrifilefinder.data.model.FileRecord
import com.pab.patrifilefinder.data.model.Source
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileDao: FileDao,
) {
    // WhatsApp moved to Android/media in Android 11+ (scoped storage).
    // We check both so older devices and backup-restored setups are covered.
    private val whatsappDirs = listOf(
        "WhatsApp/Media",
        "Android/media/com.whatsapp/WhatsApp/Media",
    )
    private val telegramDirs = listOf(
        "Telegram",
        "Android/media/org.telegram.messenger/Telegram",
    )

    suspend fun scan() = withContext(Dispatchers.IO) {
        val existingPaths = fileDao.getAllPaths().toHashSet()
        val discovered = mutableListOf<FileRecord>()

        discovered += queryMediaStore(
            relativeDirs = whatsappDirs,
            source = Source.WHATSAPP,
        )
        discovered += queryMediaStore(
            relativeDirs = telegramDirs,
            source = Source.TELEGRAM,
        )
        discovered += queryDownloads()

        // Only upsert files we haven't seen before (path-based deduplication).
        val newFiles = discovered.filter { it.path !in existingPaths }
        if (newFiles.isNotEmpty()) {
            fileDao.upsertAll(newFiles)
        }

        // Remove DB rows whose backing file has been deleted.
        val discoveredPaths = discovered.map { it.path }.toHashSet()
        existingPaths
            .filter { it !in discoveredPaths }
            .forEach { fileDao.deleteByPath(it) }
    }

    private fun queryMediaStore(
        relativeDirs: List<String>,
        source: Source,
    ): List<FileRecord> {
        val results = mutableListOf<FileRecord>()
        val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_ADDED,
        )

        for (relDir in relativeDirs) {
            // RELATIVE_PATH on Android 10+ matches a prefix (e.g. "WhatsApp/Media/WhatsApp Images/")
            val selection = "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?"
            val selectionArgs = arrayOf("$relDir%")

            context.contentResolver.query(
                uri,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Files.FileColumns.DATE_ADDED} DESC",
            )?.use { cursor ->
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)

                while (cursor.moveToNext()) {
                    val path = cursor.getString(pathCol) ?: continue
                    results += FileRecord(
                        name = cursor.getString(nameCol) ?: path.substringAfterLast('/'),
                        path = path,
                        source = source,
                        mimeType = cursor.getString(mimeCol) ?: "application/octet-stream",
                        sizeBytes = cursor.getLong(sizeCol),
                        dateAdded = cursor.getLong(dateCol) * 1000L, // epoch seconds → millis
                    )
                }
            }
        }
        return results
    }

    private fun queryDownloads(): List<FileRecord> {
        val results = mutableListOf<FileRecord>()
        val uri = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.DATA,
            MediaStore.Downloads.MIME_TYPE,
            MediaStore.Downloads.SIZE,
            MediaStore.Downloads.DATE_ADDED,
        )

        context.contentResolver.query(
            uri,
            projection,
            null,
            null,
            "${MediaStore.Downloads.DATE_ADDED} DESC",
        )?.use { cursor ->
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATA)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_ADDED)

            while (cursor.moveToNext()) {
                val path = cursor.getString(pathCol) ?: continue
                results += FileRecord(
                    name = cursor.getString(nameCol) ?: path.substringAfterLast('/'),
                    path = path,
                    source = Source.DOWNLOADS,
                    mimeType = cursor.getString(mimeCol) ?: "application/octet-stream",
                    sizeBytes = cursor.getLong(sizeCol),
                    dateAdded = cursor.getLong(dateCol) * 1000L,
                )
            }
        }
        return results
    }
}
