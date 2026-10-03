package com.dongholab.pagetuner.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.dongholab.pagetuner.R

/** Offline reader font, shared with the browser reader; static weights also support Android 23–25. */
val ReaderFontFamily = FontFamily(
    Font(R.font.noto_serif_kr_regular, weight = FontWeight.Normal),
    Font(R.font.noto_serif_kr_bold, weight = FontWeight.Bold),
)
