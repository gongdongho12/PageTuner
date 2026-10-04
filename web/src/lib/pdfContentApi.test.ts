import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { describe, expect, it, vi } from 'vitest'
import vectors from '../../../contracts/fixtures/portable-content-proof-v1/vectors.json'
import storageVector from '../../../contracts/fixtures/pdf-content-v1.json'
import { createPdfContentClient, pdfContentLimits, validatePdfContentDocument, type PdfContentDocument } from './pdfContentApi'

const uploadId = 'a0000000-0000-4000-8000-000000000001', recordId = 'a0000000-0000-4000-8000-000000000002'
const csrf = { headerName: 'X-CSRF-TOKEN', token: 'fixture-csrf' }, credentials = { username: 'reader', password: 'fixture-only' }
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
const payload = (bytes: Uint8Array, mimeType: PdfContentDocument['payloads'][number]['mimeType'] = 'application/pdf') => ({ path: `assets/${createHash('sha256').update(bytes).digest('hex')}`, mimeType, base64: Buffer.from(bytes).toString('base64') })
const fixture = (): PdfContentDocument => {
  const source = vectors[1], pdf = payload(readFileSync(new URL('../../../contracts/fixtures/portable-content-proof-v1/source.pdf', import.meta.url)))
  return { version: 1, language: source.document.language, paragraphs: structuredClone(source.document.paragraphs), assets: source.document.assets.map(a => ({ ...a, role: 'pdf', paragraphId: 'paragraphId' in a ? a.paragraphId : null, alt: 'alt' in a ? a.alt ?? null : null })), payloads: [pdf] }
}
const receipt = async (content: PdfContentDocument = fixture()) => ({ recordId, createdAt: '2026-10-05T01:02:03Z', proof: (await validatePdfContentDocument(content)).proof })
const getApi = (value: unknown) => { const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(json(value)); return { fetch, api: createPdfContentClient(credentials, { fetch }) } }

