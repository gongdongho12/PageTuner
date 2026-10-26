import { parseExchangeJson, validateExchangeDocument, type ExchangeDocument } from './libraryExchange'
import { inspectPortableIdentity, sameLibraryIdentity, validateLibraryIdentity, type LibraryDocumentIdentity } from './libraryIdentity'

export type PortableReadingProgressAnchor = { paragraphId: string; characterOffset: number }
export type PortableReadingProgressSnapshot = {
  version: 1
  identity: LibraryDocumentIdentity
} & ({ presence: 'absent'; anchor: null } | { presence: 'present'; anchor: PortableReadingProgressAnchor })

const extensionKey = 'readingProgressSnapshot'
const maximumBytes = 256 * 1024
const invalid = (): never => { throw new Error('Invalid portable reading progress snapshot') }

function unicode(value: string): void {
  for (let i = 0; i < value.length; i++) {
    const code = value.charCodeAt(i)
    if (code >= 0xd800 && code <= 0xdbff) {
      const next = value.charCodeAt(++i)
      if (!(next >= 0xdc00 && next <= 0xdfff)) invalid()
    } else if (code >= 0xdc00 && code <= 0xdfff) invalid()
  }
}

function object(value: unknown, fields: string[]): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return invalid()
  const raw = value as Record<string, unknown>
  if (Object.keys(raw).length !== fields.length || fields.some(key => !Object.hasOwn(raw, key))) return invalid()
  return raw
}

/** Structural validation only. No account, server version, binding or mutation authority is transferred. */
export function validatePortableReadingProgressSnapshot(value: unknown): PortableReadingProgressSnapshot {
  const raw = object(value, ['version', 'identity', 'presence', 'anchor'])
  if (raw.version !== 1) return invalid()
  const identity = validateLibraryIdentity(raw.identity)
  if (raw.presence === 'absent' && raw.anchor === null) return { version: 1, identity, presence: 'absent', anchor: null }
  if (raw.presence !== 'present') return invalid()
  const anchor = object(raw.anchor, ['paragraphId', 'characterOffset'])
  if (typeof anchor.paragraphId !== 'string' || !anchor.paragraphId.trim() || anchor.paragraphId.length > 200 ||
    /[\u0000-\u001f\u007f-\u009f]/.test(anchor.paragraphId) || typeof anchor.characterOffset !== 'number' ||
    !Number.isInteger(anchor.characterOffset) || anchor.characterOffset < 0 || anchor.characterOffset > 2_147_483_647) return invalid()
  unicode(anchor.paragraphId)
  return { version: 1, identity, presence: 'present', anchor: { paragraphId: anchor.paragraphId, characterOffset: anchor.characterOffset } }
}

/** Strict raw JSON grammar and duplicate keys must be checked before projecting a snapshot object. */
export function parseReadingProgressSnapshot(raw: string): PortableReadingProgressSnapshot {
  if (typeof raw !== 'string' || raw.length > maximumBytes) return invalid()
  unicode(raw) // Reject malformed UTF-16 before TextEncoder can replace it.
  const bytes = new TextEncoder().encode(raw)
  if (bytes.byteLength > maximumBytes) return invalid()
  return validatePortableReadingProgressSnapshot(parseExchangeJson(bytes))
}

async function proveDocument(document: ExchangeDocument, snapshot: PortableReadingProgressSnapshot): Promise<void> {
  if (Object.hasOwn(document.extensions ?? {}, 'documentIdentity')) {
    const existing = validateLibraryIdentity(document.extensions!.documentIdentity)
    if (!sameLibraryIdentity(existing, snapshot.identity)) return invalid()
  }
  if (snapshot.presence === 'present') {
    const paragraph = document.paragraphs.find(value => value.paragraphId === snapshot.anchor.paragraphId)
    const offset = snapshot.anchor.characterOffset
    if (!paragraph || offset > paragraph.text.length || offset > 0 && offset < paragraph.text.length &&
      /[\uD800-\uDBFF]/.test(paragraph.text[offset - 1]) && /[\uDC00-\uDFFF]/.test(paragraph.text[offset])) return invalid()
  }
  const proof = await inspectPortableIdentity({ ...document, extensions: { ...document.extensions, documentIdentity: snapshot.identity } })
  if (proof.status !== 'ready' || !sameLibraryIdentity(proof.identity, snapshot.identity)) return invalid()
}

/** Missing is not an observed absent account value. Reading never starts synchronization or moves a reader. */
export async function readReadingProgressSnapshotFromDocument(document: ExchangeDocument): Promise<PortableReadingProgressSnapshot | undefined> {
  const checked = validateExchangeDocument(document)
  if (!checked.extensions || !Object.hasOwn(checked.extensions, extensionKey)) return undefined
  const captured = structuredClone(checked)
  const snapshot = validatePortableReadingProgressSnapshot(captured.extensions![extensionKey])
  await proveDocument(captured, snapshot)
  return snapshot
}

/** Preserve the legacy local position and all siblings; refuse an unsupported or stale existing snapshot. */
export async function withReadingProgressSnapshot(document: ExchangeDocument, value: unknown): Promise<ExchangeDocument> {
  const captured = structuredClone(validateExchangeDocument(document))
  const snapshot = validatePortableReadingProgressSnapshot(value)
  const previous = captured.extensions && Object.hasOwn(captured.extensions, extensionKey)
    ? validatePortableReadingProgressSnapshot(captured.extensions[extensionKey]) : undefined
  // Validate combined extensions and document limits before doing asynchronous proof work.
  const result = validateExchangeDocument({ ...captured, extensions: { ...captured.extensions, [extensionKey]: snapshot } })
  if (previous) await proveDocument(captured, previous)
  await proveDocument(captured, snapshot)
  return result
}
