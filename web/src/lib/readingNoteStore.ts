import { readingNamespace, readingTransaction, type DeviceDatabaseOptions } from './deviceReadingDatabase'
import { notifyReadingNotes, subscribeReadingNotes } from './readingNoteEvents'
import { ReadingNoteError, localReadingNote, readingNoteInput, sameReadingNoteChange, validateReadingNoteItem, validateReadingNoteMutation,
  type ReadingNoteChange, type ReadingNoteChoice, type ReadingNoteIdentity, type ReadingNoteItem, type ReadingNoteMutation, type ReadingNotePage } from './readingNoteApi'
import { validReadingProgressIdentity } from './readingProgressApi'
import { validRecordId } from './validation'
import type { ReadingNote } from './readingNotes'
import type { ReadingDocument } from './readingDocument'

export type ReadingNoteSyncRow = ReadingNoteIdentity & {
  username: string; documentId: string; noteId: string
  remote: ReadingNoteItem | null; pending: ReadingNoteMutation | null; queued: ReadingNoteChange | null; conflict: ReadingNoteItem | null
  protectedLocal: boolean
}
export type ReadingNoteDocumentRow = ReadingNoteIdentity & {
  username: string; documentId: string; cursor: number; watermark: number | null; retryAfterUntil: number | null; unsupportedIds: string[]
}
const stores = ['notes', 'noteSync', 'noteSyncDocuments'] as const
const identityKey = (username: string, identity: ReadingNoteIdentity): IDBValidKey => [username, identity.kind, identity.recordId]
const noteKey = (username: string, identity: ReadingNoteIdentity, id: string): IDBValidKey => [username, identity.kind, identity.recordId, id]
function freshDocument(username: string, identity: ReadingNoteIdentity, documentId: string): ReadingNoteDocumentRow {
  return { ...identity, username, documentId, cursor: 0, watermark: null, retryAfterUntil: null, unsupportedIds: [] }
}
function freshRow(username: string, identity: ReadingNoteIdentity, documentId: string, noteId: string): ReadingNoteSyncRow {
  return { ...identity, username, documentId, noteId, remote: null, pending: null, queued: null, conflict: null, protectedLocal: false }
}
function mutation(change: ReadingNoteChange, version: number): ReadingNoteMutation {
  if (version >= Number.MAX_SAFE_INTEGER) throw new ReadingNoteError('exhausted')
  return validateReadingNoteMutation({ ...change, expectedVersion: version, mutationId: crypto.randomUUID() })
}
function checkedRow(value: ReadingNoteSyncRow): ReadingNoteSyncRow {
  try {
    if (!value || !validReadingProgressIdentity(value) || !validRecordId(value.noteId) || typeof value.documentId !== 'string' || !value.documentId || typeof value.protectedLocal !== 'boolean') throw new Error()
    return { ...value, remote: value.remote ? validateReadingNoteItem(value.remote, value.noteId, true) : null,
      pending: value.pending ? validateReadingNoteMutation(value.pending) : null,
      queued: value.queued ? (({ deleted, note }) => ({ deleted, note }))(validateReadingNoteMutation({ ...value.queued, expectedVersion: 0, mutationId: value.noteId })) : null,
      conflict: value.conflict ? validateReadingNoteItem(value.conflict, value.noteId, true) : null }
  } catch { throw new ReadingNoteError('storage') }
}
function checkedDocument(value: ReadingNoteDocumentRow): ReadingNoteDocumentRow {
  if (!value || !validReadingProgressIdentity(value) || typeof value.documentId !== 'string' || !value.documentId || !Number.isSafeInteger(value.cursor) || value.cursor < 0 ||
    value.watermark !== null && (!Number.isSafeInteger(value.watermark) || value.watermark < value.cursor) || value.retryAfterUntil !== null && (!Number.isSafeInteger(value.retryAfterUntil) || value.retryAfterUntil < 0) ||
    !Array.isArray(value.unsupportedIds) || !value.unsupportedIds.every(id => typeof id === 'string')) throw new ReadingNoteError('storage')
  return value
}
function guard(fail: (error: Error) => void, action: () => void) { try { action() } catch (error) { fail(error instanceof ReadingNoteError ? error : new ReadingNoteError('storage')) } }

