import type { ReadingDocument } from './readingDocument'
import { localSha256 } from './localSha256'

export type DocumentExportFormat = 'txt' | 'markdown' | 'pdf'
export type DocumentFileExport = { blob: Blob; name: string; type: string }

const maximumInputUnits = 5_000_000
const maximumParagraphs = 100_000
const maximumOutputUnits = 12_000_000
const maximumPdfBytes = 32 * 1024 * 1024
const invalid = (): never => { throw new Error('문서 파일을 내보낼 수 없습니다. 원본 내용과 파일 형식을 확인해 주세요.') }
const tooLarge = (): never => { throw new Error('문서가 파일 내보내기 크기 제한을 초과합니다. 내용을 나누어 주세요.') }
const trimTitle = (value: string) => value.replace(/^[ \t\r\n]+|[ \t\r\n]+$/g, '')

function checkedText(value: string): string {
  if (typeof value !== 'string') return invalid()
  for (let index = 0; index < value.length; index++) {
    const unit = value.charCodeAt(index)
    if ((unit < 0x20 && unit !== 9 && unit !== 10 && unit !== 13) || (unit >= 0x7f && unit <= 0x9f)) return invalid()
    if (unit >= 0xd800 && unit <= 0xdbff) {
      const next = value.charCodeAt(++index)
      if (!(next >= 0xdc00 && next <= 0xdfff)) return invalid()
    } else if (unit >= 0xdc00 && unit <= 0xdfff) return invalid()
  }
  return value
}

function titles(bookTitle: string, chapterTitle: string) {
  if (typeof bookTitle !== 'string' || typeof chapterTitle !== 'string') return invalid()
  if (bookTitle.length + chapterTitle.length > maximumInputUnits) return tooLarge()
  const book = trimTitle(checkedText(bookTitle)) || 'Untitled'
  const normalizedChapter = trimTitle(checkedText(chapterTitle))
  return { book, chapter: normalizedChapter === book ? '' : normalizedChapter }
}

function filename(book: string, chapter: string, extension: 'txt' | 'md' | 'pdf') {
  const edgeTrim = (value: string) => value.replace(/^[ .]+|[ .]+$/g, '')
  const bounded = (value: string) => {
    let end = Math.min(value.length, 120)
    if (end < value.length && /[\uD800-\uDBFF]/.test(value[end - 1])) end--
    return edgeTrim(value.slice(0, end))
  }
  let stem = bounded(edgeTrim((book + (chapter ? ` - ${chapter}` : '')).replace(/[<>:"/\\|?*\u0000-\u001f\u007f-\u009f\u061c\u200e\u200f\u202a-\u202e\u2066-\u2069]/g, '_'))) || 'document'
  const first = stem.split('.')[0].replace(/ +$/g, '').replace(/[a-z]/g, value => value.toUpperCase())
  if (/^(CON|PRN|AUX|NUL|CONIN\$|CONOUT\$|COM[1-9¹²³]|LPT[1-9¹²³])$/.test(first)) stem = bounded(`_${stem}`)
  return `${stem}.${extension}`
}

function isPdf(document: ReadingDocument) {
  return document.local?.format === 'pdf' || document.assets?.pdf !== undefined
}

/** Available choices never offer extracted PDF text as a complete TXT/Markdown document. */
export function documentExportFormats(document: ReadingDocument): DocumentExportFormat[] {
  if (!isPdf(document)) return ['txt', 'markdown']
  const blob = document.assets?.pdf, metadata = document.local
  return blob instanceof Blob && blob.size > 0 && blob.size <= maximumPdfBytes && metadata?.format === 'pdf' &&
    Number.isSafeInteger(metadata.byteLength) && metadata.byteLength === blob.size && /^[a-f0-9]{64}$/.test(metadata.contentHash) ? ['pdf'] : []
}

/** Exports one fixed local snapshot; never fetches, binds or sends account records. */
export async function prepareDocumentFileExport(document: ReadingDocument, format: DocumentExportFormat, signal?: AbortSignal): Promise<DocumentFileExport> {
  signal?.throwIfAborted()
  const { book, chapter } = titles(document.bookTitle, document.chapterTitle)
  if (format === 'pdf') {
    const blob = document.assets?.pdf, metadata = document.local ? { ...document.local } : undefined
    if (!(blob instanceof Blob) || metadata?.format !== 'pdf' || !blob.size || !Number.isSafeInteger(metadata.byteLength) || metadata.byteLength !== blob.size || !/^[a-f0-9]{64}$/.test(metadata.contentHash)) return invalid()
    if (blob.size > maximumPdfBytes) return tooLarge()
    const input = new Uint8Array(await blob.arrayBuffer()); signal?.throwIfAborted()
    if (input.length !== metadata.byteLength || input.length > maximumPdfBytes) return invalid()
    const bytes = Uint8Array.from(input)
    const digest = await localSha256(bytes); signal?.throwIfAborted()
    if (digest !== metadata.contentHash) return invalid()
    return { blob: new Blob([bytes], { type: 'application/pdf' }), name: filename(book, chapter, 'pdf'), type: 'application/pdf' }
  }
  if ((format !== 'txt' && format !== 'markdown') || isPdf(document) || !Array.isArray(document.paragraphs)) return invalid()
  if (document.paragraphs.length > maximumParagraphs) return tooLarge()
  let units = document.bookTitle.length + document.chapterTitle.length
  const paragraphs = document.paragraphs.map(paragraph => {
    if (!paragraph || typeof paragraph.text !== 'string') return invalid()
    units += paragraph.text.length
    if (units > maximumInputUnits) return tooLarge()
    return checkedText(paragraph.text)
  })
  const escape = (value: string) => value.replace(/[!-/:-@[-`{-~]/g, '\\$&')
  const header = format === 'txt' ? book + (chapter ? `\n${chapter}` : '') : `# ${escape(book)}` + (chapter ? `\n\n## ${escape(chapter)}` : '')
  const text = header + '\n\n' + paragraphs.map(value => format === 'markdown' ? escape(value) : value).join('\n\n') + '\n'
  if (text.length > maximumOutputUnits) return tooLarge()
  signal?.throwIfAborted()
  const type = format === 'txt' ? 'text/plain' : 'text/markdown'
  // Explicit bytes avoid platform newline conversion and always use UTF-8 without a BOM.
  return { blob: new Blob([new TextEncoder().encode(text)], { type }), name: filename(book, chapter, format === 'txt' ? 'txt' : 'md'), type }
}

/** Invoke only after the UI confirms its selection/session is still current. */
export function downloadDocumentFileExport(value: DocumentFileExport): void {
  const url = URL.createObjectURL(value.blob)
  let link: HTMLAnchorElement | undefined
  let clicked = false
  try {
    link = document.createElement('a')
    link.href = url; link.download = value.name; link.hidden = true
    document.body.appendChild(link); link.click(); clicked = true
  } finally {
    link?.remove()
    if (clicked) setTimeout(() => URL.revokeObjectURL(url), 1000)
    else URL.revokeObjectURL(url)
  }
}
