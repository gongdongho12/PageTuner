import type { ReadingAnchor } from './offline'
import type { ReadingDocument } from './readingDocument'
import { validAnchor } from './readingDocument'
import { readingNamespace, readingTransaction, type DeviceDatabaseOptions } from './deviceReadingDatabase'
import { readingRangeText, type ReadingRange } from './readingSelection'

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
      await readingTransaction<void>(['notes', 'positions'], 'readwrite', tx => {
        const notes = tx.objectStore('notes'), positions = tx.objectStore('positions')
        const request = notes.index('document').getAll([namespace, previous.id])
        request.onsuccess = () => {
          for (const value of request.result as StoredNote[]) {
            try { validate(value, previous); validate({ ...value, documentId: document.id }, document) } catch { continue }
            const existing = notes.get([namespace, document.id, value.id])
            existing.onsuccess = () => {
              // Preserve an already migrated record, including subsequent user edits.
              if (!existing.result) notes.put({ ...value, documentId: document.id })
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
      }, options)
    },
    async list(document: ReadingDocument): Promise<{ items: ReadingNote[]; damagedIds: string[] }> {
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
        title: input.title.trim(), text: input.text?.trim() ?? '', excerpt: input.kind === 'highlight' && input.range ? readingRangeText(document, input.range) : paragraph?.text.slice(input.anchor.characterOffset, input.anchor.characterOffset + 1000) ?? '',
        anchor: { ...input.anchor }, createdAt: new Date().toISOString(),
        ...(input.range ? { range: { start: { ...input.range.start }, end: { ...input.range.end } } } : {}),
      }
      validate(note, document)
      if (note.kind === 'note' && !note.text) throw new Error('메모 내용을 입력해 주세요.')
      await readingTransaction<void>(['notes'], 'readwrite', tx => { tx.objectStore('notes').add(note) }, options)
      return validate(note, document)
    },
    async remove(documentId: string, id: string): Promise<void> {
      await readingTransaction<void>(['notes'], 'readwrite', tx => { tx.objectStore('notes').delete([namespace, documentId, id]) }, options)
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
