import { LibraryOrganizationError, sameLibraryOrganization, sameLibraryOrganizationView, sameLibraryOrganizationScope, validateLibraryOrganization, validateLibraryOrganizationScope, validateLibraryOrganizationView, validateLibraryOrganizationMutation, type LibraryOrganizationScope, type LibraryOrganizationClient, type LibraryOrganizationErrorCode, type LibraryOrganizationMutation, type LibraryOrganizationView, type LibraryOrganization } from './libraryOrganizationApi'
import type { LibraryOrganizationRecord, LibraryOrganizationStore } from './libraryOrganizationStore'

export type LibraryOrganizationState = { status: 'loading' | 'unlinked' | 'synced' | 'pending' | 'conflict' | 'error'; enabled: boolean; local: LibraryOrganization; remote: LibraryOrganizationView | null; errorCode?: LibraryOrganizationErrorCode }
export type LibraryOrganizationChoice = { local: LibraryOrganization; remote: LibraryOrganizationView | null }
/** The exact nullable device journal and directly read server value reviewed by the caller. */
export type LibraryOrganizationAdoptionComparison = { record: LibraryOrganizationRecord | null; remote: LibraryOrganizationView }
const defaults: LibraryOrganization = { folder: '', tags: [], favorite: false }
const terminal = new Set<LibraryOrganizationErrorCode>(['authentication', 'forbidden', 'invalid-request', 'mutation-reused', 'invalid-response', 'storage', 'exhausted', 'not-found'])
function mutation(organization: LibraryOrganization, expectedVersion: number): LibraryOrganizationMutation {
  if (expectedVersion >= Number.MAX_SAFE_INTEGER) throw new LibraryOrganizationError('exhausted')
  return { organization: structuredClone(organization), expectedVersion, mutationId: crypto.randomUUID() }
}
function newest(previous: LibraryOrganizationView | null, incoming: LibraryOrganizationView): LibraryOrganizationView {
  if (previous && previous.version > incoming.version) return previous
  if (previous && previous.version === incoming.version && !sameLibraryOrganizationView(previous, incoming)) throw new LibraryOrganizationError('invalid-response')
  return incoming
}
function observeRemote(current: LibraryOrganizationRecord, incoming: LibraryOrganizationView): LibraryOrganizationRecord {
  const remote = newest(current.remote, incoming)
  // Once another tab has resolved the outbox, a late reply must advance the visible fields
  // together with their version. Otherwise a later partial edit can overwrite newer fields.
  return { ...current, remote,
    local: current.enabled && !current.pending && !current.conflict && remote.organization ? remote.organization : current.local,
    conflict: current.conflict ? newest(current.conflict, remote) : null }
}
function adoptionRecord(value: LibraryOrganizationRecord | null, scope: LibraryOrganizationScope): LibraryOrganizationRecord | null {
  if (value === null) return null
  if (!value || typeof value !== 'object' || Object.keys(value).length !== 7 || typeof value.enabled !== 'boolean' || value.retryAfterUntil !== null && (!Number.isSafeInteger(value.retryAfterUntil) || value.retryAfterUntil < 0)) throw new LibraryOrganizationError('invalid-request')
  const result = { enabled: value.enabled, local: validateLibraryOrganization(value.local), remote: value.remote === null ? null : validateLibraryOrganizationView(value.remote),
    pending: value.pending === null ? null : validateLibraryOrganizationMutation(value.pending), queued: value.queued === null ? null : validateLibraryOrganization(value.queued),
    conflict: value.conflict === null ? null : validateLibraryOrganizationView(value.conflict), retryAfterUntil: value.retryAfterUntil }
  if (result.remote && !sameLibraryOrganizationScope(scope, result.remote) || result.conflict && !sameLibraryOrganizationScope(scope, result.conflict) ||
      !result.enabled && (result.pending || result.queued || result.conflict) || result.enabled && !result.remote || !result.pending && (result.queued || result.conflict) ||
      result.pending && (!sameLibraryOrganization(result.local, result.queued ?? result.pending.organization) || result.pending.expectedVersion > (result.remote?.version ?? 0)) ||
      result.conflict && !sameLibraryOrganizationView(result.conflict, result.remote)) throw new LibraryOrganizationError('invalid-request')
  return result
}
function sameAdoptionRecord(a: LibraryOrganizationRecord | null, b: LibraryOrganizationRecord | null): boolean {
  if (a === null || b === null) return a === b
  return a.enabled === b.enabled && sameLibraryOrganization(a.local, b.local) && sameLibraryOrganizationView(a.remote, b.remote) &&
    (a.pending === null || b.pending === null ? a.pending === b.pending : a.pending.mutationId === b.pending.mutationId && a.pending.expectedVersion === b.pending.expectedVersion && sameLibraryOrganization(a.pending.organization, b.pending.organization)) &&
    (a.queued === null || b.queued === null ? a.queued === b.queued : sameLibraryOrganization(a.queued, b.queued)) && sameLibraryOrganizationView(a.conflict, b.conflict) && a.retryAfterUntil === b.retryAfterUntil
}
function cleanAdoptionRecord(record: LibraryOrganizationRecord | null) {
  if (record?.pending || record?.queued || record?.conflict) throw new LibraryOrganizationError('choice-stale')
}
function checkAdoptionHighwater(previous: LibraryOrganizationView | null, incoming: LibraryOrganizationView) {
  if (previous && (incoming.version < previous.version || incoming.version === previous.version && !sameLibraryOrganizationView(previous, incoming))) throw new LibraryOrganizationError('invalid-response')
}

