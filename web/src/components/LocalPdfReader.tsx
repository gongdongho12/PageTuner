import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import type { PDFDocumentProxy, RenderTask } from 'pdfjs-dist'
import { translate as t } from '../lib/locale'
import { openLocalPdf } from '../lib/pdfDocument'
import type { LocalDocument } from '../lib/localDocuments'
import type { ReadingAnchor } from '../lib/offline'
import { ReaderTools } from './ReaderTools'
import { usePageKeys } from './usePageKeys'
import { useReaderPreferences } from './ReaderPreferences'
import { useReaderFullscreen, useReaderTouch } from './useReaderControls'
import './readingTools.css'

export function LocalPdfReader({ document, namespace, anchor, onClose, onAnchorChange, onTranslate, actionError, showReadingTools = true }: {
  document: LocalDocument; namespace: string; anchor?: ReadingAnchor; onClose: () => void; onAnchorChange: (anchor: ReadingAnchor) => void; onTranslate?: () => void; actionError?: string; showReadingTools?: boolean
}) {
  const root = useRef<HTMLElement>(null), fullscreen = useReaderFullscreen(root)
  const { preferences } = useReaderPreferences(namespace)
  const canvas = useRef<HTMLCanvasElement>(null), viewport = useRef<HTMLDivElement>(null), activeRender = useRef<RenderTask | undefined>(undefined)
  const [pdf, setPdf] = useState<PDFDocumentProxy>(), [page, setPage] = useState(Math.max(0, document.paragraphs.findIndex(item => item.paragraphId === anchor?.paragraphId)))
  const [fit, setFit] = useState<'page' | 'width'>('page'), [bounds, setBounds] = useState({ width: 0, height: 0 }), [error, setError] = useState(''), [busy, setBusy] = useState(true), [tools, setTools] = useState(false)
  const [pan, setPan] = useState(0), [renderedHeight, setRenderedHeight] = useState(0)
  const currentAnchor = { paragraphId: document.paragraphs[page].paragraphId, characterOffset: 0 }
  useEffect(() => {
    const controller = new AbortController(); let loaded: PDFDocumentProxy | undefined
    setBusy(true)
    void document.assets!.pdf!.arrayBuffer().then(bytes => openLocalPdf(new Uint8Array(bytes), controller.signal)).then(value => { loaded = value; if (!controller.signal.aborted) setPdf(value); else void value.destroy() }).catch(error => { if (!controller.signal.aborted) { setError(error instanceof Error ? error.message : 'PDF를 열지 못했습니다.'); setBusy(false) } })
    return () => { controller.abort(); activeRender.current?.cancel(); void loaded?.destroy() }
  }, [document])
  useLayoutEffect(() => {
    const element = viewport.current; if (!element) return
    const observer = new ResizeObserver(() => setBounds({ width: element.clientWidth, height: element.clientHeight }))
    observer.observe(element); return () => observer.disconnect()
  }, [tools])
  useEffect(() => {
    if (!pdf || !canvas.current || bounds.width <= 0 || bounds.height <= 0 || tools) return
    let cancelled = false; const node = canvas.current
    setBusy(true); setError('')
    setPan(0)
    const previousRender = activeRender.current
    previousRender?.cancel()
    void (async () => {
      try {
        if (previousRender) await previousRender.promise.catch(() => {})
        const pdfPage = await pdf.getPage(page + 1); if (cancelled) return
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
        if (!cancelled) setBusy(false)
      } catch (error) { if (!cancelled && !(error instanceof Error && error.name === 'RenderingCancelledException')) { setError('PDF 페이지를 표시하지 못했습니다. 다른 페이지를 선택해 주세요.'); setBusy(false) } }
    })()
    return () => { cancelled = true; activeRender.current?.cancel() }
  }, [pdf, page, bounds, fit, tools])
  function move(next: number) { const target = Math.max(0, Math.min(document.paragraphs.length - 1, next)); setPage(target); onAnchorChange({ paragraphId: document.paragraphs[target].paragraphId, characterOffset: 0 }) }
  usePageKeys(() => move(page - 1), () => move(page + 1), !tools, preferences.pageKeys)
  const touch = useReaderTouch(preferences.touchDirection, direction => move(page + direction))
  if (tools) return <ReaderTools namespace={namespace} document={document} anchor={currentAnchor} onClose={() => setTools(false)} onJump={anchor => { move(document.paragraphs.findIndex(item => item.paragraphId === anchor.paragraphId)); setTools(false) }}/>
  const extractionFailed = document.local.pdfTextErrorPages?.some(Boolean)
  const extractionUnknown = !document.local.pdfTextErrorPages
  return <section ref={root} className="reading-workspace" aria-label={t('PDF 읽기')}><header className="reading-tools-header"><button className="button-quiet" onClick={onClose}>{t('돌아가기')}</button><strong>{document.bookTitle}</strong>{showReadingTools && <button className="button-outline" onClick={() => setTools(true)}>{t('읽기 도구')}</button>}{onTranslate && <button className="button-outline" onClick={onTranslate} disabled={extractionFailed || extractionUnknown || !document.local.pdfTextPages?.some(Boolean)}>{t('텍스트 페이지 번역')}</button>}<button className="button-outline" onClick={() => void fullscreen.toggle()}>{t(fullscreen.fullscreen ? '전체 화면 해제' : '전체 화면')}</button></header>
    <nav className="workflow-subtabs" aria-label={t('PDF 맞춤')}><button aria-pressed={fit === 'page'} onClick={() => setFit('page')}>{t('페이지 맞춤')}</button><button aria-pressed={fit === 'width'} onClick={() => setFit('width')}>{t('너비 맞춤')}</button></nav>
    {error && <div role="alert" className="workflow-message">{t(error)}</div>}
    {actionError && <div role="alert" className="workflow-message">{t(actionError)}</div>}
    {fullscreen.error && <div role="alert" className="workflow-message">{t(fullscreen.error)}</div>}
    {(extractionFailed || extractionUnknown) && <div role="alert" className="workflow-message">{t(extractionFailed ? '일부 페이지의 텍스트 추출에 실패해 파일 전체 번역을 사용할 수 없습니다. 원본 읽기는 가능합니다.' : 'PDF의 텍스트 추출 상태를 확인하려면 원본 파일을 다시 가져와 주세요.')}</div>}
    <div className="local-pdf-viewport" ref={viewport} aria-busy={busy} {...touch}><canvas ref={canvas} style={{ transform: `translateY(-${pan}px)` }} aria-label={t('PDF {0}페이지', [page + 1])}/></div>
    {fit === 'width' && <nav className="reading-row-actions" aria-label={t('PDF 페이지 안 이동')}><button className="button-quiet" disabled={pan <= 0} onClick={() => setPan(value => Math.max(0, value - Math.max(1, bounds.height - 24)))}>{t('위로')}</button><button className="button-quiet" disabled={pan >= Math.max(0, renderedHeight - bounds.height)} onClick={() => setPan(value => Math.min(Math.max(0, renderedHeight - bounds.height), value + Math.max(1, bounds.height - 24)))}>{t('아래로')}</button></nav>}
    {(showReadingTools || busy) && <div className="reading-pdf-note" role="status">{busy ? t('페이지를 표시하고 있습니다…') : document.local.pdfTextErrorPages?.[page] ? t('이 페이지의 텍스트 추출에 실패했습니다. 원본 화면을 표시합니다.') : extractionUnknown ? t('이 PDF의 텍스트 추출 상태를 확인할 수 없습니다.') : document.local.pdfTextPages?.[page] ? t('텍스트가 있는 PDF 페이지입니다.') : t('이미지 PDF 페이지입니다. 읽기는 가능하며 텍스트 번역·검색은 지원하지 않습니다.')}</div>}
    <nav className="page-navigation" aria-label={t('책 페이지')}><button className="button-quiet" disabled={page <= 0} onClick={() => move(page - 1)}>{t('이전')}</button><span>{page + 1} / {document.paragraphs.length}</span><button className="button-quiet" disabled={page >= document.paragraphs.length - 1} onClick={() => move(page + 1)}>{t('다음')}</button></nav>
  </section>
}
