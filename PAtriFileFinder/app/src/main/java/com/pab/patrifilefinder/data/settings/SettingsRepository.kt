package com.pab.patrifilefinder.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "settings")

/** Persisted user preferences. Currently just the AI-search toggle. */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val aiSearchEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[AI_SEARCH] ?: true }

    suspend fun setAiSearchEnabled(enabled: Boolean) {
        context.dataStore.edit { it[AI_SEARCH] = enabled }
    }

    private companion object {
        val AI_SEARCH = booleanPreferencesKey("ai_search_enabled")
    }
}
