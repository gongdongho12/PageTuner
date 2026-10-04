import { readingNamespace, readingTransaction, type DeviceDatabaseOptions } from './deviceReadingDatabase'
import { validateSavedExchangePdfContent, type SavedExchange } from './exchangeLibrary'
import { PdfContentError, type PdfContentClient, type PdfContentDocument, type PdfContentReceipt } from './pdfContentApi'
import { validatePortableContentProof, type PortableContentProof } from './portableContentProof'
import { validRecordId } from './validation'

export const pdfBindingErrors = {
  stale: 'PDF 사본이나 연결 상태가 변경되었습니다. 다시 확인해 주세요.',
  offline: 'PDF를 서버에 보관하거나 연결하려면 다시 로그인해 주세요.',
  damaged: 'PDF 연결 정보를 확인할 수 없습니다. 기기 원본은 보존됩니다.',
  unlinked: '이 PDF 사본은 현재 계정의 서버 보관본에 연결되지 않았습니다.',
  busy: 'PDF 작업이 이미 진행 중입니다.',
} as const
type StoredCopy = SavedExchange & { username: string }
export type PdfCopyLink = { recordId: string; proof: PortableContentProof }
type BindingRow = { version: 1; username: string; origin: string; copyId: string; nonce: string; link: PdfCopyLink | null }
export type PdfCopyReview = {
  readonly title: string; readonly content: PdfContentDocument; readonly proof: PortableContentProof; readonly binding: PdfCopyLink | null
  readonly uploadedReceipt: PdfContentReceipt | undefined
  upload(): Promise<PdfContentReceipt>
  compare(recordId: string): Promise<PdfContentReceipt>
  confirm(): Promise<void>
  unlink(): Promise<void>
  open(): Promise<SavedExchange>
}
const copy = <T>(value: T): T => structuredClone(value)
/** Deliberately synchronous: asynchronous digests cannot keep an IndexedDB transaction alive. */
function same(a: unknown, b: unknown): boolean {
  if (a === b) return true
  if (a instanceof Uint8Array || b instanceof Uint8Array) return a instanceof Uint8Array && b instanceof Uint8Array && a.length === b.length && a.every((value, index) => value === b[index])
  if (!a || !b || typeof a !== 'object' || typeof b !== 'object' || Array.isArray(a) !== Array.isArray(b)) return false
  if (Array.isArray(a) && Array.isArray(b) && a.length !== b.length) return false
  const left = Object.keys(a), right = Object.keys(b)
  return left.length === right.length && left.every(key => Object.hasOwn(b, key) && same((a as Record<string, unknown>)[key], (b as Record<string, unknown>)[key]))
}
const stale = (): never => { throw new Error(pdfBindingErrors.stale) }

