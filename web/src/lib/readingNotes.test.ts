import { webcrypto } from 'node:crypto'
import { IDBFactory } from 'fake-indexeddb'
import { beforeAll, describe, expect, it, vi } from 'vitest'
import { createReadingNotes } from './readingNotes'
import type { ReadingDocument } from './readingDocument'

beforeAll(() => { vi.stubGlobal('crypto', webcrypto) })
const document: ReadingDocument = { id: 'source-or-translation-or-local', bookTitle: 'Book', chapterTitle: 'Chapter', language: 'ko', kind: 'original', paragraphs: [{ paragraphId: 'stable-p1', text: '문단 본문 😀' }] }
const anchor = { paragraphId: 'stable-p1', characterOffset: 3 }

describe('account and document scoped reading notes', () => {
  it('preserves independent concurrent notes across fresh repository instances', async () => {
    const options = { indexedDB: new IDBFactory() }, one = createReadingNotes('alice', options), two = createReadingNotes('alice', options)
    await Promise.all([one.add(document, { kind: 'bookmark', title: 'One', anchor }), two.add(document, { kind: 'note', title: 'Two', text: 'My note', anchor })])
    expect((await one.list(document)).items).toHaveLength(2)
    expect((await createReadingNotes('bob', options).list(document)).items).toEqual([])
    expect((await one.list({ ...document, id: 'other' })).items).toEqual([])
    await one.setPosition(document, anchor)
    expect(await two.getPosition(document)).toEqual(anchor)
  })
  it('rejects foreign paragraph anchors, empty notes and overly long fields', async () => {
    const notes = createReadingNotes('alice', { indexedDB: new IDBFactory() })
    await expect(notes.add(document, { kind: 'note', title: 'Title', text: '', anchor })).rejects.toThrow('메모 내용')
    await expect(notes.add(document, { kind: 'bookmark', title: 'a'.repeat(201), anchor })).rejects.toThrow('메모')
    await expect(notes.add(document, { kind: 'bookmark', title: 'Title', anchor: { paragraphId: 'another', characterOffset: 0 } })).rejects.toThrow('메모')
    await expect(notes.setPosition(document, { ...anchor, characterOffset: -1 })).rejects.toThrow('읽기 위치')
  })
  it('retries the actual database open after a previous failure', async () => {
    const factory = new IDBFactory(), notes = createReadingNotes('alice', { indexedDB: factory })
    const spy = vi.spyOn(factory, 'open').mockImplementationOnce(() => { throw new DOMException('Blocked', 'SecurityError') })
    await expect(notes.list(document)).rejects.toThrow('Blocked')
    expect((await notes.list(document)).items).toEqual([])
    expect(spy).toHaveBeenCalledTimes(2); spy.mockRestore()
  })
})
