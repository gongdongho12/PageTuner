import { webcrypto } from 'node:crypto'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import original from '../../../contracts/fixtures/library-identity-v1/original.json'
import translation from '../../../contracts/fixtures/library-identity-v1/translation.json'
import fixture from '../../../contracts/fixtures/reading-progress-snapshot-v1.json'
import { exchangeLimits, readExchange, validateExchangeDocument, writeExchange, type ExchangeDocument } from './libraryExchange'
import { libraryParagraphHash } from './libraryIdentity'
import { sha256 } from './validation'
import { parseReadingProgressSnapshot, readReadingProgressSnapshotFromDocument, validatePortableReadingProgressSnapshot, withReadingProgressSnapshot } from './portableReadingProgress'

const key = 'readingProgressSnapshot'
const document = (translated = false): ExchangeDocument => validateExchangeDocument(structuredClone(translated ? translation : original))
const snapshot = (translated = false) => ({ version: 1, identity: structuredClone((translated ? translation : original).extensions.documentIdentity),
  presence: 'present', anchor: { paragraphId: 'p:1', characterOffset: 1 } })
const absent = () => ({ ...snapshot(), presence: 'absent', anchor: null })
const attached = (value: unknown, base = document()) => ({ ...base, extensions: { ...base.extensions, [key]: value } })
const packageOf = (value: ExchangeDocument) => ({ createdAt: '2026-10-26T00:00:00Z', documents: [value], assets: [] })

async function originalWithParagraphs(paragraphs: ExchangeDocument['paragraphs']): Promise<ExchangeDocument> {
  const base = document()
  const revision = await sha256((await Promise.all(paragraphs.map(async (p, i) => `${p.paragraphId}:${i}:${await sha256(p.text)}`))).join('\n'))
  return validateExchangeDocument({ ...base, paragraphs, extensions: { ...base.extensions, documentIdentity: {
    ...snapshot().identity, sourceRevision: revision, paragraphHash: await libraryParagraphHash(paragraphs),
  } } })
}

beforeEach(() => vi.stubGlobal('crypto', webcrypto))
afterEach(() => vi.unstubAllGlobals())

