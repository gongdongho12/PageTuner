import { useEffect, useId, useLayoutEffect, useRef, useState } from 'react'
import { translate as t } from '../lib/locale'
import type { GlossaryEntry, PersonalLibrary } from '../lib/personalLibrary'
import { bookGlossaryScope } from '../lib/bookGlossaryProjection'
import type { BookGlossary, BookGlossaryEntry, BookGlossaryScope } from '../lib/bookGlossaryApi'
import type { BookGlossaryState } from '../lib/bookGlossarySync'
import { useBookGlossary } from './BookGlossaryProvider'
import { GlossaryEditor } from './GlossaryEditor'
import { AdaptiveCollection } from './AdaptiveCollection'
import './bookGlossary.css'

export function glossarySyncNotice(state?: BookGlossaryState): string {
  if (!state || state.status === 'loading') return '계정 용어집을 확인하고 있습니다.'
  if (state.errorCode === 'storage') return '용어집을 기기에 보관하지 못했습니다. 저장 공간을 확인해 주세요.'
  if (state.errorCode === 'choice-stale') return '용어집이 바뀌었습니다. 최신 내용을 확인한 뒤 다시 선택해 주세요.'
  if (state.errorCode === 'invalid-request') return '계정 용어의 입력값 또는 전체 크기를 확인해 주세요. 이전 용어집은 유지됩니다.'
  if (state.errorCode === 'exhausted') return '용어집의 저장 한도에 도달했습니다. 서버 값을 선택해 주세요.'
  if (state.errorCode === 'authentication' || state.errorCode === 'forbidden') return '계정 용어집을 동기화하려면 다시 로그인해 주세요.'
  if (state.errorCode) return '용어집 연결을 확인해 주세요. 기기에 보관한 변경은 유지됩니다.'
  if (state.status === 'conflict') return '다른 기기에서 용어집이 변경되었습니다. 전체 내용을 비교해 선택해 주세요.'
  return state.status === 'unlinked' ? '이 책과 번역 언어에 사용할 용어집을 직접 선택해 주세요.' : state.status === 'pending' ? '용어집 · 저장 대기' : '용어집 · 동기화됨'
}
type Props = { username: string; storage: PersonalLibrary; providerId: string; bookId: string; targetLanguage: string; onChange: (entries: GlossaryEntry[]) => void }
export function BookGlossaryWorkspace(props: Props) {
  const scope = bookGlossaryScope(props.providerId, props.bookId, props.targetLanguage)
  const account = useBookGlossary(props.username, scope)
  const [mode, setMode] = useState<'local' | 'account'>(scope ? 'account' : 'local'), [open, setOpen] = useState(false)
  return <section className="novel-workspace">
    <nav className="workflow-subtabs"><button aria-pressed={mode === 'local'} onClick={() => setMode('local')}>{t('기기 용어집')}</button><button disabled={!scope} aria-pressed={mode === 'account'} onClick={() => setMode('account')}>{t('계정 용어집')}</button></nav>
    {mode === 'local' ? <>{scope && <button className="button-outline" disabled={!account.controller || !account.state?.enabled} onClick={() => void account.controller?.selectDevice()}>{t('기기 용어집 사용')}</button>}<GlossaryEditor storage={props.storage} providerId={props.providerId} bookId={props.bookId} onChange={props.onChange}/></> : <div className="workflow-about">
      <p>{t('계정 용어집은 원본 책 식별자와 번역 언어별로 동기화됩니다. 기기 용어집은 직접 채택할 때만 복사합니다.')}</p>
      <button className="button-outline" onClick={() => setOpen(true)}>{t('계정 용어집 열기')} · {scope?.targetLanguage}</button>
    </div>}
    {open && scope && <AccountGlossaryEditor key={JSON.stringify(scope)} {...props} scope={scope} onClose={() => setOpen(false)}/>}
  </section>
}
function comparisonFields(scope: BookGlossaryScope, entries: BookGlossary) {
  const fields = [{ label: '소스', value: scope.providerId }, { label: '책 식별자', value: scope.bookId }, { label: '번역 언어', value: scope.targetLanguage },
    ...(entries === null ? [{ label: '용어집', value: t('삭제됨') }] : entries.length ? entries.flatMap((entry, index) => [
      { label: `${index + 1} · ${t('용어 식별자')}`, value: entry.id }, { label: t('원문 용어'), value: entry.sourceTerm }, { label: t('번역 표기'), value: entry.translatedTerm },
      { label: t('읽기 표시 이름'), value: entry.displayTerm || t('없음') }, { label: t('용어 종류'), value: t(entry.kind === 'Character' ? '인물' : entry.kind === 'Place' ? '장소' : '일반 용어') },
      { label: t('표시·적용 설정'), value: `${t(entry.caseSensitive ? '대소문자 구분' : '대소문자 무시')} · ${t(entry.enabled ? '용어 적용 켜짐' : '용어 적용 꺼짐')}` },
    ]) : [{ label: '용어집', value: t('아직 저장한 용어가 없습니다.') }])]
  return fields.flatMap((field, index) => (field.value.match(/[\s\S]{1,48}/gu) ?? ['']).map((value, part) => ({ id: `${index}:${part}`, label: field.label, value })))
}
function AccountGlossaryEditor({ username, storage, scope, onClose }: Props & { scope: BookGlossaryScope; onClose: () => void }) {
  const { state, controller } = useBookGlossary(username, scope)
  const dialog = useRef<HTMLDialogElement>(null), titleId = useId()
  const [panel, setPanel] = useState<'list' | 'compare' | 'edit'>('list'), [side, setSide] = useState<'local' | 'server' | 'legacy'>('server')
  const [legacy, setLegacy] = useState<GlossaryEntry[]>(), [error, setError] = useState(''), [busy, setBusy] = useState(false)
  const [review, setReview] = useState<BookGlossaryState>(), [draft, setDraft] = useState<BookGlossaryEntry>(), [step, setStep] = useState(0)
  useLayoutEffect(() => { const node = dialog.current!; node.showModal(); return () => { if (node.open) node.close() } }, [])
  useEffect(() => { let active = true; void storage.getGlossary(scope.providerId, scope.bookId).then(value => { if (active) setLegacy(value.entries) }).catch(() => { if (active) setError('기기 용어집을 불러오지 못했습니다.') }); return () => { active = false } }, [storage, scope.providerId, scope.bookId])
  const current = state?.linked ? state.local : state?.remote?.entries ?? null
  async function act(work: () => Promise<unknown>) { setBusy(true); setError(''); try { const accepted = await work(); if (accepted !== false) setPanel('list') } catch (e) { setError(e instanceof Error ? e.message : '') } finally { setBusy(false) } }
  function compare() { if (state) { setReview(structuredClone(state)); setSide(state.status === 'conflict' ? 'local' : 'server'); setPanel('compare') } }
  function edit(entry?: BookGlossaryEntry) { if (!state?.enabled) return; setReview(structuredClone(state)); setDraft(entry ? structuredClone(entry) : { id: crypto.randomUUID(), sourceTerm: '', translatedTerm: '', displayTerm: '', kind: 'Character', caseSensitive: false, enabled: true }); setStep(0); setPanel('edit') }
  const notice = error || glossarySyncNotice(state)
  const comparison = side === 'server' ? review?.remote?.entries ?? null : side === 'local' ? review?.local ?? null : legacy?.map((entry, index) => ({ id: `${t('채택 시 식별자 생성')} ${index + 1}`, sourceTerm: entry.source, translatedTerm: entry.target, displayTerm: entry.displayTerm ?? '', kind: entry.kind ?? 'Character', caseSensitive: entry.caseSensitive ?? false, enabled: entry.enabled ?? true })) ?? null
  return <dialog ref={dialog} className="book-glossary-dialog" aria-labelledby={titleId} onCancel={event => { event.preventDefault(); onClose() }}>
    <header className="book-glossary-toolbar"><button autoFocus onClick={onClose}>{t('닫기')}</button><strong id={titleId}>{t('계정 용어집')} · {scope.targetLanguage}</strong>
      <button disabled={busy} onClick={() => setPanel('list')}>{t('용어 목록')}</button><button disabled={busy || !state} onClick={compare}>{t('비교·동기화')}</button></header>
    <p className="book-glossary-notice" role={error || state?.errorCode ? 'alert' : 'status'} title={t(notice)}>{t(notice)}</p>
    {panel === 'edit' && draft && review ? <form className="book-glossary-edit" onSubmit={event => { event.preventDefault(); const entries = [...(review.local ?? [])], at = entries.findIndex(entry => entry.id === draft.id); if (at < 0) entries.push(draft); else entries[at] = draft; void act(() => controller!.update(entries, review)) }}>
      <div className="book-glossary-edit-field">
        {step < 3 ? <label>{t(['원문 용어', '번역 표기', '읽기 표시 이름'][step])}<input value={draft[(['sourceTerm', 'translatedTerm', 'displayTerm'] as const)[step]]} maxLength={200} disabled={busy} onChange={event => setDraft({ ...draft, [(['sourceTerm', 'translatedTerm', 'displayTerm'] as const)[step]]: event.target.value })}/></label>
          : step === 3 ? <label>{t('용어 종류')}<select value={draft.kind} disabled={busy} onChange={event => setDraft({ ...draft, kind: event.target.value as BookGlossaryEntry['kind'] })}><option value="Character">{t('인물')}</option><option value="Place">{t('장소')}</option><option value="Term">{t('일반 용어')}</option></select></label>
            : <div className="book-glossary-actions"><button type="button" aria-pressed={draft.caseSensitive} onClick={() => setDraft({ ...draft, caseSensitive: !draft.caseSensitive })}>{t(draft.caseSensitive ? '대소문자 구분' : '대소문자 무시')}</button><button type="button" aria-pressed={draft.enabled} onClick={() => setDraft({ ...draft, enabled: !draft.enabled })}>{t(draft.enabled ? '용어 적용 켜짐' : '용어 적용 꺼짐')}</button></div>}
      </div>
      <footer className="book-glossary-actions"><button type="button" disabled={busy || step === 0} onClick={() => setStep(step - 1)}>{t('이전')}</button><span>{step + 1}/5</span><button type="button" disabled={busy || step === 4} onClick={() => setStep(step + 1)}>{t('다음')}</button><button disabled={busy || !controller || !draft.sourceTerm.trim() || !draft.translatedTerm.trim()}>{t('저장')}</button></footer>
    </form> : panel === 'compare' && review ? <>
      <nav className="book-glossary-actions"><button aria-pressed={side === 'local'} onClick={() => setSide('local')}>{t('이 기기의 값')}</button><button aria-pressed={side === 'server'} onClick={() => setSide('server')}>{t('서버의 값')}</button><button aria-pressed={side === 'legacy'} onClick={() => setSide('legacy')}>{t('기기 용어집')}</button></nav>
      <AdaptiveCollection items={comparisonFields(scope, comparison)} itemKey={item => item.id} rowHeight={108} renderItem={item => <div className="book-glossary-field"><strong>{t(item.label)}</strong><p>{item.value}</p></div>}/>
      <footer className="book-glossary-actions">
        {review.status === 'conflict' ? <><button disabled={busy || !controller} onClick={() => void act(() => controller!.choose('local', review))}>{t('기기 값 사용')}</button><button disabled={busy || !controller} onClick={() => void act(() => controller!.choose('server', review))}>{t('서버 값 사용')}</button></>
          : <>{!review.enabled && <button disabled={busy || !review.remote || !controller} onClick={() => void act(() => controller!.choose('server', review))}>{t('계정 용어집 사용')}</button>}<button disabled={busy || !review.remote || !legacy || !controller} onClick={() => void act(() => controller!.adoptLegacy(legacy!, review))}>{t('기기 용어집 계정에 채택')}</button></>}
      </footer>
    </> : <>
      {current?.length ? <AdaptiveCollection items={current} itemKey={entry => entry.id} rowHeight={104} renderItem={entry => <div className="book-glossary-row"><button className="workflow-row-copy" disabled={!state?.enabled || busy} onClick={() => edit(entry)}><strong>{entry.sourceTerm}</strong><span>{entry.translatedTerm}</span><span>{entry.displayTerm}</span></button><button disabled={!state?.enabled || busy || !controller} onClick={() => void act(() => controller!.update(current!.filter(item => item.id !== entry.id), state))}>{t('삭제')}</button></div>}/>
        : <div className="book-glossary-empty">{t(current === null ? '삭제됨' : '아직 저장한 용어가 없습니다.')}</div>}
      <footer className="book-glossary-actions"><button disabled={busy || !state?.enabled} onClick={() => edit()}>{t('용어 추가')}</button><button disabled={busy || !state?.enabled || current === null || !controller} onClick={() => void act(() => controller!.update(null, state))}>{t('계정 용어집 삭제')}</button><button disabled={busy || !controller} onClick={() => void controller?.refresh()}>{t('새로고침')}</button></footer>
    </>}
  </dialog>
}
