package com.dongholab.pagetuner.library

import com.dongholab.pagetuner.reader.ReaderAnnotation
import com.dongholab.pagetuner.reader.ReaderAnnotationType
import com.dongholab.pagetuner.reader.ReaderBookmark

fun LocalBookBookmark.toReaderBookmark(): ReaderBookmark {
    return ReaderBookmark(
        id = id,
        pageIndex = pageIndex,
        label = label,
        createdAtMillis = createdAtMillis,
    )
}

fun ReaderBookmark.toLocalBookBookmark(): LocalBookBookmark {
    return LocalBookBookmark(
        id = id,
        pageIndex = pageIndex,
        label = label,
        createdAtMillis = createdAtMillis,
    )
}

fun LocalBookAnnotation.toReaderAnnotation(): ReaderAnnotation {
    return ReaderAnnotation(
        id = id,
        type = when (type) {
            LocalBookAnnotationType.Highlight -> ReaderAnnotationType.Highlight
            LocalBookAnnotationType.Note -> ReaderAnnotationType.Note
        },
        pageIndex = pageIndex,
        text = text,
        createdAtMillis = createdAtMillis,
    )
}

fun ReaderAnnotation.toLocalBookAnnotation(): LocalBookAnnotation {
    return LocalBookAnnotation(
        id = id,
        type = when (type) {
            ReaderAnnotationType.Highlight -> LocalBookAnnotationType.Highlight
            ReaderAnnotationType.Note -> LocalBookAnnotationType.Note
        },
        pageIndex = pageIndex,
        text = text,
        createdAtMillis = createdAtMillis,
    )
}
