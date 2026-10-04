import type { components } from '../generated/api'

export type TranslationResponse = components['schemas']['TranslationResponse']
export type TranslationSummary = components['schemas']['TranslationSummary']
export type TranslationPage = components['schemas']['TranslationListResponse']

export interface ReadingAnchor {
  paragraphId: string
  /** UTF-16 character offset within the paragraph, independent of rendered pages. */
  characterOffset: number
}

export interface StoredBook {
  translation: TranslationResponse
  savedAt: string
  anchor?: ReadingAnchor
}
