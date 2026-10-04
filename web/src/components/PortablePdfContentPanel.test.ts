import { describe, expect, it } from 'vitest'
import vector from '../../../contracts/fixtures/pdf-content-v1.json'
import { pdfCopyReviewFields } from './PortablePdfContentPanel'
import type { PdfCopyReview } from '../lib/portablePdfBinding'
import { setLocale, translate } from '../lib/locale'

describe('bounded PDF copy review fields', () => {
  it('keeps full IDs, whitespace, empty/null distinctions and Unicode on discrete pages', () => {
    setLocale('ko')
    const value = '  ' + '책😀 '.repeat(30) + '  '
    const review = { title: '  Title  ', content: { ...structuredClone(vector.upload.content), paragraphs: [{ paragraphId: value, text: '' }, { paragraphId: 'p2', text: value }] },
      proof: structuredClone(vector.expected.proof), binding: null } as unknown as PdfCopyReview
    const fields = pdfCopyReviewFields(review, '  Account  ', 'https://example.test', '11111111-1111-4111-8111-111111111111')
    expect(fields.every(f => Array.from(f.value).length <= 32)).toBe(true)
    expect(fields.filter(f => f.label.startsWith('1 · 문단 식별자')).map(f => f.value).join('')).toBe(value)
    expect(fields.filter(f => f.label.startsWith('2 · 문단 본문')).map(f => f.value).join('')).toBe(value)
    expect(fields.find(f => f.label === '보관할 계정')?.value).toBe('  Account  ')
    expect(fields.filter(f => f.label.startsWith('전체 내용 SHA-256')).map(f => f.value).join('')).toBe(vector.expected.proof.sha256)
    expect(fields.find(f => f.label === '1 · 문단 본문')?.value).toBe('')
    expect(fields.find(f => f.label === '1 · 자산 설명')?.value).toBe('없음 (null)')
    expect(fields.find(f => f.label === '3 · 자산 설명')?.value).toBe('')
  })
  it('provides English action, failure and proof labels', () => {
    setLocale('en')
    try {
      for (const key of ['PDF 서버 보관·연결', '선택', '보관할 계정', '전체 내용 SHA-256', '같은 요청으로 다시 확인', '이 기기 연결 확정', 'PDF 사본이나 연결 상태가 변경되었습니다. 다시 확인해 주세요.']) expect(translate(key)).not.toBe(key)
    } finally { setLocale('ko') }
  })
  it('shows every newline, CRLF, tab and emoji without hidden textarea lines', () => {
    const value = '  a\nb\nc\r\nd\t\t\t\u0085\u2028\u0000' + '😀'.repeat(40)
    const review = { title: 'PDF', content: { ...structuredClone(vector.upload.content), paragraphs: [{ paragraphId: 'p', text: value }] }, proof: structuredClone(vector.expected.proof), binding: null } as unknown as PdfCopyReview
    const fields = pdfCopyReviewFields(review, 'reader', 'https://example.test').filter(f => f.label.startsWith('1 · 문단 본문'))
    expect(fields.map(f => f.value).join('')).toBe(value)
    expect(fields.map(f => JSON.parse(f.display!)).join('')).toBe(value)
    for (const field of fields) {
      expect(field.display).not.toMatch(/[\n\r\t\u0085\u2028\u0000]/)
      expect(Array.from(field.display!).reduce((n, c) => n + (c.codePointAt(0)! > 0x7e ? 2 : 1), 0)).toBeLessThanOrEqual(26)
    }
  })
})
