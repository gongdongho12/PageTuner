import { webcrypto } from 'node:crypto'
import { IDBFactory, IDBObjectStore } from 'fake-indexeddb'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { createReadingNotes, type ReadingNote } from './readingNotes'
import { createReadingNoteStore } from './readingNoteStore'
import { createReadingNoteController, type ReadingNoteController } from './readingNoteSync'
import { ReadingNoteError, readingNoteInput, type ReadingNoteClient, type ReadingNoteIdentity, type ReadingNoteInput, type ReadingNoteItem, type ReadingNoteMutation, type ReadingNotePage } from './readingNoteApi'
import { readingTransaction } from './deviceReadingDatabase'
import type { ReadingDocument } from './readingDocument'

beforeAll(() => vi.stubGlobal('crypto', webcrypto))
const identity: ReadingNoteIdentity = { kind: 'ORIGINAL', recordId: '11111111-1111-1111-1111-111111111111' }
const document: ReadingDocument = { id: `original:${identity.recordId}:revision`, serverProgress: identity, kind: 'original', bookTitle: 'Book', chapterTitle: 'Chapter', language: 'en',
  paragraphs: [{ paragraphId: 'p1', text: 'First 😀 paragraph' }, { paragraphId: 'p2', text: 'Second paragraph' }] }
const anchor = { paragraphId: 'p1', characterOffset: 0 }, time = '2026-09-16T00:00:00Z'
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>(done => { resolve = done }); return { promise, resolve } }
function item(noteId: string, version: number, revision: number, note: ReadingNoteInput | null): ReadingNoteItem {
  return { noteId, version, changeRevision: revision, deleted: !note, note: note ? { ...note, excerpt: document.paragraphs.find(p => p.paragraphId === note.anchor.paragraphId)!.text.slice(note.anchor.characterOffset) } : null, updatedAt: time }
}
function feed(items: ReadingNoteItem[], watermark = items.at(-1)?.changeRevision ?? 0, hasMore = false): ReadingNotePage {
  return { ...identity, items, watermark, hasMore, nextAfterRevision: hasMore ? items.at(-1)!.changeRevision : watermark }
}
function server() {
  let revision = 0
  const items = new Map<string, { item: ReadingNoteItem; mutation: ReadingNoteMutation }>(), history: ReadingNoteItem[] = []
  const put = vi.fn<ReadingNoteClient['put']>().mockImplementation(async (_, noteId, request) => {
    const previous = items.get(noteId)
    if (previous?.mutation.mutationId === request.mutationId) return structuredClone(previous.item)
    if ((previous?.item.version ?? 0) !== request.expectedVersion) throw new ReadingNoteError('conflict', previous?.item ?? { noteId, version: 0, changeRevision: 0, deleted: true, note: null, updatedAt: null })
    const next = item(noteId, request.expectedVersion + 1, ++revision, request.note)
    items.set(noteId, { item: next, mutation: structuredClone(request) }); history.push(next); return structuredClone(next)
  })
  const list = vi.fn<ReadingNoteClient['list']>().mockImplementation(async (_, query) => {
    const watermark = query.untilRevision ?? revision, selected = history.filter(i => i.changeRevision > query.afterRevision && i.changeRevision <= watermark), page = selected.slice(0, query.limit ?? 50)
    return feed(structuredClone(page), watermark, selected.length > page.length)
  })
  return { api: { put, list, close: vi.fn() } satisfies ReadingNoteClient, items, history }
}
const controllers: ReadingNoteController[] = []
afterEach(() => { controllers.splice(0).forEach(controller => controller.close()); vi.restoreAllMocks() })
function setup(options: { factory?: IDBFactory; username?: string; api?: ReadingNoteClient | null } = {}) {
  const db = { indexedDB: options.factory ?? new IDBFactory() }, username = options.username ?? 'alice', store = createReadingNoteStore(username, db), notes = createReadingNotes(username, db)
  const remote = server(), api = options.api === undefined ? remote.api : options.api
  const controller = createReadingNoteController({ api, store, identity, debounceMs: 100_000 }); controllers.push(controller)
  return { db, username, store, notes, controller, remote, api }
}
const add = (notes: ReturnType<typeof createReadingNotes>, title = 'Bookmark') => notes.add(document, { kind: 'bookmark', title, anchor })

