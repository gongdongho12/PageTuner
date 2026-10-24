import { createContext, useContext, useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react'
import { createLibraryOrganizationStore, listLibraryOrganizationRecords, type LibraryOrganizationRecord } from '../lib/libraryOrganizationStore'
import { createLibraryOrganizationController, type LibraryOrganizationState } from '../lib/libraryOrganizationSync'
import { LibraryOrganizationError, type LibraryOrganizationClient, type LibraryOrganizationScope } from '../lib/libraryOrganizationApi'
import { validReadingProgressIdentity } from '../lib/readingProgressApi'
import { sameLibraryOrganization, sameLibraryOrganizationScope, sameLibraryOrganizationView, validateLibraryOrganizationScope, validateLibraryOrganizationView, type LibraryOrganizationView } from '../lib/libraryOrganizationApi'
import { organizationExportErrors, type FreshLibraryOrganizationReader } from '../lib/portableOrganizationExport'
import type { OrganizationAdoptionSession } from '../lib/portableOrganizationAdoption'
import { useExportSession } from './useExportSession'

type Controller = ReturnType<typeof createLibraryOrganizationController>
const keyFor = (scope: LibraryOrganizationScope) => `${scope.kind}:${scope.recordId.toLowerCase()}`
const empty = () => ({ folder: '', tags: [] as string[], favorite: false })
const retryable = (state: LibraryOrganizationState) => !state.errorCode || ['network', 'timeout', 'server', 'limit', 'choice-stale'].includes(state.errorCode)

/** Account-owned controllers outlive editors, so closing a screen cannot strand pending edits. */
export class OrganizationRegistry {
  private entries = new Map<string, { controller: Controller; store: ReturnType<typeof createLibraryOrganizationStore>; readers: number; ready: Promise<void> }>()
  private adoptionSessions = new Set<OrganizationAdoptionSession>()
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
  /** Fresh exports observe the account without starting an outbox controller. */
  freshReader(current: () => boolean): FreshLibraryOrganizationReader {
    const observed = new Map<string, LibraryOrganizationView>()
    let expired = false
    const assertCurrent = () => {
      if (expired || this.closed || !current()) {
        expired = true
        throw new Error(organizationExportErrors.stale)
      }
      if (!this.api) throw new Error(organizationExportErrors.offline)
    }
    const assert = (signal?: AbortSignal) => {
      if (signal?.aborted) expired = true
      assertCurrent()
    }
    const assertNoRegression = (known: LibraryOrganizationView | null | undefined, incoming: LibraryOrganizationView) => {
      if (known && (known.version > incoming.version || known.version === incoming.version && !sameLibraryOrganizationView(known, incoming))) {
        throw new Error(organizationExportErrors.response)
      }
    }
    const inspectJournal = async (scope: LibraryOrganizationScope, signal?: AbortSignal, incoming?: LibraryOrganizationView) => {
      assert(signal)
      const store = createLibraryOrganizationStore(this.username, scope)
      try {
        const record = await store.snapshot()
        assert(signal)
        if (record?.pending || record?.queued || record?.conflict) throw new Error(organizationExportErrors.pending)
        const previous = observed.get(keyFor(scope))
        if (incoming) {
          assertNoRegression(previous, incoming)
          assertNoRegression(record?.remote, incoming)
        } else if (previous) assertNoRegression(record?.remote, previous)
        return record?.remote ? structuredClone(record.remote) : null
      } finally { store.close() }
    }
    return {
      assertCurrent,
      checkReady: async (input, signal) => {
        assert(signal)
        await inspectJournal(validateLibraryOrganizationScope(input), signal)
      },
      read: async (input, signal) => {
        assert(signal)
        const scope = validateLibraryOrganizationScope(input)
        const before = await inspectJournal(scope, signal)
        assert(signal)
        let response: LibraryOrganizationView
        try { response = await this.api!.get(scope, signal) }
        catch (error) { assert(signal); throw error }
        assert(signal)
        const view = validateLibraryOrganizationView(response)
        if (!sameLibraryOrganizationScope(scope, view)) throw new Error(organizationExportErrors.response)
        assertNoRegression(before, view)
        // Compare the previous observation before replacing it, including overlapping GETs.
        assertNoRegression(observed.get(keyFor(scope)), view)
        await inspectJournal(scope, signal, view)
        assert(signal)
        assertNoRegression(observed.get(keyFor(scope)), view)
        observed.set(keyFor(scope), structuredClone(view))
        return structuredClone(view)
      },
    }
  }
  /** Comparing a ZIP snapshot has no initialization or outbox side effects. */
  adoptionSession(current: () => boolean): OrganizationAdoptionSession {
    let expired = false
    const resources = new Set<() => void>()
    const observed = new Map<string, LibraryOrganizationView>()
    const assertCurrent = () => {
      if (expired || this.closed || !current()) {
        expired = true
        throw new LibraryOrganizationError('choice-stale')
      }
      if (!this.api) throw new LibraryOrganizationError('network')
    }
    const assert = (signal?: AbortSignal) => {
      if (signal?.aborted) expired = true
      assertCurrent()
    }
    const reader = this.freshReader(() => !expired && !this.closed && current())
    const clean = (record: LibraryOrganizationRecord | null) => {
      if (record?.pending || record?.queued || record?.conflict) throw new LibraryOrganizationError('choice-stale')
    }
    const sameRecord = (a: LibraryOrganizationRecord | null, b: LibraryOrganizationRecord | null) => {
      if (a === null || b === null) return a === b
      return a.enabled === b.enabled && sameLibraryOrganization(a.local, b.local) && sameLibraryOrganizationView(a.remote, b.remote)
        && a.pending === null && b.pending === null && a.queued === null && b.queued === null && a.conflict === null && b.conflict === null
        && a.retryAfterUntil === b.retryAfterUntil
    }
    const snapshot = async (scope: LibraryOrganizationScope, signal?: AbortSignal) => {
      assert(signal)
      const store = createLibraryOrganizationStore(this.username, scope)
      resources.add(store.close)
      try { const record = await store.snapshot(); assert(signal); clean(record); return record }
      finally { resources.delete(store.close); store.close() }
    }
    const close = () => {
      expired = true
      const releases = [...resources]
      resources.clear()
      for (const release of releases) release()
      this.adoptionSessions.delete(session)
    }
    const run = async <T,>(signal: AbortSignal | undefined, action: (signal: AbortSignal) => Promise<T>): Promise<T> => {
      assert(signal)
      const controller = new AbortController(), abort = () => { controller.abort(); close() }
      resources.add(abort)
      signal?.addEventListener('abort', abort, { once: true })
      try { return await action(controller.signal) }
      catch (error) {
        if (error instanceof LibraryOrganizationError && error.code === 'invalid-response' || error instanceof Error && error.message === organizationExportErrors.response) close()
        throw error
      } finally { signal?.removeEventListener('abort', abort); resources.delete(abort) }
    }
    const read = (input: LibraryOrganizationScope, signal?: AbortSignal) => run(signal, async activeSignal => {
      const scope = validateLibraryOrganizationScope(input), before = await snapshot(scope, activeSignal)
      const remote = await reader.read(scope, activeSignal)
      assert(activeSignal)
      const after = await snapshot(scope, activeSignal)
      if (!sameRecord(before, after)) throw new LibraryOrganizationError('choice-stale')
      const previous = observed.get(keyFor(scope))
      if (previous && (previous.version > remote.version || previous.version === remote.version && !sameLibraryOrganizationView(previous, remote))) throw new LibraryOrganizationError('invalid-response')
      observed.set(keyFor(scope), structuredClone(remote))
      return structuredClone({ record: after, remote })
    })
    const session: OrganizationAdoptionSession = {
      assertCurrent,
      read,
      adopt: (input, value, reviewed, checkBeforeWrite, guard, signal) => run(signal, async activeSignal => {
        const scope = validateLibraryOrganizationScope(input)
        const comparison = structuredClone(reviewed), organization = structuredClone(value)
        let rejected: unknown
        const check = () => {
          assert(activeSignal)
          try { guard() } catch (error) { rejected = error; throw error }
          assert(activeSignal)
          const latest = observed.get(keyFor(scope))
          if (latest && latest.version > comparison.remote.version) throw new LibraryOrganizationError('choice-stale')
          if (latest && latest.version === comparison.remote.version && !sameLibraryOrganizationView(latest, comparison.remote)) throw new LibraryOrganizationError('invalid-response')
        }
        check()
        const store = createLibraryOrganizationStore(this.username, scope)
        resources.add(store.close)
        const checkCanonical = async () => { check(); try { await checkBeforeWrite() } catch (error) { rejected = error; throw error } check() }
        // Never start this actor: it must not initialize or flush unrelated device records.
        const actor = createLibraryOrganizationController({ scope,
          api: { ...this.api!, get: async (target, requestSignal) => {
            await checkCanonical()
            const latest = await read(target, requestSignal).catch(error => { rejected = error; throw error })
            await checkCanonical()
            if (!sameRecord(comparison.record, latest.record)) throw new LibraryOrganizationError('choice-stale')
            return latest.remote
          }, put: async () => { throw new LibraryOrganizationError('aborted') } },
          store: { ...store, transactNullable: async (reducer, finalGuard) => {
            await checkCanonical()
            return store.transactNullable(reducer, () => { check(); finalGuard?.(); check() })
          } },
        })
        const closeActor = () => { void actor.close() }
        resources.add(closeActor)
        let accepted = false
        try {
          accepted = await actor.adoptSnapshot(organization, comparison, check)
          if (!accepted) throw rejected ?? new LibraryOrganizationError(actor.snapshot().errorCode ?? 'choice-stale')
        } finally {
          // Close the standalone actor before the account registry resumes its normal authorized outbox.
          await actor.close()
          resources.delete(closeActor); resources.delete(store.close); store.close()
        }
        // A completed IDB commit cannot be rolled back by a later close or account transition.
        if (accepted && !this.closed) void this.drain()
      }),
      close,
    }
    assertCurrent()
    this.adoptionSessions.add(session)
    return session
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
    for (const session of [...this.adoptionSessions]) session.close()
    this.entries.forEach(entry => { void entry.controller.close().finally(() => entry.store.close()) })
    this.entries.clear()
  }
}

const OrganizationContext = createContext<OrganizationRegistry | null>(null)

/** A fresh GET may only be issued by the committed screen/account lease that exposed this callback. */
export function useFreshLibraryOrganization(namespace: string) {
  const registry = useContext(OrganizationContext)
  const active = registry?.username === namespace ? registry : null
  const lease = useExportSession(active, window.location.origin)
  return { available: !!active?.api && !!lease, session: active,
    begin: () => {
      if (!lease) throw new Error(organizationExportErrors.stale)
      const current = lease.issue(organizationExportErrors.stale)
      if (!active?.api) throw new Error(organizationExportErrors.offline)
      return active.freshReader(current)
    },
  }
}

/** Issuance belongs to one committed account/origin lease; retained callbacks cannot borrow a later lease. */
export function usePortableOrganizationAdoption(namespace: string) {
  const registry = useContext(OrganizationContext)
  const active = registry?.username === namespace ? registry : null
  const lease = useExportSession(active, window.location.origin)
  const owned = useRef(new Set<OrganizationAdoptionSession>())
  useLayoutEffect(() => {
    const sessions = owned.current
    return () => { for (const session of sessions) session.close(); sessions.clear() }
  }, [lease])
  return { available: !!active?.api && !!lease, session: active,
    begin: (selection: () => boolean) => {
      if (!lease) throw new LibraryOrganizationError('choice-stale')
      const committed = lease.issue()
      if (!active?.api) throw new LibraryOrganizationError('network')
      const session = active.adoptionSession(() => committed() && selection())
      owned.current.add(session)
      return session
    },
  }
}

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
