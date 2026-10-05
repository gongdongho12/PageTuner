import { useEffect, useLayoutEffect, useState } from 'react'
import { documentFileChoices, type DocumentFileChoice } from '../lib/documentFileLibrary'
import { translate as t } from '../lib/locale'
import { AdaptiveCollection } from './AdaptiveCollection'
import { DocumentFileExportPanel } from './DocumentFileExportPanel'

export function DocumentFileExportWorkspace({ username, onClose, onReadingChange }: { username: string; onClose: () => void; onReadingChange: (reading: boolean) => void }) {
  const [choices, setChoices] = useState<DocumentFileChoice[]>([]), [damaged, setDamaged] = useState(0)
  const [unavailable, setUnavailable] = useState<string[]>([])
  const [selected, setSelected] = useState<DocumentFileChoice>(), [busy, setBusy] = useState(true), [error, setError] = useState(''), [revision, setRevision] = useState(0)
  useLayoutEffect(() => { onReadingChange(true); return () => onReadingChange(false) }, [onReadingChange])
  useEffect(() => {
    let current = true
    setBusy(true); setError(''); setChoices([]); setDamaged(0); setUnavailable([]); setSelected(undefined)
    void documentFileChoices(username).then(result => { if (current) { setChoices(result.choices); setDamaged(result.damaged); setUnavailable(result.unavailable) } })
      .catch(error => { if (current) setError(error instanceof Error ? error.message : '파일을 내보내지 못했습니다.') })
      .finally(() => { if (current) setBusy(false) })
    return () => { current = false }
  }, [username, revision])
  if (selected) return <DocumentFileExportPanel key={`${username}:${selected.key}`} document={selected.document} onClose={() => setSelected(undefined)}/>
  return <section className="reading-workspace" aria-label={t('책 파일 내보내기')}>
    <header className="reading-tools-header"><button className="button-quiet" onClick={onClose}>{t('돌아가기')}</button><strong>{t('책 파일 내보내기')}</strong><button className="button-outline" disabled={busy} onClick={() => setRevision(value => value + 1)}>{t('새로고침')}</button></header>
    <p className="reading-tools-caption">{t('기기에 보관한 문서를 선택하세요. 서버의 책은 먼저 기기에 보관해 주세요.')}</p>
    {error && <p role="alert" className="workflow-message">{t(error)}</p>}
    {unavailable.length > 0 && <p role="alert" className="workflow-message">{t('일부 보관함을 읽지 못했습니다: {0}. 다른 문서는 내보낼 수 있습니다.', [unavailable.map(label => t(label)).join(', ')])}</p>}
    {damaged > 0 && <p role="alert" className="workflow-message">{t('손상된 문서 {0}개는 내보내지 않습니다. 기기 보관함에서 확인해 주세요.', [damaged])}</p>}
    {busy ? <p role="status">{t('보관한 문서를 확인하고 있습니다…')}</p> : choices.length ? <AdaptiveCollection items={choices} itemKey={item => item.key} rowHeight={132} renderItem={item => <div className="reading-note-row document-export-choice"><strong>{item.document.bookTitle}</strong><span>{t(item.label)} · {item.document.chapterTitle} · {item.document.language}</span><button className="button-outline" onClick={() => setSelected(item)}>{t('파일 내보내기')}</button></div>}/> : !error && <p>{t('내보낼 기기 보관 문서가 없습니다.')}</p>}
  </section>
}
