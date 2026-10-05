import { zipSync, type Zippable } from 'fflate'
import type { ReadingDocument } from './readingDocument'
import { checkedDocumentExportText, documentExportFilename, documentTextExportSnapshot, type DocumentFileExport } from './documentFileExport'
import { localSha256 } from './localSha256'

type TextSnapshot = ReturnType<typeof documentTextExportSnapshot>
const mimeType = 'application/epub+zip'
const maximumArchiveBytes = 32 * 1024 * 1024
const escaped: Record<string, string> = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&apos;', '\r': '&#xD;' }
const languagePattern = /^[A-Za-z]{2,8}(?:-[A-Za-z]{4})?(?:-(?:[A-Za-z]{2}|[0-9]{3}))?(?:-(?:[A-Za-z0-9]{5,8}|[0-9][A-Za-z0-9]{3}))*$/
const tooLarge = (): never => { throw new Error('문서가 파일 내보내기 크기 제한을 초과합니다. 내용을 나누어 주세요.') }

/** A new prose EPUB, not restoration of source EPUB layout, assets or portable identity. */
export async function prepareDocumentEbookExport(document: ReadingDocument, signal?: AbortSignal): Promise<DocumentFileExport> {
  signal?.throwIfAborted()
  return prepareDocumentEbookSnapshot(documentTextExportSnapshot(document), document.language, signal)
}

/** Captured by documentFileExport before its lazy import, so late edits cannot change the publication. */
export async function prepareDocumentEbookSnapshot(snapshot: TextSnapshot, rawLanguage: string | null | undefined, signal?: AbortSignal): Promise<DocumentFileExport> {
  signal?.throwIfAborted()
  const inputLanguage = rawLanguage ?? ''
  if (snapshot.inputUnits + inputLanguage.length > 5_000_000) return tooLarge()
  checkedDocumentExportText(inputLanguage)
  const { book, chapter, paragraphs } = snapshot
  for (const text of [book, chapter, inputLanguage, ...paragraphs]) {
    if (/[\uFFFE\uFFFF]/.test(text)) throw new Error('문서 파일을 내보낼 수 없습니다. 원본 내용과 파일 형식을 확인해 주세요.')
  }
  const normalized = inputLanguage.replace(/^[ \t\r\n]+|[ \t\r\n]+$/g, '')
  const language = normalized.length <= 128 && !/^(auto|auto-detect)$/i.test(normalized) && languagePattern.test(normalized) ? normalized.toLowerCase() : 'und'
  const displayTitle = book + (chapter ? ` - ${chapter}` : '')
  let used = 0, totalBytes = 0
  const utf8Length = (text: string) => {
    let bytes = 0
    for (let index = 0; index < text.length; index++) {
      const code = text.charCodeAt(index)
      if (code < 0x80) bytes++
      else if (code < 0x800) bytes += 2
      else if (code >= 0xd800 && code <= 0xdbff) { bytes += 4; index++ }
      else bytes += 3
    }
    return bytes
  }
  const entry = (path: string, build: (raw: (text: string) => void, xml: (text: string) => void) => void) => {
    const parts: string[] = []; let entryBytes = 0
    const reserve = (units: number, bytes: number) => {
      if (used + units > 32_000_000 || entryBytes + bytes > 8 * 1024 * 1024 || totalBytes + bytes > 32 * 1024 * 1024) return tooLarge()
      used += units; entryBytes += bytes; totalBytes += bytes
    }
    const raw = (text: string) => { reserve(text.length, utf8Length(text)); parts.push(text) }
    const xml = (text: string) => {
      let units = 0, bytes = 0
      for (let index = 0; index < text.length; index++) {
        const replacement = escaped[text[index]], code = text.charCodeAt(index)
        if (replacement) { units += replacement.length; bytes += replacement.length }
        else if (code >= 0xd800 && code <= 0xdbff) { units += 2; bytes += 4; index++ }
        else { units++; bytes += code < 0x80 ? 1 : code < 0x800 ? 2 : 3 }
      }
      reserve(units, bytes); parts.push(text.replace(/[&<>"'\r]/g, character => escaped[character]))
    }
    build(raw, xml)
    return { path, text: parts.join('') }
  }
  const mimetype = entry('mimetype', raw => raw(mimeType))
  const container = entry('META-INF/container.xml', raw => raw('<?xml version="1.0" encoding="UTF-8"?>\n<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">\n  <rootfiles>\n    <rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml" />\n  </rootfiles>\n</container>\n'))
  const content = entry('EPUB/content.xhtml', (raw, xml) => {
    raw(`<?xml version="1.0" encoding="UTF-8"?>\n<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="${language}" lang="${language}">\n<head>\n  <title>`); xml(displayTitle)
    raw('</title>\n  <link rel="stylesheet" type="text/css" href="styles.css" />\n</head>\n<body>\n  <section id="document">\n    <h1>'); xml(book); raw('</h1>\n')
    if (chapter) { raw('    <h2>'); xml(chapter); raw('</h2>\n') }
    for (const paragraph of paragraphs) { raw('    <p>'); xml(paragraph); raw('</p>\n') }
    raw('  </section>\n</body>\n</html>\n')
  })
  const identifier = `urn:sha256:${await localSha256(new TextEncoder().encode(content.text))}`
  signal?.throwIfAborted()
  const opf = entry('EPUB/package.opf', (raw, xml) => {
    raw('<?xml version="1.0" encoding="UTF-8"?>\n<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="publication-id">\n  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">\n')
    raw(`    <dc:identifier id="publication-id">${identifier}</dc:identifier>\n    <dc:title>`); xml(displayTitle)
    raw(`</dc:title>\n    <dc:language>${language}</dc:language>\n    <meta property="dcterms:modified">2000-01-01T00:00:00Z</meta>\n  </metadata>\n`)
    raw('  <manifest>\n    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav" />\n    <item id="content" href="content.xhtml" media-type="application/xhtml+xml" />\n    <item id="styles" href="styles.css" media-type="text/css" />\n  </manifest>\n  <spine>\n    <itemref idref="content" />\n  </spine>\n</package>\n')
  })
  const nav = entry('EPUB/nav.xhtml', (raw, xml) => {
    raw(`<?xml version="1.0" encoding="UTF-8"?>\n<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="${language}" lang="${language}">\n<head>\n  <title>`); xml(displayTitle)
    raw('</title>\n</head>\n<body>\n  <nav epub:type="toc" id="toc">\n    <ol>\n      <li><a href="content.xhtml#document">'); xml(chapter || book)
    raw('</a></li>\n    </ol>\n  </nav>\n</body>\n</html>\n')
  })
  const styles = entry('EPUB/styles.css', raw => raw('body { font-family: serif; line-height: 1.5; }\nh1, h2, p { white-space: pre-wrap; overflow-wrap: anywhere; }\np { margin: 1em 0; min-height: 1em; }\n'))
  const files: Zippable = {}
  for (const value of [mimetype, container, opf, nav, content, styles]) {
    files[value.path] = [new TextEncoder().encode(value.text), { level: value.path === 'mimetype' ? 0 : 6, mtime: new Date('2000-01-01T00:00:00Z') }]
  }
  signal?.throwIfAborted()
  const bytes = zipSync(files)
  if (bytes.length > maximumArchiveBytes) return tooLarge()
  signal?.throwIfAborted()
  return { blob: new Blob([Uint8Array.from(bytes).buffer], { type: mimeType }), name: documentExportFilename(book, chapter, 'epub'), type: mimeType }
}
