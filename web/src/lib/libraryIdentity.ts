import type { ExchangeDocument } from './libraryExchange'
import type { StoredChapter } from './workflowTypes'
import type { TranslationResponse } from './types'
import { kotlinTrim, sha256, validRecordId } from './validation'
import type { components } from '../generated/libraryIdentity'

// Kept independent of portable document IDs: titles, archive hashes and UUID hints are not identity.
export type LibraryDocumentIdentity = components['schemas']['LibraryDocumentIdentity']
export type PortableIdentityCheck = { status: 'ready'; identity: LibraryDocumentIdentity; recordIdHint?: string } | { status: 'missing' | 'invalid' | 'assets' }
const baseFields = ['version', 'kind', 'contentProviderId', 'bookId', 'chapterId', 'sourceRevision', 'sourceLanguage', 'paragraphHash'] as const
const translatedFields = ['targetLanguage', 'translationProviderId', 'modelId', 'promptRevision', 'glossaryRevision', 'artifactId', 'revision', 'payloadHash'] as const
const invalid = (): never => { throw new Error('Invalid portable document identity') }
const hex = (value: unknown): string => typeof value === 'string' && /^[0-9a-f]{64}$/.test(value) ? value : invalid()
function unicode(value: string) {
  for (let i = 0; i < value.length; i++) {
    const code = value.charCodeAt(i)
    if (code >= 0xd800 && code <= 0xdbff) { const next = value.charCodeAt(++i); if (!(next >= 0xdc00 && next <= 0xdfff)) invalid() }
    else if (code >= 0xdc00 && code <= 0xdfff) invalid()
  }
}
function field(value: unknown, maximum: number, blank = false): string {
  if (typeof value !== 'string' || value.length > maximum || !blank && !kotlinTrim(value) || /[\u0000-\u001f\u007f-\u009f]/.test(value)) return invalid()
  unicode(value); return value
}
function language(value: unknown): string { const result = field(value, 35); return /^[A-Za-z][A-Za-z0-9-]{0,34}$/.test(result) ? result : invalid() }
export function validateLibraryIdentity(value: unknown): LibraryDocumentIdentity {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return invalid()
  const v = value as Record<string, unknown>, fields = v.kind === 'TRANSLATION' ? [...baseFields, ...translatedFields] : [...baseFields]
  if (v.version !== 1 || !['ORIGINAL', 'TRANSLATION'].includes(v.kind as string) || Object.keys(v).length !== fields.length || fields.some(key => !Object.hasOwn(v, key))) return invalid()
  const base = { version: 1 as const, contentProviderId: field(v.contentProviderId, 2000), bookId: field(v.bookId, 2000), chapterId: field(v.chapterId, 2000), sourceRevision: field(v.sourceRevision, 64), sourceLanguage: language(v.sourceLanguage), paragraphHash: hex(v.paragraphHash) }
  if (v.kind === 'ORIGINAL') return { ...base, kind: 'ORIGINAL', sourceRevision: hex(base.sourceRevision) }
  return { ...base, kind: 'TRANSLATION', targetLanguage: language(v.targetLanguage), translationProviderId: field(v.translationProviderId, 2000), modelId: field(v.modelId, 2000, true), promptRevision: field(v.promptRevision, 2000, true), glossaryRevision: field(v.glossaryRevision, 2000, true), artifactId: hex(v.artifactId), revision: hex(v.revision), payloadHash: hex(v.payloadHash) }
}
export function sameLibraryIdentity(a: LibraryDocumentIdentity, b: LibraryDocumentIdentity): boolean { return JSON.stringify(validateLibraryIdentity(a)) === JSON.stringify(validateLibraryIdentity(b)) }

