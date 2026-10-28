import { BookGlossaryError, bookGlossaryKey, sameBookGlossaryScope, validateBookGlossaryScope, validateBookGlossaryView, type BookGlossaryScope } from './bookGlossaryApi'
import { LibraryOrganizationError, sameLibraryOrganizationScope, validateLibraryOrganizationView, type LibraryOrganizationScope } from './libraryOrganizationApi'
import { ReadingProgressError, type ReadingProgressIdentity } from './readingProgressApi'
import { mergeExchangePackages, type ExchangeExportCapture, type ExchangeExportChoice } from './exchangeLibrary'
import { inspectPortableIdentity, sameLibraryIdentity } from './libraryIdentity'
import { LibraryIdentityError, type LibraryIdentityClient } from './libraryIdentityApi'
import { exchangeLimits, writeExchange, type ExchangePackage } from './libraryExchange'
import { glossaryExportErrors, type FreshBookGlossaryReader } from './portableGlossaryExport'
import { organizationExportErrors, type FreshLibraryOrganizationReader } from './portableOrganizationExport'
import { captureFreshReadingProgressIdentity, captureFreshReadingProgressView, readingProgressExportErrors, type FreshReadingProgressReader } from './portableReadingProgressExport'
import { readBookGlossarySnapshotsFromDocument, withBookGlossarySnapshots, type PortableBookGlossarySnapshot } from './portableBookGlossary'
import { readLibraryOrganizationSnapshotFromDocument, withLibraryOrganizationSnapshot, type PortableLibraryOrganizationSnapshot } from './portableLibraryOrganization'
import { readReadingProgressSnapshotFromDocument, withReadingProgressSnapshot, type PortableReadingProgressSnapshot } from './portableReadingProgress'
import type { createPortableBindings, PortableBindingCapture } from './portableBinding'

export const accountExportErrors = {
  network: '최신 계정 기록을 읽지 못했습니다. 서버 연결을 확인하고 다시 시도해 주세요.',
  offline: '최신 계정 기록을 포함하려면 서버에 다시 로그인해 주세요.',
  journal: '기기의 계정 기록을 확인하지 못했습니다. 대기 중인 변경과 충돌을 확인한 뒤 다시 시도해 주세요.',
  source: '선택한 문서가 변경되었거나 삭제되었습니다. 목록을 새로 불러온 뒤 다시 선택해 주세요.',
  duplicate: '같은 문서가 두 번 선택되었습니다. 원본 항목 하나만 선택해 주세요.',
  invalid: 'ZIP 교환 파일의 규격이나 무결성을 확인할 수 없습니다.',
  progressSnapshot: '기존 읽던 위치 스냅샷의 규격이나 원본 정보를 확인하지 못했습니다. 원래 파일은 보존됩니다.',
  progressSize: '최신 읽던 위치와 기존 문서·확장 정보가 ZIP 교환 한도를 넘습니다. 내용을 자르지 않았습니다.',
} as const

// Readonly binding.capture intentionally returns user-facing messages after protecting its raw
// generation. Only these fixed messages may pass through; arbitrary transport bodies never do.
const bindingExportMessages = [
  '서버 연결 확인이 취소되었습니다.',
  '서버 연결이 변경되어 이전 확인 결과를 취소했습니다. 다시 확인해 주세요.',
  '서버 연결 정보를 확인하지 못했습니다. 원래 연결과 기기 기록은 보존됩니다.',
  '서버 연결을 확인하려면 다시 로그인해 주세요. 기기에서 읽기는 계속 사용할 수 있습니다.',
  '서버 원본을 확인하려면 다시 로그인해 주세요.',
  '서버 확인 권한을 확인해 주세요. 다시 로그인한 뒤 시도해 주세요.',
  '현재 계정에서 해당 서버 문서를 찾을 수 없습니다.',
  '서버 문서의 식별자·언어·revision·전체 문단이 가져온 책과 일치하지 않습니다.',
  '서버 문서의 원본 정보를 검증할 수 없습니다. 기존 책은 계속 읽을 수 있습니다.',
  '서버에 연결하지 못했습니다. 연결을 확인한 뒤 다시 시도해 주세요.',
  '서버 확인 시간이 초과되었습니다. 다시 시도해 주세요.',
  '서버에서 원본을 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.',
  '서버의 확인 응답을 검증하지 못했습니다.',
]

