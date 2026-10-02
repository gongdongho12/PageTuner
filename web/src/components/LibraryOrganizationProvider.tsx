import { createContext, useContext, useEffect, useRef, useState, type ReactNode } from 'react'
import { createLibraryOrganizationStore, listLibraryOrganizationRecords } from '../lib/libraryOrganizationStore'
import { createLibraryOrganizationController, type LibraryOrganizationState } from '../lib/libraryOrganizationSync'
import { LibraryOrganizationError, type LibraryOrganizationClient, type LibraryOrganizationScope } from '../lib/libraryOrganizationApi'
import { validReadingProgressIdentity } from '../lib/readingProgressApi'

type Controller = ReturnType<typeof createLibraryOrganizationController>
const keyFor = (scope: LibraryOrganizationScope) => `${scope.kind}:${scope.recordId.toLowerCase()}`
const empty = () => ({ folder: '', tags: [] as string[], favorite: false })
const retryable = (state: LibraryOrganizationState) => !state.errorCode || ['network', 'timeout', 'server', 'limit', 'choice-stale'].includes(state.errorCode)

/** Account-owned controllers outlive editors, so closing a screen cannot strand pending edits. */
export class OrganizationRegistry {
  private entries = new Map<string, { controller: Controller; store: ReturnType<typeof createLibraryOrganizationStore>; readers: number; ready: Promise<void> }>()
  private closed = false
  private draining = false
  private offset = 0
  private restoring: Promise<void> | undefined
  private restored = false
  private guardedApi: LibraryOrganizationClient | null
  constructor(readonly username: string, readonly api: LibraryOrganizationClient | null) {
    this.guardedApi = api ? { ...api,
      get: async (scope, signal) => { await this.restore(); return api.get(scope, signal) },
      put: async (scope, mutation, signal) => { await this.restore(true); return api.put(scope, mutation, signal) },
    } : null
  }
  private restore(fresh = false) {
    if (this.restored && !fresh) return Promise.resolve()
    if (!this.restoring) this.restoring = (async () => {
      const records = await listLibraryOrganizationRecords(this.username)
      this.api?.deferWritesUntil?.(records.reduce((maximum, item) => Math.max(maximum, item.record.retryAfterUntil ?? 0), 0))
      this.restored = true
    })().catch(() => { throw new LibraryOrganizationError('storage') }).finally(() => { this.restoring = undefined })
    return this.restoring
  }
  private entry(scope: LibraryOrganizationScope) {
    const key = keyFor(scope)
    let entry = this.entries.get(key)
    if (!entry) {
      const store = createLibraryOrganizationStore(this.username, scope)
      const controller = createLibraryOrganizationController({ api: this.guardedApi, store, scope })
      entry = { store, controller, readers: 0, ready: controller.start(empty()).catch(() => undefined) }
      this.entries.set(key, entry)
    }
    return entry
  }
  open(scope: LibraryOrganizationScope) {
    const entry = this.entry(scope)
    entry.readers++
    if (entry.readers === 1) void entry.ready.then(() => { if (!this.closed && retryable(entry.controller.snapshot())) return entry.controller.refresh() }).catch(() => undefined)
    return entry.controller
  }
  release(scope: LibraryOrganizationScope) {
    const entry = this.entries.get(keyFor(scope))
    if (entry) entry.readers = Math.max(0, entry.readers - 1)
  }
  async drain() {
    if (!this.api || this.closed || this.draining) return
    this.draining = true
    try {
      const saved = await listLibraryOrganizationRecords(this.username)
      this.api.deferWritesUntil?.(saved.reduce((maximum, item) => Math.max(maximum, item.record.retryAfterUntil ?? 0), 0))
      const records = saved.filter(item => item.record.pending && !item.record.conflict)
      const batch = Array.from({ length: Math.min(records.length, 20) }, (_, index) => records[(this.offset + index) % records.length])
      this.offset = records.length ? (this.offset + batch.length) % records.length : 0
      const visited = new Set<string>()
      for (const item of batch) {
        if (this.closed) return
        const entry = this.entry(item.scope)
        await entry.ready
        visited.add(keyFor(item.scope))
        if (!this.closed && retryable(entry.controller.snapshot())) await entry.controller.refresh()
      }
      for (const [key, entry] of [...this.entries].filter(([, value]) => value.readers > 0).slice(0, 20)) {
        if (this.closed) return
        if (!visited.has(key) && retryable(entry.controller.snapshot())) await entry.controller.refresh()
      }
    } catch { /* A journal failure never discards pending changes; the editor exposes its storage error. */ }
    finally { this.draining = false }
  }
  close() {
    this.closed = true
    this.entries.forEach(entry => { void entry.controller.close().finally(() => entry.store.close()) })
    this.entries.clear()
  }
}

const OrganizationContext = createContext<OrganizationRegistry | null>(null)
export function LibraryOrganizationProvider({ username, client, children }: { username: string; client: LibraryOrganizationClient | null; children: ReactNode }) {
  const [registry, setRegistry] = useState<OrganizationRegistry | null>(null)
  useEffect(() => {
    if (!username) { setRegistry(null); return }
    const next = new OrganizationRegistry(username, client)
    setRegistry(next)
    const retry = () => { void next.drain() }
    retry()
    const timer = setInterval(retry, 30_000)
    window.addEventListener('online', retry); window.addEventListener('focus', retry)
    return () => { clearInterval(timer); window.removeEventListener('online', retry); window.removeEventListener('focus', retry); next.close() }
  }, [username, client])
  const current = registry?.username === username && registry.api === client ? registry : null
  return <OrganizationContext.Provider value={current}>{children}</OrganizationContext.Provider>
}

export function useLibraryOrganization(namespace: string, scope: LibraryOrganizationScope | undefined) {
  const registry = useContext(OrganizationContext)
  const available = !!scope && validReadingProgressIdentity(scope) && registry?.username === namespace
  const key = available ? `${namespace}:${keyFor(scope!)}` : ''
  const controller = useRef<Controller | null>(null)
  const [value, setValue] = useState<{ key: string; registry: OrganizationRegistry; state: LibraryOrganizationState }>()
  useEffect(() => {
    if (!available || !scope || !registry) { controller.current = null; return }
    const session = registry.open(scope)
    controller.current = session
    const update = (state: LibraryOrganizationState) => setValue({ key, registry, state })
    update(session.snapshot())
    const unsubscribe = session.subscribe(update)
    return () => { unsubscribe(); registry.release(scope); if (controller.current === session) controller.current = null }
  }, [key, registry])
  const current = value?.key === key && value.registry === registry ? value : undefined
  return { available, online: !!registry?.api, state: current?.state, controller: current ? controller.current : null }
}
