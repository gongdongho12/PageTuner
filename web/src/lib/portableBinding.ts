import { inspectPortableIdentity, sameLibraryIdentity, validateLibraryIdentity, type LibraryDocumentIdentity } from './libraryIdentity'
import { LibraryIdentityError, type LibraryIdentityClient, type LibraryIdentityResult } from './libraryIdentityApi'
import { exchangeReadingDocument, type SavedExchange } from './exchangeLibrary'
import type { ReadingDocument } from './readingDocument'
import { bookGlossaryScope } from './bookGlossaryProjection'
import { validRecordId } from './validation'
export function portableBindingKey(username: string, origin: string, copyId: string) {
  return `pageturner.portable-binding:${JSON.stringify([username, new URL(origin).origin, copyId])}`
}

export type PortableBindingCapture = Readonly<{
  recordId: string
  kind: LibraryDocumentIdentity['kind']
  identity: Readonly<LibraryDocumentIdentity>
  current(): boolean
  assertCurrent(): void
}>

const cancelledMessage = '서버 연결 확인이 취소되었습니다.'
const staleMessage = '서버 연결이 변경되어 이전 확인 결과를 취소했습니다. 다시 확인해 주세요.'
const invalidBindingMessage = '본문 또는 원본 정보가 변경되어 서버 연결을 해제했습니다. 기기 기록은 보존됩니다.'
const verificationMessages: Partial<Record<LibraryIdentityError['code'], string>> = {
  authentication: '서버 원본을 확인하려면 다시 로그인해 주세요.', forbidden: '서버 확인 권한을 확인해 주세요. 다시 로그인한 뒤 시도해 주세요.',
  'not-found': '현재 계정에서 해당 서버 문서를 찾을 수 없습니다.', mismatch: '서버 문서의 식별자·언어·revision·전체 문단이 가져온 책과 일치하지 않습니다.',
  unavailable: '서버 문서의 원본 정보를 검증할 수 없습니다. 기존 책은 계속 읽을 수 있습니다.',
  network: '서버에 연결하지 못했습니다. 연결을 확인한 뒤 다시 시도해 주세요.', timeout: '서버 확인 시간이 초과되었습니다. 다시 시도해 주세요.',
  server: '서버에서 원본을 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.',
}

