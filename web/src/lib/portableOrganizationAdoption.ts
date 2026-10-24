import { LibraryOrganizationError, sameLibraryOrganizationScope, validateLibraryOrganizationView, type LibraryOrganization, type LibraryOrganizationScope, type LibraryOrganizationView } from './libraryOrganizationApi'
import type { LibraryOrganizationAdoptionComparison } from './libraryOrganizationSync'
import type { LibraryOrganizationRecord } from './libraryOrganizationStore'
import { inspectPortableIdentity, sameLibraryIdentity } from './libraryIdentity'
import type { LibraryIdentityClient } from './libraryIdentityApi'
import type { createExchangeLibrary } from './exchangeLibrary'
import type { createPortableBindings, PortableBindingCapture } from './portableBinding'
import { localFileHash } from './localDocuments'
import { readLibraryOrganizationSnapshotFromDocument, type PortableLibraryOrganizationSnapshot } from './portableLibraryOrganization'

/** Read-only comparison; only the separate explicit adopt command may create an account intent. */
export interface OrganizationAdoptionSession {
  assertCurrent(): void
  read(scope: LibraryOrganizationScope, signal?: AbortSignal): Promise<LibraryOrganizationAdoptionComparison>
  adopt(scope: LibraryOrganizationScope, value: LibraryOrganization, comparison: LibraryOrganizationAdoptionComparison,
    checkBeforeWrite: () => Promise<void>, guard: () => void, signal?: AbortSignal): Promise<void>
  close(): void
}

export const organizationAdoptionErrors = {
  stale: '비교한 뒤 계정·문서·연결·분류가 변경되었습니다. 다시 비교해 주세요.',
  pending: '대기 중인 분류 변경이나 충돌을 먼저 해결한 뒤 다시 비교해 주세요.',
  binding: '먼저 이 ZIP 책의 서버 원본을 확인하고 계정 기록에 연결해 주세요.',
  scope: 'ZIP 분류의 전체 원본 정보가 연결된 문서와 일치하지 않습니다.',
  absent: '미등록 분류 스냅샷은 정보만 표시하며 계정 분류를 변경하지 않습니다.',
  snapshot: '지원하는 계정 분류 스냅샷이 없습니다. 기존 ZIP은 그대로 보존됩니다.',
  offline: '계정 분류를 비교하려면 서버에 다시 로그인해 주세요.',
  source: '선택한 기기 문서가 변경되거나 삭제되었습니다. 다시 선택해 주세요.',
} as const

export type PortableOrganizationAdoptionReview = {
  scope: LibraryOrganizationScope; snapshot: PortableLibraryOrganizationSnapshot
  server: LibraryOrganizationView; device: LibraryOrganizationRecord | null
  /** Single-use explicit confirmation; success means durable local intent, not server acknowledgement. */
  confirm(signal?: AbortSignal): Promise<void>
  close(): void
}
export function organizationAdoptionErrorMessage(error: unknown): string {
  if (error instanceof LibraryOrganizationError) {
    if (['choice-stale', 'invalid-response', 'aborted'].includes(error.code)) return organizationAdoptionErrors.stale
    if (['authentication', 'forbidden', 'network'].includes(error.code)) return organizationAdoptionErrors.offline
  }
  return error instanceof Error ? error.message : organizationAdoptionErrors.stale
}

