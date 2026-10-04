import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { translate as t } from '../lib/locale'
import { createExchangeLibrary, exchangeExportChoices, exchangeReadingDocument, exchangePdfDocument, mergeExchangePackages, type ExchangeExportChoice, type SavedExchange } from '../lib/exchangeLibrary'
import { readExchange, writeExchange, exchangeLimits, type ExchangePackage } from '../lib/libraryExchange'
import { createReadingNotes } from '../lib/readingNotes'
import { createPortableBindings } from '../lib/portableBinding'
import type { ReadingDocument } from '../lib/readingDocument'
import type { ReadingAnchor } from '../lib/offline'
import { AdaptiveCollection } from './AdaptiveCollection'
import { PagedReader } from './PagedReader'
import { LocalPdfReader } from './LocalPdfReader'
import { ExchangeAssetReader } from './ExchangeAssetReader'
import { LibraryIdentityVerifier } from './LibraryIdentityVerifier'
import type { LibraryIdentityClient } from '../lib/libraryIdentityApi'
import { useFreshBookGlossary } from './BookGlossaryProvider'
import { glossaryExportErrorMessage, prepareFreshGlossaryExport, type FreshBookGlossaryReader } from '../lib/portableGlossaryExport'
import './readingTools.css'

export function LibraryExchangeWorkspace({ username, identityClient, targetLanguage, onReadingChange }: { username: string; identityClient: LibraryIdentityClient | null; targetLanguage: string; onReadingChange: (reading: boolean) => void }) {
  const storage = useMemo(() => createExchangeLibrary(username), [username]), notes = useMemo(() => createReadingNotes(username), [username])
  const [tab, setTab] = useState<'import' | 'export' | 'books'>('import'), [books, setBooks] = useState<SavedExchange[]>([]), [choices, setChoices] = useState<ExchangeExportChoice[]>([])
  const [selected, setSelected] = useState<string[]>([]), [preview, setPreview] = useState<ExchangePackage>(), [busy, setBusy] = useState(false), [error, setError] = useState(''), [notice, setNotice] = useState('')
  const [reading, setReading] = useState<{ book: SavedExchange; anchor?: ReadingAnchor; accountDocument?: ReadingDocument }>()
  const [verifying, setVerifying] = useState<SavedExchange>()
  const [exportOptions, setExportOptions] = useState(false), [includeGlossary, setIncludeGlossary] = useState(false), [glossaryLanguage, setGlossaryLanguage] = useState(targetLanguage)
  const freshGlossary = useFreshBookGlossary(username)
  const bindings = useMemo(() => createPortableBindings(username, window.location.origin), [username])
  const accountRequest = useRef<AbortController | null>(null)
  const operation = useRef(0)
  const session = useMemo(() => ({ username, identityClient, glossary: freshGlossary.session }), [username, identityClient, freshGlossary.session])
  const currentSession = useRef(session); currentSession.current = session
  useLayoutEffect(() => {
    operation.current++; accountRequest.current?.abort(); setBusy(false); setChoices([]); setSelected([])
    return () => { operation.current++; accountRequest.current?.abort() }
  }, [session])
  useLayoutEffect(() => { onReadingChange(!!reading); return () => onReadingChange(false) }, [!!reading, onReadingChange])
  useEffect(() => { return () => { operation.current++ } }, [username])
  const run = async (action: (current: () => boolean) => Promise<void>) => {
    if (busy) return
    const id = ++operation.current, started = session, current = () => id === operation.current && currentSession.current === started
    setBusy(true); setError(''); setNotice('')
    try { await action(current) } catch (e) { if (current()) setError(glossaryExportErrorMessage(e)) }
    finally { if (current()) setBusy(false) }
  }
  const loadTab = (next: typeof tab) => {
    setTab(next); setPreview(undefined); setSelected([]); setExportOptions(false)
    void run(async current => { const values = next === 'export' ? await exchangeExportChoices(username) : next === 'books' ? await storage.list() : undefined; if (current() && values) { if (next === 'export') setChoices(values as ExchangeExportChoice[]); else setBooks(values as SavedExchange[]) } })
  }
  const exportSelected = () => void run(async current => {
    const request = new AbortController(); accountRequest.current?.abort(); accountRequest.current = request
    let data: Uint8Array, ticket: FreshBookGlossaryReader | undefined
    const picked = choices.filter(c => selected.includes(c.key))
    if (includeGlossary) {
      ticket = freshGlossary.begin()
      data = await prepareFreshGlossaryExport({ choices: picked, targetLanguage: glossaryLanguage, reader: ticket, identityClient, bindings, current, signal: request.signal })
    } else {
      const packages: ExchangePackage[] = []
      for (const choice of picked) { packages.push(await choice.load()); if (!current()) return }
      const merged = await mergeExchangePackages(packages); if (!current()) return
      data = await writeExchange(merged)
    }
    if (!current() || request.signal.aborted) return
    ticket?.assertCurrent()
    const url = URL.createObjectURL(new Blob([Uint8Array.from(data).buffer], { type: 'application/zip' })), link = document.createElement('a')
    link.href = url; link.download = `PageTurner-${new Date().toISOString().slice(0, 10)}.ptlibrary.zip`; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000)
    setNotice(t('ZIP 파일을 내보냈습니다. 앱이나 다른 PC의 ZIP 가져오기에서 열어 주세요.'))
  })
  if (reading) {
    if (reading.book.document.assets.some(a => a.role === 'pdf') || (reading.book.document.assets.length > 0 && !reading.book.document.paragraphs.some(p => p.text.trim()))) return <ExchangeAssetReader saved={reading.book} username={username} onClose={() => setReading(undefined)}/>
    const document = reading.accountDocument ?? exchangeReadingDocument(reading.book), pdf = exchangePdfDocument(reading.book)
    const remember = (anchor: ReadingAnchor) => { void notes.setPosition(document, anchor).catch(e => setError(e instanceof Error ? e.message : '')) }
    if (pdf) return <LocalPdfReader document={pdf} namespace={username} anchor={reading.anchor} onClose={() => setReading(undefined)} onAnchorChange={remember} actionError={error}/>
    return <PagedReader document={document} notesNamespace={username} glossaryTargetLanguage={document.kind === 'translation' ? document.language : targetLanguage} anchor={reading.anchor} onClose={() => setReading(undefined)} onAnchorChange={remember} positionNote={t(reading.accountDocument ? '계정 기록으로 읽고 있습니다. ZIP의 기기 기록은 별도로 보존됩니다.' : 'ZIP으로 가져온 책입니다. 변경한 독서 기록도 다시 내보낼 수 있습니다.')} actionError={error}/>
  }
  return <section className="reading-workspace" aria-label={t('앱 · 웹 ZIP 교환')}>
    <nav className="workflow-subtabs" aria-label={t('ZIP 교환 메뉴')}>{(['import', 'export', 'books'] as const).map(value => <button key={value} disabled={busy} aria-pressed={tab === value} onClick={() => loadTab(value)}>{t(value === 'import' ? 'ZIP 가져오기' : value === 'export' ? 'ZIP 내보내기' : '가져온 책')}</button>)}</nav>
    {error && <div className="workflow-message" role="alert">{t(error)} <button onClick={() => setError('')}>{t('닫기')}</button></div>}
    {notice && <div className="workflow-message" role="status">{notice} <button onClick={() => setNotice('')}>{t('닫기')}</button></div>}
    {busy && <div role="status">{t('ZIP 파일을 검증하고 있습니다…')}{tab === 'export' && <button onClick={() => { accountRequest.current?.abort(); operation.current++; setBusy(false) }}>{t('취소')}</button>}</div>}
    {tab === 'import' ? preview ? <>
      <p>{t('{0}개 문서를 가져올 준비가 되었습니다.', [preview.documents.length])}</p>
      <AdaptiveCollection items={preview.documents} itemKey={d => d.id} rowHeight={104} renderItem={d => <div className="reading-note-row"><strong>{d.bookTitle}</strong><span>{d.chapterTitle} · {d.paragraphs.length} {t('문단')} · {d.notes.length} {t('읽기 기록')}</span></div>}/>
      <div className="reading-filter-actions"><button className="button-outline" disabled={busy} onClick={() => setPreview(undefined)}>{t('취소')}</button><button className="button-primary" disabled={busy} onClick={() => void run(async current => { if (!current()) return; const report = await storage.importPackage(preview); if (!current()) return; setBooks(await storage.list()); setPreview(undefined); setTab('books'); setNotice(t('{0}개 추가, {1}개 기존 문서 유지. 기존 읽던 위치와 메모를 보존했습니다.', [report.added, report.existing])) })}>{t('이 계정의 기기에 가져오기')}</button></div>
    </> : <div className="reading-import"><AdaptiveCollection items={['file', 'scope', 'limits']} itemKey={v => v} rowHeight={136} renderItem={value => <div className="reading-field">{value === 'file' ? <><label htmlFor="library-exchange-file">{t('앱 또는 웹에서 내보낸 ZIP 파일')}</label><input id="library-exchange-file" type="file" accept=".zip,.ptlibrary.zip,application/zip" disabled={busy} onChange={event => { const file = event.target.files?.[0]; event.target.value = ''; if (file) void run(async current => { if (!file.size || file.size > exchangeLimits.archive) throw new Error('ZIP 파일은 32MB 이하로 선택해 주세요.'); const value = await readExchange(new Uint8Array(await file.arrayBuffer())); if (current()) setPreview(value) }) }}/></> : <p>{t(value === 'scope' ? '원문·번역본, PDF·삽화, 북마크·메모·강조·읽던 위치와 분류·용어집을 옮깁니다. 가져온 책은 이 계정의 기기에 저장됩니다.' : '규격 v1 · ZIP 32MB · 압축 해제 64MB · 최대 100개 문서. 파일 전체 검증 후 저장하며 기존 문서는 덮어쓰지 않습니다.')}</p>}</div>}/></div>
    : tab === 'export' ? exportOptions ? <>
      <AdaptiveCollection items={['include', 'language', 'scope', 'limits']} itemKey={v => v} rowHeight={160} renderItem={value => <div className="reading-field">{value === 'include' ? <label className="reading-checkbox"><input type="checkbox" checked={includeGlossary} disabled={busy || !freshGlossary.available || !identityClient} onChange={event => setIncludeGlossary(event.target.checked)}/>{t('최신 계정 용어집 포함')}</label>
        : value === 'language' ? <><label htmlFor="export-glossary-language">{t('내보낼 계정 용어집 언어')}</label><input id="export-glossary-language" value={glossaryLanguage} disabled={busy} maxLength={24} onChange={event => setGlossaryLanguage(event.target.value)} placeholder="ko / en / zh-cn"/></>
        : <p>{t(value === 'scope' ? '선택한 언어의 서버 값을 새로 조회합니다. 미등록·삭제·빈 목록도 구분하며, 가져오기만으로 계정 용어집을 바꾸지 않습니다.' : '서버 원본 확인과 ZIP 책의 계정 연결이 필요합니다. 대기 중인 변경·충돌이나 크기 초과가 있으면 파일을 만들지 않습니다.')}</p>}</div>}/>
      <div className="reading-filter-actions"><button disabled={busy} onClick={() => setExportOptions(false)}>{t('책 선택으로 돌아가기')}</button></div>
    </> : <>
      <p>{t('기기에 보관된 책을 선택하세요. 서버 책은 먼저 기기에 보관해 주세요.')}</p>
      {choices.length ? <AdaptiveCollection items={choices} itemKey={c => c.key} rowHeight={104} renderItem={choice => <label className="reading-note-row reading-checkbox"><input type="checkbox" checked={selected.includes(choice.key)} disabled={busy} onChange={event => setSelected(values => event.target.checked ? [...values, choice.key] : values.filter(v => v !== choice.key))}/><strong>{choice.title}</strong><span>{choice.kind}</span></label>}/> : <p>{t('내보낼 기기 보관 문서가 없습니다.')}</p>}
      <div className="reading-filter-actions exchange-export-actions"><button disabled={busy} onClick={() => setExportOptions(true)}>{t(includeGlossary ? '내보내기 설정 · 계정 용어집 포함' : '내보내기 설정')}</button><span>{t('{0}개 선택', [selected.length])}</span><button className="button-primary" disabled={busy || selected.length === 0 || selected.length > 100 || includeGlossary && (!freshGlossary.available || !identityClient)} onClick={exportSelected}>{t('선택한 책을 ZIP으로 내보내기')}</button></div>
    </> : books.length ? <AdaptiveCollection items={books} itemKey={book => book.id} rowHeight={180} renderItem={book => <div className="reading-note-row"><strong>{book.document.bookTitle}</strong><span>{book.document.chapterTitle} · {book.document.language} · {book.document.organization.folder}</span><div className="exchange-book-actions"><button className="button-outline" disabled={busy} onClick={() => void run(async current => { const anchor = await notes.getPosition(exchangeReadingDocument(book)); if (current()) setReading({ book, anchor }) })}>{t('읽기')}</button><button className="button-outline" disabled={busy} onClick={() => setVerifying(book)}>{t('서버 원본 확인')}</button><button className="button-outline" disabled={busy} onClick={() => void run(async current => { const request = new AbortController(); accountRequest.current?.abort(); accountRequest.current = request; const accountDocument = await bindings.open(book, identityClient, request.signal); if (!accountDocument) throw new Error('먼저 서버 원본을 확인하고 계정 기록에 연결해 주세요.'); const anchor = await notes.getPosition(accountDocument); if (current()) setReading({ book, anchor, accountDocument }) })}>{t('계정 기록으로 읽기')}</button></div></div>}/> : <p>{t('ZIP으로 가져온 책이 없습니다.')}</p>}
    {verifying && <LibraryIdentityVerifier key={`${username}:${verifying.id}`} book={verifying} username={username} client={identityClient} onConfirm={(result, signal) => bindings.confirm(verifying, result, signal)} onUnbind={() => bindings.remove(verifying)} onClose={() => setVerifying(undefined)}/>}
  </section>
}
