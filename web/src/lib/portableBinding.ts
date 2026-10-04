import { inspectPortableIdentity, sameLibraryIdentity } from './libraryIdentity'
import { LibraryIdentityError, type LibraryIdentityClient, type LibraryIdentityResult } from './libraryIdentityApi'
import { exchangeReadingDocument, type SavedExchange } from './exchangeLibrary'
import type { ReadingDocument } from './readingDocument'
import { bookGlossaryScope } from './bookGlossaryProjection'
import { validRecordId } from './validation'
export function portableBindingKey(username: string, origin: string, copyId: string) {
  return `pageturner.portable-binding:${JSON.stringify([username, new URL(origin).origin, copyId])}`
}
/** Imported records remain intact; account records use their existing canonical view. */
export function createPortableBindings(username: string, origin: string, storage: Pick<Storage, 'getItem' | 'setItem' | 'removeItem'> = localStorage) {
  const key = (book: SavedExchange) => portableBindingKey(username, origin, book.id)
  return {
    async confirm(book: SavedExchange, result: LibraryIdentityResult, signal?: AbortSignal) {
      const check = await inspectPortableIdentity(book.document)
      if (check.status !== 'ready' || !result.verified || result.kind !== check.identity.kind || !validRecordId(result.recordId) || !sameLibraryIdentity(check.identity, result.identity)) throw new Error('서버 연결 정보를 다시 확인해 주세요.')
      if (signal?.aborted) throw new Error('서버 연결 확인이 취소되었습니다.')
      storage.setItem(key(book), JSON.stringify({ ...result, bindingNonce: crypto.randomUUID() }))
    },
    remove(book: SavedExchange) { storage.removeItem(key(book)) },
    /** Local-only generation, never transferred to ZIP. Unlink/relink invalidates an old review. */
    ticket(book: SavedExchange) {
      const storageKey = key(book), captured = storage.getItem(storageKey)
      return captured === null ? undefined : { current: () => storage.getItem(storageKey) === captured }
    },
    async open(book: SavedExchange, client: LibraryIdentityClient | null, signal?: AbortSignal): Promise<ReadingDocument | undefined> {
      const raw = storage.getItem(key(book)); if (!raw) return undefined
      const check = await inspectPortableIdentity(book.document)
      if (signal?.aborted) throw new Error('서버 연결 확인이 취소되었습니다.')
      let saved: LibraryIdentityResult
      try { saved = JSON.parse(raw); if (check.status !== 'ready' || !saved.verified || !validRecordId(saved.recordId) || saved.kind !== check.identity.kind || !sameLibraryIdentity(saved.identity, check.identity)) throw new Error() }
      catch { storage.removeItem(key(book)); throw new Error('본문 또는 원본 정보가 변경되어 서버 연결을 해제했습니다. 기기 기록은 보존됩니다.') }
      if (!client) throw new Error('서버 연결을 확인하려면 다시 로그인해 주세요. 기기에서 읽기는 계속 사용할 수 있습니다.')
      try { await client.verify(saved.recordId, saved.identity, signal) }
      catch (error) {
        if (signal?.aborted) throw new Error('서버 연결 확인이 취소되었습니다.')
        if (error instanceof LibraryIdentityError) {
          if (['not-found', 'mismatch', 'unavailable'].includes(error.code)) storage.removeItem(key(book))
          const messages: Partial<Record<LibraryIdentityError['code'], string>> = {
            authentication: '서버 원본을 확인하려면 다시 로그인해 주세요.', forbidden: '서버 확인 권한을 확인해 주세요. 다시 로그인한 뒤 시도해 주세요.',
            'not-found': '현재 계정에서 해당 서버 문서를 찾을 수 없습니다.', mismatch: '서버 문서의 식별자·언어·revision·전체 문단이 가져온 책과 일치하지 않습니다.',
            unavailable: '서버 문서의 원본 정보를 검증할 수 없습니다. 기존 책은 계속 읽을 수 있습니다.',
            network: '서버에 연결하지 못했습니다. 연결을 확인한 뒤 다시 시도해 주세요.', timeout: '서버 확인 시간이 초과되었습니다. 다시 시도해 주세요.',
            server: '서버에서 원본을 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.',
          }
          throw new Error(messages[error.code] ?? '서버의 확인 응답을 검증하지 못했습니다.')
        }
        throw new Error('서버에 연결하지 못했습니다. 연결을 확인한 뒤 다시 시도해 주세요.')
      }
      if (signal?.aborted) throw new Error('서버 연결 확인이 취소되었습니다.')
      const identity = check.identity
      return { ...exchangeReadingDocument(book), id: identity.kind === 'TRANSLATION' ? saved.recordId : `original:${saved.recordId}:${identity.sourceRevision}`,
        kind: identity.kind === 'TRANSLATION' ? 'translation' : 'original', serverProgress: { kind: identity.kind, recordId: saved.recordId }, glossaryIdentity: bookGlossaryScope(identity.contentProviderId, identity.bookId, identity.kind === 'TRANSLATION' ? identity.targetLanguage! : 'ko') ? { providerId: identity.contentProviderId, bookId: identity.bookId } : undefined }
    },
  }
}
