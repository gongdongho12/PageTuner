import { validateBookGlossary, validateBookGlossaryScope, validateBookGlossaryView, sameBookGlossaryScope, type BookGlossary, type BookGlossaryScope, type BookGlossaryView } from './bookGlossaryApi'
import { validateLibraryIdentity, type LibraryDocumentIdentity } from './libraryIdentity'

export const accountGlossarySnapshotLimit = 16
export type AccountGlossarySnapshot = BookGlossaryScope & { state: 'PRESENT' | 'ABSENT' | 'DELETED'; entries: BookGlossary }
export type AccountGlossarySnapshots = { version: 1; snapshots: AccountGlossarySnapshot[] }
const invalid = (): never => { throw new Error('계정 용어집 ZIP 정보를 확인할 수 없습니다.') }
const object = (v: unknown): Record<string, unknown> => v && typeof v === 'object' && !Array.isArray(v) ? v as Record<string, unknown> : invalid()
function keys(v: Record<string, unknown>, names: string[]) { if (Object.keys(v).length !== names.length || names.some(name => !Object.hasOwn(v, name))) invalid() }
export function validateAccountGlossarySnapshot(value: unknown, identity: LibraryDocumentIdentity): AccountGlossarySnapshot {
  identity = validateLibraryIdentity(identity)
  const v = object(value); keys(v, ['providerId', 'bookId', 'targetLanguage', 'state', 'entries'])
  const scope = validateBookGlossaryScope({ providerId: v.providerId, bookId: v.bookId, targetLanguage: v.targetLanguage })
  if (scope.providerId !== identity.contentProviderId || scope.bookId !== identity.bookId || identity.kind === 'TRANSLATION' && scope.targetLanguage !== identity.targetLanguage) return invalid()
  if (!['PRESENT', 'ABSENT', 'DELETED'].includes(String(v.state))) return invalid()
  const entries = validateBookGlossary(v.entries)
  if (v.state === 'PRESENT' ? entries === null : entries !== null) return invalid()
  return { ...scope, state: v.state as AccountGlossarySnapshot['state'], entries }
}
export function validateAccountGlossarySnapshots(value: unknown, identity: LibraryDocumentIdentity): AccountGlossarySnapshots {
  const v = object(value); keys(v, ['version', 'snapshots'])
  if (v.version !== 1 || !Array.isArray(v.snapshots) || v.snapshots.length > accountGlossarySnapshotLimit) return invalid()
  const snapshots = v.snapshots.map(s => validateAccountGlossarySnapshot(s, identity)), scopes = snapshots.map(s => JSON.stringify([s.providerId, s.bookId, s.targetLanguage]))
  if (new Set(scopes).size !== scopes.length) return invalid()
  return { version: 1, snapshots }
}
/** Project an actual authenticated read-query response; synchronization versions and outboxes never enter the snapshot. */
export function accountGlossarySnapshot(view: BookGlossaryView, identity: LibraryDocumentIdentity, requested: BookGlossaryScope): AccountGlossarySnapshot {
  const checked = validateBookGlossaryView(view)
  if (!sameBookGlossaryScope(checked, requested)) return invalid()
  return validateAccountGlossarySnapshot({ ...requested, state: checked.version === 0 ? 'ABSENT' : checked.entries === null ? 'DELETED' : 'PRESENT', entries: checked.entries }, identity)
}
export function mergeAccountGlossarySnapshot(extensions: Record<string, unknown> | undefined, snapshot: AccountGlossarySnapshot, identity: LibraryDocumentIdentity): Record<string, unknown> {
  const next = validateAccountGlossarySnapshot(snapshot, identity), previous = extensions && Object.hasOwn(extensions, 'accountGlossarySnapshots') ? validateAccountGlossarySnapshots(extensions.accountGlossarySnapshots, identity) : { version: 1 as const, snapshots: [] }
  const index = previous.snapshots.findIndex(s => sameBookGlossaryScope(s, next))
  const values = previous.snapshots.map((s, i) => i === index ? next : s); if (index < 0) values.push(next)
  const envelope = validateAccountGlossarySnapshots({ version: 1, snapshots: values }, identity), result = { ...extensions, accountGlossarySnapshots: envelope }
  if (new TextEncoder().encode(JSON.stringify(result)).byteLength > 256 * 1024) return invalid()
  return result
}
