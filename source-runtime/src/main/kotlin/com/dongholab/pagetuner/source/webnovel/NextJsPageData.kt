package com.dongholab.pagetuner.source.webnovel

import org.json.JSONObject

/** Shared extraction for providers whose server-rendered data lives in Next.js page props. */
object NextJsPageData {
    private val NextDataScript =
        Regex("(?is)<script[^>]*id=[\"']__NEXT_DATA__[\"'][^>]*>(.*?)</script>")

    fun pageProps(html: String): JSONObject? {
        val rawJson = NextDataScript.find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return null
        return runCatching {
            JSONObject(rawJson).optJSONObject("props")?.optJSONObject("pageProps")
        }.getOrNull()
    }
}
