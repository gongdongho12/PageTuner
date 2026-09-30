import { useEffect, useMemo, useState } from 'react'
import { translate as t } from '../lib/locale'
import { createReadingNotes, subscribeReadingNotes, type ReadingNote } from '../lib/readingNotes'
import { deviceStorageMessage } from '../lib/deviceReadingDatabase'
import type { ReadingDocument } from '../lib/readingDocument'
import type { ReadingAnchor } from '../lib/offline'
import { AdaptiveCollection } from './AdaptiveCollection'
import { PagedReader } from './PagedReader'
import { ReaderPreferencesPanel } from './ReaderPreferences'
import { ReaderSearch } from './ReaderSearch'
import { useReadingNoteSync } from './ReadingNoteProvider'
import { ReadingNotePanel, readingNoteStatus } from './ReadingNotePanel'
import './readingTools.css'
import { downloadReadingExport, readingNotesExport, shareReadingExport } from '../lib/readingExport'

export function ReaderTools({ namespace, document, anchor, onJump, onClose }: {
  namespace: string; document: ReadingDocument; anchor: ReadingAnchor
  onJump: (anchor: ReadingAnchor) => void; onClose: () => void
}) {
  const storage = useMemo(() => createReadingNotes(namespace), [namespace])
  const [tab, setTab] = useState<'bookmark' | 'note' | 'highlight' | 'add' | 'outline' | 'images' | 'search' | 'settings' | 'export' | 'sync'>('bookmark')
  const sync = useReadingNoteSync(document, namespace)
  const [items, setItems] = useState<ReadingNote[]>([]), [damagedIds, setDamagedIds] = useState<string[]>([])
  const [error, setError] = useState(''), [busy, setBusy] = useState(false)
  const [kind, setKind] = useState<'bookmark' | 'note'>('bookmark')
  const [title, setTitle] = useState(document.chapterTitle || document.bookTitle), [text, setText] = useState('')
  const [removeId, setRemoveId] = useState<string>()
  const [removeSnapshot, setRemoveSnapshot] = useState<ReadingNote>()
  const [selected, setSelected] = useState<ReadingNote>()
  const [editing, setEditing] = useState<ReadingNote>()
  const [editTitle, setEditTitle] = useState(''), [editText, setEditText] = useState('')
  const [exportMessage, setExportMessage] = useState('')
  const refresh = async () => { const snapshot = await storage.list(document); setItems(snapshot.items); setDamagedIds(snapshot.damagedIds) }
  useEffect(() => {
    let active = true
    const update = () => storage.list(document).then(value => { if (active) { setItems(value.items); setDamagedIds(value.damagedIds) } })
      .catch(error => { if (active) setError(deviceStorageMessage(error)) })
    void update()
    const unsubscribe = subscribeReadingNotes(namespace, document.id, () => { void update() })
    return () => { active = false; unsubscribe() }
  }, [document, storage])
  async function write(action: () => Promise<unknown>) {
    setBusy(true); setError('')
    try { await action(); await refresh(); setRemoveId(undefined); setRemoveSnapshot(undefined) } catch (error) { setError(deviceStorageMessage(error)) } finally { setBusy(false) }
  }
  const labels = { bookmark: '북마크', note: '메모', highlight: '강조', add: '현재 위치에 추가', outline: '목차', images: '삽화', search: '본문 검색', settings: '독서 설정', export: '내보내기 · 공유', sync: '읽기 기록 동기화' }
  const rows = items.filter(item => item.kind === tab)
  if (selected) return <PagedReader document={{ id: `reading-note:${selected.id}`, bookTitle: document.bookTitle, chapterTitle: selected.title, language: document.language, kind: 'introduction',
    paragraphs: [{ paragraphId: 'note-body', text: selected.text || selected.excerpt }] }} readOnly onClose={() => setSelected(undefined)} onAnchorChange={() => {}} positionNote={t(sync.available ? '같은 계정의 앱과 웹에서 공유하는 읽기 기록입니다.' : '이 기기에 저장한 읽기 기록입니다.')}/>
  if (tab === 'settings') return <ReaderPreferencesPanel namespace={namespace} onClose={() => setTab('bookmark')}/>
  if (tab === 'sync') return <ReadingNotePanel document={document} sync={sync} onClose={() => setTab('bookmark')}/>
  if (editing) return <section className="reading-workspace" aria-label={t('읽기 기록 수정')}>
    <header className="reading-tools-header"><button className="button-quiet" disabled={busy} onClick={() => { setEditing(undefined); setError('') }}>{t('수정 취소')}</button><strong>{t('읽기 기록 수정')}</strong></header>
    {error && <p role="alert" className="workflow-message">{t(error)}</p>}
    <form className="reading-tools-form" onSubmit={event => { event.preventDefault(); void write(async () => { await storage.update(document, editing.id, { title: editTitle, text: editText }, editing); setEditing(undefined) }) }}>
      <AdaptiveCollection items={['title', 'text']} itemKey={item => item} rowHeight={132} renderItem={field => <div className="reading-field">
        {field === 'title' ? <><label htmlFor="reading-note-edit-title">{t('제목')}</label><input id="reading-note-edit-title" required maxLength={200} value={editTitle} onChange={event => setEditTitle(event.target.value)} disabled={busy}/></>
          : <><label htmlFor="reading-note-edit-text">{t('메모 내용')}</label><textarea id="reading-note-edit-text" required={editing.kind === 'note'} maxLength={4000} rows={2} value={editText} onChange={event => setEditText(event.target.value)} disabled={busy}/></>}
      </div>}/>
      <button type="submit" className="button-primary" disabled={busy || !editTitle.trim() || (editing.kind === 'note' && !editText.trim())}>{t(busy ? '저장 중…' : '변경 저장')}</button>
    </form><p className="reading-tools-caption">{t('기록의 위치와 강조 범위를 유지하며 제목과 메모를 수정합니다.')}</p>
  </section>
  return <section className="reading-workspace" aria-label={t('읽기 도구')}>
    <header className="reading-tools-header"><button className="button-quiet" onClick={onClose}>{t('본문으로 돌아가기')}</button><strong>{document.bookTitle}</strong></header>
    <nav className="workflow-subtabs" aria-label={t('읽기 도구')}>
      {(Object.keys(labels) as Array<keyof typeof labels>).filter(value => (value !== 'outline' || document.outline?.length) && (value !== 'images' || document.assets?.images?.length) && (value !== 'highlight' || document.local?.format !== 'pdf') && (value !== 'sync' || sync.available)).map(value =>
        <button key={value} aria-pressed={tab === value} onClick={() => { setTab(value); setRemoveId(undefined); setRemoveSnapshot(undefined) }} disabled={busy}>{t(labels[value])}</button>)}
    </nav>
    {error && <div role="alert" className="workflow-message">{t(error)} <button className="button-text" onClick={() => void write(refresh)}>{t('다시 시도')}</button></div>}
    {removeId ? <div className="reading-confirm"><h2>{t(sync.available ? '이 읽기 기록을 삭제할까요?' : '이 기기에서 항목을 삭제할까요?')}</h2><p>{t(sync.available ? '동기화된 기록의 삭제는 같은 계정의 앱과 웹에도 반영됩니다.' : '삭제한 북마크와 메모는 복구할 수 없습니다.')}</p><div>
      <button className="button-outline" disabled={busy} onClick={() => setRemoveId(undefined)}>{t('취소')}</button>
      <button className="button-primary" disabled={busy} onClick={() => void write(() => storage.remove(document.id, removeId, removeSnapshot))}>{t('삭제')}</button></div></div>
    : tab === 'add' ? <form className="reading-tools-form" onSubmit={event => { event.preventDefault(); void write(async () => { await storage.add(document, { kind, title, text, anchor }); setText(''); setTab(kind) }) }}>
      <AdaptiveCollection items={['kind', 'title', ...(kind === 'note' ? ['text'] : [])]} itemKey={item => item} rowHeight={132} renderItem={field => <div className="reading-field">
        {field === 'kind' ? <><label htmlFor="reading-note-kind">{t('종류')}</label><select id="reading-note-kind" value={kind} onChange={event => setKind(event.target.value as 'bookmark' | 'note')} disabled={busy}><option value="bookmark">{t('북마크')}</option><option value="note">{t('메모')}</option></select></>
          : field === 'title' ? <><label htmlFor="reading-note-title">{t('제목')}</label><input id="reading-note-title" value={title} maxLength={200} required onChange={event => setTitle(event.target.value)} disabled={busy}/></>
          : <><label htmlFor="reading-note-text">{t('메모 내용')}</label><textarea id="reading-note-text" value={text} maxLength={4000} required rows={2} onChange={event => setText(event.target.value)} disabled={busy}/></>}
      </div>}/><button type="submit" className="button-primary" disabled={busy || !title.trim() || (kind === 'note' && !text.trim())}>{busy ? t('저장 중…') : t('현재 위치 저장')}</button>
    </form>
    : tab === 'export' ? <div className="reading-tools-form">{exportMessage && <p role="status" className="workflow-message">{t(exportMessage)}</p>}<AdaptiveCollection mode="paged" items={['txt', 'json', 'share', 'scope']} itemKey={item => item} rowHeight={132} renderItem={action => <div className="reading-field">
      {action === 'scope' ? <p>{t('이 문서의 북마크·강조·메모를 내보냅니다. 계정 정보와 원본 파일은 포함하지 않습니다.')}</p>
      : <button className="button-outline" disabled={busy || !items.length} onClick={() => {
        setError(''); setExportMessage('');
        try {
          const value = readingNotesExport(document, items, action === 'json' ? 'json' : 'txt')
          if (action === 'share') { setBusy(true); void shareReadingExport(value).then(result => setExportMessage(result === 'opened' ? '공유 화면에 읽기 기록을 전달했습니다.' : '공유를 취소했습니다.')).catch(error => setError(deviceStorageMessage(error))).finally(() => setBusy(false)) }
          else { downloadReadingExport(value); setExportMessage('읽기 기록 파일의 다운로드를 요청했습니다.') }
        } catch (error) { setError(deviceStorageMessage(error)) }
      }}>{t(action === 'txt' ? 'TXT로 내보내기' : action === 'json' ? 'JSON으로 내보내기' : '공유 화면 열기')}</button>}
    </div>}/></div>
    : tab === 'search' ? <ReaderSearch document={document} onJump={onJump}/>
    : tab === 'outline' ? <AdaptiveCollection items={document.outline ?? []} itemKey={item => item.paragraphId} rowHeight={92} renderItem={item => <div className="reading-item"><strong>{item.title}</strong><button className="button-outline" onClick={() => onJump({ paragraphId: item.paragraphId, characterOffset: 0 })}>{t('이동')}</button></div>}/>
    : tab === 'images' ? <AdaptiveCollection items={(document.assets?.images ?? []).map((image, index) => ({ ...image, index }))} itemKey={image => `${image.paragraphId}:${image.index}`} rowHeight={260} renderItem={image => <LocalIllustration blob={image.blob} alt={image.alt}/>}/>
    : <>{damagedIds.length > 0 && <div role="alert" className="workflow-message">{t('손상된 읽기 기록 {0}개가 있습니다.', [damagedIds.length])} <button className="button-text" onClick={() => { setRemoveSnapshot(undefined); setRemoveId(damagedIds[0]) }}>{t('손상된 항목 삭제')}</button></div>}
      {rows.length ? <AdaptiveCollection items={rows} itemKey={item => item.id} rowHeight={132} renderItem={item => <div className="reading-note-item"><div className="reading-note-copy"><button className="reading-note-title" onClick={() => setSelected(item)}>{item.title}</button><p>{item.kind === 'note' ? item.text : item.excerpt}</p></div><div className="reading-row-actions reading-note-actions"><button className="button-outline" onClick={() => onJump(item.anchor)}>{t('이동')}</button><button className="button-outline" onClick={() => { setEditing(item); setEditTitle(item.title); setEditText(item.text); setError('') }} disabled={busy}>{t('수정')}</button><button className="button-quiet" onClick={() => { setRemoveSnapshot(item); setRemoveId(item.id) }} disabled={busy}>{t('삭제')}</button></div></div>}/>
      : <div className="empty-state"><h2>{t(tab === 'bookmark' ? '아직 북마크가 없습니다.' : tab === 'highlight' ? '본문을 선택한 뒤 선택 강조를 눌러 주세요.' : '아직 메모가 없습니다.')}</h2>{tab === 'highlight' ? <button className="button-outline" onClick={onClose}>{t('본문으로 돌아가기')}</button> : <button className="button-outline" onClick={() => { setKind(tab as 'bookmark' | 'note'); setTab('add') }}>{t('현재 위치에 추가')}</button>}</div>}</>}
    <p className="reading-tools-caption">{t(sync.available ? readingNoteStatus(sync) : '읽기 기록은 이 계정 이름으로 이 기기에만 저장됩니다.')}</p>
  </section>
}

function LocalIllustration({ blob, alt }: { blob: Blob; alt: string }) {
  const [url, setUrl] = useState('')
  useEffect(() => { const next = URL.createObjectURL(blob); setUrl(next); return () => { URL.revokeObjectURL(next) } }, [blob])
  return <figure className="reading-image">{url && <img src={url} alt={alt}/>}<figcaption>{alt}</figcaption></figure>
}
