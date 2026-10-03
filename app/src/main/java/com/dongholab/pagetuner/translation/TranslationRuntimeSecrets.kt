package com.dongholab.pagetuner.translation

import com.dongholab.pagetuner.BuildConfig

/** Debug-only local credentials injected from the root .env file at build time. */
object TranslationRuntimeSecrets {
    val geminiApiKey: String get() = BuildConfig.GEMINI_API_KEY
    val geminiApiUrl: String get() = BuildConfig.GEMINI_API_URL.ifBlank { GeminiDefaults.ApiUrl }
    val geminiModel: String get() = BuildConfig.GEMINI_MODEL.ifBlank { GeminiDefaults.Model }
    val hasLocalGeminiKey: Boolean get() = geminiApiKey.isNotBlank()

    val deepSeekApiKey: String
        get() = BuildConfig.DEEPSEEK_API_KEY

    val deepSeekApiUrl: String
        get() = BuildConfig.DEEPSEEK_API_URL.ifBlank { DeepSeekDefaults.ApiUrl }

    val deepSeekModel: String
        get() = BuildConfig.DEEPSEEK_MODEL.ifBlank { DeepSeekDefaults.Model }

    val hasLocalDeepSeekKey: Boolean
        get() = deepSeekApiKey.isNotBlank()
}
