import { BookGlossaryError, bookGlossaryKey, sameBookGlossaryScope, validateBookGlossaryScope, validateBookGlossaryView, type BookGlossaryScope, type BookGlossaryView } from './bookGlossaryApi'
import { mergeExchangePackages, type ExchangeExportChoice } from './exchangeLibrary'
import { inspectPortableIdentity, sameLibraryIdentity } from './libraryIdentity'
import { LibraryIdentityError, type LibraryIdentityClient } from './libraryIdentityApi'
import { writeExchange, type ExchangePackage } from './libraryExchange'
import type { createPortableBindings } from './portableBinding'
import { readBookGlossarySnapshotsFromDocument, withBookGlossarySnapshots, type PortableBookGlossarySnapshot } from './portableBookGlossary'

export const glossaryExportErrors = {
  stale: '계정 또는 서버 연결이 변경되어 ZIP 내보내기를 취소했습니다. 다시 선택해 주세요.',
  offline: '최신 계정 용어집을 포함하려면 서버에 다시 로그인해 주세요.',
  pending: '먼저 계정 용어집 화면에서 대기 중인 변경이나 충돌을 해결한 뒤 다시 내보내 주세요.',
  identity: '서버 원본을 확인할 수 없는 책이 있습니다. 원본을 확인하거나 최신 계정 용어집 포함을 꺼 주세요.',
  binding: '가져온 ZIP 책을 먼저 서버 원본 확인 화면에서 계정 기록에 연결해 주세요.',
  language: '용어집 언어를 소문자 언어 코드로 입력해 주세요. 예: ko, en, zh-cn. 공백과 auto는 사용할 수 없습니다.',
  translationLanguage: '번역본의 계정 용어집은 번역본과 같은 언어로 선택해 주세요. 다른 언어는 원문 책에서 내보낼 수 있습니다.',
  snapshot: '기존 용어집 스냅샷의 규격을 지원하지 않습니다. 기존 파일은 보존되며 새 ZIP은 만들지 않습니다.',
  size: '용어집과 기존 확장 정보가 ZIP의 256 KiB 한도를 넘습니다. 내용을 자르지 않았습니다. 최신 계정 용어집 포함을 끄고 다시 내보내 주세요.',
  response: '계정 용어집 응답이 요청한 책·언어와 일치하지 않습니다. 다시 로그인한 뒤 시도해 주세요.',
  network: '최신 계정 용어집을 읽지 못했습니다. 서버 연결을 확인하고 다시 시도해 주세요. 새 ZIP은 만들지 않았습니다.',
  journal: '기기의 계정 용어집 기록을 확인하지 못했습니다. 계정 용어집 화면에서 기록을 확인한 뒤 다시 시도해 주세요.',
} as const

export function glossaryExportErrorMessage(error: unknown): string {
  if (error instanceof BookGlossaryError || error instanceof LibraryIdentityError) {
    if (error.code === 'aborted') return glossaryExportErrors.stale
    if (error.code === 'authentication' || error.code === 'forbidden') return glossaryExportErrors.offline
    if (['network', 'timeout', 'server', 'limit'].includes(error.code)) return glossaryExportErrors.network
    if (error.code === 'storage') return glossaryExportErrors.journal
    return error instanceof LibraryIdentityError ? glossaryExportErrors.identity : glossaryExportErrors.response
  }
  return error instanceof Error ? error.message : 'ZIP 작업을 완료하지 못했습니다.'
}

/** A session-bound reader. It must never open a synchronization controller or flush an outbox. */
export interface FreshBookGlossaryReader {
  assertCurrent(): void
  read(scope: BookGlossaryScope, signal?: AbortSignal): Promise<BookGlossaryView>
  checkReady(scope: BookGlossaryScope, signal?: AbortSignal): Promise<void>
}

