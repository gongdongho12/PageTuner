import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { documentExportFormats, downloadDocumentFileExport, prepareDocumentFileExport, type DocumentExportFormat } from './documentFileExport'
import type { ReadingDocument } from './readingDocument'

type TextVector = { name: string; bookTitle: string; chapterTitle: string; paragraphs: string[]; format: 'TXT' | 'MARKDOWN'; expected: { filename: string; mimeType: string; text: string } }
type FilenameVector = { name: string; bookTitle: string; chapterTitle: string; extension: 'txt' | 'md' | 'pdf' | 'epub'; expected: string }
const vectors = JSON.parse(readFileSync(new URL('../../../contracts/fixtures/document-file-export-v1/vectors.json', import.meta.url), 'utf8')) as TextVector[]
const filenameVectors = JSON.parse(readFileSync(new URL('../../../contracts/fixtures/document-file-export-v1/filename-vectors.json', import.meta.url), 'utf8')) as FilenameVector[]
const rejectedVectors = JSON.parse(readFileSync(new URL('../../../contracts/fixtures/document-file-export-v1/rejected-vectors.json', import.meta.url), 'utf8')) as Omit<TextVector, 'expected'>[]
const sourcePdf = new Uint8Array(readFileSync(new URL('../../../contracts/fixtures/portable-content-proof-v1/source.pdf', import.meta.url)))
const hash = (bytes: Uint8Array) => createHash('sha256').update(bytes).digest('hex')
function textDocument(bookTitle = 'Book', chapterTitle = 'Chapter', paragraphs = ['First body', 'Second body']): ReadingDocument {
  return { id: 'opaque-document-id', bookTitle, chapterTitle, kind: 'translation', language: 'ko', paragraphs: paragraphs.map((text, index) => ({ paragraphId: `opaque-${index}`, text })) }
}
function pdfDocument(): ReadingDocument {
  return { ...textDocument(), local: { format: 'pdf', byteLength: sourcePdf.length, contentHash: hash(sourcePdf), pdfTextErrorPages: [true] }, assets: { pdf: new Blob([Uint8Array.from(sourcePdf).buffer], { type: 'application/pdf' }) } }
}
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.useRealTimers() })

describe('plain document downloads with the shared Kotlin contract', () => {
  it.each(vectors)('matches exact UTF-8 bytes, headings and filenames: $name', async vector => {
    const document = textDocument(vector.bookTitle, vector.chapterTitle, vector.paragraphs)
    const before = structuredClone(document)
    const result = await prepareDocumentFileExport(document, vector.format === 'TXT' ? 'txt' : 'markdown')
    expect(result.name).toBe(vector.expected.filename)
    expect(result.type).toBe(vector.expected.mimeType)
    expect(result.blob.type).toBe(vector.expected.mimeType)
    expect(Buffer.from(await result.blob.arrayBuffer())).toEqual(Buffer.from(vector.expected.text, 'utf8'))
    expect(document).toEqual(before)
  })
  it.each(filenameVectors)('matches portable filename rules: $name', async vector => {
    const document = { ...(vector.extension === 'pdf' ? pdfDocument() : textDocument()), bookTitle: vector.bookTitle, chapterTitle: vector.chapterTitle }
    const result = await prepareDocumentFileExport(document, vector.extension === 'md' ? 'markdown' : vector.extension)
    expect(result.name).toBe(vector.expected)
  })
  it.each(rejectedVectors)('rejects invalid Unicode/control data instead of changing it: $name', async vector => {
    await expect(prepareDocumentFileExport(textDocument(vector.bookTitle, vector.chapterTitle, vector.paragraphs), vector.format === 'TXT' ? 'txt' : 'markdown')).rejects.toThrow()
  })
  it('exports only the displayed text snapshot, never notes, account identities or extension state', async () => {
    const fetcher = vi.fn(); vi.stubGlobal('fetch', fetcher)
    const document = { ...textDocument(' 제목 ', ' 원본 장 ', ['  kept\r\nline\t ', '', '末尾😀  ']),
      serverProgress: { kind: 'TRANSLATION' as const, recordId: 'private-server-record' }, glossaryIdentity: { providerId: 'private-provider', bookId: 'private-book' },
      credentials: { password: 'private-password' }, notes: ['private-note'], extensions: { outbox: 'private-mutation' } }
    const before = structuredClone(document)
    const result = await prepareDocumentFileExport(document, 'txt')
    expect(await result.blob.text()).toBe('제목\n원본 장\n\n  kept\r\nline\t \n\n\n\n末尾😀  \n')
    expect(document).toEqual(before)
    expect(fetcher).not.toHaveBeenCalled()
  })
  it('accepts exact text/paragraph limits and explicitly rejects excess without truncation', async () => {
    const full = textDocument('B', 'C', ['x'.repeat(4_999_998)])
    expect((await prepareDocumentFileExport(full, 'txt')).blob.size).toBe(5_000_004)
    await expect(prepareDocumentFileExport({ ...full, chapterTitle: 'CC' }, 'txt')).rejects.toThrow('크기 제한')
    const many = textDocument('', '', Array(100_000).fill(''))
    expect((await prepareDocumentFileExport(many, 'txt')).blob.size).toBe(200_009)
    many.paragraphs.push({ paragraphId: 'last', text: '' })
    await expect(prepareDocumentFileExport(many, 'txt')).rejects.toThrow('크기 제한')
  })
  it('offers TXT and Markdown for body documents and refuses unknown requested formats', async () => {
    expect(documentExportFormats(textDocument())).toEqual(['txt', 'markdown', 'epub'])
    await expect(prepareDocumentFileExport(textDocument(), 'unsupported' as DocumentExportFormat)).rejects.toThrow()
  })
})

