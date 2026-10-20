import { readingTransaction, readingNamespace, type DeviceDatabaseOptions } from './deviceReadingDatabase'
import { validateExchangeDocument, readExchange, writeExchange, type ExchangePackage, type ExchangeDocument, type ExchangeAsset } from './libraryExchange'
import { localFileHash, createLocalDocuments, type LocalDocument } from './localDocuments'
import { createReadingNotes } from './readingNotes'
import { createOfflineLibrary } from './offline'
import { createPersonalLibrary } from './personalLibrary'
import { translationReadingDocument, getTranslationPosition } from './translationReading'
import { getWorkflowPosition } from './workflowPosition'
import type { ReadingDocument } from './readingDocument'
import { validAnchor, validReadingPosition } from './readingDocument'
import { readingRangeText } from './readingSelection'
import { originalLibraryIdentity, translationLibraryIdentity, type LibraryDocumentIdentity } from './libraryIdentity'
import { preparePdfContentFromExchange } from './exchangePdfContent'
import { PdfContentError } from './pdfContentApi'

export type SavedExchange = { id: string; document: ExchangeDocument; assets: ExchangeAsset[]; importedAt: string; checksum: string; integratedNoteIds: string[] }
type Row = SavedExchange & { username: string }
const bytes = (value: unknown) => new TextEncoder().encode(JSON.stringify(value))
const metadata = <T extends { paragraphs: unknown }>(value: T) => { const { paragraphs: _, ...rest } = value; return rest }
/** Legacy source metadata may exceed the new proof bounds; keep its existing portable export. */
async function identityExtension(recordId: string, create: () => Promise<LibraryDocumentIdentity>) {
  try { return { documentIdentity: await create(), serverRecordId: recordId } } catch { return {} }
}
async function contentId(doc: ExchangeDocument) { return `exchange:${await localFileHash(bytes(doc))}` }
async function checksum(doc: ExchangeDocument, assets: ExchangeAsset[]) { return localFileHash(bytes([doc, await Promise.all(assets.map(async a => [a.path, a.mimeType, await localFileHash(a.bytes)]))])) }

const sourceChanged = () => new Error('선택한 문서가 변경되었거나 삭제되었습니다. 목록을 새로 불러온 뒤 다시 선택해 주세요.')
async function validatedExchangeRow(row: Row, namespace: string, id: string): Promise<SavedExchange> {
  if (!row || row.username !== namespace || row.id !== id || !/^exchange:[a-f0-9]{64}$/.test(id)) throw sourceChanged()
  const document = validateExchangeDocument(row.document)
  // Validate ordered references, exact payload bytes, MIME and all package limits as on import.
  await writeExchange({ createdAt: row.importedAt, documents: [document], assets: row.assets })
  if (row.id !== await contentId(document) || row.checksum !== await checksum(document, row.assets)) throw sourceChanged()
  const reading = exchangeReadingDocument({ ...row, document })
  const integrated = document.notes.filter(note => supportedNote(note, reading)).map(note => note.id)
  if (!Array.isArray(row.integratedNoteIds) || JSON.stringify(row.integratedNoteIds) !== JSON.stringify(integrated)) throw sourceChanged()
  const { username: _, ...saved } = row
  return { ...saved, document }
}

/** Validates a single captured database row without consulting cached reader objects. */
export async function validateSavedExchangePdfContent(row: Row, namespace: string, id: string, signal?: AbortSignal) {
  const current = () => { if (signal?.aborted) throw new PdfContentError('aborted') }
  current()
  const doc = validateExchangeDocument(row.document), paths = new Set(doc.assets.map(a => a.path))
  if (row.username !== namespace || row.id !== id || !Array.isArray(row.assets) || row.assets.length !== paths.size || row.assets.some(a => !a || !paths.has(a.path))) throw new PdfContentError('invalid-request')
  const prepared = await preparePdfContentFromExchange({ createdAt: row.importedAt, documents: [doc], assets: row.assets }, 0, signal)
  current()
  if (row.id !== await contentId(doc)) throw new PdfContentError('invalid-request')
  current()
  if (row.checksum !== await checksum(doc, row.assets)) throw new PdfContentError('invalid-request')
  current()
  return prepared
}