export function createLibraryOrganizationController({ api, store, scope: inputScope, onChange, debounceMs = 800 }: { api: LibraryOrganizationClient | null; store: LibraryOrganizationStore; scope: LibraryOrganizationScope; onChange?: (state: LibraryOrganizationState) => void; debounceMs?: number }) {
  const scope = validateLibraryOrganizationScope(inputScope)
  if (!sameLibraryOrganizationScope(scope, store.scope)) throw new LibraryOrganizationError('invalid-request')
  let closed = false, initialized = false, fallback = defaults, row: LibraryOrganizationRecord | null = null
  let adoptionRemote: LibraryOrganizationView | null = null
  // Invalid observations permanently revoke snapshot adoption for this actor; a fresh actor must compare again.
  let adoptionInvalid = false
  let lastError: LibraryOrganizationErrorCode | undefined, terminalError: LibraryOrganizationErrorCode | undefined
  let binding: Promise<void> | null = null, starting: Promise<void> | null = null, flushing: Promise<void> | null = null, refreshing: Promise<void> | null = null
  let timer: ReturnType<typeof setTimeout> | undefined, loadTicket = 0
  let state: LibraryOrganizationState = { status: 'loading', enabled: false, local: defaults, remote: null }
  const connection = new AbortController(), listeners = new Set<(state: LibraryOrganizationState) => void>()
  const edits = new Set<Promise<unknown>>()
  if (onChange) listeners.add(onChange)
  function publish() {
    if (closed) return
    const errorCode = terminalError ?? lastError
    state = { status: row?.conflict ? 'conflict' : errorCode ? 'error' : !row ? 'loading' : !row.enabled ? 'unlinked' : row.pending ? 'pending' : 'synced', enabled: row?.enabled ?? false,
      local: structuredClone(row?.local ?? fallback), remote: structuredClone(row?.conflict ?? row?.remote ?? null), ...(errorCode ? { errorCode } : {}) }
    // Observers cannot undo a committed intent or prevent another observer from receiving it.
    listeners.forEach(listener => { try { listener(structuredClone(state)) } catch { /* Observer failures are outside the durable journal operation. */ } })
  }
  function revokeInvalid(error: unknown) {
    if (error instanceof LibraryOrganizationError && error.code === 'invalid-response') adoptionInvalid = true
  }
  function fail(error: unknown, reschedule = true) {
    revokeInvalid(error)
    if (closed || error instanceof LibraryOrganizationError && error.code === 'aborted') return
    lastError = error instanceof LibraryOrganizationError ? error.code : 'storage'
    if (terminal.has(lastError)) terminalError = lastError
    publish()
    if (reschedule && lastError === 'choice-stale') schedule()
  }
  async function load() {
    const ticket = ++loadTicket, next = await store.snapshot()
    if (!closed && ticket === loadTicket) { row = next; publish() }
  }
  async function bind() {
    if (initialized || closed) return
    if (binding) return binding
    binding = (async () => { await store.initialize(fallback); if (!closed) { initialized = true; await load() } })().finally(() => { binding = null })
    return binding
  }
  function schedule() {
    clearTimeout(timer)
    if (initialized && !closed && api && !terminalError && (!lastError || lastError === 'choice-stale') && row?.enabled && row.pending && !row.conflict) timer = setTimeout(() => { void flush() }, debounceMs)
  }
  const unsubscribe = store.subscribe(() => { void load().then(schedule).catch(fail) })
  async function flush(): Promise<void> {
    if (closed || terminalError) return
    if (flushing) return flushing
    clearTimeout(timer)
    flushing = (async () => {
      try {
        await bind()
        for (let count = 0; count < 20 && !closed; count++) {
          await load()
          if (closed || terminalError || !row?.enabled || !row.pending || row.conflict) return
          if (!api) { lastError = 'network'; publish(); return }
          if ((row.retryAfterUntil ?? 0) > Date.now()) { lastError = 'limit'; publish(); return }
          const sent = structuredClone(row.pending)
          try {
            const accepted = await api.put(scope, sent, connection.signal)
            if (closed) return
            await store.transact(current => {
              const remote = newest(current.remote, accepted)
              if (current.pending?.mutationId !== sent.mutationId) return observeRemote(current, remote)
              // A later GET/conflict from another tab must not be overwritten by this delayed ACK.
              if (remote.version > accepted.version) return { ...current, remote, conflict: remote }
              const intent = current.queued ?? current.local
              const pending = sameLibraryOrganization(intent, accepted.organization!) ? null : mutation(intent, accepted.version)
              return { ...current, remote, pending, queued: null, conflict: null, retryAfterUntil: null }
            })
            lastError = undefined
          } catch (error) {
            revokeInvalid(error)
            if (closed) return
            if (error instanceof LibraryOrganizationError && error.code === 'conflict' && error.current) {
              const incoming = error.current
              await store.transact(current => {
                const remote = newest(current.remote, incoming)
                if (current.pending?.mutationId !== sent.mutationId) return observeRemote(current, remote)
                return { ...current, remote, conflict: newest(current.conflict, remote) }
              })
              lastError = undefined; await load(); return
            }
            if (error instanceof LibraryOrganizationError && error.code === 'limit') await store.transact(current => ({ ...current, retryAfterUntil: Math.max(current.retryAfterUntil ?? 0, Date.now() + (error.retryAfterSeconds ?? 60) * 1000) }))
            throw error
          }
        }
        await load(); schedule()
      } catch (error) { revokeInvalid(error); try { await load() } catch { /* Preserve the original failure. */ } fail(error) }
    })().finally(() => { flushing = null })
    return flushing
  }
  async function refresh(): Promise<void> {
    if (closed) return
    if (refreshing) return refreshing
    terminalError = undefined; lastError = undefined
    refreshing = (async () => {
      try {
        await bind(); await flush()
        if (closed || terminalError || lastError) return
        if (!api) { lastError = 'network'; publish(); return }
        await load()
        if ((row?.retryAfterUntil ?? 0) > Date.now()) { lastError = 'limit'; publish(); return }
        const incoming = await api.get(scope, connection.signal)
        if (closed) return
        await store.transact(current => observeRemote(current, incoming))
        await load(); schedule()
      } catch (error) {
        revokeInvalid(error)
        if (!closed && error instanceof LibraryOrganizationError && error.code === 'limit') {
          try { await store.transact(current => ({ ...current, retryAfterUntil: Math.max(current.retryAfterUntil ?? 0, Date.now() + (error.retryAfterSeconds ?? 60) * 1000) })) } catch { /* Original error remains actionable. */ }
        }
        try { await load() } catch { /* Preserve the original failure. */ } fail(error)
      }
    })().finally(() => { refreshing = null })
    return refreshing
  }
  async function start(initial: LibraryOrganization): Promise<void> {
    if (closed) return
    if (starting) return starting
    if (initialized) return
    fallback = validateLibraryOrganization(initial); publish()
    starting = refresh().finally(() => { starting = null })
    return starting
  }
  function update(patch: Partial<LibraryOrganization>, expected?: LibraryOrganizationChoice): Promise<boolean> {
    if (closed) return Promise.resolve(false)
    const edit = (async () => { try {
      // Once the user submits an edit, finish its durable local write even if logout closes this controller.
      await bind()
      await store.transact(current => {
        if (expected && (!sameLibraryOrganization(current.local, expected.local) || !sameLibraryOrganizationView(current.conflict ?? current.remote, expected.remote))) throw new LibraryOrganizationError('choice-stale')
        const local = validateLibraryOrganization({ ...current.local, ...patch })
        if (sameLibraryOrganization(current.local, local)) return current
        if (!current.enabled) return { ...current, local }
        return { ...current, local, pending: current.pending ?? mutation(local, current.remote?.version ?? 0), queued: current.pending && !sameLibraryOrganization(current.pending.organization, local) ? local : null }
      })
      if (lastError === 'choice-stale') lastError = undefined
      if (!closed) { await load(); schedule() }
      return true
    } catch (error) { revokeInvalid(error); try { await load() } catch { /* Preserve the original failure. */ } fail(error); return false }
    })().finally(() => { edits.delete(edit) })
    edits.add(edit); return edit
  }
  function choose(choice: 'local' | 'server', expected: LibraryOrganizationChoice): Promise<void> {
    if (closed) return Promise.resolve()
    const edit = (async () => { try {
      await bind()
      await store.transact(current => {
        const remote = current.conflict ?? current.remote
        if (!sameLibraryOrganization(current.local, expected.local) || !sameLibraryOrganizationView(remote, expected.remote)) throw new LibraryOrganizationError('choice-stale')
        if (current.enabled && !current.conflict) throw new LibraryOrganizationError('choice-stale')
        if (!remote || choice === 'server' && !remote.organization) throw new LibraryOrganizationError('invalid-request')
        if (choice === 'server') return { ...current, enabled: true, local: remote.organization!, remote, pending: null, queued: null, conflict: null }
        return { ...current, enabled: true, remote, pending: mutation(current.local, remote.version), queued: null, conflict: null }
      })
      terminalError = undefined; lastError = undefined
      if (!closed) await load()
      return true
    } catch (error) { revokeInvalid(error); try { await load() } catch { /* Preserve the original failure. */ } fail(error) }
    })().finally(() => { edits.delete(edit) })
    edits.add(edit)
    return edit.then(accepted => { if (accepted && !closed) return flush() })
  }
  /** Creates a new explicit CAS intent only. The caller must guard account, origin, document proof and raw binding generation. */
  function adoptSnapshot(organization: LibraryOrganization, expected: LibraryOrganizationAdoptionComparison, guard: () => void): Promise<boolean> {
    if (closed) return Promise.resolve(false)
    const check = () => {
      if (closed || connection.signal.aborted) throw new LibraryOrganizationError('aborted')
      if (adoptionInvalid) throw new LibraryOrganizationError('invalid-response')
      if (typeof guard !== 'function') throw new LibraryOrganizationError('invalid-request')
      guard()
      if (closed || connection.signal.aborted) throw new LibraryOrganizationError('aborted')
      if (adoptionInvalid) throw new LibraryOrganizationError('invalid-response')
    }
    const edit = (async () => { try {
      check()
      // Clone before any await: neither a mutable caller comparison nor transport object can change this choice.
      const local = validateLibraryOrganization(structuredClone(organization))
      const comparison = structuredClone(expected)
      const record = adoptionRecord(comparison.record, scope), remote = validateLibraryOrganizationView(comparison.remote)
      if (!sameLibraryOrganizationScope(scope, remote)) throw new LibraryOrganizationError('invalid-request')
      cleanAdoptionRecord(record)
      checkAdoptionHighwater(record?.remote ?? null, remote)
      checkAdoptionHighwater(row?.remote ?? null, remote)
      checkAdoptionHighwater(adoptionRemote, remote)
      const before = await store.snapshot()
      check()
      cleanAdoptionRecord(before)
      if (!sameAdoptionRecord(before, record)) throw new LibraryOrganizationError('choice-stale')
      if (!api) throw new LibraryOrganizationError('network')
      const latest = validateLibraryOrganizationView(structuredClone(await api.get({ ...scope }, connection.signal)))
      check()
      if (!sameLibraryOrganizationScope(scope, latest)) throw new LibraryOrganizationError('invalid-response')
      checkAdoptionHighwater(remote, latest)
      checkAdoptionHighwater(adoptionRemote, latest)
      adoptionRemote = structuredClone(latest)
      if (!sameLibraryOrganizationView(remote, latest)) throw new LibraryOrganizationError('choice-stale')
      const checkWrite = () => {
        check()
        // A concurrent choice may observe a newer GET while this write is queued, or after its reducer ran.
        checkAdoptionHighwater(adoptionRemote, latest)
        checkAdoptionHighwater(row?.remote ?? null, latest)
      }
      await store.transactNullable(current => {
        checkWrite()
        cleanAdoptionRecord(current)
        if (!sameAdoptionRecord(current, record)) throw new LibraryOrganizationError('choice-stale')
        checkAdoptionHighwater(current?.remote ?? null, latest)
        // No equality shortcut: even empty-on-absent or an identical value is a fresh user's PUT intent.
        return { enabled: true, local: structuredClone(local), remote: structuredClone(latest), pending: mutation(local, latest.version), queued: null, conflict: null, retryAfterUntil: current?.retryAfterUntil ?? null }
      }, checkWrite)
      initialized = true; terminalError = undefined; lastError = undefined
      // The durable transaction has completed. A later display reload failure cannot undo acceptance.
      if (!closed) { try { await load(); schedule() } catch (error) { fail(error, false) } }
      return true
    } catch (error) {
      // Revoke before an asynchronous display reload: a queued peer write must see the invalid observation immediately.
      revokeInvalid(error)
      try { await load() } catch { /* Preserve the original failure. */ }
      fail(error, false); return false
    }
    })().finally(() => { edits.delete(edit) })
    edits.add(edit); return edit
  }
  return { start, update, refresh, flush, choose, adoptSnapshot, snapshot: (): LibraryOrganizationState => structuredClone(state),
    subscribe(listener: (state: LibraryOrganizationState) => void) { if (!closed) listeners.add(listener); return () => { listeners.delete(listener) } },
    close() { closed = true; connection.abort(); clearTimeout(timer); unsubscribe(); listeners.clear(); return Promise.allSettled([...edits, ...(binding ? [binding] : [])]).then(() => undefined) },
  }
}
export type LibraryOrganizationController = ReturnType<typeof createLibraryOrganizationController>
