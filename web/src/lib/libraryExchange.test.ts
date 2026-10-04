import { createHash, webcrypto } from 'node:crypto'
import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { beforeAll, describe, expect, it, vi } from 'vitest'
import { strToU8, unzipSync, zipSync } from 'fflate'
import { exchangeLimits, readExchange, validateExchangeDocument, writeExchange } from './libraryExchange'

beforeAll(() => vi.stubGlobal('crypto', webcrypto))
const fixtureUrl = new URL('../../../contracts/fixtures/library-exchange-v1/portable-v1.zip', import.meta.url)
const expectedUrl = new URL('../../../contracts/fixtures/library-exchange-v1/expected.json', import.meta.url)
const kotlinUrl = new URL('../../../.gradle-home/kotlin-portable-roundtrip.zip', import.meta.url)
const webUrl = new URL('../../../.gradle-home/web-portable-roundtrip.zip', import.meta.url)
const timestamp = '2026-09-15T00:00:00Z'
const hash = (bytes: Uint8Array) => createHash('sha256').update(bytes).digest('hex')
const fixture = () => new Uint8Array(readFileSync(fixtureUrl))
const minimum = () => ({ id: 'minimal', bookTitle: 'Book', chapterTitle: 'Chapter', language: 'und', kind: 'original', paragraphs: [{ paragraphId: 'p', text: 'Hello 🌏.' }] })

/** Make independently hashed wire bytes without going through the validator under test. */
function documentZip(input: unknown, raw = false): Uint8Array {
  const content = raw ? strToU8(input as string) : strToU8(JSON.stringify(input)), sha256 = hash(content), path = `documents/${sha256}.json`
  return zipSync({ 'manifest.json': strToU8(JSON.stringify({ format: 'pageturner.library', version: 1, createdAt: timestamp, documents: [{ path, sha256, bytes: content.length }], assets: [] })), [path]: content })
}
function changedManifest(change: (value: Record<string, any>, files: Record<string, Uint8Array>) => void) {
  const files = unzipSync(fixture()), manifest = JSON.parse(new TextDecoder().decode(files['manifest.json']))
  change(manifest, files); files['manifest.json'] = strToU8(JSON.stringify(manifest)); return zipSync(files)
}
function changedContainer(change: (view: DataView, central: number[], bytes: Uint8Array) => void) {
  const bytes = zipSync(unzipSync(fixture()), { level: 0 }), view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  const end = bytes.length - 22, entries: number[] = []; let at = view.getUint32(end + 16, true)
  for (let i = 0; i < view.getUint16(end + 10, true); i++) {
    entries.push(at); at += 46 + view.getUint16(at + 28, true) + view.getUint16(at + 30, true) + view.getUint16(at + 32, true)
  }
  change(view, entries, bytes); return bytes
}

