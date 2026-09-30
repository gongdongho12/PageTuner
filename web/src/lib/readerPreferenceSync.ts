import { ReaderPreferenceError, sameReaderPreferences, sameReaderPreferenceView, validateReaderPreferences, type ReaderPreferenceClient, type ReaderPreferenceErrorCode, type ReaderPreferenceMutation, type ReaderPreferenceView, type SharedReaderPreferences } from './readerPreferenceApi'
import type { ReaderPreferenceRecord, ReaderPreferenceStore } from './readerPreferenceStore'

export type ReaderPreferenceState = { status: 'loading' | 'unlinked' | 'synced' | 'pending' | 'conflict' | 'error'; enabled: boolean; local: SharedReaderPreferences; remote: ReaderPreferenceView | null; errorCode?: ReaderPreferenceErrorCode }
export type ReaderPreferenceChoice = { local: SharedReaderPreferences; remote: ReaderPreferenceView | null }
const defaults: SharedReaderPreferences = { fontSize: 20, lineHeightPercent: 160, pageMargin: 16, touchDirection: 'left-previous', listMode: 'paged' }
const terminal = new Set<ReaderPreferenceErrorCode>(['authentication', 'forbidden', 'invalid-request', 'mutation-reused', 'invalid-response', 'storage', 'exhausted'])
function mutation(preferences: SharedReaderPreferences, expectedVersion: number): ReaderPreferenceMutation {
  if (expectedVersion >= Number.MAX_SAFE_INTEGER) throw new ReaderPreferenceError('exhausted')
  return { preferences: structuredClone(preferences), expectedVersion, mutationId: crypto.randomUUID() }
}
function newest(previous: ReaderPreferenceView | null, incoming: ReaderPreferenceView): ReaderPreferenceView {
  if (previous && previous.version > incoming.version) return previous
  if (previous && previous.version === incoming.version && !sameReaderPreferenceView(previous, incoming)) throw new ReaderPreferenceError('invalid-response')
  return incoming
}

