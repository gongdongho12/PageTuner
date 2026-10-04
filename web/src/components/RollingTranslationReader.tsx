import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { PagedReader, type PagedReaderProps } from './PagedReader'
import type { StoredChapter, StartTranslation, TranslationProvider } from '../lib/workflowTypes'
import { RollingTranslationSession, type RollingSnapshot } from '../lib/rollingTranslation'
import type { ReadingPagination, ReadingPace, ReadingTranslationClient, ReadingTranslationSettings } from '../lib/readingTranslation'
import type { ReadingAnchor } from '../lib/offline'
import { translate as t } from '../lib/locale'
import { usePersonalLibrary } from './usePersonalLibrary'
import { useBookGlossary } from './BookGlossaryProvider'
import { bookGlossaryScope, selectedBookGlossary } from '../lib/bookGlossaryProjection'
import { useReadingProgress } from './ReadingProgressProvider'
import { normalizeGlossary, type GlossaryEntry } from '../lib/glossary'
import { TranslationSetup } from './TranslationSetup'
import type { ProviderCheckHandler } from './ProviderCheckSession'
import './rollingTranslation.css'
import { initialReadingTranslationTarget, sameReadingTranslationLanguage } from './readingTranslationLanguage'

type ReaderClient = ReadingTranslationClient & { providers?: (signal?: AbortSignal) => Promise<TranslationProvider[]>; checkProvider?: ProviderCheckHandler }
export type RollingTranslationReaderProps = PagedReaderProps & { source?: StoredChapter; client?: ReaderClient | null; settings?: Partial<ReadingTranslationSettings> }
export function RollingTranslationReader(props: RollingTranslationReaderProps) {
  if (!props.source || !props.client || props.preview || props.readOnly || props.document.kind !== 'original') return <PagedReader {...props} glossaryTargetLanguage={props.glossaryTargetLanguage ?? props.settings?.targetLanguage}/>
  return <ActiveRollingReader key={`${props.source.recordId}:${props.source.sourceRevision}`} {...props} source={props.source} client={props.client}/>
}
const initial: RollingSnapshot = { enabled: false, running: false, waiting: false, error: '', page: 0, totalPages: 0, windowStart: 0, windowEnd: 0, readyPages: 0, items: [], cacheCount: 0, limited: false }
type Speed = { wpm: number; pace: ReadingPace }
function readSpeed(namespace?: string): Speed {
  try {
    const raw = namespace ? localStorage.getItem(`pageturner.reading-translation:${encodeURIComponent(namespace)}`) : null
    if (raw && raw.length <= 200) { const value = JSON.parse(raw); if (Number.isInteger(value.wpm) && value.wpm >= 120 && value.wpm <= 420 && ['READING', 'FAST', 'OFFLINE_PREFETCH'].includes(value.pace)) return value }
  } catch { /* Optional speed settings must not prevent reading. */ }
  return { wpm: 210, pace: 'READING' }
}
function ActiveRollingReader(props: RollingTranslationReaderProps & { source: StoredChapter; client: ReaderClient }) {
  const [snapshot, setSnapshot] = useState(initial), [speed, setSpeed] = useState(() => readSpeed(props.notesNamespace))
  const [target, setTarget] = useState(() => initialReadingTranslationTarget(props.settings?.sourceLanguage ?? props.source.sourceLanguage, props.settings?.targetLanguage))
  const [settingsOpen, setSettingsOpen] = useState(false), [showTranslation, setShowTranslation] = useState(false), [settingsError, setSettingsError] = useState('')
  const [connectionOpen, setConnectionOpen] = useState(false), [providers, setProviders] = useState<TranslationProvider[]>([]), [preparing, setPreparing] = useState(false)
  const [panelOpen, setPanelOpen] = useState(false)
  const [statusDetail, setStatusDetail] = useState<string>()
  const [providerSettings, setProviderSettings] = useState<Partial<ReadingTranslationSettings>>(props.settings ?? {})
  const [glossary, setGlossary] = useState<GlossaryEntry[] | undefined>()
  const personal = usePersonalLibrary(props.notesNamespace ?? '')
  const accountGlossary = useBookGlossary(props.notesNamespace ?? '', bookGlossaryScope(props.source.providerId, props.source.bookId, target))
  const accountRef = useRef(accountGlossary), targetRef = useRef(target)
  accountRef.current = accountGlossary; targetRef.current = target
  async function readGlossary(language: string, fallback: GlossaryEntry[] = []) {
    const account = accountRef.current, scope = bookGlossaryScope(props.source.providerId, props.source.bookId, language)
    if (scope && account.supported) {
      if (!account.readCurrent) throw new Error('계정 용어집을 확인한 뒤 번역할 수 있습니다. 다시 시도해 주세요.')
      const state = await account.readCurrent(scope)
      const selected = selectedBookGlossary(state, [])
      if (state.enabled) return selected
    }
    const legacy = personal ? (await personal.getGlossary(props.source.providerId, props.source.bookId)).entries : fallback
    return normalizeGlossary(legacy)
  }
  const operation = useRef(0)
  const [sourceAnchor, setSourceAnchor] = useState(props.anchor), [sourceMount, setSourceMount] = useState(0)
  // The source reader is unmounted while displaying temporary translations. Keep its
  // shared progress controller available for explicit source-page navigation there.
  const sourceProgress = useReadingProgress(props.document, props.notesNamespace, props.anchor, true)
  const pagination = useRef<ReadingPagination | undefined>(undefined), mounted = useRef(true)
  const options = useMemo<ReadingTranslationSettings>(() => ({ providerKind: providerSettings.providerKind ?? 'GOOGLE_WEB_TRANSLATE_HTML',
    targetLanguage: target, sourceLanguage: providerSettings.sourceLanguage ?? props.source.sourceLanguage,
    endpoint: providerSettings.endpoint, model: providerSettings.model, apiKey: providerSettings.apiKey, glossary: glossary ?? providerSettings.glossary,
    readingWordsPerMinute: speed.wpm, paceMode: speed.pace }), [providerSettings, props.source.sourceLanguage, target, speed, glossary])
  const resource = useMemo(() => {
    const active = { value: true }
    const session = new RollingTranslationSession(props.source, options, props.client, value => { if (active.value) setSnapshot(value) }, {
      readGlossary: personal ? async () => {
        const entries = await readGlossary(targetRef.current)
        if (active.value) setGlossary(previous => JSON.stringify(previous) === JSON.stringify(entries) ? previous : entries)
        return entries
      } : undefined,
    })
    return { active, session }
  }, [props.source.recordId, props.source.sourceRevision, props.client, personal])
  const session = resource.session
  useEffect(() => { mounted.current = true; resource.active.value = true; return () => { mounted.current = false; resource.active.value = false; operation.current++; void session.stop() } }, [resource, session])
  useEffect(() => { session.configure(options) }, [session, options])
  const sameOriginal = useMemo(() => props.source.paragraphs.length === props.document.paragraphs.length && props.source.paragraphs.every((paragraph, index) =>
    paragraph.paragraphId === props.document.paragraphs[index].paragraphId && paragraph.text === props.document.paragraphs[index].text), [props.source, props.document])
  const layoutChanged = useCallback((value: ReadingPagination) => {
    if (value.documentId !== props.document.id || !sameOriginal) return
    pagination.current = value
    try { session.setPagination(value) } catch (error) { setSettingsError(error instanceof Error ? error.message : '읽기 페이지를 확인해 주세요.') }
    props.onPaginationChange?.(value)
  }, [session, props.document.id, props.onPaginationChange, sameOriginal])
  const originalMoved = useCallback((anchor: ReadingAnchor) => { setSourceAnchor(anchor); props.onAnchorChange(anchor) }, [props.onAnchorChange])
  // A late translation must not unmount an open tool or discard its unsaved draft.
  const translateVisible = showTranslation && snapshot.items.length > 0 && !panelOpen
  const noticeError = snapshot.error || settingsError || (!sameOriginal ? '읽기 번역에는 서버에 저장된 같은 원문이 필요합니다.' : '')
  const notice = noticeError || (snapshot.running && !snapshot.items.length
    ? snapshot.waiting ? '읽기 속도에 맞춰 다음 쪽을 준비합니다.' : '현재 쪽부터 번역을 준비하고 있습니다.'
    : snapshot.limited ? '메모리 한도로 일부 선행 번역을 보류했습니다. 해당 쪽으로 이동하면 현재 쪽부터 준비합니다.' : '')
  function turnSource(direction: -1 | 1) {
    const layout = pagination.current; if (!layout) return
    const page = Math.max(0, Math.min(layout.pages.length - 1, snapshot.page + direction)), first = layout.pages[page]?.[0]
    if (page === snapshot.page || !first) return
    const anchor = { paragraphId: first.paragraphId, characterOffset: first.start }
    setSourceAnchor(anchor); setSourceMount(value => value + 1); props.onAnchorChange(anchor); sourceProgress.move(anchor)
    const next = { ...layout, page }; pagination.current = next; session.setPagination(next)
  }
  function updateSpeed(value: Speed) {
    setSpeed(value); setSettingsError('')
    try { if (props.notesNamespace) localStorage.setItem(`pageturner.reading-translation:${encodeURIComponent(props.notesNamespace)}`, JSON.stringify(value)) }
    catch { setSettingsError('속도 설정은 이번 읽기에만 적용됩니다.') }
  }
  async function begin(supplied: ReadingTranslationSettings = options) {
    if (sameReadingTranslationLanguage(supplied.sourceLanguage ?? props.source.sourceLanguage, supplied.targetLanguage)) {
      setSettingsError('원문과 번역 언어를 다르게 선택해 주세요.'); return
    }
    const version = ++operation.current; setPreparing(true); setSettingsError('')
    try {
      await session.stop()
      const entries = await readGlossary(supplied.targetLanguage, supplied.glossary ?? [])
      if (!mounted.current || version !== operation.current) return
      setGlossary(entries); session.configure({ ...supplied, glossary: entries }); session.start(); setShowTranslation(true)
    } catch (error) { if (mounted.current && version === operation.current) setSettingsError(error instanceof Error ? error.message : '용어집을 불러오지 못했습니다.') }
    finally { if (mounted.current && version === operation.current) setPreparing(false) }
  }
  function openSettings() { operation.current++; void session.stop(); setPreparing(false); setSettingsOpen(true); setSourceMount(value => value + 1) }
  async function openConnection() {
    if (!props.client.providers || !props.notesNamespace) return
    const version = ++operation.current; setPreparing(true); setSettingsError('')
    try { const available = await props.client.providers(); if (mounted.current && version === operation.current) { setProviders(available); setConnectionOpen(true) } }
    catch (error) { if (mounted.current && version === operation.current) setSettingsError(error instanceof Error ? error.message : '번역기 목록을 불러오지 못했습니다.') }
    finally { if (mounted.current && version === operation.current) setPreparing(false) }
  }
  async function applyConnection(input: StartTranslation) {
    const selected = { providerKind: input.providerKind, sourceLanguage: input.sourceLanguage, targetLanguage: input.targetLanguage,
      endpoint: input.endpoint, model: input.model, apiKey: input.apiKey, glossary: input.glossary }
    setProviderSettings(selected); setTarget(input.targetLanguage); setConnectionOpen(false); setSettingsOpen(false)
    await begin({ ...selected, readingWordsPerMinute: speed.wpm, paceMode: speed.pace })
  }
  const preview = useMemo(() => ({ id: `reading-preview:${props.source.recordId}:${snapshot.page}`, bookTitle: props.document.bookTitle,
    chapterTitle: `${props.document.chapterTitle} · ${t('원문 쪽')} ${snapshot.page + 1}`, language: target, kind: 'translation' as const,
    paragraphs: snapshot.items.map((item, index) => ({ paragraphId: `reading-fragment-${index}`, text: item.text })) }),
    [props.source.recordId, props.document.bookTitle, props.document.chapterTitle, snapshot.page, snapshot.items, target])
  if (statusDetail) return <PagedReader document={{ id: 'reading-translation-status', bookTitle: props.document.bookTitle,
    chapterTitle: t('읽기 번역'), language: props.document.language, kind: 'introduction',
    paragraphs: [{ paragraphId: 'status', text: statusDetail }] }} preview readOnly notesNamespace={props.notesNamespace}
    onClose={() => setStatusDetail(undefined)} onAnchorChange={() => {}}/>
  if (settingsOpen) return <section className="rolling-reader" aria-label={t('읽기 번역 설정')}>
    {connectionOpen ? <TranslationSetup readingPreview chapter={props.source} providers={providers} username={props.notesNamespace ?? ''} busy={preparing}
      onCheckProvider={props.client.checkProvider}
      defaultTargetLanguage={target} initialSettings={options} onBack={() => setConnectionOpen(false)} onSubmit={applyConnection}/> : <>
      <div className="rolling-controls"><button type="button" onClick={() => setSettingsOpen(false)}>{t('읽기로 돌아가기')}</button><strong>{t('읽기 번역 설정')}</strong></div>
      <div className="rolling-settings rolling-settings-page">
        <label>{t('분당 읽는 단어')}<input type="number" min={120} max={420} step={10} value={speed.wpm} onChange={event => { const wpm = Number(event.target.value); if (Number.isInteger(wpm) && wpm >= 120 && wpm <= 420) updateSpeed({ ...speed, wpm }) }}/></label>
        <label>{t('번역 속도')}<select value={speed.pace} onChange={event => updateSpeed({ ...speed, pace: event.target.value as ReadingPace })}>
          <option value="READING">{t('읽는 속도에 맞춤')}</option><option value="FAST">{t('빠르게')}</option><option value="OFFLINE_PREFETCH">{t('미리 준비')}</option>
        </select></label>
        <label>{t('번역 언어')}<select value={target} onChange={event => setTarget(event.target.value)}>
          {[...new Set(['ko', 'en', 'ja', 'zh-CN', 'zh-TW', 'es', 'fr', 'de', 'pt', 'it', 'ru', 'vi', 'id', 'th', target])].map(language => <option key={language} value={language}>{language}</option>)}
        </select></label>
        {props.client.providers && props.notesNamespace && <button type="button" disabled={preparing} onClick={() => void openConnection()}>{t('번역기 · API 설정')}</button>}
        <button type="button" disabled={preparing} onClick={() => { setSettingsOpen(false); void begin() }}>{t('현재 쪽 번역')}</button>
        <p>{t('임시 읽기 번역입니다. 전체 번역을 보관하려면 기존 전체 번역 작업을 실행하세요.')}</p>
      </div>
    </>}
    {settingsError && <div className="rolling-notice" role="alert">{t(settingsError)}</div>}
  </section>
  return <section className="rolling-reader" aria-label={t('읽기 번역')}>
    {!panelOpen && <div className="rolling-controls rolling-main-controls">
      <button type="button" disabled={!sameOriginal || !snapshot.totalPages || preparing} aria-pressed={snapshot.enabled} onClick={() => {
        if (snapshot.enabled) { operation.current++; void session.stop() } else void begin()
      }}>{t(snapshot.enabled ? '번역 중지' : '읽으며 번역')}</button>
      <button type="button" aria-pressed={translateVisible} disabled={!snapshot.items.length} onClick={() => { setShowTranslation(value => !value); setSourceMount(value => value + 1) }}>{t(translateVisible ? '원문' : '임시 번역')}</button>
      <button type="button" aria-expanded={settingsOpen} onClick={openSettings}>{t('읽기 번역 설정')}</button>
      <div className="rolling-toolbar-status" role={noticeError ? 'alert' : 'status'}>
        {notice ? <button type="button" className="rolling-status-detail" onClick={() => setStatusDetail(t(notice))}><span>{t(notice)}</span></button>
          : <span>{t('현재 원문 {0}/{1}쪽 · 준비 {2}/{3}쪽', [snapshot.page + 1, snapshot.totalPages, snapshot.readyPages, snapshot.windowEnd - snapshot.windowStart])}</span>}
        {snapshot.error && <button type="button" disabled={preparing} onClick={() => void begin()}>{t('다시 시도')}</button>}
      </div>
    </div>}
    <div className="rolling-pane">
      {translateVisible ? <PagedReader key={`preview:${snapshot.page}:${preview.paragraphs[0]?.text}`} document={preview} preview readOnly
        notesNamespace={props.notesNamespace} glossaryEntriesOverride={glossary} onClose={props.onClose} onAnchorChange={() => {}} onBoundaryPageTurn={turnSource}
        hasPreviousBoundary={snapshot.page > 0} hasNextBoundary={snapshot.page + 1 < snapshot.totalPages}
        editionLabel={t('임시 번역')} positionNote={t('임시 읽기 번역입니다. 전체 번역을 보관하려면 기존 전체 번역 작업을 실행하세요.')}/>
        : <PagedReader {...props} glossaryTargetLanguage={target} key={`original:${sourceMount}`} anchor={sourceAnchor} onAnchorChange={originalMoved} onPaginationChange={layoutChanged} onPanelChange={setPanelOpen}/>}
    </div>
    {translateVisible && !panelOpen && <div className="rolling-source-navigation">
      <button type="button" disabled={snapshot.page === 0} onClick={() => turnSource(-1)}>{t('이전 원문 쪽')}</button>
      <span>{t('임시 번역')}</span>
      <button type="button" disabled={snapshot.page + 1 >= snapshot.totalPages} onClick={() => turnSource(1)}>{t('다음 원문 쪽')}</button>
    </div>}
  </section>
}
