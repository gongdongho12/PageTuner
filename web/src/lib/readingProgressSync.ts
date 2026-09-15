import { ReadingProgressError, sameReadingProgressAnchor, validReadingProgressAnchor,
  type ReadingProgressAnchor, type ReadingProgressClient, type ReadingProgressErrorCode, type ReadingProgressIdentity,
  type ReadingProgressMutation, type ReadingProgressView } from './readingProgressApi'
import { emptyReadingProgress, type ReadingProgressRecord, type ReadingProgressStore } from './readingProgressStore'

export type ReadingProgressState = {
  status: 'loading' | 'synced' | 'pending' | 'conflict' | 'error'
  localAnchor: ReadingProgressAnchor | null
  remote: ReadingProgressView | null
  errorCode?: ReadingProgressErrorCode
  /** Apply only a new sequence; ordinary persistence notifications never command navigation. */
  restoration?: { sequence: number; anchor: ReadingProgressAnchor }
}
type Options = {
  api: ReadingProgressClient | null
  store: ReadingProgressStore
  identity: ReadingProgressIdentity
  onChange?: (state: ReadingProgressState) => void
  debounceMs?: number
}

/** Durable mutations survive account closure and lost responses. Only explicit choices resolve conflicts. */
export function createReadingProgressController({ api, store, identity, onChange, debounceMs = 800 }: Options) {
  let closed = false, started = false, fallback: ReadingProgressAnchor | null = null
  let record = emptyReadingProgress(identity)
  let state: ReadingProgressState = { status: 'loading', localAnchor: null, remote: null }
  let startPromise: Promise<ReadingProgressAnchor | null> | null = null, flushPromise: Promise<void> | null = null
  let refreshPromise: Promise<ReadingProgressAnchor | null> | null = null
  let localWrites: Promise<void> = Promise.resolve(), timer: ReturnType<typeof setTimeout> | undefined
  let movementGeneration = 0, restoration: ReadingProgressState['restoration']
  let terminalError: ReadingProgressErrorCode | undefined
  const connection = new AbortController(), listeners = new Set<(state: ReadingProgressState) => void>()
  if (onChange) listeners.add(onChange)
  function preferred(value = record): ReadingProgressAnchor | null { return value.queued ?? value.pending?.anchor ?? value.remote?.anchor ?? fallback }
  function publish(errorCode?: ReadingProgressErrorCode, restoreGeneration?: number) {
    if (closed) return
    const activeError = terminalError ?? errorCode
    const anchor = preferred()
    // Capture intent synchronously in move(), before an IndexedDB write can yield an older position.
    if (restoreGeneration !== undefined && restoreGeneration === movementGeneration && anchor) {
      restoration = { sequence: (restoration?.sequence ?? 0) + 1, anchor: { ...anchor } }
    }
    state = { status: record.conflict ? 'conflict' : activeError ? 'error' : record.pending ? 'pending' : 'synced',
      localAnchor: anchor, remote: record.conflict ?? record.remote, ...(activeError ? { errorCode: activeError } : {}), ...(restoration ? { restoration } : {}) }
    listeners.forEach(listener => listener(structuredClone(state)))
  }
  function failure(error: unknown): void {
    if (closed || error instanceof ReadingProgressError && error.code === 'aborted') return
    const code = error instanceof ReadingProgressError ? error.code : 'storage'
    if (['authentication', 'forbidden', 'not-found', 'invalid-request', 'mutation-reused', 'invalid-response', 'storage'].includes(code)) terminalError = code
    publish(code)
  }
  function mutation(anchor: ReadingProgressAnchor, version: number): ReadingProgressMutation {
    if (version >= Number.MAX_SAFE_INTEGER) throw new ReadingProgressError('invalid-request')
    return { expectedVersion: version, mutationId: crypto.randomUUID(), anchor: { ...anchor } }
  }
  function schedule() {
    clearTimeout(timer)
    if (!closed && api && !record.conflict && !terminalError) timer = setTimeout(() => { void flush() }, debounceMs)
  }
  async function refresh(): Promise<ReadingProgressAnchor | null> {
    if (closed) return null
    if (refreshPromise) return refreshPromise
    // Explicit user rechecks (and a new account controller) can retry failures requiring intervention.
    terminalError = undefined
    const restoreGeneration = movementGeneration
    refreshPromise = (async () => {
      try {
        await localWrites
        if (closed) return null
        record = await store.get(identity)
        if (closed) return null
        publish(undefined, restoreGeneration)
        if (record.pending) { await flush(); return preferred() }
        if (!api) { publish('network'); return preferred() }
        const remote = await api.get(identity, connection.signal)
        if (closed) return null
        record = await store.update(identity, current => {
          // A local movement or newer response in another tab always wins over this GET.
          if (current.pending || current.remote && current.remote.version > remote.version) return current
          return { ...current, remote }
        })
        if (closed) return null
        publish(undefined, restoreGeneration)
        if (record.pending) await flush()
      } catch (error) {
        // A second tab may have acknowledged or edited this document while GET was failing.
        if (!closed) { try { await localWrites; record = await store.get(identity) } catch { /* Preserve the original fixed error. */ } }
        failure(error)
      }
      return preferred()
    })().finally(() => { refreshPromise = null })
    return refreshPromise
  }
  async function start(fallbackAnchor: ReadingProgressAnchor | null = null): Promise<ReadingProgressAnchor | null> {
    if (closed) return null
    if (fallbackAnchor && !validReadingProgressAnchor(fallbackAnchor)) throw new ReadingProgressError('invalid-request')
    if (started) return startPromise ?? preferred()
    started = true; fallback = fallbackAnchor ? { ...fallbackAnchor } : null
    startPromise = refresh().finally(() => { startPromise = null })
    return startPromise
  }
  async function move(anchor: ReadingProgressAnchor): Promise<void> {
    if (closed) return
    if (!validReadingProgressAnchor(anchor)) throw new ReadingProgressError('invalid-request')
    movementGeneration++
    const copied = { ...anchor }
    const write = localWrites.then(async () => {
      record = await store.update(identity, current => {
        // A movement accepted before close must still reach durable storage.
        if (current.pending) return { ...current, queued: sameReadingProgressAnchor(current.pending.anchor, copied) ? null : copied }
        if (sameReadingProgressAnchor(current.remote?.anchor ?? null, copied)) return current
        return { ...current, pending: mutation(copied, current.remote?.version ?? 0) }
      })
      if (closed) return
      publish(); schedule()
    })
    localWrites = write.catch(error => { failure(error) })
    await localWrites
  }
  async function flush(): Promise<void> {
    if (closed) return
    clearTimeout(timer)
    if (terminalError) { publish(); return }
    if (flushPromise) return flushPromise
    flushPromise = (async () => {
      try {
        // A finite drain prevents rapid reading from holding the background worker forever.
        for (let count = 0; count < 20 && !closed; count++) {
          await localWrites
          if (closed) return
          if (terminalError) { publish(); return }
          record = await store.get(identity)
          if (closed) return
          publish()
          if (!record.pending || record.conflict || !api) return
          if (record.retryAfterUntil && record.retryAfterUntil > Date.now()) { publish('limit'); return }
          const sent = structuredClone(record.pending)
          let remote: ReadingProgressView
          try { remote = await api.put(identity, sent, connection.signal) }
          catch (error) {
            if (closed) return
            if (error instanceof ReadingProgressError && error.code === 'conflict' && error.current) {
              const conflict = error.current
              record = await store.update(identity, current => current.pending?.mutationId === sent.mutationId
                ? { ...current, conflict } : current)
              publish()
              return
            }
            if (error instanceof ReadingProgressError && error.code === 'limit') {
              const retryAfterUntil = Date.now() + (error.retryAfterSeconds ?? 60) * 1000
              record = await store.update(identity, current => ({ ...current, retryAfterUntil: Math.max(current.retryAfterUntil ?? 0, retryAfterUntil) }))
            }
            throw error
          }
          if (closed) return
          record = await store.update(identity, current => {
            // Another tab may already have acknowledged this mutation and queued its successor.
            if (current.pending?.mutationId !== sent.mutationId) return current
            return { ...current, remote, conflict: null, queued: null, retryAfterUntil: null,
              pending: current.queued && !sameReadingProgressAnchor(current.queued, remote.anchor) ? mutation(current.queued, remote.version) : null }
          })
          publish()
          if (!record.pending) return
        }
        if (record.pending) schedule()
      } catch (error) {
        // Never publish the old in-flight anchor over a newer durable movement from another tab.
        if (!closed) { try { await localWrites; record = await store.get(identity) } catch { /* Preserve the original fixed error. */ } }
        failure(error)
      }
    })().finally(() => { flushPromise = null })
    return flushPromise
  }
  async function chooseLocal(): Promise<void> {
    if (closed) return
    try {
      await localWrites
      record = await store.update(identity, current => {
        if (closed || !current.conflict || !current.pending) return current
        const anchor = current.queued ?? current.pending.anchor
        return { ...current, remote: current.conflict, pending: mutation(anchor, current.conflict.version), queued: null, conflict: null }
      })
      publish(); await flush()
    } catch (error) { failure(error) }
  }
  async function chooseServer(): Promise<ReadingProgressAnchor | null> {
    if (closed) return null
    const restoreGeneration = movementGeneration
    let selected = false
    try {
      await localWrites
      record = await store.update(identity, current => {
        if (closed || !current.conflict) return current
        selected = true
        return { ...current, remote: current.conflict, pending: null, queued: null, conflict: null }
      })
      publish(undefined, selected ? restoreGeneration : undefined); return preferred()
    } catch (error) { failure(error); return preferred() }
  }
  return {
    start, refresh, move, flush, chooseLocal, chooseServer,
    snapshot: (): ReadingProgressState => structuredClone(state),
    subscribe(listener: (state: ReadingProgressState) => void) { if (!closed) listeners.add(listener); return () => { listeners.delete(listener) } },
    close() { closed = true; clearTimeout(timer); connection.abort(); listeners.clear() },
  }
}
export type ReadingProgressController = ReturnType<typeof createReadingProgressController>
