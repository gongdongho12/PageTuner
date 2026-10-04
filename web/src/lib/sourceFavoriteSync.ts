import { SourceFavoriteError, sameSourceFavoriteChange, sourceBookKey, validateSourceBookIdentity, validateSourceFavoriteChange, type SourceBookIdentity, type SourceFavoriteChange, type SourceFavoriteClient, type SourceFavoriteErrorCode, type SourceFavoriteItem, type SourceFavoriteMutation } from './sourceFavoriteApi'
import { emptyFavoriteJournal, favoriteRowChange, type SourceFavoriteJournal, type SourceFavoriteRow, type SourceFavoriteStore } from './sourceFavoriteStore'

export type SourceFavoriteState = SourceFavoriteJournal & { loading: boolean; errorCode?: SourceFavoriteErrorCode }
const terminal = new Set<SourceFavoriteErrorCode>(['authentication', 'forbidden', 'invalid-request', 'mutation-reused', 'invalid-response', 'storage', 'exhausted'])
function newest(old: SourceFavoriteItem | null, incoming: SourceFavoriteItem) {
  if (old && old.version > incoming.version) return old
  if (old && (old.version === incoming.version && (sourceBookKey(old) !== sourceBookKey(incoming) || old.changeRevision !== incoming.changeRevision || old.updatedAt !== incoming.updatedAt || !sameSourceFavoriteChange(old, incoming)) || old.version < incoming.version && old.changeRevision >= incoming.changeRevision)) throw new SourceFavoriteError('invalid-response')
  return incoming
}
function observe(row: SourceFavoriteRow | undefined, item: SourceFavoriteItem): SourceFavoriteRow {
  if (!row) return { remote: item, pending: null, queued: null, conflict: null }
  const remote = newest(row.remote, item)
  return { ...row, remote, conflict: row.conflict ? remote : null }
}
function mutation(identity: SourceBookIdentity, change: SourceFavoriteChange, version: number): SourceFavoriteMutation {
  if (version >= Number.MAX_SAFE_INTEGER) throw new SourceFavoriteError('exhausted')
  return { providerId: identity.providerId, bookId: identity.bookId, deleted: change.deleted, book: change.book, expectedVersion: version, mutationId: crypto.randomUUID() }
}
export function createSourceFavoriteController(store: SourceFavoriteStore, api: SourceFavoriteClient | null) {
  let closed = false, syncing: Promise<void> | null = null, lastError: SourceFavoriteErrorCode | undefined, row = emptyFavoriteJournal(), loading = true, loadVersion = 0
  const abort = new AbortController(), listeners = new Set<(state: SourceFavoriteState) => void>(), edits = new Set<Promise<unknown>>()
  const snapshot = (): SourceFavoriteState => structuredClone({ ...row, loading, ...(lastError ? { errorCode: lastError } : {}) })
  const publish = () => { if (!closed) listeners.forEach(listener => listener(snapshot())) }
  async function load() { const ticket = ++loadVersion, next = await store.snapshot(); if (!closed && ticket === loadVersion) { row = next; publish() } }
  function fail(error: unknown) { if (closed || error instanceof SourceFavoriteError && error.code === 'aborted') return; lastError = error instanceof SourceFavoriteError ? error.code : 'storage'; publish() }
  const unsubscribe = store.subscribe(() => { void load().catch(fail) })
  async function run(manual = false): Promise<void> {
    if (closed || syncing || !manual && lastError && terminal.has(lastError)) return syncing ?? Promise.resolve()
    if (manual) lastError = undefined
    syncing = (async () => { try {
      await load(); if (closed) return
      if (!api) { lastError = 'network'; return }
      if (row.retryAfterUntil > Date.now()) { lastError = 'limit'; return }
      // Replay durable mutations unchanged before reading the feed. PUT responses never move the feed cursor.
      for (let count = 0; count < 50 && !closed; count++) {
        await load(); const entry = Object.entries(row.rows).find(([, value]) => value.pending && !value.conflict)
        if (!entry) break
        const [key, value] = entry, sent = structuredClone(value.pending!)
        try {
          const accepted = await api.put(sent, abort.signal); if (closed) return
          await store.transact(current => { const item = current.rows[key]; if (!item) return current; const observed = observe(item, accepted)
            if (item.pending?.mutationId !== sent.mutationId) { current.rows[key] = observed; return current }
            if (observed.remote!.version > accepted.version) { current.rows[key] = { ...observed, conflict: observed.remote }; return current }
            const desired = item.queued ?? item.pending, next = sameSourceFavoriteChange(desired, accepted) ? null : mutation(accepted, desired, accepted.version)
            current.rows[key] = { remote: accepted, pending: next, queued: null, conflict: null }; return current
          })
        } catch (error) {
          if (closed) return
          if (error instanceof SourceFavoriteError && error.code === 'conflict' && error.current) { const incoming = error.current; await store.transact(current => { const item = current.rows[key]; if (item) { const observed = observe(item, incoming); current.rows[key] = item.pending?.mutationId === sent.mutationId ? { ...observed, conflict: observed.remote } : observed } return current }); continue }
          throw error
        }
      }
      // Pin every page to the first response watermark. Persist each page and cursor together.
      for (let count = 0; count < 100 && !closed; count++) {
        await load(); const query = { afterRevision: row.cursor, ...(row.watermark === null ? {} : { untilRevision: row.watermark }) }, page = await api.list(query, abort.signal)
        if (closed) return
        await store.transact(current => {
          if (current.cursor !== query.afterRevision || current.watermark !== (query.untilRevision ?? null)) return current
          for (const item of page.items) { const key = sourceBookKey(item); current.rows[key] = observe(current.rows[key], item) }
          current.cursor = page.nextAfterRevision; current.watermark = page.hasMore ? page.watermark : null; if (!page.hasMore) current.ready = true; return current
        })
        if (!page.hasMore) break
      }
      lastError = undefined
    } catch (error) {
      if (!closed && error instanceof SourceFavoriteError && error.code === 'limit') { try { await store.transact(current => ({ ...current, retryAfterUntil: Math.max(current.retryAfterUntil, Date.now() + (error.retryAfterSeconds ?? 60) * 1000) })) } catch { /* Keep the original error visible. */ } }
      fail(error)
    } finally { loading = false; if (!closed) { try { await load() } catch (error) { fail(error) } publish() } } })().finally(() => { syncing = null })
    return syncing
  }
  function edit(work: (current: SourceFavoriteJournal) => SourceFavoriteJournal): Promise<boolean> {
    if (closed) return Promise.resolve(false)
    const pending = (async () => { try { await store.transact(work); if (!closed) { lastError = undefined; await load() } return true } catch (error) { fail(error); return false } })().finally(() => edits.delete(pending))
    edits.add(pending); return pending
  }
  async function update(identity: SourceBookIdentity, change: SourceFavoriteChange, expected: SourceFavoriteRow | null) {
    let id: SourceBookIdentity, desired: SourceFavoriteChange
    try { id = validateSourceBookIdentity(identity); desired = validateSourceFavoriteChange(change) } catch { fail(new SourceFavoriteError('invalid-request')); return false }
    const key = sourceBookKey(id)
    return edit(current => {
      const existing = current.rows[key] ?? null
      if (!current.ready || JSON.stringify(existing) !== JSON.stringify(expected)) throw new SourceFavoriteError('choice-stale')
      if (existing?.conflict) throw new SourceFavoriteError('choice-stale')
      if (existing && favoriteRowChange(existing) && sameSourceFavoriteChange(favoriteRowChange(existing)!, desired)) return current
      current.rows[key] = { remote: existing?.remote ?? null, pending: existing?.pending ?? mutation(id, desired, existing?.remote?.version ?? 0), queued: existing?.pending && !sameSourceFavoriteChange(existing.pending, desired) ? desired : null, conflict: null }; return current
    })
  }
  async function choose(identity: SourceBookIdentity, side: 'local' | 'server', expected: SourceFavoriteRow) {
    const key = sourceBookKey(identity)
    return edit(current => { const existing = current.rows[key]; if (!existing?.conflict || JSON.stringify(existing) !== JSON.stringify(expected)) throw new SourceFavoriteError('choice-stale')
      const remote = existing.conflict; current.rows[key] = side === 'server' ? { remote, pending: null, queued: null, conflict: null } : { remote, pending: mutation(identity, favoriteRowChange(existing)!, remote.version), queued: null, conflict: null }; return current
    })
  }
  return { snapshot, update, choose, refresh: () => run(true), drain: () => run(),
    subscribe(listener: (state: SourceFavoriteState) => void) { listeners.add(listener); return () => { listeners.delete(listener) } },
    close() { closed = true; abort.abort(); unsubscribe(); listeners.clear(); return Promise.allSettled([...edits]).then(() => undefined) },
  }
}
export type SourceFavoriteController = ReturnType<typeof createSourceFavoriteController>
