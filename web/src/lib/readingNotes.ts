import type { ReadingAnchor } from './offline'
import type { ReadingDocument } from './readingDocument'
import { validAnchor } from './readingDocument'
import { readingNamespace, readingTransaction, type DeviceDatabaseOptions } from './deviceReadingDatabase'
import { readingRangeText, type ReadingRange } from './readingSelection'
import { createReadingNoteStore, enqueueReadingNoteChange } from './readingNoteStore'
import { readingNoteInput } from './readingNoteApi'
import { notifyReadingNotes } from './readingNoteEvents'
export { subscribeReadingNotes } from './readingNoteEvents'

export type ReadingNote = {
  id: string
  documentId: string
  kind: 'bookmark' | 'note' | 'highlight'
  title: string
  text: string
  excerpt: string
  anchor: ReadingAnchor
  range?: ReadingRange
  createdAt: string
}
type StoredNote = ReadingNote & { username: string }
const comparable = (n: ReadingNote) => JSON.stringify([n.id, n.documentId, n.kind, n.title, n.text, n.excerpt, n.anchor.paragraphId, n.anchor.characterOffset,
  n.range?.start.paragraphId, n.range?.start.characterOffset, n.range?.end.paragraphId, n.range?.end.characterOffset, n.createdAt])
const changedNote = () => new Error('다른 기기에서 기록이 변경되었습니다. 최신 기록을 확인한 뒤 다시 수정해 주세요.')
function excerptAt(text: string, offset: number): string {
  let end = Math.min(text.length, offset + 1000)
  if (end < text.length && /[\uD800-\uDBFF]/.test(text[end - 1]) && /[\uDC00-\uDFFF]/.test(text[end])) end--
  return text.slice(offset, end)
}

function validate(value: StoredNote, document: ReadingDocument): ReadingNote {
  if (!value || typeof value.id !== 'string' || !value.id || value.documentId !== document.id ||
      !['bookmark', 'note', 'highlight'].includes(value.kind) || typeof value.title !== 'string' || !value.title.trim() || value.title.length > 200 ||
      typeof value.text !== 'string' || value.text.length > 4000 || typeof value.excerpt !== 'string' || value.excerpt.length > (value.kind === 'highlight' ? 4000 : 1000) ||
      !value.anchor || !validAnchor(document, value.anchor) || !Number.isFinite(Date.parse(value.createdAt))) {
    throw new Error('저장된 읽기 메모를 확인할 수 없습니다.')
  }
  if (value.kind === 'highlight' && (!value.range || value.anchor.paragraphId !== value.range.start.paragraphId ||
      value.anchor.characterOffset !== value.range.start.characterOffset || readingRangeText(document, value.range) !== value.excerpt)) {
    throw new Error('저장된 강조 범위가 본문과 일치하지 않습니다.')
  }
  if (value.kind !== 'highlight' && value.range) throw new Error('읽기 기록의 종류와 강조 범위가 일치하지 않습니다.')
  const { username: _, ...note } = value
  return note
}

