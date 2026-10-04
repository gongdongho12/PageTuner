package com.dongholab.pagetuner.translation

enum class ReaderTranslationLoadStage {
    Idle,
    CheckingCache,
    Queued,
    Translating,
    Ready,
    Missing,
    Failed,
}

data class ReaderTranslationLoadState(
    val documentId: String? = null,
    val pageIndex: Int = -1,
    val stage: ReaderTranslationLoadStage = ReaderTranslationLoadStage.Idle,
) {
    val isLoading: Boolean
        get() = stage in setOf(
            ReaderTranslationLoadStage.CheckingCache,
            ReaderTranslationLoadStage.Queued,
            ReaderTranslationLoadStage.Translating,
        )

    fun matches(documentId: String, pageIndex: Int): Boolean {
        return this.documentId == documentId && this.pageIndex == pageIndex
    }
}
