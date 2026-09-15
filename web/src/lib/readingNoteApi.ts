import { validRecordId, kotlinTrim } from './validation'
import { validReadingProgressIdentity, validReadingProgressAnchor, type ReadingProgressIdentity } from './readingProgressApi'
import { readingRangeText } from './readingSelection'
import type { ReadingDocument } from './readingDocument'
import type { ReadingNote } from './readingNotes'
import type { components } from '../generated/readingNotes'

export type ReadingNoteIdentity = ReadingProgressIdentity
export type ReadingNoteInput = components['schemas']['ReadingNoteInput']
export type ReadingNoteItem = components['schemas']['ReadingNoteItem']
export type ReadingNoteMutation = components['schemas']['PutReadingNoteRequest']
export type ReadingNotePage = components['schemas']['ReadingNoteChanges']
export type ReadingNoteChange = Pick<ReadingNoteMutation, 'deleted' | 'note'>
export type ReadingNoteChoice = { local: ReadingNoteChange; remote: ReadingNoteItem }
export type ReadingNoteErrorCode = 'authentication' | 'forbidden' | 'not-found' | 'conflict' | 'choice-stale' | 'invalid-request' | 'mutation-reused' | 'limit' | 'exhausted' | 'server' | 'network' | 'timeout' | 'aborted' | 'invalid-response' | 'storage'
const messages: Record<ReadingNoteErrorCode, string> = {
  authentication: '읽기 기록을 동기화하려면 다시 로그인해 주세요.', forbidden: '읽기 기록을 동기화할 권한을 확인할 수 없습니다.',
  'not-found': '읽기 기록을 동기화할 서버 문서를 찾을 수 없습니다.', conflict: '다른 기기에서 읽기 기록이 변경되었습니다. 유지할 기록을 선택해 주세요.',
  'choice-stale': '읽기 기록이 다시 변경되었습니다. 최신 기록을 확인하고 다시 선택해 주세요.',
  'invalid-request': '동기화할 읽기 기록을 확인해 주세요.', 'mutation-reused': '읽기 기록 변경 요청을 확인할 수 없습니다.',
  limit: '읽기 기록 동기화 요청이 많습니다. 잠시 후 다시 시도해 주세요.', exhausted: '읽기 기록 변경 횟수가 서버 한도에 도달했습니다.',
  server: '서버에서 읽기 기록을 처리하지 못했습니다.', network: '서버에 연결할 수 없습니다. 변경한 읽기 기록은 기기에 보관됩니다.',
  timeout: '읽기 기록 동기화 시간이 초과되었습니다.', aborted: '읽기 기록 동기화가 취소되었습니다.',
  'invalid-response': '서버의 읽기 기록 응답을 확인할 수 없습니다.', storage: '이 기기에 읽기 기록 변경을 보관하지 못했습니다.',
}
export class ReadingNoteError extends Error {
  constructor(readonly code: ReadingNoteErrorCode, readonly current?: ReadingNoteItem, readonly retryAfterSeconds?: number) { super(messages[code]); this.name = 'ReadingNoteError' }
}
const invalid = (): never => { throw new ReadingNoteError('invalid-response') }
const integer = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
function object(value: unknown): Record<string, unknown> { return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : invalid() }
function text(value: unknown, max: number, nonblank = false): string {
  if (typeof value !== 'string' || value.length > max || nonblank && !kotlinTrim(value)) return invalid()
  for (let i = 0; i < value.length; i++) {
    const code = value.charCodeAt(i)
    if (code >= 0xd800 && code <= 0xdbff) { const next = value.charCodeAt(++i); if (!(next >= 0xdc00 && next <= 0xdfff)) return invalid() }
    else if (code >= 0xdc00 && code <= 0xdfff) return invalid()
  }
  return value
}
function timestamp(value: unknown): string {
  const result = text(value, 64), parts = /^(\d{4})-(\d\d)-(\d\d)T(\d\d):(\d\d):(\d\d)(?:\.\d{1,9})?Z$/.exec(result)
  if (!parts) return invalid()
  const [year, month, day, hour, minute, second] = parts.slice(1).map(Number), leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0)
  if (year < 1 || month < 1 || month > 12 || day < 1 || day > [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][month - 1] || hour > 23 || minute > 59 || second > 59) return invalid()
  return result
}
function anchor(value: unknown) {
  if (!validReadingProgressAnchor(value)) return invalid()
  text(value.paragraphId, 200, true)
  return { paragraphId: value.paragraphId, characterOffset: value.characterOffset }
}
export function validateReadingNoteInput(value: unknown): ReadingNoteInput {
  const n = object(value)
  if (!['BOOKMARK', 'NOTE', 'HIGHLIGHT'].includes(n.kind as string)) return invalid()
  const result: ReadingNoteInput = { kind: n.kind as ReadingNoteInput['kind'], title: text(n.title, 200, true),
    text: text(n.text, 4000, n.kind === 'NOTE'), anchor: anchor(n.anchor), range: null, createdAt: timestamp(n.createdAt) }
  if (n.kind === 'HIGHLIGHT') {
    const r = object(n.range), start = anchor(r.start), end = anchor(r.end)
    if (start.paragraphId !== result.anchor.paragraphId || start.characterOffset !== result.anchor.characterOffset || start.paragraphId === end.paragraphId && start.characterOffset >= end.characterOffset) return invalid()
    result.range = { start, end }
  } else if (n.range !== null) return invalid()
  return result
}
export function readingNoteInput(note: ReadingNote, document?: ReadingDocument): ReadingNoteInput {
  const input = validateReadingNoteInput({ kind: note.kind.toUpperCase(), title: note.title, text: note.text, anchor: note.anchor, range: note.range ?? null, createdAt: note.createdAt })
  if (document) {
    const p = document.paragraphs.find(p => p.paragraphId === input.anchor.paragraphId), offset = input.anchor.characterOffset
    if (!p || offset >= p.text.length || offset > 0 && /[\uD800-\uDBFF]/.test(p.text[offset - 1]) && /[\uDC00-\uDFFF]/.test(p.text[offset])) return invalid()
    if (input.range && readingRangeText(document, input.range) !== note.excerpt) return invalid()
  }
  return input
}
export function validateReadingNoteItem(value: unknown, noteId?: string, allowEmpty = false): ReadingNoteItem {
  const n = object(value)
  if (typeof n.noteId !== 'string' || !validRecordId(n.noteId) || noteId && n.noteId !== noteId || !integer(n.version) || !integer(n.changeRevision) || typeof n.deleted !== 'boolean') return invalid()
  if (n.version === 0) {
    if (!allowEmpty || n.changeRevision !== 0 || !n.deleted || n.note !== null || n.updatedAt !== null) return invalid()
    return { noteId: n.noteId, version: 0, changeRevision: 0, deleted: true, note: null, updatedAt: null }
  }
  if (n.changeRevision < 1) return invalid()
  const updatedAt = timestamp(n.updatedAt)
  if (n.deleted) {
    if (n.note !== null) return invalid()
    return { noteId: n.noteId, version: n.version, changeRevision: n.changeRevision, deleted: true, note: null, updatedAt }
  }
  const input = validateReadingNoteInput(n.note), full = object(n.note)
  return { noteId: n.noteId, version: n.version, changeRevision: n.changeRevision, deleted: false,
    note: { ...input, excerpt: text(full.excerpt, input.kind === 'HIGHLIGHT' ? 4000 : 1000, input.kind === 'HIGHLIGHT') }, updatedAt }
}
export function validateReadingNoteMutation(value: unknown): ReadingNoteMutation {
  const n = object(value)
  if (!integer(n.expectedVersion) || n.expectedVersion >= Number.MAX_SAFE_INTEGER || typeof n.mutationId !== 'string' || !validRecordId(n.mutationId) || typeof n.deleted !== 'boolean') return invalid()
  if (n.deleted && n.note !== null) return invalid()
  return { expectedVersion: n.expectedVersion, mutationId: n.mutationId, deleted: n.deleted, note: n.deleted ? null : validateReadingNoteInput(n.note) }
}
export function sameReadingNoteChange(a: ReadingNoteChange, b: ReadingNoteChange): boolean {
  return a.deleted === b.deleted && (a.deleted || JSON.stringify(a.note) === JSON.stringify(b.note))
}
export function localReadingNote(documentId: string, item: ReadingNoteItem): ReadingNote | null {
  if (item.deleted || !item.note) return null
  const note = item.note
  return { id: item.noteId, documentId, kind: note.kind.toLowerCase() as ReadingNote['kind'], title: note.title, text: note.text,
    excerpt: note.excerpt, anchor: { ...note.anchor }, ...(note.range ? { range: structuredClone(note.range) } : {}), createdAt: note.createdAt }
}
export type ReadingNoteListQuery = { afterRevision: number; limit?: number; untilRevision?: number }
export function validateReadingNotePage(value: unknown, identity: ReadingNoteIdentity, query: ReadingNoteListQuery): ReadingNotePage {
  const p = object(value), limit = query.limit ?? 50
  if (p.kind !== identity.kind || p.recordId !== identity.recordId || !Array.isArray(p.items) || p.items.length > limit || !integer(p.nextAfterRevision) || !integer(p.watermark) || typeof p.hasMore !== 'boolean' ||
      p.watermark < query.afterRevision || query.untilRevision !== undefined && p.watermark !== query.untilRevision || p.nextAfterRevision < query.afterRevision || p.nextAfterRevision > p.watermark) return invalid()
  let previous = query.afterRevision
  const versions = new Map<string, number>()
  const items = p.items.map(value => {
    const item = validateReadingNoteItem(value)
    if (item.changeRevision <= previous || item.changeRevision > (p.watermark as number) || (versions.get(item.noteId) ?? 0) >= item.version) return invalid()
    previous = item.changeRevision; versions.set(item.noteId, item.version); return item
  })
  if (p.hasMore ? !items.length || p.nextAfterRevision !== previous || p.nextAfterRevision >= p.watermark : p.nextAfterRevision !== p.watermark) return invalid()
  return { kind: identity.kind, recordId: identity.recordId, items, nextAfterRevision: p.nextAfterRevision, watermark: p.watermark, hasMore: p.hasMore }
}

