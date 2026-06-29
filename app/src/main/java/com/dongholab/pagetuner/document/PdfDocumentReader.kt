package com.dongholab.pagetuner.document

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.dongholab.pagetuner.display.DisplayMode
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

object PdfDocumentReader {
    private const val MaxRenderWidthPx = 1080
    private const val MaxRenderHeightPx = 1440

    fun read(
        context: Context,
        uri: Uri,
        title: String,
        fallbackTitle: String,
    ): ReaderDocument {
        val pageCount = openRenderer(context, uri) { renderer -> renderer.pageCount }
        val documentId = DocumentIds.stableId(title, "pdf:$uri:$pageCount")
        val pages = List(pageCount.coerceAtLeast(1)) { pageIndex ->
            ReaderPage(index = pageIndex, segments = emptyList())
        }

        return ReaderDocument(
            id = documentId,
            title = title.ifBlank { fallbackTitle },
            format = DocumentFormat.PDF,
            pages = pages,
        )
    }

    fun renderPage(
        context: Context,
        uri: Uri,
        pageIndex: Int,
        displayMode: DisplayMode,
    ): Bitmap {
        return openRenderer(context, uri) { renderer ->
            val safePageIndex = pageIndex.coerceIn(0, renderer.pageCount - 1)
            renderer.openPage(safePageIndex).use { page ->
                val scale = minOf(
                    MaxRenderWidthPx.toFloat() / page.width.toFloat(),
                    MaxRenderHeightPx.toFloat() / page.height.toFloat(),
                ).coerceAtLeast(1f)
                val width = max(1, (page.width * scale).roundToInt())
                val height = max(1, (page.height * scale).roundToInt())
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap.applyDisplayMode(displayMode)
                bitmap
            }
        }
    }

    private fun <T> openRenderer(
        context: Context,
        uri: Uri,
        block: (PdfRenderer) -> T,
    ): T {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IOException("Unable to open PDF file.")

        descriptor.use { parcelFileDescriptor ->
            PdfRenderer(parcelFileDescriptor).use { renderer ->
                return block(renderer)
            }
        }
    }

    private fun Bitmap.applyDisplayMode(displayMode: DisplayMode) {
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
}
