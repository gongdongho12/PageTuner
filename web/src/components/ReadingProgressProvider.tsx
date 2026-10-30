import { createContext, useContext, useEffect, useRef, useState, type ReactNode } from 'react'
import { createReadingProgressStore } from '../lib/readingProgressStore'
import { createReadingProgressController, type ReadingProgressState } from '../lib/readingProgressSync'
import { ReadingProgressError, validReadingProgressIdentity, type ReadingProgressClient, type ReadingProgressIdentity, type ReadingProgressAnchor, type ReadingProgressView } from '../lib/readingProgressApi'
import type { ReadingDocument } from '../lib/readingDocument'
import { captureFreshReadingProgressIdentity, captureFreshReadingProgressView, sameFreshReadingProgressView,
  readingProgressExportErrors, type FreshReadingProgressReader } from '../lib/portableReadingProgressExport'
import { useExportSession } from './useExportSession'

type Controller = ReturnType<typeof createReadingProgressController>
const keyFor = (identity: ReadingProgressIdentity) => `${identity.kind}:${identity.recordId}`

/** A reader can close while its durable pending position continues syncing in this account session. */
export class ProgressRegistry {
  readonly store: ReturnType<typeof createReadingProgressStore>
  private entries = new Map<string, { controller: Controller; started: boolean; readers: number }>()
  private closed = false
  private draining = false
  private drainOffset = 0
  private refreshOpenReaders = false
  constructor(readonly username: string, readonly api: ReadingProgressClient | null) {
    this.store = createReadingProgressStore(username)
  }
  private entry(identity: ReadingProgressIdentity) {
    const key = keyFor(identity)
    let entry = this.entries.get(key)
    if (!entry) {
      entry = { controller: createReadingProgressController({ api: this.api, store: this.store, identity }), started: false, readers: 0 }
      this.entries.set(key, entry)
    }
    return entry
  }
  open(identity: ReadingProgressIdentity, fallback: ReadingProgressAnchor | null, explicitNavigation = false) {
    const entry = this.entry(identity)
    entry.readers++
    if (explicitNavigation && fallback) void entry.controller.move(fallback).catch(() => undefined)
    const ready = entry.started ? entry.controller.refresh() : entry.controller.start(fallback)
    entry.started = true
    // The controller reports failures through its snapshot. They never prevent local reading.
    void ready.catch(() => undefined)
    return entry.controller
  }
  release(identity: ReadingProgressIdentity) {
    const entry = this.entries.get(keyFor(identity))
    if (entry) entry.readers = Math.max(0, entry.readers - 1)
  }
  /** Never starts a controller or flushes a pending reader. Observations live only in this lease. */
  freshReader(current: () => boolean): FreshReadingProgressReader {
    const observed = new Map<string, ReadingProgressView>(), journalHighwater = new Map<string, ReadingProgressView>()
    let terminal: 'stale' | 'response' | undefined
    const response = (): never => { terminal = 'response'; throw new Error(readingProgressExportErrors.response) }
    const assertCurrent = () => {
      if (terminal) throw new Error(readingProgressExportErrors[terminal])
      let active = false
      try { active = !this.closed && current() } catch { /* A failed authority check retires this lease. */ }
      if (!active) { terminal = 'stale'; throw new Error(readingProgressExportErrors.stale) }
      if (!this.api) throw new Error(readingProgressExportErrors.offline)
    }
    const assert = (signal?: AbortSignal) => {
      if (signal?.aborted && !terminal) terminal = 'stale'
      assertCurrent()
    }
    const run = async <T,>(signal: AbortSignal | undefined, action: () => Promise<T>): Promise<T> => {
      assert(signal)
      const abort = () => { terminal ??= 'stale' }
      signal?.addEventListener('abort', abort, { once: true })
      try { return await action() }
      catch (error) { assert(signal); throw error }
      finally { signal?.removeEventListener('abort', abort) }
    }
    const noRegression = (known: ReadingProgressView | undefined, incoming: ReadingProgressView) => {
      if (known && (known.version > incoming.version || known.version === incoming.version && !sameFreshReadingProgressView(known, incoming))) response()
    }
    const captureView = (value: unknown, identity: ReadingProgressIdentity, alias = false) => {
      try { return captureFreshReadingProgressView(value, identity, alias) } catch { return response() }
    }
    const inspectJournal = async (identity: ReadingProgressIdentity, signal?: AbortSignal, incoming?: ReadingProgressView) => {
      assert(signal)
      let rows
      try { rows = await this.store.snapshotAliases({ ...identity }) }
      catch (error) { assert(signal); throw error }
      assert(signal)
      const key = keyFor(identity)
      for (const row of rows) {
        if (!row.remote) continue
        const remote = captureView(row.remote, identity, true), previous = journalHighwater.get(key)
        if (previous?.version === remote.version && !sameFreshReadingProgressView(previous, remote)) response()
        if (!previous || remote.version > previous.version) journalHighwater.set(key, remote)
      }
      if (rows.some(row => row.pending || row.queued || row.conflict)) throw new Error(readingProgressExportErrors.pending)
      const latest = incoming ?? observed.get(key)
      if (latest) {
        noRegression(journalHighwater.get(key), latest)
        if (incoming) noRegression(observed.get(key), incoming)
      }
    }
    return {
      assertCurrent,
      checkReady: (input, signal) => run(signal, async () => {
        assert(signal)
        const identity = captureFreshReadingProgressIdentity(input)
        if (!observed.has(keyFor(identity))) return response()
        await inspectJournal(identity, signal)
        assert(signal)
        const latest = observed.get(keyFor(identity))
        if (latest) noRegression(journalHighwater.get(keyFor(identity)), latest)
      }),
      read: (input, signal) => run(signal, async () => {
        assert(signal)
        const identity = captureFreshReadingProgressIdentity(input), key = keyFor(identity)
        await inspectJournal(identity, signal)
        assert(signal)
        let raw: ReadingProgressView
        try { raw = await this.api!.get({ ...identity }, signal) }
        catch (error) {
          assert(signal)
          if (error instanceof ReadingProgressError && error.code === 'invalid-response') return response()
          if (error instanceof ReadingProgressError && error.code === 'aborted') {
            terminal = 'stale'
            assert(signal)
          }
          throw error
        }
        assert(signal)
        const view = captureView(raw, identity)
        noRegression(journalHighwater.get(key), view)
        noRegression(observed.get(key), view)
        await inspectJournal(identity, signal, view)
        assert(signal)
        // Another request can finish while the final journal transaction is awaiting completion.
        noRegression(journalHighwater.get(key), view)
        noRegression(observed.get(key), view)
        observed.set(key, structuredClone(view))
        return structuredClone(view)
      }),
    }
  }
  async drain(refreshOpenReaders = false) {
    this.refreshOpenReaders ||= refreshOpenReaders
    if (!this.api || this.closed || this.draining) return
    this.draining = true
    try {
      const pending = await this.store.listPending()
      const batch = Array.from({ length: Math.min(pending.length, 20) }, (_, index) => pending[(this.drainOffset + index) % pending.length])
      this.drainOffset = pending.length ? (this.drainOffset + batch.length) % pending.length : 0
      for (const identity of batch) {
        if (this.closed) return
        const entry = this.entry(identity)
        if (!entry.started) { entry.started = true; await entry.controller.start() }
        else await entry.controller.flush()
      }
      // Reconnecting or returning to the app also restores changes from another device.
      const refreshActive = this.refreshOpenReaders
      this.refreshOpenReaders = false
      for (const entry of [...this.entries.values()].filter(entry => entry.readers > 0 &&
        (refreshActive && !entry.controller.snapshot().errorCode ||
          ['network', 'timeout', 'server'].includes(entry.controller.snapshot().errorCode ?? ''))).slice(0, 20)) {
        if (this.closed) return
        await entry.controller.refresh()
      }
    } catch { /* Each failed position remains in its durable queue for the next attempt. */ }
    finally { this.draining = false }
  }
  close() {
    this.closed = true
    this.entries.forEach(entry => entry.controller.close())
    this.entries.clear()
  }
}