describe('bounded original PDF download', () => {
  it('keeps ZIP PDF export available through the real canonical reader mapper', async () => {
    const { writeExchange, readExchange } = await import('./libraryExchange')
    const { exchangeReadingDocument } = await import('./exchangeLibrary')
    const path = `assets/${hash(sourcePdf)}`, createdAt = '2026-10-05T00:00:00Z'
    const archive = await readExchange(await writeExchange({ createdAt, documents: [{
      id: 'original:opaque-pdf', bookTitle: 'ZIP PDF', chapterTitle: 'ZIP PDF', language: 'EN', kind: 'local',
      paragraphs: [], outline: [], notes: [], glossary: [], organization: { folder: '', tags: [], favorite: false },
      assets: [{ path, role: 'pdf' }], extensions: { android: { pageCount: 999 } },
    }], assets: [{ path, mimeType: 'application/pdf', bytes: sourcePdf }] }))
    const document = exchangeReadingDocument({ id: 'exchange:opaque-copy', document: archive.documents[0], assets: archive.assets, importedAt: createdAt, checksum: '', integratedNoteIds: [] })
    expect(documentExportFormats(document)).toEqual(['pdf'])
    expect(document.paragraphs).toEqual([])
    const result = await prepareDocumentFileExport(document, 'pdf')
    expect(result.name).toBe('ZIP PDF.pdf')
    expect(new Uint8Array(await result.blob.arrayBuffer())).toEqual(sourcePdf)
  })
  it('returns byte-identical original PDF even with failed extraction, without a text-format fallback', async () => {
    const fetcher = vi.fn(); vi.stubGlobal('fetch', fetcher); vi.stubGlobal('crypto', undefined)
    const document = pdfDocument(), before = structuredClone(document)
    expect(documentExportFormats(document)).toEqual(['pdf'])
    const result = await prepareDocumentFileExport(document, 'pdf')
    expect(result.type).toBe('application/pdf'); expect(result.blob.type).toBe('application/pdf')
    expect(new Uint8Array(await result.blob.arrayBuffer())).toEqual(sourcePdf)
    expect(hash(new Uint8Array(await result.blob.arrayBuffer()))).toBe(document.local?.contentHash)
    expect(document).toEqual(before); expect(fetcher).not.toHaveBeenCalled()
    for (const format of ['txt', 'markdown', 'epub'] as const) await expect(prepareDocumentFileExport(document, format)).rejects.toThrow()
  })
  it('refuses absent bytes, missing/wrong hash or length and oversize PDF before reading', async () => {
    const original = pdfDocument()
    for (const document of [
      { ...original, assets: undefined }, { ...original, local: undefined },
      { ...original, local: { ...original.local!, contentHash: '0'.repeat(64) } },
      { ...original, local: { ...original.local!, byteLength: sourcePdf.length + 1 } },
    ]) await expect(prepareDocumentFileExport(document, 'pdf')).rejects.toThrow()
    expect(documentExportFormats({ ...original, assets: undefined })).toEqual([])
    expect(documentExportFormats({ ...original, local: undefined })).toEqual([])
    const huge = new Blob([new Uint8Array(32 * 1024 * 1024 + 1)]), read = vi.spyOn(huge, 'arrayBuffer')
    await expect(prepareDocumentFileExport({ ...original, assets: { pdf: huge }, local: { ...original.local!, byteLength: huge.size } }, 'pdf')).rejects.toThrow('크기 제한')
    expect(read).not.toHaveBeenCalled()
  })
  it('captures selected metadata once and discards an aborted delayed byte read', async () => {
    const document = pdfDocument(), expectedName = 'Book - Chapter.pdf'
    let release!: (value: ArrayBuffer) => void
    vi.spyOn(document.assets!.pdf!, 'arrayBuffer').mockImplementation(() => new Promise(resolve => { release = resolve }))
    const pending = prepareDocumentFileExport(document, 'pdf')
    document.bookTitle = 'Later selection'; document.local!.contentHash = '0'.repeat(64)
    release(Uint8Array.from(sourcePdf).buffer)
    expect((await pending).name).toBe(expectedName)
    const another = pdfDocument(), controller = new AbortController()
    vi.spyOn(another.assets!.pdf!, 'arrayBuffer').mockImplementation(() => new Promise(resolve => { release = resolve }))
    const cancelled = prepareDocumentFileExport(another, 'pdf', controller.signal)
    controller.abort(); release(Uint8Array.from(sourcePdf).buffer)
    await expect(cancelled).rejects.toMatchObject({ name: 'AbortError' })
    await expect(prepareDocumentFileExport(textDocument(), 'txt', controller.signal)).rejects.toMatchObject({ name: 'AbortError' })
  })
})

