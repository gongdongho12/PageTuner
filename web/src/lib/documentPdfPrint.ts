import type { ReadingDocument } from './readingDocument'
import { checkedDocumentExportText, documentExportFilename, documentTextExportSnapshot } from './documentFileExport'

export type PreparedDocumentPdfPrint = Readonly<{ html: string; title: string; name: string }>
export type DocumentPdfPrintControl = Readonly<{ close(): void; ready: Promise<void> }>
const escape = (value: string) => value.replace(/[&<>"'\r]/g, character => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&apos;', '\r': '&#xD;' })[character]!)
const validLanguage = /^[A-Za-z]{2,8}(?:-[A-Za-z]{4})?(?:-(?:[A-Za-z]{2}|[0-9]{3}))?(?:-(?:[A-Za-z0-9]{5,8}|[0-9][A-Za-z0-9]{3}))*$/

/** A separate full-document print layout, never the currently visible reader page. */
export function prepareDocumentPdfPrint(document: ReadingDocument): PreparedDocumentPdfPrint {
  const snapshot = documentTextExportSnapshot(document)
  const rawLanguage = document.language ?? ''
  if (snapshot.inputUnits + rawLanguage.length > 5_000_000) throw new Error('문서가 파일 내보내기 크기 제한을 초과합니다. 내용을 나누어 주세요.')
  checkedDocumentExportText(rawLanguage)
  const normalized = rawLanguage.replace(/^[ \t\r\n]+|[ \t\r\n]+$/g, '')
  const language = normalized.length <= 128 && !/^(auto|auto-detect)$/i.test(normalized) && validLanguage.test(normalized) ? normalized.toLowerCase() : 'und'
  const { book, chapter, paragraphs } = snapshot
  if ([book, chapter, rawLanguage, ...paragraphs].some(value => /[\uFFFE\uFFFF]/.test(value))) throw new Error('문서 파일을 내보낼 수 없습니다. 원본 내용과 파일 형식을 확인해 주세요.')
  const name = documentExportFilename(book, chapter, 'pdf')
  const html = `<!doctype html>\n<html lang="${language}">\n<head>\n<meta charset="utf-8">\n<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'">\n<title>${escape(name.slice(0, -4))}</title>\n<style>\n@page { size: A4; margin: 18mm; }\nhtml { color: #000; background: #fff; font-family: "Noto Serif CJK KR", "Malgun Gothic", "Apple SD Gothic Neo", "Yu Mincho", "SimSun", serif; font-size: 11pt; line-height: 1.65; }\nbody { margin: 0; }\nh1, h2, p { white-space: pre-wrap; overflow-wrap: anywhere; word-break: normal; }\nh1 { font-size: 20pt; margin: 0 0 0.8em; }\nh2 { font-size: 15pt; margin: 0 0 1em; }\np { margin: 0 0 0.8em; min-height: 1em; orphans: 2; widows: 2; break-inside: auto; }\n</style>\n</head>\n<body>\n<h1>${escape(book)}</h1>\n${chapter ? `<h2>${escape(chapter)}</h2>\n` : ''}${paragraphs.map(text => `<p>${escape(text)}</p>\n`).join('')}</body>\n</html>\n`
  if (html.length > 32_000_000) throw new Error('문서가 파일 내보내기 크기 제한을 초과합니다. 내용을 나누어 주세요.')
  return Object.freeze({ html, title: chapter ? `${book} - ${chapter}` : book, name })
}

/** `ready` means print was requested, never that a PDF was saved. Close on UI unmount/cancel. */
export function openDocumentPdfPrint(prepared: PreparedDocumentPdfPrint): DocumentPdfPrintControl {
  const frame = document.createElement('iframe')
  let closed = false, settled = false, loaded = false, printingWindow: Window | null = null
  let resolveReady!: () => void, rejectReady!: (error: unknown) => void
  const ready = new Promise<void>((resolve, reject) => { resolveReady = resolve; rejectReady = reject })
  // An immediate UI unmount must not produce an unhandled rejection while its handler is disposing.
  void ready.catch(() => undefined)
  const resolve = () => { if (!settled) { settled = true; resolveReady() } }
  const cleanup = () => {
    if (closed) return
    closed = true; clearTimeout(timeout)
    frame.removeEventListener('load', onLoad); frame.removeEventListener('error', onError)
    printingWindow?.removeEventListener('afterprint', onAfterPrint)
    window.removeEventListener('afterprint', onAfterPrint)
    frame.remove()
  }
  const fail = (error: unknown) => { if (!settled) { settled = true; rejectReady(error) }; cleanup() }
  const close = () => fail(new DOMException('Print preparation cancelled', 'AbortError'))
  const onAfterPrint = () => { if (closed) return; resolve(); cleanup() }
  const onError = () => fail(new Error('인쇄용 문서를 열지 못했습니다. 다시 시도해 주세요.'))
  const onLoad = () => {
    if (closed || loaded) return
    loaded = true
    void (async () => {
      try {
        printingWindow = frame.contentWindow
        if (!printingWindow || !frame.contentDocument) throw new Error('인쇄용 문서를 열지 못했습니다. 다시 시도해 주세요.')
        await frame.contentDocument.fonts?.ready
        if (closed) return
        clearTimeout(timeout)
        printingWindow.addEventListener('afterprint', onAfterPrint)
        window.addEventListener('afterprint', onAfterPrint)
        printingWindow.focus()
        printingWindow.print()
        resolve()
      } catch (error) { fail(error) }
    })()
  }
  const timeout = setTimeout(() => fail(new Error('인쇄용 문서를 여는 시간이 초과되었습니다. 다시 시도해 주세요.')), 30_000)
  try {
    // Kept outside the visible page; display:none/visibility:hidden can make native print blank.
    frame.style.cssText = 'position:fixed;left:-10000px;top:0;width:210mm;height:297mm;border:0;'
    frame.title = prepared.title; frame.tabIndex = -1; frame.setAttribute('aria-hidden', 'true')
    frame.setAttribute('sandbox', 'allow-modals allow-same-origin')
    frame.addEventListener('load', onLoad); frame.addEventListener('error', onError)
    frame.srcdoc = prepared.html
    document.body.appendChild(frame)
  } catch (error) { fail(error) }
  return Object.freeze({ ready, close })
}
