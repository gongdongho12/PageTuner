import { createHash, webcrypto } from 'node:crypto'
import { strToU8, zipSync } from 'fflate'
import { beforeAll, describe, expect, it, vi } from 'vitest'
import fixture from '../../../contracts/fixtures/book-glossary-snapshots-v1.json'
import { readExchange, validateExchangeDocument, writeExchange, type ExchangeDocument } from './libraryExchange'
import { readBookGlossarySnapshotsFromDocument, validatePortableBookGlossarySnapshots, withBookGlossarySnapshots } from './portableBookGlossary'

beforeAll(() => vi.stubGlobal('crypto', webcrypto))
const timestamp = '2026-10-04T00:00:00Z'
const empty = () => ({ version: 1, snapshots: [] })
const first = () => structuredClone(fixture.snapshots[0])
const doc = (): ExchangeDocument => validateExchangeDocument({ id: 'device-copy', bookTitle: 'Book', chapterTitle: 'Chapter', language: 'ko', kind: 'translation',
  paragraphs: [{ paragraphId: 'original-paragraph', text: 'Text🌏' }], position: { paragraphId: 'original-paragraph', characterOffset: 6 },
  glossary: [{ source: 'legacy', target: 'legacy value', enabled: true, caseSensitive: true }] })
const archive = (document: ExchangeDocument) => ({ createdAt: timestamp, documents: [document], assets: [] })
function rawDocumentZip(raw: string): Uint8Array {
  const bytes = strToU8(raw), sha256 = createHash('sha256').update(bytes).digest('hex'), path = `documents/${sha256}.json`
  return zipSync({ 'manifest.json': strToU8(JSON.stringify({ format: 'pageturner.library', version: 1, createdAt: timestamp,
    documents: [{ path, sha256, bytes: bytes.length }], assets: [] })), [path]: bytes })
}

