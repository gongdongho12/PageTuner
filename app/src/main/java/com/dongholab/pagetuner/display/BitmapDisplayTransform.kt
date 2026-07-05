package com.dongholab.pagetuner.display

import android.graphics.Bitmap
import android.graphics.Color

fun Bitmap.applyDisplayMode(displayMode: DisplayMode) {
    when (displayMode) {
        DisplayMode.Color -> Unit
        DisplayMode.Grayscale -> applyGrayscale()
        DisplayMode.Monochrome -> applyMonochromeThreshold(threshold = 188)
        DisplayMode.EinkHighContrast -> applyMonochromeThreshold(threshold = 210)
    }
}

private fun Bitmap.applyGrayscale() {
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)

    for (index in pixels.indices) {
        val luminance = pixels[index].luminance()
        pixels[index] = Color.rgb(luminance, luminance, luminance)
    }

    setPixels(pixels, 0, width, 0, 0, width, height)
}

private fun Bitmap.applyMonochromeThreshold(threshold: Int) {
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)

    for (index in pixels.indices) {
        pixels[index] = if (pixels[index].luminance() < threshold) Color.BLACK else Color.WHITE
    }

    setPixels(pixels, 0, width, 0, 0, width, height)
}

private fun Int.luminance(): Int {
    val red = Color.red(this)
    val green = Color.green(this)
    val blue = Color.blue(this)
    return ((red * 299) + (green * 587) + (blue * 114)) / 1000
}
