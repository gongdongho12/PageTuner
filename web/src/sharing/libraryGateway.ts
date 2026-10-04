import type { ReadingDocument } from '../lib/readingDocument'
import type { ReadingAnchor } from '../lib/offline'

export type SharedBook = { id: string; title: string; format: 'txt' | 'markdown' | 'epub' | 'pdf'; edition: 'original' | 'translation' }
export type SharedLibraryPage = { items: SharedBook[]; total: number; offset: number; limit: number }
export type SharedAsset = { id: string; mimeType: string; byteLength: number; role: 'pdf' | 'image'; paragraphId?: string; alt: string }
export type SharedDocument = SharedBook & { language: string; revision: string; paragraphs: ReadingDocument['paragraphs']; outline: NonNullable<ReadingDocument['outline']>; assets: SharedAsset[]; anchor?: ReadingAnchor }
export type SharedReading = { source: SharedDocument; document: ReadingDocument; illustrations: { id: string; paragraphId?: string; alt: string; blob: Blob }[]; anchor?: ReadingAnchor }
export type SharingStatus = { version: 1; readOnly: true; expiresAt: number }
export interface LibraryGateway {
  status(signal?: AbortSignal): Promise<SharingStatus>
  pair(code: string, signal?: AbortSignal): Promise<number>
  list(offset: number, signal?: AbortSignal): Promise<SharedLibraryPage>
  read(id: string, signal?: AbortSignal): Promise<SharedReading>
  disconnect(): Promise<void>
  forget(): void
}