describe('passive book glossary snapshots shared with Kotlin', () => {
  it('retains shared fixture scope and entry order, IDs, aliases, kinds, exact whitespace and all presence states through ZIP exchange', async () => {
    const checked = validatePortableBookGlossarySnapshots(fixture)
    expect(checked).toEqual(fixture)
    expect(checked.snapshots.map(value => value.presence)).toEqual(['present', 'present', 'deleted', 'absent'])
    expect(checked.snapshots[0].entries?.map(value => value.id)).toEqual(['original-entry-2:人物🌏', 'disabled-place-1', 'term-3'])
    expect(checked.snapshots[0].entries?.map(value => value.sourceTerm)).toEqual([' Alice ', 'River', 'River'])
    const prepared = withBookGlossarySnapshots(doc(), checked)
    const restored = await readExchange(await writeExchange(archive(prepared)))
    expect(readBookGlossarySnapshotsFromDocument(restored.documents[0])).toEqual(fixture)
    expect(restored.documents[0].glossary).toEqual(doc().glossary)
    expect(restored.documents[0].position).toEqual(doc().position)
  })

  it('distinguishes omitted, empty, deleted, absent and present-empty values without a legacy fallback', () => {
    expect(readBookGlossarySnapshotsFromDocument(doc())).toBeUndefined()
    expect(readBookGlossarySnapshotsFromDocument(withBookGlossarySnapshots(doc(), empty()))).toEqual(empty())
    for (const snapshot of fixture.snapshots.slice(1)) {
      const value = { version: 1, snapshots: [snapshot] }
      expect(readBookGlossarySnapshotsFromDocument(withBookGlossarySnapshots(doc(), value))).toEqual(value)
    }
    for (const value of [{ ...first(), presence: 'absent' }, { ...first(), presence: 'deleted' }, { ...first(), entries: null },
      { ...first(), presence: 'missing', entries: null }, { ...first(), presence: 'absent', entries: [] }, { ...first(), presence: 'deleted', entries: [] }]) {
      expect(() => validatePortableBookGlossarySnapshots({ version: 1, snapshots: [value] })).toThrow()
    }
  })

  it('requires numeric version one and every exact field without coercion or extra account/mutation data', () => {
    for (const version of ['1', null, undefined, true, 0, 2, 1.5]) expect(() => validatePortableBookGlossarySnapshots({ version, snapshots: [] })).toThrow()
    for (const key of ['version', 'snapshots']) {
      const value: Record<string, unknown> = empty(); delete value[key]
      expect(() => validatePortableBookGlossarySnapshots(value)).toThrow()
    }
    for (const key of ['providerId', 'bookId', 'targetLanguage', 'presence', 'entries']) {
      const scope: Record<string, unknown> = first(); delete scope[key]
      expect(() => validatePortableBookGlossarySnapshots({ version: 1, snapshots: [scope] })).toThrow()
    }
    for (const key of ['id', 'sourceTerm', 'translatedTerm', 'displayTerm', 'kind', 'caseSensitive', 'enabled']) {
      const entry: Record<string, unknown> = { ...fixture.snapshots[0].entries![0] }; delete entry[key]
      expect(() => validatePortableBookGlossarySnapshots({ version: 1, snapshots: [{ ...first(), entries: [entry] }] })).toThrow()
    }
    for (const key of ['accountId', 'username', 'origin', 'authorization', 'password', 'expectedVersion', 'mutationId', 'outbox', 'pending', 'conflict']) {
      expect(() => validatePortableBookGlossarySnapshots({ ...empty(), [key]: 'unsupported' })).toThrow()
      expect(() => validatePortableBookGlossarySnapshots({ version: 1, snapshots: [{ ...first(), [key]: 'unsupported' }] })).toThrow()
      expect(() => validatePortableBookGlossarySnapshots({ version: 1, snapshots: [{ ...first(), entries: [{ ...fixture.snapshots[0].entries![0], [key]: 'unsupported' }] }] })).toThrow()
    }
    for (const change of [{ enabled: 'true' }, { caseSensitive: 0 }, { kind: 'character' }, { id: 3 }, { displayTerm: null }]) {
      expect(() => validatePortableBookGlossarySnapshots({ version: 1, snapshots: [{ ...first(), entries: [{ ...fixture.snapshots[0].entries![0], ...change }] }] })).toThrow()
    }
  })

  it('uses exact tuple scope identity without ambiguous delimiters or cross-scope entry deduplication', () => {
    const base = first()
    const values = [{ ...base, providerId: 'a|b', bookId: 'c' }, { ...base, providerId: 'a', bookId: 'b|c' },
      { ...base, providerId: 'a', bookId: 'b|c', targetLanguage: 'ja' }]
    expect(validatePortableBookGlossarySnapshots({ version: 1, snapshots: values }).snapshots).toEqual(values)
    expect(() => validatePortableBookGlossarySnapshots({ version: 1, snapshots: [base, structuredClone(base)] })).toThrow()
    expect(() => validatePortableBookGlossarySnapshots({ version: 1, snapshots: [{ ...base, entries: [base.entries![0], base.entries![0]] }] })).toThrow()
  })

  it('reuses account glossary UTF16, Unicode, whitespace and exact language scope validation', () => {
    for (const change of [{ providerId: '' }, { providerId: ' x' }, { providerId: 'x\ufeff' }, { providerId: 'x'.repeat(101) },
      { bookId: 'x'.repeat(2001) }, { bookId: 'x\u0085y' }, { bookId: '\ud800' }, { targetLanguage: 'KO' },
      { targetLanguage: 'auto' }, { targetLanguage: 'ko_KR' }, { targetLanguage: 'ko ' }]) {
      expect(() => validatePortableBookGlossarySnapshots({ version: 1, snapshots: [{ ...first(), ...change }] })).toThrow()
    }
    const entry = fixture.snapshots[0].entries![0]
    for (const change of [{ id: '\ufeffid' }, { id: 'x'.repeat(201) }, { sourceTerm: '  ' }, { translatedTerm: '' },
      { sourceTerm: '\ud800' }, { translatedTerm: 'a\u0000b' }, { displayTerm: '\udc00' }, { displayTerm: 'x'.repeat(201) },
      { sourceTerm: '🌏'.repeat(101) }]) {
      expect(() => validatePortableBookGlossarySnapshots({ version: 1, snapshots: [{ ...first(), entries: [{ ...entry, ...change }] }] })).toThrow()
    }
    const boundary = { ...first(), providerId: 'p'.repeat(100), bookId: '🌏'.repeat(1000), entries: [{ ...entry,
      id: '🌏'.repeat(100), sourceTerm: '🌏'.repeat(100), translatedTerm: '🌏'.repeat(100), displayTerm: '\ufeff  ' }] }
    expect(validatePortableBookGlossarySnapshots({ version: 1, snapshots: [boundary] }).snapshots[0]).toEqual(boundary)
  })

  it('accepts all 100 scopes and 500 entries but explicitly rejects structural excess rather than truncating', () => {
    const entries = Array.from({ length: 500 }, (_, index) => ({ ...fixture.snapshots[0].entries![0], id: `id-${index}` }))
    const value = { version: 1, snapshots: [{ ...first(), entries }] }
    expect(readBookGlossarySnapshotsFromDocument(withBookGlossarySnapshots(doc(), value))?.snapshots[0].entries).toHaveLength(500)
    expect(() => validatePortableBookGlossarySnapshots({ version: 1, snapshots: [{ ...first(), entries: [...entries, { ...entries[0], id: '501' }] }] })).toThrow()
    const scopes = Array.from({ length: 100 }, (_, index) => ({ ...first(), bookId: `scope-${index}`, entries: [] }))
    expect(validatePortableBookGlossarySnapshots({ version: 1, snapshots: scopes }).snapshots).toHaveLength(100)
    expect(() => validatePortableBookGlossarySnapshots({ version: 1, snapshots: [...scopes, { ...scopes[0], bookId: 'scope-101' }] })).toThrow()
  })

  it('enforces 256KiB for the entire UTF8 extension including siblings without shortening valid large snapshots', () => {
    const overhead = new TextEncoder().encode(JSON.stringify({ opaque: '', bookGlossarySnapshots: empty() })).length
    const fitting = { ...doc(), extensions: { opaque: 'x'.repeat(256 * 1024 - overhead) } }
    expect(new TextEncoder().encode(JSON.stringify(withBookGlossarySnapshots(fitting, empty()).extensions)).length).toBe(256 * 1024)
    const oversized = { ...doc(), extensions: { opaque: fitting.extensions.opaque + 'x' } }
    expect(() => withBookGlossarySnapshots(oversized, empty())).toThrow()
    const entries = Array.from({ length: 500 }, (_, index) => ({ ...fixture.snapshots[0].entries![0], id: `id-${index}`,
      sourceTerm: '🌏'.repeat(100), translatedTerm: '🌏'.repeat(100), displayTerm: '🌏'.repeat(100) }))
    const large = { version: 1, snapshots: [{ ...first(), entries }] }
    expect(validatePortableBookGlossarySnapshots(large).snapshots[0].entries).toHaveLength(500)
    const unchanged = JSON.stringify(large)
    expect(() => withBookGlossarySnapshots(doc(), large)).toThrow()
    expect(JSON.stringify(large)).toBe(unchanged)
  })

  it('preserves document identity and safe unknown siblings without mutating inputs or sharing mutable extensions', () => {
    const source = { ...doc(), extensions: { documentIdentity: { version: 1, futureProof: ['exact', 1] }, unknown: { order: [2, 1], text: ' preserved ' } } }
    const sourceBefore = structuredClone(source), input = structuredClone(fixture), inputBefore = structuredClone(input)
    const attached = withBookGlossarySnapshots(source, input)
    expect(attached.extensions).toMatchObject(source.extensions)
    expect(source).toEqual(sourceBefore); expect(input).toEqual(inputBefore)
    input.snapshots[0].entries![0].sourceTerm = 'changed'
    expect(readBookGlossarySnapshotsFromDocument(attached)).toEqual(fixture)
    const parsed = readBookGlossarySnapshotsFromDocument(attached)!
    parsed.snapshots[0].entries![0].sourceTerm = 'changed'
    expect(readBookGlossarySnapshotsFromDocument(attached)).toEqual(fixture)
    ;(attached.extensions!.unknown as { order: number[] }).order.reverse()
    expect(source.extensions.unknown.order).toEqual([2, 1])
    const replaced = withBookGlossarySnapshots(attached, empty())
    expect(readBookGlossarySnapshotsFromDocument(replaced)).toEqual(empty())
    expect(readBookGlossarySnapshotsFromDocument(attached)).toEqual(fixture)
  })

  it('preserves unsupported and malformed safe snapshots as passive ZIP metadata while typed interpretation rejects them', async () => {
    for (const value of [{ ...fixture, version: 2 }, { version: 1, snapshots: 'future encoding' },
      { ...fixture, mutationId: 'passive unsupported value' }, null, []]) {
      const input = { ...doc(), extensions: { sibling: { retained: true }, bookGlossarySnapshots: value } }
      const decoded = await readExchange(await writeExchange(archive(input)))
      expect(decoded.documents[0].extensions).toEqual(input.extensions)
      expect(() => readBookGlossarySnapshotsFromDocument(decoded.documents[0])).toThrow()
      expect((await readExchange(await writeExchange(decoded))).documents[0].extensions).toEqual(input.extensions)
    }
  })

  it('retains generic credential/nesting protections and rejects duplicate JSON keys before typed interpretation', async () => {
    for (const extensions of [{ sibling: { authorization: 'credential' } }, { sibling: { secret: 'credential' } }]) {
      expect(() => withBookGlossarySnapshots({ ...doc(), extensions }, fixture)).toThrow()
    }
    const raw = JSON.stringify({ ...doc(), extensions: { bookGlossarySnapshots: fixture } })
    await expect(readExchange(rawDocumentZip(raw.replace('"version":1', '"version":1,"version":1')))).rejects.toThrow()
    await expect(readExchange(rawDocumentZip(raw.replace('"targetLanguage":"ko"', '"targetLanguage":"ko","targetLanguage":"ko"')))).rejects.toThrow()
    let nested: unknown = 'end'
    for (let depth = 0; depth < 17; depth++) nested = { child: nested }
    expect(() => withBookGlossarySnapshots({ ...doc(), extensions: { nested } }, fixture)).toThrow()
    await expect(readExchange(rawDocumentZip(raw.replace('"version":1', '"version":1.0')))).resolves.toBeDefined()
  })
})
