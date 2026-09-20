package com.dongholab.pagetuner.ui.reader

import androidx.compose.ui.text.font.FontFamily
import com.dongholab.pagetuner.settings.ReaderFontFamily

fun ReaderFontFamily.toComposeFontFamily(): FontFamily = when (this) {
    ReaderFontFamily.DEFAULT -> FontFamily.Default
    ReaderFontFamily.SERIF -> FontFamily.Serif
    ReaderFontFamily.SANS_SERIF -> FontFamily.SansSerif
    ReaderFontFamily.MONOSPACE -> FontFamily.Monospace
}
