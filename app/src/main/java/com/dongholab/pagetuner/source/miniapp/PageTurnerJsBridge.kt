package com.dongholab.pagetuner.source.miniapp

import android.webkit.JavascriptInterface

class PageTurnerJsBridge(
    private val onImportBookRequest: (title: String, author: String, contentText: String) -> Unit = { _, _, _ -> },
    private val onReadRequest: (title: String, contentText: String) -> Unit = { _, _ -> },
) {

    @JavascriptInterface
    fun importBook(title: String, author: String, contentText: String): Boolean {
        if (title.isBlank() || contentText.isBlank()) return false
        onImportBookRequest(title.trim(), author.trim(), contentText)
        return true
    }

    @JavascriptInterface
    fun readInEink(title: String, contentText: String): Boolean {
        if (contentText.isBlank()) return false
        onReadRequest(title.trim(), contentText)
        return true
    }

    @JavascriptInterface
    fun getAppVersion(): String {
        return "1.0.0-eink-miniprogram"
    }

    @JavascriptInterface
    fun log(message: String) {
        android.util.Log.d("PageTurnerJsBridge", message)
    }
}
