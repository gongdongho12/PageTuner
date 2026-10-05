import { useEffect, useMemo, useState } from 'react'
import type { SavedExchange } from '../lib/exchangeLibrary'
import { exchangePdfDocument } from '../lib/exchangeLibrary'
import { translate as t } from '../lib/locale'
import { LocalPdfReader } from './LocalPdfReader'

/** Asset pages have no portable text-position mapping. Keep their native metadata intact. */
export function ExchangeAssetReader({ saved, username, onClose }: { saved: SavedExchange; username: string; onClose: () => void }) {
  const pdf = useMemo(() => exchangePdfDocument(saved), [saved])
  const [page, setPage] = useState(0), [imageUrls, setImageUrls] = useState<string[]>([])
  useEffect(() => {
    const urls = saved.document.assets.filter(ref => ref.role === 'image').map(ref => { const asset = saved.assets.find(a => a.path === ref.path)!; return URL.createObjectURL(new Blob([Uint8Array.from(asset.bytes).buffer], { type: asset.mimeType })) })
    setImageUrls(urls)
    setPage(0)
    return () => { urls.forEach(url => URL.revokeObjectURL(url)) }
  }, [saved])
  if (pdf) return <LocalPdfReader document={pdf} namespace={username} onClose={onClose} onAnchorChange={() => {}} showReadingTools={false} actionError={t('원본 PDF의 독서 정보는 ZIP에 보존됩니다. 이 화면에서는 원본 페이지를 읽습니다.')}/>
  return <section className="reading-workspace"><header className="reading-tools-header"><button className="button-quiet" onClick={onClose}>{t('돌아가기')}</button><strong>{saved.document.bookTitle}</strong></header>
    {imageUrls.length ? <><div style={{ flex: 1, minHeight: 0, display: 'grid', placeItems: 'center', overflow: 'hidden' }}><img src={imageUrls[page]} alt={saved.document.bookTitle} style={{ maxWidth: '100%', maxHeight: '100%', objectFit: 'contain' }}/></div><nav className="reading-filter-actions"><button disabled={page === 0} onClick={() => setPage(page - 1)}>{t('이전')}</button><span>{page + 1} / {imageUrls.length}</span><button disabled={page === imageUrls.length - 1} onClick={() => setPage(page + 1)}>{t('다음')}</button></nav></> : <p role="status">{t('원본 페이지를 열고 있습니다…')}</p>}
  </section>
}
