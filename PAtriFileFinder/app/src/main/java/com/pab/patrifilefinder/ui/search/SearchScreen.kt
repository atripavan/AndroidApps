package com.pab.patrifilefinder.ui.search

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.Switch
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pab.patrifilefinder.data.model.FileRecord
import com.pab.patrifilefinder.data.model.Source
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(viewModel: SearchViewModel = hiltViewModel()) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val displayList by viewModel.displayedFiles.collectAsStateWithLifecycle()
    val isSearching by viewModel.isSearching.collectAsStateWithLifecycle()
    val filterState by viewModel.filterState.collectAsStateWithLifecycle()
    val aiSearchEnabled by viewModel.aiSearchEnabled.collectAsStateWithLifecycle()
    val semanticStatus by viewModel.semanticStatus.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showFilterSheet by remember { mutableStateOf(false) }
    var selectedFile by remember { mutableStateOf<FileRecord?>(null) }

    val sectionLabel = if (query.isBlank()) "Recent" else "Results (${displayList.size})"

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            SearchBar(
                inputField = {
                    SearchBarDefaults.InputField(
                        query = query,
                        onQueryChange = viewModel::onQueryChange,
                        onSearch = {},
                        expanded = false,
                        onExpandedChange = {},
                        placeholder = { Text("Search files…") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            IconButton(onClick = { showFilterSheet = true }) {
                                BadgedBox(
                                    badge = {
                                        if (filterState.isActive) {
                                            Badge { Text(filterState.activeCount.toString()) }
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.FilterList, contentDescription = "Filters")
                                }
                            }
                        },
                    )
                },
                expanded = false,
                onExpandedChange = {},
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {}

            // AI (semantic) search toggle. Disabled when the device can't run the
            // on-device model so the user isn't toggling something with no effect.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("AI search", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = when (semanticStatus) {
                            SemanticStatus.WARMING -> "Warming up…"
                            SemanticStatus.AVAILABLE -> "Rank results by meaning, on-device"
                            SemanticStatus.UNAVAILABLE -> "Unavailable on this device"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (semanticStatus == SemanticStatus.WARMING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Switch(
                        checked = aiSearchEnabled && semanticStatus == SemanticStatus.AVAILABLE,
                        onCheckedChange = viewModel::onAiSearchToggle,
                        enabled = semanticStatus == SemanticStatus.AVAILABLE,
                    )
                }
            }

            // Debug/test actions — handy on the emulator while there are no real files indexed.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { viewModel.onScanNow() },
                    enabled = !isScanning,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (isScanning) "Scanning…" else "Scan now")
                }
                OutlinedButton(
                    onClick = { viewModel.onAddMockData() },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.BugReport, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Mock data")
                }
            }

            // Progress hint while a scan is running (files appear as they're indexed).
            if (isScanning) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Scanning files… results appear as they're found",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (isSearching) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                Text(
                    text = sectionLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                LazyColumn {
                    items(displayList, key = { it.id }) { file ->
                        FileRow(
                            file = file,
                            onClick = {
                                viewModel.onFileOpened(file)
                                openFile(context, file)
                            },
                            onLongClick = { selectedFile = file },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (showFilterSheet) {
        FilterBottomSheet(
            filter = filterState,
            onFilterChange = viewModel::onFilterChange,
            onClear = viewModel::onClearFilters,
            onDismiss = { showFilterSheet = false },
        )
    }

    selectedFile?.let { file ->
        FileActionSheet(
            file = file,
            onDismiss = { selectedFile = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FilterBottomSheet(
    filter: FilterState,
    onFilterChange: (FilterState) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var showDatePicker by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Filters", style = MaterialTheme.typography.titleLarge)
                if (filter.isActive) {
                    TextButton(onClick = onClear) { Text("Clear all") }
                }
            }

            Spacer(Modifier.size(8.dp))
            Text("Source", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = filter.source == null,
                    onClick = { onFilterChange(filter.copy(source = null)) },
                    label = { Text("All") },
                )
                Source.entries.forEach { source ->
                    FilterChip(
                        selected = filter.source == source,
                        onClick = {
                            // Tapping the active source again clears it back to "All".
                            val next = if (filter.source == source) null else source
                            onFilterChange(filter.copy(source = next))
                        },
                        label = { Text(source.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    )
                }
            }

            Spacer(Modifier.size(12.dp))
            Text("Type", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FileType.entries.forEach { type ->
                    val selected = type in filter.types
                    FilterChip(
                        selected = selected,
                        onClick = {
                            val next = if (selected) filter.types - type else filter.types + type
                            onFilterChange(filter.copy(types = next))
                        },
                        label = { Text(type.label) },
                    )
                }
            }

            Spacer(Modifier.size(12.dp))
            Text("Date", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.size(4.dp))
            val dateLabel = when {
                filter.dateFrom != null && filter.dateTo != null ->
                    "${formatDate(filter.dateFrom)} – ${formatDate(filter.dateTo)}"
                else -> "Any date"
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.weight(1f)) {
                    Text(dateLabel)
                }
                if (filter.dateFrom != null || filter.dateTo != null) {
                    TextButton(onClick = { onFilterChange(filter.copy(dateFrom = null, dateTo = null)) }) {
                        Text("Reset")
                    }
                }
            }
        }
    }

    if (showDatePicker) {
        DateRangePickerModal(
            onDismiss = { showDatePicker = false },
            onConfirm = { start, end ->
                // Make the end bound inclusive of the whole selected day.
                val inclusiveEnd = end?.let { it + DAY_MILLIS - 1 }
                onFilterChange(filter.copy(dateFrom = start, dateTo = inclusiveEnd))
                showDatePicker = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRangePickerModal(
    onDismiss: () -> Unit,
    onConfirm: (Long?, Long?) -> Unit,
) {
    val state = rememberDateRangePickerState()
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(state.selectedStartDateMillis, state.selectedEndDateMillis) }) {
                Text("OK")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DateRangePicker(state = state, modifier = Modifier.weight(1f))
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun FileActionSheet(file: FileRecord, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Share") },
                leadingContent = { Icon(Icons.Default.Share, contentDescription = null) },
                modifier = Modifier.combinedClickable(onClick = {
                    shareFile(context, file)
                    onDismiss()
                }),
            )
            ListItem(
                headlineContent = { Text("Copy path") },
                leadingContent = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                modifier = Modifier.combinedClickable(onClick = {
                    clipboard.setText(AnnotatedString(file.path))
                    Toast.makeText(context, "Path copied", Toast.LENGTH_SHORT).show()
                    onDismiss()
                }),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(file: FileRecord, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = mimeIcon(file.mimeType),
            contentDescription = null,
            modifier = Modifier.size(36.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${file.source.name.lowercase().replaceFirstChar { it.uppercase() }}  ·  ${formatDate(file.dateAdded)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun mimeIcon(mimeType: String): ImageVector = when {
    mimeType.startsWith("image/") -> Icons.Default.Image
    mimeType.startsWith("video/") -> Icons.Default.VideoFile
    mimeType.startsWith("audio/") -> Icons.Default.MusicNote
    else -> Icons.Default.Description
}

private fun formatDate(epochMillis: Long): String =
    SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(epochMillis))

private fun openFile(context: android.content.Context, file: FileRecord) {
    val f = File(file.path)
    if (!f.exists()) {
        Toast.makeText(context, "File not found", Toast.LENGTH_SHORT).show()
        return
    }
    val uri: Uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        f,
    )
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, file.mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Open with"))
}

private fun shareFile(context: android.content.Context, file: FileRecord) {
    val f = File(file.path)
    if (!f.exists()) {
        Toast.makeText(context, "File not found", Toast.LENGTH_SHORT).show()
        return
    }
    val uri: Uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        f,
    )
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = file.mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share via"))
}

private const val DAY_MILLIS = 24L * 60 * 60 * 1000