/** Explicit augmentation only. The original packages, legacy glossary and other scopes remain intact. */
export async function prepareFreshGlossaryExport(options: {
  choices: ExchangeExportChoice[]; targetLanguage: string; reader: FreshBookGlossaryReader
  identityClient: LibraryIdentityClient | null; bindings: Pick<ReturnType<typeof createPortableBindings>, 'open'>
  current: () => boolean; signal?: AbortSignal
  encode?: typeof writeExchange
}): Promise<Uint8Array> {
  const { reader, identityClient, bindings, targetLanguage, signal } = options
  const assert = () => {
    if (!options.current() || signal?.aborted) throw new Error(glossaryExportErrors.stale)
    reader.assertCurrent()
  }
  assert()
  if (!identityClient) throw new Error(glossaryExportErrors.offline)
  try { validateBookGlossaryScope({ providerId: 'check', bookId: 'check', targetLanguage }) }
  catch { throw new Error(glossaryExportErrors.language) }
  if (!options.choices.length || options.choices.length > 100) throw new Error(glossaryExportErrors.identity)
  const snapshots = new Map<string, PortableBookGlossarySnapshot>(), packages: ExchangePackage[] = []
  const linked: { book: import('./exchangeLibrary').SavedExchange; documentId: string }[] = []
  for (const choice of options.choices) {
    assert()
    if (!choice.glossarySource) throw new Error(glossaryExportErrors.identity)
    const loaded = await choice.load(); assert()
    if (loaded.documents.length !== 1) throw new Error(glossaryExportErrors.identity)
    const document = loaded.documents[0], check = await inspectPortableIdentity(document); assert()
    if (check.status !== 'ready') throw new Error(glossaryExportErrors.identity)
    if (check.identity.kind === 'TRANSLATION' && check.identity.targetLanguage !== targetLanguage) throw new Error(glossaryExportErrors.translationLanguage)
    let scope: BookGlossaryScope
    try { scope = validateBookGlossaryScope({ providerId: check.identity.contentProviderId, bookId: check.identity.bookId, targetLanguage }) }
    catch { throw new Error(glossaryExportErrors.identity) }
    let previous
    try { previous = readBookGlossarySnapshotsFromDocument(document) }
    catch { throw new Error(glossaryExportErrors.snapshot) }
    if (choice.glossarySource.kind === 'portable') {
      const saved = choice.glossarySource.book
      const savedCheck = await inspectPortableIdentity(saved.document); assert()
      if (savedCheck.status !== 'ready' || !sameLibraryIdentity(savedCheck.identity, check.identity)) throw new Error(glossaryExportErrors.identity)
      const bound = await bindings.open(saved, identityClient, signal); assert()
      if (!bound) throw new Error(glossaryExportErrors.binding)
      linked.push({ book: saved, documentId: bound.id })
    } else {
      const verified = await identityClient.verify(choice.glossarySource.recordId, check.identity, signal); assert()
      if (verified.verified !== true || verified.recordId !== choice.glossarySource.recordId.toLowerCase() || verified.kind !== check.identity.kind || !sameLibraryIdentity(verified.identity, check.identity)) throw new Error(glossaryExportErrors.identity)
    }
    const key = bookGlossaryKey(scope)
    let snapshot = snapshots.get(key)
    if (!snapshot) {
      const incoming = await reader.read(scope, signal); assert()
      let view: BookGlossaryView
      try { view = validateBookGlossaryView(incoming); if (!sameBookGlossaryScope(view, scope)) throw new Error() }
      catch { throw new Error(glossaryExportErrors.response) }
      snapshot = view.version === 0 ? { ...scope, presence: 'absent', entries: null }
        : view.entries === null ? { ...scope, presence: 'deleted', entries: null }
        : { ...scope, presence: 'present', entries: view.entries }
      snapshots.set(key, snapshot)
    }
    const values = previous?.snapshots.slice() ?? [], index = values.findIndex(value => sameBookGlossaryScope(value, scope))
    if (index < 0) values.push(snapshot); else values[index] = snapshot
    // Inspect the combined size before the codec so the user receives an actionable limit error.
    const extension = { version: 1, snapshots: values }
    if (new TextEncoder().encode(JSON.stringify({ ...document.extensions, bookGlossarySnapshots: extension })).length > 256 * 1024) throw new Error(glossaryExportErrors.size)
    if (values.length > 100) throw new Error(glossaryExportErrors.snapshot)
    packages.push({ ...loaded, documents: [withBookGlossarySnapshots(document, extension)] })
  }
  const merged = await mergeExchangePackages(packages); assert()
  const data = await (options.encode ?? writeExchange)(merged); assert()
  // An imported copy may have been unlinked or relinked from another tab during encoding.
  for (const link of linked) {
    const bound = await bindings.open(link.book, identityClient, signal); assert()
    if (!bound || bound.id !== link.documentId) throw new Error(glossaryExportErrors.binding)
  }
  // Recheck every journal after slow ZIP encoding. No bytes reach the download on any failure.
  for (const snapshot of snapshots.values()) {
    await reader.checkReady({ providerId: snapshot.providerId, bookId: snapshot.bookId, targetLanguage: snapshot.targetLanguage }, signal); assert()
  }
  return data
}
