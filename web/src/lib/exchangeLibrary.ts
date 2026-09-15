import { readingTransaction, readingNamespace, type DeviceDatabaseOptions } from './deviceReadingDatabase'
import { validateExchangeDocument, readExchange, writeExchange, type ExchangePackage, type ExchangeDocument, type ExchangeAsset } from './libraryExchange'
import { localFileHash, createLocalDocuments, type LocalDocument } from './localDocuments'
import { createReadingNotes } from './readingNotes'
import { createOfflineLibrary } from './offline'
import { createPersonalLibrary } from './personalLibrary'
import { translationReadingDocument, getTranslationPosition } from './translationReading'
import { getWorkflowPosition } from './workflowPosition'
import type { ReadingDocument } from './readingDocument'
import { validAnchor } from './readingDocument'
import { readingRangeText } from './readingSelection'

export type SavedExchange = { id: string; document: ExchangeDocument; assets: ExchangeAsset[]; importedAt: string; checksum: string; integratedNoteIds: string[] }
type Row = SavedExchange & { username: string }
const bytes = (value: unknown) => new TextEncoder().encode(JSON.stringify(value))
const metadata = <T extends { paragraphs: unknown }>(value: T) => { const { paragraphs: _, ...rest } = value; return rest }
async function contentId(doc: ExchangeDocument) { return `exchange:${await localFileHash(bytes(doc))}` }
async function checksum(doc: ExchangeDocument, assets: ExchangeAsset[]) { return localFileHash(bytes([doc, await Promise.all(assets.map(async a => [a.path, a.mimeType, await localFileHash(a.bytes)]))])) }

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
  return {
    async list(): Promise<SavedExchange[]> {
      const rows = await readingTransaction<Row[]>(['exchanges'], 'readonly', (tx, done) => { const r = tx.objectStore('exchanges').index('username').getAll(namespace); r.onsuccess = () => done(r.result) }, options)
      const result: SavedExchange[] = []
      for (const row of rows) {
        const doc = validateExchangeDocument(row.document)
        if (row.id !== await contentId(doc) || row.checksum !== await checksum(doc, row.assets)) throw new Error('가져온 ZIP 문서가 손상되었습니다. 원본 ZIP을 확인해 주세요.')
        result.push({ ...row, document: doc })
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
            if (row.document.position && validAnchor(exchangeReadingDocument(row), row.document.position)) {
              const positions = tx.objectStore('positions'), previous = positions.get([namespace, row.id])
              previous.onsuccess = () => { if (!previous.result) positions.add({ username: namespace, documentId: row.id, anchor: row.document.position }) }
            }
          }
        }
      }, options)
    },
    async exportDocument(saved: SavedExchange): Promise<ExchangePackage> {
      const reading = exchangeReadingDocument(saved), notes = createReadingNotes(namespace, options), snapshot = await notes.list(reading)
      if (snapshot.damagedIds.length) throw new Error('손상된 읽기 기록을 확인한 뒤 내보내 주세요.')
      const { position: _, ...base } = saved.document
      const position = await notes.getPosition(reading) ?? saved.document.position
      const document: ExchangeDocument = { ...base, ...(position ? { position } : {}), notes: [...base.notes.filter(n => !saved.integratedNoteIds.includes(n.id)), ...snapshot.items.map(({ documentId: _, ...note }) => note)] }
      return { createdAt: new Date().toISOString(), documents: [document], assets: saved.assets }
    },
  }
}

