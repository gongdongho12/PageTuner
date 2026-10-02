import { LibraryOrganizationError, sameLibraryOrganization, sameLibraryOrganizationView, sameLibraryOrganizationScope, validateLibraryOrganization, validateLibraryOrganizationScope, type LibraryOrganizationScope, type LibraryOrganizationClient, type LibraryOrganizationErrorCode, type LibraryOrganizationMutation, type LibraryOrganizationView, type LibraryOrganization } from './libraryOrganizationApi'
import type { LibraryOrganizationRecord, LibraryOrganizationStore } from './libraryOrganizationStore'

export type LibraryOrganizationState = { status: 'loading' | 'unlinked' | 'synced' | 'pending' | 'conflict' | 'error'; enabled: boolean; local: LibraryOrganization; remote: LibraryOrganizationView | null; errorCode?: LibraryOrganizationErrorCode }
export type LibraryOrganizationChoice = { local: LibraryOrganization; remote: LibraryOrganizationView | null }
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

export function createLibraryOrganizationController({ api, store, scope: inputScope, onChange, debounceMs = 800 }: { api: LibraryOrganizationClient | null; store: LibraryOrganizationStore; scope: LibraryOrganizationScope; onChange?: (state: LibraryOrganizationState) => void; debounceMs?: number }) {
  const scope = validateLibraryOrganizationScope(inputScope)
  if (!sameLibraryOrganizationScope(scope, store.scope)) throw new LibraryOrganizationError('invalid-request')
  let closed = false, initialized = false, fallback = defaults, row: LibraryOrganizationRecord | null = null
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
    listeners.forEach(listener => listener(structuredClone(state)))
  }
  function fail(error: unknown) {
    if (closed || error instanceof LibraryOrganizationError && error.code === 'aborted') return
    lastError = error instanceof LibraryOrganizationError ? error.code : 'storage'
    if (terminal.has(lastError)) terminalError = lastError
    publish()
    if (lastError === 'choice-stale') schedule()
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
    if (!closed && api && !terminalError && (!lastError || lastError === 'choice-stale') && row?.enabled && row.pending && !row.conflict) timer = setTimeout(() => { void flush() }, debounceMs)
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
      } catch (error) { try { await load() } catch { /* Preserve the original failure. */ } fail(error) }
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
    } catch (error) { try { await load() } catch { /* Preserve the original failure. */ } fail(error); return false }
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
    } catch (error) { try { await load() } catch { /* Preserve the original failure. */ } fail(error) }
    })().finally(() => { edits.delete(edit) })
    edits.add(edit)
    return edit.then(accepted => { if (accepted && !closed) return flush() })
  }
  return { start, update, refresh, flush, choose, snapshot: (): LibraryOrganizationState => structuredClone(state),
    subscribe(listener: (state: LibraryOrganizationState) => void) { if (!closed) listeners.add(listener); return () => { listeners.delete(listener) } },
    close() { closed = true; connection.abort(); clearTimeout(timer); unsubscribe(); listeners.clear(); return Promise.allSettled([...edits, ...(binding ? [binding] : [])]).then(() => undefined) },
  }
}
export type LibraryOrganizationController = ReturnType<typeof createLibraryOrganizationController>
