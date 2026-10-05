import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import type { ReadingDocument } from '../lib/readingDocument'
import { documentExportFormats, prepareDocumentFileExport, type DocumentExportFormat } from '../lib/documentFileExport'
import { prepareDocumentPdfPrint, openDocumentPdfPrint } from '../lib/documentPdfPrint'
import { translate as t } from '../lib/locale'
import { AdaptiveCollection } from './AdaptiveCollection'
import './readingTools.css'

type Prepared = { kind: 'file'; value: Awaited<ReturnType<typeof prepareDocumentFileExport>> }
  | { kind: 'print'; value: ReturnType<typeof prepareDocumentPdfPrint> }
type ExportChoice = DocumentExportFormat | 'pdf-document'
const labels: Record<ExportChoice, string> = { txt: '본문 TXT로 내보내기', markdown: '본문 Markdown으로 내보내기', pdf: 'PDF 원본 저장', epub: 'EPUB 전자책 만들기', 'pdf-document': 'PDF 문서 만들기' }
const descriptions: Record<ExportChoice, string> = {
  txt: '일반 텍스트 편집기에서 읽을 수 있는 UTF-8 파일입니다.',
  markdown: '제목과 본문을 Markdown 문서로 저장합니다. EPUB 레이아웃이나 원래 Markdown 문법을 복원하지 않습니다.',
  pdf: '보관된 PDF 원본을 확인해 그대로 저장합니다.',
  epub: '전체 본문과 목차를 EPUB 전자책으로 만듭니다. 글자 크기를 바꿔 읽을 수 있으며 삽화와 원래 레이아웃은 포함하지 않습니다.',
  'pdf-document': '전체 본문을 A4 인쇄 문서로 준비합니다. 브라우저 인쇄 창에서 PDF로 저장을 선택하세요.',
}

