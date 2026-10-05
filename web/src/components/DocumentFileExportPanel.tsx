import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import type { ReadingDocument } from '../lib/readingDocument'
import { documentExportFormats, prepareDocumentFileExport, type DocumentExportFormat } from '../lib/documentFileExport'
import { translate as t } from '../lib/locale'
import { AdaptiveCollection } from './AdaptiveCollection'
import './readingTools.css'

type Prepared = Awaited<ReturnType<typeof prepareDocumentFileExport>>
/** Prepared bytes belong to one document selection; a new selection or closing invalidates late work. */
export function DocumentFileExportPanel({ document, onClose }: { document: ReadingDocument; onClose: () => void }) {
  const [prepared, setPrepared] = useState<Prepared>(), [url, setUrl] = useState(''), [busy, setBusy] = useState(false), [error, setError] = useState('')
  const request = useRef<AbortController | undefined>(undefined), generation = useRef(0)
  const latest = useRef(document); latest.current = document
  useLayoutEffect(() => {
    generation.current++; request.current?.abort(); setPrepared(undefined); setUrl(''); setBusy(false); setError('')
    return () => { generation.current++; request.current?.abort() }
  }, [document])
  useEffect(() => {
    if (!prepared) { setUrl(''); return }
    const link = URL.createObjectURL(prepared.blob); setUrl(link)
    return () => URL.revokeObjectURL(link)
  }, [prepared])
  const close = () => { generation.current++; request.current?.abort(); onClose() }
  async function prepare(format: DocumentExportFormat) {
    const source = document, id = ++generation.current, controller = new AbortController()
    request.current?.abort(); request.current = controller; setBusy(true); setError(''); setPrepared(undefined)
    try {
      const result = await prepareDocumentFileExport(source, format, controller.signal)
      if (!controller.signal.aborted && id === generation.current && latest.current === source) setPrepared(result)
    } catch (error) {
      if (!controller.signal.aborted && id === generation.current) setError(error instanceof Error ? error.message : '파일을 내보내지 못했습니다.')
    } finally { if (id === generation.current) setBusy(false) }
  }
  const labels = { txt: '본문 TXT로 내보내기', markdown: '본문 Markdown으로 내보내기', pdf: 'PDF 원본 저장' }
  const formats = documentExportFormats(document)
  return <section className="reading-workspace" aria-label={t('책 파일 내보내기')}>
    <header className="reading-tools-header"><button className="button-quiet" onClick={close}>{t('돌아가기')}</button><strong>{t('책 파일 내보내기')}</strong></header>
    <p className="reading-tools-caption document-export-title">{document.bookTitle}{document.chapterTitle !== document.bookTitle ? ` · ${document.chapterTitle}` : ''}</p>
    {error && <p role="alert" className="workflow-message">{t(error)}</p>}
    {busy && <p role="status" className="workflow-message">{t('파일을 준비하고 있습니다…')}</p>}
    {!formats.length && <p role="alert" className="workflow-message">{t('PDF 원본 파일이 없거나 크기 제한을 초과합니다. 원본을 다시 가져오거나 ZIP 내보내기를 사용하세요.')}</p>}
    {prepared ? <AdaptiveCollection mode="paged" items={['save', 'scope', 'again']} itemKey={value => value} rowHeight={156} renderItem={value => <div className="reading-field document-export-field">
      {value === 'save' ? <><span className="document-export-filename">{prepared.name}</span>{url && <a className="button-primary document-export-download" href={url} download={prepared.name}>{t('파일 저장')}</a>}</>
        : value === 'again' ? <button className="button-outline" onClick={() => { setPrepared(undefined); setUrl('') }}>{t('다른 형식 선택')}</button>
        : <p>{t('파일을 준비했습니다. 파일 저장을 눌러 다운로드 위치를 선택하세요. 저장 완료 여부는 브라우저에서 확인해 주세요.')}</p>}
    </div>}/> : <AdaptiveCollection mode="paged" items={[...formats, 'scope' as const]} itemKey={value => value} rowHeight={156} renderItem={value => <div className="reading-field document-export-field">
      {value === 'scope' ? <p>{t('선택한 문서의 전체 본문을 내보냅니다. 회차 문서는 해당 회차만 포함합니다. 계정·메모·읽기 위치·삽화는 포함하지 않으며 기록 이전에는 ZIP을 사용하세요. PDF는 보관된 원본 파일을 저장합니다.')}</p>
        : <><p>{t(value === 'pdf' ? '보관된 PDF의 원본 bytes를 확인해 그대로 저장합니다. 텍스트 파일을 PDF로 변환하지 않습니다.' : value === 'txt' ? '일반 텍스트 편집기에서 읽을 수 있는 UTF-8 파일입니다.' : '제목과 본문을 Markdown 문서로 저장합니다. EPUB 레이아웃이나 원래 Markdown 문법을 복원하지 않습니다.')}</p><button className="button-outline" disabled={busy} onClick={() => void prepare(value)}>{t(labels[value])}</button></>}
    </div>}/>}
  </section>
}