/** Passive ZIP comparison. Only the returned single-use confirm command may authorize a new account intent. */
export function createPortableOrganizationAdoption(options: {
  session: OrganizationAdoptionSession
  library: Pick<ReturnType<typeof createExchangeLibrary>, 'get' | 'exportDocument'>
  bindings: Pick<ReturnType<typeof createPortableBindings>, 'capture'>
  identityClient: LibraryIdentityClient | null
  current: () => boolean
}) {
  let prepared = false
  return { async prepare(copyId: string, signal?: AbortSignal): Promise<PortableOrganizationAdoptionReview> {
    let closed = false, binding: PortableBindingCapture | undefined
    const close = () => { closed = true; options.session.close() }
    const assert = (extraSignal?: AbortSignal) => {
      if (closed || !options.current() || signal?.aborted || extraSignal?.aborted) throw new Error(organizationAdoptionErrors.stale)
      options.session.assertCurrent(); binding?.assertCurrent()
    }
    try {
      assert()
      if (prepared) throw new Error(organizationAdoptionErrors.stale)
      prepared = true
      if (!options.identityClient) throw new Error(organizationAdoptionErrors.offline)
      const client = options.identityClient
      // Every load re-reads canonical storage and actual payloads. Inventory objects are never authority.
      const load = async () => {
        assert()
        const book = await options.library.get(copyId, signal); assert()
        if (!book) throw new Error(organizationAdoptionErrors.source)
        const captured = structuredClone(book)
        const value = structuredClone(await options.library.exportDocument(captured)); assert()
        const latest = await options.library.get(copyId, signal); assert()
        if (!latest || latest.checksum !== captured.checksum || latest.importedAt !== captured.importedAt
          || JSON.stringify(latest.integratedNoteIds) !== JSON.stringify(captured.integratedNoteIds)) throw new Error(organizationAdoptionErrors.source)
        if (value.documents.length !== 1) throw new Error(organizationAdoptionErrors.source)
        const assets = await Promise.all(value.assets.map(async asset => [asset.path, asset.mimeType, await localFileHash(asset.bytes)]))
        const fingerprint = await localFileHash(new TextEncoder().encode(JSON.stringify([
          captured.id, captured.checksum, captured.importedAt, captured.integratedNoteIds, value.documents, assets,
        ])))
        assert()
        return { book: captured, value, fingerprint }
      }
      const source = await load(), document = source.value.documents[0]
      let snapshot: PortableLibraryOrganizationSnapshot | undefined
      try { snapshot = await readLibraryOrganizationSnapshotFromDocument(document) }
      catch { throw new Error(organizationAdoptionErrors.snapshot) }
      assert()
      if (!snapshot) throw new Error(organizationAdoptionErrors.snapshot)
      const proof = await inspectPortableIdentity(document); assert()
      if (proof.status !== 'ready' || !sameLibraryIdentity(proof.identity, snapshot.identity)) throw new Error(organizationAdoptionErrors.scope)
      const captured = await options.bindings.capture(source.book, client, signal); assert()
      if (!captured) throw new Error(organizationAdoptionErrors.binding)
      if (captured.kind !== proof.identity.kind || !sameLibraryIdentity(captured.identity, proof.identity)) throw new Error(organizationAdoptionErrors.scope)
      binding = captured
      const scope = { kind: binding.kind, recordId: binding.recordId }
      const checkSource = async () => {
        assert()
        if ((await load()).fingerprint !== source.fingerprint) throw new Error(organizationAdoptionErrors.source)
        assert()
      }
      await checkSource()
      const comparison = structuredClone(await options.session.read(scope, signal)); assert()
      comparison.remote = validateLibraryOrganizationView(comparison.remote)
      if (!sameLibraryOrganizationScope(scope, comparison.remote)) throw new Error(organizationAdoptionErrors.scope)
      await checkSource()
      const selected = structuredClone(snapshot), baseline = structuredClone(comparison)
      let used = false
      return { scope: { ...scope }, snapshot: structuredClone(selected), server: structuredClone(baseline.remote), device: structuredClone(baseline.record), close,
        confirm: async extraSignal => {
          if (used) throw new Error(organizationAdoptionErrors.stale)
          used = true
          try {
            assert(extraSignal)
            if (selected.presence === 'absent') throw new Error(organizationAdoptionErrors.absent)
            const guard = () => assert(extraSignal)
            const checkBeforeWrite = async () => {
              guard(); await checkSource(); guard()
              const verified = await options.bindings.capture(source.book, client, extraSignal ?? signal); guard()
              if (!verified || verified.recordId !== scope.recordId || verified.kind !== scope.kind || !sameLibraryIdentity(verified.identity, selected.identity)) throw new Error(organizationAdoptionErrors.scope)
              await checkSource(); guard()
            }
            await checkBeforeWrite()
            await options.session.adopt(scope, structuredClone(selected.organization), structuredClone(baseline), checkBeforeWrite, guard, extraSignal ?? signal)
            // No stale assertion after durable commit: logout cannot turn an accepted intent into a rollback.
          } finally { close() }
        },
      }
    } catch (error) { close(); throw error }
  } }
}
