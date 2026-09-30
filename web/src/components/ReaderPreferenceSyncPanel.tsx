import { translate as t } from '../lib/locale'
import type { SharedReaderPreferences } from '../lib/readerPreferenceApi'
import { AdaptiveCollection } from './AdaptiveCollection'
import { useReaderPreferenceSync } from './ReaderPreferences'

function summary(value: SharedReaderPreferences) {
  return [t('글자 {0} · 행간 {1}% · 여백 {2}', [value.fontSize, value.lineHeightPercent, value.pageMargin]),
    t(value.touchDirection === 'left-previous' ? '왼쪽 이전 · 오른쪽 다음' : value.touchDirection === 'left-next' ? '왼쪽 다음 · 오른쪽 이전' : '버튼으로만 넘기기'),
    t(value.listMode === 'paged' ? '페이지 방식' : '터치 스크롤')]
}
const errors: Record<string, string> = {
  authentication: '계정 설정을 동기화하려면 다시 로그인해 주세요.',
  forbidden: '계정 설정을 저장할 권한을 확인할 수 없습니다.',
  storage: '이 기기에 계정 설정을 보관하지 못했습니다. 저장 공간을 확인하고 다시 시도해 주세요.',
  'invalid-response': '서버의 계정 설정 응답을 확인할 수 없습니다.',
  'invalid-request': '계정 설정 값을 확인해 주세요.',
  'mutation-reused': '계정 설정 변경 요청을 확인할 수 없습니다.',
  exhausted: '계정 설정의 저장 한도에 도달했습니다.',
  stale: '설정이 바뀌었습니다. 최신 내용을 확인한 뒤 다시 선택해 주세요.',
  'stale-choice': '설정이 바뀌었습니다. 최신 내용을 확인한 뒤 다시 선택해 주세요.',
  'choice-stale': '설정이 바뀌었습니다. 최신 내용을 확인한 뒤 다시 선택해 주세요.',
  limit: '동기화 요청이 많습니다. 잠시 후 다시 시도해 주세요.',
  network: '연결을 확인해 주세요. 변경한 설정은 기기에 남아 있습니다.',
  timeout: '연결을 확인해 주세요. 변경한 설정은 기기에 남아 있습니다.',
  server: '서버에서 계정 설정을 저장하지 못했습니다. 다시 시도해 주세요.',
}
export function ReaderPreferenceSyncPanel({ namespace, onClose }: { namespace: string; onClose: () => void }) {
  const sync = useReaderPreferenceSync(namespace), state = sync?.state
  const choosing = state && (!state.enabled || state.status === 'conflict')
  const status = !sync?.client ? '계정 동기화를 사용하려면 서버에 연결해 주세요.' : !state ? '계정 설정을 확인하고 있습니다.' :
    state.errorCode ? (errors[state.errorCode] ?? '계정 설정을 동기화하지 못했습니다. 다시 확인해 주세요.') :
    !state.enabled ? '처음 사용할 설정을 선택하면 이후 변경은 자동으로 동기화됩니다.' :
    state.status === 'conflict' ? '다른 기기에서 설정을 변경했습니다. 사용할 설정을 선택해 주세요.' :
    state.status === 'pending' ? '계정 설정 · 저장 대기' : state.status === 'loading' ? '계정 설정을 확인하고 있습니다.' : '계정 설정 · 동기화됨'
  return <section className="reading-workspace" aria-label={t('계정 설정 동기화')}>
    <header className="reading-tools-header"><button className="button-quiet" onClick={onClose}>{t('독서 설정으로')}</button><strong>{t('계정 설정 동기화')}</strong></header>
    <p className="workflow-message" role={state?.errorCode || state?.status === 'conflict' ? 'alert' : 'status'}>{t(status)}</p>
    <AdaptiveCollection mode="paged" items={['local', 'server'] as const} itemKey={item => item} rowHeight={168} renderItem={side => {
      const value = side === 'local' ? state?.local : state?.remote?.preferences
      return <div className="reader-preference-choice"><strong>{t(side === 'local' ? '이 기기의 설정' : '서버의 설정')}</strong>
        {value ? <div>{summary(value).map(line => <p key={line}>{line}</p>)}</div> : <p>{t('아직 저장된 서버 설정이 없습니다.')}</p>}
        {choosing && <button className="button-outline" disabled={!sync?.client || !state.remote || !value || state.status === 'loading'} onClick={() => {
          void sync?.controller.choose(side, { local: state.local, remote: state.remote }).catch(() => undefined)
        }}>{t(side === 'local' ? '이 기기 설정 사용' : '서버 설정 사용')}</button>}
      </div>
    }}/>
    <button className="button-outline reader-preference-sync-entry" disabled={!sync?.client || state?.status === 'loading'} onClick={() => { void sync?.controller.refresh().catch(() => undefined) }}>{t('동기화 다시 확인')}</button>
    <p className="reading-tools-caption">{t('다섯 설정을 함께 선택합니다. 원격 설정을 적용해도 본문의 현재 읽기 위치는 유지합니다.')}</p>
  </section>
}
