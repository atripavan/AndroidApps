package com.pab.patrifilefinder.data.embedding

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Wraps MediaPipe's [TextEmbedder] (Universal Sentence Encoder) to turn text into
 * a fixed-length meaning vector, entirely on-device — no data ever leaves the phone.
 *
 * Embeddings are stored in the DB as a little-endian float BLOB. Cosine similarity
 * between two vectors gives a -1..1 "how related is this" score, which we squash to
 * 0..1 for blending with keyword search.
 *
 * The embedder is created lazily and degrades gracefully: if the model asset is
 * missing or the device is too small (low RAM), [isAvailable] is false and semantic
 * search simply falls back to keyword search.
 */
@Singleton
class EmbeddingEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    // TextEmbedder is not documented as thread-safe; scan and query can call embed()
    // from different coroutines, so we guard every call with this lock.
    private val lock = Any()

    @Volatile private var initialised = false
    private var embedder: TextEmbedder? = null

    /**
     * True once the model has loaded successfully and the device has enough RAM.
     * Suspends on a background dispatcher because the first call triggers the native
     * model load, which is slow (seconds, more under emulator ARM translation) and
     * must never run on the main thread.
     */
    suspend fun isAvailable(): Boolean = withContext(Dispatchers.Default) {
        val enoughRam = hasEnoughMemory()
        if (!enoughRam) {
            Log.w(TAG, "isAvailable=false: not enough RAM (< ${MIN_TOTAL_RAM_BYTES / (1024 * 1024)}MB)")
            return@withContext false
        }
        val available = ensureEmbedder() != null
        Log.i(TAG, "isAvailable=$available")
        available
    }

    private fun ensureEmbedder(): TextEmbedder? {
        if (initialised) return embedder
        synchronized(lock) {
            if (initialised) return embedder
            val startedAt = android.os.SystemClock.elapsedRealtime()
            embedder = try {
                val options = TextEmbedder.TextEmbedderOptions.builder()
                    .setBaseOptions(
                        BaseOptions.builder()
                            .setModelAssetPath(MODEL_ASSET)
                            .build()
                    )
                    .setL2Normalize(true)
                    .setQuantize(false)
                    .build()
                TextEmbedder.createFromOptions(context, options).also {
                    val ms = android.os.SystemClock.elapsedRealtime() - startedAt
                    Log.i(TAG, "TextEmbedder loaded in ${ms}ms")
                }
            } catch (e: Throwable) {
                // Catch Throwable, not Exception: a missing native lib surfaces as
                // UnsatisfiedLinkError (an Error, e.g. no x86_64 .so on some emulators),
                // and a missing model asset as an Exception. Either way, semantic search
                // is optional — degrade to keyword-only rather than crash the app.
                Log.w(TAG, "TextEmbedder init failed; semantic search disabled", e)
                null
            }
            initialised = true
            return embedder
        }
    }

    /** Embeds [text] to a float vector, or null if embedding is unavailable/fails. */
    fun embed(text: String): FloatArray? {
        if (text.isBlank()) return null
        val e = ensureEmbedder() ?: return null
        return try {
            synchronized(lock) {
                val result = e.embed(text)
                result.embeddingResult().embeddings().firstOrNull()?.floatEmbedding()
            }
        } catch (ex: Throwable) {
            Log.w(TAG, "embed() failed", ex)
            null
        }
    }

    /** Embeds [text] straight to the BLOB form stored in the DB. */
    fun embedToBytes(text: String): ByteArray? = embed(text)?.toBytes()

    private fun hasEnoughMemory(): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem >= MIN_TOTAL_RAM_BYTES
    }

    companion object {
        private const val TAG = "EmbeddingEngine"
        private const val MODEL_ASSET = "universal_sentence_encoder.tflite"

        // Skip embeddings on very small devices so we don't risk OOM during a scan.
        private const val MIN_TOTAL_RAM_BYTES = 2L * 1024 * 1024 * 1024 // ~2 GB

        /**
         * Raw cosine similarity, clamped to 0..1: 1 = identical meaning, ~0 = unrelated.
         * We deliberately do NOT remap -1..1 onto 0..1 (which would make "unrelated"
         * score 0.5); keeping the natural scale means a similarity threshold actually
         * separates related from unrelated files. Negative cosines (rare for these
         * embeddings) are treated as unrelated.
         */
        fun similarity(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size || a.isEmpty()) return 0f
            var dot = 0f
            var na = 0f
            var nb = 0f
            for (i in a.indices) {
                dot += a[i] * b[i]
                na += a[i] * a[i]
                nb += b[i] * b[i]
            }
            if (na == 0f || nb == 0f) return 0f
            val cosine = dot / (sqrt(na) * sqrt(nb))
            return cosine.coerceIn(0f, 1f)
        }

        fun FloatArray.toBytes(): ByteArray {
            val buffer = ByteBuffer.allocate(size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            forEach { buffer.putFloat(it) }
            return buffer.array()
        }

        fun ByteArray.toFloats(): FloatArray {
            val buffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(size / Float.SIZE_BYTES) { buffer.float }
        }
    }
}