/** Prepared content belongs to one selection; changing or closing it invalidates late work. */
export function DocumentFileExportPanel({ document, onClose }: { document: ReadingDocument; onClose: () => void }) {
  const [prepared, setPrepared] = useState<Prepared>(), [url, setUrl] = useState(''), [busy, setBusy] = useState(false), [error, setError] = useState('')
  const [printMessage, setPrintMessage] = useState('')
  const request = useRef<AbortController | undefined>(undefined), generation = useRef(0)
  const printJob = useRef<ReturnType<typeof openDocumentPdfPrint> | undefined>(undefined)
  const latest = useRef(document); latest.current = document
  useLayoutEffect(() => {
    generation.current++; request.current?.abort(); printJob.current?.close()
    setPrepared(undefined); setUrl(''); setBusy(false); setError(''); setPrintMessage('')
    return () => { generation.current++; request.current?.abort(); printJob.current?.close() }
  }, [document])
  useEffect(() => {
    if (!prepared) { setUrl(''); return }
    const blob = prepared.kind === 'file' ? prepared.value.blob : new Blob([prepared.value.html], { type: 'text/html;charset=utf-8' })
    const link = URL.createObjectURL(blob); setUrl(link)
    return () => URL.revokeObjectURL(link)
  }, [prepared])
  const close = () => { generation.current++; request.current?.abort(); printJob.current?.close(); onClose() }
  const chooseAgain = () => {
    generation.current++; request.current?.abort(); printJob.current?.close(); printJob.current = undefined
    setPrintMessage(''); setError(''); setPrepared(undefined); setUrl('')
  }
  async function prepare(format: ExportChoice) {
    const source = document, id = ++generation.current, controller = new AbortController()
    request.current?.abort(); printJob.current?.close(); request.current = controller
    setBusy(true); setError(''); setPrepared(undefined); setPrintMessage('')
    try {
      const result: Prepared = format === 'pdf-document'
        ? { kind: 'print', value: prepareDocumentPdfPrint(source) }
        : { kind: 'file', value: await prepareDocumentFileExport(source, format, controller.signal) }
      if (!controller.signal.aborted && id === generation.current && latest.current === source) setPrepared(result)
    } catch (error) {
      if (!controller.signal.aborted && id === generation.current) setError(error instanceof Error ? error.message : '파일을 내보내지 못했습니다.')
    } finally { if (id === generation.current) setBusy(false) }
  }
  function print() {
    if (prepared?.kind !== 'print') return
    const id = generation.current, source = document
    printJob.current?.close(); setError(''); setPrintMessage('인쇄 창을 열고 있습니다…')
    try {
      const job = openDocumentPdfPrint(prepared.value); printJob.current = job
      void job.ready.then(() => {
        if (generation.current === id && latest.current === source && printJob.current === job)
          setPrintMessage('인쇄 창에서 PDF로 저장을 선택하세요. 창이 열리지 않으면 인쇄용 문서 보기를 이용해 브라우저 메뉴에서 인쇄하세요.')
      }).catch(error => {
        if (generation.current === id && latest.current === source && printJob.current === job) { setPrintMessage(''); setError(error instanceof Error ? error.message : '파일을 내보내지 못했습니다.') }
      })
    } catch (error) { setPrintMessage(''); setError(error instanceof Error ? error.message : '파일을 내보내지 못했습니다.') }
  }
  const formats = documentExportFormats(document)
  const choices: ExportChoice[] = formats.includes('epub') ? ['epub', 'pdf-document', ...formats.filter(format => format !== 'epub')] : formats
  return <section className="reading-workspace" aria-label={t('책 파일 내보내기')}>
    <header className="reading-tools-header"><button className="button-quiet" onClick={close}>{t('돌아가기')}</button><strong>{t('책 파일 내보내기')}</strong></header>
    <p className="reading-tools-caption document-export-title">{document.bookTitle}{document.chapterTitle !== document.bookTitle ? ` · ${document.chapterTitle}` : ''}</p>
    {error && <p role="alert" className="workflow-message">{t(error)}</p>}
    {busy && <p role="status" className="workflow-message">{t('파일을 준비하고 있습니다…')}</p>}
    {printMessage && <p role="status" className="workflow-message">{t(printMessage)}</p>}
    {!formats.length && <p role="alert" className="workflow-message">{t('PDF 원본 파일이 없거나 크기 제한을 초과합니다. 원본을 다시 가져오거나 ZIP 내보내기를 사용하세요.')}</p>}
    {prepared ? <AdaptiveCollection key="prepared" mode="paged" items={prepared.kind === 'print' ? ['save', 'preview', 'scope', 'again'] : ['save', 'scope', 'again']} itemKey={value => value} rowHeight={156} renderItem={value => <div className="reading-field document-export-field">
      {value === 'save' ? <><span className="document-export-filename">{prepared.value.name}</span>{prepared.kind === 'print' ? <button className="button-primary" onClick={print}>{t('PDF로 저장 · 인쇄')}</button> : url && <a className="button-primary document-export-download" href={url} download={prepared.value.name}>{t('파일 저장')}</a>}</>
        : value === 'preview' ? <><p>{t('인쇄 창이 지원되지 않으면 문서를 새 탭에서 열어 브라우저의 인쇄 메뉴를 사용하세요.')}</p>{url && <a className="button-outline document-export-download" href={url} target="_blank" rel="noopener noreferrer">{t('인쇄용 문서 보기')}</a>}</>
        : value === 'again' ? <button className="button-outline" onClick={chooseAgain}>{t('다른 형식 선택')}</button>
        : <p>{t(prepared.kind === 'print' ? 'PDF 저장은 브라우저 인쇄 창에서 완료합니다. 대상에서 PDF로 저장을 선택하고 모든 페이지를 확인하세요. 이 화면은 저장 성공을 확인하지 않습니다.' : '파일을 준비했습니다. 파일 저장을 눌러 다운로드 위치를 선택하세요. 저장 완료 여부는 브라우저에서 확인해 주세요.')}</p>}
    </div>}/> : <AdaptiveCollection key="formats" mode="paged" items={[...choices, 'scope' as const]} itemKey={value => value} rowHeight={156} renderItem={value => <div className="reading-field document-export-field">
      {value === 'scope' ? <p>{t('선택한 문서의 전체 본문을 내보냅니다. 회차 문서는 해당 회차만 포함합니다. 메모·읽기 위치·계정과 삽화는 포함하지 않습니다. 기존 PDF는 원본 파일을 그대로 저장합니다.')}</p>
        : <><p>{t(descriptions[value])}</p><button className="button-outline" disabled={busy} onClick={() => void prepare(value)}>{t(labels[value])}</button></>}
    </div>}/>}
  </section>
}
