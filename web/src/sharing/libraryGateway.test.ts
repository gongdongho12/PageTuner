import { describe, expect, it, vi } from 'vitest'
import fixture from '../../../contracts/fixtures/local-sharing-v1/document.json'
import { createPhoneLibraryGateway, parseSharedDocument, parseSharedPage, sharedReadingDocument } from './libraryGateway'

const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })
const paired = () => json({ token: 'session-secret-only-in-header', expiresAt: 60_000 })
const source = () => structuredClone(fixture)
const id = fixture.id

describe('shared phone document contract', () => {
  it('preserves fixture identity, UTF-16 position, revision and text without cloud or hash identity', () => {
    const wire = parseSharedDocument(source(), id), result = sharedReadingDocument(wire, new Map())
    expect(result.document.id).toBe(id)
    expect(result.document.paragraphs).toEqual(fixture.paragraphs)
    expect(result.document.outline).toEqual(fixture.outline)
    expect(result.anchor).toEqual(fixture.anchor)
    expect(result.source.revision).toBe(fixture.revision)
    expect(result.document.serverProgress).toBeUndefined()
    expect(result.document.local).toBeUndefined()
    expect(result.document.glossaryIdentity).toBeUndefined()
  })
  it('rejects another document, duplicate paragraphs and stale outline targets', () => {
    expect(() => parseSharedDocument(source(), 'another-id')).toThrow('invalid_response')
    const duplicate = source(); duplicate.paragraphs.push(duplicate.paragraphs[0]); expect(() => parseSharedDocument(duplicate, id)).toThrow('invalid_response')
    const outline = source(); outline.outline[0].paragraphId = 'unknown'; expect(() => parseSharedDocument(outline, id)).toThrow('invalid_response')
  })
  it('rejects a UTF-16 anchor inside a surrogate pair rather than rounding it', () => {
    const document = source(); document.paragraphs[1].text = 'a😀b'; document.anchor.characterOffset = 2
    expect(() => parseSharedDocument(document, id)).toThrow('invalid_response')
    document.anchor.characterOffset = 3
    expect(parseSharedDocument(document, id).anchor?.characterOffset).toBe(3)
  })
  it('keeps an unanchored image as a gallery asset without inventing a paragraph', () => {
    const blob = new Blob(['x'], { type: 'image/png' })
    const wire = parseSharedDocument({ ...source(), assets: [{ id: 'image/a', mimeType: 'image/png', byteLength: 1, role: 'image', paragraphId: null, alt: '표지' }] }, id)
    const reading = sharedReadingDocument(wire, new Map([['image/a', blob]]))
    expect(reading.illustrations).toEqual([{ id: 'image/a', paragraphId: undefined, alt: '표지', blob }])
    expect(reading.document.assets?.images).toBeUndefined()
    expect(reading.document.paragraphs).toEqual(fixture.paragraphs)
  })
  it('requires the original PDF binary and accepts textless physical pages', () => {
    const wire = { ...source(), format: 'pdf', paragraphs: [{ paragraphId: 'physical:1', text: '' }], outline: [], anchor: { paragraphId: 'physical:1', characterOffset: 0 }, assets: [{ id: 'pdf', mimeType: 'application/pdf', byteLength: 1, role: 'pdf' }] }
    const parsed = parseSharedDocument(wire, id)
    expect(sharedReadingDocument(parsed, new Map([['pdf', new Blob(['x'], { type: 'application/pdf' })]])).document.assets?.pdf?.size).toBe(1)
    expect(() => parseSharedDocument({ ...wire, assets: [] }, id)).toThrow('invalid_response')
    expect(() => parseSharedDocument({ ...wire, assets: [...wire.assets, ...wire.assets] }, id)).toThrow('invalid_response')
  })
  it('rejects active image formats and verifies pagination batch coordinates', () => {
    expect(() => parseSharedDocument({ ...source(), assets: [{ id: 'svg', mimeType: 'image/svg+xml', byteLength: 1, role: 'image' }] }, id)).toThrow('invalid_response')
    expect(() => parseSharedPage({ items: [], total: 12, offset: 50, limit: 50 }, 0)).toThrow('invalid_response')
    expect(() => parseSharedPage({ items: [source(), source()], total: 2, offset: 0, limit: 50 }, 0)).toThrow('invalid_response')
    expect(parseSharedPage({ items: [], total: 0, offset: 50, limit: 50 }, 50).total).toBe(0)
  })
})

