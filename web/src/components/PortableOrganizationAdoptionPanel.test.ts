import { afterEach, expect, it } from 'vitest'
import original from '../../../contracts/fixtures/library-identity-v1/original.json'
import { validateLibraryIdentity } from '../lib/libraryIdentity'
import { setLocale, translate } from '../lib/locale'
import type { PortableOrganizationAdoptionReview } from '../lib/portableOrganizationAdoption'
import { organizationAdoptionFields } from './PortableOrganizationAdoptionPanel'

const scope = { kind: 'ORIGINAL' as const, recordId: '11111111-1111-4111-8111-111111111111' }
const destination = { username: 'reader', origin: 'https://account.example:8443' }
const identity = validateLibraryIdentity(original.extensions.documentIdentity)
const empty = () => ({ folder: '', tags: [] as string[], favorite: false })
const base = (): PortableOrganizationAdoptionReview => ({ scope,
  snapshot: { version: 1, identity, presence: 'absent', organization: null },
  server: { ...scope, version: 0, organization: null, updatedAt: null }, device: null,
  confirm: async () => {}, close: () => {},
})
afterEach(() => setLocale('ko'))

it('preserves full folder and ordered tags, Unicode forms and internal spaces on bounded field pages', () => {
  const folder = '🌏'.repeat(98) + '  A', tags = ['Case', 'case', 'é', 'e\u0301', '두  칸', ...Array.from({ length: 27 }, (_, i) => `태그${i}🌏`)]
  const review = base(); review.snapshot = { ...review.snapshot, presence: 'present', organization: { folder, tags, favorite: true } }
  const fields = organizationAdoptionFields(review, 'snapshot', destination)
  const values = (label: string) => fields.filter(field => field.label === label || field.label.startsWith(`${label} · `)).map(field => field.value).join('')
  expect(values('변경할 계정')).toBe(destination.username)
  expect(values('계정 서버')).toBe(destination.origin)
  expect(values('계정 문서 식별자')).toBe(scope.recordId)
  expect(values('폴더')).toBe(folder)
  expect(tags.map((_, i) => values(`${i + 1} · 태그`))).toEqual(tags)
  expect(values('태그 수')).toBe('32')
  expect(values('즐겨찾기')).toBe('켜짐')
  expect(fields.every(field => Array.from(field.value).length <= 20)).toBe(true)
})

it('distinguishes absent snapshot, absent server, missing journal and explicitly empty organization', () => {
  const review = base()
  const fields = (side: 'snapshot' | 'server' | 'device') => organizationAdoptionFields(review, side, destination)
  const state = (side: 'snapshot' | 'server' | 'device') => fields(side).find(field => field.label === '스냅샷 상태')?.value
  expect(state('snapshot')).toBe('미등록')
  expect(state('server')).toBe('미등록')
  expect(state('device')).toBe('기기 계정 기록 없음')
  expect(fields('snapshot').some(field => field.label === '폴더')).toBe(false)
  review.snapshot = { ...review.snapshot, presence: 'present', organization: empty() }
  expect(state('snapshot')).toBe('명시적 빈 분류')
  expect(fields('snapshot').find(field => field.label === '폴더')?.value).toBe('')
  expect(fields('server').find(field => field.label === '서버 버전')?.value).toBe('0')
  review.device = { enabled: false, local: empty(), remote: null, pending: null, queued: null, conflict: null, retryAfterUntil: null }
  expect(state('device')).toBe('명시적 빈 분류')
  expect(fields('device').find(field => field.label === '계정 분류 사용')?.value).toBe('사용 안 함')
})

it('shows device local values independently from snapshot and server without mutation', () => {
  const review = base()
  review.snapshot = { ...review.snapshot, presence: 'present', organization: { folder: 'ZIP', tags: [], favorite: false } }
  review.server = { ...review.server, version: 3, updatedAt: '2026-10-24T00:00:00Z', organization: { folder: '서버', tags: ['a'], favorite: false } }
  review.device = { enabled: true, local: { folder: '기기', tags: ['b'], favorite: true }, remote: review.server, pending: null, queued: null, conflict: null, retryAfterUntil: null }
  const before = structuredClone({ ...review, confirm: undefined, close: undefined })
  const folder = (side: 'snapshot' | 'server' | 'device') => organizationAdoptionFields(review, side, destination).find(field => field.label === '폴더')?.value
  expect([folder('snapshot'), folder('server'), folder('device')]).toEqual(['ZIP', '서버', '기기'])
  expect({ ...review, confirm: undefined, close: undefined }).toEqual(before)
})

it('labels empty intent and informational absence in English without confusing them with deletion', () => {
  setLocale('en')
  const review = base(); review.snapshot = { ...review.snapshot, presence: 'present', organization: empty() }
  expect(organizationAdoptionFields(review, 'snapshot', destination).find(field => field.label === 'Snapshot state')?.value).toBe('Explicitly empty')
  expect(translate('미등록 분류 스냅샷은 정보만 표시하며 계정 분류를 변경하지 않습니다.')).toContain('informational')
  expect(translate('채택 의도를 이 기기에 안전하게 보관했습니다. 서버 반영 완료를 뜻하지 않습니다.')).toContain('not a server acknowledgement')
})
