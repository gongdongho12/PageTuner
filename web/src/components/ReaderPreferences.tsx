import { createContext, useContext, useEffect, useState, type ReactNode } from 'react'
import { createReaderPreferences, defaultReaderPreferences, readerPreferencesKey, type ReaderPreferences } from '../lib/readerPreferences'
import { translate as t } from '../lib/locale'
import { AdaptiveCollection } from './AdaptiveCollection'
import { createReaderPreferenceStore } from '../lib/readerPreferenceStore'
import { createReaderPreferenceController, type ReaderPreferenceState } from '../lib/readerPreferenceSync'
import type { ReaderPreferenceClient } from '../lib/readerPreferenceApi'
import { projectReaderPreferences, sharedReaderPreferencePatch, sharedReaderPreferences } from '../lib/readerPreferenceProjection'
import { ReaderPreferenceSyncPanel } from './ReaderPreferenceSyncPanel'

const Namespace = createContext('')
const CHANGE_EVENT = 'pageturner-reader-preferences'
type PreferenceController = ReturnType<typeof createReaderPreferenceController>
type Binding = { namespace: string; client: ReaderPreferenceClient | null; controller: PreferenceController; state: ReaderPreferenceState }
const AccountPreferences = createContext<Binding | null>(null)
type VisitPreferences = { preferences: ReaderPreferences; update: (patch: Partial<ReaderPreferences>) => void; reset: () => void }
const VisitPreferencesContext = createContext<VisitPreferences | null>(null)
/** LAN sharing does not require browser storage or create an account preference journal. */
export function VisitReaderPreferencesProvider({ children }: { children: ReactNode }) {
  const [preferences, setPreferences] = useState<ReaderPreferences>({ ...defaultReaderPreferences })
  return <VisitPreferencesContext.Provider value={{ preferences, update: patch => setPreferences(value => ({ ...value, ...patch })), reset: () => setPreferences({ ...defaultReaderPreferences }) }}>{children}</VisitPreferencesContext.Provider>
}
export function ReaderPreferencesProvider({ namespace, client = null, children }: { namespace: string; client?: ReaderPreferenceClient | null; children: ReactNode }) {
  const [binding, setBinding] = useState<Binding | null>(null)
  useEffect(() => {
    if (!namespace) { setBinding(null); return }
    let stopped = false, controller: PreferenceController | null = null, unsubscribe: (() => void) | undefined
    let store: ReturnType<typeof createReaderPreferenceStore> | undefined
    const start = () => {
      if (stopped || controller) return
      // A damaged legacy value must be explicitly reset in the existing settings screen.
      let fallback
      try { fallback = sharedReaderPreferences(createReaderPreferences(namespace, localStorage).load()) } catch { return }
      store = createReaderPreferenceStore(namespace)
      controller = createReaderPreferenceController({ api: client, store })
      const session = controller
      const publish = (state: ReaderPreferenceState) => { if (!stopped) setBinding({ namespace, client, controller: session, state }) }
      unsubscribe = session.subscribe(publish)
      publish(session.snapshot())
      void session.start(fallback).catch(() => undefined)
    }
    const retry = () => {
      start()
      const state = controller?.snapshot()
      if (client && state && (!state.errorCode || ['network', 'timeout', 'server', 'limit'].includes(state.errorCode))) void controller?.refresh().catch(() => undefined)
    }
    start()
    const interval = setInterval(retry, 30_000)
    window.addEventListener('online', retry); window.addEventListener('focus', retry); window.addEventListener(CHANGE_EVENT, start)
    return () => { stopped = true; clearInterval(interval); window.removeEventListener('online', retry); window.removeEventListener('focus', retry); window.removeEventListener(CHANGE_EVENT, start); unsubscribe?.(); if (controller) void controller.close().finally(() => store?.close()); else store?.close() }
  }, [namespace, client])
  const current = binding?.namespace === namespace && binding.client === client ? binding : null
  return <Namespace.Provider value={namespace}><AccountPreferences.Provider value={current}>{children}</AccountPreferences.Provider></Namespace.Provider>
}
export function useReaderPreferenceSync(namespace: string) {
  const binding = useContext(AccountPreferences)
  return binding?.namespace === namespace ? binding : null
}
export function useReaderPreferences(namespace?: string) {
  const visit = useContext(VisitPreferencesContext)
  const inherited = useContext(Namespace), active = namespace ?? inherited
  const sync = useReaderPreferenceSync(active)
  const [value, setValue] = useState<ReaderPreferences>({ ...defaultReaderPreferences }), [error, setError] = useState('')
  useEffect(() => {
    if (visit) return
    const refresh = () => {
      try { setValue(createReaderPreferences(active, localStorage).load()); setError('') }
      catch (error) { setValue({ ...defaultReaderPreferences }); setError(error instanceof Error ? error.message : '독서 설정을 읽지 못했습니다.') }
    }
    refresh()
    const onStorage = (event: StorageEvent) => { if (!event.key || event.key === readerPreferencesKey(active)) refresh() }
    window.addEventListener(CHANGE_EVENT, refresh); window.addEventListener('storage', onStorage)
    return () => { window.removeEventListener(CHANGE_EVENT, refresh); window.removeEventListener('storage', onStorage) }
  }, [active, !!visit])
  function write(patch?: Partial<ReaderPreferences>) {
    try {
      const store = createReaderPreferences(active, localStorage)
      const shared = sharedReaderPreferencePatch(patch ?? defaultReaderPreferences)
      // The account journal is the sole owner of shared values and its durable outbox.
      const devicePatch = patch ? { ...(patch.fontFamily !== undefined ? { fontFamily: patch.fontFamily } : {}), ...(patch.pageKeys !== undefined ? { pageKeys: patch.pageKeys } : {}) } : undefined
      const next = sync ? (devicePatch ? (Object.keys(devicePatch).length ? store.update(devicePatch) : value) : store.reset()) : (patch ? store.update(patch) : store.reset())
      setValue(next); setError('')
      if (sync && Object.keys(shared).length) void sync.controller.update(shared).catch(() => undefined)
      window.dispatchEvent(new Event(CHANGE_EVENT))
    } catch (error) { setError(error instanceof Error ? error.message : '독서 설정을 저장하지 못했습니다.') }
  }
  const preferences = sync && sync.state.status !== 'loading' ? projectReaderPreferences(value, sync.state.local) : value
  const storageError = sync?.state.errorCode === 'storage' ? '이 기기에 계정 설정을 보관하지 못했습니다. 저장 공간을 확인하고 다시 시도해 주세요.' : ''
  return visit ? { ...visit, error: '' } : { preferences, error: error || storageError, update: (patch: Partial<ReaderPreferences>) => write(patch), reset: () => write() }
}

