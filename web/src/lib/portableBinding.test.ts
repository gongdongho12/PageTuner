import { describe, expect, it, vi } from 'vitest'
import original from '../../../contracts/fixtures/library-identity-v1/original.json'
import translation from '../../../contracts/fixtures/library-identity-v1/translation.json'
import { validateExchangeDocument } from './libraryExchange'
import { validateLibraryIdentity } from './libraryIdentity'
import { LibraryIdentityError, type LibraryIdentityResult } from './libraryIdentityApi'
import { createPortableBindings, portableBindingKey } from './portableBinding'
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

  it('captures immutable verified scope without writing or exposing a nonce and expires on same-record reconnect', async () => {
    const s = storage(), binding = createPortableBindings('alice', 'https://one.example', s), { book, result } = fixture()
    const api = { verify: vi.fn().mockResolvedValue(result), close() {} }
    expect(await binding.capture(book, api)).toBeUndefined()
    expect(api.verify).not.toHaveBeenCalled()
    await binding.confirm(book, result)
    const set = vi.spyOn(s, 'setItem'), remove = vi.spyOn(s, 'removeItem')
    const captured = (await binding.capture(book, api))!
    expect(captured.recordId).toBe(result.recordId)
    expect(captured.identity).toEqual(result.identity)
    expect(Object.isFrozen(captured)).toBe(true); expect(Object.isFrozen(captured.identity)).toBe(true)
    expect(Reflect.set(captured, 'recordId', 'changed')).toBe(false)
    expect(Reflect.set(captured.identity, 'sourceLanguage', 'changed')).toBe(false)
    expect(Object.keys(captured)).not.toContain('bindingNonce')
    expect(captured.current()).toBe(true); captured.assertCurrent()
    expect(set).not.toHaveBeenCalled(); expect(remove).not.toHaveBeenCalled()
    binding.remove(book); await binding.confirm(book, result)
    expect(captured.current()).toBe(false)
    expect(() => captured.assertCurrent()).toThrow('서버 연결이 변경')
    expect((await binding.capture(book, api))!.recordId).toBe(result.recordId)
  })

  it.each([null, 'not-found', 'mismatch', 'unavailable', 'network'] as const)('discards a late %s response after another tab reconnects the same UUID', async failure => {
    const s = storage(), binding = createPortableBindings('alice', 'https://one.example', s), otherTab = createPortableBindings('alice', 'https://one.example', s)
    const { book, result } = fixture()
    await binding.confirm(book, result)
    const previous = binding.ticket(book)!
    let release!: () => void
    const gate = new Promise<void>(resolve => { release = resolve })
    const api = { verify: vi.fn(async () => { await gate; if (failure) throw new LibraryIdentityError(failure); return result }), close() {} }
    const opening = binding.open(book, api)
    await vi.waitFor(() => expect(api.verify).toHaveBeenCalledTimes(1))
    otherTab.remove(book); await otherTab.confirm(book, result)
    const replacement = s.getItem(portableBindingKey('alice', 'https://one.example', book.id))
    release()
    await expect(opening).rejects.toThrow('서버 연결이 변경')
    expect(previous.current()).toBe(false)
    expect(s.getItem(portableBindingKey('alice', 'https://one.example', book.id))).toBe(replacement)
    expect(await binding.open(book, { verify: async () => result, close() {} })).toBeDefined()
  })

  it('cannot delete a replacement when malformed metadata was captured before asynchronous local proof inspection', async () => {
    const s = storage(), binding = createPortableBindings('alice', 'https://one.example', s), { book, result } = fixture()
    const key = portableBindingKey('alice', 'https://one.example', book.id), api = { verify: vi.fn().mockResolvedValue(result), close() {} }
    s.setItem(key, '{broken')
    const remove = vi.spyOn(s, 'removeItem'), opening = binding.open(book, api)
    const replacement = JSON.stringify({ ...result, bindingNonce: crypto.randomUUID() })
    s.setItem(key, replacement)
    await expect(opening).rejects.toThrow('서버 연결이 변경')
    expect(remove).not.toHaveBeenCalled(); expect(s.getItem(key)).toBe(replacement)
    expect(api.verify).not.toHaveBeenCalled()
    expect(await binding.open(book, api)).toBeDefined()
  })

  it('uses one fixed document copy while verifying instead of projecting a caller mutation as server reading content', async () => {
    const binding = createPortableBindings('alice', 'https://one.example', storage()), { book, result } = fixture()
    const originalParagraphs = structuredClone(book.document.paragraphs)
    await binding.confirm(book, result)
    let release!: () => void
    const gate = new Promise<void>(resolve => { release = resolve })
    const api = { verify: vi.fn(async (_record: string, identity: typeof result.identity) => {
      identity.sourceLanguage = 'changed-client-input'
      await gate; return result
    }), close() {} }
    const opening = binding.open(book, api)
    await vi.waitFor(() => expect(api.verify).toHaveBeenCalledTimes(1))
    book.document.paragraphs[0].text += 'caller mutation'
    release()
    const document = (await opening)!
    expect(document.paragraphs).toEqual(originalParagraphs)
    expect(document.paragraphs).not.toBe(book.document.paragraphs)
    expect(result.identity.sourceLanguage).not.toBe('changed-client-input')
  })

  it.each(['verified', 'record', 'kind', 'identity', 'extra'] as const)('validates the actual %s verification response and preserves the association on an invalid response', async changed => {
    const s = storage(), binding = createPortableBindings('alice', 'https://one.example', s), { book, result } = fixture()
    await binding.confirm(book, result)
    const wrong = structuredClone(result) as unknown as Record<string, unknown>
    if (changed === 'verified') wrong.verified = false
    if (changed === 'record') wrong.recordId = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'
    if (changed === 'kind') wrong.kind = 'TRANSLATION'
    if (changed === 'identity') wrong.identity = { ...result.identity, bookId: 'wrong-book' }
    if (changed === 'extra') wrong.extra = 'unrecognized'
    const key = portableBindingKey('alice', 'https://one.example', book.id), before = s.getItem(key)
    await expect(binding.open(book, { verify: vi.fn().mockResolvedValue(wrong), close() {} })).rejects.toThrow('응답')
    expect(s.getItem(key)).toBe(before)
    expect(await binding.open(book, { verify: async () => result, close() {} })).toBeDefined()
  })

  it('discards an aborted late response and expires an issued capture without unlinking its binding', async () => {
    const s = storage(), binding = createPortableBindings('alice', 'https://one.example', s), { book, result } = fixture()
    await binding.confirm(book, result)
    const controller = new AbortController(), api = { verify: async () => result, close() {} }
    const captured = (await binding.capture(book, api, controller.signal))!
    const remove = vi.spyOn(s, 'removeItem')
    controller.abort()
    expect(captured.current()).toBe(false); expect(() => captured.assertCurrent()).toThrow('취소')
    const lateController = new AbortController()
    let release!: () => void
    const gate = new Promise<void>(resolve => { release = resolve })
    const lateApi = { verify: vi.fn(async () => { await gate; return result }), close() {} }
    const opening = binding.open(book, lateApi, lateController.signal)
    await vi.waitFor(() => expect(lateApi.verify).toHaveBeenCalledTimes(1))
    lateController.abort(); release()
    await expect(opening).rejects.toThrow('취소')
    expect(remove).not.toHaveBeenCalled()
    expect(await binding.open(book, api)).toBeDefined()
  })

  it('keeps legacy nonce-free bindings usable while rejecting corrupt or oversized private metadata', async () => {
    const s = storage(), binding = createPortableBindings('alice', 'https://one.example', s), { book, result } = fixture()
    const key = portableBindingKey('alice', 'https://one.example', book.id), api = { verify: vi.fn().mockResolvedValue(result), close() {} }
    s.setItem(key, JSON.stringify(result))
    expect((await binding.capture(book, api))!.recordId).toBe(result.recordId)
    for (const raw of [JSON.stringify({ ...result, verified: 1 }), JSON.stringify({ ...result, bindingNonce: 'broken' }), ' '.repeat(65_537)]) {
      s.setItem(key, raw)
      await expect(binding.capture(book, api)).rejects.toThrow('원래 연결과 기기 기록은 보존')
      expect(s.getItem(key)).toBe(raw)
      await expect(binding.open(book, api)).rejects.toThrow('서버 연결을 해제')
      expect(s.getItem(key)).toBeNull()
    }
  })

  it('keeps read-only export captures from retiring a missing server binding', async () => {
    const s = storage(), binding = createPortableBindings('alice', 'https://one.example', s), { book, result } = fixture()
    await binding.confirm(book, result)
    const key = portableBindingKey('alice', 'https://one.example', book.id), before = s.getItem(key)
    const api = { verify: vi.fn().mockRejectedValue(new LibraryIdentityError('not-found')), close() {} }
    await expect(binding.capture(book, api)).rejects.toThrow()
    expect(s.getItem(key)).toBe(before)
    await expect(binding.open(book, api)).rejects.toThrow()
    expect(s.getItem(key)).toBeNull()
  })
})
