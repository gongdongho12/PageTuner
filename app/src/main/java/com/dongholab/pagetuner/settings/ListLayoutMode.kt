package com.dongholab.pagetuner.settings

/**
 * Controls collection screens only. Reader body pagination remains independent because reading
 * progress and rolling translation use document page indexes.
 */
enum class ListLayoutMode {
    Paged,
    Scroll,
}
