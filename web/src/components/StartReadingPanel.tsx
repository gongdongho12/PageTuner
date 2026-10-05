import { AdaptiveCollection } from './AdaptiveCollection'
import { translate as t } from '../lib/locale'

/** The first useful action must also work when no account or server is available. */
export function StartReadingPanel({ onFiles, onExchange, onExport, onConnect, onPreview, onSaved }: {
  onFiles?: () => void; onExchange?: () => void; onExport?: () => void; onConnect: () => void; onPreview: () => void; onSaved?: () => void
}) {
  const actions = [
    { id: 'files', title: '파일 읽기', detail: '계정 없이 TXT·Markdown·EPUB·PDF를 이 브라우저에 보관하고 읽습니다.', run: onFiles },
    { id: 'exchange', title: 'ZIP 가져오기', detail: '앱이나 다른 PC에서 내보낸 책을 기기 보관함으로 가져옵니다.', run: onExchange },
    { id: 'export', title: '파일 내보내기', detail: '현재 기기 보관함의 본문을 TXT·Markdown으로, PDF를 원본 파일로 저장합니다.', run: onExport },
    { id: 'server', title: '서버에 연결', detail: '계정을 연결하면 웹소설을 불러오고 번역할 수 있습니다.', run: onConnect },
    { id: 'preview', title: '미리보기 읽기', detail: '파일 없이 예제 책으로 페이지 넘김과 읽기 설정을 살펴봅니다.', run: onPreview },
    { id: 'saved', title: '저장된 목록', detail: '이전에 보관한 웹소설 목록을 엽니다. 새 회차는 서버 연결이 필요합니다.', run: onSaved },
  ].filter((item): item is typeof item & { run: () => void } => !!item.run)
  return <section className="reading-workspace start-reading" aria-label={t('바로 읽기')}>
    <header className="reading-tools-header"><h2>{t('바로 읽기')}</h2><span>{t('파일과 ZIP은 서버 없이 사용할 수 있습니다.')}</span></header>
    <AdaptiveCollection items={actions} itemKey={item => item.id} rowHeight={144} renderItem={item => <div className="start-reading-action">
      <p>{t(item.detail)}</p><button className={item.id === 'files' ? 'button-primary' : 'button-outline'} onClick={item.run}>{t(item.title)}</button>
    </div>}/>
  </section>
}
