package com.dongholab.pagetuner.reader

import androidx.annotation.StringRes
import com.dongholab.pagetuner.R

enum class PdfFitMode(
    @param:StringRes val labelRes: Int,
) {
    FitPage(R.string.pdf_fit_page),
    FitWidth(R.string.pdf_fit_width),
}