function supportedNote(note: ExchangeDocument['notes'][number], reading: ReadingDocument) {
  if (!note.title.trim() || note.title.length > 200 || note.text.length > 4000 || note.excerpt.length > (note.kind === 'highlight' ? 4000 : 1000) || !validAnchor(reading, note.anchor)) return false
  if (note.kind !== 'highlight') return !note.range
  try { return !!note.range && note.anchor.paragraphId === note.range.start.paragraphId && note.anchor.characterOffset === note.range.start.characterOffset && readingRangeText(reading, note.range) === note.excerpt } catch { return false }
}

export function exchangeReadingDocument(saved: SavedExchange): ReadingDocument {
  const doc = saved.document
  const result: ReadingDocument = { id: saved.id, bookTitle: doc.bookTitle, chapterTitle: doc.chapterTitle, language: doc.language, kind: doc.kind, paragraphs: doc.paragraphs, outline: doc.outline }
  const pdf = doc.assets.find(a => a.role === 'pdf'), pdfAsset = pdf ? saved.assets.find(a => a.path === pdf.path) : undefined
  if (pdfAsset) {
    const flags = doc.extensions?.pdfTextPages, errors = doc.extensions?.pdfTextErrorPages
    const isFlags = (value: unknown): value is boolean[] => Array.isArray(value) && value.length === doc.paragraphs.length && value.every(v => typeof v === 'boolean')
    result.kind = 'local'; result.assets = { pdf: new Blob([Uint8Array.from(pdfAsset.bytes).buffer], { type: 'application/pdf' }) }
    result.local = { format: 'pdf', byteLength: pdfAsset.bytes.length, contentHash: pdfAsset.path.slice(7),
      pdfTextPages: isFlags(flags) ? flags : doc.paragraphs.map(() => false), pdfTextErrorPages: isFlags(errors) ? errors : doc.paragraphs.map(() => true) }
  }
  // Asset-only books use ExchangeAssetReader; there is no text anchor to project.
  const images = doc.paragraphs.length ? doc.assets.filter(a => a.role === 'image').map(ref => { const asset = saved.assets.find(a => a.path === ref.path)!; return { paragraphId: ref.paragraphId ?? doc.paragraphs[0].paragraphId, alt: ref.alt ?? '', blob: new Blob([Uint8Array.from(asset.bytes).buffer], { type: asset.mimeType }) } }) : []
  if (images.length) result.assets = { ...result.assets, images }
  return result
}

