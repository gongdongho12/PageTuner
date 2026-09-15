import { useEffect, useState } from 'react'
import { translate as t } from '../lib/locale'
import { catalogCacheKey, type CatalogCache, type CachedCatalog } from '../lib/catalogCache'
import { AdaptiveCollection } from './AdaptiveCollection'

export function CachedCatalogBrowser({ cache, onClose, onConnect }: { cache: CatalogCache; onClose: () => void; onConnect: () => void }) {
  const [items, setItems] = useState<CachedCatalog[]>([]), [selected, setSelected] = useState<CachedCatalog>()
  const [error, setError] = useState(''), [loading, setLoading] = useState(true)
  useEffect(() => {
    let active = true
    setSelected(undefined); setItems([]); setLoading(true); setError('')
    void cache.list().then(items => { if (active) setItems(items) }).catch(() => { if (active) setError(t('저장된 목록을 확인할 수 없습니다.')) }).finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [cache])
  const adjacent = (direction: -1 | 1) => selected && selected.request.page + direction > 0 ? items.find(item => catalogCacheKey(item.request) === catalogCacheKey({ ...selected.request, page: selected.request.page + direction })) : undefined
  const previous = selected?.catalog.hasPreviousPage ? adjacent(-1) : undefined
  const next = selected?.catalog.hasNextPage ? adjacent(1) : undefined
  return <section className="novel-workspace" aria-label={t('저장된 목록')}>
    <header className="reading-tools-header"><button className="button-quiet" onClick={selected ? () => setSelected(undefined) : onClose}>{t('돌아가기')}</button><strong>{selected?.request.source.displayName ?? t('저장된 목록')}</strong><button className="button-outline" onClick={onConnect}>{t('서버에 연결')}</button></header>
    <p className="reading-tools-caption">{t('기기에 남은 소스 목록입니다. 새 검색·목차·본문을 열려면 서버 연결이 필요합니다.')}</p>
    {error && <div role="alert" className="workflow-message">{error}</div>}
    {selected ? <><div role="status" className="workflow-message">{t('저장 목록 · {0}페이지 · {1}', [selected.catalog.currentPage, new Date(selected.savedAt).toLocaleString()])} {selected.stale && t('오래된 목록')}{selected.request.query && ` · ${selected.request.query}`}</div>
      <AdaptiveCollection key={catalogCacheKey(selected.request)} items={selected.catalog.items} itemKey={book => book.bookId} rowHeight={110}
        onPreviousBatch={previous ? () => setSelected(previous) : undefined} onNextBatch={next ? () => setSelected(next) : undefined}
        renderItem={book => <article className="workflow-row"><span className="workflow-row-copy"><span>{book.authors.join(' · ') || book.sourceLanguage}</span><strong>{book.title}</strong><span>{book.description ?? t('목차 보기')}</span></span></article>}/></>
    : loading ? <div role="status" className="empty-state">{t('저장된 목록을 확인하고 있습니다…')}</div>
    : items.length ? <AdaptiveCollection items={items} itemKey={item => catalogCacheKey(item.request)} rowHeight={110} renderItem={item => <button className="workflow-row" onClick={() => setSelected(item)}><span className="workflow-row-copy"><strong>{item.request.source.displayName} · {t('{0}페이지', [item.catalog.currentPage])}</strong><span>{item.request.query || t('전체 목록')} {Object.values(item.request.filters).filter(Boolean).join(' · ')}</span><span>{new Date(item.savedAt).toLocaleString()} · {item.stale ? t('오래된 목록') : t('저장된 목록')}</span></span></button>}/>
    : <div className="empty-state"><h2>{t('기기에 저장된 소스 목록이 없습니다.')}</h2><p>{t('연결된 상태에서 소스 목록을 열면 최근 목록을 임시 보관합니다.')}</p></div>}
  </section>
}
