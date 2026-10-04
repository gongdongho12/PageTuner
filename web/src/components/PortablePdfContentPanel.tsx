import { useLayoutEffect, useMemo, useRef, useState } from 'react'
import { translate as t, useLocale } from '../lib/locale'
import type { SavedExchange } from '../lib/exchangeLibrary'
import type { PdfContentClient, PdfContentReceipt } from '../lib/pdfContentApi'
import { createPortablePdfSession, pdfBindingErrorMessage, type PdfCopyReview } from '../lib/portablePdfBinding'
import { validRecordId } from '../lib/validation'
import { AdaptiveCollection } from './AdaptiveCollection'
import './readingTools.css'

type Field = { id: string; label: string; value: string; display?: string }
type Panel = 'menu' | 'details' | 'upload' | 'existing' | 'link' | 'unlink' | 'result'
const messageFields = (message: string, prefix: string): Field[] => {
  const text = Array.from(message), count = Math.max(1, Math.ceil(text.length / 64))
  return Array.from({ length: count }, (_, index) => ({ id: `${prefix}:${index}`, label: `${index + 1}/${count}`, value: text.slice(index * 64, (index + 1) * 64).join('') }))
}
/** Escape controls for inspection, keeping raw pieces lossless and each display within 24 monospace cells. */
function inspectableParts(value: string) {
  const parts: { value: string; display: string }[] = []; let raw = '', shown = '', cells = 0
  const flush = () => { parts.push({ value: raw, display: `"${shown}"` }); raw = ''; shown = ''; cells = 0 }
  for (const point of Array.from(value)) {
    const cp = point.codePointAt(0)!, encoded = /[\u007f-\u009f\u2028\u2029]/.test(point) ? `\\u${cp.toString(16).padStart(4, '0')}` : JSON.stringify(point).slice(1, -1)
    const width = Array.from(encoded).reduce((n, c) => n + (c.codePointAt(0)! > 0x7e ? 2 : 1), 0)
    if (cells + width > 24) flush()
    raw += point; shown += encoded; cells += width
  }
  if (raw || !parts.length) flush()
  return parts
}
export function pdfCopyReviewFields(review: PdfCopyReview, username: string, origin: string, recordId?: string): Field[] {
  const fields = [
    { label: t('보관할 계정'), value: username }, { label: t('계정 서버'), value: origin },
    { label: t('책 제목'), value: review.title }, { label: t('원래 언어'), value: review.content.language },
    { label: t('전체 내용 SHA-256'), value: review.proof.sha256 }, { label: t('PDF 원본 SHA-256'), value: review.proof.originalFileSha256! },
    { label: t('PDF 원본 크기'), value: `${review.proof.originalFileByteLength} bytes` },
    { label: t('문단·자산 참조 수'), value: `${review.content.paragraphs.length} / ${review.content.assets.length}` },
    { label: t('서버 사본 번호'), value: recordId ?? review.binding?.recordId ?? t('연결 없음') },
    ...review.content.paragraphs.flatMap((p, i) => [{ label: `${i + 1} · ${t('문단 식별자')}`, value: p.paragraphId }, { label: `${i + 1} · ${t('문단 본문')}`, value: p.text }]),
    ...review.proof.assets.flatMap((a, i) => [
      { label: `${i + 1} · ${t('자산 경로')}`, value: a.path }, { label: `${i + 1} · ${t('자산 종류')}`, value: `${a.role} / ${a.mimeType}` },
      { label: `${i + 1} · ${t('자산 문단 식별자')}`, value: a.paragraphId === null ? t('없음 (null)') : a.paragraphId },
      { label: `${i + 1} · ${t('자산 설명')}`, value: a.alt === null ? t('없음 (null)') : a.alt },
      { label: `${i + 1} · ${t('자산 크기')}`, value: `${a.byteLength} bytes` },
    ]),
  ]
  return fields.flatMap((field, i) => { const parts = inspectableParts(field.value)
    return parts.map((part, index) => ({ id: `${i}:${index}`, label: `${field.label}${parts.length > 1 ? ` · ${index + 1}/${parts.length}` : ''}`, ...part })) })
}