export function createExchangeLibrary(username: string, options: DeviceDatabaseOptions = {}) {
  const namespace = readingNamespace(username)
  const get = async (id: string, signal?: AbortSignal): Promise<SavedExchange | undefined> => {
    signal?.throwIfAborted()
    if (typeof id !== 'string' || !/^exchange:[a-f0-9]{64}$/.test(id)) throw sourceChanged()
    const row = await readingTransaction<Row | undefined>(['exchanges'], 'readonly', (tx, done) => {
      const request = tx.objectStore('exchanges').get([namespace, id]); request.onsuccess = () => done(request.result)
    }, options)
    signal?.throwIfAborted()
    const result = row ? await validatedExchangeRow(row, namespace, id) : undefined
    signal?.throwIfAborted()
    return result
  }
  return {
    /** A fresh canonical row, including its exact payloads; caller inventory objects are never trusted. */
    get,
    /** Read canonical metadata and actual payloads together; a cached list/reader object is never an upload source. */
    async preparePdfContent(id: string, signal?: AbortSignal) {
      const current = () => { if (signal?.aborted) throw new PdfContentError('aborted') }
      current()
      if (typeof id !== 'string' || !/^exchange:[a-f0-9]{64}$/.test(id)) throw new PdfContentError('invalid-request')
      const row = await readingTransaction<Row | undefined>(['exchanges'], 'readonly', (tx, done) => {
        const request = tx.objectStore('exchanges').get([namespace, id]); request.onsuccess = () => done(request.result)
      }, options)
      current()
      if (!row) throw new PdfContentError('not-found')
      return validateSavedExchangePdfContent(row, namespace, id, signal)
    },
    async list(): Promise<SavedExchange[]> {
      const rows = await readingTransaction<Row[]>(['exchanges'], 'readonly', (tx, done) => { const r = tx.objectStore('exchanges').index('username').getAll(namespace); r.onsuccess = () => done(r.result) }, options)
      const result: SavedExchange[] = []
      for (const row of rows) {
        result.push(await validatedExchangeRow(row, namespace, row.id))
      }
      return result.sort((a, b) => b.importedAt.localeCompare(a.importedAt))
    },
    async importPackage(input: ExchangePackage): Promise<{ added: number; existing: number }> {
      // Revalidate callers as well as files, before opening the single atomic transaction.
      const verified = await readExchange(await writeExchange(input)), rows: Row[] = []
      for (const document of verified.documents) {
        const assets = verified.assets.filter(asset => document.assets.some(ref => ref.path === asset.path))
        const row: Row = { username: namespace, id: await contentId(document), document, assets, importedAt: new Date().toISOString(), checksum: await checksum(document, assets), integratedNoteIds: [] }
        const reading = exchangeReadingDocument(row)
        row.integratedNoteIds = document.notes.filter(note => supportedNote(note, reading)).map(note => note.id)
        rows.push(row)
      }
      return readingTransaction(['exchanges', 'notes', 'positions'], 'readwrite', (tx, done) => {
        const report = { added: 0, existing: 0 }; done(report)
        for (const row of rows) {
          const books = tx.objectStore('exchanges'), req = books.get([namespace, row.id])
          req.onsuccess = () => {
            if (req.result) { report.existing++; return } else { books.add(row); report.added++ }
            // Preserve existing edited notes and current progress; append only unseen note IDs.
            for (const note of row.document.notes) {
              if (!row.integratedNoteIds.includes(note.id)) continue
              const notes = tx.objectStore('notes'), previous = notes.get([namespace, row.id, note.id])
              previous.onsuccess = () => { if (!previous.result) notes.add({ ...note, username: namespace, documentId: row.id }) }
            }
            if (row.document.position && validReadingPosition(exchangeReadingDocument(row), row.document.position)) {
              const positions = tx.objectStore('positions'), previous = positions.get([namespace, row.id])
              previous.onsuccess = () => { if (!previous.result) positions.add({ username: namespace, documentId: row.id, anchor: row.document.position }) }
            }
          }
        }
      }, options)
    },
    async exportDocument(saved: SavedExchange): Promise<ExchangePackage> {
      const current = await get(saved.id)
      if (!current) throw sourceChanged()
      const reading = exchangeReadingDocument(current), notes = createReadingNotes(namespace, options), snapshot = await notes.list(reading)
      if (snapshot.damagedIds.length) throw new Error('손상된 읽기 기록을 확인한 뒤 내보내 주세요.')
      const { position: _, ...base } = current.document
      const position = await notes.getPosition(reading) ?? current.document.position
      const document: ExchangeDocument = { ...base, ...(position ? { position } : {}), notes: [...base.notes.filter(n => !current.integratedNoteIds.includes(n.id)), ...snapshot.items.map(({ documentId: _, ...note }) => note)] }
      const latest = await get(current.id)
      if (!latest || latest.checksum !== current.checksum || latest.importedAt !== current.importedAt) throw sourceChanged()
      return { createdAt: new Date().toISOString(), documents: [document], assets: current.assets }
    },
  }
}

