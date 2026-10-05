import { webcrypto } from 'node:crypto'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import original from '../../../contracts/fixtures/library-identity-v1/original.json'
import translation from '../../../contracts/fixtures/library-identity-v1/translation.json'
import fixture from '../../../contracts/fixtures/library-organization-snapshot-v1.json'
import { readExchange, validateExchangeDocument, writeExchange, type ExchangeDocument } from './libraryExchange'
import { libraryParagraphHash } from './libraryIdentity'
import { parseLibraryOrganizationSnapshot, readLibraryOrganizationSnapshotFromDocument, validatePortableLibraryOrganizationSnapshot, withLibraryOrganizationSnapshot } from './portableLibraryOrganization'

const extensionKey = 'libraryOrganizationSnapshot'
const document = (translated = false): ExchangeDocument => validateExchangeDocument(structuredClone(translated ? translation : original))
const emptyOrganization = () => ({ folder: '', tags: [] as string[], favorite: false })
const snapshot = (translated = false) => ({ version: 1, identity: structuredClone((translated ? translation : original).extensions.documentIdentity),
  presence: 'present', organization: { folder: '분류  🌏', tags: ['Case', 'case', 'Ａ', 'A', 'é', 'e\u0301', '두  칸'], favorite: true } })
const packageOf = (value: ExchangeDocument) => ({ createdAt: '2026-10-05T00:00:00Z', documents: [value], assets: [] })
const attached = (value: unknown, base = document()) => ({ ...base, extensions: { ...base.extensions, [extensionKey]: value } })

beforeEach(() => vi.stubGlobal('crypto', webcrypto))
afterEach(() => vi.unstubAllGlobals())