/** Local metadata and even injected clients must prove the exact requested binding scope. */
function verifiedResult(value: unknown, identity: LibraryDocumentIdentity, recordId?: string, local = false): LibraryIdentityResult {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new LibraryIdentityError('invalid-response')
  const input = value as Record<string, unknown>
  const fields = ['verified', 'kind', 'recordId', 'identity', ...(local && Object.hasOwn(input, 'bindingNonce') ? ['bindingNonce'] : [])]
  if (Object.keys(input).length !== fields.length || fields.some(field => !Object.hasOwn(input, field)) ||
    local && Object.hasOwn(input, 'bindingNonce') && !validRecordId(input.bindingNonce)) throw new LibraryIdentityError('invalid-response')
  let accepted: LibraryDocumentIdentity
  try { accepted = validateLibraryIdentity(input.identity) } catch { throw new LibraryIdentityError('invalid-response') }
  if (input.verified !== true || input.kind !== identity.kind || !validRecordId(input.recordId) ||
    recordId && input.recordId.toLowerCase() !== recordId.toLowerCase() || !sameLibraryIdentity(accepted, identity)) throw new LibraryIdentityError('invalid-response')
  return { verified: true, kind: identity.kind, recordId: input.recordId.toLowerCase(), identity: accepted }
}
/** Imported records remain intact; account records use their existing canonical view. */
export function createPortableBindings(username: string, origin: string, storage: Pick<Storage, 'getItem' | 'setItem' | 'removeItem'> = localStorage) {
  const key = (book: SavedExchange) => portableBindingKey(username, origin, book.id)
  async function captured(book: SavedExchange, client: LibraryIdentityClient | null, signal?: AbortSignal): Promise<PortableBindingCapture | undefined> {
    const storageKey = key(book), raw = storage.getItem(storageKey)
    if (!raw) return undefined
    let expired = false
    const current = () => {
      if (expired || signal?.aborted || storage.getItem(storageKey) !== raw) { expired = true; return false }
      return true
    }
    const assertCurrent = () => {
      if (signal?.aborted) { expired = true; throw new Error(cancelledMessage) }
      if (!current()) throw new Error(staleMessage)
    }
    const removeObserved = () => {
      assertCurrent()
      // A delayed failure may only retire the generation captured before any await.
      if (storage.getItem(storageKey) === raw) storage.removeItem(storageKey)
    }
    assertCurrent()
    const check = await inspectPortableIdentity(book.document)
    assertCurrent()
    let saved: LibraryIdentityResult
    try {
      if (check.status !== 'ready' || new TextEncoder().encode(raw).byteLength > 64 * 1024) throw new Error()
      saved = verifiedResult(JSON.parse(raw), check.identity, undefined, true)
    } catch { removeObserved(); throw new Error(invalidBindingMessage) }
    if (!client) throw new Error('서버 연결을 확인하려면 다시 로그인해 주세요. 기기에서 읽기는 계속 사용할 수 있습니다.')
    try {
      const response = await client.verify(saved.recordId, structuredClone(saved.identity), signal)
      assertCurrent()
      verifiedResult(response, saved.identity, saved.recordId)
    } catch (error) {
      assertCurrent()
      if (error instanceof LibraryIdentityError) {
        if (['not-found', 'mismatch', 'unavailable'].includes(error.code)) removeObserved()
        throw new Error(verificationMessages[error.code] ?? '서버의 확인 응답을 검증하지 못했습니다.')
      }
      throw new Error(verificationMessages.network)
    }
    assertCurrent()
    return Object.freeze({ recordId: saved.recordId, kind: saved.kind,
      identity: Object.freeze(structuredClone(saved.identity)), current, assertCurrent })
  }
  return {
    async confirm(book: SavedExchange, result: LibraryIdentityResult, signal?: AbortSignal) {
      const check = await inspectPortableIdentity(book.document)
      if (check.status !== 'ready' || result.verified !== true || result.kind !== check.identity.kind || !validRecordId(result.recordId) || !sameLibraryIdentity(check.identity, result.identity)) throw new Error('서버 연결 정보를 다시 확인해 주세요.')
      if (signal?.aborted) throw new Error('서버 연결 확인이 취소되었습니다.')
      storage.setItem(key(book), JSON.stringify({ ...result, bindingNonce: crypto.randomUUID() }))
    },
    remove(book: SavedExchange) { storage.removeItem(key(book)) },
    /** Local-only generation, never transferred to ZIP. Unlink/relink invalidates an old review. */
    ticket(book: SavedExchange) {
      const storageKey = key(book), captured = storage.getItem(storageKey)
      return captured === null ? undefined : { current: () => storage.getItem(storageKey) === captured }
    },
    /** Read-only capture; callers separately re-read canonical storage before exporting or writing. */
    capture(book: SavedExchange, client: LibraryIdentityClient | null, signal?: AbortSignal): Promise<PortableBindingCapture | undefined> {
      return captured(structuredClone(book), client, signal)
    },
    async open(book: SavedExchange, client: LibraryIdentityClient | null, signal?: AbortSignal): Promise<ReadingDocument | undefined> {
      const snapshot = structuredClone(book), saved = await captured(snapshot, client, signal)
      if (!saved) return undefined
      saved.assertCurrent()
      const identity = saved.identity
      return { ...exchangeReadingDocument(snapshot), id: identity.kind === 'TRANSLATION' ? saved.recordId : `original:${saved.recordId}:${identity.sourceRevision}`,
        kind: identity.kind === 'TRANSLATION' ? 'translation' : 'original', serverProgress: { kind: identity.kind, recordId: saved.recordId }, glossaryIdentity: bookGlossaryScope(identity.contentProviderId, identity.bookId, identity.kind === 'TRANSLATION' ? identity.targetLanguage! : 'ko') ? { providerId: identity.contentProviderId, bookId: identity.bookId } : undefined }
    },
  }
}
