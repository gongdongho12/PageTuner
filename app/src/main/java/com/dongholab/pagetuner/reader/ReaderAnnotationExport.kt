package com.dongholab.pagetuner.reader

import com.dongholab.pagetuner.document.ReaderDocument

object ReaderAnnotationExport {
    fun buildText(
        document: ReaderDocument,
        annotations: List<ReaderAnnotation>,
    ): String {
        val body = annotations
            .sortedWith(compareBy<ReaderAnnotation> { it.pageIndex }.thenBy { it.createdAtMillis })
            .joinToString(separator = "\n\n") { annotation ->
                val type = when (annotation.type) {
                    ReaderAnnotationType.Highlight -> "Highlight"
                    ReaderAnnotationType.Note -> "Note"
                }
                "[$type] Page ${annotation.pageIndex + 1}\n${annotation.text}"
            }
            .ifBlank { "No highlights or notes." }

        return "${document.title}\n${"=".repeat(document.title.length.coerceAtLeast(1))}\n\n$body"
    }
}
