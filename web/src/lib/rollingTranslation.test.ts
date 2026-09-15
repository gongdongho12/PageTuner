import { describe, expect, it, vi } from 'vitest'
import { RollingTranslationSession, readingPacingDelay, rollingPageOrder, rollingWindow, type RollingSnapshot } from './rollingTranslation'
import { readingFragmentKey, readingFragmentText, readingSourceHash, verifyReadingTranslation, validateReadingTranslation,
  type ReadingPagination, type ReadingTranslationClient, type ReadingTranslationRequest, type ReadingTranslationResponse, type ReadingTranslationSettings } from './readingTranslation'
import type { StoredChapter } from './workflowTypes'
import { createWorkflowClient } from './workflowApi'
import { ApiError } from './errors'

const chapter: StoredChapter = { recordId: '11111111-1111-4111-8111-111111111111', providerId: 'fixture', bookId: 'book', bookTitle: 'Book', bookUrl: '',
  chapterId: 'chapter', chapterTitle: 'Chapter', chapterUrl: '', sourceLanguage: 'en', sourceRevision: 'a'.repeat(64), createdAt: '2026-09-15T00:00:00Z',
  paragraphs: Array.from({ length: 25 }, (_, ordinal) => ({ paragraphId: `p-${ordinal}`, ordinal, text: `Page ${ordinal} 🌏 text.` })) }