export type ExchangeExportCapture = { value: ExchangePackage; assertCurrent(): Promise<void>; portableBook?: SavedExchange }
export type ExchangeExportChoice = { key: string; title: string; kind: string; load: () => Promise<ExchangePackage>
  /** Production choices capture current canonical content and can recheck it after asynchronous account reads. */
  capture?: () => Promise<ExchangeExportCapture>
  /** Provenance stays in memory; a ZIP record hint alone never grants account access. */
  glossarySource?: { kind: 'server'; recordId: string } | { kind: 'portable'; book: SavedExchange }
}
/** All candidates are explicit, verified reading content belonging to the selected device account. */
export async function exchangeExportChoices(username: string): Promise<ExchangeExportChoice[]> {
  const personal = createPersonalLibrary(username), offline = createOfflineLibrary(username), notes = createReadingNotes(username), portable = createExchangeLibrary(username)
  try {
    const [local, translations, originals, exchanged] = await Promise.all([createLocalDocuments(username).list(), offline.list(), personal.originals(), portable.list()])
    const prepare = async (reading: ReadingDocument, options: { organization?: ExchangeDocument['organization']; extensions?: Record<string, unknown>; anchor?: import('./offline').ReadingAnchor; glossaryIdentity?: { providerId: string; bookId: string } } = {}): Promise<ExchangePackage> => {
      // A translation reading model carries serverProgress; listing it normally binds/enqueues notes.
      // Export is a passive local read and must not create synchronization authority or an outbox.
      const savedNotes = await notes.list({ ...reading, serverProgress: undefined }); if (savedNotes.damagedIds.length) throw new Error('손상된 읽기 기록을 확인한 뒤 내보내 주세요.')
      const position = await notes.getPosition(reading) ?? options.anchor
      const assets: ExchangeAsset[] = [], refs: ExchangeDocument['assets'] = []
      async function add(blob: Blob, role: 'pdf' | 'image', paragraphId?: string, alt?: string) {
        const content = new Uint8Array(await blob.arrayBuffer()), path = `assets/${await localFileHash(content)}`
        if (!assets.some(asset => asset.path === path)) assets.push({ path, mimeType: blob.type, bytes: content })
        refs.push({ path, role, ...(paragraphId ? { paragraphId } : {}), ...(alt !== undefined ? { alt } : {}) })
      }
      if (reading.assets?.pdf) await add(reading.assets.pdf, 'pdf')
      for (const image of reading.assets?.images ?? []) await add(image.blob, 'image', image.paragraphId, image.alt)
      const glossaryLibrary = options.glossaryIdentity ? createPersonalLibrary(username) : undefined
      const glossary = await (async () => {
        try { return options.glossaryIdentity ? await glossaryLibrary!.getGlossary(options.glossaryIdentity.providerId, options.glossaryIdentity.bookId) : undefined }
        finally { glossaryLibrary?.close() }
      })()
      const extensions = { ...options.extensions, ...(reading.local?.pdfTextPages ? { pdfTextPages: reading.local.pdfTextPages } : {}), ...(reading.local?.pdfTextErrorPages ? { pdfTextErrorPages: reading.local.pdfTextErrorPages } : {}) }
      const document = validateExchangeDocument({ id: reading.id, bookTitle: reading.bookTitle, chapterTitle: reading.chapterTitle, language: reading.language, kind: reading.kind === 'introduction' ? 'original' : reading.kind,
        paragraphs: reading.paragraphs.map(({ paragraphId, text }) => ({ paragraphId, text })), outline: reading.outline ?? [], ...(position ? { position } : {}), notes: savedNotes.items.map(({ documentId: _, ...note }) => note), organization: options.organization ?? { folder: '', tags: [], favorite: false },
        glossary: glossary?.entries.map(g => ({ ...g, caseSensitive: g.caseSensitive ?? false, enabled: g.enabled ?? true })) ?? [], assets: refs, ...(Object.keys(extensions).length ? { extensions } : {}) })
      return { createdAt: new Date().toISOString(), documents: [document], assets }
    }
    type Source = { value: ExchangePackage; token: unknown; portableBook?: SavedExchange }
    const fingerprint = async (source: Source) => localFileHash(bytes([source.token, source.value.documents,
      await Promise.all(source.value.assets.map(async asset => [asset.path, asset.mimeType, await localFileHash(asset.bytes)]))]))
    const choice = (summary: Omit<ExchangeExportChoice, 'load' | 'capture'>, loadSource: () => Promise<Source>): ExchangeExportChoice => ({
      ...summary,
      load: async () => (await loadSource()).value,
      capture: async () => {
        const captured = await loadSource(), expected = await fingerprint(captured)
        return { value: structuredClone(captured.value), ...(captured.portableBook ? { portableBook: structuredClone(captured.portableBook) } : {}),
          assertCurrent: async () => { if (await fingerprint(await loadSource()) !== expected) throw sourceChanged() } }
      },
    })
    return [
      ...local.books.map(book => {
        const id = book.document.id
        return choice({ key: `local:${id}`, title: book.document.bookTitle, kind: book.document.local.format.toUpperCase() }, async () => {
          const latest = (await createLocalDocuments(username).list()).books.find(value => value.document.id === id)
          if (!latest) throw sourceChanged()
          return { value: await prepare(latest.document, { organization: latest.organization }), token: [latest.savedAt, latest.document.local] }
        })
      }),
      ...translations.books.map(book => {
        const recordId = book.translation.recordId
        return choice({ key: `translation:${recordId}`, title: book.translation.bookTitle || book.translation.bookId, kind: 'translation', glossarySource: { kind: 'server', recordId } }, async () => {
          const library = createOfflineLibrary(username)
          try {
            const latest = await library.get(recordId)
            if (!latest) throw sourceChanged()
            const translation = latest.translation
            return { value: await prepare(translationReadingDocument(translation), { anchor: getTranslationPosition(username, translation) ?? latest.anchor,
              extensions: { translation: metadata(translation), ...await identityExtension(recordId, () => translationLibraryIdentity(translation)) },
              glossaryIdentity: { providerId: translation.contentProviderId, bookId: translation.bookId } }), token: [latest.savedAt, translation.revision] }
          } finally { library.close() }
        })
      }),
      ...originals.books.map(book => {
        const recordId = book.chapter.recordId
        return choice({ key: `original:${recordId}`, title: book.chapter.bookTitle, kind: 'original', glossarySource: { kind: 'server', recordId } }, async () => {
          const library = createPersonalLibrary(username)
          try {
            const latest = (await library.originals()).books.find(value => value.chapter.recordId === recordId)
            if (!latest) throw sourceChanged()
            const c = latest.chapter
            const reading: ReadingDocument = { id: `original:${c.recordId}:${c.sourceRevision}`, kind: 'original', bookTitle: c.bookTitle, chapterTitle: c.chapterTitle, language: c.sourceLanguage, paragraphs: c.paragraphs }
            return { value: await prepare(reading, { anchor: getWorkflowPosition(username, reading),
              extensions: { source: metadata(c), ...await identityExtension(c.recordId, () => originalLibraryIdentity(c)) },
              glossaryIdentity: { providerId: c.providerId, bookId: c.bookId } }), token: [latest.savedAt, c.sourceRevision] }
          } finally { library.close() }
        })
      }),
      ...exchanged.map(book => {
        const id = book.id
        return choice({ key: id, title: book.document.bookTitle, kind: 'ZIP', glossarySource: { kind: 'portable', book } }, async () => {
          const current = await portable.get(id)
          if (!current) throw sourceChanged()
          const value = await portable.exportDocument(current), latest = await portable.get(id)
          if (!latest || latest.checksum !== current.checksum || latest.importedAt !== current.importedAt) throw sourceChanged()
          return { value, token: [current.checksum, current.importedAt, current.integratedNoteIds], portableBook: current }
        })
      }),
    ]
  } finally { personal.close(); offline.close() }
}

export async function mergeExchangePackages(packages: ExchangePackage[]): Promise<ExchangePackage> {
  const documents: ExchangeDocument[] = [], assets = new Map<string, ExchangeAsset>()
  for (const value of packages) {
    for (const document of value.documents) { if (documents.some(d => d.id === document.id)) throw new Error('같은 문서가 두 번 선택되었습니다. 원본 항목 하나만 선택해 주세요.'); documents.push(document) }
    for (const asset of value.assets) assets.set(asset.path, asset)
  }
  return { createdAt: new Date().toISOString(), documents, assets: [...assets.values()] }
}

export function exchangePdfDocument(saved: SavedExchange): LocalDocument | undefined { const doc = exchangeReadingDocument(saved); return doc.local?.format === 'pdf' ? doc as LocalDocument : undefined }