/** UTF-8 byte-length frames eliminate delimiter collisions and retain order and exact Unicode. */
export async function libraryParagraphHash(paragraphs: { paragraphId: string; text: string }[]): Promise<string> {
  if (!Array.isArray(paragraphs) || paragraphs.length === 0 || paragraphs.length > 50_000) return invalid()
  const ids = new Set<string>(), encoder = new TextEncoder(); let total = 0
  const frame = (value: string) => `${encoder.encode(value).byteLength}:${value}`
  const frames = [frame('pageturner.document-paragraphs.v1'), frame(String(paragraphs.length))]
  for (const p of paragraphs) {
    if (!p || typeof p.paragraphId !== 'string' || p.paragraphId.length > 500 || !kotlinTrim(p.paragraphId) || ids.has(p.paragraphId) || typeof p.text !== 'string') return invalid()
    unicode(p.paragraphId); unicode(p.text); total += p.text.length; if (total > 5_000_000) return invalid()
    ids.add(p.paragraphId); frames.push(frame(p.paragraphId), frame(p.text))
  }
  return sha256(frames.join(''))
}
export async function originalLibraryIdentity(chapter: StoredChapter): Promise<LibraryDocumentIdentity> {
  if (chapter.paragraphs.some((paragraph, index) => paragraph.ordinal !== index)) return invalid()
  return validateLibraryIdentity({ version: 1, kind: 'ORIGINAL', contentProviderId: chapter.providerId, bookId: chapter.bookId, chapterId: chapter.chapterId, sourceRevision: chapter.sourceRevision, sourceLanguage: chapter.sourceLanguage, paragraphHash: await libraryParagraphHash(chapter.paragraphs) })
}
export async function translationLibraryIdentity(translation: TranslationResponse): Promise<LibraryDocumentIdentity> {
  return validateLibraryIdentity({ version: 1, kind: 'TRANSLATION', contentProviderId: translation.contentProviderId, bookId: translation.bookId, chapterId: translation.chapterId, sourceRevision: translation.sourceRevision, sourceLanguage: translation.sourceLanguage, paragraphHash: await libraryParagraphHash(translation.paragraphs), ...Object.fromEntries(translatedFields.map(key => [key, translation[key]])) })
}
/** Read-only proof; never upgrades a local document into an account sync binding. */
export async function inspectPortableIdentity(document: ExchangeDocument): Promise<PortableIdentityCheck> {
  if (document.assets.length) return { status: 'assets' }
  if (!Object.hasOwn(document.extensions ?? {}, 'documentIdentity')) return { status: 'missing' }
  try {
    const identity = validateLibraryIdentity(document.extensions!.documentIdentity)
    if (document.kind !== identity.kind.toLowerCase() || document.language !== (identity.kind === 'ORIGINAL' ? identity.sourceLanguage : identity.targetLanguage) || identity.paragraphHash !== await libraryParagraphHash(document.paragraphs)) return { status: 'invalid' }
    if (identity.kind === 'ORIGINAL') {
      const source = await Promise.all(document.paragraphs.map(async (p, index) => `${p.paragraphId}:${index}:${await sha256(p.text)}`))
      if (identity.sourceRevision !== await sha256(source.join('\n'))) return { status: 'invalid' }
    } else {
      const artifact = await sha256([[identity.contentProviderId, identity.bookId, identity.chapterId].map(kotlinTrim).join(':'), identity.sourceRevision, identity.sourceLanguage, identity.targetLanguage, identity.translationProviderId, identity.modelId, identity.promptRevision, identity.glossaryRevision].join('|'))
      const payload = await sha256(document.paragraphs.map(p => `${p.paragraphId}:${p.text}`).join('\n'))
      if (identity.artifactId !== artifact || identity.payloadHash !== payload || identity.revision !== await sha256(`${artifact}|${payload}`)) return { status: 'invalid' }
    }
    const server = document.extensions?.server
    const legacyHint = server && typeof server === 'object' && !Array.isArray(server) ? (server as Record<string, unknown>).recordId : undefined
    const hint = Object.hasOwn(document.extensions ?? {}, 'serverRecordId') ? document.extensions?.serverRecordId : legacyHint
    return { status: 'ready', identity, ...(validRecordId(hint) ? { recordIdHint: hint.toLowerCase() } : {}) }
  } catch { return { status: 'invalid' } }
}