const ProgressContext = createContext<ProgressRegistry | null>(null)

/** Retained callbacks can only issue readers for their own committed account/origin lease. */
export function useFreshReadingProgress(namespace: string) {
  const registry = useContext(ProgressContext)
  const active = registry?.username === namespace ? registry : null
  const lease = useExportSession(active, window.location.origin)
  return { available: !!active?.api && !!lease, session: active,
    begin: () => {
      if (!lease) throw new Error(readingProgressExportErrors.stale)
      const current = lease.issue(readingProgressExportErrors.stale)
      if (!active?.api) throw new Error(readingProgressExportErrors.offline)
      return active.freshReader(current)
    },
  }
}

export function ReadingProgressProvider({ username, client, children }: { username: string; client: ReadingProgressClient | null; children: ReactNode }) {
  const [registry, setRegistry] = useState<ProgressRegistry | null>(null)
  useEffect(() => {
    if (!username) { setRegistry(null); return }
    const next = new ProgressRegistry(username, client)
    setRegistry(next)
    const retry = () => { void next.drain() }
    const reconnect = () => { void next.drain(true) }
    retry()
    const interval = setInterval(retry, 30_000)
    window.addEventListener('online', reconnect)
    window.addEventListener('focus', reconnect)
    return () => { clearInterval(interval); window.removeEventListener('online', reconnect); window.removeEventListener('focus', reconnect); next.close() }
  }, [username, client])
  const current = registry?.username === username && registry.api === client ? registry : null
  return <ProgressContext.Provider value={current}>{children}</ProgressContext.Provider>
}

export function useReadingProgress(document: ReadingDocument, namespace: string | undefined, fallback: ReadingProgressAnchor | undefined, enabled: boolean, explicitNavigation = false) {
  const registry = useContext(ProgressContext)
  const identity = enabled && namespace === registry?.username && document.serverProgress && validReadingProgressIdentity(document.serverProgress) ? document.serverProgress : undefined
  const key = identity ? `${namespace}:${keyFor(identity)}` : ''
  const controller = useRef<Controller | null>(null)
  const [value, setValue] = useState<{ key: string; registry: ProgressRegistry; session: Controller; state: ReadingProgressState }>()
  useEffect(() => {
    if (!identity || !registry) { controller.current = null; return }
    const session = registry.open(identity, fallback ?? null, explicitNavigation)
    controller.current = session
    const previousRestoration = session.snapshot().restoration?.sequence ?? 0
    const update = (state: ReadingProgressState) => setValue({ key, registry, session,
      state: state.restoration && state.restoration.sequence <= previousRestoration ? { ...state, restoration: undefined } : state })
    update(session.snapshot())
    const unsubscribe = session.subscribe(update)
    return () => { unsubscribe(); registry.release(identity); if (controller.current === session) controller.current = null }
  }, [key, registry])
  const state = value?.key === key && value.registry === registry ? value.state : undefined
  return {
    available: !!identity,
    online: !!registry?.api,
    state,
    restorationSource: state ? value?.session : undefined,
    move: (anchor: ReadingProgressAnchor) => { void controller.current?.move(anchor).catch(() => undefined) },
    refresh: () => { void controller.current?.refresh().catch(() => undefined) },
    chooseLocal: () => { void controller.current?.chooseLocal().catch(() => undefined) },
    chooseServer: () => { void controller.current?.chooseServer().catch(() => undefined) },
  }
}
