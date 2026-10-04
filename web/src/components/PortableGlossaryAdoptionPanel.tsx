import { useLayoutEffect, useMemo, useRef, useState } from 'react'
import { translate as t } from '../lib/locale'
import { bookGlossaryKey, type BookGlossary, type BookGlossaryScope } from '../lib/bookGlossaryApi'
import type { SavedExchange } from '../lib/exchangeLibrary'
import type { LibraryIdentityClient } from '../lib/libraryIdentityApi'
import { createPersonalLibrary } from '../lib/personalLibrary'
import type { createPortableBindings } from '../lib/portableBinding'
import { readBookGlossarySnapshotsFromDocument } from '../lib/portableBookGlossary'
import { glossaryAdoptionErrors, type PortableGlossaryAdoptionReview } from '../lib/portableGlossaryAdoption'
import { glossaryExportErrorMessage } from '../lib/portableGlossaryExport'
import { AdaptiveCollection } from './AdaptiveCollection'
import { AccountGlossaryEditor } from './BookGlossaryWorkspace'
import { usePortableGlossaryAdoption } from './BookGlossaryProvider'
import './readingTools.css'

type Field = { id: string; label: string; value: string }
type Side = 'snapshot' | 'server' | 'device'
const presenceText = (presence: 'absent' | 'deleted' | 'present') => t(presence === 'absent' ? '미등록' : presence === 'deleted' ? '삭제됨' : '저장된 용어집')
/** Every code point and outer space remains inspectable in bounded, selectable fields. */
export function glossaryAdoptionFields(review: PortableGlossaryAdoptionReview, side: Side, destination?: { username: string; origin: string }): Field[] {
  const value: BookGlossary = side === 'snapshot' ? review.snapshot.entries : side === 'server' ? review.server.entries : review.device?.local ?? null
  const presence = side === 'snapshot' ? presenceText(review.snapshot.presence) : side === 'server'
    ? presenceText(review.server.version === 0 ? 'absent' : value === null ? 'deleted' : 'present')
    : review.device === null ? t('기기 계정 기록 없음') : value === null
      ? review.device.remote?.version === 0 ? t('미등록') : review.device.remote && review.device.remote.entries === null ? t('삭제됨') : t('기기 용어집 없음')
      : t('저장된 용어집')
  const fields = [
    ...(destination ? [{ label: t('변경할 계정'), value: destination.username }, { label: t('계정 서버'), value: destination.origin }] : []),
    { label: t('소스'), value: review.scope.providerId }, { label: t('책 식별자'), value: review.scope.bookId }, { label: t('번역 언어'), value: review.scope.targetLanguage },
    { label: t('스냅샷 상태'), value: `${presence} · ${value?.length ?? 0} ${t('항목')}` },
    ...(side === 'device' && review.device ? [{ label: t('계정 용어집 사용'), value: t(review.device.selected ? '사용 중' : '사용 안 함') }] : []),
    ...(value ?? []).flatMap((entry, index) => [
      { label: `${index + 1} · ${t('용어 식별자')}`, value: entry.id }, { label: `${index + 1} · ${t('원문 용어')}`, value: entry.sourceTerm },
      { label: `${index + 1} · ${t('번역 표기')}`, value: entry.translatedTerm }, { label: `${index + 1} · ${t('읽기 표시 이름')}`, value: entry.displayTerm },
      { label: `${index + 1} · ${t('용어 종류')}`, value: entry.kind },
      { label: `${index + 1} · ${t('표시·적용 설정')}`, value: `${t(entry.caseSensitive ? '대소문자 구분' : '대소문자 무시')} · ${t(entry.enabled ? '용어 적용 켜짐' : '용어 적용 꺼짐')}` },
    ]),
  ]
  return fields.flatMap((field, index) => {
    const codepoints = Array.from(field.value), count = Math.max(1, Math.ceil(codepoints.length / 32))
    return Array.from({ length: count }, (_, part) => ({ id: `${side}:${index}:${part}`, label: `${field.label}${count > 1 ? ` · ${part + 1}/${count}` : ''}`, value: codepoints.slice(part * 32, (part + 1) * 32).join('') }))
  })
}

