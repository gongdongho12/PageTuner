import { BookGlossaryError, sameBookGlossary, sameBookGlossaryView, sameBookGlossaryScope, validateBookGlossary, validateBookGlossaryPayload, validateBookGlossaryScope, type BookGlossaryScope, type BookGlossaryClient, type BookGlossaryErrorCode, type BookGlossaryMutation, type BookGlossaryView, type BookGlossary } from './bookGlossaryApi'
import type { BookGlossaryRecord, BookGlossaryStore } from './bookGlossaryStore'

export type BookGlossaryState = { status: 'loading' | 'unlinked' | 'synced' | 'pending' | 'conflict' | 'error'; enabled: boolean; linked: boolean; local: BookGlossary; remote: BookGlossaryView | null; errorCode?: BookGlossaryErrorCode }
export type BookGlossaryChoice = { local: BookGlossary; remote: BookGlossaryView | null }
const defaults: BookGlossary = null
const terminal = new Set<BookGlossaryErrorCode>(['authentication', 'forbidden', 'invalid-request', 'mutation-reused', 'invalid-response', 'storage', 'exhausted', 'not-found'])
function mutation(entries: BookGlossary, expectedVersion: number): BookGlossaryMutation {
  if (expectedVersion >= Number.MAX_SAFE_INTEGER) throw new BookGlossaryError('exhausted')
  return { entries: structuredClone(entries), expectedVersion, mutationId: crypto.randomUUID() }
}
function newest(previous: BookGlossaryView | null, incoming: BookGlossaryView): BookGlossaryView {
  if (previous && previous.version > incoming.version) return previous
  if (previous && previous.version === incoming.version && !sameBookGlossaryView(previous, incoming)) throw new BookGlossaryError('invalid-response')
  return incoming
}
function observeRemote(current: BookGlossaryRecord, incoming: BookGlossaryView): BookGlossaryRecord {
  const remote = newest(current.remote, incoming)
  // Once another tab has resolved the outbox, a late reply must advance the visible fields
  // together with their version. Otherwise a later partial edit can overwrite newer fields.
  return { ...current, remote,
    local: current.enabled && !current.pending && !current.conflict ? remote.entries : current.local,
    conflict: current.conflict ? newest(current.conflict, remote) : null }
}

