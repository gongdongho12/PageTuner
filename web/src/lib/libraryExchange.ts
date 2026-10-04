import { Unzip, UnzipInflate, zipSync } from 'fflate'
import { inspectExchangeZip, exchangeCrc32 } from './exchangeZip'
import { localFileHash } from './localDocuments'
import type { ReadingAnchor } from './offline'
import type { ReadingRange } from './readingSelection'
import { kotlinTrim } from './validation'

export type ExchangeNote = { id: string; kind: 'bookmark' | 'note' | 'highlight'; title: string; text: string; excerpt: string; anchor: ReadingAnchor; createdAt: string; range?: ReadingRange }
export type ExchangeAsset = { path: string; mimeType: string; bytes: Uint8Array }
export type ExchangeDocument = {
  id: string; bookTitle: string; chapterTitle: string; language: string; kind: 'original' | 'translation' | 'local'
  paragraphs: { paragraphId: string; text: string }[]; outline: { title: string; paragraphId: string }[]
  position?: ReadingAnchor; notes: ExchangeNote[]; organization: { folder: string; tags: string[]; favorite: boolean }
  glossary: { source: string; target: string; kind?: string; displayTerm?: string; caseSensitive: boolean; enabled: boolean }[]
  assets: { path: string; role: 'pdf' | 'image'; paragraphId?: string; alt?: string }[]
  extensions?: Record<string, unknown>
}
export type ExchangePackage = { createdAt: string; documents: ExchangeDocument[]; assets: ExchangeAsset[] }
export const exchangeLimits = { archive: 32 * 1024 * 1024, expanded: 64 * 1024 * 1024, document: 8 * 1024 * 1024, manifest: 1024 * 1024, entries: 512, documents: 100 }
const encoder = new TextEncoder(), decoder = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true })
const mimeTypes = new Set(['application/pdf', 'image/png', 'image/jpeg', 'image/gif', 'image/webp'])
const invalid = () => new Error('ZIP 교환 파일의 규격이나 무결성을 확인할 수 없습니다.')
const check: (condition: unknown) => asserts condition = condition => { if (!condition) throw invalid() }
const object = (value: unknown): Record<string, unknown> => { check(value !== null && typeof value === 'object' && !Array.isArray(value)); return value as Record<string, unknown> }
function fields(value: Record<string, unknown>, required: string[], optional: string[] = []): void {
  check(required.every(key => Object.hasOwn(value, key)) && Object.keys(value).every(key => required.includes(key) || optional.includes(key)))
}
function text(value: unknown, maximum: number, empty = false): string {
  check(typeof value === 'string' && value.length <= maximum && (empty || kotlinTrim(value).length > 0))
  // UTF-8 encoders otherwise silently replace lone surrogates, changing text and offsets.
  for (let i = 0; i < value.length; i++) { const n = value.charCodeAt(i); if (n >= 0xd800 && n <= 0xdbff) { const next = value.charCodeAt(++i); check(next >= 0xdc00 && next <= 0xdfff) } else check(n < 0xdc00 || n > 0xdfff) }
  return value
}
const array = (value: unknown, maximum: number): unknown[] => { check(Array.isArray(value) && value.length <= maximum); return value }
const optionalArray = (value: unknown, maximum: number) => array(value === undefined ? [] : value, maximum)
const timestamp = (value: unknown) => {
  const result = text(value, 64)
  const match = /^(\d{4})-(\d\d)-(\d\d)T(\d\d):(\d\d):(\d\d)(?:\.(\d{1,9}))?(Z|([+-])(\d\d):(\d\d))$/.exec(result)
  check(match)
  const year = Number(match[1]), month = Number(match[2]), day = Number(match[3]), hour = Number(match[4]), minute = Number(match[5]), second = Number(match[6])
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0)
  check(year >= 1 && month >= 1 && month <= 12 && day >= 1 && day <= [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][month - 1])
  check(hour <= 23 && minute <= 59 && second <= 59)
  if (match[8] !== 'Z') check(Number(match[10]) <= 18 && Number(match[11]) <= 59 && (Number(match[10]) !== 18 || Number(match[11]) === 0))
  return result
}
const assetPath = (value: unknown) => { const result = text(value, 71); check(/^assets\/[a-f0-9]{64}$/.test(result)); return result }
const bool = (value: unknown, fallback: boolean) => { check(value === undefined || typeof value === 'boolean'); return value === undefined ? fallback : value as boolean }