/** Do not expose transport messages, response bodies or account details in the export screen. */
export function accountExchangeExportErrorMessage(error: unknown): string {
  if (error instanceof BookGlossaryError || error instanceof LibraryOrganizationError || error instanceof LibraryIdentityError || error instanceof ReadingProgressError) {
    if (error.code === 'aborted') return error instanceof ReadingProgressError ? readingProgressExportErrors.stale : glossaryExportErrors.stale
    if (error.code === 'authentication' || error.code === 'forbidden') return accountExportErrors.offline
    if (['network', 'timeout', 'server', 'limit'].includes(error.code)) return accountExportErrors.network
    if (error.code === 'storage') return accountExportErrors.journal
    return error instanceof ReadingProgressError ? readingProgressExportErrors.response : error instanceof LibraryIdentityError ? organizationExportErrors.identity
      : error instanceof BookGlossaryError ? glossaryExportErrors.response : organizationExportErrors.response
  }
  const known: string[] = [...Object.values(glossaryExportErrors), ...Object.values(organizationExportErrors), ...Object.values(readingProgressExportErrors), ...Object.values(accountExportErrors), ...bindingExportMessages]
  return error instanceof Error && known.includes(error.message) ? error.message : accountExportErrors.invalid
}

export type PreparedAccountExchangeExport = {
  bytes: Uint8Array
  checkBeforeSave(): Promise<void>
  /** No await between this final session/raw-binding check and the actual download action. */
  assertCurrent(): void
}

