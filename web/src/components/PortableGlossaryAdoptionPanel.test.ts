import { afterEach, expect, it } from 'vitest'
import { setLocale } from '../lib/locale'
import type { PortableGlossaryAdoptionReview } from '../lib/portableGlossaryAdoption'
import { glossaryAdoptionFields } from './PortableGlossaryAdoptionPanel'
const scope = { providerId: 'source', bookId: 'original/book', targetLanguage: 'ko' }
const view: PortableGlossaryAdoptionReview = { scope, snapshot: { ...scope, presence: 'absent', entries: null }, server: { ...scope, version: 0, entries: null, updatedAt: null }, device: null, confirm: async () => {} }
afterEach(() => setLocale('ko'))
it('shows complete destination and exact whitespace/Unicode through bounded field pages without truncation', () => {
  const sourceTerm = ' ' + '👩🌏'.repeat(49) + ' ', id = 'id-' + '🌏'.repeat(98)
  const review: PortableGlossaryAdoptionReview = { ...view, snapshot: { ...scope, presence: 'present', entries: [{ id, sourceTerm, translatedTerm: ' exact ', displayTerm: '   ', kind: 'Term', enabled: false, caseSensitive: true }] } }
  const fields = glossaryAdoptionFields(review, 'snapshot', { username: 'reader', origin: 'https://account.example' })
  expect(fields.find(v => v.label === '변경할 계정')?.value).toBe('reader')
  expect(fields.find(v => v.label === '계정 서버')?.value).toBe('https://account.example')
  expect(fields.filter(v => v.label.includes('원문 용어')).map(v => v.value).join('')).toBe(sourceTerm)
  expect(fields.filter(v => v.label.includes('용어 식별자')).map(v => v.value).join('')).toBe(id)
  expect(fields.find(v => v.label.includes('읽기 표시 이름'))?.value).toBe('   ')
  expect(fields.every(v => Array.from(v.value).length <= 32)).toBe(true)
})
it('distinguishes missing device journal, absent remote, saved deletion and an unconfirmed local null', () => {
  const status = (review: PortableGlossaryAdoptionReview) => glossaryAdoptionFields(review, 'device').find(v => v.label === '스냅샷 상태')?.value
  expect(status(view)).toContain('기기 계정 기록 없음')
  const row = { enabled: false, selected: false, local: null, remote: view.server, pending: null, conflict: null, legacyIds: {}, retryAfterUntil: null }
  expect(status({ ...view, device: row })).toContain('미등록')
  expect(status({ ...view, device: { ...row, remote: { ...view.server, version: 1, updatedAt: '2026-10-04T00:00:00Z' } } })).toContain('삭제됨')
  expect(status({ ...view, device: { ...row, remote: null } })).toContain('기기 용어집 없음')
})
