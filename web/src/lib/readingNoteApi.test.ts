import { afterEach, describe, expect, it, vi } from 'vitest'
import { createReadingNoteClient, validateReadingNoteInput, validateReadingNotePage, ReadingNoteError, type ReadingNoteClient, type ReadingNoteIdentity, type ReadingNoteInput } from './readingNoteApi'

const identity: ReadingNoteIdentity = { kind: 'ORIGINAL', recordId: '11111111-1111-1111-1111-111111111111' }, noteId = '22222222-2222-2222-2222-222222222222'
const note: ReadingNoteInput = { kind: 'NOTE', title: 'Title', text: 'Memo', anchor: { paragraphId: 'p1', characterOffset: 0 }, range: null, createdAt: '2026-09-16T00:00:00.000Z' }
const mutation = { expectedVersion: 0, mutationId: '33333333-3333-3333-3333-333333333333', deleted: false, note }
const item = { noteId, version: 1, changeRevision: 1, deleted: false, note: { ...note, excerpt: 'Body', createdAt: '2026-09-16T00:00:00Z' }, updatedAt: '2026-09-16T01:00:00Z' }
const page = { ...identity, items: [item], nextAfterRevision: 1, watermark: 1, hasMore: false }
const json = (value: unknown, status = 200, extra = {}) => new Response(JSON.stringify(value), { status, headers: { 'content-type': status === 200 ? 'application/json' : 'application/problem+json', ...extra } })
const clients: ReadingNoteClient[] = []
function client(fetch: typeof globalThis.fetch) { const result = createReadingNoteClient({ username: 'alice', password: 'secret' }, { fetch }); clients.push(result); return result }
afterEach(() => { clients.splice(0).forEach(value => value.close()); vi.restoreAllMocks() })

describe('reading notes transport contract', () => {
  it('writes only projected mutation fields with same-origin authentication and CSRF', async () => {
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })).mockResolvedValueOnce(json(item))
    expect(await client(fetch).put(identity, noteId, { ...mutation, note: { ...note, excerpt: 'do-not-send' }, password: 'do-not-send' } as typeof mutation)).toEqual(item)
    expect(fetch.mock.calls[1][0]).toBe(`/api/v1/reading-notes/ORIGINAL/${identity.recordId}/${noteId}`)
    expect(fetch.mock.calls[1][1]).toMatchObject({ method: 'PUT', credentials: 'same-origin', mode: 'same-origin', redirect: 'error', cache: 'no-store', headers: { Authorization: `Basic ${btoa('alice:secret')}`, 'X-CSRF-TOKEN': 'csrf' } })
    expect(JSON.parse(fetch.mock.calls[1][1]?.body as string)).toEqual(mutation)
  })
  it('pins continuation requests to the watermark and validates ordered repeated-note changes', async () => {
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValue(json(page))
    await client(fetch).list(identity, { afterRevision: 0, limit: 50, untilRevision: 1 })
    expect(fetch.mock.calls[0][0]).toContain('afterRevision=0&limit=50&untilRevision=1')
    const changed = { ...item, version: 2, changeRevision: 2 }
    expect(validateReadingNotePage({ ...page, items: [item, changed], watermark: 2, nextAfterRevision: 2 }, identity, { afterRevision: 0 }).items).toHaveLength(2)
    for (const value of [
      { ...page, watermark: 2 }, { ...page, hasMore: true }, { ...page, items: [{ ...item, changeRevision: 0 }] },
      { ...page, items: [changed, item] }, { ...page, items: [item, { ...changed, version: 1 }], watermark: 2, nextAfterRevision: 2 },
      { ...page, recordId: noteId }, { ...page, nextAfterRevision: Number.MAX_SAFE_INTEGER + 1 },
    ]) expect(() => validateReadingNotePage(value, identity, { afterRevision: 0 })).toThrow(ReadingNoteError)
  })
  it('validates UTC timestamps, text limits, exact highlight shape and Unicode', () => {
    for (const value of [
      { ...note, createdAt: '2026-02-30T00:00:00Z' }, { ...note, createdAt: '2026-09-16T00:00:00+09:00' },
      { ...note, title: ' ' }, { ...note, text: '' }, { ...note, text: 'a'.repeat(4001) }, { ...note, title: '\ud800' },
      { ...note, kind: 'HIGHLIGHT', range: null }, { ...note, range: { start: note.anchor, end: { ...note.anchor, characterOffset: 1 } } },
      { ...note, anchor: { paragraphId: 'p\u0000', characterOffset: 0 } },
    ]) expect(() => validateReadingNoteInput(value)).toThrow(ReadingNoteError)
  })
  it('preserves a valid version-zero conflict and never exposes raw server messages', async () => {
    const current = { noteId, version: 0, changeRevision: 0, deleted: true, note: null, updatedAt: null }
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })).mockResolvedValueOnce(json({ code: 'READING_NOTE_CONFLICT', current, detail: 'secret stack' }, 409))
    await expect(client(fetch).put(identity, noteId, mutation)).rejects.toMatchObject({ code: 'conflict', current })
    const unknown = vi.fn<typeof globalThis.fetch>().mockImplementation(async () => json({ code: 'unknown', detail: 'secret stack' }, 500))
    await expect(client(unknown).list(identity, { afterRevision: 0 })).rejects.not.toThrow('secret stack')
  })
  it('retains rate metadata and rejects malformed success acknowledgements', async () => {
    const limited = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })).mockResolvedValueOnce(json({ code: 'READING_NOTE_LIMIT' }, 429, { 'retry-after': '45' }))
    await expect(client(limited).put(identity, noteId, mutation)).rejects.toMatchObject({ code: 'limit', retryAfterSeconds: 45 })
    const wrong = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })).mockResolvedValueOnce(json({ ...item, note: { ...item.note, text: 'wrong note' } }))
    await expect(client(wrong).put(identity, noteId, mutation)).rejects.toMatchObject({ code: 'invalid-response' })
  })
  it('bounds response bytes and ignores late responses after account closure', async () => {
    const oversized = vi.fn<typeof globalThis.fetch>().mockResolvedValue(new Response(' '.repeat(8 * 1024 * 1024 + 1), { headers: { 'content-type': 'application/json' } }))
    await expect(client(oversized).list(identity, { afterRevision: 0 })).rejects.toMatchObject({ code: 'invalid-response' })
    let finish!: (response: Response) => void
    const fetch = vi.fn<typeof globalThis.fetch>().mockImplementation(() => new Promise(resolve => { finish = resolve })), api = client(fetch)
    const request = api.list(identity, { afterRevision: 0 }); api.close(); finish(json(page))
    await expect(request).rejects.toMatchObject({ code: 'aborted' })
    expect(fetch.mock.calls[0][1]?.signal?.aborted).toBe(true)
  })
})
