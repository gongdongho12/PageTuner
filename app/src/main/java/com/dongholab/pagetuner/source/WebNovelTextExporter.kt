package com.dongholab.pagetuner.source

import android.content.Context
import android.os.Environment
import java.io.File

object WebNovelTextExporter {

    fun exportChapterTextFile(
        context: Context,
        title: String,
        content: String,
    ): File {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir

        val pageTurnerFolder = File(downloadsDir, "PageTurner").apply { mkdirs() }
        val safeTitle = title.replace(Regex("[^a-zA-Z0-9가-힣_-]"), "_").take(40)
        val file = File(pageTurnerFolder, "$safeTitle.txt")

        file.writeText(content, Charsets.UTF_8)
        return file
    }
}