const forbiddenMetadataKeys = new Set(['authorization', 'password', 'passwd', 'token', 'apikey', 'accesstoken', 'refreshtoken', 'secret', 'clientsecret', 'credentials', 'cookie', 'setcookie', 'basicauth'])
function safeExtensions(value: unknown, depth = 0): void {
  check(depth <= 16)
  if (Array.isArray(value)) { value.forEach(v => safeExtensions(v, depth + 1)); return }
  if (value !== null && typeof value === 'object') { for (const [key, child] of Object.entries(value)) {
    text(key, 256 * 1024, true)
    check(!forbiddenMetadataKeys.has(key.toLowerCase().replace(/[^\p{L}\p{Nd}]/gu, '')))
    safeExtensions(child, depth + 1)
  } return }
  if (typeof value === 'string') text(value, 256 * 1024, true)
  else check(value === null || typeof value === 'boolean' || typeof value === 'number' && Number.isFinite(value) && (!Number.isInteger(value) || Number.isSafeInteger(value)))
}

/** Validate JSON grammar, duplicate object keys and nesting before the platform parser allocates it. */
function parseJson(bytes: Uint8Array): Record<string, unknown> {
  const input = decoder.decode(bytes); let at = 0
  const whitespace = () => { while (at < input.length && /[\t\n\r ]/.test(input[at])) at++ }
  function string(): string {
    check(input[at] === '"'); const start = at++
    while (at < input.length) { const char = input[at++]; if (char === '"') return JSON.parse(input.slice(start, at)) as string; if (char === '\\') at++ }
    throw invalid()
  }
  function value(depth: number): void {
    whitespace()
    const char = input[at]
    if (char === '{' || char === '[') {
      check(depth < 32); at++; whitespace()
      const end = char === '{' ? '}' : ']', keys = new Set<string>()
      if (input[at] === end) { at++; return }
      while (true) {
        if (char === '{') { whitespace(); const key = string(); check(!keys.has(key)); keys.add(key); whitespace(); check(input[at++] === ':') }
        value(depth + 1); whitespace()
        if (input[at] === end) { at++; return }
        check(input[at++] === ',')
      }
    }
    if (char === '"') { string(); return }
    const token = /^(?:true|false|null|-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?)/.exec(input.slice(at)); check(token)
    if (/[-\d]/.test(token[0][0])) {
      const number = Number(token[0]); check(Number.isFinite(number) && (!Number.isInteger(number) || Number.isSafeInteger(number)))
      check(number !== 0 || !/[1-9]/.test(token[0].split(/[eE]/)[0]))
    }
    at += token[0].length
  }
  value(0); whitespace(); check(at === input.length)
  return object(JSON.parse(input))
}