export function ReaderPreferencesPanel({ namespace, onClose }: { namespace: string; onClose: () => void }) {
  const visit = useContext(VisitPreferencesContext)
  const { preferences, update, reset, error } = useReaderPreferences(namespace)
  const [showSync, setShowSync] = useState(false)
  const rows = ['fontSize', 'fontFamily', 'lineHeight', 'pageMargin', 'pageKeys', 'touchDirection', 'listMode'] as const
  const labels = { fontSize: '글자 크기', fontFamily: '글꼴', lineHeight: '행간', pageMargin: '페이지 여백', pageKeys: '페이지 키', touchDirection: '터치 방향', listMode: '목록 표시' }
  const options = {
    fontFamily: [['serif', '명조'], ['sans', '고딕'], ['mono', '고정폭']],
    pageKeys: [['normal', '기본 방향'], ['reversed', '반대 방향'], ['disabled', '키 사용 안 함']],
    touchDirection: [['left-previous', '왼쪽 이전 · 오른쪽 다음'], ['left-next', '왼쪽 다음 · 오른쪽 이전'], ['buttons-only', '버튼으로만 넘기기']],
    listMode: [['paged', '페이지 방식'], ['scroll', '터치 스크롤']],
  }
  if (showSync) return <ReaderPreferenceSyncPanel namespace={namespace} onClose={() => setShowSync(false)}/>
  return <section className="reading-workspace" aria-label={t('독서 설정')}><header className="reading-tools-header"><button className="button-quiet" onClick={onClose}>{t('돌아가기')}</button><strong>{t('독서 설정')}</strong><button className="button-outline" onClick={reset}>{t('기본값으로 초기화')}</button></header>
    {namespace && <button className="button-outline reader-preference-sync-entry" onClick={() => setShowSync(true)}>{t('계정 설정 동기화')}</button>}
    {error && <div role="alert" className="workflow-message">{t(error)}</div>}
    <AdaptiveCollection mode="paged" items={rows} itemKey={item => item} rowHeight={112} renderItem={field => <div className="reading-field"><label htmlFor={`reader-setting-${field}`}>{t(labels[field])}</label>
      {field === 'fontSize' || field === 'lineHeight' || field === 'pageMargin' ? <div className="reader-setting-range"><input id={`reader-setting-${field}`} type="range" min={field === 'fontSize' ? 14 : field === 'lineHeight' ? 1.1 : 0} max={field === 'fontSize' ? 36 : field === 'lineHeight' ? 2.4 : 48} step={field === 'lineHeight' ? 0.01 : 1} value={preferences[field]} onChange={event => update({ [field]: Number(event.target.value) })}/><output>{preferences[field]}</output></div>
      : <select id={`reader-setting-${field}`} value={preferences[field]} onChange={event => update({ [field]: event.target.value })}>{options[field].map(([value, label]) => <option value={value} key={value}>{t(label)}</option>)}</select>}
    </div>}/><p className="reading-tools-caption">{t(visit ? '설정과 읽기 위치는 이 연결에서만 유지됩니다. 휴대폰의 책과 기록은 변경하지 않습니다.' : '계정 동기화를 시작하면 글자 크기·행간·여백·터치 방향·목록 표시를 공유합니다. 글꼴과 페이지 키는 이 기기의 설정입니다.')}</p>
  </section>
}