export function PortableGlossaryAdoptionPanel({ book, username, identityClient, bindings, onClose }: {
  book: SavedExchange; username: string; identityClient: LibraryIdentityClient | null
  bindings: ReturnType<typeof createPortableBindings>; onClose: () => void
}) {
  const account = usePortableGlossaryAdoption(username), [storage, setStorage] = useState<ReturnType<typeof createPersonalLibrary> | null>(null)
  useLayoutEffect(() => { const next = createPersonalLibrary(username); setStorage(next); return () => next.close() }, [username])
  const snapshots = useMemo(() => { try { return { value: readBookGlossarySnapshotsFromDocument(book.document)?.snapshots ?? [], error: '' } }
    catch { return { value: [], error: glossaryAdoptionErrors.snapshot } } }, [book])
  const [scope, setScope] = useState<BookGlossaryScope>(), [review, setReview] = useState<PortableGlossaryAdoptionReview>()
  const [panel, setPanel] = useState<'choose' | 'compare' | 'confirm' | 'queued'>('choose'), [side, setSide] = useState<Side>('snapshot')
  const [error, setError] = useState(''), [busy, setBusy] = useState(false), [editor, setEditor] = useState(false)
  const running = useRef(false), operation = useRef(0), request = useRef<AbortController | null>(null)
  const context = useMemo(() => ({ username, identityClient, registry: account.session, book }), [username, identityClient, account.session, book])
  const latest = useRef(context); latest.current = context
  useLayoutEffect(() => {
    operation.current++; request.current?.abort(); running.current = false; setBusy(false); setReview(undefined); setPanel('choose'); setEditor(false)
    return () => { operation.current++; request.current?.abort() }
  }, [context])
  const prepare = async (next: BookGlossaryScope) => {
    if (running.current) return
    const id = ++operation.current, started = context, controller = new AbortController()
    request.current?.abort(); request.current = controller; running.current = true; setBusy(true); setError(''); setScope(next); setReview(undefined)
    const current = () => id === operation.current && latest.current === started && !controller.signal.aborted
    try {
      const result = await account.begin().prepare(book, next, identityClient, bindings, controller.signal)
      if (current()) { setReview(result); setPanel('compare'); setSide('snapshot') }
    } catch (failure) { if (current()) setError(glossaryExportErrorMessage(failure)) }
    finally { if (current()) { running.current = false; setBusy(false) } }
  }
  const confirm = async () => {
    if (running.current || !review) return
    const id = operation.current, started = context
    const current = () => id === operation.current && latest.current === started && !request.current?.signal.aborted
    running.current = true; setBusy(true); setError('')
    try { await review.confirm(request.current?.signal); if (current()) { setPanel('queued'); setReview(undefined) } }
    catch (failure) { if (current()) { setError(glossaryExportErrorMessage(failure)); setReview(undefined); setPanel('choose') } }
    finally { if (current()) { running.current = false; setBusy(false) } }
  }
  const openEditor = async () => {
    if (!scope || running.current) return
    const id = operation.current, started = context; setBusy(true); running.current = true
    try {
      const bound = await bindings.open(book, identityClient, request.current?.signal)
      if (id !== operation.current || latest.current !== started) return
      if (!bound) throw new Error(glossaryAdoptionErrors.binding)
      setEditor(true)
    } catch (failure) { if (id === operation.current && latest.current === started) setError(glossaryExportErrorMessage(failure)) }
    finally { if (id === operation.current && latest.current === started) { setBusy(false); running.current = false } }
  }
  const fields = useMemo(() => review ? glossaryAdoptionFields(review, side, { username, origin: window.location.origin }) : [], [review, side, username])
  return <section className="reading-workspace" aria-label={t('ZIP 계정 용어집 채택')}>
    <header className="reading-tools-header"><strong>{t(panel === 'confirm' ? '계정 변경 최종 확인' : 'ZIP 계정 용어집 채택')}</strong><button disabled={busy} onClick={onClose}>{t('닫기')}</button></header>
    {error && <div className="workflow-message" role="alert">{t(error)}<button onClick={() => setError('')}>{t('닫기')}</button></div>}
    {snapshots.error && <p className="reading-tools-caption" role="alert">{t(snapshots.error)}</p>}
    {(!account.available || !identityClient) && <p className="reading-tools-caption">{t(glossaryAdoptionErrors.offline)}</p>}
    {busy && <p className="reading-tools-caption" role="status">{t('계정 용어집을 확인하고 있습니다.')}</p>}
    {panel === 'queued' ? <>
      <AdaptiveCollection items={['queued', 'unchanged']} itemKey={v => v} rowHeight={144} renderItem={value => <div className="reading-field"><p>{t(value === 'queued' ? '채택 요청을 기기에 안전하게 보관했습니다. 동기화 상태를 확인하고 충돌이 있으면 비교해 해결해 주세요.' : 'ZIP 원본과 다른 언어의 용어집은 변경하지 않았습니다.')}</p></div>}/>
      <div className="reading-filter-actions"><button disabled={busy} onClick={() => void openEditor()}>{t('동기화 상태·충돌 확인')}</button></div>
    </> : review ? <>
      <div className="reading-tool-choice"><select aria-label={t('비교할 용어집')} value={side} disabled={busy} onChange={event => setSide(event.target.value as Side)}><option value="snapshot">{t('ZIP의 값')}</option><option value="server">{t('조회한 서버 값')}</option><option value="device">{t('기기의 계정 기록')}</option></select></div>
      <AdaptiveCollection key={side} items={fields} itemKey={v => v.id} rowHeight={144} renderItem={field => <label className="reading-field glossary-adoption-field"><strong>{field.label}</strong><textarea readOnly aria-label={field.label} value={field.value} spellCheck={false}/></label>}/>
      {review.snapshot.presence === 'absent' && <p className="reading-tools-caption">{t(glossaryAdoptionErrors.absent)}</p>}
      <div className="reading-filter-actions"><button disabled={busy} onClick={() => { if (panel === 'confirm') setPanel('compare'); else { setReview(undefined); setPanel('choose') } }}>{t('이전')}</button>
        {review.snapshot.presence !== 'absent' && <button disabled={busy} onClick={() => { if (panel === 'confirm') void confirm(); else { setPanel('confirm'); setSide('snapshot') } }}>{t(panel === 'confirm' ? review.snapshot.presence === 'deleted' ? '계정 용어집 삭제 확정' : '이 ZIP 값으로 계정 변경 확정' : '채택할 내용 최종 확인')}</button>}</div>
    </> : snapshots.value.length ? <>
      <AdaptiveCollection items={snapshots.value} itemKey={bookGlossaryKey} rowHeight={144} renderItem={value => <div className="reading-field"><strong>{value.targetLanguage} · {presenceText(value.presence)} · {value.entries?.length ?? 0} {t('항목')}</strong><button disabled={busy || !account.available || !identityClient} onClick={() => void prepare({ providerId: value.providerId, bookId: value.bookId, targetLanguage: value.targetLanguage })}>{t('원본 확인 후 현재 값과 비교')}</button></div>}/>
      {scope && error === glossaryAdoptionErrors.pending && <div className="reading-filter-actions"><button disabled={busy} onClick={() => void openEditor()}>{t('동기화 상태·충돌 확인')}</button></div>}
    </> : <p className="reading-tools-caption">{t(glossaryAdoptionErrors.snapshot)}</p>}
    {editor && scope && storage && <AccountGlossaryEditor username={username} storage={storage} providerId={scope.providerId} bookId={scope.bookId} targetLanguage={scope.targetLanguage} scope={scope} onChange={() => {}} onClose={() => setEditor(false)}/>}
  </section>
}
