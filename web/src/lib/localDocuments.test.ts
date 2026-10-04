import { webcrypto } from 'node:crypto'
import { IDBFactory } from 'fake-indexeddb'
import { beforeAll, describe, expect, it, vi } from 'vitest'
import { zipSync, strToU8 } from 'fflate'
import { createLocalDocuments, decodeLocalText, parseLocalDocument, textParagraphs, localDocumentForTranslation } from './localDocuments'
import { createReadingNotes } from './readingNotes'
import { resolveEpubPath, safeImageType } from './epubDocument'

beforeAll(() => { vi.stubGlobal('crypto', webcrypto) })
function file(name: string, value: string | Uint8Array) {
  const bytes = typeof value === 'string' ? new TextEncoder().encode(value) : value
  return { name, size: bytes.length, arrayBuffer: async () => Uint8Array.from(bytes).buffer }
}

function epub(entries: Record<string, string | Uint8Array> = {}) {
  return zipSync(Object.fromEntries(Object.entries({
    'mimetype': 'application/epub+zip',
    'META-INF/container.xml': '<container><rootfiles><rootfile full-path="OPS/book.opf"/></rootfiles></container>',
    'OPS/book.opf': '<package><metadata><title>순서 있는 책</title><language>ko</language></metadata><manifest><item id="second" href="second.xhtml"/><item id="first" href="first.xhtml"/></manifest><spine><itemref idref="first"/><itemref idref="second"/></spine></package>',
    'OPS/first.xhtml': '<html><head><title>첫 장</title></head><body><p>첫 번째 본문</p><script>window.evil=true</script><iframe src="https://example.invalid"/><img src="https://example.invalid/a.png" alt="외부 그림"/></body></html>',
    'OPS/second.xhtml': '<html><head><title>둘째 장</title></head><body><p>두 번째 본문</p></body></html>',
    ...entries,
  }).map(([path, content]) => [path, typeof content === 'string' ? strToU8(content) : content])))
}