/** Called inside the same transaction that edits/removes the visible note. Never await inside this helper. */
export function enqueueReadingNoteChange(tx: IDBTransaction, username: string, documentId: string, noteId: string, change: ReadingNoteChange,
  fail: (error: Error) => void, identity?: ReadingNoteIdentity): void {
  if (!validRecordId(noteId)) return // Preserve local/ZIP legacy identifiers for the later mapping feature.
  const documents = tx.objectStore('noteSyncDocuments')
  const request = identity ? documents.get(identityKey(username, identity)) : documents.index('localDocument').get([username, documentId])
  request.onsuccess = () => guard(fail, () => {
    if (!request.result && !identity) return
    const document = request.result ? checkedDocument(request.result) : freshDocument(username, identity!, documentId)
    if (document.documentId !== documentId) throw new ReadingNoteError('storage')
    documents.put(document)
    const sync = tx.objectStore('noteSync'), current = sync.get(noteKey(username, document, noteId))
    current.onsuccess = () => guard(fail, () => {
      const row = current.result ? checkedRow(current.result) : freshRow(username, document, documentId, noteId)
      if (row.protectedLocal) return
      const next = row.pending ? { ...row, queued: sameReadingNoteChange(row.pending, change) ? null : structuredClone(change) }
        : { ...row, pending: mutation(change, row.remote?.version ?? 0), queued: null }
      sync.put(next)
    })
  })
}

