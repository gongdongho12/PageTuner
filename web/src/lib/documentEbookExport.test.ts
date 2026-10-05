import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { unzipSync } from 'fflate'
import { DOMParser } from '@xmldom/xmldom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { prepareDocumentEbookExport } from './documentEbookExport'
import { prepareDocumentFileExport } from './documentFileExport'
import type { ReadingDocument } from './readingDocument'

type Vector = { name: string; bookTitle: string; chapterTitle: string; language: string | null; paragraphs: string[]; expected: { filename: string; mimeType: string; entries: { path: string; text: string }[] } }
const fixtures = JSON.parse(readFileSync(new URL('../../../contracts/fixtures/document-ebook-export-v1/vectors.json', import.meta.url), 'utf8')) as Vector[]
const rejected = JSON.parse(readFileSync(new URL('../../../contracts/fixtures/document-ebook-export-v1/rejected-vectors.json', import.meta.url), 'utf8')) as Omit<Vector, 'expected'>[]
const decode = (bytes: Uint8Array) => new TextDecoder('utf-8', { fatal: true }).decode(bytes)
function document(vector = fixtures[0]): ReadingDocument {
  return { id: 'private-document-id', kind: 'translation', bookTitle: vector.bookTitle, chapterTitle: vector.chapterTitle, language: vector.language as string,
    paragraphs: vector.paragraphs.map((text, index) => ({ paragraphId: `private-paragraph-${index}`, text })) }
}
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals() })

describe('shared standalone EPUB publication', () => {
  it.each(fixtures)('matches each Kotlin resource byte for byte: $name', async vector => {
    const input = document(vector), before = structuredClone(input), result = await prepareDocumentEbookExport(input)
    const data = new Uint8Array(await result.blob.arrayBuffer()), entries = unzipSync(data)
    expect(result.name).toBe(vector.expected.filename)
    expect(result.type).toBe(vector.expected.mimeType); expect(result.blob.type).toBe(vector.expected.mimeType)
    expect(Object.keys(entries)).toEqual(vector.expected.entries.map(entry => entry.path))
    for (const entry of vector.expected.entries) expect(Buffer.from(entries[entry.path])).toEqual(Buffer.from(entry.text, 'utf8'))
    const identifier = createHash('sha256').update(entries['EPUB/content.xhtml']).digest('hex')
    expect(decode(entries['EPUB/package.opf'])).toContain(`urn:sha256:${identifier}`)
    // EPUB mandates an uncompressed, first mimetype local file header without extra fields.
    const view = new DataView(data.buffer, data.byteOffset, data.byteLength)
    expect(view.getUint32(0, true)).toBe(0x04034b50)
    expect(view.getUint16(8, true)).toBe(0)
    expect(view.getUint16(26, true)).toBe(8); expect(view.getUint16(28, true)).toBe(0)
    expect(decode(data.subarray(30, 38))).toBe('mimetype')
    expect(view.getUint32(22, true)).toBe(20)
    expect(decode(data.subarray(38, 58))).toBe('application/epub+zip')
    expect(view.getUint16(58 + 8, true)).toBe(8)
    expect(input).toEqual(before)
  })
  it.each(rejected)('rejects invalid source text before producing an EPUB: $name', async vector => {
    await expect(prepareDocumentEbookExport(document(vector as Vector))).rejects.toThrow()
  })
  it('preserves whitespace and hostile-looking text as XML text without source/account metadata', async () => {
    const input = { ...document(), bookTitle: '<script>Book</script>', chapterTitle: 'Exact & chapter', language: 'ko-KR',
      paragraphs: [{ paragraphId: 'private-paragraph-id', text: '  A\r\nB\t <img src="https://invalid/x"> & \' \n ' }, { paragraphId: 'empty', text: '' }, { paragraphId: 'last', text: '끝😀' }],
      credentials: 'private-password', notes: ['private-note'], serverProgress: { kind: 'TRANSLATION' as const, recordId: 'private-record' } }
    const fetcher = vi.fn(); vi.stubGlobal('fetch', fetcher); vi.stubGlobal('crypto', undefined)
    const result = await prepareDocumentEbookExport(input), entries = unzipSync(new Uint8Array(await result.blob.arrayBuffer()))
    const parsed = new DOMParser().parseFromString(decode(entries['EPUB/content.xhtml']), 'application/xml')
    expect(Array.from(parsed.getElementsByTagName('p')).map(element => element.textContent)).toEqual(input.paragraphs.map(p => p.text))
    expect(parsed.getElementsByTagName('img').length).toBe(0); expect(parsed.getElementsByTagName('script').length).toBe(0)
    expect(Object.values(entries).map(decode).join('')).not.toMatch(/private-(document|paragraph|password|note|record)/)
    expect(fetcher).not.toHaveBeenCalled()
  })
  it('round-trips a basic generated publication through the real EPUB parser including its last paragraph', async () => {
    const { parseEpub } = await import('./epubDocument')
    const input = document(); input.paragraphs.push({ paragraphId: 'last', text: 'Exact final tail 끝😀' })
    const result = await prepareDocumentEbookExport(input), parsed = await parseEpub(new Uint8Array(await result.blob.arrayBuffer()))
    expect(parsed.title).toBe('Book - Chapter'); expect(parsed.language).toBe('en')
    expect(parsed.chapters.flatMap(chapter => chapter.paragraphs).join('\n')).toContain('Exact final tail 끝😀')
  })
  it('retains the selection snapshot across hashing/lazy loading and rejects cancellation', async () => {
    const input = document(), pending = prepareDocumentFileExport(input, 'epub')
    input.bookTitle = 'Changed title'; input.language = 'ko'; input.paragraphs[0].text = 'Changed body'
    const entries = unzipSync(new Uint8Array(await (await pending).blob.arrayBuffer()))
    expect(decode(entries['EPUB/content.xhtml'])).toBe(fixtures[0].expected.entries.find(entry => entry.path === 'EPUB/content.xhtml')!.text)
    const controller = new AbortController(), cancelled = prepareDocumentEbookExport(document(), controller.signal)
    controller.abort()
    await expect(cancelled).rejects.toMatchObject({ name: 'AbortError' })
    await expect(prepareDocumentEbookExport(document(), controller.signal)).rejects.toMatchObject({ name: 'AbortError' })
  })
  it('refuses PDF extraction and input/UTF-8 expansion limits without truncating the body', async () => {
    await expect(prepareDocumentEbookExport({ ...document(), assets: { pdf: new Blob(['source']) } })).rejects.toThrow()
    const long = document(); long.paragraphs = [{ paragraphId: 'whole', text: 'x'.repeat(5_000_001) }]
    await expect(prepareDocumentEbookExport(long)).rejects.toThrow('크기 제한')
    for (const body of ['한'.repeat(2_800_000), '&'.repeat(1_680_000)]) {
      const expanded = document(); expanded.paragraphs = [{ paragraphId: 'whole', text: body }]
      await expect(prepareDocumentEbookExport(expanded)).rejects.toThrow('크기 제한')
    }
    const oversizedTitle = document(); oversizedTitle.bookTitle = '&'.repeat(1_000_000)
    await expect(prepareDocumentEbookExport(oversizedTitle)).rejects.toThrow('크기 제한')
    const many = document(); many.paragraphs = Array.from({ length: 100_001 }, (_, index) => ({ paragraphId: `${index}`, text: '' }))
    await expect(prepareDocumentEbookExport(many)).rejects.toThrow('크기 제한')
  })
})