export function createBookGlossaryController({ api, store, scope: inputScope, onChange, debounceMs = 800 }: { api: BookGlossaryClient | null; store: BookGlossaryStore; scope: BookGlossaryScope; onChange?: (state: BookGlossaryState) => void; debounceMs?: number }) {
  const scope = validateBookGlossaryScope(inputScope)
  if (!sameBookGlossaryScope(scope, store.scope)) throw new BookGlossaryError('invalid-request')
  let closed = false, initialized = false, fallback = defaults, row: BookGlossaryRecord | null = null
  let lastError: BookGlossaryErrorCode | undefined, terminalError: BookGlossaryErrorCode | undefined
  let binding: Promise<void> | null = null, starting: Promise<void> | null = null, flushing: Promise<void> | null = null, refreshing: Promise<void> | null = null
  let timer: ReturnType<typeof setTimeout> | undefined, loadTicket = 0
  let state: BookGlossaryState = { status: 'loading', enabled: false, linked: false, local: defaults, remote: null }
  const connection = new AbortController(), listeners = new Set<(state: BookGlossaryState) => void>()
  const edits = new Set<Promise<unknown>>()
  if (onChange) listeners.add(onChange)
  function publish() {
    if (closed) return
    const errorCode = terminalError ?? lastError
    state = { status: row?.conflict ? 'conflict' : errorCode ? 'error' : !row ? 'loading' : !row.selected ? 'unlinked' : row.pending ? 'pending' : 'synced', enabled: row?.selected ?? false, linked: row?.enabled ?? false,
      local: structuredClone(row ? row.local : fallback), remote: structuredClone(row?.conflict ?? row?.remote ?? null), ...(errorCode ? { errorCode } : {}) }
    listeners.forEach(listener => listener(structuredClone(state)))
  }
  function fail(error: unknown, latch = true) {
    if (closed || error instanceof BookGlossaryError && error.code === 'aborted') return
    lastError = error instanceof BookGlossaryError ? error.code : 'storage'
    if (latch && terminal.has(lastError)) terminalError = lastError
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
              const intent = current.local
              if (!sameBookGlossary(intent, accepted.entries) && accepted.version >= Number.MAX_SAFE_INTEGER) return { ...current, remote, conflict: remote }
              const pending = sameBookGlossary(intent, accepted.entries) ? null : mutation(intent, accepted.version)
              return { ...current, remote, pending, conflict: null, retryAfterUntil: null }
            })
            lastError = undefined
          } catch (error) {
            if (closed) return
            if (error instanceof BookGlossaryError && error.code === 'conflict' && error.current) {
              const incoming = error.current
              await store.transact(current => {
                const remote = newest(current.remote, incoming)
                if (current.pending?.mutationId !== sent.mutationId) return observeRemote(current, remote)
                return { ...current, remote, conflict: newest(current.conflict, remote) }
              })
              lastError = undefined; await load(); return
            }
            if (error instanceof BookGlossaryError && error.code === 'limit') await store.transact(current => ({ ...current, retryAfterUntil: Math.max(current.retryAfterUntil ?? 0, Date.now() + (error.retryAfterSeconds ?? 60) * 1000) }))
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
        if (!closed && error instanceof BookGlossaryError && error.code === 'limit') {
          try { await store.transact(current => ({ ...current, retryAfterUntil: Math.max(current.retryAfterUntil ?? 0, Date.now() + (error.retryAfterSeconds ?? 60) * 1000) })) } catch { /* Original error remains actionable. */ }
        }
        try { await load() } catch { /* Preserve the original failure. */ } fail(error)
      }
    })().finally(() => { refreshing = null })
    return refreshing
  }
  async function start(initial: BookGlossary = null): Promise<void> {
    if (closed) return
    if (starting) return starting
    if (initialized) return
    fallback = validateBookGlossary(initial); publish()
    starting = refresh().finally(() => { starting = null })
    return starting
  }
  function update(entries: BookGlossary, expected?: BookGlossaryChoice): Promise<boolean> {
    if (closed) return Promise.resolve(false)
    const edit = (async () => { try {
      // Once the user submits an edit, finish its durable local write even if logout closes this controller.
      await bind()
      await store.transact(current => {
        if (expected && (!sameBookGlossary(current.local, expected.local) || !sameBookGlossaryView(current.conflict ?? current.remote, expected.remote))) throw new BookGlossaryError('choice-stale')
        const local = validateBookGlossaryPayload(scope, entries)
        if (sameBookGlossary(current.local, local)) return current
        if (!current.enabled) return { ...current, local }
        return { ...current, local, pending: current.pending ?? mutation(local, current.remote?.version ?? 0) }
      })
      lastError = undefined
      if (!closed) { await load(); schedule() }
      return true
    } catch (error) { try { await load() } catch { /* Preserve the original failure. */ } fail(error, false); return false }
    })().finally(() => { edits.delete(edit) })
    edits.add(edit); return edit
  }
  function choose(choice: 'local' | 'server', expected: BookGlossaryChoice): Promise<boolean> {
    if (closed) return Promise.resolve(false)
    const edit = (async () => { try {
      await bind()
      await store.transact(current => {
        const remote = current.conflict ?? current.remote
        if (!sameBookGlossary(current.local, expected.local) || !sameBookGlossaryView(remote, expected.remote)) throw new BookGlossaryError('choice-stale')
        if (current.selected && !current.conflict) throw new BookGlossaryError('choice-stale')
        if (current.enabled && !current.conflict) return { ...current, selected: true }
        if (!remote) throw new BookGlossaryError('invalid-request')
        if (choice === 'server') return { ...current, enabled: true, selected: true, local: remote.entries, remote, pending: null, conflict: null }
        validateBookGlossaryPayload(scope, current.local)
        return { ...current, enabled: true, selected: true, remote, pending: mutation(current.local, remote.version), conflict: null }
      })
      terminalError = undefined; lastError = undefined
      if (!closed) await load()
      return true
    } catch (error) { try { await load() } catch { /* Preserve the original failure. */ } fail(error, false); return false }
    })().finally(() => { edits.delete(edit) })
    edits.add(edit)
    return edit.then(async accepted => { if (accepted && !closed) await flush(); return accepted === true })
  }
  function adoptLegacy(entries: { source: string; target: string; kind?: 'Character' | 'Place' | 'Term'; displayTerm?: string; caseSensitive?: boolean; enabled?: boolean }[], expected: BookGlossaryChoice): Promise<boolean> {
    if (closed) return Promise.resolve(false)
    const edit = (async () => { try {
      await bind()
      await store.transact(current => {
        const remote = current.conflict ?? current.remote
        if (!remote || current.conflict || !sameBookGlossary(current.local, expected.local) || !sameBookGlossaryView(remote, expected.remote)) throw new BookGlossaryError('choice-stale')
        const legacyIds = { ...current.legacyIds }
        const local = validateBookGlossaryPayload(scope, entries.map(entry => {
          const key = JSON.stringify(entry.source)
          const id = legacyIds[key] ?? crypto.randomUUID(); legacyIds[key] = id
          return { id, sourceTerm: entry.source, translatedTerm: entry.target, displayTerm: entry.displayTerm ?? '', kind: entry.kind ?? 'Character', caseSensitive: entry.caseSensitive ?? false, enabled: entry.enabled ?? true }
        }))
        return { ...current, enabled: true, selected: true, local, legacyIds, pending: current.pending ?? mutation(local, remote.version) }
      })
      terminalError = undefined; lastError = undefined
      if (!closed) { await load(); schedule() }
      return true
    } catch (error) { fail(error, false); return false }
    })().finally(() => { edits.delete(edit) })
    edits.add(edit); return edit
  }
  function selectDevice(): Promise<boolean> {
    if (closed) return Promise.resolve(false)
    const edit = (async () => { try {
      await bind()
      await store.transact(current => ({ ...current, selected: false }))
      if (!closed) { await load(); schedule() }
      return true
    } catch (error) { fail(error, false); return false }
    })().finally(() => { edits.delete(edit) })
    edits.add(edit); return edit
  }
  return { start, update, refresh, flush, choose, adoptLegacy, selectDevice, snapshot: (): BookGlossaryState => structuredClone(state),
    subscribe(listener: (state: BookGlossaryState) => void) { if (!closed) listeners.add(listener); return () => { listeners.delete(listener) } },
    close() { closed = true; connection.abort(); clearTimeout(timer); unsubscribe(); listeners.clear(); return Promise.allSettled([...edits, ...(binding ? [binding] : [])]).then(() => undefined) },
  }
}
export type BookGlossaryController = ReturnType<typeof createBookGlossaryController>
