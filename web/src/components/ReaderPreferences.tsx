import { createContext, useContext, useEffect, useState, type ReactNode } from 'react'
import { createReaderPreferences, defaultReaderPreferences, readerPreferencesKey, type ReaderPreferences } from '../lib/readerPreferences'
import { translate as t } from '../lib/locale'
import { AdaptiveCollection } from './AdaptiveCollection'

const Namespace = createContext('')
const CHANGE_EVENT = 'pageturner-reader-preferences'
export function ReaderPreferencesProvider({ namespace, children }: { namespace: string; children: ReactNode }) {
  return <Namespace.Provider value={namespace}>{children}</Namespace.Provider>
}
export function useReaderPreferences(namespace?: string) {
  const inherited = useContext(Namespace), active = namespace ?? inherited
  const [value, setValue] = useState<ReaderPreferences>({ ...defaultReaderPreferences }), [error, setError] = useState('')
  useEffect(() => {
    const refresh = () => {
      try { setValue(createReaderPreferences(active, localStorage).load()); setError('') }
      catch (error) { setValue({ ...defaultReaderPreferences }); setError(error instanceof Error ? error.message : '독서 설정을 읽지 못했습니다.') }
    }
    refresh()
    const onStorage = (event: StorageEvent) => { if (!event.key || event.key === readerPreferencesKey(active)) refresh() }
    window.addEventListener(CHANGE_EVENT, refresh); window.addEventListener('storage', onStorage)
    return () => { window.removeEventListener(CHANGE_EVENT, refresh); window.removeEventListener('storage', onStorage) }
  }, [active])
  function write(patch?: Partial<ReaderPreferences>) {
    try {
      const store = createReaderPreferences(active, localStorage)
      const next = patch ? store.update(patch) : store.reset(); setValue(next); setError('')
      window.dispatchEvent(new Event(CHANGE_EVENT))
    } catch (error) { setError(error instanceof Error ? error.message : '독서 설정을 저장하지 못했습니다.') }
  }
  return { preferences: value, error, update: (patch: Partial<ReaderPreferences>) => write(patch), reset: () => write() }
}

export function ReaderPreferencesPanel({ namespace, onClose }: { namespace: string; onClose: () => void }) {
  const { preferences, update, reset, error } = useReaderPreferences(namespace)
  const rows = ['fontSize', 'fontFamily', 'lineHeight', 'pageMargin', 'pageKeys', 'touchDirection', 'listMode'] as const
  const labels = { fontSize: '글자 크기', fontFamily: '글꼴', lineHeight: '행간', pageMargin: '페이지 여백', pageKeys: '페이지 키', touchDirection: '터치 방향', listMode: '목록 표시' }
  const options = {
    fontFamily: [['serif', '명조'], ['sans', '고딕'], ['mono', '고정폭']],
    pageKeys: [['normal', '기본 방향'], ['reversed', '반대 방향'], ['disabled', '키 사용 안 함']],
    touchDirection: [['left-previous', '왼쪽 이전 · 오른쪽 다음'], ['left-next', '왼쪽 다음 · 오른쪽 이전'], ['buttons-only', '버튼으로만 넘기기']],
    listMode: [['paged', '페이지 방식'], ['scroll', '터치 스크롤']],
  }
  return <section className="reading-workspace" aria-label={t('독서 설정')}><header className="reading-tools-header"><button className="button-quiet" onClick={onClose}>{t('돌아가기')}</button><strong>{t('독서 설정')}</strong><button className="button-outline" onClick={reset}>{t('기본값으로 초기화')}</button></header>
    {error && <div role="alert" className="workflow-message">{t(error)}</div>}
    <AdaptiveCollection mode="paged" items={rows} itemKey={item => item} rowHeight={112} renderItem={field => <div className="reading-field"><label htmlFor={`reader-setting-${field}`}>{t(labels[field])}</label>
      {field === 'fontSize' || field === 'lineHeight' || field === 'pageMargin' ? <div className="reader-setting-range"><input id={`reader-setting-${field}`} type="range" min={field === 'fontSize' ? 14 : field === 'lineHeight' ? 1.2 : 0} max={field === 'fontSize' ? 36 : field === 'lineHeight' ? 2.4 : 48} step={field === 'lineHeight' ? 0.05 : field === 'fontSize' ? 2 : 4} value={preferences[field]} onChange={event => update({ [field]: Number(event.target.value) })}/><output>{preferences[field]}</output></div>
      : <select id={`reader-setting-${field}`} value={preferences[field]} onChange={event => update({ [field]: event.target.value })}>{options[field].map(([value, label]) => <option value={value} key={value}>{t(label)}</option>)}</select>}
    </div>}/><p className="reading-tools-caption">{t('이 계정의 설정은 이 기기에 저장됩니다. 본문은 항상 페이지로 읽습니다. 볼륨 키는 브라우저가 전달하는 경우에만 동작합니다.')}</p>
  </section>
}
