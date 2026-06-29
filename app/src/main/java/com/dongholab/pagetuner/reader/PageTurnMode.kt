package com.dongholab.pagetuner.reader

import androidx.annotation.StringRes
import com.dongholab.pagetuner.R

enum class PageTurnMode(
    @param:StringRes val labelRes: Int,
) {
    LeftPreviousRightNext(R.string.page_turn_left_previous_right_next),
    LeftNextRightPrevious(R.string.page_turn_left_next_right_previous),
    ButtonsOnly(R.string.page_turn_buttons_only),
}