export function createReaderPreferenceController({ api, store, onChange, debounceMs = 800 }: { api: ReaderPreferenceClient | null; store: ReaderPreferenceStore; onChange?: (state: ReaderPreferenceState) => void; debounceMs?: number }) {
  let closed = false, initialized = false, fallback = defaults, row: ReaderPreferenceRecord | null = null
  let lastError: ReaderPreferenceErrorCode | undefined, terminalError: ReaderPreferenceErrorCode | undefined
  let binding: Promise<void> | null = null, starting: Promise<void> | null = null, flushing: Promise<void> | null = null, refreshing: Promise<void> | null = null
  let timer: ReturnType<typeof setTimeout> | undefined, loadTicket = 0
  let state: ReaderPreferenceState = { status: 'loading', enabled: false, local: defaults, remote: null }
  const connection = new AbortController(), listeners = new Set<(state: ReaderPreferenceState) => void>()
  const edits = new Set<Promise<void>>()
  if (onChange) listeners.add(onChange)
  function publish() {
    if (closed) return
    const errorCode = terminalError ?? lastError
    state = { status: row?.conflict ? 'conflict' : errorCode ? 'error' : !row ? 'loading' : !row.enabled ? 'unlinked' : row.pending ? 'pending' : 'synced', enabled: row?.enabled ?? false,
      local: structuredClone(row?.local ?? fallback), remote: structuredClone(row?.conflict ?? row?.remote ?? null), ...(errorCode ? { errorCode } : {}) }
    listeners.forEach(listener => listener(structuredClone(state)))
  }
  function fail(error: unknown) {
    if (closed || error instanceof ReaderPreferenceError && error.code === 'aborted') return
    lastError = error instanceof ReaderPreferenceError ? error.code : 'storage'
    if (terminal.has(lastError)) terminalError = lastError
    publish()
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
    if (!closed && api && !terminalError && !lastError && row?.enabled && row.pending && !row.conflict) timer = setTimeout(() => { void flush() }, debounceMs)
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
            const accepted = await api.put(sent, connection.signal)
            if (closed) return
            await store.transact(current => {
              const remote = newest(current.remote, accepted)
              if (current.pending?.mutationId !== sent.mutationId) return { ...current, remote, conflict: current.conflict ? newest(current.conflict, remote) : null }
              // A later GET/conflict from another tab must not be overwritten by this delayed ACK.
              if (remote.version > accepted.version) return { ...current, remote, conflict: remote }
              const intent = current.queued ?? current.local
              const pending = sameReaderPreferences(intent, accepted.preferences!) ? null : mutation(intent, accepted.version)
              return { ...current, remote, pending, queued: null, conflict: null, retryAfterUntil: null }
            })
            lastError = undefined
          } catch (error) {
            if (closed) return
            if (error instanceof ReaderPreferenceError && error.code === 'conflict' && error.current) {
              const incoming = error.current
              await store.transact(current => {
                const remote = newest(current.remote, incoming)
                if (current.pending?.mutationId !== sent.mutationId) return { ...current, remote, conflict: current.conflict ? newest(current.conflict, remote) : null }
                return { ...current, remote, conflict: newest(current.conflict, remote) }
              })
              lastError = undefined; await load(); return
            }
            if (error instanceof ReaderPreferenceError && error.code === 'limit') await store.transact(current => ({ ...current, retryAfterUntil: Math.max(current.retryAfterUntil ?? 0, Date.now() + (error.retryAfterSeconds ?? 60) * 1000) }))
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
        const incoming = await api.get(connection.signal)
        if (closed) return
        await store.transact(current => {
          const remote = newest(current.remote, incoming)
          return { ...current, remote, local: current.enabled && !current.pending && remote.preferences ? remote.preferences : current.local,
            conflict: current.conflict ? newest(current.conflict, remote) : null }
        })
        await load(); schedule()
      } catch (error) {
        if (!closed && error instanceof ReaderPreferenceError && error.code === 'limit') {
          try { await store.transact(current => ({ ...current, retryAfterUntil: Math.max(current.retryAfterUntil ?? 0, Date.now() + (error.retryAfterSeconds ?? 60) * 1000) })) } catch { /* Original error remains actionable. */ }
        }
        try { await load() } catch { /* Preserve the original failure. */ } fail(error)
      }
    })().finally(() => { refreshing = null })
    return refreshing
  }
  async function start(initial: SharedReaderPreferences): Promise<void> {
    if (closed) return
    if (starting) return starting
    if (initialized) return
    fallback = validateReaderPreferences(initial); publish()
    starting = refresh().finally(() => { starting = null })
    return starting
  }
  function update(patch: Partial<SharedReaderPreferences>): Promise<void> {
    if (closed) return Promise.resolve()
    const edit = (async () => { try {
      // Once the user submits an edit, finish its durable local write even if logout closes this controller.
      await bind()
      await store.transact(current => {
        const local = validateReaderPreferences({ ...current.local, ...patch })
        if (sameReaderPreferences(current.local, local)) return current
        if (!current.enabled) return { ...current, local }
        return { ...current, local, pending: current.pending ?? mutation(local, current.remote?.version ?? 0), queued: current.pending && !sameReaderPreferences(current.pending.preferences, local) ? local : null }
      })
      if (!closed) { await load(); schedule() }
    } catch (error) { try { await load() } catch { /* Preserve the original failure. */ } fail(error) }
    })().finally(() => { edits.delete(edit) })
    edits.add(edit); return edit
  }
  async function choose(choice: 'local' | 'server', expected: ReaderPreferenceChoice): Promise<void> {
    if (closed) return
    try {
      await bind(); if (closed) return
      await store.transact(current => {
        const remote = current.conflict ?? current.remote
        if (!sameReaderPreferences(current.local, expected.local) || !sameReaderPreferenceView(remote, expected.remote)) throw new ReaderPreferenceError('choice-stale')
        if (current.enabled && !current.conflict) throw new ReaderPreferenceError('choice-stale')
        if (!remote || choice === 'server' && !remote.preferences) throw new ReaderPreferenceError('invalid-request')
        if (choice === 'server') return { ...current, enabled: true, local: remote.preferences!, remote, pending: null, queued: null, conflict: null }
        return { ...current, enabled: true, remote, pending: mutation(current.local, remote.version), queued: null, conflict: null }
      })
      terminalError = undefined; lastError = undefined; await load(); await flush()
    } catch (error) { try { await load() } catch { /* Preserve the original failure. */ } fail(error) }
  }
  return { start, update, refresh, flush, choose, snapshot: (): ReaderPreferenceState => structuredClone(state),
    subscribe(listener: (state: ReaderPreferenceState) => void) { if (!closed) listeners.add(listener); return () => { listeners.delete(listener) } },
    close() { closed = true; connection.abort(); clearTimeout(timer); unsubscribe(); listeners.clear(); return Promise.allSettled([...edits, ...(binding ? [binding] : [])]).then(() => undefined) },
  }
}
export type ReaderPreferenceController = ReturnType<typeof createReaderPreferenceController>
