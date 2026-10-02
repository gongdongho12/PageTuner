import { useEffect, useMemo, useRef, useState } from 'react'
import { AdaptiveCollection } from '../components/AdaptiveCollection'
import { PagedReader } from '../components/PagedReader'
import { LocalPdfReader } from '../components/LocalPdfReader'
import { ReaderSearch } from '../components/ReaderSearch'
import { ReaderPreferencesPanel, VisitReaderPreferencesProvider, useReaderPreferences } from '../components/ReaderPreferences'
import { useLocale } from '../lib/locale'
import type { ReadingAnchor } from '../lib/offline'
import { createPhoneLibraryGateway, SharingError, type LibraryGateway, type SharedReading, type SharedLibraryPage } from './libraryGateway'
import '../components/readingTools.css'
import './sharing.css'

const errorMessages: Record<string, string> = {
  session_expired: '공유 연결이 끝났습니다. 휴대폰에서 공유를 시작하고 다시 연결해 주세요.',
  pair_failed: '연결 코드를 확인해 주세요. 공유 시간이 끝났다면 휴대폰에서 다시 시작해 주세요.',
  rate_limited: '연결 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.',
  revision_changed: '휴대폰의 문서가 변경되었습니다. 서재에서 다시 열어 주세요.',
  not_found: '이 문서를 공유할 수 없습니다. 휴대폰의 서재를 확인해 주세요.',
  too_large: '이 문서는 공유 읽기의 크기 제한을 초과합니다. 휴대폰에서 직접 열어 주세요.',
  invalid_response: '공유 데이터를 확인할 수 없습니다. 휴대폰 앱을 업데이트하고 다시 연결해 주세요.',
  unavailable: '휴대폰에서 이 요청을 처리하지 못했습니다. 공유 상태를 확인해 주세요.',
  connection_lost: '휴대폰에 연결할 수 없습니다. 같은 Wi-Fi나 핫스팟에 연결되어 있는지 확인해 주세요.',
}

export default function SharingApp({ gateway: supplied }: { gateway?: LibraryGateway }) {
  const gateway = useMemo(() => supplied ?? createPhoneLibraryGateway(), [supplied])
  return <VisitReaderPreferencesProvider><SharingSession gateway={gateway}/></VisitReaderPreferencesProvider>
}

