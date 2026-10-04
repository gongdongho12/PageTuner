import { webcrypto } from 'node:crypto'
import { IDBFactory } from 'fake-indexeddb'
import { beforeAll, describe, expect, it, vi } from 'vitest'
import { createLocalDocuments, parseLocalDocument } from './localDocuments'
import { emptyLocalLibraryFilter, filterLocalDocuments, localDocumentFolders, localOrganizationInput } from './localOrganization'

beforeAll(() => { vi.stubGlobal('crypto', webcrypto) })
async function text(name: string) {
  const bytes = new TextEncoder().encode(name)
  return parseLocalDocument({ name: `${name}.txt`, size: bytes.length, arrayBuffer: async () => bytes.buffer })
}
describe('local library classification and filtering', () => {
  it('persists folders, tags and favorites across reimport while isolating accounts', async () => {
    const options = { indexedDB: new IDBFactory() }, alice = createLocalDocuments('alice', options), bob = createLocalDocuments('bob', options), doc = await text('Book')
    await alice.save(doc); await bob.save(doc)
    await alice.organize(doc.id, localOrganizationInput(' Fiction ', 'Fantasy, fantasy, 읽는중', true))
    await alice.save(doc)
    expect((await createLocalDocuments('alice', options).list()).books[0].organization).toEqual({ folder: 'Fiction', tags: ['Fantasy', '읽는중'], favorite: true })
    expect((await bob.list()).books[0].organization).toEqual({ folder: '', tags: [], favorite: false })
    expect((await alice.list()).books[0].document).toEqual(doc)
  })
  it('combines query, folder and favorites filters and leaves stored data unmodified', async () => {
    const store = createLocalDocuments('alice', { indexedDB: new IDBFactory() }), alpha = await text('Alpha'), beta = await text('Beta')
    await store.save(alpha); await store.save(beta)
    await store.organize(alpha.id, localOrganizationInput('소설', 'Space, New', true))
    await store.organize(beta.id, localOrganizationInput('공부', 'Space', false))
    const books = (await store.list()).books, base = emptyLocalLibraryFilter()
    expect(filterLocalDocuments(books, { ...base, query: 'space', favoritesOnly: true }).map(book => book.document.id)).toEqual([alpha.id])
    expect(filterLocalDocuments(books, { ...base, query: 'TXT', folder: '공부' }).map(book => book.document.id)).toEqual([beta.id])
    expect(filterLocalDocuments(books, { ...base, sort: 'title' }).map(book => book.document.bookTitle)).toEqual(['Alpha', 'Beta'])
    expect(localDocumentFolders(books)).toEqual(['공부', '소설'])
    expect((await store.list()).books).toEqual(books)
  })
  it('rejects invalid metadata and cannot resurrect a deleted document', async () => {
    const store = createLocalDocuments('alice', { indexedDB: new IDBFactory() }), doc = await text('Book')
    await store.save(doc)
    await expect(store.organize(doc.id, { folder: 'x'.repeat(201), tags: [], favorite: false })).rejects.toThrow('폴더')
    await store.remove(doc.id)
    await expect(store.organize(doc.id, localOrganizationInput('folder', '', true))).rejects.toThrow('찾지 못했습니다')
    expect((await store.list()).books).toEqual([])
  })
})
