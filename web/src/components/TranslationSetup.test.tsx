import { renderToStaticMarkup } from 'react-dom/server'
import { afterEach, describe, expect, it } from 'vitest'
import { TranslationSetup } from './TranslationSetup'
import { setLocale } from '../lib/locale'
import type { StoredChapter } from '../lib/workflowTypes'

const chapter: StoredChapter = {
  recordId: '11111111-1111-4111-8111-111111111111', providerId: 'fixture', bookId: 'book', bookTitle: 'Book', bookUrl: '',
  chapterId: 'chapter', chapterTitle: 'Chapter', chapterUrl: '', sourceLanguage: 'en', sourceRevision: 'a'.repeat(64), createdAt: '2026-09-15T00:00:00Z',
  paragraphs: [{ paragraphId: 'p1', ordinal: 0, text: 'First paragraph.' }, { paragraphId: 'p2', ordinal: 1, text: 'Second paragraph.' }],
}
const props = { chapter, providers: [], busy: false, username: 'reader', defaultTargetLanguage: 'ko', onBack: () => {}, onSubmit: async () => {} }
afterEach(() => setLocale('ko'))

describe('translation setup scope', () => {
  it('identifies current-page session results in the reading preview settings', () => {
    setLocale('en')
    const html = renderToStaticMarkup(<TranslationSetup {...props} readingPreview/>)
    expect(html).toContain('Reading translation settings')
    expect(html).toContain('Translate this page')
    expect(html).toContain('not saved as a complete translation')
    expect(html).not.toContain('paragraphs will be translated.')
  })
  it('keeps the complete-chapter scope and action for the ordinary workflow', () => {
    const html = renderToStaticMarkup(<TranslationSetup {...props}/>)
    expect(html).toContain('번역 준비')
    expect(html).toContain('원문2개 문단을 번역합니다.')
    expect(html).toContain('번역 시작')
    expect(html).not.toContain('현재 쪽 번역')
  })
})
