import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import type { RenderTask } from 'pdfjs-dist'
import { translate as t } from '../lib/locale'
import { openPdfReadingDocument, type PdfReadingDocument } from '../lib/pdfReadingDocument'
import type { ReadingDocument } from '../lib/readingDocument'
import type { ReadingAnchor } from '../lib/offline'
import { ReaderTools } from './ReaderTools'
import { usePageKeys } from './usePageKeys'
import { useReaderPreferences } from './ReaderPreferences'
import { useReaderFullscreen, useReaderTouch } from './useReaderControls'
import './readingTools.css'

export function LocalPdfReader({ document, namespace, anchor, onClose, onAnchorChange, onTranslate, actionError, showReadingTools = true, onReadOnlyTools, phoneSharingVisit = false }: {
  document: ReadingDocument; namespace: string; anchor?: ReadingAnchor; onClose: () => void; onAnchorChange: (anchor: ReadingAnchor) => void; onTranslate?: () => void; actionError?: string; showReadingTools?: boolean; onReadOnlyTools?: () => void; phoneSharingVisit?: boolean
}) {
  const root = useRef<HTMLElement>(null), fullscreen = useReaderFullscreen(root)
  const { preferences } = useReaderPreferences(namespace)
  const canvas = useRef<HTMLCanvasElement>(null), viewport = useRef<HTMLDivElement>(null), activeRender = useRef<RenderTask | undefined>(undefined)
  const [opened, setOpened] = useState<{ source: ReadingDocument; namespace: string; phoneSharingVisit: boolean; reading: PdfReadingDocument }>(), [page, setPage] = useState(0)
  const generation = useRef(0), latest = useRef({ document, namespace, phoneSharingVisit }); latest.current = { document, namespace, phoneSharingVisit }
  const reading = opened?.source === document && opened.namespace === namespace && opened.phoneSharingVisit === phoneSharingVisit && opened.reading.matchesInput() ? opened.reading : undefined
  const verifiedDocument = reading?.documentSnapshot
  const pdf = reading?.decoder.pdf, pageCount = reading?.decoder.context.pageCount ?? 0
  const [fit, setFit] = useState<'page' | 'width'>('page'), [bounds, setBounds] = useState({ width: 0, height: 0 }), [error, setError] = useState(''), [busy, setBusy] = useState(true), [tools, setTools] = useState(false)
  const [pan, setPan] = useState(0), [renderedHeight, setRenderedHeight] = useState(0)
  const currentAnchor = reading?.nativePageAnchors?.[page]
  useEffect(() => {
    const controller = new AbortController(), id = ++generation.current; let loaded: PdfReadingDocument | undefined
    const current = () => id === generation.current && latest.current.document === document && latest.current.namespace === namespace && latest.current.phoneSharingVisit === phoneSharingVisit && !controller.signal.aborted
    setBusy(true); setError(''); setOpened(undefined); setPage(0); setTools(false)
    void openPdfReadingDocument(document, controller.signal, phoneSharingVisit).then(value => {
      loaded = value
      if (current()) { setOpened({ source: document, namespace, phoneSharingVisit, reading: value }); setPage(Math.max(0, (value.nativePageAnchors ?? value.visitPageAnchors)?.findIndex(item => item.paragraphId === anchor?.paragraphId) ?? 0)) }
      else void value.decoder.close().catch(() => undefined)
    }).catch(error => { if (current()) { setError(error instanceof Error ? error.message : 'PDF를 열지 못했습니다.'); setBusy(false) } })
    return () => { generation.current++; controller.abort(); activeRender.current?.cancel(); void loaded?.decoder.close().catch(() => undefined) }
  }, [document, namespace, phoneSharingVisit])
  useLayoutEffect(() => {
    const element = viewport.current; if (!element) return
    const observer = new ResizeObserver(() => setBounds({ width: element.clientWidth, height: element.clientHeight }))
    observer.observe(element); return () => observer.disconnect()
  }, [tools])
  useEffect(() => {
    if (!pdf || !reading || !canvas.current || bounds.width <= 0 || bounds.height <= 0 || tools) return
    let cancelled = false; const node = canvas.current
    const id = generation.current, current = () => !cancelled && id === generation.current && latest.current.document === document && latest.current.namespace === namespace && latest.current.phoneSharingVisit === phoneSharingVisit && reading.matchesInput()
    setBusy(true); setError('')
    setPan(0)
    const previousRender = activeRender.current
    previousRender?.cancel()
    void (async () => {
      try {
        if (previousRender) await previousRender.promise.catch(() => {})
        if (!current()) return
        reading.decoder.anchor(page)
        const pdfPage = await pdf.getPage(page + 1)
        try {
        if (!current()) return
        const original = pdfPage.getViewport({ scale: 1 })
        const scale = Math.max(0.1, fit === 'page' ? Math.min(bounds.width / original.width, bounds.height / original.height) : bounds.width / original.width)
        const density = Math.min(globalThis.devicePixelRatio || 1, 2, Math.sqrt(4_000_000 / (original.width * original.height * scale * scale)))
        const view = pdfPage.getViewport({ scale: scale * density })
        node.width = Math.ceil(view.width); node.height = Math.ceil(view.height)
        node.style.width = `${view.width / density}px`; node.style.height = `${view.height / density}px`
        setRenderedHeight(view.height / density)
        const context = node.getContext('2d'); if (!context) throw new Error('이 브라우저에서는 PDF 페이지를 표시할 수 없습니다.')
        const task = pdfPage.render({ canvas: node, canvasContext: context, viewport: view }); activeRender.current = task
        await task.promise
        if (current()) setBusy(false)
        } finally { pdfPage.cleanup() }
      } catch (error) { if (current() && !(error instanceof Error && error.name === 'RenderingCancelledException')) { setError('PDF 페이지를 표시하지 못했습니다. 다른 페이지를 선택해 주세요.'); setBusy(false) } }
    })()
    return () => { cancelled = true; activeRender.current?.cancel() }
  }, [reading, page, bounds, fit, tools])
  function move(next: number) {
    if (!reading || !reading.matchesInput() || !pageCount || !Number.isSafeInteger(next)) return
    const target = Math.max(0, Math.min(pageCount - 1, next)); reading.decoder.anchor(target); setPage(target)
    const textAnchor = reading.nativePageAnchors?.[target]
    if (showReadingTools && textAnchor) onAnchorChange({ ...textAnchor })
    else if (phoneSharingVisit && reading.visitPageAnchors?.[target]) onAnchorChange({ ...reading.visitPageAnchors[target] })
  }
  usePageKeys(() => move(page - 1), () => move(page + 1), !tools, preferences.pageKeys)
  const touch = useReaderTouch(preferences.touchDirection, direction => move(page + direction))
  if (tools && currentAnchor && verifiedDocument) return <ReaderTools namespace={namespace} document={verifiedDocument} anchor={currentAnchor} onClose={() => setTools(false)} onJump={anchor => { const at = reading!.nativePageAnchors!.findIndex(item => item.paragraphId === anchor.paragraphId); if (at >= 0) move(at); setTools(false) }}/>
  const extractionFailed = verifiedDocument?.local?.pdfTextErrorPages?.some(Boolean)
  const extractionUnknown = !verifiedDocument?.local?.pdfTextErrorPages
  return <section ref={root} className="reading-workspace" aria-label={t('PDF 읽기')}><header className="reading-tools-header"><button className="button-quiet" onClick={onClose}>{t('돌아가기')}</button><strong>{document.bookTitle}</strong>{showReadingTools && <button className="button-outline" disabled={!currentAnchor} onClick={() => { if (reading?.matchesInput()) setTools(true) }}>{t('읽기 도구')}</button>}{onReadOnlyTools && <button className="button-outline" onClick={onReadOnlyTools}>{t('읽기 도구')}</button>}{onTranslate && <button className="button-outline" onClick={() => { if (reading?.matchesInput()) onTranslate() }} disabled={!reading?.nativePageAnchors || extractionFailed || extractionUnknown || !verifiedDocument?.local?.pdfTextPages?.some(Boolean)}>{t('텍스트 페이지 번역')}</button>}<button className="button-outline" onClick={() => void fullscreen.toggle()}>{t(fullscreen.fullscreen ? '전체 화면 해제' : '전체 화면')}</button></header>
    <nav className="workflow-subtabs" aria-label={t('PDF 맞춤')}><button aria-pressed={fit === 'page'} onClick={() => setFit('page')}>{t('페이지 맞춤')}</button><button aria-pressed={fit === 'width'} onClick={() => setFit('width')}>{t('너비 맞춤')}</button></nav>
    {error && <div role="alert" className="workflow-message">{t(error)}</div>}
    {actionError && <div role="alert" className="workflow-message">{t(actionError)}</div>}
    {fullscreen.error && <div role="alert" className="workflow-message">{t(fullscreen.error)}</div>}
    {showReadingTools && (extractionFailed || extractionUnknown) && <div role="alert" className="workflow-message">{t(extractionFailed ? '일부 페이지의 텍스트 추출에 실패해 파일 전체 번역을 사용할 수 없습니다. 원본 읽기는 가능합니다.' : 'PDF의 텍스트 추출 상태를 확인하려면 원본 파일을 다시 가져와 주세요.')}</div>}
    {showReadingTools && reading && !reading.nativePageAnchors && <div role="alert" className="workflow-message">{t('PDF의 표시 본문을 현재 원본과 대응할 수 없습니다. 원본 읽기는 가능하며 읽기 도구를 사용하려면 다시 가져와 주세요.')}</div>}
    <div className="local-pdf-viewport" ref={viewport} aria-busy={busy} {...touch}><canvas ref={canvas} style={{ transform: `translateY(-${pan}px)` }} aria-label={t('PDF {0}페이지', [page + 1])}/></div>
    {fit === 'width' && <nav className="reading-row-actions" aria-label={t('PDF 페이지 안 이동')}><button className="button-quiet" disabled={pan <= 0} onClick={() => setPan(value => Math.max(0, value - Math.max(1, bounds.height - 24)))}>{t('위로')}</button><button className="button-quiet" disabled={pan >= Math.max(0, renderedHeight - bounds.height)} onClick={() => setPan(value => Math.min(Math.max(0, renderedHeight - bounds.height), value + Math.max(1, bounds.height - 24)))}>{t('아래로')}</button></nav>}
    {(showReadingTools || busy) && <div className="reading-pdf-note" role="status">{busy ? t('페이지를 표시하고 있습니다…') : verifiedDocument?.local?.pdfTextErrorPages?.[page] ? t('이 페이지의 텍스트 추출에 실패했습니다. 원본 화면을 표시합니다.') : extractionUnknown ? t('이 PDF의 텍스트 추출 상태를 확인할 수 없습니다.') : verifiedDocument?.local?.pdfTextPages?.[page] ? t('텍스트가 있는 PDF 페이지입니다.') : t('이미지 PDF 페이지입니다. 읽기는 가능하며 텍스트 번역·검색은 지원하지 않습니다.')}</div>}
    <nav className="page-navigation" aria-label={t('책 페이지')}><button className="button-quiet" disabled={!pageCount || page <= 0} onClick={() => move(page - 1)}>{t('이전')}</button><span>{pageCount ? page + 1 : 0} / {pageCount}</span><button className="button-quiet" disabled={!pageCount || page >= pageCount - 1} onClick={() => move(page + 1)}>{t('다음')}</button></nav>
  </section>
}
