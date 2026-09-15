import { createContext, useContext, useEffect, useRef, useState, type ReactNode } from 'react'
import { createReadingNoteStore } from '../lib/readingNoteStore'
import { createReadingNoteController, type ReadingNoteState } from '../lib/readingNoteSync'
import type { ReadingNoteClient, ReadingNoteIdentity } from '../lib/readingNoteApi'
import { validReadingProgressIdentity } from '../lib/readingProgressApi'
import type { ReadingDocument } from '../lib/readingDocument'

type Controller = ReturnType<typeof createReadingNoteController>
const keyFor = (identity: ReadingNoteIdentity) => `${identity.kind}:${identity.recordId}`

/** Keep durable note mutations syncing after the reader that created them closes. */
class NoteRegistry {
  readonly store: ReturnType<typeof createReadingNoteStore>
  private entries = new Map<string, { controller: Controller; started: boolean; readers: number }>()
  private closed = false
  private draining = false
  private drainOffset = 0
  private refreshOpenReaders = false
  constructor(readonly username: string, readonly api: ReadingNoteClient | null) { this.store = createReadingNoteStore(username) }
  private entry(identity: ReadingNoteIdentity) {
    const key = keyFor(identity)
    let entry = this.entries.get(key)
    if (!entry) {
      entry = { controller: createReadingNoteController({ api: this.api, store: this.store, identity }), started: false, readers: 0 }
      this.entries.set(key, entry)
    }
    return entry
  }
  open(document: ReadingDocument, identity: ReadingNoteIdentity) {
    const entry = this.entry(identity)
    entry.readers++
    // Additional consumers observe the same controller without restarting its request.
    if (!entry.started) { entry.started = true; void entry.controller.start(document).catch(() => undefined) }
    else if (entry.readers === 1) void entry.controller.refresh().catch(() => undefined)
    return entry.controller
  }
  release(identity: ReadingNoteIdentity) {
    const entry = this.entries.get(keyFor(identity))
    if (entry) entry.readers = Math.max(0, entry.readers - 1)
  }
  async drain(refreshOpenReaders = false) {
    this.refreshOpenReaders ||= refreshOpenReaders
    if (!this.api || this.closed || this.draining) return
    this.draining = true
    try {
      const pending = await this.store.listPending()
      const batch = Array.from({ length: Math.min(pending.length, 20) }, (_, i) => pending[(this.drainOffset + i) % pending.length])
      this.drainOffset = pending.length ? (this.drainOffset + batch.length) % pending.length : 0
      for (const identity of batch) {
        if (this.closed) return
        await this.entry(identity).controller.flush()
      }
      const refreshActive = this.refreshOpenReaders
      this.refreshOpenReaders = false
      for (const entry of [...this.entries.values()].filter(entry => entry.readers > 0 &&
        (refreshActive && !entry.controller.snapshot().errorCode ||
          entry.controller.snapshot().pullPending && !entry.controller.snapshot().errorCode ||
          ['network', 'timeout', 'server'].includes(entry.controller.snapshot().errorCode ?? ''))).slice(0, 20)) {
        if (this.closed) return
        await entry.controller.refresh()
      }
    } catch { /* Mutations remain in the durable queue; controllers expose their errors. */ }
    finally { this.draining = false }
  }
  close() { this.closed = true; this.entries.forEach(entry => entry.controller.close()); this.entries.clear() }
}

const NoteContext = createContext<NoteRegistry | null>(null)
export function ReadingNoteProvider({ username, client, children }: { username: string; client: ReadingNoteClient | null; children: ReactNode }) {
  const [registry, setRegistry] = useState<NoteRegistry | null>(null)
  useEffect(() => {
    if (!username) { setRegistry(null); return }
    const next = new NoteRegistry(username, client)
    setRegistry(next)
    const retry = () => { void next.drain(true) }, reconnect = () => { void next.drain(true) }
    retry()
    const interval = setInterval(retry, 30_000)
    window.addEventListener('online', reconnect); window.addEventListener('focus', reconnect)
    return () => { clearInterval(interval); window.removeEventListener('online', reconnect); window.removeEventListener('focus', reconnect); next.close() }
  }, [username, client])
  const current = registry?.username === username && registry.api === client ? registry : null
  return <NoteContext.Provider value={current}>{children}</NoteContext.Provider>
}

export function useReadingNoteSync(document: ReadingDocument, namespace: string | undefined, enabled = true) {
  const registry = useContext(NoteContext)
  const identity = enabled && namespace === registry?.username && document.serverProgress && validReadingProgressIdentity(document.serverProgress) ? document.serverProgress : undefined
  const key = identity ? `${namespace}:${keyFor(identity)}` : ''
  const controller = useRef<Controller | null>(null)
  const [value, setValue] = useState<{ key: string; registry: NoteRegistry; state: ReadingNoteState }>()
  useEffect(() => {
    if (!identity || !registry) { controller.current = null; return }
    const session = registry.open(document, identity)
    controller.current = session
    const update = (state: ReadingNoteState) => setValue({ key, registry, state })
    update(session.snapshot())
    const unsubscribe = session.subscribe(update)
    return () => { unsubscribe(); registry.release(identity); if (controller.current === session) controller.current = null }
  }, [key, registry])
  return {
    available: !!identity,
    online: !!registry?.api,
    state: value?.key === key && value.registry === registry ? value.state : undefined,
    refresh: () => { void controller.current?.refresh().catch(() => undefined) },
    resolve: (noteId: string, choice: 'local' | 'server', expected: ReadingNoteState['conflicts'][number]) => { void controller.current?.resolve(noteId, choice, expected).catch(() => undefined) },
  }
}
