import type { ReadingDocument } from './readingDocument'
import { readingNamespace, readingTransaction, type DeviceDatabaseOptions } from './deviceReadingDatabase'
import { emptyLocalOrganization, validateLocalOrganization, type LocalOrganization } from './localOrganization'

export const MAX_LOCAL_FILE_BYTES = 32 * 1024 * 1024
export const MAX_LOCAL_TEXT_LENGTH = 5_000_000
export type LocalEncoding = 'auto' | 'utf-8' | 'utf-16le' | 'utf-16be' | 'euc-kr' | 'gb18030' | 'shift_jis' | 'windows-1252'
export type LocalDocument = ReadingDocument & { kind: 'local'; local: NonNullable<ReadingDocument['local']> }
export type SavedLocalDocument = { document: LocalDocument; savedAt: string; organization: LocalOrganization }
type StoredLocalDocument = Omit<SavedLocalDocument, 'organization'> & { organization?: LocalOrganization; username: string; id: string; payloadHash: string; readingHash: string }

export function decodeLocalText(bytes: Uint8Array, encoding: LocalEncoding = 'auto'): { text: string; encoding: string } {
  const detected = encoding === 'auto' ? bytes[0] === 0xff && bytes[1] === 0xfe ? 'utf-16le' : bytes[0] === 0xfe && bytes[1] === 0xff ? 'utf-16be' : 'utf-8' : encoding
  let text: string
  try { text = new TextDecoder(detected, { fatal: true }).decode(bytes) }
  catch { throw new Error('문자 인코딩을 읽지 못했습니다. 파일의 인코딩을 선택한 뒤 다시 가져와 주세요.') }
  if (text.includes('\0') || text.includes('\ufffd')) throw new Error('텍스트 파일에 잘못된 문자가 있습니다. 인코딩과 파일 형식을 확인해 주세요.')
  return { text, encoding: detected }
}

export async function localFileHash(bytes: Uint8Array): Promise<string> {
  if (!crypto.subtle) throw new Error('파일 확인을 위해 HTTPS 또는 localhost에서 열어 주세요.')
  return Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', Uint8Array.from(bytes).buffer)), byte => byte.toString(16).padStart(2, '0')).join('')
}

export function textParagraphs(text: string): string[] {
  if (text.length > MAX_LOCAL_TEXT_LENGTH) throw new Error('문서의 본문이 너무 큽니다. 더 작은 파일로 나누어 주세요.')
  const paragraphs = text.replace(/\r\n?/g, '\n').split(/\n\s*\n/).flatMap(paragraph => {
    const value = paragraph.trim()
    // Bound individual paragraphs without losing text or splitting a surrogate pair.
    const chunks: string[] = []
    for (let at = 0; at < value.length;) {
      let end = Math.min(value.length, at + 8000)
      if (end < value.length && /[\uD800-\uDBFF]/.test(value[end - 1])) end--
      chunks.push(value.slice(at, end)); at = end
    }
    return chunks
  }).filter(Boolean)
  if (!paragraphs.length) throw new Error('읽을 수 있는 본문이 없습니다.')
  if (paragraphs.length > 50_000) throw new Error('문단이 너무 많습니다. 더 작은 파일로 나누어 주세요.')
  return paragraphs
}