describe('passive library organization snapshots', () => {
  for (const entry of fixture.cases) it(`matches shared Kotlin fixture: ${entry.name}`, async () => {
    const base = validateExchangeDocument(structuredClone(entry.document))
    expect(validatePortableLibraryOrganizationSnapshot(entry.snapshot)).toEqual(entry.snapshot)
    expect(parseLibraryOrganizationSnapshot(JSON.stringify(entry.snapshot))).toEqual(entry.snapshot)
    const written = await withLibraryOrganizationSnapshot(base, entry.snapshot)
    expect(await readLibraryOrganizationSnapshotFromDocument(written)).toEqual(entry.snapshot)
    const restored = await readExchange(await writeExchange(packageOf(written)))
    expect(await readLibraryOrganizationSnapshotFromDocument(restored.documents[0])).toEqual(entry.snapshot)
    expect(restored.documents[0].organization).toEqual(base.organization)
  })

  it('distinguishes missing, absent and present empty values without legacy fallback or side effects', async () => {
    const fetch = vi.fn(() => { throw new Error('No account requests allowed') }); vi.stubGlobal('fetch', fetch)
    const base = document(), before = structuredClone(base)
    expect(await readLibraryOrganizationSnapshotFromDocument(base)).toBeUndefined()
    const absent = { ...snapshot(), presence: 'absent', organization: null }
    const presentEmpty = { ...snapshot(), organization: emptyOrganization() }
    expect(await readLibraryOrganizationSnapshotFromDocument(await withLibraryOrganizationSnapshot(base, absent))).toEqual(absent)
    expect(await readLibraryOrganizationSnapshotFromDocument(await withLibraryOrganizationSnapshot(base, presentEmpty))).toEqual(presentEmpty)
    expect(base).toEqual(before); expect(fetch).not.toHaveBeenCalled()
    for (const value of [{ ...absent, organization: emptyOrganization() }, { ...presentEmpty, organization: null },
      { ...absent, presence: 'deleted' }, { ...absent, presence: 'missing' }]) expect(() => validatePortableLibraryOrganizationSnapshot(value)).toThrow()
    // Missing does not require an identity proof, including a safe future identity or asset document.
    expect(await readLibraryOrganizationSnapshotFromDocument({ ...base, assets: [{ path: `assets/${'a'.repeat(64)}`, role: 'pdf' }],
      extensions: { documentIdentity: { version: 2 } } })).toBeUndefined()
  })

  it('requires every exact field and rejects account IDs, record UUID scope, CAS and mutation state', () => {
    for (const version of ['1', null, undefined, false, 0, 2, 1.5]) expect(() => validatePortableLibraryOrganizationSnapshot({ ...snapshot(), version })).toThrow()
    for (const key of ['version', 'identity', 'presence', 'organization']) {
      const value: Record<string, unknown> = snapshot(); delete value[key]
      expect(() => validatePortableLibraryOrganizationSnapshot(value)).toThrow()
    }
    for (const key of ['accountId', 'username', 'origin', 'recordId', 'expectedVersion', 'mutationId', 'pending', 'queued', 'conflict', 'updatedAt']) {
      expect(() => validatePortableLibraryOrganizationSnapshot({ ...snapshot(), [key]: 'unsupported' })).toThrow()
      expect(() => validatePortableLibraryOrganizationSnapshot({ ...snapshot(), identity: { ...snapshot().identity, [key]: 'unsupported' } })).toThrow()
      expect(() => validatePortableLibraryOrganizationSnapshot({ ...snapshot(), organization: { ...emptyOrganization(), [key]: 'unsupported' } })).toThrow()
    }
    expect(() => validatePortableLibraryOrganizationSnapshot({ ...snapshot(), identity: { kind: 'ORIGINAL', recordId: original.extensions.serverRecordId } })).toThrow()
    expect(() => validatePortableLibraryOrganizationSnapshot({ ...snapshot(), identity: { ...snapshot().identity, version: 2 } })).toThrow()
  })

  it('preserves exact ordered case/NFC/NFD tags and validates account bounds without normalization or truncation', () => {
    expect(validatePortableLibraryOrganizationSnapshot(snapshot())).toEqual(snapshot())
    const organization = { folder: '🌏'.repeat(100), tags: Array.from({ length: 32 }, (_, i) => `${i}`.padStart(60, 'x')), favorite: false }
    expect(validatePortableLibraryOrganizationSnapshot({ ...snapshot(), organization }).organization).toEqual(organization)
    for (const change of [{ folder: 'a'.repeat(201) }, { folder: ' leading' }, { folder: 'trailing\ufeff' }, { folder: 'x\n' },
      { folder: '\ud800' }, { tags: ['x', 'x'] }, { tags: [''] }, { tags: ['x '] }, { tags: ['\udc00'] }, { tags: ['x\u0085y'] },
      { tags: ['🌏'.repeat(31)] }, { tags: [...organization.tags, '33'] }, { favorite: 1 }]) {
      expect(() => validatePortableLibraryOrganizationSnapshot({ ...snapshot(), organization: { ...organization, ...change } })).toThrow()
    }
  })

  it('checks raw JSON grammar, duplicate escaped fields, numeric semantics and both Unicode representations', () => {
    const raw = JSON.stringify(snapshot())
    for (const text of [raw.replace('"version":1', '"version":1,"version":1'),
      raw.replace('"folder":', '"folder":"ignored","fol\\u0064er":'), raw.replace('"favorite":true', '"favorite":true,"favorite":false'),
      raw.replace('"version":1', '"version":1e999'), raw.replace('"version":1', '"version":1e-999'),
      raw.replace('"version":1', '"version":9007199254740992'), `${raw} false`, raw.slice(0, -1) + ',}', '\ufeff' + raw]) {
      expect(() => parseLibraryOrganizationSnapshot(text)).toThrow()
    }
    for (const token of ['1.0', '1e0']) expect(parseLibraryOrganizationSnapshot(raw.replace('"version":1', `"version":${token}`))).toEqual(snapshot())
    for (const value of ['\ud800', '\udc00']) {
      const escaped = JSON.stringify({ ...snapshot(), identity: { ...snapshot().identity, bookId: value } })
      expect(() => parseLibraryOrganizationSnapshot(escaped)).toThrow()
      expect(() => parseLibraryOrganizationSnapshot(escaped.replace(/\\ud[89a-f][0-9a-f]{2}/, value))).toThrow()
    }
  })

  it('bounds raw decoding at 256 KiB UTF8 before parsing without truncating whitespace or Unicode', () => {
    const raw = JSON.stringify(snapshot()), bytes = new TextEncoder().encode(raw).length
    expect(parseLibraryOrganizationSnapshot(raw + ' '.repeat(256 * 1024 - bytes))).toEqual(snapshot())
    expect(() => parseLibraryOrganizationSnapshot(raw + ' '.repeat(256 * 1024 - bytes + 1))).toThrow()
    const rawUnderCharacterLimit = raw + ' '.repeat(256 * 1024 - raw.length)
    expect(rawUnderCharacterLimit.length).toBe(256 * 1024)
    expect(() => parseLibraryOrganizationSnapshot(rawUnderCharacterLimit)).toThrow()
  })

  it('proves exact original and translated contents rather than title, portable ID or UUID hints', async () => {
    for (const translated of [false, true]) {
      const base = document(translated), value = snapshot(translated)
      const noIdentity = { ...base, id: 'different-local-id', bookTitle: 'Different display title', extensions: { serverRecordId: 'not-a-binding' } }
      expect(await readLibraryOrganizationSnapshotFromDocument(await withLibraryOrganizationSnapshot(noIdentity, value))).toEqual(value)
      for (const changed of [{ ...noIdentity, kind: 'local' as const }, { ...noIdentity, language: base.language.toUpperCase() },
        { ...noIdentity, paragraphs: [...base.paragraphs].reverse() }, { ...noIdentity, paragraphs: base.paragraphs.map((p, i) => i === 0 ? { ...p, text: p.text + ' ' } : p) },
        { ...noIdentity, paragraphs: base.paragraphs.map((p, i) => i === 0 ? { ...p, paragraphId: p.paragraphId + ':changed' } : p) },
        { ...noIdentity, assets: [{ path: `assets/${'a'.repeat(64)}`, role: 'pdf' as const }] }]) {
        await expect(withLibraryOrganizationSnapshot(changed, value)).rejects.toThrow()
        await expect(readLibraryOrganizationSnapshotFromDocument(attached(value, changed))).rejects.toThrow()
      }
      const changed = structuredClone(noIdentity); changed.paragraphs[0].text = 'Changed with a matching paragraph hash'
      const forged = { ...value, identity: { ...value.identity, paragraphHash: await libraryParagraphHash(changed.paragraphs) } }
      // A claimed paragraph hash is insufficient: source revision / translated artifact hashes must also match.
      await expect(withLibraryOrganizationSnapshot(changed, forged)).rejects.toThrow()
    }
  })

  it('requires strict equality with a supplied document identity including language case and every translation revision', async () => {
    for (const translated of [false, true]) {
      const base = document(translated), value = snapshot(translated)
      for (const identity of [{ ...value.identity, version: 2 }, { ...value.identity, bookId: value.identity.bookId + ' ' },
        { ...value.identity, sourceLanguage: value.identity.sourceLanguage.toUpperCase() }, { ...value.identity, sourceRevision: 'a'.repeat(64) }]) {
        const changed = { ...base, extensions: { ...base.extensions, documentIdentity: identity } }
        await expect(withLibraryOrganizationSnapshot(changed, value)).rejects.toThrow()
        await expect(readLibraryOrganizationSnapshotFromDocument(attached(value, changed))).rejects.toThrow()
      }
    }
    const value = snapshot(true), base = document(true)
    for (const key of ['targetLanguage', 'translationProviderId', 'modelId', 'promptRevision', 'glossaryRevision', 'artifactId', 'revision', 'payloadHash']) {
      const wrong = { ...value, identity: { ...value.identity, [key]: key.endsWith('Hash') || ['artifactId', 'revision'].includes(key) ? 'a'.repeat(64) : 'other' } }
      await expect(withLibraryOrganizationSnapshot(base, wrong)).rejects.toThrow()
    }
  })

  it('rejects unsupported or stale existing snapshots before overwrite while generic ZIP retains them passively', async () => {
    for (const value of [null, [], { ...snapshot(), version: 2 }, { ...snapshot(), presence: 'deleted' },
      { ...snapshot(), identity: { ...snapshot().identity, paragraphHash: 'a'.repeat(64) } }]) {
      const source = attached(value), before = structuredClone(source)
      const restored = await readExchange(await writeExchange(packageOf(source)))
      expect(restored.documents[0].extensions).toEqual(source.extensions)
      await expect(readLibraryOrganizationSnapshotFromDocument(restored.documents[0])).rejects.toThrow()
      await expect(withLibraryOrganizationSnapshot(source, snapshot())).rejects.toThrow()
      expect(source).toEqual(before)
      expect((await readExchange(await writeExchange(restored))).documents[0].extensions).toEqual(source.extensions)
    }
    const previous = await withLibraryOrganizationSnapshot(document(), { ...snapshot(), presence: 'absent', organization: null })
    expect(await readLibraryOrganizationSnapshotFromDocument(await withLibraryOrganizationSnapshot(previous, snapshot()))).toEqual(snapshot())
  })

  it('preserves legacy organization and opaque siblings with detached document/value copies across awaits', async () => {
    const base = document(), value = snapshot()
    base.organization = { folder: 'L'.repeat(500), tags: Array.from({ length: 100 }, (_, i) => `${i}`.padStart(200, 'x')), favorite: true }
    base.extensions!.future = { order: [2, 1], nested: { value: 'unchanged' } }
    const before = structuredClone(base), expected = structuredClone(value)
    const pending = withLibraryOrganizationSnapshot(base, value)
    base.paragraphs[0].text = 'mutation after preparation'
    ;(base.extensions!.future as { order: number[] }).order.reverse()
    value.identity.bookId = 'changed'; value.organization.tags.reverse()
    const result = await pending
    expect(result.paragraphs).toEqual(before.paragraphs)
    expect(result.organization).toEqual(before.organization)
    expect(result.extensions!.future).toEqual(before.extensions!.future)
    expect(await readLibraryOrganizationSnapshotFromDocument(result)).toEqual(expected)
    const reading = readLibraryOrganizationSnapshotFromDocument(result)
    ;(result.extensions![extensionKey] as { organization: { tags: string[] } }).organization.tags.reverse()
    result.paragraphs[0].text = 'changed after read started'
    expect(await reading).toEqual(expected)
    ;(await reading)!.identity.bookId = 'caller changed returned snapshot'
    expect((result.extensions![extensionKey] as { identity: { bookId: string } }).identity.bookId).toBe(expected.identity.bookId)
  })

  it('enforces the combined 256 KiB extension budget and generic credential/nesting protections', async () => {
    const base = document(), value = snapshot()
    const overhead = new TextEncoder().encode(JSON.stringify({ ...base.extensions, opaque: '', [extensionKey]: value })).length
    base.extensions!.opaque = 'x'.repeat(256 * 1024 - overhead)
    const fitting = await withLibraryOrganizationSnapshot(base, value)
    expect(new TextEncoder().encode(JSON.stringify(fitting.extensions)).length).toBe(256 * 1024)
    const before = structuredClone(base)
    await expect(withLibraryOrganizationSnapshot({ ...base, extensions: { ...base.extensions, opaque: base.extensions!.opaque + 'x' } }, value)).rejects.toThrow()
    expect(base).toEqual(before)
    for (const sibling of [{ authorization: 'credential' }, { access_token: 'credential' }, { secret: 'credential' }, { invalid: '\ud800' }]) {
      await expect(withLibraryOrganizationSnapshot({ ...document(), extensions: { sibling } }, value)).rejects.toThrow()
    }
    let nested: unknown = 'end'; for (let depth = 0; depth < 17; depth++) nested = { child: nested }
    await expect(withLibraryOrganizationSnapshot({ ...document(), extensions: { nested } }, value)).rejects.toThrow()
  })
})