export type ExchangeExportChoice = { key: string; title: string; kind: string; load: () => Promise<ExchangePackage> }
/** All candidates are explicit, verified reading content belonging to the selected device account. */
export async function exchangeExportChoices(username: string): Promise<ExchangeExportChoice[]> {
  const personal = createPersonalLibrary(username), offline = createOfflineLibrary(username), notes = createReadingNotes(username), portable = createExchangeLibrary(username)
  try {
    const [local, translations, originals, exchanged] = await Promise.all([createLocalDocuments(username).list(), offline.list(), personal.originals(), portable.list()])
    const prepare = async (reading: ReadingDocument, options: { organization?: ExchangeDocument['organization']; extensions?: Record<string, unknown>; anchor?: import('./offline').ReadingAnchor; glossaryIdentity?: { providerId: string; bookId: string } } = {}): Promise<ExchangePackage> => {
      const savedNotes = await notes.list(reading); if (savedNotes.damagedIds.length) throw new Error('손상된 읽기 기록을 확인한 뒤 내보내 주세요.')
      const position = await notes.getPosition(reading) ?? options.anchor
      const assets: ExchangeAsset[] = [], refs: ExchangeDocument['assets'] = []
      async function add(blob: Blob, role: 'pdf' | 'image', paragraphId?: string, alt?: string) {
        const content = new Uint8Array(await blob.arrayBuffer()), path = `assets/${await localFileHash(content)}`
        if (!assets.some(asset => asset.path === path)) assets.push({ path, mimeType: blob.type, bytes: content })
        refs.push({ path, role, ...(paragraphId ? { paragraphId } : {}), ...(alt !== undefined ? { alt } : {}) })
      }
      if (reading.assets?.pdf) await add(reading.assets.pdf, 'pdf')
      for (const image of reading.assets?.images ?? []) await add(image.blob, 'image', image.paragraphId, image.alt)
      const glossary = options.glossaryIdentity ? await createPersonalLibrary(username).getGlossary(options.glossaryIdentity.providerId, options.glossaryIdentity.bookId) : undefined
      const extensions = { ...options.extensions, ...(reading.local?.pdfTextPages ? { pdfTextPages: reading.local.pdfTextPages } : {}), ...(reading.local?.pdfTextErrorPages ? { pdfTextErrorPages: reading.local.pdfTextErrorPages } : {}) }
      const document = validateExchangeDocument({ id: reading.id, bookTitle: reading.bookTitle, chapterTitle: reading.chapterTitle, language: reading.language, kind: reading.kind === 'introduction' ? 'original' : reading.kind,
        paragraphs: reading.paragraphs, outline: reading.outline ?? [], ...(position ? { position } : {}), notes: savedNotes.items.map(({ documentId: _, ...note }) => note), organization: options.organization ?? { folder: '', tags: [], favorite: false },
        glossary: glossary?.entries.map(g => ({ ...g, caseSensitive: g.caseSensitive ?? false, enabled: g.enabled ?? true })) ?? [], assets: refs, ...(Object.keys(extensions).length ? { extensions } : {}) })
      return { createdAt: new Date().toISOString(), documents: [document], assets }
    }
    return [
      ...local.books.map(book => ({ key: `local:${book.document.id}`, title: book.document.bookTitle, kind: book.document.local.format.toUpperCase(), load: () => prepare(book.document, { organization: book.organization }) })),
      ...translations.books.map(book => ({ key: `translation:${book.translation.recordId}`, title: book.translation.bookTitle || book.translation.bookId, kind: 'translation', load: () => prepare(translationReadingDocument(book.translation), { anchor: getTranslationPosition(username, book.translation) ?? book.anchor, extensions: { translation: metadata(book.translation) }, glossaryIdentity: { providerId: book.translation.contentProviderId, bookId: book.translation.bookId } }) })),
      ...originals.books.map(book => { const c = book.chapter; const reading: ReadingDocument = { id: `original:${c.recordId}:${c.sourceRevision}`, kind: 'original', bookTitle: c.bookTitle, chapterTitle: c.chapterTitle, language: c.sourceLanguage, paragraphs: c.paragraphs }; return { key: `original:${c.recordId}`, title: c.bookTitle, kind: 'original', load: () => prepare(reading, { anchor: getWorkflowPosition(username, reading), extensions: { source: metadata(c) }, glossaryIdentity: { providerId: c.providerId, bookId: c.bookId } }) } }),
      ...exchanged.map(book => ({ key: book.id, title: book.document.bookTitle, kind: 'ZIP', load: () => portable.exportDocument(book) })),
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
