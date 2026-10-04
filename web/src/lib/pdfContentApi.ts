import { createPortableContentProof, validatePortableContentProof, type PortableContentProof } from './portableContentProof'
import { validRecordId, validTimestamp } from './validation'
import type { components } from '../generated/pdfContent'

export type PdfContentDocument = components['schemas']['PdfContentDocument']
export type PdfContentReceipt = { recordId: string; createdAt: string; proof: PortableContentProof }
export type PdfContentRecord = PdfContentReceipt & { content: PdfContentDocument }
export type PdfContentVerification = { recordId: string; verified: true; proof: PortableContentProof }
export type PdfContentErrorCode = 'invalid-request' | 'invalid-response' | 'authentication' | 'forbidden' | 'not-found' | 'upload-reused' | 'mismatch' | 'unavailable' | 'too-large' | 'server' | 'network' | 'timeout' | 'aborted'
export class PdfContentError extends Error { constructor(readonly code: PdfContentErrorCode) { super(`PDF content storage: ${code}`); this.name = 'PdfContentError' } }
export const pdfContentLimits = { bodyBytes: 8 * 1024 * 1024, responseBytes: 12 * 1024 * 1024, verifyBytes: 2 * 1024 * 1024, payloadBytes: 4 * 1024 * 1024,
  payloads: 64, references: 128, paragraphs: 4096, metadataCodeUnits: 262144 } as const
