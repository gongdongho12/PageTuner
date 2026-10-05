import { afterEach, describe, expect, it, vi } from 'vitest'
import { openDocumentPdfPrint, prepareDocumentPdfPrint } from './documentPdfPrint'
import type { ReadingDocument } from './readingDocument'

function prose(texts = ['  첫 문단\r\n둘째 줄\t ', '', 'Last paragraph 尾😀']): ReadingDocument {
  return { id: 'private-source-id', kind: 'original', bookTitle: ' 책 <script> ', chapterTitle: ' 제1장 & "끝" ', language: ' ko-KR ',
    paragraphs: texts.map((text, index) => ({ paragraphId: `private-paragraph-${index}`, text })) }
}
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.useRealTimers() })

describe('full-document PDF print preparation', () => {
  it('escapes every text field and prepares complete ordered body without account data or remote resources', () => {
    const document = { ...prose(['<img src="https://evil.invalid/x" onerror="alert(1)"> & \'', '  kept\r\nline\t ', '', '마지막😀']),
      serverProgress: { kind: 'ORIGINAL' as const, recordId: 'private-server-id' }, credentials: 'private-password', notes: ['private-note'], outbox: ['private-mutation'] }
    const before = structuredClone(document), prepared = prepareDocumentPdfPrint(document)
    expect(Object.isFrozen(prepared)).toBe(true)
    expect(prepared.html).toContain('<h1>책 &lt;script&gt;</h1>')
    expect(prepared.html).toContain('<h2>제1장 &amp; &quot;끝&quot;</h2>')
    expect(prepared.html).toContain('<p>&lt;img src=&quot;https://evil.invalid/x&quot; onerror=&quot;alert(1)&quot;&gt; &amp; &apos;</p>')
    expect(prepared.html).toContain('<p>  kept&#xD;\nline\t </p>\n<p></p>\n<p>마지막😀</p>')
    expect(prepared.html).not.toMatch(/<(script|img|iframe|link)\b|private-(source|paragraph|server|password|note|mutation)/)
    expect(prepared.html).toContain("default-src 'none'; style-src 'unsafe-inline'")
    expect(prepared.html).toContain('<html lang="ko-kr">')
    expect(prepared.html).toContain('size: A4; margin: 18mm')
    expect(prepared.html).toContain('white-space: pre-wrap; overflow-wrap: anywhere')
    expect(prepared.html).toContain('Malgun Gothic')
    expect(prepared.html).not.toMatch(/overflow:\s*hidden|max-height:|max-width:|url\(/)
    expect(prepared.name.endsWith('.pdf')).toBe(true)
    expect(document).toEqual(before)
  })
  it('includes the tail of a long document and freezes the prepared snapshot independently of later edits', () => {
    const texts = Array.from({ length: 2_000 }, (_, index) => `문단 ${index} ${'긴 본문 '.repeat(60)} ${index === 1999 ? 'EXACT-TAIL' : ''}`)
    const document = prose(texts), prepared = prepareDocumentPdfPrint(document)
    document.paragraphs[1999].text = 'Changed after preparation'
    expect(prepared.html.match(/<p>/g)).toHaveLength(2_000)
    expect(prepared.html).toContain('EXACT-TAIL</p>')
    expect(prepared.html).not.toContain('Changed after preparation')
  })
  it('rejects partial PDF extraction, bad Unicode/control data and oversize text, with a safe language fallback', () => {
    expect(() => prepareDocumentPdfPrint({ ...prose(), assets: { pdf: new Blob(['source']) } })).toThrow()
    for (const text of ['\ud800', '\u0001', '\uffff']) expect(() => prepareDocumentPdfPrint(prose([text]))).toThrow()
    expect(() => prepareDocumentPdfPrint(prose(['x'.repeat(5_000_001)]))).toThrow('크기 제한')
    const document = { ...prose(), language: '" onclick="alert(1)' }
    expect(prepareDocumentPdfPrint(document).html).toContain('<html lang="und">')
  })
})

function printEnvironment(fontReady = Promise.resolve()) {
  const printingWindow = Object.assign(new EventTarget(), { focus: vi.fn(), print: vi.fn() })
  const parentWindow = new EventTarget(), appendChild = vi.fn()
  const frame = Object.assign(new EventTarget(), {
    style: { cssText: '' }, title: '', tabIndex: 0, srcdoc: '',
    setAttribute: vi.fn(), remove: vi.fn(), contentWindow: printingWindow,
    contentDocument: { fonts: { ready: fontReady } },
  })
  vi.stubGlobal('window', parentWindow)
  vi.stubGlobal('document', { createElement: vi.fn(() => frame), body: { appendChild } })
  return { printingWindow, parentWindow, frame, appendChild }
}

describe('user-triggered print frame lifetime', () => {
  it('creates a sandboxed frame synchronously, waits for load, and removes it after native print finishes', async () => {
    const environment = printEnvironment(), prepared = prepareDocumentPdfPrint(prose())
    const job = openDocumentPdfPrint(prepared)
    expect(environment.appendChild).toHaveBeenCalledWith(environment.frame)
    expect(environment.frame.srcdoc).toBe(prepared.html)
    expect(environment.frame.setAttribute).toHaveBeenCalledWith('sandbox', 'allow-modals allow-same-origin')
    expect(environment.frame.style.cssText).not.toMatch(/display:none|visibility:hidden/)
    expect(environment.printingWindow.print).not.toHaveBeenCalled()
    environment.frame.dispatchEvent(new Event('load'))
    await job.ready
    expect(environment.printingWindow.print).toHaveBeenCalledTimes(1)
    expect(environment.frame.remove).not.toHaveBeenCalled()
    environment.frame.dispatchEvent(new Event('load'))
    expect(environment.printingWindow.print).toHaveBeenCalledTimes(1)
    environment.printingWindow.dispatchEvent(new Event('afterprint'))
    expect(environment.frame.remove).toHaveBeenCalledTimes(1)
    job.close(); expect(environment.frame.remove).toHaveBeenCalledTimes(1)
  })
  it('cancels before a late load or font completion, without showing a stale print dialog', async () => {
    let finishFonts!: () => void
    const environment = printEnvironment(new Promise<void>(resolve => { finishFonts = resolve }))
    const job = openDocumentPdfPrint(prepareDocumentPdfPrint(prose()))
    environment.frame.dispatchEvent(new Event('load')); job.close(); finishFonts()
    await expect(job.ready).rejects.toMatchObject({ name: 'AbortError' })
    await Promise.resolve()
    environment.frame.dispatchEvent(new Event('load'))
    expect(environment.printingWindow.print).not.toHaveBeenCalled()
    expect(environment.frame.remove).toHaveBeenCalledTimes(1)
  })
  it('cleans a load failure, timeout and synchronous print refusal without claiming a saved file', async () => {
    vi.useFakeTimers()
    const environment = printEnvironment(), prepared = prepareDocumentPdfPrint(prose())
    const failed = openDocumentPdfPrint(prepared)
    environment.frame.dispatchEvent(new Event('error'))
    await expect(failed.ready).rejects.toThrow('열지 못했습니다')
    expect(environment.frame.remove).toHaveBeenCalledTimes(1)
    const timed = openDocumentPdfPrint(prepared)
    vi.advanceTimersByTime(30_001)
    await expect(timed.ready).rejects.toThrow('초과')
    environment.printingWindow.print.mockImplementation(() => { throw new Error('Print blocked') })
    const blocked = openDocumentPdfPrint(prepared)
    environment.frame.dispatchEvent(new Event('load'))
    await expect(blocked.ready).rejects.toThrow('Print blocked')
    expect(environment.frame.remove).toHaveBeenCalledTimes(3)
  })
  it('handles browsers that fire afterprint synchronously before print returns', async () => {
    const environment = printEnvironment()
    environment.printingWindow.print.mockImplementation(() => { environment.printingWindow.dispatchEvent(new Event('afterprint')) })
    const job = openDocumentPdfPrint(prepareDocumentPdfPrint(prose()))
    environment.frame.dispatchEvent(new Event('load'))
    await expect(job.ready).resolves.toBeUndefined()
    expect(environment.frame.remove).toHaveBeenCalledTimes(1)
  })
})
