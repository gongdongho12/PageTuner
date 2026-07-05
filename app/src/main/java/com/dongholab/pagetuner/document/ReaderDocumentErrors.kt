package com.dongholab.pagetuner.document

class UnsupportedReaderDocumentException(
    val detail: String,
) : IllegalArgumentException(detail)