function SharingSession({ gateway }: { gateway: LibraryGateway }) {
  const { t, locale, setLocale } = useLocale()
  const { reset: resetPreferences } = useReaderPreferences()
  const [code, setCode] = useState(''), [connected, setConnected] = useState(false), [deadline, setDeadline] = useState(0)
  const [page, setPage] = useState<SharedLibraryPage>(), [reading, setReading] = useState<SharedReading>(), [anchor, setAnchor] = useState<ReadingAnchor>()
  const [batchStart, setBatchStart] = useState<'first' | 'last'>('first')
  const [panel, setPanel] = useState<'reader' | 'tools'>('reader'), [navigation, setNavigation] = useState(0)
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [available, setAvailable] = useState(false)
  const [guide, setGuide] = useState(false)
  const operation = useRef(0), request = useRef<AbortController | undefined>(undefined), positions = useRef(new Map<string, ReadingAnchor>())
  const forgetView = () => { operation.current++; request.current?.abort(); positions.current.clear(); resetPreferences(); setConnected(false); setDeadline(0); setReading(undefined); setPage(undefined); setAnchor(undefined); setPanel('reader'); setCode(''); setBusy(false) }
  useEffect(() => {
    const controller = new AbortController()
    void gateway.status(controller.signal).then(() => setAvailable(true)).catch(failure => { if (!controller.signal.aborted) setError(errorMessages[failure instanceof SharingError ? failure.code : 'connection_lost'] ?? errorMessages.unavailable) })
    return () => { controller.abort(); request.current?.abort(); gateway.forget() }
  }, [gateway])
  useEffect(() => {
    if (!deadline) return
    const expired = () => { gateway.forget(); forgetView(); setError(errorMessages.session_expired) }
    const timer = setTimeout(expired, Math.max(0, Math.min(2_147_483_647, deadline - Date.now())))
    const check = () => { if (Date.now() >= deadline) expired() }
    window.addEventListener('focus', check); document.addEventListener('visibilitychange', check)
    return () => { clearTimeout(timer); window.removeEventListener('focus', check); document.removeEventListener('visibilitychange', check) }
  }, [deadline, gateway])
  async function run(action: (signal: AbortSignal) => Promise<() => void>) {
    request.current?.abort(); const controller = new AbortController(); request.current = controller; const current = ++operation.current
    setBusy(true); setError('')
    try { const commit = await action(controller.signal); if (current === operation.current && !controller.signal.aborted) commit() }
    catch (failure) {
      if (current !== operation.current || controller.signal.aborted) return
      const code = failure instanceof SharingError ? failure.code : 'connection_lost'
      if (code === 'session_expired') { gateway.forget(); forgetView() }
      else if (!connected) gateway.forget()
      setError(errorMessages[code] ?? errorMessages.unavailable)
    } finally { if (current === operation.current) setBusy(false) }
  }
  function load(offset: number, start: 'first' | 'last' = 'first') { void run(async signal => {
    let next = await gateway.list(offset, signal)
    if (offset > 0 && offset >= next.total) next = await gateway.list(Math.max(0, Math.floor((next.total - 1) / next.limit) * next.limit), signal)
    return () => { setPage(next); setBatchStart(start) }
  }) }
  function remember(value: ReadingAnchor) { if (reading) positions.current.set(`${reading.source.id}:${reading.source.revision}`, value); setAnchor(value) }
  function jump(value: ReadingAnchor) { remember(value); setNavigation(value => value + 1); setPanel('reader') }
  function close() { setReading(undefined); setPanel('reader') }
  function disconnect() { forgetView(); void gateway.disconnect().catch(() => {}) }
  return <main className={`sharing-app${reading ? ' sharing-reading' : ''}`}>
    <header className="sharing-header"><strong>{t('휴대폰 서재 공유')}</strong><select aria-label={t('언어')} value={locale.startsWith('ko') ? 'ko' : 'en'} onChange={event => setLocale(event.target.value)}><option value="ko">한국어</option><option value="en">English</option></select>{connected && <button className="button-outline" onClick={disconnect}>{t('연결 해제')}</button>}</header>
    {error && <div role="alert" className="sharing-error"><span>{t(error)}</span><button className="button-quiet" onClick={() => setError('')}>{t('닫기')}</button></div>}
    {!connected && guide ? <section className="sharing-library"><header className="reading-tools-header"><button className="button-quiet" onClick={() => setGuide(false)}>{t('돌아가기')}</button><strong>{t('휴대폰 공유 안내')}</strong></header><AdaptiveCollection mode="paged" items={[
      '휴대폰과 같은 Wi-Fi 또는 핫스팟에 연결하고, 앱의 공유 화면에 표시된 주소와 연결 코드를 사용하세요.',
      '인터넷과 계정 로그인이 필요 없습니다. 휴대폰 데이터는 읽기 전용으로 열립니다.',
      '클라우드 웹이 아니라 휴대폰 앱이 표시한 주소를 열어 주세요.',
      'HTTP 공유는 신뢰하는 Wi-Fi나 개인 핫스팟에서 사용하세요. 공유를 끄면 연결이 종료됩니다.',
    ]} itemKey={item => item} rowHeight={148} renderItem={item => <div className="reading-field"><p>{t(item)}</p></div>}/></section>
    : !connected ? <section className="sharing-connect"><h1>{t('휴대폰의 책을 이 화면에서 읽기')}</h1><p>{t(available ? '휴대폰 연결 코드' : '클라우드 웹이 아니라 휴대폰 앱이 표시한 주소를 열어 주세요.')}</p>
      <form onSubmit={event => { event.preventDefault(); void run(async signal => { await gateway.status(signal); setAvailable(true); const expires = await gateway.pair(code.trim(), signal); const first = await gateway.list(0, signal); return () => { setConnected(true); setDeadline(expires); setPage(first); setCode('') } }) }}>
        <label htmlFor="sharing-code">{t('휴대폰 연결 코드')}</label><input id="sharing-code" autoComplete="off" spellCheck={false} maxLength={32} value={code} onChange={event => setCode(event.target.value)} disabled={busy}/><button className="button-primary" disabled={busy || !code.trim()}>{t(busy ? '연결 중…' : '휴대폰에 연결')}</button>
      </form><button className="button-quiet" onClick={() => setGuide(true)}>{t('휴대폰 공유 안내')}</button>
    </section> : reading ? <div className="sharing-reader-slot">
      {panel === 'tools' ? <SharedReadTools reading={reading} onClose={() => setPanel('reader')} onJump={jump}/>
        : reading.source.format === 'pdf' ? <LocalPdfReader key={`${reading.source.id}:${reading.source.revision}:${navigation}`} document={reading.document} namespace="" anchor={anchor} onClose={close} onAnchorChange={remember} showReadingTools={false} onReadOnlyTools={() => setPanel('tools')}/>
        : <PagedReader key={`${reading.source.id}:${reading.source.revision}:${navigation}`} document={reading.document} anchor={anchor} readOnly onClose={close} onAnchorChange={remember} editionLabel={t('휴대폰 공유 · 읽기 전용')} contentKindLabel={t(reading.source.edition === 'translation' ? '번역문' : '원문')} positionNote={t('읽기 위치는 이 연결에서만 기억합니다.')} actionLabel={t('읽기 도구')} onAction={() => setPanel('tools')}/>}
    </div> : <section className="sharing-library" aria-label={t('휴대폰 서재')}><div className="sharing-library-header"><h1>{t('휴대폰 서재')}</h1><button className="button-outline" disabled={busy} onClick={() => load(page?.offset ?? 0)}>{t('새로고침')}</button></div><p className="sharing-caption">{t('휴대폰 공유 · 읽기 전용')}{busy ? ` · ${t('불러오는 중…')}` : ''}</p>
      {page?.items.length ? <AdaptiveCollection key={page.offset} items={page.items} itemKey={item => item.id} rowHeight={112} total={page.total} offset={page.offset} initialPage={batchStart} onPreviousBatch={!busy && page.offset > 0 ? () => load(Math.max(0, page.offset - page.limit), 'last') : undefined} onNextBatch={!busy && page.offset + page.items.length < page.total ? () => load(page.offset + page.limit) : undefined} renderItem={book => <div className="sharing-book"><div><strong title={book.title}>{book.title}</strong><span>{book.format.toUpperCase()} · {t(book.edition === 'translation' ? '번역문' : '원문')}</span></div><button className="button-outline" disabled={busy} onClick={() => void run(async signal => { const next = await gateway.read(book.id, signal); return () => { setReading(next); setAnchor(positions.current.get(`${next.source.id}:${next.source.revision}`) ?? next.anchor); setPanel('reader'); setNavigation(0) } })}>{t('읽기')}</button></div>}/>
        : <div className="empty-state"><h2>{t('공유할 수 있는 책이 없습니다.')}</h2><p>{t('휴대폰에서 책을 저장한 뒤 새로고침해 주세요.')}</p></div>}
    </section>}
  </main>
}

