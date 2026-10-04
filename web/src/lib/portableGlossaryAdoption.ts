import { BookGlossaryError, sameBookGlossaryScope, sameBookGlossaryView, validateBookGlossaryPayload, validateBookGlossaryScope, validateBookGlossaryView, type BookGlossaryClient, type BookGlossaryScope, type BookGlossaryView } from './bookGlossaryApi'
import { createBookGlossaryStore, type BookGlossaryRecord, type BookGlossaryStoreOptions } from './bookGlossaryStore'
import type { SavedExchange } from './exchangeLibrary'
import { inspectPortableIdentity } from './libraryIdentity'
import type { LibraryIdentityClient } from './libraryIdentityApi'
import type { createPortableBindings } from './portableBinding'
import { readBookGlossarySnapshotsFromDocument, type PortableBookGlossarySnapshot } from './portableBookGlossary'

export const glossaryAdoptionErrors = {
  stale: '비교한 뒤 계정·연결·용어집이 변경되었습니다. 다시 조회하고 비교해 주세요.',
  pending: '대기 중인 변경이나 충돌이 있습니다. 계정 용어집에서 해결한 뒤 다시 비교해 주세요.',
  binding: '먼저 이 ZIP 책의 서버 원본을 확인하고 계정 기록에 연결해 주세요.',
  scope: 'ZIP 용어집의 원본 책·언어가 연결된 문서와 일치하지 않습니다.',
  absent: '미등록 스냅샷은 정보만 표시하며 계정 용어집을 변경하지 않습니다.',
  snapshot: '지원하는 계정 용어집 스냅샷이 없습니다. 기존 ZIP은 그대로 보존됩니다.',
  offline: '계정 용어집을 비교하려면 서버에 다시 로그인해 주세요.',
} as const
export type PortableGlossaryAdoptionReview = {
  scope: BookGlossaryScope; snapshot: PortableBookGlossarySnapshot; server: BookGlossaryView; device: BookGlossaryRecord | null
  /** Single-use confirmation. A failed confirmation requires a fresh comparison. */
  confirm(signal?: AbortSignal): Promise<void>
}
const sameRecord = (left: BookGlossaryRecord | null, right: BookGlossaryRecord | null) => JSON.stringify(left) === JSON.stringify(right)
const clear = (record: BookGlossaryRecord | null) => { if (record?.pending || record?.conflict) throw new Error(glossaryAdoptionErrors.pending) }

/** Compare without opening controllers; only explicit confirmation creates an exact durable intent. */
export function createPortableGlossaryAdoption(options: {
  username: string; api: BookGlossaryClient | null; assertCurrent: () => void
  onQueued: (scope: BookGlossaryScope) => void; storeOptions?: BookGlossaryStoreOptions
}) {
  return { async prepare(book: SavedExchange, inputScope: BookGlossaryScope, client: LibraryIdentityClient | null,
    bindings: Pick<ReturnType<typeof createPortableBindings>, 'open' | 'ticket'>, signal?: AbortSignal): Promise<PortableGlossaryAdoptionReview> {
    const assert = (activeSignal = signal) => { options.assertCurrent(); if (signal?.aborted || activeSignal?.aborted) throw new Error(glossaryAdoptionErrors.stale) }
    assert()
    if (!options.api || !client) throw new Error(glossaryAdoptionErrors.offline)
    const api = options.api, scope = validateBookGlossaryScope(inputScope)
    let snapshot: PortableBookGlossarySnapshot
    try {
      const snapshots = readBookGlossarySnapshotsFromDocument(book.document)
      const selected = snapshots?.snapshots.find(value => sameBookGlossaryScope(value, scope))
      if (!selected) throw new Error()
      snapshot = structuredClone(selected)
    } catch { throw new Error(glossaryAdoptionErrors.snapshot) }
    const check = await inspectPortableIdentity(book.document); assert()
    if (check.status !== 'ready' || check.identity.contentProviderId !== scope.providerId || check.identity.bookId !== scope.bookId || check.identity.kind === 'TRANSLATION' && check.identity.targetLanguage !== scope.targetLanguage) throw new Error(glossaryAdoptionErrors.scope)
    const binding = bindings.ticket(book)
    if (!binding) throw new Error(glossaryAdoptionErrors.binding)
    const guard = (activeSignal = signal) => { assert(activeSignal); if (!binding.current()) throw new Error(glossaryAdoptionErrors.stale) }
    const linked = await bindings.open(book, client, signal); guard()
    if (!linked) throw new Error(glossaryAdoptionErrors.binding)
    const store = createBookGlossaryStore(options.username, scope, options.storeOptions)
    let device: BookGlossaryRecord | null, server: BookGlossaryView
    try {
      device = await store.snapshot(); guard(); clear(device)
      server = validateBookGlossaryView(await api.get(scope, signal)); guard()
      if (!sameBookGlossaryScope(server, scope)) throw new BookGlossaryError('invalid-response')
      if (device?.remote && (device.remote.version > server.version || device.remote.version === server.version && !sameBookGlossaryView(device.remote, server))) throw new Error(glossaryAdoptionErrors.stale)
      const after = await store.snapshot(); guard(); clear(after)
      if (!sameRecord(device, after)) throw new Error(glossaryAdoptionErrors.stale)
    } finally { store.close() }
    // Command data is private: mutating the display copy cannot change the reviewed command.
    const baseline = structuredClone(device), remote = structuredClone(server), selected = structuredClone(snapshot)
    let used = false
    return { scope: { ...scope }, snapshot: structuredClone(selected), server: structuredClone(remote), device: structuredClone(baseline),
      confirm: async confirmSignal => {
        if (used) throw new Error(glossaryAdoptionErrors.stale)
        used = true; guard(confirmSignal)
        if (selected.presence === 'absent') throw new Error(glossaryAdoptionErrors.absent)
        const confirmed = await bindings.open(book, client, confirmSignal); guard(confirmSignal)
        if (!confirmed || confirmed.id !== linked.id) throw new Error(glossaryAdoptionErrors.stale)
        const latest = validateBookGlossaryView(await api.get(scope, confirmSignal)); guard(confirmSignal)
        if (!sameBookGlossaryView(latest, remote)) throw new Error(glossaryAdoptionErrors.stale)
        if (remote.version >= Number.MAX_SAFE_INTEGER) throw new BookGlossaryError('exhausted')
        const entries = validateBookGlossaryPayload(scope, selected.entries)
        const mutation = { entries, expectedVersion: remote.version, mutationId: crypto.randomUUID() }
        const destination = createBookGlossaryStore(options.username, scope, options.storeOptions)
        try {
          await destination.transactNullable(current => {
            guard(confirmSignal); clear(current)
            if (!sameRecord(current, baseline)) throw new Error(glossaryAdoptionErrors.stale)
            // A saved deletion must write a tombstone even when the absent server also has null.
            return { enabled: true, selected: true, local: entries, remote, pending: mutation, conflict: null,
              legacyIds: current?.legacyIds ?? {}, retryAfterUntil: current?.retryAfterUntil ?? null }
          })
        } finally { destination.close() }
        // The accepted intent survives logout. Only the still-active registry may resume network work.
        try { guard(confirmSignal); options.onQueued(scope) } catch { /* The account journal retains its authorized pending mutation. */ }
      },
    }
  } }
}
