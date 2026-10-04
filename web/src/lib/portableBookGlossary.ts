import { bookGlossaryKey, validateBookGlossary, validateBookGlossaryScope, type BookGlossaryEntry, type BookGlossaryScope } from './bookGlossaryApi'
import { validateExchangeDocument, type ExchangeDocument } from './libraryExchange'

export type PortableBookGlossarySnapshot = BookGlossaryScope & (
  { presence: 'present'; entries: BookGlossaryEntry[] } |
  { presence: 'absent' | 'deleted'; entries: null }
)
export type PortableBookGlossarySnapshots = { version: 1; snapshots: PortableBookGlossarySnapshot[] }
const extensionKey = 'bookGlossarySnapshots'
const invalid = (): never => { throw new Error('Invalid portable book glossary snapshots') }
function object(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : invalid()
}
function fields(value: Record<string, unknown>, expected: string[]): void {
  if (Object.keys(value).length !== expected.length || expected.some(key => !Object.hasOwn(value, key))) invalid()
}

/** Passive values only: no account identity, synchronization version, intent or conflict state. */
export function validatePortableBookGlossarySnapshots(value: unknown): PortableBookGlossarySnapshots {
  const root = object(value)
  fields(root, ['version', 'snapshots'])
  if (root.version !== 1 || !Array.isArray(root.snapshots) || root.snapshots.length > 100) return invalid()
  const scopes = new Set<string>()
  const snapshots = root.snapshots.map((input): PortableBookGlossarySnapshot => {
    const raw = object(input)
    fields(raw, ['providerId', 'bookId', 'targetLanguage', 'presence', 'entries'])
    const scope = validateBookGlossaryScope({ providerId: raw.providerId, bookId: raw.bookId, targetLanguage: raw.targetLanguage })
    const key = bookGlossaryKey(scope)
    if (scopes.has(key)) return invalid()
    scopes.add(key)
    const entries = validateBookGlossary(raw.entries)
    if (raw.presence === 'present' && entries !== null) return { ...scope, presence: 'present', entries }
    if ((raw.presence === 'absent' || raw.presence === 'deleted') && entries === null) return { ...scope, presence: raw.presence, entries: null }
    return invalid()
  })
  return { version: 1, snapshots }
}

/** Missing means no snapshot was supplied; never fall back to a legacy glossary or adopt it. */
export function readBookGlossarySnapshotsFromDocument(document: ExchangeDocument): PortableBookGlossarySnapshots | undefined {
  const checked = validateExchangeDocument(document)
  if (!checked.extensions || !Object.hasOwn(checked.extensions, extensionKey)) return undefined
  return validatePortableBookGlossarySnapshots(checked.extensions[extensionKey])
}

/** Preserve sibling metadata, and explicitly reject the combined document/extension size limits. */
export function withBookGlossarySnapshots(document: ExchangeDocument, value: unknown): ExchangeDocument {
  const checked = validateExchangeDocument(document)
  const snapshots = validatePortableBookGlossarySnapshots(value)
  const result = validateExchangeDocument({ ...checked, extensions: { ...checked.extensions, [extensionKey]: snapshots } })
  // Extension siblings are safe JSON data, but the input and returned copy must not share mutable state.
  return { ...result, extensions: structuredClone(result.extensions) }
}