describe('local document imports and account storage', () => {
  it('uses original file bytes for identity and keeps TXT markup inert', async () => {
    const bytes = '첫 문단\r\n\r\n<script>literal</script> 😀'
    const first = await parseLocalDocument(file('one.txt', bytes)), renamed = await parseLocalDocument(file('renamed.txt', bytes))
    expect(first.id).toBe(renamed.id)
    expect(first.kind).toBe('local')
    expect(first.paragraphs.map(p => p.text)).toEqual(['첫 문단', '<script>literal</script> 😀'])
    expect(first.paragraphs[1].paragraphId).toBe(`${first.id}:p1`)
    expect(await parseLocalDocument(file('different.txt', bytes + 'x'))).not.toMatchObject({ id: first.id })
  })
  it('decodes BOM UTF-16 and requires explicit encoding for invalid UTF-8', () => {
    expect(decodeLocalText(new Uint8Array([0xff, 0xfe, 0x41, 0, 0x00, 0xac]))).toEqual({ text: 'A가', encoding: 'utf-16le' })
    expect(decodeLocalText(new Uint8Array([0xfe, 0xff, 0x00, 0x41]))).toEqual({ text: 'A', encoding: 'utf-16be' })
    expect(() => decodeLocalText(new Uint8Array([0xc0, 0xaf]))).toThrow('인코딩')
    expect(decodeLocalText(new Uint8Array([0xb0, 0xa1]), 'euc-kr').text).toBe('가')
  })
  it('bounds inputs, cancels, and never splits a surrogate pair at the paragraph limit', async () => {
    await expect(parseLocalDocument(file('empty.txt', ''))).rejects.toThrow('32MB')
    await expect(parseLocalDocument(file('blank.txt', '\n '))).rejects.toThrow('본문')
    const controller = new AbortController(); controller.abort()
    await expect(parseLocalDocument(file('text.txt', 'text'), { signal: controller.signal })).rejects.toMatchObject({ name: 'AbortError' })
    const original = 'a'.repeat(7999) + '😀tail'
    expect(textParagraphs(original).join('')).toBe(original)
    expect(textParagraphs(original)[0]).toHaveLength(7999)
  })
  it('honors EPUB spine, removes active content, and preserves the chapter outline', async () => {
    const doc = await parseLocalDocument(file('book.epub', epub()))
    expect(doc.bookTitle).toBe('순서 있는 책')
    expect(doc.paragraphs.map(p => p.text)).toEqual(['첫 번째 본문', '[외부 그림]', '두 번째 본문'])
    expect(doc.outline?.map(item => item.title)).toEqual(['첫 장', '둘째 장'])
    expect(doc.assets?.images).toEqual([])
    expect(doc.outline?.[1].paragraphId).toBe(doc.paragraphs[2].paragraphId)
  })
  it('rejects missing spine content, path traversal, entities and oversized ZIP entries', async () => {
    await expect(parseLocalDocument(file('bad.epub', epub({ 'OPS/first.xhtml': '<!DOCTYPE x [<!ENTITY evil SYSTEM "file:///x">]><html><body>&evil;</body></html>' })))).rejects.toThrow('엔터티')
    await expect(parseLocalDocument(file('bad.epub', epub({ 'OPS/book.opf': '<package><manifest/><spine><itemref idref="absent"/></spine></package>' })))).rejects.toThrow('존재하지')
    expect(() => resolveEpubPath('OPS/book.opf', '../../outside')).toThrow('경로')
    expect(() => resolveEpubPath('OPS/book.opf', 'https://bad.example/a')).toThrow('외부')
    expect(resolveEpubPath('OPS/text/a.xhtml', '../images/a.png')).toBe('OPS/images/a.png')
    expect(safeImageType(new TextEncoder().encode('<svg onload="evil()"/>'))).toBeUndefined()
    await expect(parseLocalDocument(file('large.epub', epub({ 'huge': new Uint8Array(9 * 1024 * 1024) })))).rejects.toThrow('크기')
  })
  it('keeps safe embedded raster images as blobs and rejects disguised SVG', async () => {
    const png = new Uint8Array(Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=', 'base64'))
    const doc = await parseLocalDocument(file('images.epub', epub({ 'OPS/first.xhtml': '<html><body><p>본문</p><img src="img.png" alt="정상"/><img src="bad.png" alt="위장"/></body></html>', 'OPS/img.png': png, 'OPS/bad.png': '<svg onload="evil()"/>' })))
    expect(doc.assets?.images).toHaveLength(1)
    expect(doc.assets?.images?.[0].blob.type).toBe('image/png')
  })
  it('isolates accounts and deduplicates identical file content without losing notes', async () => {
    const factory = new IDBFactory(), options = { indexedDB: factory }, alice = createLocalDocuments('alice', options), bob = createLocalDocuments('bob', options)
    const doc = await parseLocalDocument(file('same.txt', 'first\n\nsecond'))
    await alice.save(doc); await bob.save(doc)
    const notes = createReadingNotes('alice', options), anchor = { paragraphId: doc.paragraphs[1].paragraphId, characterOffset: 1 }
    await notes.add(doc, { kind: 'bookmark', title: 'second', anchor }); await notes.setPosition(doc, anchor)
    await alice.save({ ...doc, bookTitle: 'renamed' })
    expect((await alice.list()).books).toHaveLength(1)
    expect((await notes.list(doc)).items).toHaveLength(1)
    await alice.remove(doc.id)
    expect((await alice.list()).books).toHaveLength(0)
    expect((await bob.list()).books).toHaveLength(1)
    expect((await notes.list(doc)).items).toHaveLength(0)
    expect(await notes.getPosition(doc)).toBeUndefined()
  })
  it('rejects changing the parsed text under the same file identity and preserves the prior copy', async () => {
    const store = createLocalDocuments('alice', { indexedDB: new IDBFactory() }), doc = await parseLocalDocument(file('same.txt', 'original'))
    await store.save(doc)
    await expect(store.save({ ...doc, paragraphs: [{ ...doc.paragraphs[0], text: 'changed encoding result' }] })).rejects.toThrow('덮어쓸')
    expect((await store.list()).books[0].document.paragraphs[0].text).toBe('original')
  })
  it('uploads only extracted PDF text with original paragraph IDs and enforces server text limits', async () => {
    const doc = await parseLocalDocument(file('text.txt', 'first\n\n[PDF 2]\n\nthird'))
    doc.local = { ...doc.local, format: 'pdf', pdfTextPages: [true, false, true], pdfTextErrorPages: [false, false, false] }
    const upload = localDocumentForTranslation(doc)
    expect(upload.paragraphs.map(p => p.paragraphId)).toEqual([doc.paragraphs[0].paragraphId, doc.paragraphs[2].paragraphId])
    expect(upload.assets).toBeUndefined()
    doc.local.pdfTextPages = [false, false, false]
    expect(() => localDocumentForTranslation(doc)).toThrow('번역할 텍스트')
    doc.local.format = 'txt'; doc.paragraphs = [{ paragraphId: 'p', text: 'x'.repeat(1_000_001) }]
    expect(() => localDocumentForTranslation(doc)).toThrow('100만')
  })
  it('isolates a tampered document while keeping other books visible', async () => {
    const factory = new IDBFactory(), dbName = 'corrupt-local', store = createLocalDocuments('alice', { indexedDB: factory, dbName })
    const first = await parseLocalDocument(file('one.txt', 'one')), second = await parseLocalDocument(file('two.txt', 'two'))
    await store.save(first); await store.save(second)
    const db = await new Promise<IDBDatabase>((resolve, reject) => { const request = factory.open(dbName); request.onsuccess = () => resolve(request.result); request.onerror = () => reject(request.error) })
    await new Promise<void>(resolve => { const tx = db.transaction('documents', 'readwrite'), docs = tx.objectStore('documents'), request = docs.get(['alice', first.id]); request.onsuccess = () => { request.result.document.paragraphs[0].text = 'tampered'; docs.put(request.result) }; tx.oncomplete = () => resolve() }); db.close()
    const result = await store.list()
    expect(result.books.map(item => item.document.id)).toEqual([second.id]); expect(result.damagedIds).toEqual([first.id])
  })
})