const settings: ReadingTranslationSettings = { providerKind: 'GOOGLE_WEB_TRANSLATE_HTML', targetLanguage: 'ko', readingWordsPerMinute: 210, paceMode: 'READING' }
const pagination = (page = 0): ReadingPagination => ({ documentId: 'source', page, pages: chapter.paragraphs.map(p => [{ paragraphId: p.paragraphId, start: 0, end: p.text.length }]) })
let sequence = 0
const uuid = () => `22222222-2222-4222-8222-${String(++sequence).padStart(12, '0')}`
const request = (): ReadingTranslationRequest => ({ ...settings, requestId: uuid(), chapterRecordId: chapter.recordId, sourceRevision: chapter.sourceRevision, fragments: [...pagination().pages[0]] })
async function response(input: ReadingTranslationRequest, status: ReadingTranslationResponse['status'] = 'COMPLETED'): Promise<ReadingTranslationResponse> {
  return { requestId: input.requestId, chapterRecordId: input.chapterRecordId, sourceRevision: input.sourceRevision, sourceHash: await readingSourceHash(input),
    providerKind: input.providerKind, targetLanguage: input.targetLanguage, scope: 'READING_PREVIEW', status, totalFragments: input.fragments.length,
    completedFragments: status === 'COMPLETED' ? input.fragments.length : 0, errorCode: null, updatedAt: '2026-09-15T00:00:00Z',
    items: status === 'COMPLETED' ? input.fragments.map(fragment => ({ ...fragment, text: `번역 ${readingFragmentText(chapter, fragment)}` })) : [] }
}
function immediateClient() {
  const inputs: ReadingTranslationRequest[] = [], cancelled: string[] = []
  const client: ReadingTranslationClient = {
    async startReadingTranslation(input) { inputs.push(input); return response(input) },
    async getReadingTranslation(id) { return response(inputs.find(input => input.requestId === id)!) },
    async cancelReadingTranslation(id) { cancelled.push(id); return response(inputs.find(input => input.requestId === id)!, 'CANCELLED') },
  }
  return { client, inputs, cancelled }
}
describe('reading translation windows and transport', () => {
  it('uses aligned ten-page windows and starts the next one at the fifth page', () => {
    expect(rollingWindow(14, 25)).toEqual({ start: 10, end: 20, trigger: 14 })
    expect(rollingPageOrder(3, 25)).toEqual([3, 0, 1, 2, 4, 5, 6, 7, 8, 9])
    expect(rollingPageOrder(14, 25)).toEqual([14, 10, 11, 12, 13, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24])
    expect(() => rollingWindow(-1, 25)).toThrow()
  })
  it('matches shared runtime WPM rounding and pace bounds', () => {
    expect(readingPacingDelay(21, 210, 'READING')).toBe(6000)
    expect(readingPacingDelay(21, 210, 'FAST')).toBe(1680)
    expect(readingPacingDelay(21, 210, 'OFFLINE_PREFETCH')).toBe(480)
    expect(readingPacingDelay(1, 420, 'READING')).toBe(750)
    expect(readingPacingDelay(1000, 120, 'READING')).toBe(14000)
  })
  it('verifies source hashes, exact ranges and preview scope before accepting text', async () => {
    const input = request(), good = await response(input)
    await expect(verifyReadingTranslation(good, input)).resolves.toBeUndefined()
    await expect(verifyReadingTranslation({ ...good, sourceRevision: 'b'.repeat(64) }, input)).rejects.toThrow()
    await expect(verifyReadingTranslation({ ...good, items: [{ ...good.items[0], end: good.items[0].end - 1 }] }, input)).rejects.toThrow()
    expect(() => validateReadingTranslation({ ...good, scope: 'TRANSLATION_ARTIFACT' })).toThrow()
    expect(() => validateReadingTranslation({ ...good, status: 'RUNNING' })).toThrow()
    expect(() => readingFragmentText(chapter, { paragraphId: 'p-0', start: 0, end: 8 })).toThrow()
  })
  it('prioritizes the current page, reuses prepared ranges and never asks for a complete artifact', async () => {
    const { client, inputs } = immediateClient(), delays: number[] = []; let latest!: RollingSnapshot
    const session = new RollingTranslationSession(chapter, settings, client, state => { latest = state }, { uuid, sleep: async ms => { delays.push(ms) } })
    session.setPagination(pagination(3)); session.start()
    await vi.waitFor(() => expect(latest.running).toBe(false))
    expect(inputs).toHaveLength(10); expect(inputs[0].fragments[0].paragraphId).toBe('p-3')
    expect(latest.readyPages).toBe(10); expect(latest.items).toHaveLength(1)
    session.setPagination(pagination(4))
    await vi.waitFor(() => expect(inputs).toHaveLength(20))
    await vi.waitFor(() => expect(latest.running).toBe(false))
    expect(new Set(inputs.flatMap(input => input.fragments.map(readingFragmentKey))).size).toBe(20)
    expect(delays.every(ms => ms >= 750 && ms <= 14000)).toBe(true)
    await session.stop()
  })
  it('discards late results after stopping and cancels the UUID allocated before POST', async () => {
    let release!: () => void, input!: ReadingTranslationRequest, latest!: RollingSnapshot
    const gate = new Promise<void>(resolve => { release = resolve }), cancelled: string[] = []
    const client: ReadingTranslationClient = { async startReadingTranslation(value) { input = value; await gate; return response(value) },
      getReadingTranslation: async () => response(input), cancelReadingTranslation: async id => { cancelled.push(id); return response(input, 'CANCELLED') } }
    const session = new RollingTranslationSession(chapter, settings, client, value => { latest = value }, { uuid, sleep: async () => {} })
    session.setPagination(pagination()); session.start(); await vi.waitFor(() => expect(input).toBeDefined())
    const stopped = session.stop(); release(); await stopped
    expect(cancelled).toEqual([input.requestId]); expect(latest.items).toEqual([]); expect(latest.enabled).toBe(false)
  })
  it('layout changes discard stale page caches and preserve valid source ranges', async () => {
    const { client, inputs } = immediateClient(); let latest!: RollingSnapshot
    const session = new RollingTranslationSession(chapter, settings, client, state => { latest = state }, { uuid, sleep: async () => {} })
    session.setPagination(pagination()); session.start(); await vi.waitFor(() => expect(latest.readyPages).toBe(10)); await session.stop()
    const layout: ReadingPagination = { documentId: 'source', page: 0, pages: [[{ paragraphId: 'p-0', start: 0, end: 6 }]] }
    session.setPagination(layout); expect(latest.items).toEqual([]); session.start()
    await vi.waitFor(() => expect(latest.items).toHaveLength(1)); await session.stop()
    expect(inputs.at(-1)!.fragments).toEqual(layout.pages[0])
  })
  it('retries failed preview jobs with new IDs and clears translations on language changes', async () => {
    const { client, inputs } = immediateClient(); let latest!: RollingSnapshot, failures = 1
    const start = client.startReadingTranslation
    client.startReadingTranslation = async input => { if (failures > 0) { failures--; inputs.push(input); return { ...await response(input, 'FAILED'), errorCode: 'READING_PROVIDER_FAILED' } } return start(input) }
    const session = new RollingTranslationSession(chapter, settings, client, state => { latest = state }, { uuid, sleep: async () => {} })
    session.setPagination({ ...pagination(), pages: pagination().pages.slice(0, 1) }); session.start()
    await vi.waitFor(() => expect(latest.error).toBeTruthy()); const first = inputs[0].requestId
    session.retry(); await vi.waitFor(() => expect(latest.items).toHaveLength(1)); expect(inputs[1].requestId).not.toBe(first)
    await session.stop(); session.configure({ ...settings, targetLanguage: 'ja' }); expect(latest.items).toEqual([])
    session.start(); await vi.waitFor(() => expect(latest.items).toHaveLength(1)); expect(inputs.at(-1)!.targetLanguage).toBe('ja'); await session.stop()
  })
  it('disconnect dispatches an authenticated CSRF keepalive cancel before aborting a pending start', async () => {
    const input = request(), calls: { path: string; init: RequestInit }[] = []
    const fetcher = vi.fn(async (path: RequestInfo | URL, init?: RequestInit) => {
      calls.push({ path: String(path), init: init! })
      if (String(path) === '/api/v1/csrf') return new Response(JSON.stringify({ headerName: 'X-CSRF-TOKEN', token: 'test-csrf' }), { headers: { 'Content-Type': 'application/json' } })
      if (String(path).endsWith('/cancel')) return new Response('', { status: 404 })
      return await new Promise<Response>((_, reject) => init?.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true }))
    })
    const client = createWorkflowClient({ username: 'reader', password: 'test-password' }, { fetch: fetcher as typeof fetch })
    const started = client.startReadingTranslation(input).catch(error => error)
    await vi.waitFor(() => expect(calls.some(call => call.path === '/api/v1/reading-translations')).toBe(true))
    client.close(); await started
    const cancel = calls.find(call => call.path.endsWith(`/${input.requestId}/cancel`))!
    expect(cancel.init.keepalive).toBe(true); expect(cancel.init.signal).toBeUndefined()
    const headers = cancel.init.headers as Record<string, string>
    expect(headers.Authorization).toMatch(/^Basic /); expect(headers['X-CSRF-TOKEN']).toBe('test-csrf')
    expect(cancel.init.body).toBe('{}'); expect(cancel.path).not.toContain('test-password')
  })
  it('defers prefetch at the memory budget instead of retranslating evicted pages forever', async () => {
    const { client, inputs } = immediateClient(); let latest!: RollingSnapshot
    const session = new RollingTranslationSession(chapter, settings, client, state => { latest = state }, { uuid, sleep: async () => {}, cacheCharacters: 80, cacheFragments: 2 })
    session.setPagination(pagination()); session.start()
    await vi.waitFor(() => expect(latest.running).toBe(false))
    expect(inputs).toHaveLength(1); expect(latest.items).toHaveLength(1); expect(latest.limited).toBe(true)
    session.setPagination(pagination(1)); await vi.waitFor(() => expect(latest.running).toBe(false))
    expect(inputs).toHaveLength(2); expect(latest.items[0].paragraphId).toBe('p-1')
    session.setPagination(pagination(2)); await vi.waitFor(() => expect(latest.running).toBe(false))
    expect(inputs).toHaveLength(3); expect(latest.cacheCount).toBeLessThanOrEqual(2); expect(latest.items[0].paragraphId).toBe('p-2'); await session.stop()
  })
  it('rejects a single translated page that cannot fit the session budget', async () => {
    const { client, inputs } = immediateClient(); let latest!: RollingSnapshot
    const session = new RollingTranslationSession(chapter, settings, client, state => { latest = state }, { uuid, sleep: async () => {}, cacheCharacters: 2 })
    session.setPagination(pagination()); session.start(); await vi.waitFor(() => expect(latest.error).toBeTruthy())
    expect(inputs).toHaveLength(1); expect(latest.items).toEqual([]); await session.stop()
  })
  it('refreshes edited book glossaries before using an already prepared page', async () => {
    const { client, inputs } = immediateClient(); let latest!: RollingSnapshot
    let glossary = [{ source: 'Page', target: '쪽' }]
    const session = new RollingTranslationSession(chapter, settings, client, state => { latest = state }, { uuid, sleep: async () => {}, readGlossary: async () => glossary })
    session.setPagination(pagination()); session.start(); await vi.waitFor(() => expect(latest.running).toBe(false))
    expect(inputs).toHaveLength(10); expect(inputs[0].glossary).toEqual(glossary)
    glossary = [{ source: 'Page', target: '페이지' }]
    session.setPagination(pagination(1)); await vi.waitFor(() => expect(latest.running).toBe(false))
    expect(inputs).toHaveLength(20); expect(inputs[10].fragments[0].paragraphId).toBe('p-1'); expect(inputs[10].glossary).toEqual(glossary)
    const count = inputs.length
    session.configure({ paceMode: 'READING', readingWordsPerMinute: 210, glossary, targetLanguage: 'ko', providerKind: 'GOOGLE_WEB_TRANSLATE_HTML' })
    expect(latest.items).toHaveLength(1); expect(inputs).toHaveLength(count); await session.stop()
  })
  it('waits a bounded time for cancelled provider cleanup and reuses the unaccepted request ID', async () => {
    const { client, inputs } = immediateClient(); let latest!: RollingSnapshot, busy = 2
    const ids: string[] = [], delays: number[] = [], start = client.startReadingTranslation
    client.startReadingTranslation = async input => {
      ids.push(input.requestId)
      if (busy-- > 0) throw new ApiError('invalid-request', 'Busy', 429)
      return start(input)
    }
    const session = new RollingTranslationSession(chapter, settings, client, value => { latest = value }, { uuid, sleep: async ms => { delays.push(ms) } })
    session.setPagination({ ...pagination(), pages: pagination().pages.slice(0, 1) }); session.start()
    await vi.waitFor(() => expect(latest.running).toBe(false))
    expect(latest.items).toHaveLength(1); expect(inputs).toHaveLength(1)
    expect(new Set(ids).size).toBe(1); expect(delays).toEqual([500, 1000]); await session.stop()
    busy = 100; ids.length = 0; delays.length = 0
    session.configure({ ...settings, targetLanguage: 'ja' }); session.start()
    await vi.waitFor(() => expect(latest.running).toBe(false))
    expect(ids).toHaveLength(6); expect(delays).toEqual([500, 1000, 2000, 4000, 4000])
    expect(latest.error).toBeTruthy(); expect(latest.items).toEqual([]); await session.stop()
  })
})