export async function parseLocalDocument(file: { name: string; size: number; arrayBuffer(): Promise<ArrayBuffer> }, options: { encoding?: LocalEncoding; signal?: AbortSignal } = {}): Promise<LocalDocument> {
  if (file.size <= 0 || file.size > MAX_LOCAL_FILE_BYTES) throw new Error('비어 있지 않은 32MB 이하 파일을 선택해 주세요.')
  options.signal?.throwIfAborted()
  const bytes = new Uint8Array(await file.arrayBuffer())
  if (bytes.byteLength > MAX_LOCAL_FILE_BYTES || !bytes.byteLength) throw new Error('비어 있지 않은 32MB 이하 파일을 선택해 주세요.')
  options.signal?.throwIfAborted()
  const contentHash = await localFileHash(bytes)
  const extension = file.name.toLowerCase().split('.').pop()
  const id = `local-sha256:${contentHash}`
  if (extension === 'pdf') {
    const { parsePdfPages } = await import('./pdfDocument')
    const pages = await parsePdfPages(bytes, options.signal)
    return { id, kind: 'local', bookTitle: file.name.replace(/\.pdf$/i, ''), chapterTitle: file.name, language: 'auto',
      paragraphs: pages.texts.map((text, index) => ({ paragraphId: `${id}:p${index}`, text })),
      outline: pages.texts.map((_, index) => ({ title: `PDF ${index + 1}`, paragraphId: `${id}:p${index}` })),
      local: { format: 'pdf', byteLength: bytes.byteLength, contentHash, pdfTextPages: pages.hasText, pdfTextErrorPages: pages.textErrors }, assets: { pdf: new Blob([bytes], { type: 'application/pdf' }) } }
  }
  if (extension === 'epub') {
    const { parseEpub } = await import('./epubDocument')
    const epub = await parseEpub(bytes, options.signal)
    const paragraphs: LocalDocument['paragraphs'] = [], outline: NonNullable<LocalDocument['outline']> = [], images: NonNullable<NonNullable<LocalDocument['assets']>['images']> = []
    for (const chapter of epub.chapters) {
      const paragraphId = `${id}:p${paragraphs.length}`
      outline.push({ title: chapter.title, paragraphId })
      images.push(...chapter.images.map(image => ({ ...image, paragraphId })))
      for (const text of chapter.paragraphs) paragraphs.push({ paragraphId: `${id}:p${paragraphs.length}`, text })
    }
    return { id, kind: 'local', bookTitle: epub.title || file.name.replace(/\.epub$/i, ''), chapterTitle: file.name, language: epub.language,
      paragraphs, outline, assets: { images }, local: { format: 'epub', byteLength: bytes.byteLength, contentHash } }
  }
  if (!['txt', 'md', 'markdown'].includes(extension ?? '')) throw new Error('지원하는 문서 파일을 선택해 주세요.')
  const decoded = decodeLocalText(bytes, options.encoding)
  const markdown = extension !== 'txt'
  const text = markdown ? decoded.text.replace(/^#{1,6}\s+/gm, '').replace(/^>\s?/gm, '').replace(/`{1,3}/g, '').replace(/\[([^\]]*)\]\([^)]*\)/g, '$1') : decoded.text
  const paragraphs = textParagraphs(text)
  options.signal?.throwIfAborted()
  return { id, kind: 'local', bookTitle: file.name.replace(/\.(txt|md|markdown)$/i, '') || file.name, chapterTitle: file.name, language: 'auto',
    paragraphs: paragraphs.map((text, index) => ({ paragraphId: `${id}:p${index}`, text })),
    local: { format: markdown ? 'markdown' : 'txt', byteLength: bytes.byteLength, contentHash, encoding: decoded.encoding } }
}

function validated(value: StoredLocalDocument): SavedLocalDocument {
  const doc = value?.document
  if (!doc || doc.kind !== 'local' || typeof doc.id !== 'string' || doc.id !== value.id || !doc.local ||
      !/^[a-f0-9]{64}$/.test(doc.local.contentHash) || doc.id !== `local-sha256:${doc.local.contentHash}` ||
      !['txt', 'markdown', 'epub', 'pdf'].includes(doc.local.format) || typeof doc.bookTitle !== 'string' || !doc.bookTitle.trim() || doc.bookTitle.length > 2000 ||
      !Array.isArray(doc.paragraphs) || !doc.paragraphs.length || doc.paragraphs.length > 50_000 ||
      doc.paragraphs.some((p, index) => !p || p.paragraphId !== `${doc.id}:p${index}` || typeof p.text !== 'string' || !p.text.trim()) ||
      doc.paragraphs.reduce((sum, p) => sum + p.text.length, 0) > MAX_LOCAL_TEXT_LENGTH || !Number.isFinite(Date.parse(value.savedAt))) {
    throw new Error('이 기기에 저장된 문서를 확인할 수 없습니다. 삭제 후 원본 파일을 다시 가져와 주세요.')
  }
  if (!Number.isSafeInteger(doc.local.byteLength) || doc.local.byteLength <= 0 || doc.local.byteLength > MAX_LOCAL_FILE_BYTES ||
      typeof doc.chapterTitle !== 'string' || !doc.chapterTitle.trim() || doc.chapterTitle.length > 2000 || typeof doc.language !== 'string' || doc.language.length < 2 || doc.language.length > 24 ||
      (doc.outline && (!Array.isArray(doc.outline) || doc.outline.length > 50_000 || doc.outline.some(item => typeof item.title !== 'string' || !doc.paragraphs.some(p => p.paragraphId === item.paragraphId))))) {
    throw new Error('이 기기에 저장된 문서의 정보를 확인할 수 없습니다.')
  }
  if (doc.local.format === 'pdf' && (!(doc.assets?.pdf instanceof Blob) || doc.assets.pdf.size !== doc.local.byteLength ||
      !Array.isArray(doc.local.pdfTextPages) || doc.local.pdfTextPages.length !== doc.paragraphs.length || doc.local.pdfTextPages.some(value => typeof value !== 'boolean'))) throw new Error('저장된 PDF 원본을 확인할 수 없습니다.')
  if (doc.local.pdfTextErrorPages !== undefined && (doc.local.format !== 'pdf' || !Array.isArray(doc.local.pdfTextErrorPages) ||
      doc.local.pdfTextErrorPages.length !== doc.paragraphs.length || doc.local.pdfTextErrorPages.some((value, index) => typeof value !== 'boolean' || (value && doc.local.pdfTextPages?.[index])))) throw new Error('저장된 PDF 텍스트 추출 상태를 확인할 수 없습니다.')
  if (doc.assets?.images) {
    if (!Array.isArray(doc.assets.images) || doc.assets.images.length > 4000 || doc.assets.images.some(image => !(image.blob instanceof Blob) || image.blob.size > 2 * 1024 * 1024 ||
        !['image/png', 'image/jpeg', 'image/gif', 'image/webp'].includes(image.blob.type) || typeof image.alt !== 'string' || !doc.paragraphs.some(p => p.paragraphId === image.paragraphId)) ||
        doc.assets.images.reduce((sum, image) => sum + image.blob.size, 0) > 20 * 1024 * 1024) throw new Error('저장된 EPUB 삽화를 확인할 수 없습니다.')
  }
  return { document: doc, savedAt: value.savedAt, organization: value.organization ? validateLocalOrganization(value.organization) : emptyLocalOrganization() }
}

async function documentHashes(document: LocalDocument) {
  const assetHashes: string[] = []
  if (document.assets?.pdf) {
    const hash = await localFileHash(new Uint8Array(await document.assets.pdf.arrayBuffer()))
    if (hash !== document.local.contentHash) throw new Error('저장된 PDF 원본이 파일 식별자와 일치하지 않습니다.')
    assetHashes.push(hash)
  }
  if (document.assets?.images?.length) {
    const { safeImageType } = await import('./epubDocument')
    for (const image of document.assets.images) {
      const bytes = new Uint8Array(await image.blob.arrayBuffer())
      if (safeImageType(bytes) !== image.blob.type) throw new Error('저장된 EPUB 삽화의 형식이 올바르지 않습니다.')
      assetHashes.push(`${image.paragraphId}:${image.alt}:${await localFileHash(bytes)}`)
    }
  }
  const hash = (value: unknown) => localFileHash(new TextEncoder().encode(JSON.stringify(value)))
  const { assets: _, ...textDocument } = document
  return { payloadHash: await hash([textDocument, assetHashes]), readingHash: await hash([document.paragraphs, document.outline]) }
}

export function createLocalDocuments(username: string, options: DeviceDatabaseOptions = {}) {
  const namespace = readingNamespace(username)
  return {
    async list(): Promise<{ books: SavedLocalDocument[]; damagedIds: string[] }> {
      const values = await readingTransaction<StoredLocalDocument[]>(['documents'], 'readonly', (tx, result) => {
        const request = tx.objectStore('documents').index('username').getAll(namespace)
        request.onsuccess = () => result(request.result)
      }, options)
      const books: SavedLocalDocument[] = [], damagedIds: string[] = []
      for (const value of values) {
        try {
          const checked = validated(value), hashes = await documentHashes(checked.document)
          if (hashes.payloadHash !== value.payloadHash || hashes.readingHash !== value.readingHash) throw new Error('Stored document checksum mismatch')
          books.push(checked)
        } catch { damagedIds.push(value.id) }
      }
      books.sort((a, b) => b.savedAt.localeCompare(a.savedAt) || a.document.id.localeCompare(b.document.id))
      return { books, damagedIds }
    },
    async save(document: LocalDocument): Promise<void> {
      const snapshot = structuredClone(document)
      const value: StoredLocalDocument = { username: namespace, id: snapshot.id, document: snapshot, savedAt: new Date().toISOString(), payloadHash: '', readingHash: '' }
      validated(value)
      Object.assign(value, await documentHashes(snapshot))
      await readingTransaction<void>(['documents'], 'readwrite', (tx, _, fail) => {
        const store = tx.objectStore('documents'), request = store.get([namespace, snapshot.id])
        request.onsuccess = () => {
          const previous = request.result as StoredLocalDocument | undefined
          if (previous && previous.readingHash !== value.readingHash) { fail(new Error('같은 파일을 다른 본문으로 덮어쓸 수 없습니다. 기존 파일과 읽기 기록을 확인한 뒤 삭제하고 다시 가져와 주세요.')); return }
          if (previous) { value.savedAt = previous.savedAt; value.organization = previous.organization }
          store.put(value)
        }
      }, options)
    },
    async organize(documentId: string, input: LocalOrganization): Promise<void> {
      const organization = validateLocalOrganization(input)
      await readingTransaction<void>(['documents'], 'readwrite', (tx, _, fail) => {
        const store = tx.objectStore('documents'), request = store.get([namespace, documentId])
        request.onsuccess = () => {
          const previous = request.result as StoredLocalDocument | undefined
          if (!previous) { fail(new Error('분류할 로컬 파일을 찾지 못했습니다. 서재를 새로 불러와 주세요.')); return }
          store.put({ ...previous, organization })
        }
      }, options)
    },
    async remove(documentId: string): Promise<void> {
      await readingTransaction<void>(['documents', 'notes', 'positions'], 'readwrite', tx => {
        tx.objectStore('documents').delete([namespace, documentId])
        tx.objectStore('positions').delete([namespace, documentId])
        const cursor = tx.objectStore('notes').index('document').openCursor([namespace, documentId])
        cursor.onsuccess = () => { if (cursor.result) { cursor.result.delete(); cursor.result.continue() } }
      }, options)
    },
  }
}

/** Upload only actual extracted text; raster-only PDF page labels are reader metadata. */
export function localDocumentForTranslation(document: LocalDocument): ReadingDocument {
  if (document.local.format === 'pdf') {
    if (!document.local.pdfTextErrorPages || document.local.pdfTextErrorPages.length !== document.paragraphs.length)
      throw new Error('PDF의 텍스트 추출 상태를 확인하려면 원본 파일을 다시 가져와 주세요.')
    if (document.local.pdfTextErrorPages.some(Boolean))
      throw new Error('텍스트를 추출하지 못한 PDF 페이지가 있어 파일 전체를 번역할 수 없습니다. 원본은 계속 읽을 수 있습니다.')
  }
  const paragraphs = document.local.format === 'pdf' ? document.paragraphs.filter((_, index) => document.local.pdfTextPages?.[index]) : document.paragraphs
  if (!paragraphs.length) throw new Error('번역할 텍스트가 없습니다. 이미지 PDF는 원본으로만 읽을 수 있습니다.')
  if (paragraphs.length > 10_000 || paragraphs.reduce((sum, p) => sum + p.text.length, 0) > 1_000_000) throw new Error('서버 번역은 10,000개 문단·100만 문자 이하입니다. 파일을 나누어 가져와 주세요.')
  return { id: document.id, bookTitle: document.bookTitle, chapterTitle: document.chapterTitle, language: document.language || 'auto', kind: 'local',
    local: { format: document.local.format, byteLength: document.local.byteLength, contentHash: document.local.contentHash },
    paragraphs: paragraphs.map(p => ({ ...p })), outline: document.outline?.filter(item => paragraphs.some(p => p.paragraphId === item.paragraphId)) }
}
