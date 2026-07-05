package com.dongholab.pagetuner.display

data class DisplayPalette(
    val paperArgb: Int,
    val panelArgb: Int,
    val softArgb: Int,
    val inkArgb: Int,
    val mutedArgb: Int,
    val lineArgb: Int,
)

fun DisplayMode.servicePalette(): DisplayPalette {
    return when (this) {
        DisplayMode.Color -> ColorServicePalette
        DisplayMode.Grayscale,
        DisplayMode.Monochrome,
        DisplayMode.EinkHighContrast -> MonochromeServicePalette
    }
}

val ColorServicePalette = DisplayPalette(
    paperArgb = 0xFFF7F7F2.toInt(),
    panelArgb = 0xFFFFFFFF.toInt(),
    softArgb = 0xFFF0F1EC.toInt(),
    inkArgb = 0xFF111111.toInt(),
    mutedArgb = 0xFF555A52.toInt(),
    lineArgb = 0xFFB8BDB2.toInt(),
)

val MonochromeServicePalette = DisplayPalette(
    paperArgb = 0xFFFFFFFF.toInt(),
    panelArgb = 0xFFFFFFFF.toInt(),
    softArgb = 0xFFF2F2F2.toInt(),
    inkArgb = 0xFF000000.toInt(),
    mutedArgb = 0xFF444444.toInt(),
    lineArgb = 0xFF999999.toInt(),
)