function SharedReadTools({ reading, onClose, onJump }: { reading: SharedReading; onClose: () => void; onJump: (anchor: ReadingAnchor) => void }) {
  const { t } = useLocale(), [tab, setTab] = useState('search')
  const document = reading.document
  if (tab === 'settings') return <ReaderPreferencesPanel namespace="" onClose={() => setTab('search')}/>
  return <section className="reading-workspace"><header className="reading-tools-header"><button className="button-quiet" onClick={onClose}>{t('본문으로 돌아가기')}</button><strong>{document.bookTitle}</strong></header><nav className="reading-tool-choice"><select aria-label={t('읽기 도구')} value={tab} onChange={event => setTab(event.target.value)}><option value="search">{t('본문 검색')}</option>{!!document.outline?.length && <option value="outline">{t('목차')}</option>}{!!reading.illustrations.length && <option value="images">{t('삽화')}</option>}<option value="settings">{t('독서 설정')}</option></select></nav>
    {tab === 'search' ? <ReaderSearch document={document} onJump={onJump}/> : tab === 'outline' ? <AdaptiveCollection items={document.outline ?? []} itemKey={item => item.paragraphId} rowHeight={92} renderItem={item => <div className="reading-item"><strong>{item.title}</strong><button className="button-outline" onClick={() => onJump({ paragraphId: item.paragraphId, characterOffset: 0 })}>{t('이동')}</button></div>}/>
      : <AdaptiveCollection items={reading.illustrations} itemKey={item => item.id} rowHeight={260} renderItem={item => <SharedIllustration blob={item.blob} alt={item.alt}/>}/>}<p className="sharing-caption">{t('설정과 읽기 위치는 이 연결에서만 유지됩니다. 휴대폰의 책과 기록은 변경하지 않습니다.')}</p>
  </section>
}
function SharedIllustration({ blob, alt }: { blob: Blob; alt: string }) {
  const [url, setUrl] = useState('')
  useEffect(() => { const next = URL.createObjectURL(blob); setUrl(next); return () => URL.revokeObjectURL(next) }, [blob])
  return <figure className="reading-image">{url && <img src={url} alt={alt}/>}<figcaption>{alt}</figcaption></figure>
}