describe('isolated bounded PDF storage client', () => {
  it('matches the shared actual-byte PDF vector without decoding or creating bindings', async () => {
    expect((await validatePdfContentDocument(fixture())).proof).toEqual(vectors[1].expected)
    expect((await validatePdfContentDocument(storageVector.upload.content)).proof).toEqual(storageVector.expected.proof)
  })
  it('uploads exact snapshots with retained request ID, current Basic credentials and CSRF; retries only when called', async () => {
    const content = fixture(), stored = await receipt(), fetch = vi.fn<typeof globalThis.fetch>().mockImplementation(async path => json(path === '/api/v1/csrf' ? csrf : stored))
    const api = createPdfContentClient(credentials, { fetch }), pending = api.upload(uploadId, content)
    content.language = 'KO'; content.assets[0].alt = 'changed'; content.payloads[0].base64 = 'AAAA'
    expect(await pending).toEqual(stored)
    expect(fetch).toHaveBeenCalledTimes(2)
    expect(fetch.mock.calls[1][0]).toBe('/api/v1/pdf-content')
    expect(fetch.mock.calls[1][1]).toMatchObject({ method: 'POST', cache: 'no-store', redirect: 'error', credentials: 'same-origin', mode: 'same-origin', headers: { Authorization: `Basic ${btoa('reader:fixture-only')}`, 'X-CSRF-TOKEN': csrf.token } })
    expect(JSON.parse(fetch.mock.calls[1][1]!.body as string)).toEqual({ uploadId, content: fixture() })
    expect(await api.upload(uploadId, fixture())).toEqual(stored)
    expect(fetch).toHaveBeenCalledTimes(4); api.close()
  })
  it('downloads whole bytes and recomputes the exact proof before returning data', async () => {
    const value = { ...await receipt(), content: fixture() }, { api, fetch } = getApi(value)
    expect(await api.get(recordId)).toEqual(value)
    expect(fetch.mock.calls[0]).toMatchObject([`/api/v1/pdf-content/${recordId}`, { method: 'GET', cache: 'no-store' }]); api.close()
  })
  it('rejects changed actual payload bytes, language, ordered references and receipt data', async () => {
    const saved = await receipt()
    for (const change of [
      (v: PdfContentDocument) => { v.payloads[0].base64 = 'AAAA' },
      (v: PdfContentDocument) => { v.language = 'KO' },
      (v: PdfContentDocument) => { v.assets[0].alt = '' },
    ]) { const content = fixture(); change(content); const { api } = getApi({ ...saved, content }); await expect(api.get(recordId)).rejects.toMatchObject({ code: 'invalid-response' }); api.close() }
    for (const other of [{ ...saved, recordId: uploadId }, { ...saved, extra: true }, { ...saved, proof: { ...saved.proof, sha256: 'a'.repeat(64) } }]) {
      const { api } = getApi({ ...other, content: fixture() }); await expect(api.get(recordId)).rejects.toMatchObject({ code: 'invalid-response' }); api.close()
    }
  })
  it('verifies a full proof with bounded read-only CSRF request and exact returned identity', async () => {
    const proof = (await receipt()).proof, fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(json(csrf)).mockResolvedValueOnce(json({ recordId, verified: true, proof })), api = createPdfContentClient(credentials, { fetch })
    expect(await api.verify(recordId, proof)).toEqual({ recordId, verified: true, proof })
    expect(fetch.mock.calls[1][0]).toBe(`/api/v1/pdf-content/${recordId}/verify`)
    expect(JSON.parse(fetch.mock.calls[1][1]!.body as string)).toEqual({ proof }); api.close()
  })
  it('refuses asserted invalid or non-PDF proofs and noncanonical IDs before any network request', async () => {
    const fetch = vi.fn<typeof globalThis.fetch>(), api = createPdfContentClient(credentials, { fetch }), proof = (await receipt()).proof
    await expect(api.verify(recordId, { ...proof, sha256: 'a'.repeat(64) })).rejects.toMatchObject({ code: 'invalid-request' })
    await expect(api.verify(recordId, vectors[0].expected as typeof proof)).rejects.toMatchObject({ code: 'invalid-request' })
    await expect(api.upload(uploadId.toUpperCase(), fixture())).rejects.toMatchObject({ code: 'invalid-request' })
    await expect(api.get('../other')).rejects.toMatchObject({ code: 'invalid-request' }); expect(fetch).not.toHaveBeenCalled(); api.close()
  })
  it('rejects unsupported fields, malformed base64, aggregate decoded bytes and metadata before transport', async () => {
    const changes: ((v: PdfContentDocument) => void)[] = [
      v => { Object.assign(v, { sourceProvider: 'invented' }) }, v => { delete (v.assets[0] as Partial<typeof v.assets[number]>).alt },
      v => { v.payloads[0].base64 = 'AB==' }, v => { v.payloads[0].base64 = 'Zg' }, v => { v.payloads[0].base64 = 'Zg==\n' },
      v => { v.payloads = [payload(new Uint8Array(pdfContentLimits.payloadBytes + 1))] },
      v => { v.paragraphs = [{ paragraphId: 'p', text: 'x'.repeat(pdfContentLimits.metadataCodeUnits) }] },
      v => { v.assets = Array.from({ length: 129 }, () => v.assets[0]) },
      v => { v.payloads.push({ ...v.payloads[0] }) }, v => { v.paragraphs = [{ paragraphId: 'p', text: '\ud800' }] },
      v => { const changed = payload(new Uint8Array([1, 2, 3])); v.payloads = [changed]; v.assets[0].path = changed.path },
    ]
    const fetch = vi.fn<typeof globalThis.fetch>(), api = createPdfContentClient(credentials, { fetch })
    for (const change of changes) { const value = fixture(); change(value); await expect(api.upload(uploadId, value)).rejects.toMatchObject({ code: 'invalid-request' }) }
    expect(fetch).not.toHaveBeenCalled(); api.close()
  })
  it('preserves repeated ordered images, explicit null/empty alt and exact language', async () => {
    const content = fixture(), image = payload(new Uint8Array([1, 2, 3]), 'image/png'); content.language = 'KO-KR'; content.paragraphs = [{ paragraphId: 'p|😀', text: '  본문\n' }, { paragraphId: 'empty', text: '' }]
    content.payloads.push(image); content.assets.push({ path: image.path, role: 'image', paragraphId: 'p|😀', alt: null }, { path: image.path, role: 'image', paragraphId: 'empty', alt: '' })
    const saved = await receipt(content), { api } = getApi({ ...saved, content }); expect((await api.get(recordId)).content).toEqual(content); api.close()
  })
  it('rejects noncanonical base64 padding bits before decoded allocation', async () => {
    const decode = vi.spyOn(globalThis, 'atob')
    try { const content = fixture(); content.payloads[0].base64 = 'AB=='; await expect(validatePdfContentDocument(content)).rejects.toMatchObject({ code: 'invalid-response' }); expect(decode).not.toHaveBeenCalled() }
    finally { decode.mockRestore() }
  })
  it('supports valid receipts over 128KiB and full content responses over 8MiB without truncation', async () => {
    const pdfBytes = new Uint8Array(pdfContentLimits.payloadBytes - 1); pdfBytes.set(new TextEncoder().encode('%PDF-'))
    const pdf = payload(pdfBytes), image = payload(new Uint8Array([1]), 'image/png')
    const content: PdfContentDocument = { version: 1, language: 'en', paragraphs: [], payloads: [pdf, image], assets: [{ path: pdf.path, role: 'pdf', paragraphId: null, alt: null }, ...Array.from({ length: 127 }, () => ({ path: image.path, role: 'image' as const, paragraphId: null, alt: '\u0001'.repeat(2000) }))] }
    const saved = await receipt(content), value = { ...saved, content }, body = JSON.stringify(value)
    expect(Buffer.byteLength(JSON.stringify({ uploadId, content }))).toBeLessThan(pdfContentLimits.bodyBytes)
    expect(Buffer.byteLength(JSON.stringify(saved))).toBeGreaterThan(128 * 1024)
    expect(Buffer.byteLength(body)).toBeGreaterThan(pdfContentLimits.bodyBytes)
    expect(Buffer.byteLength(body)).toBeLessThan(pdfContentLimits.responseBytes)
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(json(csrf)).mockResolvedValueOnce(json(saved)).mockResolvedValueOnce(json(value)).mockResolvedValueOnce(json(csrf)).mockResolvedValueOnce(json({ recordId, verified: true, proof: saved.proof })), api = createPdfContentClient(credentials, { fetch })
    expect(await api.upload(uploadId, content)).toEqual(saved); expect((await api.get(recordId)).content).toEqual(content); expect(await api.verify(recordId, saved.proof)).toMatchObject({ verified: true }); api.close()
  })
  it('bounds streamed responses without Content-Length and checks declared length before reading', async () => {
    for (const declared of [false, true]) {
      let cancelled = false
      const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(new Response(new ReadableStream({ start(c) { c.enqueue(new Uint8Array(pdfContentLimits.responseBytes + 1)) }, cancel() { cancelled = true } }), { headers: { 'Content-Type': 'application/json', ...(declared ? { 'Content-Length': String(pdfContentLimits.responseBytes + 1) } : {}) } }))
      const api = createPdfContentClient(credentials, { fetch }); await expect(api.get(recordId)).rejects.toMatchObject({ code: 'invalid-response' }); if (!declared) expect(cancelled).toBe(true); api.close()
    }
  })
  it('distinguishes auth, ownership, reused upload, corruption, mismatch and request limits', async () => {
    for (const [status, code, expected] of [[401, '', 'authentication'], [403, '', 'forbidden'], [404, 'PDF_CONTENT_NOT_FOUND', 'not-found'], [409, 'PDF_CONTENT_UPLOAD_REUSED', 'upload-reused'], [409, 'PDF_CONTENT_MISMATCH', 'mismatch'], [409, 'PDF_CONTENT_UNAVAILABLE', 'unavailable'], [413, '', 'too-large'], [400, 'PDF_CONTENT_INVALID', 'invalid-request']] as const) {
      const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(json({ code }, status)), api = createPdfContentClient(credentials, { fetch }); await expect(api.get(recordId)).rejects.toMatchObject({ code: expected }); api.close()
    }
  })
  it('does not follow redirects or send data with an unexpected CSRF header', async () => {
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(json({ ...csrf, headerName: 'Authorization' })), api = createPdfContentClient(credentials, { fetch })
    await expect(api.upload(uploadId, fixture())).rejects.toMatchObject({ code: 'invalid-response' }); expect(fetch).toHaveBeenCalledTimes(1)
    const response = json(await receipt()); Object.defineProperty(response, 'redirected', { value: true }); fetch.mockResolvedValueOnce(response)
    await expect(api.get(recordId)).rejects.toMatchObject({ code: 'invalid-response' }); api.close()
  })
  it('drops delayed responses after close or explicit abort even when transport ignores abort', async () => {
    for (const close of [true, false]) {
      let finish!: (v: Response) => void
      const fetch = vi.fn<typeof globalThis.fetch>().mockImplementation(() => new Promise(resolve => { finish = resolve })), api = createPdfContentClient(credentials, { fetch }), controller = new AbortController(), pending = api.get(recordId, controller.signal)
      await vi.waitFor(() => expect(fetch).toHaveBeenCalledTimes(1)); if (close) api.close(); else controller.abort()
      finish(json({ ...await receipt(), content: fixture() })); await expect(pending).rejects.toMatchObject({ code: 'aborted' }); api.close()
    }
  })
  it('cancels during asynchronous local hashing before credentials or uploads leave the client', async () => {
    const fetch = vi.fn<typeof globalThis.fetch>(), api = createPdfContentClient(credentials, { fetch }), pending = api.upload(uploadId, fixture())
    api.close(); await expect(pending).rejects.toMatchObject({ code: 'aborted' }); expect(fetch).not.toHaveBeenCalled()
  })
  it('drops a body that finishes after close and never authorizes another request', async () => {
    let finish!: () => void
    const value = JSON.stringify({ ...await receipt(), content: fixture() }), fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(new Response(new ReadableStream({ start(c) { finish = () => { c.enqueue(new TextEncoder().encode(value)); c.close() } } }), { headers: { 'Content-Type': 'application/json' } }))
    const api = createPdfContentClient(credentials, { fetch }), pending = api.get(recordId); await vi.waitFor(() => expect(fetch).toHaveBeenCalledTimes(1)); api.close(); finish()
    await expect(pending).rejects.toMatchObject({ code: 'aborted' }); await expect(api.get(recordId)).rejects.toMatchObject({ code: 'aborted' }); expect(fetch).toHaveBeenCalledTimes(1)
  })
})
