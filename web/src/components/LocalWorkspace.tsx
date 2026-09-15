import { useEffect, useMemo, useRef, useState } from 'react'
import { translate as t } from '../lib/locale'
import { createLocalDocuments, parseLocalDocument, localDocumentForTranslation, type LocalEncoding, type SavedLocalDocument } from '../lib/localDocuments'
import type { ReadingDocument } from '../lib/readingDocument'
import { createReadingNotes } from '../lib/readingNotes'
import { deviceStorageMessage } from '../lib/deviceReadingDatabase'
import type { ReadingAnchor } from '../lib/offline'
import { AdaptiveCollection } from './AdaptiveCollection'
import { PagedReader } from './PagedReader'
import { LocalPdfReader } from './LocalPdfReader'
import { LocalLibraryOrganizer } from './LocalLibraryOrganizer'
import { emptyLocalLibraryFilter, filterLocalDocuments, localDocumentFolders } from '../lib/localOrganization'
import './readingTools.css'

export function LocalWorkspace({ username, onReadingChange, onTranslate }: { username: string; onReadingChange?: (reading: boolean) => void; onTranslate?: (document: ReadingDocument) => void }) {
  const storage = useMemo(() => username.trim() ? createLocalDocuments(username) : undefined, [username])
  const notes = useMemo(() => username.trim() ? createReadingNotes(username) : undefined, [username])
  const [books, setBooks] = useState<SavedLocalDocument[]>([]), [damagedIds, setDamagedIds] = useState<string[]>([])
  const [reading, setReading] = useState<{ book: SavedLocalDocument; anchor?: ReadingAnchor }>()
  const [tab, setTab] = useState<'books' | 'import' | 'filter'>('books'), [encoding, setEncoding] = useState<LocalEncoding>('auto')
  const [filter, setFilter] = useState(emptyLocalLibraryFilter), [organizing, setOrganizing] = useState<SavedLocalDocument>()
  const filteredBooks = useMemo(() => filterLocalDocuments(books, filter), [books, filter]), folders = useMemo(() => localDocumentFolders(books), [books])
  const [error, setError] = useState(''), [busy, setBusy] = useState(false), [removeId, setRemoveId] = useState<string>()
  const pending = useRef<AbortController | undefined>(undefined)
  useEffect(() => { onReadingChange?.(!!reading); return () => { onReadingChange?.(false) } }, [!!reading, onReadingChange])
  const refresh = async () => { if (storage) { const snapshot = await storage.list(); setBooks(snapshot.books); setDamagedIds(snapshot.damagedIds) } }
  useEffect(() => {
    let active = true
    pending.current?.abort(); setReading(undefined); setBooks([]); setDamagedIds([]); setError(''); setBusy(false); setFilter(emptyLocalLibraryFilter()); setOrganizing(undefined)
    storage?.list().then(value => { if (active) { setBooks(value.books); setDamagedIds(value.damagedIds) } }).catch(error => { if (active) setError(deviceStorageMessage(error)) })
    return () => { active = false; pending.current?.abort() }
  }, [storage])
  async function importFile(file: File) {
    if (!storage || busy) return
    const controller = new AbortController(); pending.current = controller; setBusy(true); setError('')
    try {
      const document = await parseLocalDocument(file, { encoding, signal: controller.signal })
      controller.signal.throwIfAborted(); await storage.save(document); controller.signal.throwIfAborted()
      await refresh(); setTab('books')
    } catch (error) { if (!controller.signal.aborted) setError(deviceStorageMessage(error)) }
    finally { if (pending.current === controller) { pending.current = undefined; setBusy(false) } }
  }
  function translateCurrent() {
    if (!reading || !onTranslate) return
    setError('')
    try { onTranslate(localDocumentForTranslation(reading.book.document)) } catch (error) { setError(deviceStorageMessage(error)) }
  }
  if (!storage || !notes) return <div className="empty-state"><h2>{t('기기 보관함에 사용할 계정 이름을 입력해 주세요.')}</h2></div>
  if (organizing) return <LocalLibraryOrganizer key={organizing.document.id} book={organizing} onClose={() => setOrganizing(undefined)} onSave={async value => { await storage.organize(organizing.document.id, value); await refresh() }}/>
  if (reading?.book.document.local.format === 'pdf') return <LocalPdfReader key={reading.book.document.id} document={reading.book.document} namespace={username} anchor={reading.anchor} onClose={() => setReading(undefined)} onTranslate={onTranslate ? translateCurrent : undefined} actionError={error} onAnchorChange={anchor => { void notes.setPosition(reading.book.document, anchor).catch(error => setError(deviceStorageMessage(error))) }}/>
  if (reading) return <PagedReader key={reading.book.document.id} document={reading.book.document} anchor={reading.anchor} notesNamespace={username} onClose={() => setReading(undefined)} onAnchorChange={anchor => {
    void notes.setPosition(reading.book.document, anchor).catch(error => setError(deviceStorageMessage(error)))
  }} onAction={onTranslate ? translateCurrent : undefined} actionLabel={t('서버에서 번역')} actionError={error} positionNote={t('읽기 기록은 이 계정 이름으로 이 기기에만 저장됩니다.')}/>
  return <section className="reading-workspace" aria-label={t('로컬 파일 서재')}>
    <nav className="workflow-subtabs" aria-label={t('로컬 파일 서재')}><button aria-pressed={tab === 'books'} onClick={() => setTab('books')}>{t('가져온 파일')}</button><button aria-pressed={tab === 'import'} onClick={() => setTab('import')}>{t('파일 가져오기')}</button><button aria-pressed={tab === 'filter'} onClick={() => setTab('filter')}>{t('찾기 · 정렬')}</button></nav>
    {error && <div role="alert" className="workflow-message">{t(error)} <button className="button-text" onClick={() => void refresh().catch(error => setError(deviceStorageMessage(error)))}>{t('다시 시도')}</button></div>}
    {busy && <div role="status" className="workflow-message">{t('문서를 읽고 있습니다…')} <button className="button-outline" onClick={() => pending.current?.abort()}>{t('취소')}</button></div>}
    {removeId ? <div className="reading-confirm"><h2>{t('이 파일을 기기에서 삭제할까요?')}</h2><p>{t('이 파일의 북마크, 메모, 읽은 위치도 함께 삭제됩니다. 원본 파일은 변경하지 않습니다.')}</p><div><button className="button-outline" disabled={busy} onClick={() => setRemoveId(undefined)}>{t('취소')}</button><button className="button-primary" disabled={busy} onClick={() => { setBusy(true); void storage.remove(removeId).then(refresh).then(() => setRemoveId(undefined)).catch(error => setError(deviceStorageMessage(error))).finally(() => setBusy(false)) }}>{t('삭제')}</button></div></div>
    : tab === 'filter' ? <div className="reading-tools-form"><AdaptiveCollection items={['query', 'folder', 'favorite', 'sort']} itemKey={item => item} rowHeight={112} renderItem={field => <div className="reading-field">
      {field === 'query' ? <><label htmlFor="local-filter-query">{t('제목 · 형식 · 폴더 · 태그 검색')}</label><input id="local-filter-query" value={filter.query} maxLength={200} onChange={event => setFilter(value => ({ ...value, query: event.target.value }))}/></>
      : field === 'folder' ? <><label htmlFor="local-filter-folder">{t('폴더')}</label><select id="local-filter-folder" value={filter.folder} onChange={event => setFilter(value => ({ ...value, folder: event.target.value }))}><option value="">{t('모든 폴더')}</option>{folders.map(folder => <option value={folder} key={folder}>{folder}</option>)}</select></>
      : field === 'favorite' ? <label className="reading-checkbox"><input type="checkbox" checked={filter.favoritesOnly} onChange={event => setFilter(value => ({ ...value, favoritesOnly: event.target.checked }))}/>{t('즐겨찾기만 보기')}</label>
      : <><label htmlFor="local-filter-sort">{t('정렬')}</label><select id="local-filter-sort" value={filter.sort} onChange={event => setFilter(value => ({ ...value, sort: event.target.value as typeof value.sort }))}><option value="recent">{t('최근 가져온 순서')}</option><option value="title">{t('제목 순서')}</option><option value="folder">{t('폴더 순서')}</option></select></>}
    </div>}/><div className="reading-filter-actions"><button className="button-outline" onClick={() => setFilter(emptyLocalLibraryFilter())}>{t('필터 초기화')}</button><button className="button-primary" onClick={() => setTab('books')}>{t('{0}개 파일 보기', [filteredBooks.length])}</button></div></div>
    : tab === 'import' ? <div className="reading-import"><AdaptiveCollection items={['file', 'encoding', 'scope']} itemKey={item => item} rowHeight={136} renderItem={item => <div className="reading-field">
      {item === 'file' ? <><label htmlFor="local-document-file">{t('파일 가져오기')}</label><input id="local-document-file" type="file" accept=".txt,.md,.markdown,.epub,.pdf" disabled={busy} onChange={event => { const file = event.target.files?.[0]; event.target.value = ''; if (file) void importFile(file) }}/></>
      : item === 'encoding' ? <><label htmlFor="local-document-encoding">{t('텍스트 인코딩')}</label><select id="local-document-encoding" value={encoding} disabled={busy} onChange={event => setEncoding(event.target.value as LocalEncoding)}>{(['auto', 'utf-8', 'utf-16le', 'utf-16be', 'euc-kr', 'gb18030', 'shift_jis', 'windows-1252'] as const).map(value => <option value={value} key={value}>{value === 'auto' ? t('자동: BOM 또는 UTF-8') : value.toUpperCase()}</option>)}</select></>
      : <p>{t('32MB 이하 TXT·Markdown·EPUB·PDF를 기기에 보관합니다. 서버에 업로드하지 않습니다. 텍스트 인코딩은 TXT·Markdown에만 적용합니다.')}</p>}
    </div>}/></div>
    : <>{damagedIds.length > 0 && <div role="alert" className="workflow-message">{t('손상된 로컬 문서 {0}개가 있습니다.', [damagedIds.length])} <button className="button-text" onClick={() => setRemoveId(damagedIds[0])}>{t('손상된 항목 삭제')}</button></div>}
      {filteredBooks.length ? <AdaptiveCollection key={JSON.stringify(filter)} items={filteredBooks} itemKey={item => item.document.id} rowHeight={132} renderItem={item => <div className="reading-item"><div className="reading-note-copy"><strong>{item.organization.favorite ? '★ ' : ''}{item.document.bookTitle}</strong><span>{item.document.local.format.toUpperCase()} · {t('{0}개 문단', [item.document.paragraphs.length])}</span><span title={[item.organization.folder, ...item.organization.tags].join(' · ')}>{[item.organization.folder, ...item.organization.tags.map(tag => `#${tag}`)].filter(Boolean).join(' · ')}</span></div><div className="reading-row-actions"><button className="button-outline" disabled={busy} onClick={() => void notes.getPosition(item.document).then(anchor => setReading({ book: item, anchor })).catch(error => { setError(deviceStorageMessage(error)); setReading({ book: item }) })}>{t('읽기')}</button><button className="button-quiet" disabled={busy} onClick={() => setOrganizing(item)}>{t('분류')}</button><button className="button-quiet" disabled={busy} onClick={() => setRemoveId(item.document.id)}>{t('삭제')}</button></div></div>}/>
      : <div className="empty-state"><h2>{t(books.length ? '조건에 맞는 파일이 없습니다.' : '가져온 로컬 파일이 없습니다.')}</h2><button className="button-outline" onClick={() => books.length ? setTab('filter') : setTab('import')}>{t(books.length ? '찾기 · 정렬' : '파일 가져오기')}</button></div>}</>}
  </section>
}
