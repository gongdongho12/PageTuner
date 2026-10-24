import { useLayoutEffect, useMemo, useRef, useState } from 'react'
import { translate as t } from '../lib/locale'
import { createExchangeLibrary } from '../lib/exchangeLibrary'
import type { LibraryIdentityClient } from '../lib/libraryIdentityApi'
import type { createPortableBindings } from '../lib/portableBinding'
import {
  createPortableOrganizationAdoption, organizationAdoptionErrorMessage, organizationAdoptionErrors,
  type OrganizationAdoptionSession, type PortableOrganizationAdoptionReview,
} from '../lib/portableOrganizationAdoption'
import { AdaptiveCollection } from './AdaptiveCollection'
import { usePortableOrganizationAdoption } from './LibraryOrganizationProvider'
import { useExportSession } from './useExportSession'
import './readingTools.css'

type Side = 'snapshot' | 'server' | 'device'
type Field = { id: string; label: string; value: string }

/** Keep every ordered tag and code point inspectable, including whitespace and Unicode forms. */
export function organizationAdoptionFields(review: PortableOrganizationAdoptionReview, side: Side, destination: { username: string; origin: string }): Field[] {
  const value = side === 'snapshot' ? review.snapshot.organization : side === 'server' ? review.server.organization : review.device?.local ?? null
  const empty = value !== null && value.folder === '' && value.tags.length === 0 && !value.favorite
  const presence = side === 'device' && review.device === null ? t('기기 계정 기록 없음')
    : value === null ? t('미등록') : t(empty ? '명시적 빈 분류' : '저장된 분류')
  const fields = [
    { label: t('변경할 계정'), value: destination.username }, { label: t('계정 서버'), value: destination.origin },
    { label: t('문서 종류'), value: t(review.scope.kind === 'ORIGINAL' ? '원문' : '번역본') },
    { label: t('계정 문서 식별자'), value: review.scope.recordId },
    { label: t('스냅샷 상태'), value: presence },
    ...(side === 'server' ? [{ label: t('서버 버전'), value: String(review.server.version) }] : []),
    ...(side === 'device' && review.device ? [{ label: t('계정 분류 사용'), value: t(review.device.enabled ? '사용 중' : '사용 안 함') }] : []),
    ...(value === null ? [] : [
      { label: t('폴더'), value: value.folder }, { label: t('태그 수'), value: String(value.tags.length) },
      ...value.tags.map((tag, index) => ({ label: `${index + 1} · ${t('태그')}`, value: tag })),
      { label: t('즐겨찾기'), value: t(value.favorite ? '켜짐' : '꺼짐') },
    ]),
  ]
  return fields.flatMap((field, index) => {
    const codepoints = Array.from(field.value), count = Math.max(1, Math.ceil(codepoints.length / 20))
    return Array.from({ length: count }, (_, part) => ({ id: `${side}:${index}:${part}`,
      label: `${field.label}${count > 1 ? ` · ${part + 1}/${count}` : ''}`,
      value: codepoints.slice(part * 20, (part + 1) * 20).join('') }))
  })
}

