import { useState } from 'react'
import { translate as t } from '../lib/locale'
import { ReadingNoteError, type ReadingNoteInput } from '../lib/readingNoteApi'
import type { ReadingDocument } from '../lib/readingDocument'
import type { useReadingNoteSync } from './ReadingNoteProvider'
import { AdaptiveCollection } from './AdaptiveCollection'
import { PagedReader } from './PagedReader'

export function readingNoteStatus(sync: ReturnType<typeof useReadingNoteSync>) {
  if (!sync.online) return '읽기 기록 · 연결 대기'
  switch (sync.state?.status) {
    case 'synced': return '읽기 기록 · 동기화됨'
    case 'conflict': return '읽기 기록 · 기록 선택 필요'
    case 'pending': return '읽기 기록 · 저장 대기'
    case 'error': return '읽기 기록 · 동기화 확인'
    default: return '읽기 기록 · 확인 중'
  }
}
export function ReadingNotePanel({ document, sync, onClose }: {
  document: ReadingDocument; sync: ReturnType<typeof useReadingNoteSync>; onClose: () => void
}) {
  const [preview, setPreview] = useState<ReadingNoteInput>()
  const [selectedId, setSelectedId] = useState<string>()
  const state = sync.state
  const conflicts = state?.conflicts ?? []
  const selected = conflicts.find(item => item.noteId === selectedId)
  if (preview) return <PagedReader readOnly document={{ id: 'reading-note-conflict-preview', kind: 'introduction', bookTitle: document.bookTitle,
    chapterTitle: preview.title, language: document.language, paragraphs: [
      { paragraphId: 'title', text: preview.title },
      ...(preview.text ? [{ paragraphId: 'text', text: preview.text }] : []),
      { paragraphId: 'anchor', text: document.paragraphs.find(p => p.paragraphId === preview.anchor.paragraphId)?.text || t('이 문서에서 확인할 수 없는 위치') },
    ] }} onClose={() => setPreview(undefined)} onAnchorChange={() => {}}/>
  return <section className="reading-workspace" aria-label={t('읽기 기록 동기화')}>
    <header className="reading-tools-header"><button className="button-quiet" onClick={selected ? () => setSelectedId(undefined) : onClose}>{t(selected ? '충돌 목록으로' : '읽기 도구로')}</button><strong>{t('읽기 기록 동기화')}</strong></header>
    <p role={state?.errorCode || conflicts.length ? 'alert' : 'status'} className="workflow-message">{t(state?.errorCode ? new ReadingNoteError(state.errorCode).message : readingNoteStatus(sync))} {t('저장 대기 {0}개 · 충돌 {1}개', [state?.pendingCount ?? 0, conflicts.length])}</p>
    {selected ? <AdaptiveCollection items={['local', 'server'] as const} itemKey={where => where} rowHeight={164} renderItem={where => {
      const change = where === 'local' ? selected.local : selected.remote
      const note = change.note
      return <div className="reading-note-choice"><strong>{t(where === 'local' ? '이 기기의 기록' : '서버의 기록')}</strong>
        {change.deleted || !note ? <p>{t('삭제된 기록')}</p> : <button className="reading-note-title" onClick={() => setPreview(note)} aria-label={t('{0} 기록 보기', [note.title])}>{note.title}</button>}
        <p>{change.deleted || !note ? t('이 상태를 선택하면 항목이 삭제됩니다.') : note.text || t('제목을 누르면 기록과 해당 문단을 확인할 수 있습니다.')}</p>
        <button className="button-outline" disabled={!sync.online || state?.status === 'loading'} onClick={() => sync.resolve(selected.noteId, where, selected)}>{t(where === 'local' ? '이 기기 기록 사용' : '서버 기록 사용')}</button>
      </div>
    }}/>
    : conflicts.length ? <AdaptiveCollection items={conflicts} itemKey={item => item.noteId} rowHeight={132} renderItem={item => <div className="reading-note-item"><div className="reading-note-copy"><strong>{item.local.note?.title || item.remote.note?.title || t('삭제된 기록')}</strong><p>{t('두 기기에서 같은 기록을 변경했습니다.')}</p></div><button className="button-outline" onClick={() => setSelectedId(item.noteId)}>{t('기록 비교')}</button></div>}/>
    : <AdaptiveCollection items={['scope', 'offline', ...(state?.unsupportedCount ? ['legacy'] : [])]} itemKey={item => item} rowHeight={132} renderItem={item => <div className="reading-field"><p>{t(item === 'scope' ? '서버 원문과 번역문의 북마크·강조·메모를 같은 계정의 앱과 웹에서 공유합니다.' : item === 'offline' ? '연결이 끊겨도 변경한 기록은 기기에 남습니다. 다른 기기와 충돌한 기록은 직접 선택할 때까지 보존합니다.' : '이전 형식의 기록 {0}개는 이 기기와 ZIP 파일에 보존됩니다. 아직 계정 동기화 대상이 아닙니다.', [state?.unsupportedCount ?? 0])}</p></div>}/>}
    <button className="button-outline" disabled={!sync.online || state?.status === 'loading'} onClick={sync.refresh}>{t('동기화 다시 확인')}</button>
    <p className="reading-tools-caption">{t('원문과 번역문의 기록은 각각 저장됩니다. 서버 문서를 삭제하면 연결된 기록도 삭제됩니다.')}</p>
  </section>
}
