package com.dongholab.pagetuner.display

import androidx.annotation.StringRes
import com.dongholab.pagetuner.R

enum class DisplayMode(
    @param:StringRes val labelRes: Int,
) {
    Color(R.string.display_mode_color),
    Grayscale(R.string.display_mode_grayscale),
    Monochrome(R.string.display_mode_monochrome),
    EinkHighContrast(R.string.display_mode_eink_high_contrast),
}