export function PortablePdfContentPanel({ copyId, username, client, onClose, onRead }: {
  copyId: string; username: string; client: PdfContentClient | null; onClose: () => void; onRead: (saved: SavedExchange) => void
}) {
  useLocale()
  const origin = window.location.origin, context = useMemo(() => ({ copyId, username, client, origin }), [copyId, username, client, origin])
  const latest = useRef(context); latest.current = context
  const session = useRef<ReturnType<typeof createPortablePdfSession> | null>(null), generation = useRef(0), running = useRef(false)
  const [review, setReview] = useState<PdfCopyReview>(), [panel, setPanel] = useState<Panel>('menu'), [error, setError] = useState(''), [notice, setNotice] = useState('')
  const [busy, setBusy] = useState(false), [recordId, setRecordId] = useState(''), [receipt, setReceipt] = useState<PdfContentReceipt>(), [uploadAttempted, setUploadAttempted] = useState(false)
  useLayoutEffect(() => {
    const id = ++generation.current, started = context, current = () => id === generation.current && latest.current === started
    const next = createPortablePdfSession({ username, origin, client, current }); session.current = next
    running.current = true; setBusy(true); setReview(undefined); setReceipt(undefined); setRecordId(''); setPanel('menu'); setError(''); setNotice(''); setUploadAttempted(false)
    void next.prepare(copyId).then(value => { if (current()) setReview(value) }).catch(e => { if (current()) setError(pdfBindingErrorMessage(e)) }).finally(() => { if (current()) { running.current = false; setBusy(false) } })
    return () => { generation.current++; next.close(); running.current = false }
  }, [context])
  const run = async (action: (current: () => boolean) => Promise<void>) => {
    if (running.current || !review || !session.current) return
    const id = generation.current, started = context, current = () => id === generation.current && latest.current === started
    running.current = true; setBusy(true); setError('')
    try { await action(current) } catch (e) { if (current()) setError(pdfBindingErrorMessage(e)) }
    finally { if (current()) { running.current = false; setBusy(false) } }
  }
  const refresh = async (message: string, current: () => boolean) => {
    const next = await session.current!.prepare(copyId)
    if (current()) { setReview(next); setNotice(message); setPanel('result'); setReceipt(undefined); setUploadAttempted(false) }
  }
  const upload = () => void run(async current => {
    setUploadAttempted(true); setReceipt(undefined)
    try { const value = await review!.upload(); if (current()) { setReceipt(value); setRecordId(value.recordId); setPanel('link') } }
    catch (e) { if (current()) { const uploaded = review!.uploadedReceipt; if (uploaded) { setReceipt(uploaded); setRecordId(uploaded.recordId) } }; throw e }
  })
  const compare = () => void run(async current => { setReceipt(undefined); const value = await review!.compare(recordId); if (current()) { setReceipt(value); setPanel('link') } })
  const confirm = () => void run(async current => {
    try { await review!.confirm(); if (current()) await refresh('이 기기의 PDF 사본을 서버 보관본에 연결했습니다.', current) }
    catch (e) { if (current()) { const next = await session.current!.prepare(copyId); if (current()) { setReview(next); setPanel('existing') } }; throw e }
  })
  const unlink = () => void run(async current => {
    try { await review!.unlink(); if (current()) await refresh('이 기기의 PDF 연결을 해제했습니다. 기기와 서버 사본은 보존됩니다.', current) }
    catch (e) { if (current()) { const next = await session.current!.prepare(copyId); if (current()) { setReview(next); setPanel('menu') } }; throw e }
  })
  const open = () => void run(async current => { const saved = await review!.open(); if (current()) onRead(saved) })
  const close = () => { generation.current++; session.current?.close(); onClose() }
  // Locale packs can change while the panel stays open; labels must update with every locale render.
  const fields = review ? pdfCopyReviewFields(review, username, origin, receipt?.recordId) : []
  const info = panel === 'upload' ? '원본 PDF·본문·삽화를 현재 계정 서버에 보관합니다. 연결은 다음 화면에서 따로 확정합니다. 응답이 없어도 저장되었을 수 있습니다.'
    : panel === 'link' ? '조회한 서버의 실제 내용이 이 PDF 사본과 일치합니다. 아래 계정과 사본 번호를 확인하고 이 기기 연결을 확정해 주세요.'
    : panel === 'unlink' ? '이 기기의 연결만 해제합니다. 서버 보관본과 ZIP 원본, 독서 기록은 삭제하지 않습니다.'
    : 'PDF 보관과 연결은 독서 위치·메모·분류·용어집 동기화를 활성화하지 않습니다. 원래 기기 읽기는 계속 사용할 수 있습니다.'
  const summaries = [...messageFields(t(info), 'info'), ...fields.slice(0, fields.findIndex(v => v.label.startsWith('1 ·')) < 0 ? fields.length : fields.findIndex(v => v.label.startsWith('1 ·')))]
  const menu = [...messageFields(t(notice || info), 'intro').map(v => ({ id: v.id, message: v.value })), ...['details', 'upload', 'existing', ...(review?.binding ? ['read', 'unlink'] : [])].map(id => ({ id, message: '' }))]
  const renderField = (field: Field) => <label className="reading-field pdf-copy-field"><strong>{field.label}</strong><textarea readOnly aria-label={field.label} value={field.display ?? field.value} spellCheck={false}/></label>
  return <section className="reading-workspace pdf-copy-panel" aria-label={t('PDF 서버 보관·연결')}>
    <header className="reading-tools-header"><strong>{t('PDF 서버 보관·연결')}</strong><button onClick={close}>{t(busy ? '취소·닫기' : '닫기')}</button></header>
    {busy ? <AdaptiveCollection items={[{ id: 'busy', label: t('확인 중'), value: t('PDF 원본과 서버 응답을 확인하고 있습니다.') }]} itemKey={v => v.id} rowHeight={144} renderItem={renderField}/>
      : error ? <><AdaptiveCollection items={[...messageFields(t(error), 'error'), ...(receipt ? fields.filter(v => v.label.startsWith(t('서버 사본 번호'))) : [])]} itemKey={v => v.id} rowHeight={144} renderItem={field => <div className="reading-field" role="alert"><strong>{field.label}</strong><p>{field.value}</p></div>}/><div className="reading-filter-actions"><button onClick={() => setError('')}>{t('이전')}</button><button onClick={close}>{t('닫기')}</button></div></>
      : !review ? <div className="reading-field"><p>{t('PDF 사본을 다시 선택해 주세요.')}</p></div>
      : panel === 'menu' || panel === 'result' ? <AdaptiveCollection items={menu} itemKey={v => v.id} rowHeight={144} renderItem={item => <div className="reading-field pdf-copy-action">
        {item.message ? <p role="status">{item.message}</p> : <>
          <strong>{t(item.id === 'details' ? '원본·계정 정보 확인' : item.id === 'upload' ? '새 서버 사본 보관' : item.id === 'existing' ? '기존 서버 사본 연결' : item.id === 'read' ? '서버 확인 후 기기로 읽기' : '이 기기 연결 해제')}</strong>
          <button disabled={!client && !['details', 'unlink'].includes(item.id)} onClick={() => { if (item.id === 'read') open(); else { setNotice(''); setPanel(item.id as Panel) } }}>{t(item.id === 'details' ? '전체 내용 확인' : item.id === 'read' ? '내용 재검증 후 읽기' : '선택')}</button>
        </>}</div>}/>
      : panel === 'existing' ? <><AdaptiveCollection items={['scope', 'record']} itemKey={v => v} rowHeight={176} renderItem={item => item === 'record' ? <label className="reading-field"><strong>{t('서버 사본 번호')}</strong><input aria-label={t('서버 사본 번호')} value={recordId} onChange={e => { setRecordId(e.target.value); setReceipt(undefined) }} placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"/><button disabled={!client || !validRecordId(recordId) || recordId !== recordId.toLowerCase()} onClick={compare}>{t('서버 실제 내용 비교')}</button></label> : <div className="reading-field"><p>{t('현재 계정에 저장된 PDF 사본 번호를 입력하세요. 조회만 하며 아직 연결하지 않습니다.')}</p></div>}/><div className="reading-filter-actions"><button onClick={() => setPanel('menu')}>{t('이전')}</button></div></>
      : <><AdaptiveCollection key={panel} items={panel === 'details' ? [...messageFields(t('따옴표 안의 공백은 원문입니다. 줄바꿈·탭은 \\n·\\r·\\t처럼 표시하며 원본 내용은 바꾸지 않습니다.'), 'info'), ...fields] : summaries} itemKey={v => v.id} rowHeight={144} renderItem={field => field.id.startsWith('info:') ? <div className="reading-field"><p>{field.value}</p></div> : renderField(field)}/><div className="reading-filter-actions"><button onClick={() => setPanel('menu')}>{t('이전')}</button>
        {panel === 'upload' && <button disabled={!client} onClick={upload}>{t(uploadAttempted ? '같은 요청으로 다시 확인' : '서버에 새 사본 보관')}</button>}
        {panel === 'link' && <button disabled={!client || !receipt} onClick={confirm}>{t('이 기기 연결 확정')}</button>}
        {panel === 'unlink' && <button onClick={unlink}>{t('연결 해제 확정')}</button>}
      </div></>}
  </section>
}
