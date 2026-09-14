package com.dongholab.pagetuner.source

/** Host applications may attach diagnostics without coupling parsers to Android logging. */
object SourceDiagnostics {
    @Volatile
    var sink: (String, String) -> Unit = { _, _ -> }

    fun log(tag: String, message: String) = sink(tag, message)
}
