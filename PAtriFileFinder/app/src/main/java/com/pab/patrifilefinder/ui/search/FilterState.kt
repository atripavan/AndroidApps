package com.pab.patrifilefinder.ui.search

import com.pab.patrifilefinder.data.model.FileRecord
import com.pab.patrifilefinder.data.model.Source

/**
 * The file-type categories the user can filter by. Each maps a [FileRecord]'s
 * MIME type to a friendly label and icon-able bucket.
 */
enum class FileType(val label: String) {
    IMAGE("Image"),
    VIDEO("Video"),
    PDF("PDF"),
    DOCUMENT("Document"),
    AUDIO("Audio");

    fun matches(mimeType: String): Boolean = when (this) {
        IMAGE -> mimeType.startsWith("image/")
        VIDEO -> mimeType.startsWith("video/")
        AUDIO -> mimeType.startsWith("audio/")
        PDF -> mimeType == "application/pdf"
        // Everything document-y that isn't a PDF: office docs, text, etc.
        DOCUMENT -> (mimeType.startsWith("application/") && mimeType != "application/pdf") ||
            mimeType.startsWith("text/")
    }
}

/**
 * Holds the currently active filters. An empty/null value means "no filter on
 * that dimension". Filters combine with each other and with the search text (AND).
 */
data class FilterState(
    val source: Source? = null,
    val types: Set<FileType> = emptySet(),
    val dateFrom: Long? = null,
    val dateTo: Long? = null,
) {
    /** How many distinct filters are active — used for the badge on the filter icon. */
    val activeCount: Int
        get() = (if (source != null) 1 else 0) +
            (if (types.isNotEmpty()) 1 else 0) +
            (if (dateFrom != null || dateTo != null) 1 else 0)

    val isActive: Boolean get() = activeCount > 0

    /** True if [file] passes every active filter. */
    fun matches(file: FileRecord): Boolean {
        if (source != null && file.source != source) return false
        if (types.isNotEmpty() && types.none { it.matches(file.mimeType) }) return false
        if (dateFrom != null && file.dateAdded < dateFrom) return false
        if (dateTo != null && file.dateAdded > dateTo) return false
        return true
    }
}
