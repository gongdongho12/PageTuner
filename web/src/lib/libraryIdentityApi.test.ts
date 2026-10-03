import { describe, expect, it, vi } from 'vitest'
import fixture from '../../../contracts/fixtures/library-identity-v1/original-request.json'
import { createLibraryIdentityClient } from './libraryIdentityApi'
import { validateLibraryIdentity } from './libraryIdentity'
const identity = validateLibraryIdentity(fixture.identity), recordId = fixture.recordId
const csrf = { headerName: 'X-CSRF-TOKEN', token: 'fixture-token' }
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
const accepted = { ...fixture, verified: true }
describe('explicit read-only portable server verification', () => {
  it('uses current credentials, CSRF and a bounded POST body with canonical UUID without changing identity', async () => {
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(json(csrf)).mockResolvedValueOnce(json(accepted))
    const api = createLibraryIdentityClient({ username: 'reader', password: 'fixture-only' }, { fetch })
    expect(await api.verify(recordId.toUpperCase(), identity)).toEqual(accepted)
    expect(fetch.mock.calls.map(([url]) => url)).toEqual(['/api/v1/csrf', '/api/v1/library-identity/verify'])
    expect(fetch.mock.calls[1][1]).toMatchObject({ method: 'POST', cache: 'no-store', redirect: 'error', credentials: 'same-origin', mode: 'same-origin', headers: { 'X-CSRF-TOKEN': 'fixture-token' } })
    expect(JSON.parse(fetch.mock.calls[1][1]!.body as string)).toEqual(fixture); api.close()
  })
  it('refuses success for another record, identity, kind or unexpected response data', async () => {
    for (const response of [{ ...accepted, recordId: '00000000-0000-4000-8000-000000000001' }, { ...accepted, identity: { ...identity, bookId: 'different' } }, { ...accepted, kind: 'TRANSLATION' }, { ...accepted, unexpected: true }, { ...accepted, verified: false }]) {
      const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(json(csrf)).mockResolvedValueOnce(json(response)), api = createLibraryIdentityClient({ username: 'reader', password: 'fixture-only' }, { fetch })
      await expect(api.verify(recordId, identity)).rejects.toMatchObject({ code: 'invalid-response' }); api.close()
    }
  })
  it('distinguishes absent owned records, mismatch and unavailable legacy records', async () => {
    for (const [status, code, result] of [[404, 'LIBRARY_IDENTITY_NOT_FOUND', 'not-found'], [409, 'LIBRARY_IDENTITY_MISMATCH', 'mismatch'], [409, 'LIBRARY_IDENTITY_UNAVAILABLE', 'unavailable'], [400, 'LIBRARY_IDENTITY_INVALID', 'invalid-request']] as const) {
      const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(json(csrf)).mockResolvedValueOnce(json({ code }, status)), api = createLibraryIdentityClient({ username: 'reader', password: 'fixture-only' }, { fetch })
      await expect(api.verify(recordId, identity)).rejects.toMatchObject({ code: result }); api.close()
    }
  })
  it('blocks malformed UUID/CSRF and oversized response before trusting server data', async () => {
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(json({ ...csrf, headerName: 'Authorization' })), api = createLibraryIdentityClient({ username: 'reader', password: 'fixture-only' }, { fetch })
    await expect(api.verify('bad', identity)).rejects.toMatchObject({ code: 'invalid-request' }); expect(fetch).not.toHaveBeenCalled()
    await expect(api.verify(recordId, identity)).rejects.toMatchObject({ code: 'invalid-response' }); expect(fetch).toHaveBeenCalledTimes(1)
    fetch.mockResolvedValueOnce(json(csrf)).mockResolvedValueOnce(json({ ...accepted, extra: 'a'.repeat(65536) }))
    await expect(api.verify(recordId, identity)).rejects.toMatchObject({ code: 'invalid-response' }); api.close()
  })
  it('ignores a late response after logout even when transport ignores abort', async () => {
    let finish!: (response: Response) => void
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(json(csrf)).mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    const api = createLibraryIdentityClient({ username: 'reader', password: 'fixture-only' }, { fetch }), pending = api.verify(recordId, identity)
    await vi.waitFor(() => expect(fetch).toHaveBeenCalledTimes(2)); api.close(); finish(json(accepted))
    await expect(pending).rejects.toMatchObject({ code: 'aborted' }); await expect(api.verify(recordId, identity)).rejects.toMatchObject({ code: 'aborted' }); expect(fetch).toHaveBeenCalledTimes(2)
  })
})
