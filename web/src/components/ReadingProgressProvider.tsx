import { createContext, useContext, useEffect, useRef, useState, type ReactNode } from 'react'
import { createReadingProgressStore } from '../lib/readingProgressStore'
import { createReadingProgressController, type ReadingProgressState } from '../lib/readingProgressSync'
import { validReadingProgressIdentity, type ReadingProgressClient, type ReadingProgressIdentity, type ReadingProgressAnchor } from '../lib/readingProgressApi'
import type { ReadingDocument } from '../lib/readingDocument'

type Controller = ReturnType<typeof createReadingProgressController>
const keyFor = (identity: ReadingProgressIdentity) => `${identity.kind}:${identity.recordId}`

/** A reader can close while its durable pending position continues syncing in this account session. */
class ProgressRegistry {
  readonly store: ReturnType<typeof createReadingProgressStore>
  private entries = new Map<string, { controller: Controller; started: boolean; readers: number }>()
  private closed = false
  private draining = false
  private drainOffset = 0
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
  async drain() {
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
      // A reader with a failed initial GET has no outgoing mutation to enumerate.
      for (const entry of [...this.entries.values()].filter(entry => entry.readers > 0 &&
        ['network', 'timeout', 'server'].includes(entry.controller.snapshot().errorCode ?? '')).slice(0, 20)) {
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
export function ReadingProgressProvider({ username, client, children }: { username: string; client: ReadingProgressClient | null; children: ReactNode }) {
  const [registry, setRegistry] = useState<ProgressRegistry | null>(null)
  useEffect(() => {
    if (!username) { setRegistry(null); return }
    const next = new ProgressRegistry(username, client)
    setRegistry(next)
    const retry = () => { void next.drain() }
    retry()
    const interval = setInterval(retry, 30_000)
    window.addEventListener('online', retry)
    return () => { clearInterval(interval); window.removeEventListener('online', retry); next.close() }
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