describe('browser download resource lifetime', () => {
  it('attaches the download link for activation, removes it and later revokes its object URL', () => {
    vi.useFakeTimers()
    const link = { href: '', download: '', hidden: false, click: vi.fn(), remove: vi.fn() }, appendChild = vi.fn()
    vi.stubGlobal('document', { createElement: () => link, body: { appendChild } })
    const create = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:document-export'), revoke = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    const value = { blob: new Blob(['text']), name: '다운로드.txt', type: 'text/plain' }
    downloadDocumentFileExport(value)
    expect(create).toHaveBeenCalledWith(value.blob); expect(appendChild).toHaveBeenCalledWith(link)
    expect(link.download).toBe(value.name); expect(link.click).toHaveBeenCalledTimes(1); expect(link.remove).toHaveBeenCalledTimes(1)
    expect(revoke).not.toHaveBeenCalled(); vi.runAllTimers(); expect(revoke).toHaveBeenCalledWith('blob:document-export')
  })
  it('revokes immediately if link creation or click fails', () => {
    const value = { blob: new Blob(['text']), name: 'download.txt', type: 'text/plain' }
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:failed-export')
    const revoke = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    vi.stubGlobal('document', { createElement: () => { throw new Error('No document') } })
    expect(() => downloadDocumentFileExport(value)).toThrow('No document')
    expect(revoke).toHaveBeenCalledTimes(1)
    const remove = vi.fn()
    vi.stubGlobal('document', { createElement: () => ({ click: () => { throw new Error('No activation') }, remove }), body: { appendChild() {} } })
    expect(() => downloadDocumentFileExport(value)).toThrow('No activation')
    expect(remove).toHaveBeenCalledTimes(1); expect(revoke).toHaveBeenCalledTimes(2)
  })
})
