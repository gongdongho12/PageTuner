import { renderToStaticMarkup } from 'react-dom/server'
import { afterEach, describe, expect, it } from 'vitest'
import stored from '../../../contracts/fixtures/translation-v1/stored-response.json'
import { PagedReader } from './PagedReader'
import { TranslationComparisonReader } from './TranslationComparisonReader'
import { translationReadingDocument } from '../lib/translationReading'
import { setLocale } from '../lib/locale'
import { translate } from '../lib/locale'

afterEach(() => setLocale('ko'))
const props = { document: translationReadingDocument(stored), onAnchorChange: () => {}, onClose: () => {} }

describe('comparison reader controls', () => {
  it('exposes three localized modes from a normal saved translation without fetching during rendering', () => {
    setLocale('en')
    const html = renderToStaticMarkup(<TranslationComparisonReader {...props} translation={stored} notesNamespace="alice" workflowClient={null}/>)
    expect(html).toContain('aria-label="Reading display mode"')
    expect(html).toContain('aria-pressed="true"'); expect(html).toContain('Compare')
    expect(html).toContain('Original'); expect(html).toContain('Translation')
  })
  it('keeps derived comparison text read-only while preserving the ordinary reader annotation controls', () => {
    setLocale('en')
    const ordinary = renderToStaticMarkup(<PagedReader {...props} notesNamespace="alice"/>)
    const derived = renderToStaticMarkup(<PagedReader {...props} notesNamespace="alice" readOnly editionLabel="Comparison" readerLabel="Comparison reading" contentKindLabel="Compare"/>)
    expect(ordinary).toContain('Reading tools'); expect(ordinary).toContain('Highlight selection')
    expect(derived).not.toContain('Reading tools'); expect(derived).not.toContain('Highlight selection')
    expect(derived).toContain('aria-label="Comparison reading"')
  })
  it('leaves previews and ordinary original readers outside the comparison workflow', () => {
    const preview = renderToStaticMarkup(<TranslationComparisonReader {...props} translation={stored} preview/>)
    const original = renderToStaticMarkup(<TranslationComparisonReader {...props} document={{ ...props.document, kind: 'original' }} notesNamespace="alice"/>)
    expect(preview).not.toContain('comparison-modes'); expect(original).not.toContain('comparison-modes')
  })
  it('localizes the catalog status and comparison entry labels for English accounts', () => {
    setLocale('en')
    expect(translate('서버에서 받은 목록')).toBe('Catalog from the server')
    expect(translate('목록 새로 받기')).toBe('Refresh catalog')
    expect(translate('원문 보기')).toBe('View original')
    expect(translate('번역 설정')).toBe('Translation settings')
    expect(translate('저장 목록 · {0}페이지 · {1}', [2, 'date'])).toBe('Saved catalog · Page 2 · date')
  })
})
