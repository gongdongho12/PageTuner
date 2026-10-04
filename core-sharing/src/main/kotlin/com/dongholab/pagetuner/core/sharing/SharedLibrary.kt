package com.dongholab.pagetuner.core.sharing

/** Versioned, read-only views. No filesystem paths, credentials or cloud account are serialized. */
object LocalSharingContract {
    const val VERSION = 1
    const val API_PATH = "/api/share/v1"
    const val SESSION_HEADER = "X-PageTuner-Session"
    const val MAX_PAGE_SIZE = 50
    const val MAX_DOCUMENT_CHARACTERS = 4_000_000
    const val MAX_ASSET_BYTES = 32L * 1024 * 1024
}

data class SharedBookSummary(
    val id: String,
    val title: String,
    /** txt, markdown, epub or pdf; a translation keeps its actual stored format. */
    val format: String,
    /** original or translation; never infer a translation from a filename. */
    val edition: String,
)

data class SharedLibraryPage(val items: List<SharedBookSummary>, val total: Int, val offset: Int, val limit: Int)
data class SharedParagraph(val paragraphId: String, val text: String)
data class SharedOutlineItem(val title: String, val paragraphId: String)
data class SharedReadingAnchor(val paragraphId: String, val characterOffset: Int)
data class SharedAsset(
    val id: String,
    val mimeType: String,
    val byteLength: Long,
    /** pdf or image. Assets are addressed only within the owning document. */
    val role: String,
    val paragraphId: String? = null,
    val alt: String = "",
)

data class SharedDocument(
    val id: String,
    val title: String,
    val format: String,
    val edition: String,
    val language: String,
    /** Snapshot content revision, not a viewport page index or cloud record identity. */
    val revision: String,
    val paragraphs: List<SharedParagraph>,
    val outline: List<SharedOutlineItem> = emptyList(),
    val assets: List<SharedAsset> = emptyList(),
    val anchor: SharedReadingAnchor? = null,
)
