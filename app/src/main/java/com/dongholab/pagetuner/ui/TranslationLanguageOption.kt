package com.dongholab.pagetuner.ui

import androidx.annotation.StringRes
import com.dongholab.pagetuner.R

enum class TranslationLanguageOption(
    val code: String,
    @param:StringRes val labelRes: Int,
    val canBeSource: Boolean = true,
    val canBeTarget: Boolean = true,
) {
    Auto("auto", R.string.language_auto, canBeTarget = false),
    Korean("ko", R.string.language_korean),
    English("en", R.string.language_english),
    Japanese("ja", R.string.language_japanese),
    Chinese("zh", R.string.language_chinese),
}
