import { afterEach, describe, expect, it, vi } from 'vitest'
import { createLibraryOrganizationClient, LibraryOrganizationError, validateLibraryOrganization, validateLibraryOrganizationView, type LibraryOrganizationClient, type LibraryOrganizationMutation, type LibraryOrganization, type LibraryOrganizationScope } from './libraryOrganizationApi'

const scope: LibraryOrganizationScope = { kind: 'ORIGINAL', recordId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa' }
const organization: LibraryOrganization = { folder: '읽을 책', tags: ['소설', 'Novel'], favorite: true }
const mutation: LibraryOrganizationMutation = { expectedVersion: 0, mutationId: '11111111-1111-1111-1111-111111111111', organization }
const view = { ...scope, version: 1, organization, updatedAt: '2026-10-02T12:00:00Z' }
const json = (value: unknown, status = 200, headers = {}) => new Response(JSON.stringify(value), { status, headers: { 'content-type': status === 200 ? 'application/json' : 'application/problem+json', ...headers } })
const csrf = () => json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
const clients: LibraryOrganizationClient[] = []
function client(fetch: typeof globalThis.fetch) { const api = createLibraryOrganizationClient({ username: 'alice', password: 'secret' }, { fetch }); clients.push(api); return api }
afterEach(() => { clients.splice(0).forEach(api => api.close()); vi.restoreAllMocks() })

describe('library organization transport', () => {
  it('shares account Retry-After across document kinds and restores a persisted deadline', async () => {
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(csrf()).mockResolvedValueOnce(json({ code: 'LIBRARY_ORGANIZATION_LIMIT' }, 429, { 'retry-after': '60' }))
    const api = client(fetch)
    await expect(api.put(scope, mutation)).rejects.toMatchObject({ code: 'limit' })
    await expect(api.put({ ...scope, kind: 'TRANSLATION' }, mutation)).rejects.toMatchObject({ code: 'limit' })
    expect(fetch).toHaveBeenCalledTimes(2)
    const nextFetch = vi.fn<typeof globalThis.fetch>(), reopened = client(nextFetch)
    reopened.deferWritesUntil!(Date.now() + 45_000)
    await expect(reopened.put(scope, mutation)).rejects.toMatchObject({ code: 'limit' })
    expect(nextFetch).not.toHaveBeenCalled()
  })
  it('rechecks an account limit received while the CSRF request is outstanding', async () => {
    let finish!: (response: Response) => void
    const fetch = vi.fn<typeof globalThis.fetch>().mockImplementation(() => new Promise(resolve => { finish = resolve })), api = client(fetch)
    const sending = api.put(scope, mutation); api.deferWritesUntil!(Date.now() + 45_000); finish(csrf())
    await expect(sending).rejects.toMatchObject({ code: 'limit' }); expect(fetch).toHaveBeenCalledTimes(1)
  })
  it('uses same-origin authenticated CSRF writes for the original document identity', async () => {
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(csrf()).mockResolvedValueOnce(json(view))
    expect(await client(fetch).put(scope, mutation)).toEqual(view)
    expect(fetch.mock.calls[0][0]).toBe('/api/v1/csrf')
    expect(fetch.mock.calls[1][0]).toBe('/api/v1/library-organization/ORIGINAL/' + scope.recordId)
    expect(fetch.mock.calls[1][1]).toMatchObject({ method: 'PUT', mode: 'same-origin', credentials: 'same-origin', redirect: 'error', cache: 'no-store', headers: { Authorization: `Basic ${btoa('alice:secret')}`, 'X-CSRF-TOKEN': 'csrf' } })
    expect(JSON.parse(fetch.mock.calls[1][1]?.body as string)).toEqual(mutation)
  })
  it('strictly validates bounded canonical Unicode strings without case or compatibility folding', () => {
    for (const p of [{ ...organization, folder: 'x'.repeat(201) }, { ...organization, folder: ' folder' }, { ...organization, folder: '\ufefffolder' },
      { ...organization, folder: 'a\u0000b' }, { ...organization, folder: 'a\u0085b' }, { ...organization, folder: '\ud800' }, { ...organization, folder: '\udfff' },
      { ...organization, tags: [''] }, { ...organization, tags: ['x'.repeat(61)] }, { ...organization, tags: ['a', 'a'] },
      { ...organization, tags: Array.from({ length: 33 }, (_, i) => String(i)) }, { ...organization, tags: [' a'] },
      { ...organization, favorite: 'true' }, { ...organization, password: 'secret' }]) expect(() => validateLibraryOrganization(p)).toThrow(LibraryOrganizationError)
    expect(validateLibraryOrganization({ folder: '😀'.repeat(100), tags: ['A', 'a', 'Ａ', '😀'.repeat(30)], favorite: false }).tags).toEqual(['A', 'a', 'Ａ', '😀'.repeat(30)])
    expect(validateLibraryOrganization({ folder: '', tags: [], favorite: false })).toEqual({ folder: '', tags: [], favorite: false })
  })
  it('rejects invalid versions timestamps or null stored values but accepts an uninitialized view', () => {
    for (const value of [{ ...view, version: 0 }, { ...view, organization: null }, { ...view, updatedAt: '2026-02-30T00:00:00Z' },
      { ...view, updatedAt: '2026-10-02T00:00:00+09:00' }, { ...view, kind: 'LOCAL' }, { ...view, recordId: 'title' },
      { ...view, password: 'secret' }, { ...view, version: Number.MAX_SAFE_INTEGER + 1 }]) expect(() => validateLibraryOrganizationView(value)).toThrow(LibraryOrganizationError)
    expect(validateLibraryOrganizationView({ ...scope, version: 0, organization: null, updatedAt: null })).toEqual({ ...scope, version: 0, organization: null, updatedAt: null })
  })
  it('rejects malformed request identities before contacting the server', async () => {
    const fetch = vi.fn<typeof globalThis.fetch>(), api = client(fetch)
    await expect(api.get({ ...scope, recordId: '../secret' })).rejects.toMatchObject({ code: 'invalid-request' })
    await expect(api.put(scope, { ...mutation, expectedVersion: Number.MAX_SAFE_INTEGER })).rejects.toMatchObject({ code: 'invalid-request' })
    await expect(api.put(scope, { ...mutation, organization: { ...organization, tags: ['bad\nline'] } })).rejects.toMatchObject({ code: 'invalid-request' })
    expect(fetch).not.toHaveBeenCalled()
  })
  it('rejects acknowledgements and conflicts from other document identities', async () => {
    for (const value of [{ ...view, version: 2 }, { ...view, organization: { ...organization, favorite: false } }, { ...view, kind: 'TRANSLATION' }]) {
      const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(csrf()).mockResolvedValueOnce(json(value))
      await expect(client(fetch).put(scope, mutation)).rejects.toMatchObject({ code: 'invalid-response' })
    }
    const conflict = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(csrf()).mockResolvedValueOnce(json({ code: 'LIBRARY_ORGANIZATION_CONFLICT', current: { ...view, kind: 'TRANSLATION' } }, 409))
    await expect(client(conflict).put(scope, mutation)).rejects.toMatchObject({ code: 'invalid-response' })
    const get = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(json({ ...view, recordId: mutation.mutationId }))
    await expect(client(get).get(scope)).rejects.toMatchObject({ code: 'invalid-response' })
  })
  it('preserves safe conflicts, missing documents and rate limits without raw details', async () => {
    const conflict = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(csrf()).mockResolvedValueOnce(json({ code: 'LIBRARY_ORGANIZATION_CONFLICT', current: view }, 409))
    await expect(client(conflict).put(scope, mutation)).rejects.toMatchObject({ code: 'conflict', current: view })
    for (const [status, code, expected] of [[404, 'NOT_FOUND', 'not-found'], [409, 'EXHAUSTED', 'exhausted'], [400, 'MUTATION_REUSED', 'mutation-reused']] as const) {
      const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(json({ code: 'LIBRARY_ORGANIZATION_' + code, detail: 'private token' }, status))
      await expect(client(fetch).get(scope)).rejects.toMatchObject({ code: expected, current: undefined })
    }
    const limited = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(json({ code: 'LIBRARY_ORGANIZATION_LIMIT' }, 429, { 'retry-after': '45' }))
    await expect(client(limited).get(scope)).rejects.toMatchObject({ code: 'limit', retryAfterSeconds: 45 })
  })
  it('rejects hostile CSRF headers and malformed or oversized response bodies', async () => {
    const hostile = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(json({ headerName: 'Authorization', token: 'steal' }))
    await expect(client(hostile).put(scope, mutation)).rejects.toMatchObject({ code: 'invalid-response' }); expect(hostile).toHaveBeenCalledTimes(1)
    for (const text of [' '.repeat(8193), '{bad json']) {
      const malformed = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(new Response(text, { headers: { 'content-type': 'application/json' } }))
      await expect(client(malformed).get(scope)).rejects.toMatchObject({ code: 'invalid-response' })
    }
  })
  it('treats interrupted streams as retryable and discards late replies after logout', async () => {
    let reads = 0
    const broken = new Response(new ReadableStream<Uint8Array>({ pull(c) { if (reads++ === 0) c.enqueue(new TextEncoder().encode('{"version":')); else c.error(new TypeError('lost network')) } }), { headers: { 'content-type': 'application/json' } })
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(broken).mockResolvedValueOnce(json(view)), api = client(fetch)
    await expect(api.get(scope)).rejects.toMatchObject({ code: 'network' }); expect(await api.get(scope)).toEqual(view)
    let finish!: (response: Response) => void
    const late = vi.fn<typeof globalThis.fetch>().mockImplementation(() => new Promise(resolve => { finish = resolve })), closing = client(late)
    const pending = closing.get(scope); closing.close(); finish(json(view))
    await expect(pending).rejects.toMatchObject({ code: 'aborted' }); expect(late.mock.calls[0][1]?.signal?.aborted).toBe(true)
  })
})

