import { translate as t } from '../lib/locale'
import { ReadingProgressError, type ReadingProgressAnchor } from '../lib/readingProgressApi'
import type { ReadingDocument } from '../lib/readingDocument'
import type { useReadingProgress } from './ReadingProgressProvider'
import { AdaptiveCollection } from './AdaptiveCollection'

export function readingProgressStatus(progress: ReturnType<typeof useReadingProgress>): string {
  if (!progress.online) return '읽기 위치 · 연결 대기'
  switch (progress.state?.status) {
    case 'synced': return '읽기 위치 · 동기화됨'
    case 'conflict': return '읽기 위치 · 위치 선택 필요'
    case 'pending': return '읽기 위치 · 저장 대기'
    case 'error': return '읽기 위치 · 동기화 확인'
    default: return '읽기 위치 · 확인 중'
  }
}
export function ReadingProgressPanel({ document, progress, onClose }: {
  document: ReadingDocument; progress: ReturnType<typeof useReadingProgress>; onClose: () => void
}) {
  const state = progress.state
  const conflict = state?.status === 'conflict'
  const describe = (anchor: ReadingProgressAnchor | null) => {
    if (!anchor) return t('저장된 위치 없음')
    const paragraph = document.paragraphs.findIndex(p => p.paragraphId === anchor.paragraphId)
    return paragraph >= 0 ? t('{0}번째 문단 · {1}번째 문자', [paragraph + 1, anchor.characterOffset + 1]) : t('이 문서에서 확인할 수 없는 위치')
  }
  return <section className="reading-workspace" aria-label={t('읽기 위치 동기화')}>
    <header className="reading-tools-header"><button className="button-quiet" onClick={onClose}>{t('본문으로 돌아가기')}</button><strong>{t('읽기 위치 동기화')}</strong></header>
    <p className="workflow-help">{t('서버 원문과 번역문의 읽던 위치를 같은 계정의 앱과 웹에서 이어 읽습니다.')}</p>
    <p role={conflict || state?.errorCode ? 'alert' : 'status'} className="workflow-message">{t(state?.errorCode ? new ReadingProgressError(state.errorCode).message : readingProgressStatus(progress))}</p>
    <AdaptiveCollection items={['local', 'remote'] as const} itemKey={value => value} rowHeight={142} renderItem={where => {
      const anchor = where === 'local' ? state?.localAnchor ?? null : state?.remote?.anchor ?? null
      const paragraph = document.paragraphs.find(item => item.paragraphId === anchor?.paragraphId)
      return <div className="reading-progress-position"><strong>{t(where === 'local' ? '이 기기의 위치' : '서버의 위치')}</strong>
        <span>{describe(anchor)}</span><p>{anchor && paragraph ? paragraph.text.slice(anchor.characterOffset, anchor.characterOffset + 100) : t('저장된 위치 없음')}</p>
        {conflict && <button className="button-outline" disabled={!progress.online || !anchor} onClick={where === 'local' ? progress.chooseLocal : progress.chooseServer}>{t(where === 'local' ? '이 기기 위치 사용' : '서버 위치 사용')}</button>}
      </div>
    }}/>
    <button className="button-outline" disabled={!progress.online || state?.status === 'loading'} onClick={progress.refresh}>{t('동기화 다시 확인')}</button>
    <p className="reading-tools-caption">{t('연결이 끊겨도 변경한 위치는 기기에 남습니다. 서로 다른 위치가 충돌하면 직접 선택할 때까지 보존합니다.')}</p>
  </section>
}