describe('portable ZIP contract shared by Kotlin and web', () => {
  it('reads the common fixture and preserves documents, UTF-16 positions and binary assets on re-export', async () => {
    const expected = JSON.parse(readFileSync(expectedUrl, 'utf8')), value = await readExchange(fixture())
    expect(value.createdAt).toBe(expected.createdAt)
    expect(value.documents.map(doc => doc.id)).toEqual(expected.documentIds)
    expect(value.documents[0].paragraphs[0].text).toBe(expected.originalFirstParagraph)
    expect(value.documents[0].position).toEqual(expected.position)
    expect(value.documents[1].paragraphs[0].text).toBe(expected.translationFirstParagraph)
    expect(value.assets.map(asset => hash(asset.bytes))).toEqual(expected.assetSha256)
    expect(value.documents[0].glossary[0].kind).toBe('TERM')
    expect(await readExchange(await writeExchange(value))).toEqual(value)
  })

  it.runIf(existsSync(kotlinUrl))('reads Java ZIP data descriptors and writes a web package for the reciprocal Kotlin test', async () => {
    const native = await readExchange(new Uint8Array(readFileSync(kotlinUrl))), expected = await readExchange(fixture())
    expect(native).toEqual(expected)
    const written = await writeExchange(native)
    expect(await readExchange(written)).toEqual(native)
    writeFileSync(webUrl, written)
  })

  it('preserves asset-only and empty-text documents plus metadata wider than the web editor', async () => {
    const source = await readExchange(fixture()), pdf = source.assets.find(a => a.mimeType === 'application/pdf')!
    const assetOnly = validateExchangeDocument({ ...minimum(), id: 'image-only', paragraphs: [], assets: [{ path: pdf.path, role: 'pdf' }], extensions: { native: { page: 2 } } })
    const wide = validateExchangeDocument({ ...minimum(), id: 'wide', paragraphs: [{ paragraphId: 'p', text: '' }], position: { paragraphId: 'p', characterOffset: 0 },
      notes: [{ id: 'note', kind: 'note', title: '', text: 't'.repeat(10_000), excerpt: 'e'.repeat(10_000), anchor: { paragraphId: 'p', characterOffset: 0 }, createdAt: timestamp,
        range: { start: { paragraphId: 'p', characterOffset: 0 }, end: { paragraphId: 'p', characterOffset: 0 } } }],
      organization: { folder: 'f'.repeat(500), tags: Array.from({ length: 100 }, (_, i) => `${i}${'t'.repeat(197)}`), favorite: true },
      glossary: [{ source: 's'.repeat(2000), target: 't'.repeat(2000), kind: 'NativeSpecificTermKind', displayTerm: 'd'.repeat(2000) }] })
    expect(wide.glossary[0]).toMatchObject({ kind: 'NativeSpecificTermKind', enabled: true, caseSensitive: true })
    expect(await readExchange(await writeExchange({ createdAt: timestamp, documents: [assetOnly, wide], assets: [pdf] }))).toEqual({ createdAt: timestamp, documents: [assetOnly, wide], assets: [pdf] })
    const eof = await readExchange(documentZip({ ...minimum(), position: { paragraphId: 'p', characterOffset: 'Hello 🌏.'.length } }))
    expect(eof.documents[0].position?.characterOffset).toBe(9)
    const defaults = (await readExchange(documentZip(minimum()))).documents[0]
    expect(defaults).toMatchObject({ outline: [], notes: [], assets: [], glossary: [], organization: { folder: '', tags: [], favorite: false } })
  })

  it('keeps passive unknown extensions and rejects exactly the forbidden credential keys at any depth', async () => {
    const extensions = { tokenCount: 5, passwordHintAllowedByFormat: 'passive value', source: { secretary: 'character name', metadata: ['one', null, true] } }
    const value = await readExchange(documentZip({ ...minimum(), extensions }))
    expect((await readExchange(await writeExchange(value))).documents[0].extensions).toEqual(extensions)
    for (const key of ['password', 'API-key', 'Basic_Auth', 'access.token', 'Set-Cookie']) {
      await expect(readExchange(documentZip({ ...minimum(), extensions: { outer: [{ [key]: 'forbidden' }] } }))).rejects.toThrow()
    }
    expect(() => validateExchangeDocument({ ...minimum(), extensions: { bad: '\ud800' } })).toThrow()
    expect(() => validateExchangeDocument({ ...minimum(), extensions: { bad: Number.POSITIVE_INFINITY } })).toThrow()
  })

  it('rejects unsafe integral metadata before JavaScript can round it while retaining finite binary64 values', async () => {
    const extensions = { minimum: Number.MIN_SAFE_INTEGER, maximum: Number.MAX_SAFE_INTEGER, ratio: 0.125, small: 1.5e-20 }
    expect((await readExchange(documentZip({ ...minimum(), extensions }))).documents[0].extensions).toEqual(extensions)
    const prefix = JSON.stringify(minimum()).slice(0, -1) + ',"extensions":{"number":'
    for (const number of ['9007199254740993', '-9007199254740993', '9.007199254740993e15', '1e309', '1e-400']) {
      await expect(readExchange(documentZip(prefix + number + '}}', true))).rejects.toThrow()
    }
    // The format defines numeric metadata as binary64; exact decimal data is a string.
    const decimal = await readExchange(documentZip(prefix + '0.10000000000000001}}', true))
    expect(decimal.documents[0].extensions).toEqual({ number: 0.1 })
    expect((await readExchange(await writeExchange(decimal))).documents[0].extensions).toEqual({ number: 0.1 })
    expect(() => validateExchangeDocument({ ...minimum(), extensions: { page: Number.MAX_SAFE_INTEGER + 1 } })).toThrow()
  })

  it('rejects unknown fields and null aliases rather than silently discarding data', async () => {
    for (const changed of [
      { ...minimum(), accountId: 'private' }, { ...minimum(), paragraphs: [{ paragraphId: 'p', text: 'body', ordinal: 1 }] },
      { ...minimum(), position: { paragraphId: 'p', characterOffset: 0, page: 1 } },
      { ...minimum(), organization: { folder: '', tags: [], favorite: false, extra: true } },
      { ...minimum(), glossary: [{ source: 'Hello', target: '안녕', extra: true }] },
      { ...minimum(), outline: [{ title: 'One', paragraphId: 'p', extra: true }] },
      ...['outline', 'notes', 'glossary', 'assets', 'organization', 'position', 'extensions'].map(key => ({ ...minimum(), [key]: null })),
      { ...minimum(), organization: {} }, { ...minimum(), language: 'not_a_tag' }, { ...minimum(), language: 'x' },
    ]) await expect(readExchange(documentZip(changed))).rejects.toThrow()
    await expect(readExchange(changedManifest(manifest => { manifest.extra = true }))).rejects.toThrow()
    await expect(readExchange(changedManifest(manifest => { manifest.documents[0].extra = true }))).rejects.toThrow()
  })

  it('rejects unsupported versions, missing assets, wrong hashes, duplicate paths and undeclared payloads', async () => {
    await expect(readExchange(changedManifest(manifest => { manifest.version = 2 }))).rejects.toThrow('버전')
    await expect(readExchange(changedManifest((manifest, files) => { delete files[manifest.assets[0].path] }))).rejects.toThrow()
    await expect(readExchange(changedManifest((manifest, files) => { const path = manifest.assets[0].path; files[path] = Uint8Array.from(files[path]); files[path][0] ^= 1 }))).rejects.toThrow()
    await expect(readExchange(changedManifest(manifest => { manifest.documents.push(manifest.documents[0]) }))).rejects.toThrow()
    await expect(readExchange(changedManifest((_manifest, files) => { files['assets/' + 'a'.repeat(64)] = strToU8('unlisted') }))).rejects.toThrow()
    await expect(readExchange(changedManifest((_manifest, files) => { files['../outside.txt'] = strToU8('outside') }))).rejects.toThrow()
    const value = await readExchange(fixture())
    await expect(writeExchange({ ...value, assets: [...value.assets, value.assets[0]] })).rejects.toThrow()
    await expect(writeExchange({ ...value, documents: [...value.documents, value.documents[0]] })).rejects.toThrow()
  })

  it('enforces declared ZIP byte limits, actual text limits and nonempty assets', async () => {
    const manifestTooLarge = unzipSync(fixture()); manifestTooLarge['manifest.json'] = new Uint8Array(exchangeLimits.manifest + 1)
    await expect(readExchange(zipSync(manifestTooLarge))).rejects.toThrow()
    await expect(readExchange(new Uint8Array(exchangeLimits.archive + 1))).rejects.toThrow()
    expect(() => validateExchangeDocument({ ...minimum(), paragraphs: [{ paragraphId: 'p', text: 'x'.repeat(5_000_001) }] })).toThrow()
    expect(() => validateExchangeDocument({ ...minimum(), paragraphs: [] })).toThrow()
    expect(() => validateExchangeDocument({ ...minimum(), organization: { folder: '', tags: ['same', 'same'], favorite: false } })).toThrow()
    const empty = new Uint8Array(), sha256 = hash(empty), path = `assets/${sha256}`
    const value = await readExchange(fixture())
    await expect(writeExchange({ ...value, documents: [{ ...value.documents[0], assets: [{ path, role: 'pdf' }] }], assets: [{ path, mimeType: 'application/pdf', bytes: empty }] })).rejects.toThrow()
  })

  it('accepts stored entries while rejecting damaged, encrypted and mismatched ZIP containers before exposing data', async () => {
    expect((await readExchange(zipSync(unzipSync(fixture()), { level: 0 }))).documents).toHaveLength(3)
    for (const invalid of [fixture().subarray(0, fixture().length - 1), Uint8Array.from([...fixture(), 0]), Uint8Array.from([0, ...fixture()]),
      changedContainer((view, [central]) => { const local = view.getUint32(central + 42, true); view.setUint16(central + 8, 1, true); view.setUint16(local + 6, 1, true) }),
      changedContainer((view, [central]) => { const local = view.getUint32(central + 42, true); view.setUint16(central + 10, 99, true); view.setUint16(local + 8, 99, true) }),
      changedContainer((view, [central]) => { view.setUint32(central + 38, 0xa0000000, true) }),
      changedContainer((view, [central]) => { const local = view.getUint32(central + 42, true); view.setUint8(local + 30, 0x58) }),
      changedContainer((view, [central]) => { const local = view.getUint32(central + 42, true); const crc = view.getUint32(central + 16, true) ^ 1; view.setUint32(central + 16, crc, true); view.setUint32(local + 14, crc, true) }),
      changedContainer((view, [central]) => { view.setUint32(central + 24, exchangeLimits.manifest + 1, true) }),
      changedContainer((view, entries, bytes) => {
        const assets = entries.filter(at => new TextDecoder().decode(bytes.subarray(at + 46, at + 46 + 7)) === 'assets/'), first = assets[0], second = assets[1]
        const duplicateName = bytes.slice(first + 46, first + 46 + view.getUint16(first + 28, true)), local = view.getUint32(second + 42, true)
        bytes.set(duplicateName, second + 46); bytes.set(duplicateName, local + 30)
      }),
    ]) await expect(readExchange(invalid)).rejects.toThrow()
  })

  it('preserves UTF-16 ranges and refuses surrogate splits, lone surrogates and reversed ranges', async () => {
    const base = minimum(), start = { paragraphId: 'p', characterOffset: 6 }, end = { paragraphId: 'p', characterOffset: 8 }
    const note = { id: 'highlight', kind: 'highlight', title: 'World', text: '', excerpt: '🌏', anchor: start, range: { start, end }, createdAt: timestamp }
    const value = await readExchange(documentZip({ ...base, notes: [note] }))
    expect(value.documents[0].notes[0].range).toEqual({ start, end })
    for (const changed of [
      { ...base, position: { ...start, characterOffset: 7 } },
      { ...base, paragraphs: [{ paragraphId: 'p', text: '\ud800' }] },
      { ...base, notes: [{ ...note, range: { start: end, end: start } }] },
      { ...base, notes: [{ ...note, range: { start, end: { ...end, characterOffset: 7 } } }] },
      { ...base, notes: [{ ...note, anchor: { ...start, paragraphId: 'missing' } }] },
    ]) await expect(readExchange(documentZip(changed))).rejects.toThrow()
  })

  it('requires valid dates with an offset and rejects malformed UTF-8, JSON grammar and duplicate keys', async () => {
    for (const createdAt of ['2026-02-30T00:00:00Z', '2026-09-15T00:00:00', '0000-01-01T00:00:00Z', '2026-09-15T24:00:00Z', '2026-09-15T00:00:00+18:01']) {
      await expect(readExchange(changedManifest(manifest => { manifest.createdAt = createdAt }))).rejects.toThrow()
    }
    for (const suffix of ['.123456789Z', '+09:00']) {
      expect((await readExchange(changedManifest(manifest => { manifest.createdAt = '2026-09-15T00:00:00' + suffix }))).createdAt).toBe('2026-09-15T00:00:00' + suffix)
    }
    const json = JSON.stringify(minimum())
    for (const raw of [json + ' trailing', json.replace('"id":', 'id:'), json.replace('"id":', "'id':"), json.slice(0, -1) + ',}',
      json.replace('"id":"minimal"', '"id":"minimal","\\u0069d":"other"'), json.replace('"Book"', '"bad\\q"'),
      json.replace('"Book"', '"raw\nnewline"'), json.slice(0, -1) + ',"extensions":' + '['.repeat(33) + '0' + ']'.repeat(33) + '}',
    ]) await expect(readExchange(documentZip(raw, true))).rejects.toThrow()
    const invalidUtf8 = unzipSync(fixture()); invalidUtf8['manifest.json'] = Uint8Array.of(0xff, 0xfe)
    await expect(readExchange(zipSync(invalidUtf8))).rejects.toThrow()
  })
})