/** One canonical capture for normal ZIP, glossary, organization, progress, or their explicit combination. */
export async function prepareAccountExchangeExport(options: {
  choices: ExchangeExportChoice[]
  glossary?: { reader: FreshBookGlossaryReader; targetLanguage: string }
  organization?: { reader: FreshLibraryOrganizationReader }
  progress?: { reader: FreshReadingProgressReader }
  identityClient: LibraryIdentityClient | null
  bindings: Pick<ReturnType<typeof createPortableBindings>, 'capture'>
  current: () => boolean
  signal?: AbortSignal
  encode?: typeof writeExchange
}): Promise<PreparedAccountExchangeExport> {
  if (!options.choices.length || options.choices.length > 100) throw new Error(organizationExportErrors.source)
  // Capture option references before any asynchronous work, including mutable caller lists.
  const glossary = options.glossary && { ...options.glossary }, organization = options.organization && { ...options.organization }, progress = options.progress && { ...options.progress }
  const { identityClient, signal, current } = options
  const captureBinding = options.bindings.capture.bind(options.bindings), encode = options.encode ?? writeExchange
  const captures: ExchangeExportCapture[] = [], links: PortableBindingCapture[] = []
  const glossaryScopes = new Map<string, BookGlossaryScope>(), organizationScopes = new Map<string, LibraryOrganizationScope>(), progressScopes = new Map<string, ReadingProgressIdentity>()
  const glossarySnapshots = new Map<string, PortableBookGlossarySnapshot>(), organizationSnapshots = new Map<string, PortableLibraryOrganizationSnapshot>(), progressSnapshots = new Map<string, PortableReadingProgressSnapshot>()
  const assertCurrent = () => {
    if (!current() || signal?.aborted) throw new Error(glossaryExportErrors.stale)
    glossary?.reader.assertCurrent(); organization?.reader.assertCurrent(); progress?.reader.assertCurrent()
    for (const link of links) link.assertCurrent()
  }
  assertCurrent()
  // Capture selection locators and callbacks, never cached book bodies or assets as authority.
  const choices = options.choices.map(choice => ({ key: choice.key, capture: choice.capture?.bind(choice),
    source: choice.glossarySource?.kind === 'portable' ? { kind: 'portable' as const, id: choice.glossarySource.book.id }
      : choice.glossarySource ? { kind: 'server' as const, recordId: choice.glossarySource.recordId } : undefined }))
  if (new Set(choices.map(choice => choice.key)).size !== choices.length) throw new Error(organizationExportErrors.source)
  if ((glossary || organization || progress) && !identityClient) throw new Error(accountExportErrors.offline)
  if (glossary) {
    try { validateBookGlossaryScope({ providerId: 'check', bookId: 'check', targetLanguage: glossary.targetLanguage }) }
    catch { throw new Error(glossaryExportErrors.language) }
  }
  const checkBeforeSave = async () => {
    assertCurrent()
    for (const scope of glossaryScopes.values()) { await glossary!.reader.checkReady(scope, signal); assertCurrent() }
    for (const scope of organizationScopes.values()) { await organization!.reader.checkReady(scope, signal); assertCurrent() }
    for (const scope of progressScopes.values()) { await progress!.reader.checkReady({ ...scope }, signal); assertCurrent() }
    // Journal checks can suspend while another tab deletes or edits the selected canonical source.
    for (const capture of captures) { await capture.assertCurrent(); assertCurrent() }
    assertCurrent()
  }
  const packages: ExchangePackage[] = []
  for (const choice of choices) {
    assertCurrent()
    if (!choice.capture) throw new Error(organizationExportErrors.source)
    const source = choice.source
    const capture = await choice.capture(); assertCurrent(); captures.push(capture)
    const value = structuredClone(capture.value)
    if (!glossary && !organization && !progress) { packages.push(value); continue }
    if (!source || value.documents.length !== 1) throw new Error(organizationExportErrors.identity)
    let document = value.documents[0]
    const proof = await inspectPortableIdentity(document); assertCurrent()
    if (proof.status !== 'ready') throw new Error(organizationExportErrors.identity)
    if (progress) {
      try { await readReadingProgressSnapshotFromDocument(document) }
      catch { throw new Error(accountExportErrors.progressSnapshot) }
      assertCurrent()
    }
    let recordId: string
    if (source.kind === 'portable') {
      const saved = capture.portableBook
      if (!saved || saved.id !== source.id || choice.key !== saved.id) throw new Error(organizationExportErrors.source)
      const linked = await captureBinding(saved, identityClient!, signal); assertCurrent()
      if (!linked) throw new Error(organizationExportErrors.binding)
      if (linked.kind !== proof.identity.kind || !sameLibraryIdentity(linked.identity, proof.identity)) throw new Error(organizationExportErrors.identity)
      links.push(linked); recordId = linked.recordId
    } else {
      const result = await identityClient!.verify(source.recordId, proof.identity, signal); assertCurrent()
      if (result.verified !== true || result.kind !== proof.identity.kind || result.recordId !== source.recordId.toLowerCase() || !sameLibraryIdentity(result.identity, proof.identity)) throw new Error(organizationExportErrors.identity)
      recordId = result.recordId
    }
    await capture.assertCurrent(); assertCurrent()
    if (glossary) {
      if (proof.identity.kind === 'TRANSLATION' && proof.identity.targetLanguage !== glossary.targetLanguage) throw new Error(glossaryExportErrors.translationLanguage)
      let scope: BookGlossaryScope
      try { scope = validateBookGlossaryScope({ providerId: proof.identity.contentProviderId, bookId: proof.identity.bookId, targetLanguage: glossary.targetLanguage }) }
      catch { throw new Error(glossaryExportErrors.identity) }
      let previous
      try { previous = readBookGlossarySnapshotsFromDocument(document) }
      catch { throw new Error(glossaryExportErrors.snapshot) }
      const key = bookGlossaryKey(scope); glossaryScopes.set(key, scope)
      let snapshot = glossarySnapshots.get(key)
      if (!snapshot) {
        const incoming = await glossary.reader.read(scope, signal); assertCurrent()
        let view
        try { view = validateBookGlossaryView(incoming); if (!sameBookGlossaryScope(view, scope)) throw new Error() }
        catch { throw new Error(glossaryExportErrors.response) }
        snapshot = view.version === 0 ? { ...scope, presence: 'absent', entries: null }
          : view.entries === null ? { ...scope, presence: 'deleted', entries: null } : { ...scope, presence: 'present', entries: view.entries }
        glossarySnapshots.set(key, snapshot)
      }
      const values = previous?.snapshots.slice() ?? [], index = values.findIndex(value => sameBookGlossaryScope(value, scope))
      if (index < 0) values.push(snapshot); else values[index] = snapshot
      const extension = { version: 1, snapshots: values }
      if (new TextEncoder().encode(JSON.stringify({ ...document.extensions, bookGlossarySnapshots: extension })).byteLength > 256 * 1024) throw new Error(glossaryExportErrors.size)
      try { document = withBookGlossarySnapshots(document, extension) }
      catch { throw new Error(glossaryExportErrors.snapshot) }
      assertCurrent()
    }
    if (organization) {
      try { await readLibraryOrganizationSnapshotFromDocument(document) }
      catch { throw new Error(organizationExportErrors.snapshot) }
      assertCurrent()
      const scope: LibraryOrganizationScope = { kind: proof.identity.kind, recordId }, key = JSON.stringify(scope)
      organizationScopes.set(key, scope)
      let snapshot = organizationSnapshots.get(key)
      if (!snapshot) {
        const incoming = await organization.reader.read(scope, signal); assertCurrent()
        let view
        try { view = validateLibraryOrganizationView(incoming); if (!sameLibraryOrganizationScope(scope, view)) throw new Error() }
        catch { throw new Error(organizationExportErrors.response) }
        snapshot = view.version === 0 ? { version: 1, identity: proof.identity, presence: 'absent', organization: null }
          : { version: 1, identity: proof.identity, presence: 'present', organization: view.organization! }
        // Organization is scoped by account record; the same record is independently proved above.
        organizationSnapshots.set(key, snapshot)
      }
      if (new TextEncoder().encode(JSON.stringify({ ...document.extensions, libraryOrganizationSnapshot: snapshot })).byteLength > 256 * 1024) throw new Error(organizationExportErrors.size)
      try { document = await withLibraryOrganizationSnapshot(document, snapshot) }
      catch { throw new Error(organizationExportErrors.snapshot) }
      assertCurrent()
    }
    if (progress) {
      const scope = captureFreshReadingProgressIdentity({ kind: proof.identity.kind, recordId }), key = JSON.stringify(scope)
      progressScopes.set(key, scope)
      let snapshot = progressSnapshots.get(key)
      if (!snapshot) {
        const incoming = await progress.reader.read({ ...scope }, signal); assertCurrent()
        const view = captureFreshReadingProgressView(incoming, scope)
        snapshot = view.version === 0 ? { version: 1, identity: proof.identity, presence: 'absent', anchor: null }
          : { version: 1, identity: proof.identity, presence: 'present', anchor: view.anchor! }
        progressSnapshots.set(key, snapshot)
      }
      const extensions = { ...document.extensions, readingProgressSnapshot: snapshot }
      if (new TextEncoder().encode(JSON.stringify(extensions)).byteLength > 256 * 1024 ||
        new TextEncoder().encode(JSON.stringify({ ...document, extensions })).byteLength > exchangeLimits.document) throw new Error(accountExportErrors.progressSize)
      try { document = await withReadingProgressSnapshot(document, snapshot) }
      catch { throw new Error(readingProgressExportErrors.response) }
      assertCurrent()
    }
    packages.push({ ...value, documents: [document] })
  }
  await checkBeforeSave()
  const merged = await mergeExchangePackages(packages); assertCurrent()
  const bytes = await encode(merged); assertCurrent()
  await checkBeforeSave()
  return { bytes, checkBeforeSave, assertCurrent }
}