describe('passive reading progress snapshots', () => {
  for (const entry of fixture.cases) it(`matches shared Kotlin fixture: ${entry.name}`, async () => {
    const base = validateExchangeDocument(structuredClone(entry.document))
    expect(validatePortableReadingProgressSnapshot(entry.snapshot)).toEqual(entry.snapshot)
    expect(parseReadingProgressSnapshot(JSON.stringify(entry.snapshot))).toEqual(entry.snapshot)
    const written = await withReadingProgressSnapshot(base, entry.snapshot)
    const restored = await readExchange(await writeExchange(packageOf(written)))
    expect(await readReadingProgressSnapshotFromDocument(restored.documents[0])).toEqual(entry.snapshot)
    expect({ ...restored.documents[0], extensions: base.extensions }).toEqual(base)
    expect(restored.documents[0].extensions).toEqual({ ...base.extensions, [key]: entry.snapshot })
  })

  it('distinguishes missing and observed absent from a saved anchor, without moving the legacy reader or making requests', async () => {
    const fetch = vi.fn(() => { throw new Error('Passive codecs cannot request account data') }); vi.stubGlobal('fetch', fetch)
    const base = document(), before = structuredClone(base)
    base.position = { paragraphId: 'p|2', characterOffset: 1 }
    expect(await readReadingProgressSnapshotFromDocument(base)).toBeUndefined()
    const without = await withReadingProgressSnapshot(base, absent())
    expect(await readReadingProgressSnapshotFromDocument(without)).toEqual(absent())
    const withPosition = await withReadingProgressSnapshot(without, snapshot())
    expect(await readReadingProgressSnapshotFromDocument(withPosition)).toEqual(snapshot())
    expect(withPosition.position).toEqual(base.position)
    expect(base.paragraphs).toEqual(before.paragraphs); expect(base.extensions).toEqual(before.extensions)
    expect(fetch).not.toHaveBeenCalled()
    expect(await readReadingProgressSnapshotFromDocument({ ...base, assets: [{ path: `assets/${'a'.repeat(64)}`, role: 'pdf' }],
      extensions: { documentIdentity: { version: 2 } } })).toBeUndefined()
  })

  it('requires exact snapshot and anchor fields and refuses account, CAS, reset and deleted state', () => {
    for (const version of ['1', null, undefined, false, 0, 2, 1.5]) expect(() => validatePortableReadingProgressSnapshot({ ...snapshot(), version })).toThrow()
    for (const field of ['version', 'identity', 'presence', 'anchor']) {
      const value: Record<string, unknown> = snapshot(); delete value[field]
      expect(() => validatePortableReadingProgressSnapshot(value)).toThrow()
    }
    for (const extra of ['accountId', 'origin', 'recordId', 'mutationId', 'expectedVersion', 'versionedAnchor', 'updatedAt', 'pending', 'queued', 'conflict']) {
      expect(() => validatePortableReadingProgressSnapshot({ ...snapshot(), [extra]: 'unsupported' })).toThrow()
      expect(() => validatePortableReadingProgressSnapshot({ ...snapshot(), anchor: { ...snapshot().anchor, [extra]: 'unsupported' } })).toThrow()
      expect(() => validatePortableReadingProgressSnapshot({ ...snapshot(), identity: { ...snapshot().identity, [extra]: 'unsupported' } })).toThrow()
    }
    for (const value of [{ ...absent(), anchor: snapshot().anchor }, { ...snapshot(), anchor: null }, { ...snapshot(), anchor: [] },
      { ...absent(), presence: 'deleted' }, { ...absent(), presence: 'reset' }]) expect(() => validatePortableReadingProgressSnapshot(value)).toThrow()
    expect(() => validatePortableReadingProgressSnapshot({ ...snapshot(), anchor: { paragraphId: 'p:1' } })).toThrow()
  })

  it('preserves nonblank paragraph IDs exactly and validates UTF16/API bounds without trimming or truncating', () => {
    for (const paragraphId of [' p:1 ', 'e\u0301', 'é', '😀'.repeat(100), '中  文']) {
      const value = { ...snapshot(), anchor: { paragraphId, characterOffset: 2_147_483_647 } }
      expect(validatePortableReadingProgressSnapshot(value)).toEqual(value)
    }
    for (const paragraphId of ['', ' ', '\u00a0\ufeff', 'x'.repeat(201), '😀'.repeat(101), 'p\n1', 'p\u00851', '\ud800', '\udc00']) {
      expect(() => validatePortableReadingProgressSnapshot({ ...snapshot(), anchor: { paragraphId, characterOffset: 0 } })).toThrow()
    }
    for (const characterOffset of [-1, 0.5, '0', null, false, NaN, Infinity, 2_147_483_648, Number.MAX_SAFE_INTEGER + 1]) {
      expect(() => validatePortableReadingProgressSnapshot({ ...snapshot(), anchor: { paragraphId: 'p:1', characterOffset } })).toThrow()
    }
  })

  it('validates raw duplicate keys, grammar, binary64 version semantics and unpaired Unicode before UTF8 encoding', () => {
    const raw = JSON.stringify(snapshot())
    for (const text of [raw.replace('"version":1', '"version":1,"version":1'),
      raw.replace('"characterOffset":1', '"characterOffset":0,"characterOffs\\u0065t":1'),
      raw.replace('"version":1', '"version":1e999'), raw.replace('"version":1', '"version":1e-999'),
      raw.replace('"characterOffset":1', '"characterOffset":9007199254740992'), `${raw}false`, raw.slice(0, -1) + ',}', '\ufeff' + raw]) {
      expect(() => parseReadingProgressSnapshot(text)).toThrow()
    }
    for (const token of ['1.0', '1e0']) expect(parseReadingProgressSnapshot(raw.replace('"version":1', `"version":${token}`))).toEqual(snapshot())
    for (const value of ['\ud800', '\udc00']) {
      const escaped = JSON.stringify({ ...snapshot(), anchor: { paragraphId: value, characterOffset: 0 } })
      expect(() => parseReadingProgressSnapshot(escaped)).toThrow()
      expect(() => parseReadingProgressSnapshot(escaped.replace(/\\ud[89a-f][0-9a-f]{2}/, value))).toThrow()
    }
  })

  it('bounds raw JSON by total UTF8 bytes including whitespace', () => {
    const raw = JSON.stringify(snapshot()), bytes = new TextEncoder().encode(raw).length
    expect(parseReadingProgressSnapshot(raw + ' '.repeat(256 * 1024 - bytes))).toEqual(snapshot())
    expect(() => parseReadingProgressSnapshot(raw + ' '.repeat(256 * 1024 - bytes + 1))).toThrow()
    const underCharacterLimit = raw + ' '.repeat(256 * 1024 - raw.length)
    expect(underCharacterLimit.length).toBe(256 * 1024)
    expect(() => parseReadingProgressSnapshot(underCharacterLimit)).toThrow()
  })

  it('requires the exact real paragraph and UTF16 boundary, including paragraph-end and empty-paragraph zero', async () => {
    const base = await originalWithParagraphs([{ paragraphId: ' p  é e\u0301 ', text: 'A😀B' }, { paragraphId: 'empty', text: '' }])
    const identity = base.extensions!.documentIdentity
    for (const anchor of [{ paragraphId: ' p  é e\u0301 ', characterOffset: 0 }, { paragraphId: ' p  é e\u0301 ', characterOffset: 1 },
      { paragraphId: ' p  é e\u0301 ', characterOffset: 3 }, { paragraphId: ' p  é e\u0301 ', characterOffset: 4 }, { paragraphId: 'empty', characterOffset: 0 }]) {
      const value = { ...snapshot(), identity, anchor }
      expect(await readReadingProgressSnapshotFromDocument(await withReadingProgressSnapshot(base, value))).toEqual(value)
    }
    for (const anchor of [{ paragraphId: ' p  é e\u0301 ', characterOffset: 2 }, { paragraphId: ' p  é e\u0301 ', characterOffset: 5 },
      { paragraphId: 'p  é e\u0301', characterOffset: 0 }, { paragraphId: 'missing', characterOffset: 0 }, { paragraphId: 'empty', characterOffset: 1 }]) {
      const value = { ...snapshot(), identity, anchor }
      expect(validatePortableReadingProgressSnapshot(value)).toEqual(value)
      await expect(withReadingProgressSnapshot(base, value)).rejects.toThrow()
      await expect(readReadingProgressSnapshotFromDocument(attached(value, base))).rejects.toThrow()
    }
    const long = await originalWithParagraphs([{ paragraphId: 'x'.repeat(201), text: 'unchanged generic legacy text' }])
    const restored = await readExchange(await writeExchange(packageOf(long)))
    expect(restored.documents[0]).toEqual(long)
    await expect(withReadingProgressSnapshot(long, { ...snapshot(), identity: long.extensions!.documentIdentity, anchor: { paragraphId: 'x'.repeat(201), characterOffset: 0 } })).rejects.toThrow()
  })

  it('proves original and translation content, exact language, order and identity instead of title or UUID hints', async () => {
    for (const translated of [false, true]) {
      const base = document(translated), value = snapshot(translated)
      const independent = { ...base, id: 'different-portable-id', bookTitle: 'Display-only title', extensions: { serverRecordId: 'not-an-account-binding' } }
      expect(await readReadingProgressSnapshotFromDocument(await withReadingProgressSnapshot(independent, value))).toEqual(value)
      for (const changed of [{ ...independent, kind: 'local' as const }, { ...independent, language: base.language.toUpperCase() },
        { ...independent, paragraphs: [...base.paragraphs].reverse() }, { ...independent, paragraphs: base.paragraphs.map((p, i) => i ? p : { ...p, text: p.text + ' ' }) },
        { ...independent, assets: [{ path: `assets/${'a'.repeat(64)}`, role: 'pdf' as const }] }]) {
        await expect(withReadingProgressSnapshot(changed, value)).rejects.toThrow()
        await expect(readReadingProgressSnapshotFromDocument(attached(value, changed))).rejects.toThrow()
      }
      const changed = structuredClone(independent); changed.paragraphs[0].text = 'Changed even with a new paragraph hash'
      const forged = { ...value, identity: { ...value.identity, paragraphHash: await libraryParagraphHash(changed.paragraphs) } }
      await expect(withReadingProgressSnapshot(changed, forged)).rejects.toThrow()
    }
  })

  it('requires strict sibling identity equality and all translated proof fields', async () => {
    for (const translated of [false, true]) {
      const base = document(translated), value = snapshot(translated)
      for (const identity of [null, [], { ...value.identity, version: 2 }, { ...value.identity, sourceLanguage: value.identity.sourceLanguage.toUpperCase() },
        { ...value.identity, bookId: value.identity.bookId + ' ' }, { ...value.identity, paragraphHash: 'a'.repeat(64) }]) {
        const changed = { ...base, extensions: { ...base.extensions, documentIdentity: identity } }
        await expect(withReadingProgressSnapshot(changed, value)).rejects.toThrow()
        await expect(readReadingProgressSnapshotFromDocument(attached(value, changed))).rejects.toThrow()
      }
    }
    const value = snapshot(true)
    for (const field of ['targetLanguage', 'translationProviderId', 'modelId', 'promptRevision', 'glossaryRevision', 'artifactId', 'revision', 'payloadHash']) {
      const wrong = { ...value, identity: { ...value.identity, [field]: ['artifactId', 'revision', 'payloadHash'].includes(field) ? 'a'.repeat(64) : 'changed' } }
      await expect(withReadingProgressSnapshot(document(true), wrong)).rejects.toThrow()
    }
  })

  it('preserves future or malformed own metadata in generic ZIP and refuses typed read or replacement', async () => {
    for (const value of [null, [], { ...snapshot(), version: 2 }, { ...snapshot(), presence: 'deleted' },
      { ...snapshot(), anchor: { paragraphId: 'missing', characterOffset: 0 } }, { ...snapshot(), identity: { ...snapshot().identity, paragraphHash: 'a'.repeat(64) } }]) {
      const source = attached(value), before = structuredClone(source)
      const restored = await readExchange(await writeExchange(packageOf(source)))
      expect(restored.documents[0].extensions).toEqual(source.extensions)
      await expect(readReadingProgressSnapshotFromDocument(restored.documents[0])).rejects.toThrow()
      await expect(withReadingProgressSnapshot(source, snapshot())).rejects.toThrow()
      expect(source).toEqual(before)
    }
  })

  it('detaches inputs and returned values and preserves position, notes, wide legacy organization and all sibling scopes', async () => {
    const base = document(), value = snapshot()
    base.position = { paragraphId: 'p|2', characterOffset: 1 }
    base.notes = [{ id: 'note:1', kind: 'bookmark', title: 'Keep', text: '', excerpt: '', anchor: { paragraphId: 'p|2', characterOffset: 1 }, createdAt: '2026-10-26T00:00:00Z' }]
    base.organization = { folder: 'x'.repeat(500), tags: Array.from({ length: 100 }, (_, i) => `${i}`.padStart(200, 'x')), favorite: true }
    base.extensions!.bookGlossarySnapshots = { version: 1, snapshots: [{ targetLanguage: 'ko', opaque: [1, 2] }, { targetLanguage: 'ja', opaque: 'unchanged' }] }
    base.extensions!.libraryOrganizationSnapshot = { version: 2, preserveOpaque: true }
    const before = structuredClone(base), expected = structuredClone(value)
    const pending = withReadingProgressSnapshot(base, value)
    base.paragraphs[0].text = 'changed after proof capture'; base.notes[0].title = 'changed'
    ;(base.extensions!.bookGlossarySnapshots as { snapshots: unknown[] }).snapshots.reverse()
    value.identity.bookId = 'changed'; value.anchor.paragraphId = 'changed'
    const result = await pending
    expect({ ...result, extensions: before.extensions }).toEqual(before)
    expect(result.extensions!.bookGlossarySnapshots).toEqual(before.extensions!.bookGlossarySnapshots)
    expect(result.extensions!.libraryOrganizationSnapshot).toEqual(before.extensions!.libraryOrganizationSnapshot)
    expect(await readReadingProgressSnapshotFromDocument(result)).toEqual(expected)
    const read = readReadingProgressSnapshotFromDocument(result)
    ;(result.extensions![key] as { anchor: { characterOffset: number } }).anchor.characterOffset = 3
    const returned = await read
    expect(returned).toEqual(expected)
    returned!.identity.bookId = 'mutation of returned copy'
    expect((result.extensions![key] as { identity: { bookId: string } }).identity.bookId).toBe(expected.identity.bookId)
  })

  it('enforces the combined 256KiB extension budget and metadata safety without dropping siblings', async () => {
    const base = document(), value = snapshot()
    const overhead = new TextEncoder().encode(JSON.stringify({ ...base.extensions, opaque: '', [key]: value })).length
    base.extensions!.opaque = 'x'.repeat(256 * 1024 - overhead)
    const fitting = await withReadingProgressSnapshot(base, value)
    expect(new TextEncoder().encode(JSON.stringify(fitting.extensions)).length).toBe(256 * 1024)
    await expect(withReadingProgressSnapshot({ ...base, extensions: { ...base.extensions, opaque: base.extensions!.opaque + 'x' } }, value)).rejects.toThrow()
    for (const sibling of [{ authorization: 'credential' }, { access_token: 'credential' }, { password: 'credential' }, { invalid: '\ud800' }]) {
      await expect(withReadingProgressSnapshot({ ...document(), extensions: { sibling } }, value)).rejects.toThrow()
    }
    let nested: unknown = 'end'; for (let depth = 0; depth < 17; depth++) nested = { child: nested }
    await expect(withReadingProgressSnapshot({ ...document(), extensions: { nested } }, value)).rejects.toThrow()
  })

  it('rejects documents beyond 8MiB before proof awaits and accepts an exact boundary document', async () => {
    // Three-byte BMP content reaches the document byte budget within the 5M UTF16 body bound.
    const base = await originalWithParagraphs([{ paragraphId: 'p:1', text: '가' }])
    const seed = { ...snapshot(), identity: base.extensions!.documentIdentity }
    const overhead = new TextEncoder().encode(JSON.stringify(await withReadingProgressSnapshot(base, seed))).length - 3
    const remaining = exchangeLimits.document - overhead
    const text = '가'.repeat(Math.floor(remaining / 3)) + 'a'.repeat(remaining % 3)
    const fitting = await originalWithParagraphs([{ paragraphId: 'p:1', text }])
    const value = { ...snapshot(), identity: fitting.extensions!.documentIdentity }
    expect(new TextEncoder().encode(JSON.stringify(await withReadingProgressSnapshot(fitting, value))).length).toBe(exchangeLimits.document)
    const over = { ...fitting, paragraphs: [{ paragraphId: 'p:1', text: text + 'a' }] }
    await expect(withReadingProgressSnapshot(over, value)).rejects.toThrow()
    await expect(readReadingProgressSnapshotFromDocument(attached(value, over))).rejects.toThrow()
  }, 30_000)
})
