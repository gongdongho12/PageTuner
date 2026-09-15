import { useEffect, useMemo, useRef, useState } from 'react'
import { translate as t } from '../lib/locale'
import { catalogFileAllowed, filterJsonCatalog, parseCatalogFile, type JsonCatalog, type JsonCatalogEntry } from '../lib/jsonCatalog'
import type { JsonCatalogClient } from '../lib/jsonCatalogApi'
import { createLocalDocuments, localDocumentForTranslation, type LocalDocument, type LocalEncoding } from '../lib/localDocuments'
import { createReadingNotes } from '../lib/readingNotes'
import { deviceStorageMessage } from '../lib/deviceReadingDatabase'
import type { ReadingDocument } from '../lib/readingDocument'
import type { ReadingAnchor } from '../lib/offline'
import { AdaptiveCollection } from './AdaptiveCollection'
import { PagedReader } from './PagedReader'
import { LocalPdfReader } from './LocalPdfReader'
import './readingTools.css'

export type JsonCatalogWorkspaceProps = { username: string; client: JsonCatalogClient;
  onReadingChange?: (reading: boolean) => void; onTranslate?: (document: ReadingDocument) => void; onBack?: () => void }

/** Reads the same v0 catalog as the Android adapter and imports into the existing account-scoped local library. */
export function JsonCatalogWorkspace({ username, client, onReadingChange, onTranslate, onBack }: JsonCatalogWorkspaceProps) {
  const storage = useMemo(() => username.trim() ? createLocalDocuments(username) : undefined, [username])
  const notes = useMemo(() => username.trim() ? createReadingNotes(username) : undefined, [username])
  const [url, setUrl] = useState(''), [query, setQuery] = useState(''), [encoding, setEncoding] = useState<LocalEncoding>('auto')
  const [catalog, setCatalog] = useState<JsonCatalog>(), [tab, setTab] = useState<'address' | 'catalog'>('address')
  const [saved, setSaved] = useState<LocalDocument>(), [reading, setReading] = useState<{ document: LocalDocument; anchor?: ReadingAnchor }>()
  const [error, setError] = useState(''), [busy, setBusy] = useState(false)
  const pending = useRef<AbortController | undefined>(undefined), generation = useRef(0)
  useEffect(() => {
    generation.current++; pending.current?.abort(); setCatalog(undefined); setReading(undefined); setSaved(undefined); setError(''); setBusy(false); setTab('address')
    return () => { generation.current++; pending.current?.abort() }
  }, [storage, client])
  useEffect(() => { onReadingChange?.(!!reading); return () => onReadingChange?.(false) }, [!!reading, onReadingChange])
  const items = useMemo(() => filterJsonCatalog(catalog?.items ?? [], query), [catalog, query])
  const previous = catalog?.links.find(link => ['prev', 'previous'].includes(link.rel.toLowerCase()))?.href
  const next = catalog?.links.find(link => link.rel.toLowerCase() === 'next')?.href

  async function load(target: string) {
    if (busy) return
    const controller = new AbortController(); pending.current = controller; setBusy(true); setError('')
    try {
      const result = await client.catalog(target.trim(), controller.signal)
      controller.signal.throwIfAborted(); setCatalog(result); setUrl(result.catalogUrl); setTab('catalog')
    } catch (error) { if (!controller.signal.aborted) setError(deviceStorageMessage(error)) }
    finally { if (pending.current === controller) { pending.current = undefined; setBusy(false) } }
  }

  async function importBook(entry: JsonCatalogEntry) {
    if (busy || !storage) return
    const controller = new AbortController(); pending.current = controller; setBusy(true); setError(''); setSaved(undefined)
    try {
      catalogFileAllowed(entry)
      const bytes = await client.file(entry.href, controller.signal)
      const document = await parseCatalogFile(entry, bytes, { encoding, signal: controller.signal })
      controller.signal.throwIfAborted(); await storage.save(document); controller.signal.throwIfAborted()
      setSaved(document)
    } catch (error) { if (!controller.signal.aborted) setError(deviceStorageMessage(error)) }
    finally { if (pending.current === controller) { pending.current = undefined; setBusy(false) } }
  }

  async function openSaved(document: LocalDocument) {
    if (!notes) return
    const version = generation.current
    try {
      const anchor = await notes.getPosition(document)
      if (version === generation.current) setReading({ document, anchor })
    } catch (error) { if (version === generation.current) { setError(deviceStorageMessage(error)); setReading({ document }) } }
  }

  function translateCurrent() {
    if (!reading || !onTranslate) return
    setError('')
    try { onTranslate(localDocumentForTranslation(reading.document)) }
    catch (error) { setError(deviceStorageMessage(error)) }
  }

  if (!storage || !notes) return <div className="empty-state"><h2>{t('기기 보관함에 사용할 계정 이름을 입력해 주세요.')}</h2></div>
  if (reading) {
    const position = (anchor: ReadingAnchor) => { void notes.setPosition(reading.document, anchor).catch(error => setError(deviceStorageMessage(error))) }
    if (reading.document.local.format === 'pdf') return <LocalPdfReader key={reading.document.id} document={reading.document} namespace={username} anchor={reading.anchor}
      onClose={() => setReading(undefined)} onAnchorChange={position} onTranslate={onTranslate ? translateCurrent : undefined} actionError={error}/>
    return <PagedReader key={reading.document.id} document={reading.document} notesNamespace={username} anchor={reading.anchor}
      onClose={() => setReading(undefined)} onAnchorChange={position} onAction={onTranslate ? translateCurrent : undefined} actionLabel={t('서버에서 번역')} actionError={error}
      positionNote={t('읽기 기록은 이 계정 이름으로 이 기기에만 저장됩니다.')}/>
  }

  return <section className="reading-workspace" aria-label={t('JSON 카탈로그')}>
    <nav className="workflow-subtabs" aria-label={t('JSON 카탈로그')}>
      {onBack && <button onClick={onBack}>{t('소설로 돌아가기')}</button>}
      <button aria-pressed={tab === 'catalog'} disabled={!catalog} onClick={() => setTab('catalog')}>{t('카탈로그 목록')}</button>
      <button aria-pressed={tab === 'address'} onClick={() => setTab('address')}>{t('주소 · 검색 · 인코딩')}</button>
    </nav>
    {error && <div role="alert" className="workflow-message">{t(error)} <button className="button-text" onClick={() => setError('')}>{t('닫기')}</button></div>}
    {busy && <div role="status" className="workflow-message">{t('카탈로그 또는 파일을 가져오고 있습니다…')} <button className="button-outline" onClick={() => pending.current?.abort()}>{t('취소')}</button></div>}
    {saved && <div role="status" className="workflow-message">{t('로컬 파일 서재에 보관했습니다.')} <button className="button-outline" disabled={busy} onClick={() => void openSaved(saved)}>{t('읽기')}</button> <button className="button-text" onClick={() => setSaved(undefined)}>{t('닫기')}</button></div>}
    {tab === 'address' ? <div className="reading-tools-form"><AdaptiveCollection mode="paged" items={['url', 'query', 'encoding', 'scope']} itemKey={item => item} rowHeight={152} renderItem={field => <div className="reading-field">
      {field === 'url' ? <><label htmlFor="json-catalog-url">{t('카탈로그 HTTPS 주소')}</label><input id="json-catalog-url" type="url" value={url} maxLength={4096} disabled={busy} placeholder="https://example.com/catalog.json" onChange={event => setUrl(event.target.value)}/><button className="button-primary" disabled={busy || !url.trim()} onClick={() => void load(url)}>{t('카탈로그 불러오기')}</button></>
      : field === 'query' ? <><label htmlFor="json-catalog-query">{t('현재 페이지에서 제목 · 저자 검색')}</label><input id="json-catalog-query" value={query} maxLength={200} onChange={event => setQuery(event.target.value)}/>{catalog && <button className="button-outline" onClick={() => setTab('catalog')}>{t('{0}개 파일 보기', [items.length])}</button>}</>
      : field === 'encoding' ? <><label htmlFor="json-catalog-encoding">{t('텍스트 인코딩')}</label><select id="json-catalog-encoding" value={encoding} disabled={busy} onChange={event => setEncoding(event.target.value as LocalEncoding)}>{(['auto', 'utf-8', 'utf-16le', 'utf-16be', 'euc-kr', 'gb18030', 'shift_jis', 'windows-1252'] as const).map(value => <option value={value} key={value}>{value === 'auto' ? t('자동: BOM 또는 UTF-8') : value.toUpperCase()}</option>)}</select></>
      : <p>{t('PageTurner v0 JSON 카탈로그의 TXT·Markdown·EPUB·PDF를 가져옵니다. 공개 HTTPS 파일만 지원하며, 32MB 이하 원본을 현재 계정의 기기 서재에 보관합니다.')}</p>}
    </div>}/></div>
    : catalog && <><header className="reading-tools-header"><strong title={catalog.title}>{catalog.title}</strong><button className="button-outline" disabled={busy} onClick={() => void load(catalog.catalogUrl)}>{t('새로 고침')}</button></header>
      <p className="reading-tools-caption">{t('현재 카탈로그 페이지: {0}개 · 검색 결과: {1}개', [catalog.items.length, items.length])}</p>
      {items.length ? <AdaptiveCollection key={`${catalog.catalogUrl}:${query}`} items={items} itemKey={item => item.id} rowHeight={132} keyboardEnabled={!busy}
        onPreviousBatch={!busy && previous ? () => void load(previous) : undefined} onNextBatch={!busy && next ? () => void load(next) : undefined}
        renderItem={item => <div className="reading-item"><div className="reading-note-copy"><strong>{item.title}</strong><span>{item.authors.join(' · ') || t('저자 정보 없음')}</span><span>{item.format.toUpperCase()}{item.size !== null ? ` · ${Math.ceil(item.size / 1024)} KB` : ''}{item.language ? ` · ${item.language}` : ''}</span></div>
          <button className="button-outline" disabled={busy} onClick={() => void importBook(item)}>{t('가져오기')}</button></div>}/>
      : <div className="empty-state"><h2>{t('현재 페이지에 조건에 맞는 파일이 없습니다.')}</h2><div className="reading-row-actions"><button className="button-outline" disabled={busy || !previous} onClick={() => previous && void load(previous)}>{t('이전 카탈로그')}</button><button className="button-outline" disabled={busy || !next} onClick={() => next && void load(next)}>{t('다음 카탈로그')}</button></div></div>}
    </>}
  </section>
}
