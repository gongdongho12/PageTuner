import { ReadingNoteError, type ReadingNoteChange, type ReadingNoteChoice, type ReadingNoteClient, type ReadingNoteErrorCode, type ReadingNoteIdentity, type ReadingNoteItem } from './readingNoteApi'
import type { ReadingNoteStore } from './readingNoteStore'
import type { ReadingDocument } from './readingDocument'

export type ReadingNoteState = {
  status: 'loading' | 'synced' | 'pending' | 'conflict' | 'error'
  pendingCount: number
  pullPending: boolean
  unsupportedCount: number
  conflicts: { noteId: string; local: ReadingNoteChange; remote: ReadingNoteItem }[]
  errorCode?: ReadingNoteErrorCode
}
type Options = { api: ReadingNoteClient | null; store: ReadingNoteStore; identity: ReadingNoteIdentity; onChange?: (state: ReadingNoteState) => void; debounceMs?: number }

/** A controller survives reader closure; the visible notes and every pending mutation share one database transaction. */
export function createReadingNoteController({ api, store, identity, onChange, debounceMs = 800 }: Options) {
  let closed = false, started = false, terminalError: ReadingNoteErrorCode | undefined, lastError: ReadingNoteErrorCode | undefined
  let state: ReadingNoteState = { status: 'loading', pendingCount: 0, pullPending: false, unsupportedCount: 0, conflicts: [] }
  let snapshot: Awaited<ReturnType<ReadingNoteStore['snapshot']>> = { document: null, rows: [] }
  let starting: Promise<void> | null = null, flushing: Promise<void> | null = null, refreshing: Promise<void> | null = null
  let subscribedDocument: string | undefined, unsubscribe: (() => void) | undefined, timer: ReturnType<typeof setTimeout> | undefined
  const connection = new AbortController(), listeners = new Set<(state: ReadingNoteState) => void>()
  if (onChange) listeners.add(onChange)
  function publish() {
    if (closed) return
    const pending = snapshot.rows.filter(row => row.pending), conflicts = pending.flatMap(row => row.conflict ? [{ noteId: row.noteId,
      local: structuredClone({ deleted: (row.queued ?? row.pending!).deleted, note: (row.queued ?? row.pending!).note }), remote: structuredClone(row.conflict) }] : [])
    const errorCode = terminalError ?? lastError
    state = { status: errorCode ? 'error' : conflicts.length ? 'conflict' : pending.length || snapshot.document?.watermark !== null && snapshot.document?.watermark !== undefined ? 'pending' : 'synced',
      pendingCount: pending.length, pullPending: snapshot.document?.watermark != null, unsupportedCount: snapshot.document?.unsupportedIds.length ?? 0, conflicts, ...(errorCode ? { errorCode } : {}) }
    listeners.forEach(listener => listener(structuredClone(state)))
  }
  function fail(error: unknown) {
    if (closed || error instanceof ReadingNoteError && error.code === 'aborted') return
    lastError = error instanceof ReadingNoteError ? error.code : 'storage'
    if (['authentication', 'forbidden', 'not-found', 'invalid-request', 'mutation-reused', 'invalid-response', 'storage', 'exhausted'].includes(lastError)) terminalError = lastError
    publish()
  }
  function schedule() {
    clearTimeout(timer)
    if (!closed && api && !terminalError && snapshot.rows.some(row => row.pending && !row.conflict && !row.protectedLocal)) {
      timer = setTimeout(() => { void flush() }, debounceMs)
    }
  }
  async function load() {
    const value = await store.snapshot(identity)
    if (closed) return
    snapshot = value
    const documentId = value.document?.documentId
    if (documentId && documentId !== subscribedDocument) {
      unsubscribe?.(); subscribedDocument = documentId
      unsubscribe = store.subscribe(documentId, () => { void load().then(() => { if (!closed) { publish(); schedule() } }).catch(fail) })
    }
  }
  async function flush(): Promise<void> {
    if (closed) return
    clearTimeout(timer)
    if (terminalError) { publish(); return }
    if (flushing) return flushing
    flushing = (async () => {
      try {
        for (let count = 0; count < 20 && !closed; count++) {
          await load(); if (closed) return
          if (terminalError) { publish(); return }
          const entry = snapshot.rows.find(row => row.pending && !row.conflict && !row.protectedLocal)
          if (!entry || !api) { publish(); return }
          if ((snapshot.document?.retryAfterUntil ?? 0) > Date.now()) { lastError = 'limit'; publish(); return }
          const sent = structuredClone(entry.pending!)
          try {
            const accepted = await api.put(identity, entry.noteId, sent, connection.signal)
            if (closed) return
            await store.acknowledge(identity, entry.noteId, sent, accepted)
            lastError = undefined
          } catch (error) {
            if (closed) return
            if (error instanceof ReadingNoteError && error.code === 'conflict' && error.current) {
              await store.conflict(identity, entry.noteId, sent, error.current)
              lastError = undefined
              continue // Independent notes can still synchronize while this one needs a choice.
            }
            if (error instanceof ReadingNoteError && error.code === 'limit') await store.defer(identity, Date.now() + (error.retryAfterSeconds ?? 60) * 1000)
            throw error
          }
        }
        await load(); publish(); schedule()
      } catch (error) { try { await load() } catch { /* Keep the original fixed failure. */ } fail(error) }
    })().finally(() => { flushing = null })
    return flushing
  }
  async function refresh(): Promise<void> {
    if (closed) return
    if (refreshing) return refreshing
    terminalError = undefined; lastError = undefined
    refreshing = (async () => {
      try {
        await flush()
        if (closed || terminalError || lastError) return
        if (!api) { lastError = 'network'; publish(); return }
        // Resume a persisted fixed watermark after interruption. Only the feed advances the cursor.
        for (let page = 0; page < 20 && !closed; page++) {
          await load(); if (closed || !snapshot.document) return
          const { cursor, watermark } = snapshot.document
          const result = await api.list(identity, { afterRevision: cursor, limit: 50, ...(watermark === null ? {} : { untilRevision: watermark }) }, connection.signal)
          if (closed) return
          const applied = await store.applyPage(identity, cursor, result)
          await load(); publish()
          if (!applied || !result.hasMore) return
        }
      } catch (error) { try { await load() } catch { /* Keep the original fixed failure. */ } fail(error) }
    })().finally(() => { refreshing = null })
    return refreshing
  }
  async function start(document: ReadingDocument): Promise<void> {
    if (closed) return
    if (started) return starting ?? Promise.resolve()
    if (document.serverProgress?.kind !== identity.kind || document.serverProgress.recordId !== identity.recordId) throw new ReadingNoteError('invalid-request')
    started = true
    starting = (async () => {
      try { await store.bind(document); if (closed) return; await load(); publish(); await refresh() } catch (error) { fail(error) }
    })().finally(() => { starting = null })
    return starting
  }
  async function resolve(noteId: string, choice: 'local' | 'server', expected: ReadingNoteChoice): Promise<void> {
    if (closed) return
    try {
      await store.resolve(identity, noteId, choice, expected); if (closed) return
      lastError = undefined; await load(); publish(); await flush()
    } catch (error) { try { await load() } catch { /* Keep the original fixed error. */ } fail(error) }
  }
  return { start, refresh, flush, resolve,
    snapshot: (): ReadingNoteState => structuredClone(state),
    subscribe(listener: (state: ReadingNoteState) => void) { if (!closed) listeners.add(listener); return () => { listeners.delete(listener) } },
    close() { closed = true; connection.abort(); clearTimeout(timer); unsubscribe?.(); listeners.clear() },
  }
}
export type ReadingNoteController = ReturnType<typeof createReadingNoteController>
