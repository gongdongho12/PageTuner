import { useState } from 'react'
import { translate as t } from '../lib/locale'
import type { ReadingDocument } from '../lib/readingDocument'
import type { ReadingAnchor } from '../lib/offline'
import { searchReadingDocument, type ReaderSearchHit } from '../lib/readerSearch'
import { AdaptiveCollection } from './AdaptiveCollection'

export function ReaderSearch({ document, onJump }: { document: ReadingDocument; onJump: (anchor: ReadingAnchor) => void }) {
  const [query, setQuery] = useState(''), [hits, setHits] = useState<ReaderSearchHit[]>([]), [searched, setSearched] = useState(false), [truncated, setTruncated] = useState(false), [error, setError] = useState('')
  return <div className="reading-search"><form className="workflow-search" onSubmit={event => { event.preventDefault(); try { const result = searchReadingDocument(document, query); setHits(result.hits); setTruncated(result.truncated); setSearched(true); setError('') } catch (error) { setError(error instanceof Error ? error.message : '본문을 검색하지 못했습니다.') } }}>
    <input value={query} maxLength={200} onChange={event => setQuery(event.target.value)} placeholder={t('본문 검색어')} aria-label={t('본문 검색어')}/><button className="button-primary" disabled={!query.trim()}>{t('검색')}</button></form>
    {error && <div role="alert" className="workflow-message">{t(error)}</div>}
    {truncated && <p role="status" className="reading-tools-caption">{t('처음 500개 결과를 표시합니다. 검색어를 더 구체적으로 입력해 주세요.')}</p>}
    {hits.length ? <AdaptiveCollection key={`${query}:${hits[0].anchor.paragraphId}:${hits.length}`} items={hits} itemKey={hit => `${hit.anchor.paragraphId}:${hit.anchor.characterOffset}`} rowHeight={116} renderItem={hit => <div className="reading-item"><div className="reading-note-copy"><strong>{t('{0}번째 문단', [hit.paragraphIndex + 1])}</strong><p>{hit.excerpt}</p></div><button className="button-outline" onClick={() => onJump(hit.anchor)}>{t('이동')}</button></div>}/>
      : <div className="empty-state"><h2>{t(searched ? '일치하는 본문이 없습니다.' : '책 안에서 단어나 문장을 찾아보세요.')}</h2></div>}
  </div>
}
