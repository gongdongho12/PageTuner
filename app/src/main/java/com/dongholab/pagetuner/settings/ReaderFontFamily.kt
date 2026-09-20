package com.dongholab.pagetuner.settings

import androidx.annotation.StringRes
import com.dongholab.pagetuner.R

enum class ReaderFontFamily(
    @param:StringRes val labelRes: Int,
) {
    DEFAULT(R.string.font_family_default),
    SERIF(R.string.font_family_serif),
    SANS_SERIF(R.string.font_family_sans_serif),
    MONOSPACE(R.string.font_family_monospace),
}
