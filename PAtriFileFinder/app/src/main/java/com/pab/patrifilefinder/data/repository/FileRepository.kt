package com.pab.patrifilefinder.data.repository

import android.util.Log
import com.pab.patrifilefinder.data.db.FileDao
import com.pab.patrifilefinder.data.embedding.EmbeddingEngine
import com.pab.patrifilefinder.data.embedding.EmbeddingEngine.Companion.similarity
import com.pab.patrifilefinder.data.embedding.EmbeddingEngine.Companion.toFloats
import com.pab.patrifilefinder.data.model.FileRecord
import com.pab.patrifilefinder.data.profile.ProfileRepository
import com.pab.patrifilefinder.data.model.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

@Singleton
class FileRepository @Inject constructor(
    private val dao: FileDao,
    private val embeddingEngine: EmbeddingEngine,
    private val profileRepository: ProfileRepository,
) {

    /** Whether on-device semantic search can run (model loaded + enough RAM). */
    suspend fun isSemanticAvailable(): Boolean = embeddingEngine.isAvailable()

    fun recentFiles(limit: Int = 20): Flow<List<FileRecord>> = dao.observeRecent(limit)

    /**
     * Keyword search via FTS4. Appends * for prefix matching
     * ("tax" → matches "tax2024.pdf", "taxes", etc.).
     */
    suspend fun search(query: String): List<FileRecord> {
        if (query.isBlank()) return emptyList()
        val results = dao.searchByKeyword(ftsQuery(query)).sortedByDescending { it.dateAdded }
        return personalize(query, results)
    }

    /**
     * AI search: blends semantic similarity with keyword matching so results are
     * ranked by meaning, with an extra boost for literal keyword hits. Falls back
     * to plain [search] when the embedding engine is unavailable (model missing or
     * low-RAM device).
     *
     * final = [SEMANTIC_WEIGHT] × cosine + [KEYWORD_WEIGHT] × keyword
     * where cosine ∈ 0..1 and keyword is 1 if the file matched FTS else 0. Files that
     * only match semantically must clear [SEMANTIC_THRESHOLD] so unrelated files (which
     * still carry a small positive cosine) don't leak in.
     */
    suspend fun semanticSearch(query: String, limit: Int = 50): List<FileRecord> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()

        val keywordMatches = dao.searchByKeyword(ftsQuery(trimmed))
        val embedded = dao.getAllWithEmbeddings()

        // Embedding + scoring is CPU work (extra-slow under emulator ARM translation),
        // so keep it off the caller's thread.
        return withContext(Dispatchers.Default) {
            val queryVector = embeddingEngine.embed(trimmed) ?: return@withContext keywordMatches
            val keywordPaths = keywordMatches.mapTo(HashSet()) { it.path }

            // Candidate pool = everything embedded, plus any keyword hit that has no
            // embedding yet (so a literal match is never dropped just because it's unindexed).
            val candidates = (embedded + keywordMatches.filter { it.embedding == null })
                .distinctBy { it.path }

            val scored = candidates.mapNotNull { file ->
                val cosine = file.embedding
                    ?.let { similarity(queryVector, it.toFloats()) }
                    ?: 0f
                val isKeyword = file.path in keywordPaths

                // A literal keyword hit always qualifies. A semantic-only match must be
                // clearly related (cosine over the threshold) — otherwise nearly every
                // file leaks in, since unrelated files still have a small positive cosine.
                if (!isKeyword && cosine < SEMANTIC_THRESHOLD) return@mapNotNull null

                val score = SEMANTIC_WEIGHT * cosine + (if (isKeyword) KEYWORD_WEIGHT else 0f)
                Triple(file, score, cosine)
            }
                // Relevance first, but treat scores within SCORE_EPSILON as a tie and
                // break it by recency — so among similarly-related files the newest wins.
                .sortedWith(
                    compareByDescending<Triple<FileRecord, Float, Float>> { (it.second / SCORE_EPSILON).roundToInt() }
                        .thenByDescending { it.first.dateAdded }
                )

            if (scored.isNotEmpty()) {
                val top = scored.take(5).joinToString(" | ") {
                    "${it.first.name}=score ${"%.2f".format(it.second)}/cos ${"%.2f".format(it.third)}"
                }
                Log.d(TAG, "semanticSearch(\"$trimmed\"): ${scored.size} hits — top: $top")
            }

            personalize(trimmed, scored.take(limit).map { it.first })
        }
    }

    /**
     * Builds the FTS query, dropping first-person words ("my", "me", …) so a natural
     * phrase like "my medical records" keyword-matches on "medical records" instead of
     * requiring the literal filler words (which no filename contains). Trailing * gives
     * prefix matching.
     */
    private fun ftsQuery(raw: String): String {
        val terms = raw.trim().split(Regex("\\s+"))
            .filter { it.isNotBlank() && it.lowercase() !in FIRST_PERSON_TERMS }
        val cleaned = if (terms.isEmpty()) raw.trim() else terms.joinToString(" ")
        return "$cleaned*"
    }

    /**
     * When the query is first-person ("my medical records"), narrow results to the user:
     * keep files that mention one of their names, drop files that mention someone else,
     * and keep un-attributed files (we can't tell whose those are, so we don't hide them).
     * A no-op when the query isn't first-person or the profile is empty.
     */
    private fun personalize(query: String, results: List<FileRecord>): List<FileRecord> {
        val profile = profileRepository.profile()
        if (profile.isEmpty) return results

        val tokens = query.lowercase().split(Regex("[^a-z0-9]+")).toHashSet()
        if (tokens.none { it in FIRST_PERSON_TERMS }) return results

        val me = profile.me.map { it.lowercase() }
        val others = profile.others.map { it.lowercase() }
        val mine = ArrayList<FileRecord>()
        val neutral = ArrayList<FileRecord>()
        for (file in results) {
            val haystack = (file.name + " " + (file.textSnippet ?: "")).lowercase()
            when {
                me.any { haystack.contains(it) } -> mine += file       // definitely mine
                others.any { haystack.contains(it) } -> Unit           // someone else's — drop
                else -> neutral += file                                // unknown owner — keep
            }
        }
        Log.d(TAG, "personalize(\"$query\"): ${mine.size} mine + ${neutral.size} neutral (from ${results.size})")
        return mine + neutral
    }

    suspend fun incrementOpenCount(id: Long) = dao.incrementOpenCount(id)

    suspend fun insertMockData() {
        val mockFiles = listOf(
            FileRecord(
                name = "Budget_2024.pdf",
                path = "/sdcard/Download/Budget_2024.pdf",
                source = Source.DOWNLOADS,
                mimeType = "application/pdf",
                sizeBytes = 1024 * 500,
                dateAdded = System.currentTimeMillis()
            ),
            FileRecord(
                name = "Family_Photo.jpg",
                path = "/sdcard/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/Family_Photo.jpg",
                source = Source.WHATSAPP,
                mimeType = "image/jpeg",
                sizeBytes = 1024 * 200,
                dateAdded = System.currentTimeMillis() - 3600000
            ),
            FileRecord(
                name = "Voice_Note.ogg",
                path = "/sdcard/Telegram/Telegram Audio/Voice_Note.ogg",
                source = Source.TELEGRAM,
                mimeType = "audio/ogg",
                sizeBytes = 1024 * 50,
                dateAdded = System.currentTimeMillis() - 86400000
            )
        )
        dao.upsertAll(mockFiles)
    }

    companion object {
        private const val TAG = "FileRepository"

        // Words that signal the user is asking for their own files, triggering the
        // mine/others filter. Also stripped from keyword queries as filler.
        private val FIRST_PERSON_TERMS = setOf("my", "me", "mine", "myself", "i", "our", "ours", "own")

        private const val SEMANTIC_WEIGHT = 0.6f
        private const val KEYWORD_WEIGHT = 0.4f

        // Minimum raw cosine for a semantic-only match to count as "related". Raise it
        // for stricter/fewer results, lower it for looser/more. Keyword hits ignore this.
        private const val SEMANTIC_THRESHOLD = 0.35f

        // Scores within this window are considered a tie and ordered by recency instead.
        // Larger = recency matters more; smaller = relevance dominates.
        private const val SCORE_EPSILON = 0.05f
    }
}
