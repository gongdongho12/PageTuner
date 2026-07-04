package com.dongholab.pagetuner.translation

import androidx.annotation.StringRes
import com.dongholab.pagetuner.R

enum class TranslationDisplayMode(
    @param:StringRes val labelRes: Int,
) {
    OriginalOnly(R.string.translation_display_original_only),
    TranslationOnly(R.string.translation_display_translation_only),
    SideBySide(R.string.translation_display_side_by_side),
}
