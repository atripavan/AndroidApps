package com.pab.patrifilefinder.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pab.patrifilefinder.data.model.FileRecord
import com.pab.patrifilefinder.data.repository.FileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: FileRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<List<FileRecord>>(emptyList())
    val results: StateFlow<List<FileRecord>> = _results.asStateFlow()

    private val _recentFiles = MutableStateFlow<List<FileRecord>>(emptyList())
    val recentFiles: StateFlow<List<FileRecord>> = _recentFiles.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    init {
        viewModelScope.launch {
            repository.recentFiles().collectLatest { _recentFiles.value = it }
        }

        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            _query
                .debounce(300)
                .collectLatest { q ->
                    if (q.isBlank()) {
                        _results.value = emptyList()
                        _isSearching.value = false
                    } else {
                        _isSearching.value = true
                        _results.value = repository.search(q)
                        _isSearching.value = false
                    }
                }
        }
    }

    fun onQueryChange(newQuery: String) {
        _query.value = newQuery
    }

    fun onFileOpened(file: FileRecord) {
        viewModelScope.launch { repository.incrementOpenCount(file.id) }
    }
}
