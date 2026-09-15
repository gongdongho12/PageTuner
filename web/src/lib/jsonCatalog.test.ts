import { webcrypto } from 'node:crypto'
import { beforeAll, describe, expect, it, vi } from 'vitest'
import { IDBFactory } from 'fake-indexeddb'
import { catalogFileAllowed, filterJsonCatalog, parseCatalogFile, publicCatalogUrl, validateJsonCatalog, type JsonCatalogEntry } from './jsonCatalog'
import { createJsonCatalogClient } from './jsonCatalogApi'
import { createLocalDocuments, localFileHash } from './localDocuments'

beforeAll(() => vi.stubGlobal('crypto', webcrypto))
const entry: JsonCatalogEntry = { id: 'one', title: 'A book', authors: ['Writer'], format: 'txt', href: 'https://catalog.example/text.txt', language: 'en',
  type: 'text/plain', size: null, checksum: null, updatedAt: null, cover: null, translationHints: { sourceLanguage: 'en', targetLanguages: ['ko'] } }
const catalog = { version: 'pagetuner.catalog.v0', id: 'sample', title: 'Catalog', catalogUrl: 'https://catalog.example/books/catalog.json', updatedAt: null,
  links: [{ rel: 'next', href: 'https://catalog.example/books/catalog.json?page=2', type: null }], items: [entry] }
const json = (value: unknown) => new Response(JSON.stringify(value), { headers: { 'Content-Type': 'application/json' } })

describe('shared JSON catalog and real local importer', () => {
  it('keeps remote next links, searches this page by title and author, and rejects corrupted entries', () => {
    const value = validateJsonCatalog(catalog)
    expect(value.links[0].href).toContain('?page=2')
    expect(filterJsonCatalog(value.items, 'writer')).toEqual([entry])
    expect(filterJsonCatalog(value.items, 'missing')).toEqual([])
    for (const items of [[entry, entry], [null], [{ ...entry, size: -1 }], [{ ...entry, format: 'exe' }]]) {
      expect(() => validateJsonCatalog({ ...catalog, items })).toThrow()
    }
  })

  it('imports verified original bytes through the existing parser and account-scoped IndexedDB', async () => {
    const bytes = new TextEncoder().encode('First paragraph\n\nSecond paragraph'), checksum = `sha256:${await localFileHash(bytes)}`
    const document = await parseCatalogFile({ ...entry, checksum, size: bytes.length }, bytes)
    const factory = new IDBFactory(), alice = createLocalDocuments('alice', { indexedDB: factory }), bob = createLocalDocuments('bob', { indexedDB: factory })
    await alice.save(document)
    expect((await alice.list()).books[0].document).toMatchObject({ kind: 'local', language: 'en', bookTitle: 'A book', paragraphs: [{ text: 'First paragraph' }, { text: 'Second paragraph' }] })
    expect((await bob.list()).books).toEqual([])
    expect(document.local.contentHash).toBe(checksum.slice(7))
    await expect(parseCatalogFile({ ...entry, checksum: `sha256:${'a'.repeat(64)}` }, bytes)).rejects.toMatchObject({ kind: 'integrity' })
    await expect(parseCatalogFile({ ...entry, size: bytes.length + 1 }, bytes)).rejects.toMatchObject({ kind: 'integrity' })
  })

  it('rejects unsupported checksum, unsafe URLs and oversized entries before download', () => {
    for (const url of ['http://example.com/a', 'https://127.0.0.1/', 'https://a.internal/', 'https://user:pass@example.com/', 'https://example.com:8443/', 'https://example.com/#x', 'https://example.com\\@localhost/']) {
      expect(() => publicCatalogUrl(url)).toThrow()
    }
    expect(() => catalogFileAllowed({ ...entry, size: 32 * 1024 * 1024 + 1 })).toThrow('32MB')
    expect(() => catalogFileAllowed({ ...entry, checksum: 'md5:unknown' })).toThrow('SHA-256')
  })
})

describe('same-origin catalog HTTP adapter', () => {
  it('never sends credentials to a catalog host and preserves binary bytes', async () => {
    const bytes = new Uint8Array([0, 255, 15])
    const fetch = vi.fn(async (path: RequestInfo | URL, _options?: RequestInit) => String(path).startsWith('/api/v1/catalog-files')
      ? new Response(bytes, { headers: { 'Content-Type': 'application/octet-stream' } }) : json(catalog))
    const client = createJsonCatalogClient({ username: 'reader', password: 'memory-only' }, { fetch })
    expect(await client.catalog('https://catalog.example/catalog.json')).toEqual(catalog)
    expect(await client.file(entry.href)).toEqual(bytes)
    expect(fetch.mock.calls[0][0]).toBe('/api/v1/catalogs/json?url=https%3A%2F%2Fcatalog.example%2Fcatalog.json')
    expect(fetch.mock.calls[1][1]).toMatchObject({ method: 'GET', redirect: 'error', credentials: 'omit', mode: 'same-origin', cache: 'no-store',
      headers: { Authorization: `Basic ${btoa('reader:memory-only')}` } })
  })

  it('refuses oversized and wrong-content responses and identifies cancellation', async () => {
    const credentials = { username: 'reader', password: 'memory-only' }
    const huge = createJsonCatalogClient(credentials, { fetch: async () => new Response('x', { headers: { 'Content-Type': 'application/octet-stream', 'Content-Length': String(32 * 1024 * 1024 + 1) } }) })
    await expect(huge.file(entry.href)).rejects.toMatchObject({ kind: 'invalid-response' })
    const wrong = createJsonCatalogClient(credentials, { fetch: async () => new Response('<html>login</html>', { headers: { 'Content-Type': 'text/html' } }) })
    await expect(wrong.catalog(catalog.catalogUrl)).rejects.toMatchObject({ kind: 'invalid-response' })
    const controller = new AbortController(); controller.abort()
    const transport = vi.fn()
    await expect(createJsonCatalogClient(credentials, { fetch: transport }).file(entry.href, controller.signal)).rejects.toMatchObject({ kind: 'aborted' })
    expect(transport).not.toHaveBeenCalled()
  })

  it('bounds an unknown-length binary stream and cancels its reader on failure', async () => {
    const cancel = vi.fn(), chunk = new Uint8Array(1024 * 1024)
    const stream = new ReadableStream<Uint8Array>({ pull(controller) { controller.enqueue(chunk) }, cancel })
    const client = createJsonCatalogClient({ username: 'reader', password: 'memory-only' }, { fetch: async () => new Response(stream, { headers: { 'Content-Type': 'application/octet-stream' } }) })
    await expect(client.file(entry.href)).rejects.toMatchObject({ kind: 'invalid-response' })
    expect(cancel).toHaveBeenCalledOnce()
  })
})