export class SharingError extends Error {
  constructor(readonly code: string) { super(code); this.name = 'SharingError' }
}
const invalid = (): never => { throw new SharingError('invalid_response') }
const object = (value: unknown): Record<string, unknown> => value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : invalid()
const string = (value: unknown, max = 4096, blank = false): string => typeof value === 'string' && (blank || value.length > 0) && value.length <= max ? value : invalid()
const integer = (value: unknown, min = 0, max = Number.MAX_SAFE_INTEGER): number => Number.isSafeInteger(value) && Number(value) >= min && Number(value) <= max ? Number(value) : invalid()
// Opaque IDs remain unchanged. Encoding happens only at the URL boundary.
const id = (value: unknown): string => string(value, 256)
export function parseSharedBook(value: unknown): SharedBook {
  const v = object(value), format = string(v.format), edition = string(v.edition)
  if (!['txt', 'markdown', 'epub', 'pdf'].includes(format) || !['original', 'translation'].includes(edition)) return invalid()
  return { id: id(v.id), title: string(v.title), format: format as SharedBook['format'], edition: edition as SharedBook['edition'] }
}
export function parseSharedPage(value: unknown, requestedOffset: number): SharedLibraryPage {
  const v = object(value)
  if (!Array.isArray(v.items) || v.items.length > 50) return invalid()
  const items = v.items.map(parseSharedBook), total = integer(v.total), offset = integer(v.offset), limit = integer(v.limit, 1, 50)
  if (offset !== requestedOffset || items.length > limit || items.length > Math.max(0, total - offset) || new Set(items.map(book => book.id)).size !== items.length) return invalid()
  return { items, total, offset, limit }
}
export function parseSharedDocument(value: unknown, requestedId: string): SharedDocument {
  const v = object(value), book = parseSharedBook(v)
  if (book.id !== requestedId || !Array.isArray(v.paragraphs) || !v.paragraphs.length || v.paragraphs.length > 100_000) return invalid()
  let characters = 0
  const paragraphs = v.paragraphs.map(raw => {
    const p = object(raw), text = string(p.text, 4_000_000, true); characters += text.length
    return { paragraphId: string(p.paragraphId, 4096), text }
  })
  const ids = new Set(paragraphs.map(p => p.paragraphId))
  if (characters > 4_000_000 || ids.size !== paragraphs.length) return invalid()
  const rawOutline = v.outline ?? [], rawAssets = v.assets ?? []
  if (!Array.isArray(rawOutline) || rawOutline.length > 100_000 || !Array.isArray(rawAssets) || rawAssets.length > 10_000) return invalid()
  const outline = rawOutline.map(raw => { const item = object(raw), paragraphId = string(item.paragraphId, 4096); if (!ids.has(paragraphId)) return invalid(); return { title: string(item.title), paragraphId } })
  const assets: SharedAsset[] = rawAssets.map(raw => {
    const a = object(raw), role = string(a.role), mimeType = string(a.mimeType, 100)
    const paragraphId = a.paragraphId == null ? undefined : string(a.paragraphId, 4096)
    if (role !== 'pdf' && role !== 'image' || role === 'pdf' && mimeType !== 'application/pdf' || role === 'image' && !['image/png', 'image/jpeg', 'image/gif', 'image/webp', 'image/avif', 'image/bmp'].includes(mimeType) || paragraphId !== undefined && !ids.has(paragraphId)) return invalid()
    return { id: id(a.id), role, mimeType, byteLength: integer(a.byteLength, 1, 32 * 1024 * 1024), paragraphId, alt: a.alt == null ? '' : string(a.alt, 4096, true) }
  })
  if (new Set(assets.map(a => a.id)).size !== assets.length || assets.filter(a => a.role === 'pdf').length !== (book.format === 'pdf' ? 1 : 0)) return invalid()
  let anchor: ReadingAnchor | undefined
  if (v.anchor != null) {
    const a = object(v.anchor), paragraphId = string(a.paragraphId, 4096), paragraph = paragraphs.find(p => p.paragraphId === paragraphId)
    if (!paragraph) return invalid()
    const characterOffset = integer(a.characterOffset, 0, paragraph.text.length)
    if (characterOffset && /[\uD800-\uDBFF]/.test(paragraph.text[characterOffset - 1]) && /[\uDC00-\uDFFF]/.test(paragraph.text[characterOffset])) return invalid()
    anchor = { paragraphId, characterOffset }
  }
  return { ...book, language: string(v.language, 100), revision: string(v.revision, 256), paragraphs, outline, assets, anchor }
}
export function sharedReadingDocument(source: SharedDocument, assets: ReadonlyMap<string, Blob>): SharedReading {
  const illustrations = source.assets.filter(a => a.role === 'image').map(a => ({ id: a.id, paragraphId: a.paragraphId, alt: a.alt, blob: assets.get(a.id) ?? invalid() }))
  const images = illustrations.filter(a => a.paragraphId !== undefined).map(a => ({ paragraphId: a.paragraphId!, alt: a.alt, blob: a.blob }))
  const pdf = source.assets.find(a => a.role === 'pdf')
  // This is a phone snapshot, never a cloud record or a newly imported local document.
  return { source, illustrations, anchor: source.anchor, document: { id: source.id, kind: source.edition, bookTitle: source.title, chapterTitle: source.title,
    language: source.language, paragraphs: source.paragraphs, outline: source.outline,
    assets: { ...(pdf ? { pdf: assets.get(pdf.id) ?? invalid() } : {}), ...(images.length ? { images } : {}) } } }
}

async function boundedBody(response: Response, maximum: number): Promise<Uint8Array> {
  const length = response.headers.get('Content-Length')
  if (length && Number(length) > maximum) { void response.body?.cancel(); throw new SharingError('too_large') }
  const reader = response.body?.getReader()
  if (!reader) return new Uint8Array()
  const parts: Uint8Array[] = []; let size = 0
  try {
    for (;;) { const { done, value } = await reader.read(); if (done) break; size += value.length; if (size > maximum) throw new SharingError('too_large'); parts.push(value) }
  } catch (error) { await reader.cancel().catch(() => {}); throw error }
  finally { reader.releaseLock() }
  const result = new Uint8Array(size); let at = 0; for (const part of parts) { result.set(part, at); at += part.length }; return result
}

