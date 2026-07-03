package com.pab.patrifilefinder.data.profile

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Who "me / my / mine" refers to, and who to exclude, from a user-editable config. */
data class UserProfile(
    val me: List<String>,
    val others: List<String>,
) {
    val isEmpty: Boolean get() = me.isEmpty() && others.isEmpty()

    companion object {
        val EMPTY = UserProfile(emptyList(), emptyList())
    }
}

/**
 * Loads the identity config (profile.json). The bundled asset is a template; on first
 * run we copy it into the app's external files dir so it can be edited on-device without
 * rebuilding. Path (visible via adb / a file manager):
 *   /sdcard/Android/data/com.pab.patrifilefinder/files/profile.json
 *
 * The parsed profile is cached for the process lifetime; call [reload] (or restart the
 * app) after editing the file.
 */
@Singleton
class ProfileRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Volatile private var cached: UserProfile? = null

    fun profile(): UserProfile {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            return load().also { cached = it }
        }
    }

    /** Drop the cache so the next [profile] call re-reads the file from disk. */
    fun reload() {
        cached = null
    }

    private fun load(): UserProfile = try {
        val file = File(context.getExternalFilesDir(null), FILE_NAME)
        if (!file.exists()) {
            context.assets.open(FILE_NAME).use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            Log.i(TAG, "seeded editable profile at ${file.absolutePath}")
        }
        parse(file.readText()).also {
            Log.i(TAG, "profile loaded: me=${it.me.size}, others=${it.others.size}")
        }
    } catch (e: Exception) {
        Log.w(TAG, "profile load failed; personalization disabled", e)
        UserProfile.EMPTY
    }

    private fun parse(json: String): UserProfile {
        val obj = JSONObject(json)
        val me = obj.namesFor("me")
        // Every other group (wife, appa, amma, others, …) is "someone else". This lets
        // the config be organised however feels natural — only the "me" key is special.
        val others = obj.keys().asSequence()
            .filter { it != "me" && !it.startsWith("_") }   // skip "me" and _comment
            .flatMap { obj.namesFor(it).asSequence() }
            .distinct()
            .filter { it !in me }                           // a name can't be me and not-me
            .toList()
        return UserProfile(me = me, others = others)
    }

    /**
     * Collects names under [key], accepting both a JSON array and a single string, and
     * splitting comma-separated values ("a, b, c" → a, b, c). Blanks and leftover
     * REPLACE_WITH template lines are dropped, and everything is lower-cased for matching.
     */
    private fun JSONObject.namesFor(key: String): List<String> {
        val out = ArrayList<String>()
        fun addSplit(raw: String) {
            for (part in raw.split(',')) {
                val name = part.trim().lowercase()
                if (name.isNotEmpty() && !name.startsWith("replace_with")) out += name
            }
        }
        when (val value = opt(key)) {
            is JSONArray -> for (i in 0 until value.length()) addSplit(value.optString(i))
            is String -> addSplit(value)
        }
        return out
    }

    companion object {
        private const val TAG = "ProfileRepository"
        private const val FILE_NAME = "profile.json"
    }
}