export function createReadingNotes(username: string, options: DeviceDatabaseOptions = {}) {
  const namespace = readingNamespace(username)
  return {
    async migrateDocument(previous: ReadingDocument, document: ReadingDocument): Promise<void> {
      if (previous.id === document.id) return
      await readingTransaction<void>(['notes', 'positions', 'noteSync', 'noteSyncDocuments'], 'readwrite', (tx, _, fail) => {
        const notes = tx.objectStore('notes'), positions = tx.objectStore('positions')
        const request = notes.index('document').getAll([namespace, previous.id])
        request.onsuccess = () => {
          for (const value of request.result as StoredNote[]) {
            try { validate(value, previous); validate({ ...value, documentId: document.id }, document) } catch { continue }
            const existing = notes.get([namespace, document.id, value.id])
            existing.onsuccess = () => {
              // Preserve an already migrated record, including subsequent user edits.
              if (!existing.result) notes.put({ ...value, documentId: document.id })
              else if (document.serverProgress && previous.serverProgress?.kind === document.serverProgress.kind && previous.serverProgress.recordId === document.serverProgress.recordId) {
                try {
                  const retained = validate(existing.result, document)
                  enqueueReadingNoteChange(tx, namespace, document.id, retained.id, { deleted: false, note: readingNoteInput(retained, document) }, fail, document.serverProgress)
                } catch { /* An unsupported existing record stays intact for local recovery. */ }
              }
              notes.delete([namespace, previous.id, value.id])
            }
          }
        }
        const prior = positions.get([namespace, previous.id])
        prior.onsuccess = () => {
          if (!prior.result?.anchor || !validAnchor(document, prior.result.anchor)) return
          const current = positions.get([namespace, document.id])
          current.onsuccess = () => {
            if (!current.result) positions.put({ ...prior.result, documentId: document.id })
            positions.delete([namespace, previous.id])
          }
        }
        const identity = document.serverProgress
        if (identity && previous.serverProgress?.kind === identity.kind && previous.serverProgress.recordId === identity.recordId) {
          const docs = tx.objectStore('noteSyncDocuments'), binding = docs.get([namespace, identity.kind, identity.recordId])
          binding.onsuccess = () => {
            if (!binding.result || binding.result.documentId !== previous.id) return
            docs.put({ ...binding.result, documentId: document.id })
            const cursor = tx.objectStore('noteSync').index('document').openCursor([namespace, identity.kind, identity.recordId])
            cursor.onsuccess = () => { if (cursor.result) { cursor.result.update({ ...cursor.result.value, documentId: document.id }); cursor.result.continue() } }
          }
        }
      }, options)
      notifyReadingNotes(namespace, previous.id, options.dbName); notifyReadingNotes(namespace, document.id, options.dbName)
    },
    async list(document: ReadingDocument): Promise<{ items: ReadingNote[]; damagedIds: string[] }> {
      if (document.serverProgress) await createReadingNoteStore(namespace, options).bind(document)
      const values = await readingTransaction<StoredNote[]>(['notes'], 'readonly', (tx, result) => {
        const request = tx.objectStore('notes').index('document').getAll([namespace, document.id])
        request.onsuccess = () => result(request.result)
      }, options)
      const items: ReadingNote[] = [], damagedIds: string[] = []
      for (const value of values) {
        try { items.push(validate(value, document)) }
        catch { damagedIds.push(value.id) }
      }
      items.sort((a, b) => b.createdAt.localeCompare(a.createdAt) || b.id.localeCompare(a.id))
      return { items, damagedIds }
    },
    async add(document: ReadingDocument, input: { kind: ReadingNote['kind']; title: string; text?: string; anchor: ReadingAnchor; range?: ReadingRange }): Promise<ReadingNote> {
      const paragraph = document.paragraphs.find(item => item.paragraphId === input.anchor.paragraphId)
      const note: StoredNote = {
        username: namespace, id: crypto.randomUUID(), documentId: document.id, kind: input.kind,
        title: input.title.trim(), text: input.text?.trim() ?? '', excerpt: input.kind === 'highlight' && input.range ? readingRangeText(document, input.range) : paragraph ? excerptAt(paragraph.text, input.anchor.characterOffset) : '',
        anchor: { ...input.anchor }, createdAt: new Date().toISOString(),
        ...(input.range ? { range: { start: { ...input.range.start }, end: { ...input.range.end } } } : {}),
      }
      validate(note, document)
      if (note.kind === 'note' && !note.text) throw new Error('메모 내용을 입력해 주세요.')
      const syncInput = document.serverProgress ? readingNoteInput(note, document) : undefined
      await readingTransaction<void>(['notes', 'noteSync', 'noteSyncDocuments'], 'readwrite', (tx, _, fail) => {
        tx.objectStore('notes').add(note)
        if (syncInput) enqueueReadingNoteChange(tx, namespace, document.id, note.id, { deleted: false, note: syncInput }, fail, document.serverProgress)
      }, options)
      notifyReadingNotes(namespace, document.id, options.dbName)
      return validate(note, document)
    },
    async update(document: ReadingDocument, id: string, input: { title: string; text?: string }, expected?: ReadingNote): Promise<ReadingNote> {
      const result = await readingTransaction<ReadingNote>(['notes', 'noteSync', 'noteSyncDocuments'], 'readwrite', (tx, done, fail) => {
        const notes = tx.objectStore('notes'), request = notes.get([namespace, document.id, id])
        request.onsuccess = () => {
          try {
            if (!request.result) throw new Error('수정할 읽기 기록을 찾을 수 없습니다.')
            const previous = validate(request.result, document)
            if (expected && comparable(previous) !== comparable(expected)) throw changedNote()
            const note: StoredNote = { ...previous, username: namespace, title: input.title.trim(), text: input.text?.trim() ?? previous.text }
            validate(note, document)
            if (note.kind === 'note' && !note.text) throw new Error('메모 내용을 입력해 주세요.')
            const change = document.serverProgress ? readingNoteInput(note, document) : undefined
            notes.put(note)
            if (change) enqueueReadingNoteChange(tx, namespace, document.id, id, { deleted: false, note: change }, fail, document.serverProgress)
            done(validate(note, document))
          } catch (error) { fail(error instanceof Error ? error : new Error('읽기 기록을 수정하지 못했습니다.')) }
        }
      }, options)
      notifyReadingNotes(namespace, document.id, options.dbName)
      return result
    },
    async remove(documentId: string, id: string, expected?: ReadingNote): Promise<void> {
      await readingTransaction<void>(['notes', 'noteSync', 'noteSyncDocuments'], 'readwrite', (tx, _, fail) => {
        const store = tx.objectStore('notes'), request = store.get([namespace, documentId, id])
        request.onsuccess = () => {
          try {
            if (expected && (!request.result || comparable(request.result) !== comparable(expected))) throw changedNote()
            store.delete([namespace, documentId, id])
            enqueueReadingNoteChange(tx, namespace, documentId, id, { deleted: true, note: null }, fail)
          } catch (error) { fail(error instanceof Error ? error : changedNote()) }
        }
      }, options)
      notifyReadingNotes(namespace, documentId, options.dbName)
    },
    async getPosition(document: ReadingDocument): Promise<ReadingAnchor | undefined> {
      const value = await readingTransaction<{ anchor?: ReadingAnchor } | undefined>(['positions'], 'readonly', (tx, result) => {
        const request = tx.objectStore('positions').get([namespace, document.id])
        request.onsuccess = () => result(request.result)
      }, options)
      if (!value?.anchor) return undefined
      if (!validAnchor(document, value.anchor)) throw new Error('저장된 읽기 위치를 확인할 수 없습니다.')
      return value.anchor
    },
    async setPosition(document: ReadingDocument, anchor: ReadingAnchor): Promise<void> {
      if (!validAnchor(document, anchor)) throw new Error('이 문서에 없는 읽기 위치입니다.')
      await readingTransaction<void>(['positions'], 'readwrite', tx => {
        tx.objectStore('positions').put({ username: namespace, documentId: document.id, anchor: { ...anchor } })
      }, options)
    },
  }
}
