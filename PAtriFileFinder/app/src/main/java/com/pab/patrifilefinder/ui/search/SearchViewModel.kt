package com.pab.patrifilefinder.ui.search

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pab.patrifilefinder.data.model.FileRecord
import com.pab.patrifilefinder.data.repository.FileRepository
import com.pab.patrifilefinder.data.settings.SettingsRepository
import com.pab.patrifilefinder.worker.FileScanWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Lifecycle of on-device semantic search, surfaced to the UI. */
enum class SemanticStatus { WARMING, AVAILABLE, UNAVAILABLE }

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: FileRepository,
    private val settings: SettingsRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    /**
     * State of on-device semantic search. Probed off the main thread — loading the
     * model is slow — so it starts [SemanticStatus.WARMING] and settles into
     * [SemanticStatus.AVAILABLE] or [SemanticStatus.UNAVAILABLE].
     */
    private val _semanticStatus = MutableStateFlow(SemanticStatus.WARMING)
    val semanticStatus: StateFlow<SemanticStatus> = _semanticStatus.asStateFlow()

    /** User's AI-search preference (persisted); only takes effect when available. */
    val aiSearchEnabled: StateFlow<Boolean> =
        settings.aiSearchEnabled
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<List<FileRecord>>(emptyList())
    val results: StateFlow<List<FileRecord>> = _results.asStateFlow()

    private val _recentFiles = MutableStateFlow<List<FileRecord>>(emptyList())
    val recentFiles: StateFlow<List<FileRecord>> = _recentFiles.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    /**
     * True while *any* scan is in progress — the manual one (enqueued or running) or
     * the periodic background one (only while actually running; a periodic work sits in
     * ENQUEUED between runs, which must not count). Drives the "Scanning…" hint and
     * blocks a redundant manual scan.
     */
    val isScanning: StateFlow<Boolean> =
        combine(
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkFlow(FileScanWorker.PERIODIC_WORK_NAME),
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkFlow(FileScanWorker.MANUAL_WORK_NAME),
        ) { periodic, manual ->
            val periodicRunning = periodic.any { it.state == WorkInfo.State.RUNNING }
            val manualActive = manual.any { !it.state.isFinished } // ENQUEUED or RUNNING
            periodicRunning || manualActive
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _filterState = MutableStateFlow(FilterState())
    val filterState: StateFlow<FilterState> = _filterState.asStateFlow()

    /**
     * The list actually shown on screen: recents (when the query is blank) or
     * search results, with the active filters applied on top (AND semantics).
     */
    val displayedFiles: StateFlow<List<FileRecord>> =
        combine(_query, _results, _recentFiles, _filterState) { q, results, recent, filter ->
            val base = if (q.isBlank()) recent else results
            if (filter.isActive) base.filter(filter::matches) else base
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // Probe (and warm up) the embedder off the main thread.
        viewModelScope.launch {
            _semanticStatus.value = if (repository.isSemanticAvailable()) {
                SemanticStatus.AVAILABLE
            } else {
                SemanticStatus.UNAVAILABLE
            }
        }

        viewModelScope.launch {
            repository.recentFiles().collectLatest { _recentFiles.value = it }
        }

        // Re-run search when either the query or the AI toggle changes.
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            combine(_query, aiSearchEnabled) { q, ai -> q to ai }
                .debounce(300)
                .collectLatest { (q, ai) ->
                    if (q.isBlank()) {
                        _results.value = emptyList()
                        _isSearching.value = false
                    } else {
                        _isSearching.value = true
                        _results.value = if (ai && _semanticStatus.value == SemanticStatus.AVAILABLE) {
                            repository.semanticSearch(q)
                        } else {
                            repository.search(q)
                        }
                        _isSearching.value = false
                    }
                }
        }
    }

    fun onAiSearchToggle(enabled: Boolean) {
        viewModelScope.launch { settings.setAiSearchEnabled(enabled) }
    }

    fun onQueryChange(newQuery: String) {
        _query.value = newQuery
    }

    fun onFilterChange(filter: FilterState) {
        _filterState.value = filter
    }

    fun onClearFilters() {
        _filterState.value = FilterState()
    }

    fun onFileOpened(file: FileRecord) {
        viewModelScope.launch { repository.incrementOpenCount(file.id) }
    }

    fun onScanNow() {
        // Don't start a manual scan if one is already in progress (manual or the
        // periodic background scan). The button is also disabled while scanning; this
        // guards the programmatic path too.
        if (isScanning.value) return
        FileScanWorker.enqueueNow(context)
    }

    fun onAddMockData() {
        viewModelScope.launch {
            repository.insertMockData()
        }
    }
}
