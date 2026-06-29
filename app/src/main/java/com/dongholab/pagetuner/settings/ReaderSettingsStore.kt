package com.dongholab.pagetuner.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dongholab.pagetuner.display.DisplayMode
import com.dongholab.pagetuner.reader.PageTurnMode
import com.dongholab.pagetuner.reader.PdfFitMode
import com.dongholab.pagetuner.translation.TranslationPaceMode
import com.dongholab.pagetuner.translation.TranslationProviderKind
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.readerSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "reader_settings",
)

class ReaderSettingsStore(context: Context) {
    private val dataStore = context.applicationContext.readerSettingsDataStore

    val settings: Flow<ReaderSettings> = dataStore.data
        .catch { error ->
            if (error is IOException) {
                emit(androidx.datastore.preferences.core.emptyPreferences())
            } else {
                throw error
            }
        }
        .map { preferences -> preferences.toReaderSettings() }

    suspend fun updateDisplayMode(displayMode: DisplayMode) {
        dataStore.edit { preferences ->
            preferences[Keys.DISPLAY_MODE] = displayMode.name
        }
    }

    suspend fun updatePageTurnMode(pageTurnMode: PageTurnMode) {
        dataStore.edit { preferences ->
            preferences[Keys.PAGE_TURN_MODE] = pageTurnMode.name
        }
    }

    suspend fun updatePdfFitMode(pdfFitMode: PdfFitMode) {
        dataStore.edit { preferences ->
            preferences[Keys.PDF_FIT_MODE] = pdfFitMode.name
        }
    }

    suspend fun updateReaderFontSize(fontSizeSp: Int) {
        dataStore.edit { preferences ->
            preferences[Keys.READER_FONT_SIZE_SP] = fontSizeSp.coerceIn(14, 28)
        }
    }

    suspend fun updateReaderLineSpacing(lineSpacing: Float) {
        dataStore.edit { preferences ->
            preferences[Keys.READER_LINE_SPACING] = lineSpacing.coerceIn(1.1f, 1.8f).toStoredPercent()
        }
    }

    suspend fun updateReaderPageMargin(pageMarginDp: Int) {
        dataStore.edit { preferences ->
            preferences[Keys.READER_PAGE_MARGIN_DP] = pageMarginDp.coerceIn(8, 36)
        }
    }

    suspend fun updateSourceLanguage(sourceLanguage: String) {
        dataStore.edit { preferences ->
            preferences[Keys.SOURCE_LANGUAGE] = sourceLanguage.trim().ifBlank { "auto" }
        }
    }

    suspend fun updateTargetLanguage(targetLanguage: String) {
        dataStore.edit { preferences ->
            preferences[Keys.TARGET_LANGUAGE] = targetLanguage.trim().ifBlank { "ko" }
        }
    }

    suspend fun updateLanguages(sourceLanguage: String, targetLanguage: String) {
        dataStore.edit { preferences ->
            preferences[Keys.SOURCE_LANGUAGE] = sourceLanguage.trim().ifBlank { "auto" }
            preferences[Keys.TARGET_LANGUAGE] = targetLanguage.trim().ifBlank { "ko" }
        }
    }

    suspend fun updateProviderKind(providerKind: TranslationProviderKind) {
        dataStore.edit { preferences ->
            preferences[Keys.PROVIDER_KIND] = providerKind.name
        }
    }

    suspend fun updateLlmEndpoint(llmEndpoint: String) {
        dataStore.edit { preferences ->
            preferences[Keys.LLM_ENDPOINT] = llmEndpoint.trim()
        }
    }

    suspend fun updateLlmModel(llmModel: String) {
        dataStore.edit { preferences ->
            preferences[Keys.LLM_MODEL] = llmModel.trim()
        }
    }

    suspend fun updateReadingWordsPerMinute(readingWordsPerMinute: Int) {
        dataStore.edit { preferences ->
            preferences[Keys.READING_WORDS_PER_MINUTE] = readingWordsPerMinute.coerceIn(120, 420)
        }
    }

    suspend fun updatePaceMode(paceMode: TranslationPaceMode) {
        dataStore.edit { preferences ->
            preferences[Keys.PACE_MODE] = paceMode.name
        }
    }

    private fun Preferences.toReaderSettings(): ReaderSettings {
        val defaults = ReaderSettings()
        return ReaderSettings(
            displayMode = enumOrDefault(Keys.DISPLAY_MODE, defaults.displayMode),
            pageTurnMode = enumOrDefault(Keys.PAGE_TURN_MODE, defaults.pageTurnMode),
            pdfFitMode = enumOrDefault(Keys.PDF_FIT_MODE, defaults.pdfFitMode),
            readerFontSizeSp = (
                this[Keys.READER_FONT_SIZE_SP]
                    ?: defaults.readerFontSizeSp
                ).coerceIn(14, 28),
            readerLineSpacing = (
                this[Keys.READER_LINE_SPACING]
                    ?: defaults.readerLineSpacing.toStoredPercent()
                ).toLineSpacing(),
            readerPageMarginDp = (
                this[Keys.READER_PAGE_MARGIN_DP]
                    ?: defaults.readerPageMarginDp
                ).coerceIn(8, 36),
            sourceLanguage = this[Keys.SOURCE_LANGUAGE] ?: defaults.sourceLanguage,
            targetLanguage = this[Keys.TARGET_LANGUAGE] ?: defaults.targetLanguage,
            providerKind = enumOrDefault(Keys.PROVIDER_KIND, defaults.providerKind),
            llmEndpoint = this[Keys.LLM_ENDPOINT] ?: defaults.llmEndpoint,
            llmModel = this[Keys.LLM_MODEL] ?: defaults.llmModel,
            readingWordsPerMinute = (
                this[Keys.READING_WORDS_PER_MINUTE]
                    ?: defaults.readingWordsPerMinute
                ).coerceIn(120, 420),
            paceMode = enumOrDefault(Keys.PACE_MODE, defaults.paceMode),
        )
    }

    private inline fun <reified T : Enum<T>> Preferences.enumOrDefault(
        key: Preferences.Key<String>,
        default: T,
    ): T {
        return this[key]?.let { storedName ->
            runCatching { enumValueOf<T>(storedName) }.getOrNull()
        } ?: default
    }

    private fun Float.toStoredPercent(): Int = (this * 100f).toInt()

    private fun Int.toLineSpacing(): Float = (this.toFloat() / 100f).coerceIn(1.1f, 1.8f)

    private object Keys {
        val DISPLAY_MODE = stringPreferencesKey("display_mode")
        val PAGE_TURN_MODE = stringPreferencesKey("page_turn_mode")
        val PDF_FIT_MODE = stringPreferencesKey("pdf_fit_mode")
        val READER_FONT_SIZE_SP = intPreferencesKey("reader_font_size_sp")
        val READER_LINE_SPACING = intPreferencesKey("reader_line_spacing")
        val READER_PAGE_MARGIN_DP = intPreferencesKey("reader_page_margin_dp")
        val SOURCE_LANGUAGE = stringPreferencesKey("source_language")
        val TARGET_LANGUAGE = stringPreferencesKey("target_language")
        val PROVIDER_KIND = stringPreferencesKey("provider_kind")
        val LLM_ENDPOINT = stringPreferencesKey("llm_endpoint")
        val LLM_MODEL = stringPreferencesKey("llm_model")
        val READING_WORDS_PER_MINUTE = intPreferencesKey("reading_words_per_minute")
        val PACE_MODE = stringPreferencesKey("pace_mode")
    }
}
