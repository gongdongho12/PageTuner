package com.dongholab.pagetuner.ui

import androidx.annotation.StringRes
import com.dongholab.pagetuner.R

enum class LanguagePreset(
    val sourceLanguage: String,
    val targetLanguage: String,
    @param:StringRes val labelRes: Int,
) {
    AutoToKorean("auto", "ko", R.string.preset_auto_to_korean),
    EnglishToKorean("en", "ko", R.string.preset_english_to_korean),
    KoreanToEnglish("ko", "en", R.string.preset_korean_to_english),
    AutoToEnglish("auto", "en", R.string.preset_auto_to_english),
}
