import { describe, expect, it, vi } from 'vitest'
import original from '../../../contracts/fixtures/library-identity-v1/original.json'
import translation from '../../../contracts/fixtures/library-identity-v1/translation.json'
import { validateExchangeDocument } from './libraryExchange'
import { validateLibraryIdentity } from './libraryIdentity'
import { LibraryIdentityError, type LibraryIdentityResult } from './libraryIdentityApi'
import { createPortableBindings } from './portableBinding'
import type { SavedExchange } from './exchangeLibrary'
function fixture(value = original) {
  const book: SavedExchange = { id: 'copy-A', document: validateExchangeDocument(structuredClone(value)), assets: [], importedAt: '', checksum: '', integratedNoteIds: [] }
  const result: LibraryIdentityResult = { verified: true, recordId: value.extensions.serverRecordId, kind: value.extensions.documentIdentity.kind as 'ORIGINAL', identity: validateLibraryIdentity(value.extensions.documentIdentity) }
  return { book, result }
}
function storage() { const map = new Map<string, string>(); return { getItem: (k: string) => map.get(k) ?? null, setItem: (k: string, v: string) => { map.set(k, v) }, removeItem: (k: string) => { map.delete(k) } } }
describe('explicit portable account bindings', () => {
  it('requires confirm, isolates account/origin/copy and rechecks the complete proof before opening', async () => {
    const s = storage(), binding = createPortableBindings('alice', 'https://one.example', s), { book, result } = fixture(), api = { verify: vi.fn().mockResolvedValue(result), close() {} }
    expect(await binding.open(book, api)).toBeUndefined(); expect(api.verify).not.toHaveBeenCalled()
    await binding.confirm(book, result)
    for (const other of [createPortableBindings('bob', 'https://one.example', s), createPortableBindings('alice', 'https://two.example', s)]) expect(await other.open(book, api)).toBeUndefined()
    expect(await binding.open({ ...book, id: 'copy-B' }, api)).toBeUndefined()
    const document = await binding.open(book, api)
    expect(document?.id).toBe(`original:${result.recordId}:${result.identity.sourceRevision}`)
    expect(document?.paragraphs).toEqual(book.document.paragraphs); expect(book.id).toBe('copy-A')
    expect(api.verify).toHaveBeenCalledWith(result.recordId, result.identity, undefined)
    await expect(binding.open(book, null)).rejects.toThrow(); binding.remove(book); expect(await binding.open(book, api)).toBeUndefined()
  })
  it('never trusts changed text or a deleted server record and preserves the local copy', async () => {
    const s = storage(), binding = createPortableBindings('alice', 'https://one.example', s), { book, result } = fixture()
    await binding.confirm(book, result)
    const api = { verify: vi.fn().mockRejectedValue(new LibraryIdentityError('not-found')), close() {} }
    await expect(binding.open(book, api)).rejects.toThrow(); expect(await binding.open(book, api)).toBeUndefined()
    await binding.confirm(book, result); const changed = structuredClone(book); changed.document.paragraphs[0].text += 'changed'
    await expect(binding.open(changed, api)).rejects.toThrow(); expect(book.document.paragraphs[0].text).not.toContain('changed')
    expect(await binding.open(book, api)).toBeUndefined()
  })
  it('preserves a binding across network failure and succeeds on an explicit retry', async () => {
    const binding = createPortableBindings('alice', 'https://one.example', storage()), { book, result } = fixture()
    await binding.confirm(book, result)
    const api = { verify: vi.fn().mockRejectedValueOnce(new LibraryIdentityError('network')).mockResolvedValueOnce(result), close() {} }
    await expect(binding.open(book, api)).rejects.toThrow('서버에 연결하지 못했습니다. 연결을 확인한 뒤 다시 시도해 주세요.')
    expect((await binding.open(book, api))?.serverProgress?.recordId).toBe(result.recordId)
    expect(api.verify).toHaveBeenCalledTimes(2)
  })
  it('discards canceled confirmation and canceled opening without altering a saved binding', async () => {
    const s = storage(), binding = createPortableBindings('alice', 'https://one.example', s), { book, result } = fixture(), signal = AbortSignal.abort(), api = { verify: vi.fn().mockResolvedValue(result), close() {} }
    await expect(binding.confirm(book, result, signal)).rejects.toThrow(); expect(await binding.open(book, api)).toBeUndefined()
    await binding.confirm(book, result); await expect(binding.open(book, api, signal)).rejects.toThrow(); expect(api.verify).not.toHaveBeenCalled(); expect(await binding.open(book, api)).toBeDefined()
  })
  it('keeps translation canonical identity and rejects unsupported asset mapping', async () => {
    const { book, result } = fixture(translation), binding = createPortableBindings('alice', 'https://one.example', storage()), api = { verify: vi.fn().mockResolvedValue(result), close() {} }
    await binding.confirm(book, result); expect((await binding.open(book, api))?.id).toBe(result.recordId)
    book.document.assets.push({ path: 'assets/a.pdf', role: 'pdf' })
    await expect(binding.confirm(book, result)).rejects.toThrow()
  })
})
