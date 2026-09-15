import { useEffect, useMemo, useRef, useState } from 'react'
import { translate as t } from '../lib/locale'
import { createExchangeLibrary, exchangeExportChoices, exchangeReadingDocument, exchangePdfDocument, mergeExchangePackages, type ExchangeExportChoice, type SavedExchange } from '../lib/exchangeLibrary'
import { readExchange, writeExchange, exchangeLimits, type ExchangePackage } from '../lib/libraryExchange'
import { createReadingNotes } from '../lib/readingNotes'
import type { ReadingAnchor } from '../lib/offline'
import { AdaptiveCollection } from './AdaptiveCollection'
import { PagedReader } from './PagedReader'
import { LocalPdfReader } from './LocalPdfReader'
import { ExchangeAssetReader } from './ExchangeAssetReader'
import './readingTools.css'

export function LibraryExchangeWorkspace({ username, onReadingChange }: { username: string; onReadingChange: (reading: boolean) => void }) {
  const storage = useMemo(() => createExchangeLibrary(username), [username]), notes = useMemo(() => createReadingNotes(username), [username])
  const [tab, setTab] = useState<'import' | 'export' | 'books'>('import'), [books, setBooks] = useState<SavedExchange[]>([]), [choices, setChoices] = useState<ExchangeExportChoice[]>([])
  const [selected, setSelected] = useState<string[]>([]), [preview, setPreview] = useState<ExchangePackage>(), [busy, setBusy] = useState(false), [error, setError] = useState(''), [notice, setNotice] = useState('')
  const [reading, setReading] = useState<{ book: SavedExchange; anchor?: ReadingAnchor }>()
  const operation = useRef(0)
  useEffect(() => { onReadingChange(!!reading); return () => onReadingChange(false) }, [!!reading, onReadingChange])
  useEffect(() => { return () => { operation.current++ } }, [username])
  const run = async (action: (current: () => boolean) => Promise<void>) => {
    if (busy) return
    const id = ++operation.current; setBusy(true); setError(''); setNotice('')
    try { await action(() => id === operation.current) } catch (e) { if (id === operation.current) setError(e instanceof Error ? e.message : t('ZIP 작업을 완료하지 못했습니다.')) }
    finally { if (id === operation.current) setBusy(false) }
  }
  const loadTab = (next: typeof tab) => {
    setTab(next); setPreview(undefined); setSelected([])
    void run(async current => { const values = next === 'export' ? await exchangeExportChoices(username) : next === 'books' ? await storage.list() : undefined; if (current() && values) { if (next === 'export') setChoices(values as ExchangeExportChoice[]); else setBooks(values as SavedExchange[]) } })
  }
  const exportSelected = () => void run(async current => {
    const packages: ExchangePackage[] = []
    for (const choice of choices.filter(c => selected.includes(c.key))) { packages.push(await choice.load()); if (!current()) return }
    const data = await writeExchange(await mergeExchangePackages(packages)); if (!current()) return
    const url = URL.createObjectURL(new Blob([Uint8Array.from(data).buffer], { type: 'application/zip' })), link = document.createElement('a')
    link.href = url; link.download = `PageTurner-${new Date().toISOString().slice(0, 10)}.ptlibrary.zip`; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000)
    setNotice(t('ZIP 파일을 내보냈습니다. 앱이나 다른 PC의 ZIP 가져오기에서 열어 주세요.'))
  })
  if (reading) {
    if (reading.book.document.assets.some(a => a.role === 'pdf') || (reading.book.document.assets.length > 0 && !reading.book.document.paragraphs.some(p => p.text.trim()))) return <ExchangeAssetReader saved={reading.book} username={username} onClose={() => setReading(undefined)}/>
    const document = exchangeReadingDocument(reading.book), pdf = exchangePdfDocument(reading.book)
    const remember = (anchor: ReadingAnchor) => { void notes.setPosition(document, anchor).catch(e => setError(e instanceof Error ? e.message : '')) }
    if (pdf) return <LocalPdfReader document={pdf} namespace={username} anchor={reading.anchor} onClose={() => setReading(undefined)} onAnchorChange={remember} actionError={error}/>
    return <PagedReader document={document} notesNamespace={username} anchor={reading.anchor} onClose={() => setReading(undefined)} onAnchorChange={remember} positionNote={t('ZIP으로 가져온 책입니다. 변경한 독서 기록도 다시 내보낼 수 있습니다.')} actionError={error}/>
  }
  return <section className="reading-workspace" aria-label={t('앱 · 웹 ZIP 교환')}>
    <nav className="workflow-subtabs" aria-label={t('ZIP 교환 메뉴')}>{(['import', 'export', 'books'] as const).map(value => <button key={value} disabled={busy} aria-pressed={tab === value} onClick={() => loadTab(value)}>{t(value === 'import' ? 'ZIP 가져오기' : value === 'export' ? 'ZIP 내보내기' : '가져온 책')}</button>)}</nav>
    {error && <div className="workflow-message" role="alert">{t(error)} <button onClick={() => setError('')}>{t('닫기')}</button></div>}
    {notice && <div className="workflow-message" role="status">{notice} <button onClick={() => setNotice('')}>{t('닫기')}</button></div>}
    {busy && <div role="status">{t('ZIP 파일을 검증하고 있습니다…')}</div>}
    {tab === 'import' ? preview ? <>
      <p>{t('{0}개 문서를 가져올 준비가 되었습니다.', [preview.documents.length])}</p>
      <AdaptiveCollection items={preview.documents} itemKey={d => d.id} rowHeight={104} renderItem={d => <div className="reading-note-row"><strong>{d.bookTitle}</strong><span>{d.chapterTitle} · {d.paragraphs.length} {t('문단')} · {d.notes.length} {t('읽기 기록')}</span></div>}/>
      <div className="reading-filter-actions"><button className="button-outline" disabled={busy} onClick={() => setPreview(undefined)}>{t('취소')}</button><button className="button-primary" disabled={busy} onClick={() => void run(async current => { if (!current()) return; const report = await storage.importPackage(preview); if (!current()) return; setBooks(await storage.list()); setPreview(undefined); setTab('books'); setNotice(t('{0}개 추가, {1}개 기존 문서 유지. 기존 읽던 위치와 메모를 보존했습니다.', [report.added, report.existing])) })}>{t('이 계정의 기기에 가져오기')}</button></div>
    </> : <div className="reading-import"><AdaptiveCollection items={['file', 'scope', 'limits']} itemKey={v => v} rowHeight={136} renderItem={value => <div className="reading-field">{value === 'file' ? <><label htmlFor="library-exchange-file">{t('앱 또는 웹에서 내보낸 ZIP 파일')}</label><input id="library-exchange-file" type="file" accept=".zip,.ptlibrary.zip,application/zip" disabled={busy} onChange={event => { const file = event.target.files?.[0]; event.target.value = ''; if (file) void run(async current => { if (!file.size || file.size > exchangeLimits.archive) throw new Error('ZIP 파일은 32MB 이하로 선택해 주세요.'); const value = await readExchange(new Uint8Array(await file.arrayBuffer())); if (current()) setPreview(value) }) }}/></> : <p>{t(value === 'scope' ? '원문·번역본, PDF·삽화, 북마크·메모·강조·읽던 위치와 분류·용어집을 옮깁니다. 가져온 책은 이 계정의 기기에 저장됩니다.' : '규격 v1 · ZIP 32MB · 압축 해제 64MB · 최대 100개 문서. 파일 전체 검증 후 저장하며 기존 문서는 덮어쓰지 않습니다.')}</p>}</div>}/></div>
    : tab === 'export' ? <>
      <p>{t('기기에 보관된 책을 선택하세요. 서버 책은 먼저 기기에 보관해 주세요.')}</p>
      {choices.length ? <AdaptiveCollection items={choices} itemKey={c => c.key} rowHeight={104} renderItem={choice => <label className="reading-note-row reading-checkbox"><input type="checkbox" checked={selected.includes(choice.key)} disabled={busy} onChange={event => setSelected(values => event.target.checked ? [...values, choice.key] : values.filter(v => v !== choice.key))}/><strong>{choice.title}</strong><span>{choice.kind}</span></label>}/> : <p>{t('내보낼 기기 보관 문서가 없습니다.')}</p>}
      <div className="reading-filter-actions"><span>{t('{0}개 선택', [selected.length])}</span><button className="button-primary" disabled={busy || selected.length === 0 || selected.length > 100} onClick={exportSelected}>{t('선택한 책을 ZIP으로 내보내기')}</button></div>
    </> : books.length ? <AdaptiveCollection items={books} itemKey={book => book.id} rowHeight={112} renderItem={book => <div className="reading-note-row"><strong>{book.document.bookTitle}</strong><span>{book.document.chapterTitle} · {book.document.language} · {book.document.organization.folder}</span><button className="button-outline" disabled={busy} onClick={() => void run(async current => { const anchor = await notes.getPosition(exchangeReadingDocument(book)); if (current()) setReading({ book, anchor }) })}>{t('읽기')}</button></div>}/> : <p>{t('ZIP으로 가져온 책이 없습니다.')}</p>}
  </section>
}
