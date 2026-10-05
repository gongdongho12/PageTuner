import { parseExchangeJson, validateExchangeDocument, type ExchangeDocument } from './libraryExchange'
import { inspectPortableIdentity, sameLibraryIdentity, validateLibraryIdentity, type LibraryDocumentIdentity } from './libraryIdentity'
import { validateLibraryOrganization, type LibraryOrganization } from './libraryOrganizationApi'

export type PortableLibraryOrganizationSnapshot = {
  version: 1
  identity: LibraryDocumentIdentity
} & ({ presence: 'absent'; organization: null } | { presence: 'present'; organization: LibraryOrganization })

const extensionKey = 'libraryOrganizationSnapshot'
const maximumBytes = 256 * 1024
const invalid = (): never => { throw new Error('Invalid portable library organization snapshot') }

/** Passive values only: neither account/record identifiers nor synchronization state are portable. */
export function validatePortableLibraryOrganizationSnapshot(value: unknown): PortableLibraryOrganizationSnapshot {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return invalid()
  const raw = value as Record<string, unknown>, fields = ['version', 'identity', 'presence', 'organization']
  if (Object.keys(raw).length !== fields.length || fields.some(key => !Object.hasOwn(raw, key)) || raw.version !== 1) return invalid()
  const identity = validateLibraryIdentity(raw.identity)
  if (raw.presence === 'absent' && raw.organization === null) return { version: 1, identity, presence: 'absent', organization: null }
  if (raw.presence === 'present') return { version: 1, identity, presence: 'present', organization: validateLibraryOrganization(raw.organization) }
  return invalid()
}

/** Structural decoding only; document helpers additionally prove the enclosing document's contents. */
export function parseLibraryOrganizationSnapshot(raw: string): PortableLibraryOrganizationSnapshot {
  if (typeof raw !== 'string' || raw.length > maximumBytes) return invalid()
  // TextEncoder otherwise replaces unpaired UTF-16 with U+FFFD before JSON validation can see it.
  for (let i = 0; i < raw.length; i++) {
    const code = raw.charCodeAt(i)
    if (code >= 0xd800 && code <= 0xdbff) {
      const next = raw.charCodeAt(++i)
      if (!(next >= 0xdc00 && next <= 0xdfff)) return invalid()
    } else if (code >= 0xdc00 && code <= 0xdfff) return invalid()
  }
  const bytes = new TextEncoder().encode(raw)
  if (bytes.byteLength > maximumBytes) return invalid()
  return validatePortableLibraryOrganizationSnapshot(parseExchangeJson(bytes))
}

async function proveDocument(document: ExchangeDocument, snapshot: PortableLibraryOrganizationSnapshot): Promise<void> {
  if (Object.hasOwn(document.extensions ?? {}, 'documentIdentity')) {
    const existing = validateLibraryIdentity(document.extensions!.documentIdentity)
    if (!sameLibraryIdentity(existing, snapshot.identity)) return invalid()
  }
  // A portable ID, title or server UUID hint never substitutes for the original/translated content proof.
  const proof = await inspectPortableIdentity({ ...document, extensions: { ...document.extensions, documentIdentity: snapshot.identity } })
  if (proof.status !== 'ready' || !sameLibraryIdentity(proof.identity, snapshot.identity)) return invalid()
}

/** Missing is distinct from an absent account value. Reading never adopts or sends the snapshot. */
export async function readLibraryOrganizationSnapshotFromDocument(document: ExchangeDocument): Promise<PortableLibraryOrganizationSnapshot | undefined> {
  const checked = validateExchangeDocument(document)
  if (!checked.extensions || !Object.hasOwn(checked.extensions, extensionKey)) return undefined
  // Hold one detached document across hash awaits; caller mutations cannot change the checked proof.
  const captured = structuredClone(checked)
  const snapshot = validatePortableLibraryOrganizationSnapshot(captured.extensions![extensionKey])
  await proveDocument(captured, snapshot)
  return snapshot
}

/** Preserve legacy organization and every safe sibling; reject unsupported existing values and total limits. */
export async function withLibraryOrganizationSnapshot(document: ExchangeDocument, value: unknown): Promise<ExchangeDocument> {
  const captured = structuredClone(validateExchangeDocument(document))
  const snapshot = validatePortableLibraryOrganizationSnapshot(value)
  const previous = captured.extensions && Object.hasOwn(captured.extensions, extensionKey)
    ? validatePortableLibraryOrganizationSnapshot(captured.extensions[extensionKey]) : undefined
  const result = validateExchangeDocument({ ...captured, extensions: { ...captured.extensions, [extensionKey]: snapshot } })
  if (previous) await proveDocument(captured, previous)
  await proveDocument(captured, snapshot)
  return result
}