export function PortableOrganizationAdoptionPanel({ copyId, username, identityClient, bindings, onClose }: {
  copyId: string; username: string; identityClient: LibraryIdentityClient | null
  bindings: ReturnType<typeof createPortableBindings>; onClose: () => void
}) {
  const account = usePortableOrganizationAdoption(username)
  const library = useMemo(() => createExchangeLibrary(username), [username])
  const owner = useMemo(() => ({ username, identityClient, bindings, registry: account.session, copyId }), [username, identityClient, bindings, account.session, copyId])
  const origin = window.location.origin, lease = useExportSession(owner, origin)
  const [review, setReview] = useState<PortableOrganizationAdoptionReview>()
  const [page, setPage] = useState<'prepare' | 'compare' | 'confirm' | 'queued'>('prepare'), [side, setSide] = useState<Side>('snapshot')
  const [error, setError] = useState(''), [busy, setBusy] = useState(false)
  const running = useRef(false), operation = useRef(0), request = useRef<AbortController | null>(null)
  const activeReview = useRef<PortableOrganizationAdoptionReview | undefined>(undefined), activeSession = useRef<OrganizationAdoptionSession | undefined>(undefined)
  const cancelOperation = () => {
    operation.current++; request.current?.abort(); request.current = null
    activeReview.current?.close(); activeReview.current = undefined
    activeSession.current?.close(); activeSession.current = undefined
    running.current = false
  }
  useLayoutEffect(() => {
    cancelOperation(); setBusy(false); setReview(undefined); setPage('prepare'); setError(''); setSide('snapshot')
    return cancelOperation
  }, [owner, origin])
  const cancel = () => { cancelOperation(); setBusy(false); setReview(undefined); setPage('prepare'); setError('') }
  const close = () => { cancelOperation(); onClose() }
  const prepare = async () => {
    // This exact committed lease belongs to this callback. It can never issue for a later mount/account.
    if (running.current || !lease?.current() || !account.available || !identityClient) return
    const selected = lease.issue(), id = ++operation.current, controller = new AbortController()
    request.current = controller; running.current = true; setBusy(true); setError('')
    const current = () => selected() && operation.current === id && !controller.signal.aborted
    try {
      const session = account.begin(current); activeSession.current = session
      const result = await createPortableOrganizationAdoption({ session, library, bindings, identityClient, current }).prepare(copyId, controller.signal)
      if (!current()) { result.close(); return }
      activeReview.current = result; setReview(result); setPage('compare'); setSide('snapshot')
    } catch (failure) {
      if (current()) { activeSession.current?.close(); activeSession.current = undefined; setError(organizationAdoptionErrorMessage(failure)) }
    } finally { if (current()) { running.current = false; setBusy(false) } }
  }
  const confirm = async () => {
    if (running.current || !lease?.current() || !review || review !== activeReview.current || page !== 'confirm' || review.snapshot.presence === 'absent') return
    const selected = lease.issue(), id = operation.current, accepted = review, controller = request.current
    const current = () => selected() && operation.current === id && !controller?.signal.aborted
    running.current = true; setBusy(true); setError('')
    try {
      await accepted.confirm(controller?.signal)
      // The intent may already be durable. A stale/closed screen suppresses UI only; it never reports rollback.
      if (current()) { activeReview.current = undefined; activeSession.current = undefined; setReview(undefined); setPage('queued') }
    } catch (failure) {
      if (current()) { accepted.close(); activeReview.current = undefined; activeSession.current = undefined; setReview(undefined); setPage('prepare'); setError(organizationAdoptionErrorMessage(failure)) }
    } finally { if (current()) { running.current = false; setBusy(false) } }
  }
  const fields = useMemo(() => review ? organizationAdoptionFields(review, side, { username, origin }) : [], [review, side, username, origin])
  const explanation = page === 'queued'
    ? ['채택 의도를 이 기기에 안전하게 보관했습니다. 서버 반영 완료를 뜻하지 않습니다.', '계정 분류 화면에서 동기화 상태와 충돌을 확인해 주세요. ZIP 원본은 바꾸지 않았습니다.']
    : ['ZIP의 폴더·태그·즐겨찾기를 현재 계정과 비교합니다. 비교만으로 계정 기록을 바꾸지 않습니다.', '계정 변경은 비교 후 별도 확정이 필요합니다. 미등록은 정보만 표시하고, 명시적 빈 분류는 계정 값을 비웁니다.']
  return <section className="reading-workspace glossary-adoption-panel" aria-label={t('ZIP 분류 채택')}>
    <header className="reading-tools-header"><strong>{t(page === 'confirm' ? '계정 변경 최종 확인' : 'ZIP 분류 채택')}</strong><button onClick={close}>{t('취소·닫기')}</button></header>
    {error && <div className="workflow-message" role="alert">{t(error)}<button onClick={() => setError('')}>{t('닫기')}</button></div>}
    {busy ? <>
      <AdaptiveCollection items={['계정 분류와 문서를 확인하고 있습니다.']} itemKey={v => v} rowHeight={132} renderItem={value => <div className="reading-field" role="status"><p>{t(value)}</p></div>}/>
      <div className="reading-filter-actions"><button onClick={cancel}>{t('취소')}</button></div>
    </> : review ? <>
      <div className="reading-tool-choice"><select aria-label={t('비교할 분류')} value={side} onChange={event => setSide(event.target.value as Side)}><option value="snapshot">{t('ZIP의 값')}</option><option value="server">{t('조회한 서버 값')}</option><option value="device">{t('기기의 계정 기록')}</option></select></div>
      <AdaptiveCollection key={`${page}:${side}`} items={fields} itemKey={v => v.id} rowHeight={132} renderItem={field => <label className="reading-field glossary-adoption-field"><strong>{field.label}</strong><textarea readOnly aria-label={field.label} value={field.value} spellCheck={false}/></label>}/>
      <div className="reading-filter-actions"><button onClick={() => { if (page === 'confirm') setPage('compare'); else cancel() }}>{t('이전')}</button>
        {review.snapshot.presence === 'present' && <button onClick={() => { if (page === 'confirm') void confirm(); else { setPage('confirm'); setSide('snapshot') } }}>{t(page === 'confirm' ? 'ZIP 분류 채택 확정' : '채택 확인')}</button>}</div>
      {review.snapshot.presence === 'absent' && <p className="reading-tools-caption">{t(organizationAdoptionErrors.absent)}</p>}
    </> : <>
      <AdaptiveCollection items={explanation} itemKey={v => v} rowHeight={144} renderItem={value => <div className="reading-field"><p>{t(value)}</p></div>}/>
      {page === 'prepare' && <div className="reading-filter-actions"><button disabled={!lease || !account.available || !identityClient} onClick={() => void prepare()}>{t('원본 확인 후 비교')}</button></div>}
      {page === 'prepare' && (!account.available || !identityClient) && <p className="reading-tools-caption">{t(organizationAdoptionErrors.offline)}</p>}
    </>}
  </section>
}