const invalid = (): never => { throw new PdfContentError('invalid-response') }
const object = (v: unknown): Record<string, unknown> => v && typeof v === 'object' && !Array.isArray(v) ? v as Record<string, unknown> : invalid()
const keys = (v: Record<string, unknown>, names: string[]) => { if (Object.keys(v).length !== names.length || names.some(key => !Object.hasOwn(v, key))) invalid() }
const string = (v: unknown): string => typeof v === 'string' ? v : invalid()
const uuid = (v: unknown): string => validRecordId(v) && v === v.toLowerCase() ? v : invalid()
const encoder = new TextEncoder()
const sameProof = (a: PortableContentProof, b: PortableContentProof) => JSON.stringify([a.version, a.representation, a.language, a.paragraphHash, a.originalFileSha256, a.originalFileByteLength, a.sha256, a.assets.map(v => [v.path, v.role, v.paragraphId, v.alt, v.mimeType, v.byteLength, v.sha256])]) === JSON.stringify([b.version, b.representation, b.language, b.paragraphHash, b.originalFileSha256, b.originalFileByteLength, b.sha256, b.assets.map(v => [v.path, v.role, v.paragraphId, v.alt, v.mimeType, v.byteLength, v.sha256])])
function base64Length(value: string): number {
  if (!value || value.length > Math.ceil(pdfContentLimits.payloadBytes / 3) * 4 || value.length % 4) return invalid()
  const padding = value.endsWith('==') ? 2 : value.endsWith('=') ? 1 : 0, end = value.length - padding
  for (let i = 0; i < end; i++) { const c = value.charCodeAt(i); if (!(c >= 65 && c <= 90 || c >= 97 && c <= 122 || c >= 48 && c <= 57 || c === 43 || c === 47)) return invalid() }
  const last = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'.indexOf(value[end - 1])
  if (padding === 2 && (last & 15) !== 0 || padding === 1 && (last & 3) !== 0) return invalid()
  const size = value.length / 4 * 3 - padding
  return size > 0 && size <= pdfContentLimits.payloadBytes ? size : invalid()
}
/** Snapshots mutable DTOs before hashing, then verifies every supplied original/payload byte. No PDF decoding. */
export async function validatePdfContentDocument(value: unknown): Promise<{ content: PdfContentDocument; proof: PortableContentProof }> {
  const v = object(value); keys(v, ['version', 'language', 'paragraphs', 'assets', 'payloads'])
  if (v.version !== 1 || !Array.isArray(v.paragraphs) || v.paragraphs.length > pdfContentLimits.paragraphs || !Array.isArray(v.assets) || !v.assets.length || v.assets.length > pdfContentLimits.references || !Array.isArray(v.payloads) || !v.payloads.length || v.payloads.length > pdfContentLimits.payloads) return invalid()
  const content: PdfContentDocument = { version: 1, language: string(v.language),
    paragraphs: v.paragraphs.map(input => { const p = object(input); keys(p, ['paragraphId', 'text']); return { paragraphId: string(p.paragraphId), text: string(p.text) } }),
    assets: v.assets.map(input => { const a = object(input); keys(a, ['path', 'role', 'paragraphId', 'alt']); if (a.role !== 'pdf' && a.role !== 'image') return invalid(); return { path: string(a.path), role: a.role, paragraphId: a.paragraphId === null ? null : string(a.paragraphId), alt: a.alt === null ? null : string(a.alt) } }),
    payloads: v.payloads.map(input => { const p = object(input); keys(p, ['path', 'mimeType', 'base64']); if (!['application/pdf', 'image/png', 'image/jpeg', 'image/gif', 'image/webp'].includes(p.mimeType as string)) return invalid(); return { path: string(p.path), mimeType: p.mimeType as PdfContentDocument['payloads'][number]['mimeType'], base64: string(p.base64) } }) }
  const metadataSize = content.language.length + content.paragraphs.reduce((n, p) => n + p.paragraphId.length + p.text.length, 0) + content.assets.reduce((n, a) => n + (a.paragraphId?.length ?? 0) + (a.alt?.length ?? 0), 0)
  if (metadataSize > pdfContentLimits.metadataCodeUnits || content.payloads.reduce((n, p) => n + base64Length(p.base64), 0) > pdfContentLimits.payloadBytes) return invalid()
  const assets = content.payloads.map(p => { const binary = atob(p.base64); if (btoa(binary) !== p.base64) return invalid(); return { path: p.path, mimeType: p.mimeType, bytes: Uint8Array.from(binary, c => c.charCodeAt(0)) } })
  const pdf = content.assets.filter(a => a.role === 'pdf'); if (pdf.length !== 1) return invalid()
  const original = assets.find(a => a.path === pdf[0].path); if (!original || original.mimeType !== 'application/pdf') return invalid()
  // A signature check is not a PDF parser or a page-count assertion.
  if (![37, 80, 68, 70, 45].every((byte, index) => original.bytes[index] === byte)) return invalid()
  const proof = await createPortableContentProof({ representation: 'PDF', language: content.language, paragraphs: content.paragraphs,
    assetReferences: content.assets, assets, originalFile: original.bytes }).catch(invalid)
  return { content, proof }
}
async function storageProof(value: unknown): Promise<PortableContentProof> {
  const proof = await validatePortableContentProof(value).catch(invalid), unique = new Map(proof.assets.map(a => [a.path, a]))
  if (proof.representation !== 'PDF' || !proof.assets.length || proof.assets.length > pdfContentLimits.references || unique.size > pdfContentLimits.payloads || [...unique.values()].reduce((n, a) => n + a.byteLength, 0) > pdfContentLimits.payloadBytes || proof.language.length + proof.assets.reduce((n, a) => n + (a.paragraphId?.length ?? 0) + (a.alt?.length ?? 0), 0) > pdfContentLimits.metadataCodeUnits) return invalid()
  return proof
}
async function readJson(response: Response, signal: AbortSignal, maximum: number): Promise<unknown> {
  if (Number(response.headers.get('content-length')) > maximum) return invalid()
  const reader = response.body?.getReader(); if (!reader) return invalid()
  const decoder = new TextDecoder('utf-8', { fatal: true }); let size = 0, body = ''
  try {
    while (true) { if (signal.aborted) throw new PdfContentError('aborted'); const part = await reader.read(); if (part.done) break; size += part.value.byteLength; if (size > maximum) return invalid(); body += decoder.decode(part.value, { stream: true }) }
    return JSON.parse(body + decoder.decode()) as unknown
  } catch (error) { if (error instanceof PdfContentError) throw error; return invalid() }
  finally { await reader.cancel().catch(() => undefined); reader.releaseLock() }
}
export interface PdfContentClient {
  /** The caller retains uploadId for explicit retries. No deduplication, automatic retry, binding or sync permission. */
  upload(uploadId: string, content: PdfContentDocument, signal?: AbortSignal): Promise<PdfContentReceipt>
  get(recordId: string, signal?: AbortSignal): Promise<PdfContentRecord>
  verify(recordId: string, proof: PortableContentProof, signal?: AbortSignal): Promise<PdfContentVerification>
  close(): void
}
/** Isolated account client. Callers must close it on account/origin/client-generation changes. */
export function createPdfContentClient(credentials: { username: string; password: string }, options: { fetch?: typeof fetch; timeoutMs?: number } = {}): PdfContentClient {
  if (!credentials.username.trim() || /[:\r\n]/.test(credentials.username) || /[\r\n]/.test(credentials.password)) throw new PdfContentError('invalid-request')
  let authorization = `Basic ${btoa(Array.from(encoder.encode(`${credentials.username}:${credentials.password}`), byte => String.fromCharCode(byte)).join(''))}`, closed = false
  const transport = options.fetch ?? globalThis.fetch.bind(globalThis), timeoutMs = options.timeoutMs ?? 20_000, controllers = new Set<AbortController>()
  if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) throw new PdfContentError('invalid-request')
  const assertCurrent = (signal?: AbortSignal) => { if (closed || signal?.aborted) throw new PdfContentError('aborted') }
  async function request(path: string, maximum: number, body?: string, csrf?: { headerName: string; token: string }, signal?: AbortSignal) {
    assertCurrent(signal)
    const controller = new AbortController(), abort = () => controller.abort(); let timedOut = false
    controllers.add(controller); signal?.addEventListener('abort', abort, { once: true }); const timer = setTimeout(() => { timedOut = true; abort() }, timeoutMs)
    try {
      const headers: Record<string, string> = { Authorization: authorization, Accept: 'application/json', 'X-Requested-With': 'XMLHttpRequest' }
      if (body !== undefined) headers['Content-Type'] = 'application/json'
      if (csrf) headers[csrf.headerName] = csrf.token
      const response = await transport(path, { method: body === undefined ? 'GET' : 'POST', body, headers, signal: controller.signal, credentials: 'same-origin', mode: 'same-origin', redirect: 'error', cache: 'no-store' })
      assertCurrent(controller.signal)
      if (response.redirected) return invalid()
      if (response.status === 401) throw new PdfContentError('authentication')
      if (response.status === 403) throw new PdfContentError('forbidden')
      if (response.status === 413) throw new PdfContentError('too-large')
      const type = response.headers.get('content-type')?.split(';')[0].trim().toLowerCase()
      if (response.ok ? type !== 'application/json' : !['application/json', 'application/problem+json'].includes(type ?? '')) throw new PdfContentError(response.status >= 500 ? 'server' : 'invalid-response')
      const value = await readJson(response, controller.signal, response.ok ? maximum : 64 * 1024); assertCurrent(controller.signal)
      if (response.ok) return value
      const code = object(value).code
      if (response.status === 404 && code === 'PDF_CONTENT_NOT_FOUND') throw new PdfContentError('not-found')
      if (response.status === 409 && code === 'PDF_CONTENT_UPLOAD_REUSED') throw new PdfContentError('upload-reused')
      if (response.status === 409 && code === 'PDF_CONTENT_MISMATCH') throw new PdfContentError('mismatch')
      if (response.status === 409 && code === 'PDF_CONTENT_UNAVAILABLE') throw new PdfContentError('unavailable')
      if (response.status === 400 && code === 'PDF_CONTENT_INVALID') throw new PdfContentError('invalid-request')
      throw new PdfContentError(response.status >= 500 ? 'server' : 'invalid-response')
    } catch (error) {
      assertCurrent(signal)
      if (timedOut) throw new PdfContentError('timeout')
      if (error instanceof PdfContentError) throw error
      throw new PdfContentError('network')
    } finally { clearTimeout(timer); controllers.delete(controller); controller.abort(); signal?.removeEventListener('abort', abort) }
  }
  async function csrf(signal?: AbortSignal) {
    const token = object(await request('/api/v1/csrf', 16 * 1024, undefined, undefined, signal)); assertCurrent(signal)
    if (typeof token.headerName !== 'string' || !['x-csrf-token', 'x-xsrf-token'].includes(token.headerName.toLowerCase()) || typeof token.token !== 'string' || !token.token || token.token.length > 4096 || /[\r\n]/.test(token.token)) return invalid()
    return { headerName: token.headerName, token: token.token }
  }
  async function receipt(value: unknown, signal?: AbortSignal) {
    const v = object(value), recordId = uuid(v.recordId), createdAt = string(v.createdAt)
    if (!validTimestamp(createdAt)) return invalid()
    const proof = await storageProof(v.proof); assertCurrent(signal)
    return { recordId, createdAt, proof }
  }
  return {
    async upload(uploadId, content, signal) {
      assertCurrent(signal)
      let checked: Awaited<ReturnType<typeof validatePdfContentDocument>>, body: string
      try { uuid(uploadId); checked = await validatePdfContentDocument(content); body = JSON.stringify({ uploadId, content: checked.content }); if (encoder.encode(body).byteLength > pdfContentLimits.bodyBytes) return invalid() }
      catch { assertCurrent(signal); throw new PdfContentError('invalid-request') }
      assertCurrent(signal)
      const token = await csrf(signal), value = object(await request('/api/v1/pdf-content', pdfContentLimits.verifyBytes, body, token, signal)); assertCurrent(signal)
      keys(value, ['recordId', 'createdAt', 'proof']); const result = await receipt(value, signal); assertCurrent(signal)
      if (!sameProof(checked.proof, result.proof)) return invalid()
      return result
    },
    async get(recordId, signal) {
      assertCurrent(signal); try { uuid(recordId) } catch { throw new PdfContentError('invalid-request') }
      const value = object(await request(`/api/v1/pdf-content/${recordId}`, pdfContentLimits.responseBytes, undefined, undefined, signal)); assertCurrent(signal)
      keys(value, ['recordId', 'createdAt', 'content', 'proof']); const result = await receipt(value, signal)
      if (result.recordId !== recordId) return invalid()
      const checked = await validatePdfContentDocument(value.content); assertCurrent(signal)
      if (!sameProof(checked.proof, result.proof)) return invalid()
      return { ...result, content: checked.content }
    },
    async verify(recordId, proof, signal) {
      assertCurrent(signal); let checked: PortableContentProof, body: string
      try { uuid(recordId); checked = await storageProof(proof); body = JSON.stringify({ proof: checked }); if (encoder.encode(body).byteLength > pdfContentLimits.verifyBytes) return invalid() }
      catch { assertCurrent(signal); throw new PdfContentError('invalid-request') }
      assertCurrent(signal)
      const token = await csrf(signal), value = object(await request(`/api/v1/pdf-content/${recordId}/verify`, pdfContentLimits.verifyBytes, body, token, signal)); assertCurrent(signal)
      keys(value, ['recordId', 'verified', 'proof']); const actual = await storageProof(value.proof); assertCurrent(signal)
      if (value.recordId !== recordId || value.verified !== true || !sameProof(checked, actual)) return invalid()
      return { recordId, verified: true, proof: actual }
    },
    close() { closed = true; authorization = ''; controllers.forEach(c => c.abort()); controllers.clear() },
  }
}