async function readJson(response: Response, signal: AbortSignal, maximum: number): Promise<unknown> {
  if (Number(response.headers.get('content-length')) > maximum) return invalid()
  const reader = response.body?.getReader()
  if (!reader) return invalid()
  let bytes = 0, body = ''; const decoder = new TextDecoder('utf-8', { fatal: true })
  try {
    while (true) {
      if (signal.aborted) throw new ReadingNoteError('aborted')
      const part = await reader.read(); if (part.done) break
      bytes += part.value.byteLength; if (bytes > maximum) return invalid()
      body += decoder.decode(part.value, { stream: true })
    }
    return JSON.parse(body + decoder.decode()) as unknown
  } catch (error) { if (error instanceof ReadingNoteError) throw error; return invalid() }
  finally { await reader.cancel().catch(() => undefined); reader.releaseLock() }
}
export interface ReadingNoteClient {
  list(identity: ReadingNoteIdentity, query: ReadingNoteListQuery, signal?: AbortSignal): Promise<ReadingNotePage>
  put(identity: ReadingNoteIdentity, noteId: string, input: ReadingNoteMutation, signal?: AbortSignal): Promise<ReadingNoteItem>
  close(): void
}
export function createReadingNoteClient(credentials: { username: string; password: string }, options: { fetch?: typeof fetch; timeoutMs?: number } = {}): ReadingNoteClient {
  if (!credentials.username.trim() || /[:\r\n]/.test(credentials.username) || /[\r\n]/.test(credentials.password)) throw new ReadingNoteError('invalid-request')
  let authorization = `Basic ${btoa(Array.from(new TextEncoder().encode(`${credentials.username}:${credentials.password}`), byte => String.fromCharCode(byte)).join(''))}`, closed = false
  const controllers = new Set<AbortController>(), transport = options.fetch ?? globalThis.fetch.bind(globalThis), timeoutMs = options.timeoutMs ?? 20_000
  if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) throw new ReadingNoteError('invalid-request')
  function path(identity: ReadingNoteIdentity) { if (!validReadingProgressIdentity(identity)) throw new ReadingNoteError('invalid-request'); return `/api/v1/reading-notes/${identity.kind}/${identity.recordId}` }
  async function request(url: string, signal?: AbortSignal, body?: ReadingNoteMutation, csrf?: { headerName: string; token: string }, noteId?: string): Promise<unknown> {
    if (closed || signal?.aborted) throw new ReadingNoteError('aborted')
    const controller = new AbortController(), abort = () => controller.abort(); let timedOut = false
    controllers.add(controller); signal?.addEventListener('abort', abort, { once: true })
    const timer = setTimeout(() => { timedOut = true; abort() }, timeoutMs)
    try {
      const headers: Record<string, string> = { Authorization: authorization, Accept: 'application/json', 'X-Requested-With': 'XMLHttpRequest' }
      if (body) headers['Content-Type'] = 'application/json'
      if (csrf) headers[csrf.headerName] = csrf.token
      const response = await transport(url, { method: body ? 'PUT' : 'GET', body: body ? JSON.stringify(body) : undefined, headers,
        credentials: 'same-origin', mode: 'same-origin', redirect: 'error', cache: 'no-store', signal: controller.signal })
      if (closed || controller.signal.aborted) throw new ReadingNoteError('aborted')
      if (response.redirected) return invalid()
      if (response.status === 401) throw new ReadingNoteError('authentication')
      if (response.status === 403) throw new ReadingNoteError('forbidden')
      if (response.status === 404) throw new ReadingNoteError('not-found')
      const type = response.headers.get('content-type')?.split(';')[0].trim().toLowerCase()
      if (response.ok ? type !== 'application/json' : !['application/json', 'application/problem+json'].includes(type ?? '')) throw new ReadingNoteError(response.status >= 500 ? 'server' : 'invalid-response')
      const value = await readJson(response, controller.signal, response.ok && !body ? 8 * 1024 * 1024 : 64 * 1024)
      if (closed || controller.signal.aborted) throw new ReadingNoteError('aborted')
      if (response.ok) return value
      const problem = object(value)
      if (response.status === 409 && problem.code === 'READING_NOTE_CONFLICT' && noteId) throw new ReadingNoteError('conflict', validateReadingNoteItem(problem.current, noteId, true))
      if (response.status === 409 && problem.code === 'READING_NOTE_REVISION_EXHAUSTED') throw new ReadingNoteError('exhausted')
      if (response.status === 400 && problem.code === 'READING_NOTE_MUTATION_REUSED') throw new ReadingNoteError('mutation-reused')
      if (response.status === 400 && problem.code === 'READING_NOTE_INVALID' || response.status === 413) throw new ReadingNoteError('invalid-request')
      if (response.status === 429 && problem.code === 'READING_NOTE_LIMIT') {
        const retry = Number(response.headers.get('retry-after'))
        throw new ReadingNoteError('limit', undefined, Number.isSafeInteger(retry) && retry > 0 && retry <= 86400 ? retry : 60)
      }
      throw new ReadingNoteError(response.status >= 500 ? 'server' : 'invalid-response')
    } catch (error) {
      if (closed || signal?.aborted) throw new ReadingNoteError('aborted')
      if (timedOut) throw new ReadingNoteError('timeout')
      if (error instanceof ReadingNoteError) throw error
      throw new ReadingNoteError('network')
    } finally { clearTimeout(timer); controllers.delete(controller); signal?.removeEventListener('abort', abort) }
  }
  return {
    async list(identity, query, signal) {
      const base = path(identity), limit = query.limit ?? 50
      if (!integer(query.afterRevision) || !integer(limit) || limit < 1 || limit > 100 || query.untilRevision !== undefined && (!integer(query.untilRevision) || query.untilRevision < query.afterRevision)) throw new ReadingNoteError('invalid-request')
      const parameters = new URLSearchParams({ afterRevision: String(query.afterRevision), limit: String(limit) })
      if (query.untilRevision !== undefined) parameters.set('untilRevision', String(query.untilRevision))
      return validateReadingNotePage(await request(`${base}?${parameters}`, signal), identity, query)
    },
    async put(identity, noteId, input, signal) {
      const base = path(identity)
      if (!validRecordId(noteId)) throw new ReadingNoteError('invalid-request')
      let body: ReadingNoteMutation
      try { body = validateReadingNoteMutation(input) } catch { throw new ReadingNoteError('invalid-request') }
      if (new TextEncoder().encode(JSON.stringify(body)).byteLength > 32 * 1024) throw new ReadingNoteError('invalid-request')
      const token = object(await request('/api/v1/csrf', signal))
      if (typeof token.headerName !== 'string' || !['x-csrf-token', 'x-xsrf-token'].includes(token.headerName.toLowerCase()) || typeof token.token !== 'string' || !token.token || token.token.length > 4096 || /[\r\n]/.test(token.token)) return invalid()
      const item = validateReadingNoteItem(await request(`${base}/${noteId}`, signal, body, { headerName: token.headerName, token: token.token }, noteId), noteId)
      if (item.version !== input.expectedVersion + 1 || item.deleted !== body.deleted) return invalid()
      if (!body.deleted && body.note && item.note) {
        const accepted = validateReadingNoteInput(item.note)
        // Instant formatting can omit trailing zero fractional digits.
        if (Date.parse(accepted.createdAt) !== Date.parse(body.note.createdAt)) return invalid()
        accepted.createdAt = body.note.createdAt
        if (!sameReadingNoteChange(body, { deleted: false, note: accepted })) return invalid()
      }
      return item
    },
    close() { closed = true; authorization = ''; controllers.forEach(value => value.abort()); controllers.clear() },
  }
}
