import { sameLibraryOrganizationScope, validateLibraryOrganizationView, type LibraryOrganizationScope, type LibraryOrganizationView } from './libraryOrganizationApi'
import { mergeExchangePackages, type ExchangeExportChoice, type ExchangeExportCapture } from './exchangeLibrary'
import { inspectPortableIdentity, sameLibraryIdentity } from './libraryIdentity'
import type { LibraryIdentityClient } from './libraryIdentityApi'
import { writeExchange, type ExchangePackage } from './libraryExchange'
import { readLibraryOrganizationSnapshotFromDocument, withLibraryOrganizationSnapshot, type PortableLibraryOrganizationSnapshot } from './portableLibraryOrganization'
import type { createPortableBindings, PortableBindingCapture } from './portableBinding'

export const organizationExportErrors = {
  stale: '계정 또는 서버 연결이 변경되어 분류 조회를 취소했습니다. 다시 선택해 주세요.',
  offline: '최신 계정 분류를 포함하려면 서버에 다시 로그인해 주세요.',
  pending: '먼저 계정 분류의 대기 중인 변경이나 충돌을 해결한 뒤 다시 내보내 주세요.',
  response: '최신 계정 분류 응답이 기존 기록과 일치하지 않습니다. 다시 조회해 주세요.',
  source: '선택한 기기 문서가 변경되거나 삭제되었습니다. 책 목록을 다시 불러와 주세요.',
  identity: '선택한 책의 서버 원본과 전체 본문을 확인하지 못했습니다. 서버 원본을 다시 확인해 주세요.',
  binding: '가져온 ZIP 책을 먼저 서버 원본 확인 화면에서 계정 기록에 연결해 주세요.',
  snapshot: '기존 분류 스냅샷의 규격이나 원본 정보를 확인하지 못했습니다. 원래 파일은 보존됩니다.',
  size: '최신 분류와 기존 확장 정보가 ZIP의 256 KiB 한도를 넘습니다. 내용을 자르지 않았습니다.',
} as const

/** Read-only session: never opens a controller, initializes a journal, or sends mutations. */
export interface FreshLibraryOrganizationReader {
  assertCurrent(): void
  read(scope: LibraryOrganizationScope, signal?: AbortSignal): Promise<LibraryOrganizationView>
  checkReady(scope: LibraryOrganizationScope, signal?: AbortSignal): Promise<void>
}

/** Memory-only save guards. These references, bindings and journal state never enter the ZIP. */
export type PreparedOrganizationExport = { bytes: Uint8Array; checkBeforeSave(): Promise<void> }

/** Prepares an explicit passive snapshot; neither lookup nor ZIP creation changes account records. */
export async function prepareFreshOrganizationExport(options: {
  choices: ExchangeExportChoice[]; reader: FreshLibraryOrganizationReader; identityClient: LibraryIdentityClient | null
  bindings: Pick<ReturnType<typeof createPortableBindings>, 'capture'>; current: () => boolean
  signal?: AbortSignal; encode?: typeof writeExchange
}): Promise<PreparedOrganizationExport> {
  const { reader, identityClient, signal } = options
  const assert = () => {
    if (!options.current() || signal?.aborted) throw new Error(organizationExportErrors.stale)
    reader.assertCurrent()
  }
  assert()
  if (!identityClient) throw new Error(organizationExportErrors.offline)
  const choices = options.choices.slice()
  if (!choices.length || choices.length > 100 || new Set(choices.map(choice => choice.key)).size !== choices.length) throw new Error(organizationExportErrors.source)
  const captures: ExchangeExportCapture[] = [], bindings: PortableBindingCapture[] = []
  const scopes = new Map<string, LibraryOrganizationScope>(), views = new Map<string, LibraryOrganizationView>()
  const packages: ExchangePackage[] = []
  const checkBeforeSave = async () => {
    assert()
    for (const scope of scopes.values()) { await reader.checkReady(scope, signal); assert() }
    // Journal reads may suspend long enough for a selected source to be deleted or replaced.
    for (const capture of captures) { await capture.assertCurrent(); assert() }
    // No suspension after the final raw-generation checks, so a queued UI task cannot intervene.
    for (const binding of bindings) binding.assertCurrent()
    assert()
  }
  for (const choice of choices) {
    assert()
    const source = choice.glossarySource ? structuredClone(choice.glossarySource) : undefined
    if (!choice.capture || !source) throw new Error(organizationExportErrors.source)
    const capture = await choice.capture(); assert()
    captures.push(capture)
    const value = structuredClone(capture.value)
    if (value.documents.length !== 1) throw new Error(organizationExportErrors.source)
    const document = value.documents[0], proof = await inspectPortableIdentity(document); assert()
    if (proof.status !== 'ready') throw new Error(organizationExportErrors.identity)
    try { await readLibraryOrganizationSnapshotFromDocument(document) }
    catch { throw new Error(organizationExportErrors.snapshot) }
    assert()
    let recordId: string
    if (source.kind === 'portable') {
      const saved = capture.portableBook
      if (!saved || saved.id !== source.book.id || choice.key !== saved.id) throw new Error(organizationExportErrors.source)
      const linked = await options.bindings.capture(saved, identityClient, signal); assert()
      if (!linked) throw new Error(organizationExportErrors.binding)
      if (linked.kind !== proof.identity.kind || !sameLibraryIdentity(linked.identity, proof.identity)) throw new Error(organizationExportErrors.identity)
      bindings.push(linked); recordId = linked.recordId
    } else {
      const result = await identityClient.verify(source.recordId, proof.identity, signal); assert()
      if (result.verified !== true || result.kind !== proof.identity.kind || result.recordId !== source.recordId.toLowerCase() || !sameLibraryIdentity(result.identity, proof.identity)) throw new Error(organizationExportErrors.identity)
      recordId = result.recordId
    }
    await capture.assertCurrent(); assert()
    for (const binding of bindings) binding.assertCurrent()
    const scope: LibraryOrganizationScope = { kind: proof.identity.kind, recordId }, key = JSON.stringify(scope)
    scopes.set(key, scope)
    let view = views.get(key)
    if (!view) {
      const incoming = await reader.read(scope, signal); assert()
      try { view = validateLibraryOrganizationView(incoming); if (!sameLibraryOrganizationScope(scope, view)) throw new Error() }
      catch { throw new Error(organizationExportErrors.response) }
      views.set(key, view)
    }
    const snapshot: PortableLibraryOrganizationSnapshot = view.version === 0
      ? { version: 1, identity: proof.identity, presence: 'absent', organization: null }
      : { version: 1, identity: proof.identity, presence: 'present', organization: view.organization! }
    if (new TextEncoder().encode(JSON.stringify({ ...document.extensions, libraryOrganizationSnapshot: snapshot })).byteLength > 256 * 1024) throw new Error(organizationExportErrors.size)
    try { packages.push({ ...value, documents: [await withLibraryOrganizationSnapshot(document, snapshot)] }) }
    catch { throw new Error(organizationExportErrors.snapshot) }
    assert()
  }
  await checkBeforeSave()
  const merged = await mergeExchangePackages(packages); assert()
  const bytes = await (options.encode ?? writeExchange)(merged); assert()
  await checkBeforeSave()
  return { bytes, checkBeforeSave }
}