/** Separate PDF content linkage, never a ReadingDocument or S1–S3 permission. Each panel owns one lifetime. */
export function createPortablePdfSession({ username, origin, client, current, ...options }: DeviceDatabaseOptions & {
  username: string; origin: string; client: PdfContentClient | null; current: () => boolean
}) {
  const namespace = readingNamespace(username), server = new URL(origin).origin, lifetime = new AbortController()
  const assertCurrent = () => { if (lifetime.signal.aborted || !current()) throw new PdfContentError('aborted') }
  async function read(copyId: string) {
    assertCurrent()
    const value = await readingTransaction<{ row?: StoredCopy; binding?: BindingRow }>(['exchanges', 'pdfContentBindings'], 'readonly', (tx, done) => {
      const result: { row?: StoredCopy; binding?: BindingRow } = {}; done(result)
      const book = tx.objectStore('exchanges').get([namespace, copyId]), binding = tx.objectStore('pdfContentBindings').get([namespace, server, copyId])
      book.onsuccess = () => { result.row = book.result }; binding.onsuccess = () => { result.binding = binding.result }
    }, options)
    assertCurrent(); return value
  }
  return {
    close() { lifetime.abort() },
    async prepare(copyId: string): Promise<PdfCopyReview> {
      assertCurrent()
      if (!/^exchange:[a-f0-9]{64}$/.test(copyId)) throw new PdfContentError('invalid-request')
      const before = await read(copyId)
      if (!before.row) throw new PdfContentError('not-found')
      const row = before.row, binding = before.binding
      const prepared = await validateSavedExchangePdfContent(row, namespace, copyId, lifetime.signal); assertCurrent()
      if (binding) {
        if (binding.version !== 1 || binding.username !== namespace || binding.origin !== server || binding.copyId !== copyId || !validRecordId(binding.nonce) || !Object.hasOwn(binding, 'link')) throw new Error(pdfBindingErrors.damaged)
        if (binding.link !== null) {
          if (!validRecordId(binding.link?.recordId)) throw new Error(pdfBindingErrors.damaged)
          let proof: PortableContentProof
          try { proof = await validatePortableContentProof(binding.link.proof) } catch { throw new Error(pdfBindingErrors.damaged) }
          assertCurrent()
          if (!same(proof, prepared.proof)) throw new Error(pdfBindingErrors.stale)
        }
      }
      let running = false, consumed = false, uploadId: string | undefined, candidate: PdfContentReceipt | undefined, uploadedReceipt: PdfContentReceipt | undefined
      const network = () => { assertCurrent(); if (!client) throw new Error(pdfBindingErrors.offline); return client }
      const check = async () => { const now = await read(copyId); if (!same(now.row, row) || !same(now.binding, binding)) stale() }
      const atomic = async (next?: BindingRow) => {
        assertCurrent()
        await readingTransaction(['exchanges', 'pdfContentBindings'], next ? 'readwrite' : 'readonly', (tx, done, fail) => {
          const abort = () => { try { tx.abort() } catch { /* Already committed: the authorized write is retained. */ } }
          lifetime.signal.addEventListener('abort', abort, { once: true })
          tx.addEventListener('complete', () => lifetime.signal.removeEventListener('abort', abort), { once: true })
          tx.addEventListener('abort', () => lifetime.signal.removeEventListener('abort', abort), { once: true })
          const book = tx.objectStore('exchanges').get([namespace, copyId]), stored = tx.objectStore('pdfContentBindings').get([namespace, server, copyId])
          let count = 0
          const finish = () => { if (++count !== 2) return; try {
            assertCurrent()
            if (!same(book.result, row) || !same(stored.result, binding)) stale()
            if (next) tx.objectStore('pdfContentBindings').put(next)
            done(undefined)
          } catch (error) { fail(error instanceof Error ? error : new Error(pdfBindingErrors.stale)) } }
          book.onsuccess = finish; stored.onsuccess = finish
          if (lifetime.signal.aborted) abort()
        }, options)
        assertCurrent()
      }
      async function exclusive<T>(action: () => Promise<T>, consume = false): Promise<T> {
        assertCurrent(); if (consumed) stale(); if (running) throw new Error(pdfBindingErrors.busy)
        running = true; if (consume) consumed = true
        try { return await action() } finally { running = false }
      }
      async function compare(recordId: string) {
        candidate = undefined
        await check()
        // GET rehashes actual server bytes. A stored proof or verify receipt alone is insufficient here.
        const record = await network().get(recordId, lifetime.signal); assertCurrent()
        if (!same(record.proof, prepared.proof)) throw new PdfContentError('mismatch')
        await check(); candidate = { recordId: record.recordId, createdAt: record.createdAt, proof: copy(record.proof) }
        return copy(candidate)
      }
      return {
        title: row.document.bookTitle, content: copy(prepared.content), proof: copy(prepared.proof), binding: copy(binding?.link ?? null),
        get uploadedReceipt() { return uploadedReceipt ? copy(uploadedReceipt) : undefined },
        upload: () => exclusive(async () => {
          candidate = undefined
          await check(); uploadId ??= crypto.randomUUID()
          const receipt = await network().upload(uploadId, prepared.content, lifetime.signal); assertCurrent()
          uploadedReceipt = copy(receipt)
          return compare(receipt.recordId)
        }),
        compare: recordId => exclusive(() => compare(recordId)),
        confirm: () => exclusive(async () => {
          const selected = candidate ?? stale()
          await compare(selected.recordId)
          await atomic({ version: 1, username: namespace, origin: server, copyId, nonce: crypto.randomUUID(), link: { recordId: selected.recordId, proof: copy(prepared.proof) } })
        }, true),
        unlink: () => exclusive(async () => {
          if (!binding?.link) throw new Error(pdfBindingErrors.unlinked)
          // Tombstones retain ABA history; unlinking never deletes the ZIP or server snapshot.
          await atomic({ version: 1, username: namespace, origin: server, copyId, nonce: crypto.randomUUID(), link: null })
        }, true),
        open: () => exclusive(async () => {
          if (!binding?.link) throw new Error(pdfBindingErrors.unlinked)
          await compare(binding.link.recordId); await atomic()
          // Canonical local bytes only. No serverProgress, glossaryIdentity or synthetic PDF anchors.
          return copy(row)
        }),
      }
    },
  }
}

export function pdfBindingErrorMessage(error: unknown): string {
  if (error instanceof PdfContentError) {
    switch (error.code) {
      case 'aborted': return '계정이나 PDF 선택이 변경되어 작업을 취소했습니다. 다시 확인해 주세요.'
      case 'network': case 'timeout': return '서버 응답을 확인하지 못했습니다. 업로드했다면 저장되었을 수 있습니다. 같은 요청으로 다시 확인해 주세요.'
      case 'authentication': case 'forbidden': return pdfBindingErrors.offline
      case 'not-found': return '현재 계정에서 PDF 사본을 찾을 수 없습니다. 기기 또는 서버 사본을 확인해 주세요.'
      case 'mismatch': case 'unavailable': return '서버 PDF의 실제 내용이 이 사본과 일치하지 않거나 손상되었습니다. 연결하지 않았습니다.'
      case 'invalid-request': case 'too-large': return '이 PDF는 서버 보관 규격을 충족하지 않습니다. 자산 합계 4 MiB 이하인지 확인해 주세요. 기기 원본은 보존됩니다.'
      case 'upload-reused': return '업로드 요청 번호가 다른 내용에 사용되었습니다. 이 패널을 닫고 새로 확인해 주세요.'
      default: return 'PDF 서버 응답을 검증하지 못했습니다. 기기 원본은 보존됩니다.'
    }
  }
  return error instanceof Error ? error.message : 'PDF 작업을 완료하지 못했습니다.'
}
