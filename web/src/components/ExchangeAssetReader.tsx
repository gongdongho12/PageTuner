import { useEffect, useState } from 'react'
import type { SavedExchange } from '../lib/exchangeLibrary'
import { exchangePdfDocument } from '../lib/exchangeLibrary'
import type { LocalDocument } from '../lib/localDocuments'
import { translate as t } from '../lib/locale'
import { LocalPdfReader } from './LocalPdfReader'

/** Asset pages have no portable text-position mapping. Keep their native metadata intact. */
export function ExchangeAssetReader({ saved, username, onClose }: { saved: SavedExchange; username: string; onClose: () => void }) {
  const [pdf, setPdf] = useState<LocalDocument>(), [error, setError] = useState(''), [page, setPage] = useState(0), [imageUrls, setImageUrls] = useState<string[]>([])
  useEffect(() => {
    let active = true
    const urls = saved.document.assets.filter(ref => ref.role === 'image').map(ref => { const asset = saved.assets.find(a => a.path === ref.path)!; return URL.createObjectURL(new Blob([Uint8Array.from(asset.bytes).buffer], { type: asset.mimeType })) })
    setImageUrls(urls)
    const document = exchangePdfDocument(saved)
    if (document) void (async () => {
      const { openLocalPdf } = await import('../lib/pdfDocument'), loaded = await openLocalPdf(new Uint8Array(await document.assets!.pdf!.arrayBuffer()))
      const count = loaded.numPages; await loaded.destroy()
      if (active) setPdf({ ...document, paragraphs: Array.from({ length: count }, (_, i) => ({ paragraphId: `pdf-view:${i}`, text: '' })), outline: [], local: { ...document.local, pdfTextPages: Array(count).fill(false), pdfTextErrorPages: Array(count).fill(false) } })
    })().catch(e => { if (active) setError(e instanceof Error ? e.message : '') })
    return () => { active = false; urls.forEach(url => URL.revokeObjectURL(url)) }
  }, [saved])
  if (pdf) return <LocalPdfReader document={pdf} namespace={username} onClose={onClose} onAnchorChange={() => {}} showReadingTools={false} actionError={t('원본 PDF의 독서 정보는 ZIP에 보존됩니다. 이 화면에서는 원본 페이지를 읽습니다.')}/>
  return <section className="reading-workspace"><header className="reading-tools-header"><button className="button-quiet" onClick={onClose}>{t('돌아가기')}</button><strong>{saved.document.bookTitle}</strong></header>
    {error ? <p role="alert">{t(error)}</p> : imageUrls.length ? <><div style={{ flex: 1, minHeight: 0, display: 'grid', placeItems: 'center', overflow: 'hidden' }}><img src={imageUrls[page]} alt={saved.document.bookTitle} style={{ maxWidth: '100%', maxHeight: '100%', objectFit: 'contain' }}/></div><nav className="reading-filter-actions"><button disabled={page === 0} onClick={() => setPage(page - 1)}>{t('이전')}</button><span>{page + 1} / {imageUrls.length}</span><button disabled={page === imageUrls.length - 1} onClick={() => setPage(page + 1)}>{t('다음')}</button></nav></> : <p role="status">{t('원본 페이지를 열고 있습니다…')}</p>}
  </section>
}
