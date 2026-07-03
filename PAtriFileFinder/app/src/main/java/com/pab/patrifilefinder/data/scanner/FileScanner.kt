package com.pab.patrifilefinder.data.scanner

import android.content.Context
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import com.pab.patrifilefinder.data.db.FileDao
import com.pab.patrifilefinder.data.embedding.EmbeddingEngine
import com.pab.patrifilefinder.data.model.FileRecord
import com.pab.patrifilefinder.data.model.Source
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileDao: FileDao,
    private val textExtractor: TextExtractor,
    private val embeddingEngine: EmbeddingEngine,
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

    // Serializes scans across the whole app. The manual "Scan now" work and the
    // periodic background work are separate WorkManager jobs, so without this they can
    // overlap and each embed every file — doubling CPU/battery. With it, a second scan
    // waits for the first, then finds nothing new to do and exits in ~1s.
    private val scanMutex = Mutex()

    suspend fun scan() = withContext(Dispatchers.IO) {
        if (scanMutex.isLocked) Log.i(TAG, "scan: another scan is running — waiting for it to finish")
        scanMutex.withLock { runScan() }
    }

    private suspend fun runScan() {
        val startedAt = SystemClock.elapsedRealtime()
        Log.i(TAG, "scan: started")
        val existingPaths = fileDao.getAllPaths().toHashSet()
        Log.i(TAG, "scan: ${existingPaths.size} existing rows in DB")

        // MediaStore is the primary source. A direct filesystem walk is a fallback
        // for files MediaStore hasn't indexed yet (e.g. dropped onto the device
        // manually, or restored from a backup). Requires MANAGE_EXTERNAL_STORAGE.
        // Keyed by path so the richer MediaStore entry wins over a filesystem one.
        val discovered = LinkedHashMap<String, FileRecord>()
        var rawSeen = 0
        var skippedNonDoc = 0
        fun merge(source: String, records: List<FileRecord>) {
            var kept = 0
            for (record in records) {
                rawSeen++
                if (!isScannable(record.mimeType, record.path)) { skippedNonDoc++; continue }
                if (discovered.putIfAbsent(record.path, record) == null) kept++
            }
            Log.i(TAG, "discover[$source]: ${records.size} found, $kept new (running total ${discovered.size})")
        }

        merge("mediastore/whatsapp", queryMediaStore(relativeDirs = whatsappDirs, source = Source.WHATSAPP))
        merge("mediastore/telegram", queryMediaStore(relativeDirs = telegramDirs, source = Source.TELEGRAM))
        merge("mediastore/downloads", queryDownloads())

        merge("fs/whatsapp", scanFilesystem(relativeDirs = whatsappDirs, source = Source.WHATSAPP))
        merge("fs/telegram", scanFilesystem(relativeDirs = telegramDirs, source = Source.TELEGRAM))
        merge("fs/downloads", scanFilesystem(relativeDirs = listOf("Download"), source = Source.DOWNLOADS))

        Log.i(
            TAG,
            "discover: done — ${discovered.size} documents (raw seen $rawSeen, $skippedNonDoc non-document skipped)"
        )

        // Phase 1 — index new files (name + text snippet) in batches. Inserting per
        // batch means results appear quickly and progress is saved as we go, so a stop
        // mid-scan (the OS reclaiming the worker, a manual cancel) only loses the
        // current batch instead of everything. ensureActive() makes the loop cooperate
        // with cancellation so a stopped worker actually stops.
        val newFiles = discovered.values.filter { it.path !in existingPaths }
        Log.i(TAG, "index: ${newFiles.size} new files to insert")
        var inserted = 0
        newFiles
            .chunked(INDEX_BATCH)
            .forEach { batch ->
                currentCoroutineContext().ensureActive()
                val processed = batch.map { record ->
                    val snippet = textExtractor.extract(File(record.path), record.mimeType)
                    if (snippet != null) record.copy(textSnippet = snippet) else record
                }
                fileDao.insertAllIgnoringDuplicates(processed)
                inserted += processed.size
                Log.i(TAG, "index: inserted batch, $inserted/${newFiles.size}")
            }

        // Remove DB rows that are no longer discovered — either the backing file was
        // deleted, or it's a type we no longer index (e.g. audio/.status rows left over
        // from a previous, looser filter). This self-heals the index on the next scan.
        val stale = existingPaths.filter { it !in discovered.keys }
        stale.forEach { fileDao.deleteByPath(it) }
        if (stale.isNotEmpty()) Log.i(TAG, "index: removed ${stale.size} stale rows")

        // Phase 2 — backfill semantic embeddings, the slow part, after files are already
        // searchable by keyword. It's resumable: only files still missing an embedding
        // are processed, so an interrupted run just continues next time rather than
        // starting over. Skipped entirely when the embedder isn't available.
        if (embeddingEngine.isAvailable()) {
            val toEmbed = fileDao.getFilesWithoutEmbedding()
            Log.i(TAG, "embed: engine available, ${toEmbed.size} files to embed")
            var embedded = 0
            toEmbed
                .chunked(EMBED_BATCH)
                .forEach { batch ->
                    currentCoroutineContext().ensureActive()
                    for (file in batch) {
                        val embedding = embeddingEngine.embedToBytes(file.embeddingText())
                        if (embedding != null) fileDao.updateEmbedding(file.id, embedding)
                    }
                    embedded += batch.size
                    Log.i(TAG, "embed: $embedded/${toEmbed.size}")
                }
        } else {
            Log.i(TAG, "embed: engine unavailable — keyword-only index")
        }

        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
        Log.i(TAG, "scan: done in ${elapsedMs}ms — inserted $inserted, removed ${stale.size}")
    }

    /**
     * Walks [relativeDirs] (relative to external storage root) directly on the
     * filesystem and builds a [FileRecord] for every file found. This is the
     * fallback path that catches files MediaStore doesn't know about; metadata
     * (mime type, date) is inferred from the file itself rather than the index.
     */
    private fun scanFilesystem(relativeDirs: List<String>, source: Source): List<FileRecord> {
        val root = Environment.getExternalStorageDirectory() ?: return emptyList()
        val results = mutableListOf<FileRecord>()
        for (relDir in relativeDirs) {
            val dir = File(root, relDir)
            if (!dir.isDirectory) continue
            dir.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val mime = mimeTypeOf(file)
                    // Prune non-documents here rather than allocating a record and
                    // dropping it later in merge() — these folders hold the bulk of a
                    // messenger's files, so this keeps discovery fast and light.
                    if (!isScannable(mime, file.path)) return@forEach
                    results += FileRecord(
                        name = file.name,
                        path = file.absolutePath,
                        source = source,
                        mimeType = mime,
                        sizeBytes = file.length(),
                        dateAdded = file.lastModified(),
                    )
                }
        }
        return results
    }

    private fun mimeTypeOf(file: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            ?: "application/octet-stream"

    // This app indexes documents only — not media (images/video/audio), not app data
    // (.status, .db, .apk …). We allowlist by file extension because messenger files
    // often carry inconsistent or missing mime types; a text/* mime is also accepted so
    // plain-text files with unusual extensions still get in.
    private fun isScannable(mimeType: String, path: String): Boolean {
        val ext = path.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return ext in DOCUMENT_EXTENSIONS || mimeType.startsWith("text/")
    }

    /** The text we embed for a file: its name plus any extracted content snippet. */
    private fun FileRecord.embeddingText(): String =
        listOfNotNull(name, textSnippet).joinToString(" ").trim()

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

    private companion object {
        const val TAG = "FileScanner"

        // The only file types we index — documents and plain text. Everything else
        // (images, video, audio like .opus, WhatsApp .status, .apk, .db, …) is ignored.
        val DOCUMENT_EXTENSIONS = setOf(
            // PDF & e-books
            "pdf", "epub",
            // Word processing
            "doc", "docx", "odt", "rtf", "pages",
            // Spreadsheets
            "xls", "xlsx", "ods", "csv", "numbers",
            // Presentations
            "ppt", "pptx", "pps", "ppsx", "odp", "key",
            // Plain text & notes
            "txt", "md", "log", "tex",
        )

        // Files indexed per DB write in phase 1 (cheap: name + snippet).
        const val INDEX_BATCH = 100

        // Files embedded per cancellation checkpoint in phase 2 (expensive: inference).
        const val EMBED_BATCH = 25
    }
}
