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
    val alpha = Color.alpha(this)
    val red = blendOnWhite(Color.red(this), alpha)
    val green = blendOnWhite(Color.green(this), alpha)
    val blue = blendOnWhite(Color.blue(this), alpha)
    return ((red * 299) + (green * 587) + (blue * 114)) / 1000
}

private fun blendOnWhite(channel: Int, alpha: Int): Int {
    return ((channel * alpha) + (255 * (255 - alpha))) / 255
}

/**
 * Decodes a downsampled, mutable bitmap from raw bytes.
 * Prevents allocating multi-megabyte uncompressed bitmaps for small thumbnails on E-Ink.
 */
fun decodeSampledBitmapFromByteArray(
    bytes: ByteArray,
    reqWidth: Int,
    reqHeight: Int,
    config: Bitmap.Config = Bitmap.Config.ARGB_8888,
): Bitmap? {
    if (bytes.isEmpty()) return null
    return runCatching {
        val options = android.graphics.BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
        options.inJustDecodeBounds = false
        options.inPreferredConfig = config
        options.inMutable = true
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }.getOrNull()
}

fun calculateInSampleSize(
    options: android.graphics.BitmapFactory.Options,
    reqWidth: Int,
    reqHeight: Int,
): Int {
    val height = options.outHeight
    val width = options.outWidth
    var inSampleSize = 1

    if (height > reqHeight || width > reqWidth) {
        val halfHeight = height / 2
        val halfWidth = width / 2
        while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize.coerceAtLeast(1)
}