/** Parse to an explicit portable shape: account identifiers and arbitrary storage rows never leak. */
export function validateExchangeDocument(value: unknown): ExchangeDocument {
  const v = object(value)
  fields(v, ['id', 'bookTitle', 'chapterTitle', 'language', 'kind', 'paragraphs'], ['outline', 'position', 'notes', 'organization', 'glossary', 'assets', 'extensions'])
  const paragraphs = array(v.paragraphs, 50_000).map(value => { const p = object(value); fields(p, ['paragraphId', 'text']); return { paragraphId: text(p.paragraphId, 500), text: text(p.text, 5_000_000, true) } })
  check(paragraphs.reduce((sum, p) => sum + p.text.length, 0) <= 5_000_000)
  const indices = new Map(paragraphs.map((p, index) => [p.paragraphId, index])); check(indices.size === paragraphs.length)
  function anchor(value: unknown): ReadingAnchor {
    const a = object(value); fields(a, ['paragraphId', 'characterOffset'])
    const paragraphId = text(a.paragraphId, 500), index = indices.get(paragraphId); check(index !== undefined)
    const offset = a.characterOffset, content = paragraphs[index].text
    check(typeof offset === 'number' && Number.isSafeInteger(offset) && offset >= 0 && offset <= content.length)
    check(!(offset > 0 && offset < content.length && /[\uD800-\uDBFF]/.test(content[offset - 1]) && /[\uDC00-\uDFFF]/.test(content[offset])))
    return { paragraphId, characterOffset: offset }
  }
  const kind = text(v.kind, 20); check(['local', 'original', 'translation'].includes(kind))
  const language = text(v.language, 35); check(/^[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*$/.test(language))
  const doc: ExchangeDocument = { id: text(v.id, 500), bookTitle: text(v.bookTitle, 2000), chapterTitle: text(v.chapterTitle, 2000), language, kind: kind as ExchangeDocument['kind'], paragraphs,
    outline: optionalArray(v.outline, 50_000).map(value => { const e = object(value); fields(e, ['title', 'paragraphId']); const paragraphId = text(e.paragraphId, 500); check(indices.has(paragraphId)); return { title: text(e.title, 2000), paragraphId } }),
    notes: [], organization: { folder: '', tags: [], favorite: false }, glossary: [], assets: [] }
  if (v.position !== undefined) doc.position = anchor(v.position)
  doc.notes = optionalArray(v.notes, 2000).map(value => {
    const n = object(value); fields(n, ['id', 'kind', 'title', 'text', 'excerpt', 'anchor', 'createdAt'], ['range'])
    const kind = text(n.kind, 20); check(['bookmark', 'note', 'highlight'].includes(kind))
    const note: ExchangeNote = { id: text(n.id, 500), kind: kind as ExchangeNote['kind'], title: text(n.title, 2000, true), text: text(n.text, 10_000, true), excerpt: text(n.excerpt, 10_000, true), anchor: anchor(n.anchor), createdAt: timestamp(n.createdAt) }
    if (n.range !== undefined) {
      const r = object(n.range); fields(r, ['start', 'end']); note.range = { start: anchor(r.start), end: anchor(r.end) }
      const startIndex = indices.get(note.range.start.paragraphId)!, endIndex = indices.get(note.range.end.paragraphId)!
      check(startIndex < endIndex || startIndex === endIndex && note.range.start.characterOffset <= note.range.end.characterOffset)
    }
    return note
  })
  check(new Set(doc.notes.map(n => n.id)).size === doc.notes.length)
  if (v.organization !== undefined) {
    const organization = object(v.organization); fields(organization, ['folder', 'tags', 'favorite'])
    doc.organization = { folder: text(organization.folder, 500, true), tags: array(organization.tags, 100).map(v => text(v, 200)), favorite: bool(organization.favorite, false) }
    check(new Set(doc.organization.tags).size === doc.organization.tags.length)
  }
  doc.glossary = optionalArray(v.glossary, 2000).map(value => { const g = object(value); fields(g, ['source', 'target'], ['kind', 'displayTerm', 'caseSensitive', 'enabled']); const kind = g.kind === undefined ? undefined : text(g.kind, 80); return { source: text(g.source, 2000), target: text(g.target, 2000), ...(kind === undefined ? {} : { kind }), ...(g.displayTerm === undefined ? {} : { displayTerm: text(g.displayTerm, 2000, true) }), caseSensitive: bool(g.caseSensitive, true), enabled: bool(g.enabled, true) } })
  doc.assets = optionalArray(v.assets, 512).map(value => { const a = object(value); fields(a, ['path', 'role'], ['paragraphId', 'alt']); const role = text(a.role, 10); check(role === 'pdf' || role === 'image'); const paragraphId = a.paragraphId === undefined ? undefined : text(a.paragraphId, 500); check(paragraphId === undefined || indices.has(paragraphId)); return { path: assetPath(a.path), role, ...(paragraphId === undefined ? {} : { paragraphId }), ...(a.alt === undefined ? {} : { alt: text(a.alt, 2000, true) }) } })
  check(paragraphs.length > 0 || doc.assets.length > 0)
  if (v.extensions !== undefined) { doc.extensions = object(v.extensions); check(encoder.encode(JSON.stringify(doc.extensions)).length <= 256 * 1024); safeExtensions(doc.extensions) }
  check(encoder.encode(JSON.stringify(doc)).length <= exchangeLimits.document)
  return doc
}

function extract(bytes: Uint8Array): Map<string, Uint8Array> {
  check(bytes.length > 0 && bytes.length <= exchangeLimits.archive)
  const directory = inspectExchangeZip(bytes)
  const files = new Map<string, Uint8Array>(), seen = new Set<string>(); let expanded = 0, active = 0
  const unzip = new Unzip(file => {
    check(!seen.has(file.name) && seen.size < exchangeLimits.entries && /^(manifest\.json|documents\/[a-f0-9]{64}\.json|assets\/[a-f0-9]{64})$/.test(file.name))
    check(file.compression === 0 || file.compression === 8); seen.add(file.name); active++
    const limit = file.name === 'manifest.json' ? exchangeLimits.manifest : file.name.startsWith('documents/') ? exchangeLimits.document : exchangeLimits.archive
    check(file.originalSize === undefined || file.originalSize <= limit)
    const chunks: Uint8Array[] = []; let size = 0
    file.ondata = (error, chunk, final) => { if (error) throw invalid(); size += chunk.length; expanded += chunk.length; check(size <= limit && expanded <= exchangeLimits.expanded); chunks.push(chunk); if (final) { const result = new Uint8Array(size); let at = 0; for (const part of chunks) { result.set(part, at); at += part.length } files.set(file.name, result); active-- } }
    file.start()
  })
  unzip.register(UnzipInflate)
  // Incremental compressed input bounds decompression amplification before callbacks enforce caps.
  for (let at = 0; at < bytes.length; at += 4096) unzip.push(bytes.subarray(at, Math.min(at + 4096, bytes.length)), at + 4096 >= bytes.length)
  check(active === 0 && files.has('manifest.json'))
  check(files.size === directory.size)
  for (const [name, data] of files) { const entry = directory.get(name); check(entry && entry.bytes === data.length && entry.crc === exchangeCrc32(data)) }
  return files
}

export async function readExchange(bytes: Uint8Array): Promise<ExchangePackage> {
  try {
    const files = extract(bytes), manifest = parseJson(files.get('manifest.json')!)
    fields(manifest, ['format', 'version', 'createdAt', 'documents', 'assets'])
    check(manifest.format === 'pageturner.library')
    if (manifest.version !== 1) throw new Error('지원하지 않는 ZIP 교환 규격 버전입니다. 앱과 웹을 업데이트해 주세요.')
    const createdAt = timestamp(manifest.createdAt), used = new Set(['manifest.json'])
    async function entry(value: unknown, asset: boolean) {
      const e = object(value), path = text(e.path, 90), hash = text(e.sha256, 64)
      fields(e, asset ? ['path', 'sha256', 'bytes', 'mimeType'] : ['path', 'sha256', 'bytes'])
      check(/^[a-f0-9]{64}$/.test(hash) && path === (asset ? `assets/${hash}` : `documents/${hash}.json`) && !used.has(path)); used.add(path)
      const content = files.get(path); check(content && (!asset || content.length > 0) && Number.isSafeInteger(e.bytes) && e.bytes === content.length && await localFileHash(content) === hash)
      return { value: e, path, content }
    }
    const assets: ExchangeAsset[] = []
    for (const raw of array(manifest.assets, 511)) { const e = await entry(raw, true); const mimeType = text(e.value.mimeType, 80); check(mimeTypes.has(mimeType)); assets.push({ path: e.path, bytes: e.content, mimeType }) }
    const documents: ExchangeDocument[] = []
    for (const raw of array(manifest.documents, 100)) { const e = await entry(raw, false); documents.push(validateExchangeDocument(parseJson(e.content))) }
    check(documents.length > 0 && new Set(documents.map(d => d.id)).size === documents.length && used.size === files.size)
    const referenced = new Set<string>()
    for (const doc of documents) for (const ref of doc.assets) { const asset = assets.find(a => a.path === ref.path); check(asset && (ref.role === 'pdf' ? asset.mimeType === 'application/pdf' : asset.mimeType.startsWith('image/'))); referenced.add(ref.path) }
    check(assets.every(a => referenced.has(a.path)))
    return { createdAt, documents, assets }
  } catch (error) { if (error instanceof Error && error.message.includes('버전')) throw error; throw invalid() }
}

export async function writeExchange(input: ExchangePackage): Promise<Uint8Array> {
  check(input.documents.length > 0 && input.documents.length <= 100)
  check(input.documents.length + input.assets.length + 1 <= exchangeLimits.entries && new Set(input.documents.map(d => d.id)).size === input.documents.length)
  const files: Record<string, Uint8Array> = Object.create(null), documents = [], assets = [], references = new Set(input.documents.flatMap(d => d.assets.map(a => a.path)))
  for (const doc of input.documents) { const bytes = encoder.encode(JSON.stringify(validateExchangeDocument(doc))), sha256 = await localFileHash(bytes), path = `documents/${sha256}.json`; check(!files[path]); files[path] = bytes; documents.push({ path, sha256, bytes: bytes.length }) }
  for (const asset of input.assets) { check(references.has(asset.path) && asset.bytes.length > 0 && asset.bytes.length <= exchangeLimits.archive && mimeTypes.has(asset.mimeType)); const sha256 = await localFileHash(asset.bytes), path = `assets/${sha256}`; check(asset.path === path && !files[path]); files[path] = asset.bytes; assets.push({ path, sha256, bytes: asset.bytes.length, mimeType: asset.mimeType }) }
  files['manifest.json'] = encoder.encode(JSON.stringify({ format: 'pageturner.library', version: 1, createdAt: timestamp(input.createdAt), documents, assets }))
  check(Object.values(files).reduce((sum, value) => sum + value.length, 0) <= exchangeLimits.expanded)
  const result = zipSync(files, { level: 6 }); check(result.length <= exchangeLimits.archive)
  await readExchange(result)
  return result
}