export function createReadingNoteStore(username: string, options: DeviceDatabaseOptions = {}) {
  const namespace = readingNamespace(username)
  const notify = (documentId: string) => notifyReadingNotes(namespace, documentId, options.dbName)
  const transact = <T>(action: Parameters<typeof readingTransaction<T>>[2]) => readingTransaction<T>([...stores], 'readwrite', action, options)
  function applyVisible(tx: IDBTransaction, documentId: string, item: ReadingNoteItem) {
    const note = localReadingNote(documentId, item), store = tx.objectStore('notes')
    if (note) store.put({ ...note, username: namespace }); else store.delete([namespace, documentId, item.noteId])
  }
  async function edit(identity: ReadingNoteIdentity, id: string, reducer: (row: ReadingNoteSyncRow, tx: IDBTransaction) => ReadingNoteSyncRow): Promise<void> {
    let documentId: string | undefined
    await transact<void>((tx, _, fail) => {
      const store = tx.objectStore('noteSync'), request = store.get(noteKey(namespace, identity, id))
      request.onsuccess = () => guard(fail, () => {
        if (!request.result) return
        const row = checkedRow(request.result); documentId = row.documentId
        store.put(reducer(row, tx))
      })
    })
    if (documentId) notify(documentId)
  }
  return {
    namespace,
    subscribe(documentId: string, listener: () => void) { return subscribeReadingNotes(namespace, documentId, listener, options.dbName) },
    async bind(document: ReadingDocument): Promise<void> {
      const identity = document.serverProgress
      if (!identity || !validReadingProgressIdentity(identity)) return
      await transact<void>((tx, _, fail) => {
        const docs = tx.objectStore('noteSyncDocuments'), request = docs.get(identityKey(namespace, identity))
        request.onsuccess = () => guard(fail, () => {
          const current = request.result ? checkedDocument(request.result) : freshDocument(namespace, identity, document.id)
          // Legacy document readers can still inspect their now-empty rows after canonical migration.
          if (current.documentId !== document.id) return
          const list = tx.objectStore('notes').index('document').getAll([namespace, document.id]), unsupported: string[] = []
          list.onsuccess = () => guard(fail, () => {
            const sync = tx.objectStore('noteSync')
            for (const note of list.result as ReadingNote[]) {
              let input: ReturnType<typeof readingNoteInput> | undefined
              try { if (validRecordId(note.id)) input = readingNoteInput(note, document) } catch { /* Unsupported rows remain locally editable/exportable. */ }
              if (!input) unsupported.push(note.id)
              if (!validRecordId(note.id)) continue
              const prior = sync.get(noteKey(namespace, identity, note.id))
              prior.onsuccess = () => guard(fail, () => {
                if (prior.result) return
                const row = freshRow(namespace, identity, document.id, note.id)
                sync.put(input ? { ...row, pending: mutation({ deleted: false, note: input }, 0) } : { ...row, protectedLocal: true })
              })
            }
            docs.put({ ...current, unsupportedIds: unsupported })
          })
        })
      })
    },
    async snapshot(identity: ReadingNoteIdentity): Promise<{ document: ReadingNoteDocumentRow | null; rows: ReadingNoteSyncRow[] }> {
      return readingTransaction(['noteSync', 'noteSyncDocuments'], 'readonly', (tx, done, fail) => {
        const result: { document: ReadingNoteDocumentRow | null; rows: ReadingNoteSyncRow[] } = { document: null, rows: [] }; done(result)
        const doc = tx.objectStore('noteSyncDocuments').get(identityKey(namespace, identity))
        doc.onsuccess = () => guard(fail, () => { result.document = doc.result ? checkedDocument(doc.result) : null })
        const rows = tx.objectStore('noteSync').index('document').getAll(identityKey(namespace, identity))
        rows.onsuccess = () => guard(fail, () => { result.rows = rows.result.map(checkedRow) })
      }, options)
    },
    async listPending(): Promise<ReadingNoteIdentity[]> {
      return readingTransaction(['noteSync'], 'readonly', (tx, done) => {
        const request = tx.objectStore('noteSync').index('username').openCursor(namespace), found = new Map<string, ReadingNoteIdentity>()
        request.onsuccess = () => {
          const cursor = request.result
          if (!cursor) { done([...found.values()]); return }
          try { const row = checkedRow(cursor.value); if (row.pending) found.set(`${row.kind}:${row.recordId}`, { kind: row.kind, recordId: row.recordId }) } catch { /* A damaged item must not block independent documents. */ }
          cursor.continue()
        }
      }, options)
    },
    async acknowledge(identity: ReadingNoteIdentity, id: string, sent: ReadingNoteMutation, accepted: ReadingNoteItem): Promise<void> {
      await edit(identity, id, (row, tx) => {
        if (row.pending?.mutationId !== sent.mutationId) return row
        const newest = [accepted, row.remote, row.conflict].filter((item): item is ReadingNoteItem => !!item).sort((a, b) => b.version - a.version)[0]
        if (row.conflict && row.conflict.version > accepted.version) return { ...row, remote: newest }
        const queued = row.queued
        if (queued && !sameReadingNoteChange(queued, { deleted: accepted.deleted, note: accepted.note ? (({ excerpt: _, ...input }) => input)(accepted.note) : null })) {
          return { ...row, remote: newest, pending: mutation(queued, accepted.version), queued: null, conflict: null }
        }
        applyVisible(tx, row.documentId, newest)
        return { ...row, remote: newest, pending: null, queued: null, conflict: null }
      })
    },
    async conflict(identity: ReadingNoteIdentity, id: string, sent: ReadingNoteMutation, current: ReadingNoteItem): Promise<void> {
      await edit(identity, id, row => {
        if (row.pending?.mutationId !== sent.mutationId) return row
        const newest = [current, row.remote, row.conflict].filter((item): item is ReadingNoteItem => !!item).sort((a, b) => b.version - a.version)[0]
        return { ...row, conflict: newest }
      })
    },
    async resolve(identity: ReadingNoteIdentity, id: string, choice: 'local' | 'server', expected: ReadingNoteChoice): Promise<void> {
      await edit(identity, id, (row, tx) => {
        if (!row.conflict || !row.pending || row.conflict.version !== expected.remote.version || row.conflict.noteId !== expected.remote.noteId || !sameReadingNoteChange(row.queued ?? row.pending, expected.local)) throw new ReadingNoteError('choice-stale')
        const remote = row.conflict
        if (choice === 'local') return { ...row, remote, pending: mutation(row.queued ?? row.pending, remote.version), queued: null, conflict: null }
        applyVisible(tx, row.documentId, remote)
        return { ...row, remote, pending: null, queued: null, conflict: null }
      })
    },
    async defer(identity: ReadingNoteIdentity, until: number): Promise<void> {
      await transact<void>((tx, _, fail) => {
        const docs = tx.objectStore('noteSyncDocuments'), request = docs.get(identityKey(namespace, identity))
        request.onsuccess = () => guard(fail, () => { if (request.result) { const doc = checkedDocument(request.result); docs.put({ ...doc, retryAfterUntil: Math.max(doc.retryAfterUntil ?? 0, until) }) } })
      })
    },
    async applyPage(identity: ReadingNoteIdentity, afterRevision: number, page: ReadingNotePage): Promise<boolean> {
      let documentId: string | undefined, applied = false
      await transact<void>((tx, _, fail) => {
        const docs = tx.objectStore('noteSyncDocuments'), request = docs.get(identityKey(namespace, identity))
        request.onsuccess = () => guard(fail, () => {
          if (!request.result) throw new ReadingNoteError('storage')
          const doc = checkedDocument(request.result); documentId = doc.documentId
          // Another tab must not have its cursor rolled back, or lose an already committed page.
          if (doc.cursor !== afterRevision) return
          applied = true
          docs.put({ ...doc, cursor: page.nextAfterRevision, watermark: page.hasMore ? page.watermark : null })
          const sync = tx.objectStore('noteSync')
          for (const item of page.items) {
            const prior = sync.get(noteKey(namespace, identity, item.noteId))
            prior.onsuccess = () => guard(fail, () => {
              const row = prior.result ? checkedRow(prior.result) : freshRow(namespace, identity, doc.documentId, item.noteId)
              if ((row.remote?.version ?? 0) >= item.version) return
              // PUT resolves pending uncertainty: this may be our own successful response-loss retry.
              // Pull alone must neither acknowledge it nor turn it into a spurious conflict.
              sync.put({ ...row, remote: item, conflict: row.conflict && row.conflict.version < item.version ? item : row.conflict })
              if (!row.pending && !row.protectedLocal) applyVisible(tx, doc.documentId, item)
            })
          }
        })
      })
      if (documentId && applied) notify(documentId)
      return applied
    },
  }
}
export type ReadingNoteStore = ReturnType<typeof createReadingNoteStore>
