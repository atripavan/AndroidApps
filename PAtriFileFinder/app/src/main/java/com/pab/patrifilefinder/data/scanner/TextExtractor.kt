package com.pab.patrifilefinder.data.scanner

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pulls a short text snippet out of a file's contents so it can be indexed for
 * full-text (and later, semantic) search. Only handles formats we can read
 * cheaply on-device today: PDFs (via PdfBox) and plain text.
 */
@Singleton
class TextExtractor @Inject constructor() {

    /**
     * Returns up to [SNIPPET_LENGTH] characters of meaningful text from [file],
     * or null if the type is unsupported or extraction fails. Failures are
     * swallowed deliberately — a single unreadable file must never abort a scan.
     */
    fun extract(file: File, mimeType: String): String? {
        if (!file.exists() || !file.canRead()) return null
        return try {
            val raw = when {
                mimeType == "application/pdf" -> extractPdf(file)
                mimeType.startsWith("text/") -> file.readText()
                else -> return null
            }
            raw.normaliseWhitespace().take(SNIPPET_LENGTH).ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }

    private fun extractPdf(file: File): String {
        PDDocument.load(file).use { doc ->
            val stripper = PDFTextStripper().apply {
                startPage = 1
                // First couple of pages are plenty for a 500-char snippet.
                endPage = minOf(doc.numberOfPages, MAX_PDF_PAGES)
            }
            return stripper.getText(doc)
        }
    }

    private fun String.normaliseWhitespace(): String =
        trim().replace(Regex("\\s+"), " ")

    companion object {
        private const val SNIPPET_LENGTH = 500
        private const val MAX_PDF_PAGES = 2
    }
}
