import { describe, expect, it, vi } from 'vitest'
import { createBookGlossaryClient, validateBookGlossary, validateBookGlossaryScope, validateBookGlossaryView, type BookGlossaryEntry } from './bookGlossaryApi'
const scope = { providerId: 'wtr-lab', bookId: 'Book/源', targetLanguage: 'zh-hant' }
const term: BookGlossaryEntry = { id: 'original-id', sourceTerm: ' Alice ', translatedTerm: ' アリス ', displayTerm: ' 별칭 ', kind: 'Character', caseSensitive: false, enabled: true }
const view = { ...scope, version: 2, entries: [term], updatedAt: '2026-10-03T00:00:00Z' }
const csrf = { headerName: 'X-CSRF-TOKEN', token: 'safe-token' }
const json = (value: unknown, status = 200, headers = {}) => new Response(JSON.stringify(value), { status, headers: { 'content-type': 'application/json', ...headers } })
describe('book glossary transport and complete snapshot validation', () => {
  it('uses a CSRF-protected read body for maximum Unicode book IDs and preserves exact fields', async () => {
    const longScope = { ...scope, bookId: '源'.repeat(2000) }, fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(json(csrf)).mockResolvedValueOnce(json({ ...view, ...longScope }))
    const api = createBookGlossaryClient({ username: 'alice', password: 'fixture-only' }, { fetch })
    expect(await api.get(longScope)).toEqual({ ...view, ...longScope })
    expect(fetch.mock.calls[1][0]).toBe('/api/v1/book-glossary/query')
    expect(fetch.mock.calls[1][1]).toMatchObject({ method: 'POST', redirect: 'error', cache: 'no-store', mode: 'same-origin', headers: { 'X-CSRF-TOKEN': 'safe-token' } })
    expect(JSON.parse(fetch.mock.calls[1][1]!.body as string)).toEqual(longScope); api.close()
  })
  it('sends full ordered metadata with CAS and accepts null deletion as versioned state', async () => {
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(json(csrf)).mockResolvedValueOnce(json({ ...view, version: 3, entries: null }))
    const api = createBookGlossaryClient({ username: 'alice', password: 'fixture-only' }, { fetch })
    const mutation = { expectedVersion: 2, mutationId: '11111111-1111-4111-8111-111111111111', entries: null }
    expect(await api.put(scope, mutation)).toMatchObject({ version: 3, entries: null })
    expect(JSON.parse(fetch.mock.calls[1][1]!.body as string)).toEqual({ ...scope, ...mutation }); api.close()
  })
  it('rejects wrong identity, malformed timestamps and conflicts from another book', async () => {
    expect(() => validateBookGlossaryView({ ...view, updatedAt: '2026-02-30T00:00:00Z' })).toThrow()
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(json(csrf)).mockResolvedValueOnce(json({ ...view, bookId: 'Other' }))
      .mockResolvedValueOnce(json(csrf)).mockResolvedValueOnce(json({ code: 'BOOK_GLOSSARY_CONFLICT', current: { ...view, targetLanguage: 'en' } }, 409))
    const api = createBookGlossaryClient({ username: 'alice', password: 'fixture-only' }, { fetch })
    await expect(api.get(scope)).rejects.toMatchObject({ code: 'invalid-response' })
    await expect(api.put(scope, { entries: [term], expectedVersion: 1, mutationId: '11111111-1111-4111-8111-111111111111' })).rejects.toMatchObject({ code: 'invalid-response' }); api.close()
  })
  it('enforces field/UTF16 identity limits without sorting, dropping duplicate sources or trimming text', () => {
    expect(validateBookGlossary([term, { ...term, id: 'second', enabled: false }])).toEqual([term, { ...term, id: 'second', enabled: false }])
    expect(validateBookGlossaryScope({ ...scope, bookId: '😀'.repeat(1000) }).bookId).toHaveLength(2000)
    for (const value of [{ ...term, id: ' padded' }, { ...term, sourceTerm: '\ufeff ' }, { ...term, displayTerm: '\ud800' }, { ...term, enabled: 'true' }]) expect(() => validateBookGlossary([value])).toThrow()
    expect(() => validateBookGlossary([term, term])).toThrow()
    expect(() => validateBookGlossaryScope({ ...scope, targetLanguage: 'zh-Hant' })).toThrow()
    expect(() => validateBookGlossaryScope({ ...scope, providerId: 'id\ufeff' })).toThrow()
  })
  it('retains a rate deadline before a repeated PUT and does not leak a request after close', async () => {
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(json(csrf)).mockResolvedValueOnce(json({ code: 'BOOK_GLOSSARY_LIMIT' }, 429, { 'retry-after': '60' }))
    const api = createBookGlossaryClient({ username: 'alice', password: 'fixture-only' }, { fetch })
    const mutation = { expectedVersion: 2, mutationId: '11111111-1111-4111-8111-111111111111', entries: [term] }
    await expect(api.put(scope, mutation)).rejects.toMatchObject({ code: 'limit', retryAfterSeconds: 60 })
    await expect(api.put(scope, mutation)).rejects.toMatchObject({ code: 'limit' }); expect(fetch).toHaveBeenCalledTimes(2)
    api.close(); await expect(api.get(scope)).rejects.toMatchObject({ code: 'aborted' }); expect(fetch).toHaveBeenCalledTimes(2)
  })
})
