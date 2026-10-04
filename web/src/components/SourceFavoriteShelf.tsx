import { useId, useLayoutEffect, useRef, useState } from 'react'
import { translate as t } from '../lib/locale'
import type { FavoriteBook } from '../lib/personalLibrary'
import { sourceBookKey, validateSourceFavoriteBook, validateSourceBookIdentity, type SourceBookIdentity, type SourceFavoriteBook } from '../lib/sourceFavoriteApi'
import { favoriteRowChange, type SourceFavoriteRow } from '../lib/sourceFavoriteStore'
import { useSourceFavorites } from './SourceFavoriteProvider'
import { AdaptiveCollection } from './AdaptiveCollection'
import './sourceFavorites.css'

const errors: Record<string, string> = {
  authentication: '즐겨찾기를 동기화하려면 다시 로그인해 주세요.', forbidden: '즐겨찾기를 저장할 권한을 확인할 수 없습니다.',
  network: '연결되면 즐겨찾기를 전송합니다. 변경 사항은 기기에 남아 있습니다.', timeout: '연결되면 즐겨찾기를 전송합니다. 변경 사항은 기기에 남아 있습니다.',
  storage: '즐겨찾기 변경을 기기에 보관하지 못했습니다. 저장 공간을 확인해 주세요.', 'invalid-response': '서버의 즐겨찾기 응답을 확인할 수 없습니다.',
  'invalid-request': '책 정보가 동기화 규격에 맞지 않습니다. 기기 즐겨찾기는 유지됩니다.', 'choice-stale': '즐겨찾기가 바뀌었습니다. 최신 내용을 확인한 뒤 다시 선택해 주세요.',
  limit: '동기화 요청이 많습니다. 잠시 후 다시 시도해 주세요.', server: '서버에서 즐겨찾기를 저장하지 못했습니다.',
  'mutation-reused': '즐겨찾기 변경 요청을 확인할 수 없습니다.', exhausted: '즐겨찾기의 저장 한도에 도달했습니다.',
}
function metadata(input: FavoriteBook): SourceFavoriteBook {
  // Only display metadata is canonicalized. Source identities remain the original exact pair.
  return validateSourceFavoriteBook({ title: input.title.trim(), authors: [], language: 'auto', url: new URL(input.url).href })
}
export function SourceFavoriteShelf({ onOpenBook, adoption, onBack }: { onOpenBook: (url: string) => void; adoption?: FavoriteBook; onBack?: () => void }) {
  const session = useSourceFavorites(), state = session?.state
  const [selected, setSelected] = useState<SourceBookIdentity>(), [busy, setBusy] = useState(false), [error, setError] = useState('')
  const [side, setSide] = useState<'local' | 'server'>('local')
  const identity = adoption ? { providerId: adoption.sourceId, bookId: adoption.bookId } : selected
  const detailDialog = useRef<HTMLDialogElement>(null), detailTitle = useId()
  const identityKey = identity ? sourceBookKey(identity) : undefined
  useLayoutEffect(() => {
    const dialog = detailDialog.current
    if (!dialog || !identityKey) return
    dialog.showModal()
    return () => { if (dialog.open) dialog.close() }
  }, [identityKey])
  const closeDetail = () => { setSelected(undefined); onBack?.() }
  const selectedRow = identity ? state?.rows[sourceBookKey(identity)] ?? null : null
  const local = selectedRow ? favoriteRowChange(selectedRow) : null
  let candidate: SourceFavoriteBook | undefined, invalidCandidate = false
  if (adoption) { try { validateSourceBookIdentity(identity); candidate = metadata(adoption) } catch { invalidCandidate = true } }
  const rows = state ? Object.entries(state.rows).filter(([, row]) => !favoriteRowChange(row)?.deleted || row.pending || row.conflict).sort(([, a], [, b]) => (b.remote?.changeRevision ?? 0) - (a.remote?.changeRevision ?? 0)) : []
  const status = state?.errorCode ? errors[state.errorCode] ?? '즐겨찾기를 동기화하지 못했습니다.' : state?.loading ? '계정 즐겨찾기를 확인하고 있습니다.' : !state?.ready ? '서버 목록을 확인한 뒤 계정에 추가할 수 있습니다.' : Object.values(state.rows).some(row => row.conflict) ? '다른 기기에서 즐겨찾기가 변경되었습니다. 사용할 값을 선택해 주세요.' : Object.values(state.rows).some(row => row.pending) ? '즐겨찾기 · 저장 대기' : '즐겨찾기 · 동기화됨'
  async function act(work: () => Promise<boolean>) { setBusy(true); setError(''); try { if (await work()) { setSelected(undefined); onBack?.(); void session?.controller.drain() } } finally { setBusy(false) } }
  const notice = invalidCandidate ? '책 정보가 동기화 규격에 맞지 않습니다. 기기 즐겨찾기는 유지됩니다.' : error || status
  if (identity) {
    const book = side === 'server' ? selectedRow?.remote?.book : selectedRow?.conflict ? local?.book : candidate ?? local?.book
    const content = [{ label: '제목', value: book?.title ?? t('삭제됨') }, { label: '소스', value: identity.providerId }, { label: '책 식별자', value: identity.bookId }, { label: '주소', value: book?.url ?? '' }, { label: '저자', value: book?.authors.join(' · ') || t('없음') }, { label: '언어', value: book?.language ?? '' }]
      .flatMap((field, index) => (Array.from(field.value).join('').match(/[\s\S]{1,48}/gu) ?? ['']).map((value, part) => ({ id: `${index}:${part}`, label: field.label, value })))
    return <dialog ref={detailDialog} className="novel-workspace source-favorites source-favorites-dialog" aria-labelledby={detailTitle} onCancel={event => { event.preventDefault(); closeDetail() }}>
      <div className="source-favorite-detail-header">
        <header className="source-favorite-toolbar"><button autoFocus onClick={closeDetail}>{t('돌아가기')}</button><strong id={detailTitle}>{t(adoption ? '계정에 추가' : '즐겨찾기 관리')}</strong></header>
        <nav className="workflow-subtabs source-favorite-value-tabs"><button aria-pressed={side === 'local'} onClick={() => setSide('local')}>{t('이 기기의 값')}</button><button aria-pressed={side === 'server'} onClick={() => setSide('server')}>{t('서버의 값')}</button></nav>
      </div>
      <p className="source-favorite-notice" role={state?.errorCode || invalidCandidate ? 'alert' : 'status'} title={t(notice)}>{t(notice)}</p>
      <AdaptiveCollection items={content} itemKey={item => item.id} rowHeight={108} renderItem={item => <div className="source-favorite-field"><strong>{t(item.label)}</strong><p>{item.value}</p></div>}/>
      <div className="source-favorite-actions">
        {selectedRow?.conflict ? <><button disabled={busy || !session} onClick={() => void act(() => session!.controller.choose(identity, 'local', structuredClone(selectedRow)))}>{t('기기 값 사용')}</button><button disabled={busy || !session} onClick={() => void act(() => session!.controller.choose(identity, 'server', structuredClone(selectedRow)))}>{t('서버 값 사용')}</button></>
          : adoption ? <button disabled={busy || !state?.ready || !session || !candidate} onClick={() => void act(() => session!.controller.update(identity, { deleted: false, book: candidate! }, selectedRow && structuredClone(selectedRow)))}>{t(selectedRow?.remote && !selectedRow.remote.deleted ? '기기 책으로 계정 갱신' : '이 책을 계정에 추가')}</button>
            : <><button disabled={!book} onClick={() => { if (book) { closeDetail(); onOpenBook(book.url) } }}>{t('책 열기')}</button><button disabled={busy || !session || !selectedRow || !!local?.deleted} onClick={() => void act(() => session!.controller.update(identity, { deleted: true, book: null }, structuredClone(selectedRow!)))}>{t('계정에서 삭제')}</button></>}
      </div>
      {adoption && <p className="source-favorite-caption">{t('선택한 책만 계정에 추가합니다. 기기에 보관한 즐겨찾기는 유지됩니다.')}</p>}
    </dialog>
  }
  return <section className="novel-workspace source-favorites" aria-label={t('계정 즐겨찾기')}>
    <div className="source-favorite-toolbar"><p className="source-favorite-notice" role={state?.errorCode ? 'alert' : 'status'} title={t(notice)}>{t(notice)}</p><button disabled={!session || busy} onClick={() => { void session?.controller.refresh() }}>{t('새로고침')}</button></div>
    {rows.length ? <AdaptiveCollection items={rows} itemKey={([key]) => key} rowHeight={104} renderItem={([key, row]: [string, SourceFavoriteRow]) => {
      const value = favoriteRowChange(row), identity = row.pending ?? row.remote!
      return <div className="workflow-row" key={key}><button className="workflow-row-copy button-quiet" disabled={!value?.book} onClick={() => { if (value?.book) onOpenBook(value.book.url) }}><strong>{value?.book?.title ?? row.remote?.book?.title ?? t('삭제 대기')}</strong><span>{identity.providerId} · {t(row.conflict ? '충돌' : row.pending ? '저장 대기' : '동기화됨')}</span></button><button onClick={() => { setSide('local'); setSelected({ providerId: identity.providerId, bookId: identity.bookId }) }}>{t('관리')}</button></div>
    }}/> : <div className="workflow-feedback"><p>{t('기기 책 목록에서 계정에 추가할 책을 선택해 주세요.')}</p></div>}
  </section>
}