describe('atomic note synchronization', () => {
  it('commits visible adds, edits and deletes together with immutable pending and queued operations', async () => {
    const { notes, store } = setup({ api: null }), note = await add(notes)
    const first = (await store.snapshot(identity)).rows[0].pending!
    await notes.update(document, note.id, { title: 'Updated' }, note)
    const updated = (await notes.list(document)).items[0]
    expect((await store.snapshot(identity)).rows[0]).toMatchObject({ pending: first, queued: { deleted: false, note: { title: 'Updated' } } })
    await notes.remove(document.id, note.id, updated)
    expect((await notes.list(document)).items).toEqual([])
    expect((await store.snapshot(identity)).rows[0]).toMatchObject({ pending: first, queued: { deleted: true, note: null } })
  })
  it('rolls back the visible add if persisting the outbox fails', async () => {
    const { notes, store } = setup({ api: null }), put = IDBObjectStore.prototype.put
    const spy = vi.spyOn(IDBObjectStore.prototype, 'put').mockImplementation(function (this: IDBObjectStore, value: unknown, key?: IDBValidKey) {
      if (this.name === 'noteSync') throw new DOMException('Quota exhausted', 'QuotaExceededError')
      return put.call(this, value, key)
    })
    await expect(add(notes)).rejects.toThrow(); spy.mockRestore()
    expect((await notes.list(document)).items).toEqual([])
    expect((await store.snapshot(identity)).rows).toEqual([])
  })
  it('retries the identical mutation after a lost response, even without reopening the document', async () => {
    const factory = new IDBFactory(), remote = server(), first = setup({ factory, api: remote.api })
    await first.controller.start(document); await add(first.notes)
    const sent = (await first.store.snapshot(identity)).rows[0].pending!
    const put = remote.api.put.getMockImplementation()!
    remote.api.put.mockImplementationOnce(async (...args) => { await put(...args); throw new ReadingNoteError('network') })
    await first.controller.flush(); first.controller.close()
    const second = setup({ factory, api: remote.api }); await second.controller.flush()
    expect(remote.api.put).toHaveBeenNthCalledWith(2, identity, expect.any(String), sent, expect.any(AbortSignal))
    expect(remote.history).toHaveLength(1)
    expect(second.controller.snapshot().pendingCount).toBe(0)
  })
  it('preserves an offline delete against a remote edit until an explicit choice', async () => {
    const { controller, notes, store, remote } = setup(); await controller.start(document)
    const note = await add(notes); await controller.flush()
    await remote.api.put(identity, note.id, { expectedVersion: 1, mutationId: crypto.randomUUID(), deleted: false, note: { ...readingNoteInput(note), title: 'Other device' } })
    await notes.remove(document.id, note.id); await controller.refresh()
    expect((await notes.list(document)).items).toEqual([])
    const conflict = controller.snapshot().conflicts[0]
    expect(conflict).toMatchObject({ local: { deleted: true }, remote: { version: 2, note: { title: 'Other device' } } })
    await controller.resolve(note.id, 'local', conflict)
    expect(remote.items.get(note.id)?.item).toMatchObject({ version: 3, deleted: true })
    expect((await store.snapshot(identity)).rows[0].pending).toBeNull()
  })
  it('keeps a local edit visible against remote deletion and permits explicit restoration', async () => {
    const { controller, notes, remote } = setup(); await controller.start(document)
    const note = await add(notes); await controller.flush()
    await remote.api.put(identity, note.id, { expectedVersion: 1, mutationId: crypto.randomUUID(), deleted: true, note: null })
    await notes.update(document, note.id, { title: 'Local edit' }); await controller.refresh()
    expect((await notes.list(document)).items[0].title).toBe('Local edit')
    const conflict = controller.snapshot().conflicts[0]
    expect(conflict.remote.deleted).toBe(true)
    await controller.resolve(note.id, 'local', conflict)
    expect(remote.items.get(note.id)?.item).toMatchObject({ version: 3, deleted: false, note: { title: 'Local edit' } })
  })
  it('does not advance the pull cursor from a PUT acknowledgement or skip independent notes', async () => {
    const { controller, notes, store, remote } = setup(); await controller.start(document)
    const local = await add(notes), otherId = crypto.randomUUID()
    await remote.api.put(identity, otherId, { expectedVersion: 0, mutationId: crypto.randomUUID(), deleted: false, note: { ...readingNoteInput(local), title: 'Independent remote' } })
    await controller.flush()
    expect((await store.snapshot(identity)).document?.cursor).toBe(0)
    await controller.refresh()
    expect((await notes.list(document)).items.map(n => n.title)).toContain('Independent remote')
    expect((await store.snapshot(identity)).document?.cursor).toBe(2)
  })
  it('keeps a newer remote version when an older success or conflict arrives late', async () => {
    const { notes, store } = setup(), note = await add(notes), input = readingNoteInput(note), pending = (await store.snapshot(identity)).rows[0].pending!
    const first = item(note.id, 1, 1, input), second = item(note.id, 2, 2, { ...input, title: 'Newest remote' })
    await store.applyPage(identity, 0, feed([first, second]))
    await store.acknowledge(identity, note.id, pending, first)
    expect((await notes.list(document)).items[0].title).toBe('Newest remote')
    expect((await store.snapshot(identity)).rows[0].remote?.version).toBe(2)
    await notes.update(document, note.id, { title: 'Local again' })
    const next = (await store.snapshot(identity)).rows[0].pending!
    await store.conflict(identity, note.id, next, first)
    expect((await store.snapshot(identity)).rows[0].conflict?.version).toBe(2)
  })
  it('rejects stale edit and delete confirmations inside the same transaction', async () => {
    const { notes, store } = setup(), old = await add(notes)
    await notes.update(document, old.id, { title: 'Changed elsewhere' })
    const before = await store.snapshot(identity)
    await expect(notes.update(document, old.id, { title: 'Stale edit' }, old)).rejects.toThrow('다른 기기')
    await expect(notes.remove(document.id, old.id, old)).rejects.toThrow('다른 기기')
    expect((await notes.list(document)).items[0].title).toBe('Changed elsewhere')
    expect(await store.snapshot(identity)).toEqual(before)
  })
  it('rejects stale conflict choices after another tab changes the local draft or remote version', async () => {
    const { notes, store, controller } = setup(), note = await add(notes), input = readingNoteInput(note), pending = (await store.snapshot(identity)).rows[0].pending!
    await store.conflict(identity, note.id, pending, item(note.id, 1, 1, { ...input, title: 'Remote' }))
    await controller.flush(); const choice = controller.snapshot().conflicts[0]
    await notes.update(document, note.id, { title: 'New local draft' })
    await controller.resolve(note.id, 'server', choice)
    expect(controller.snapshot()).toMatchObject({ errorCode: 'choice-stale' })
    expect((await notes.list(document)).items[0].title).toBe('New local draft')
    const refreshed = controller.snapshot().conflicts[0]
    await store.applyPage(identity, 0, feed([item(note.id, 2, 2, { ...input, title: 'New remote' })]))
    await controller.resolve(note.id, 'local', refreshed)
    expect(controller.snapshot()).toMatchObject({ errorCode: 'choice-stale' })
    expect((await store.snapshot(identity)).rows[0].pending).toEqual(pending)
  })
  it('isolates accounts and originals from translations while preserving legacy IDs without upload or duplication', async () => {
    const factory = new IDBFactory(), { notes, store, db } = setup({ factory }), note = await add(notes)
    const legacy: ReadingNote = { ...note, id: 'native-page-note', title: 'Keep native ID' }
    await readingTransaction<void>(['notes'], 'readwrite', tx => { tx.objectStore('notes').add({ ...legacy, username: 'alice' }) }, db)
    await store.bind(document)
    expect((await notes.list(document)).items).toHaveLength(2)
    expect((await store.snapshot(identity)).rows).toHaveLength(1)
    expect((await store.snapshot(identity)).document?.unsupportedIds).toEqual(['native-page-note'])
    expect((await createReadingNoteStore('bob', db).snapshot(identity)).rows).toEqual([])
    expect((await store.snapshot({ ...identity, kind: 'TRANSLATION' })).rows).toEqual([])
  })
  it('preserves local movement accepted before account close and rejects late network acknowledgements', async () => {
    const { controller, notes, store, remote } = setup(); await controller.start(document)
    const note = await add(notes), response = deferred<ReadingNoteItem>()
    remote.api.put.mockImplementationOnce(() => response.promise)
    const flushing = controller.flush(); await vi.waitFor(() => expect(remote.api.put).toHaveBeenCalled())
    const writing = notes.update(document, note.id, { title: 'Accepted before close' }); controller.close(); await writing
    response.resolve(item(note.id, 1, 1, readingNoteInput(note))); await flushing
    expect((await store.snapshot(identity)).rows[0]).toMatchObject({ pending: { expectedVersion: 0 }, queued: { note: { title: 'Accepted before close' } } })
  })
  it('persists Retry-After across controllers and does not repeatedly retry permanent errors', async () => {
    const factory = new IDBFactory(), remote = server(), first = setup({ factory, api: remote.api }), clock = vi.spyOn(Date, 'now').mockReturnValue(1_000_000)
    await first.controller.start(document); await add(first.notes)
    remote.api.put.mockRejectedValueOnce(new ReadingNoteError('limit', undefined, 60))
    await first.controller.flush(); first.controller.close()
    const next = setup({ factory, api: remote.api }); await next.controller.flush()
    expect(remote.api.put).toHaveBeenCalledTimes(1)
    clock.mockReturnValue(1_060_001); remote.api.put.mockRejectedValueOnce(new ReadingNoteError('authentication'))
    await next.controller.flush(); await next.controller.flush()
    expect(remote.api.put).toHaveBeenCalledTimes(2)
    await next.controller.refresh(); expect(remote.api.put).toHaveBeenCalledTimes(3)
  })
  it('preserves a surrogate-safe local excerpt for ZIP export', async () => {
    const { notes } = setup({ api: null }), long = { ...document, serverProgress: undefined, paragraphs: [{ paragraphId: 'p1', text: 'a'.repeat(999) + '😀tail' }] }
    const note = await notes.add(long, { kind: 'bookmark', title: 'Excerpt', anchor })
    expect(note.excerpt).toBe('a'.repeat(999))
  })
  it('upgrades a real version-two device database without replacing existing notes or ZIP rows', async () => {
    const factory = new IDBFactory(), old: ReadingNote = { id: crypto.randomUUID(), documentId: document.id, kind: 'note', title: 'Existing note', text: 'Preserve me', excerpt: document.paragraphs[0].text, anchor, createdAt: time }
    await new Promise<void>((resolve, reject) => {
      const request = factory.open('pageturner-device-reading', 2)
      request.onupgradeneeded = () => {
        const db = request.result
        db.createObjectStore('documents', { keyPath: ['username', 'id'] }).createIndex('username', 'username')
        const notes = db.createObjectStore('notes', { keyPath: ['username', 'documentId', 'id'] }); notes.createIndex('document', ['username', 'documentId']); notes.add({ ...old, username: 'alice' })
        db.createObjectStore('positions', { keyPath: ['username', 'documentId'] }).createIndex('username', 'username')
        const exchanges = db.createObjectStore('exchanges', { keyPath: ['username', 'id'] }); exchanges.createIndex('username', 'username'); exchanges.add({ username: 'alice', id: 'exchange:retained', metadata: 'native metadata untouched' })
      }
      request.onerror = () => reject(request.error)
      request.onsuccess = () => { request.result.close(); resolve() }
    })
    const { notes, store, db } = setup({ factory })
    expect((await notes.list(document)).items).toEqual([old])
    expect((await store.snapshot(identity)).rows[0]).toMatchObject({ noteId: old.id, pending: { expectedVersion: 0, note: { text: old.text } } })
    const retained = await readingTransaction<{ metadata: string }>(['exchanges'], 'readonly', (tx, done) => {
      const request = tx.objectStore('exchanges').get(['alice', 'exchange:retained']); request.onsuccess = () => done(request.result)
    }, db)
    expect(retained.metadata).toBe('native metadata untouched')
  })
  it('rolls back an entire incoming page and its cursor when one visible-note write fails', async () => {
    const { store, notes } = setup(); await store.bind(document)
    const input: ReadingNoteInput = { kind: 'NOTE', title: 'Remote', text: 'Message', anchor, range: null, createdAt: time }
    const first = item(crypto.randomUUID(), 1, 1, input), second = item(crypto.randomUUID(), 1, 2, input), put = IDBObjectStore.prototype.put
    const spy = vi.spyOn(IDBObjectStore.prototype, 'put').mockImplementation(function (this: IDBObjectStore, value: unknown, key?: IDBValidKey) {
      if (this.name === 'noteSync' && (value as { noteId: string }).noteId === second.noteId) throw new DOMException('Full', 'QuotaExceededError')
      return put.call(this, value, key)
    })
    await expect(store.applyPage(identity, 0, feed([first, second]))).rejects.toThrow(); spy.mockRestore()
    expect((await store.snapshot(identity)).document?.cursor).toBe(0)
    expect((await notes.list(document)).items).toEqual([])
    await store.applyPage(identity, 0, feed([first, second]))
    expect((await notes.list(document)).items).toHaveLength(2)
    expect(await store.applyPage(identity, 0, feed([first]))).toBe(false)
    expect((await store.snapshot(identity)).document?.cursor).toBe(2)
  })
  it('reads multiple immutable pages at one watermark and picks up later writes on the next refresh', async () => {
    const remote = server(), input: ReadingNoteInput = { kind: 'NOTE', title: 'Remote', text: 'Message', anchor, range: null, createdAt: time }
    for (let index = 0; index < 65; index++) await remote.api.put(identity, crypto.randomUUID(), { expectedVersion: 0, mutationId: crypto.randomUUID(), deleted: false, note: input })
    const originalList = remote.api.list.getMockImplementation()!
    remote.api.list.mockImplementationOnce(async (...args) => {
      const page = await originalList(...args)
      await remote.api.put(identity, crypto.randomUUID(), { expectedVersion: 0, mutationId: crypto.randomUUID(), deleted: false, note: { ...input, title: 'After captured watermark' } })
      return page
    })
    const { controller, store, notes } = setup({ api: remote.api }); await controller.start(document)
    expect(remote.api.list.mock.calls.map(call => call[1])).toEqual([{ afterRevision: 0, limit: 50 }, { afterRevision: 50, limit: 50, untilRevision: 65 }])
    expect((await notes.list(document)).items).toHaveLength(65)
    expect((await store.snapshot(identity)).document).toMatchObject({ cursor: 65, watermark: null })
    await controller.refresh()
    expect((await notes.list(document)).items).toHaveLength(66)
    expect(controller.snapshot().pullPending).toBe(false)
  })
  it('keeps canonical local edits when migrating an older pending copy with the same note ID', async () => {
    const { notes, store, controller, db, remote } = setup(), previous = { ...document, id: 'legacy-server-document' }
    const legacy = await notes.add(previous, { kind: 'bookmark', title: 'Legacy title', anchor })
    await readingTransaction<void>(['notes'], 'readwrite', tx => {
      tx.objectStore('notes').add({ ...legacy, username: 'alice', documentId: document.id, title: 'Canonical edit' })
    }, db)
    await notes.migrateDocument(previous, document)
    expect((await store.snapshot(identity)).rows[0]).toMatchObject({ documentId: document.id, pending: { note: { title: 'Legacy title' } }, queued: { note: { title: 'Canonical edit' } } })
    await controller.flush()
    expect((await notes.list(document)).items[0].title).toBe('Canonical edit')
    expect(remote.items.get(legacy.id)?.item.note?.title).toBe('Canonical edit')
  })
})