export function createPhoneLibraryGateway(options: { fetch?: typeof fetch; now?: () => number } = {}): LibraryGateway {
  const fetcher = options.fetch ?? fetch, now = options.now ?? Date.now
  let token: string | undefined, expiresAt = 0, generation = 0
  const active = new Set<AbortController>()
  const forget = () => { generation++; token = undefined; expiresAt = 0; active.forEach(controller => controller.abort()); active.clear() }
  async function request(path: string, init: RequestInit = {}, authenticated = false, binary?: SharedAsset): Promise<unknown> {
    if (authenticated && (!token || now() >= expiresAt)) { forget(); throw new SharingError('session_expired') }
    const controller = new AbortController(), session = generation, outer = init.signal
    const abort = () => controller.abort(); outer?.addEventListener('abort', abort, { once: true }); if (outer?.aborted) controller.abort()
    active.add(controller); const timeout = setTimeout(() => controller.abort(), binary ? 60_000 : 20_000)
    try {
      const response = await fetcher(`/api/share/v1${path}`, { ...init, signal: controller.signal, credentials: 'omit', cache: 'no-store', redirect: 'error',
        headers: { ...(init.body ? { 'Content-Type': 'application/json' } : {}), ...(authenticated ? { 'X-PageTuner-Session': token! } : {}) } })
      if (generation !== session) throw new SharingError('session_expired')
      if (response.status === 401 || response.status === 410) { if (authenticated) forget(); throw new SharingError(authenticated ? 'session_expired' : 'pair_failed') }
      if (!response.ok) {
        // Server text is untrusted and may expose platform internals. Only known error codes reach UI.
        throw new SharingError(response.status === 429 ? 'rate_limited' : response.status === 409 ? 'revision_changed' : response.status === 404 ? 'not_found' : response.status === 413 ? 'too_large' : 'unavailable')
      }
      if (response.status === 204) return undefined
      const bytes = await boundedBody(response, binary ? binary.byteLength : 24 * 1024 * 1024)
      if (generation !== session || controller.signal.aborted) throw new SharingError('session_expired')
      if (binary) {
        if (bytes.length !== binary.byteLength || response.headers.get('Content-Type')?.split(';')[0].trim() !== binary.mimeType) return invalid()
        return new Blob([bytes.buffer as ArrayBuffer], { type: binary.mimeType })
      }
      if (!response.headers.get('Content-Type')?.toLowerCase().includes('application/json')) return invalid()
      try { return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes)) } catch { return invalid() }
    } catch (error) { if (error instanceof SharingError) throw error; if (outer?.aborted) throw new DOMException('Aborted', 'AbortError'); throw new SharingError('connection_lost') }
    finally { clearTimeout(timeout); outer?.removeEventListener('abort', abort); active.delete(controller) }
  }
  return {
    forget,
    async status(signal) { const v = object(await request('/status', { signal })); if (v.version !== 1 || v.readOnly !== true) return invalid(); return { version: 1, readOnly: true, expiresAt: integer(v.expiresAt, 1) } },
    async pair(code, signal) {
      forget(); const session = generation
      const v = object(await request('/pair', { method: 'POST', body: JSON.stringify({ code }), signal }))
      const nextToken = string(v.token, 512), deadline = integer(v.expiresAt, 1)
      if (generation !== session || deadline <= now()) throw new SharingError('session_expired')
      token = nextToken; expiresAt = deadline; return deadline
    },
    async list(offset, signal) { integer(offset); return parseSharedPage(await request(`/books?offset=${offset}&limit=50`, { signal }, true), offset) },
    async read(documentId, signal) {
      const path = `/books/${encodeURIComponent(documentId)}`
      const source = parseSharedDocument(await request(path, { signal }, true), documentId)
      if (source.assets.reduce((sum, asset) => sum + asset.byteLength, 0) > 64 * 1024 * 1024) throw new SharingError('too_large')
      const assets = new Map<string, Blob>()
      for (const asset of source.assets) assets.set(asset.id, await request(`${path}/assets/${encodeURIComponent(asset.id)}?revision=${encodeURIComponent(source.revision)}`, { signal }, true, asset) as Blob)
      return sharedReadingDocument(source, assets)
    },
    async disconnect() { const closing = generation; try { if (token) await request('/session', { method: 'DELETE' }, true) } finally { if (generation === closing) forget() } },
  }
}
