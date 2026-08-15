package com.dongholab.pagetuner.translation

import com.dongholab.pagetuner.BuildConfig

/** Debug-only local credentials injected from the root .env file at build time. */
object TranslationRuntimeSecrets {
    val deepSeekApiKey: String
        get() = BuildConfig.DEEPSEEK_API_KEY

    val deepSeekApiUrl: String
        get() = BuildConfig.DEEPSEEK_API_URL.ifBlank { DeepSeekDefaults.ApiUrl }

    val deepSeekModel: String
        get() = BuildConfig.DEEPSEEK_MODEL.ifBlank { DeepSeekDefaults.Model }

    val hasLocalDeepSeekKey: Boolean
        get() = deepSeekApiKey.isNotBlank()
}