describe('phone library gateway session and transport', () => {
  it('uses one origin and only a request header for the session, pins each binary to its revision', async () => {
    const document = { ...source(), id: 'opaque /?한', revision: 'rev/2?x', assets: [{ id: 'image /?', role: 'image', mimeType: 'image/png', byteLength: 3, paragraphId: null, alt: '' }] }
    const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(paired()).mockResolvedValueOnce(json(document)).mockResolvedValueOnce(new Response('png', { headers: { 'Content-Type': 'image/png' } })).mockResolvedValueOnce(new Response(null, { status: 204 }))
    const gateway = createPhoneLibraryGateway({ fetch: fetcher, now: () => 1 })
    await gateway.pair('1234-5678'); const reading = await gateway.read(document.id); await gateway.disconnect()
    expect(reading.source.revision).toBe('rev/2?x')
    expect(reading.illustrations[0].blob.size).toBe(3)
    expect(fetcher.mock.calls.map(call => call[0])).toEqual(['/api/share/v1/pair', '/api/share/v1/books/opaque%20%2F%3F%ED%95%9C', '/api/share/v1/books/opaque%20%2F%3F%ED%95%9C/assets/image%20%2F%3F?revision=rev%2F2%3Fx', '/api/share/v1/session'])
    for (const [url, init] of fetcher.mock.calls) { expect(String(url)).not.toContain('secret'); expect(init?.credentials).toBe('omit'); expect(init?.cache).toBe('no-store'); expect(init?.redirect).toBe('error') }
    expect(fetcher.mock.calls[0][1]?.headers).not.toHaveProperty('X-PageTuner-Session')
    expect(fetcher.mock.calls[1][1]?.headers).toHaveProperty('X-PageTuner-Session', 'session-secret-only-in-header')
    await expect(gateway.list(0)).rejects.toThrow('session_expired')
    expect(fetcher).toHaveBeenCalledTimes(4)
  })
  it('expires locally without issuing a further data request', async () => {
    let now = 1; const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(paired())
    const gateway = createPhoneLibraryGateway({ fetch: fetcher, now: () => now })
    await gateway.pair('code'); now = 60_000
    await expect(gateway.read(id)).rejects.toThrow('session_expired')
    expect(fetcher).toHaveBeenCalledTimes(1)
  })
  it('does not restore a late pairing response after disconnect', async () => {
    let resolve!: (response: Response) => void
    const fetcher = vi.fn<typeof fetch>().mockImplementation(() => new Promise(resolveValue => { resolve = resolveValue }))
    const gateway = createPhoneLibraryGateway({ fetch: fetcher, now: () => 1 })
    const pairing = gateway.pair('code'); gateway.forget(); resolve(paired())
    await expect(pairing).rejects.toThrow('session_expired')
    await expect(gateway.list(0)).rejects.toThrow('session_expired')
    expect(fetcher).toHaveBeenCalledTimes(1)
  })
  it('rejects late document data from a revoked session', async () => {
    let resolve!: (response: Response) => void
    const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(paired()).mockImplementationOnce(() => new Promise(resolveValue => { resolve = resolveValue }))
    const gateway = createPhoneLibraryGateway({ fetch: fetcher, now: () => 1 })
    await gateway.pair('code'); const reading = gateway.read(id); gateway.forget(); resolve(json(source()))
    await expect(reading).rejects.toThrow('session_expired')
  })
  it('does not clear a new pairing when an older disconnect finishes late', async () => {
    let finishDisconnect!: (response: Response) => void
    const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(paired()).mockImplementationOnce(() => new Promise(resolve => { finishDisconnect = resolve }))
      .mockResolvedValueOnce(json({ token: 'new-session', expiresAt: 80_000 })).mockResolvedValueOnce(json({ items: [], total: 0, offset: 0, limit: 50 }))
    const gateway = createPhoneLibraryGateway({ fetch: fetcher, now: () => 1 })
    await gateway.pair('first'); const disconnect = gateway.disconnect(); await gateway.pair('second'); finishDisconnect(new Response(null, { status: 204 }))
    await expect(disconnect).rejects.toThrow('session_expired')
    expect((await gateway.list(0)).total).toBe(0)
    expect(fetcher.mock.calls[3][1]?.headers).toHaveProperty('X-PageTuner-Session', 'new-session')
  })
  it('forgets a rejected token and does not surface raw server errors', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(paired()).mockResolvedValueOnce(json({ code: 'unauthorized', message: 'private file path or token' }, 401))
    const gateway = createPhoneLibraryGateway({ fetch: fetcher, now: () => 1 })
    await gateway.pair('code'); await expect(gateway.list(0)).rejects.toThrow('session_expired'); await expect(gateway.list(0)).rejects.toThrow('session_expired')
    expect(fetcher).toHaveBeenCalledTimes(2)
  })
  it('rejects mismatched binary bytes or content type and keeps the revision error distinct', async () => {
    for (const response of [new Response('x', { headers: { 'Content-Type': 'image/png' } }), new Response('abc', { headers: { 'Content-Type': 'text/html' } }), json({}, 409)]) {
      const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(paired()).mockResolvedValueOnce(json({ ...source(), assets: [{ id: 'a', role: 'image', mimeType: 'image/png', byteLength: 3 }] })).mockResolvedValueOnce(response)
      const gateway = createPhoneLibraryGateway({ fetch: fetcher, now: () => 1 }); await gateway.pair('code')
      await expect(gateway.read(id)).rejects.toThrow(response.status === 409 ? 'revision_changed' : 'invalid_response')
    }
  })
  it('cancels caller-aborted requests and allows another request on the same session', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(paired()).mockImplementationOnce((_url, init) => new Promise((_resolve, reject) => init!.signal!.addEventListener('abort', () => reject(new Error('aborted'))))).mockResolvedValueOnce(json({ items: [], total: 0, offset: 0, limit: 50 }))
    const gateway = createPhoneLibraryGateway({ fetch: fetcher, now: () => 1 }); await gateway.pair('code')
    const controller = new AbortController(), pending = gateway.list(0, controller.signal); controller.abort()
    await expect(pending).rejects.toMatchObject({ name: 'AbortError' })
    expect((await gateway.list(0)).total).toBe(0)
  })
  it('validates public capabilities before accepting a different protocol', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(json({ version: 2, readOnly: true, expiresAt: 60_000 }))
    await expect(createPhoneLibraryGateway({ fetch: fetcher }).status()).rejects.toThrow('invalid_response')
  })
})
