'use client';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import type { Translate } from '@/lib/i18n';
export function Pager({ page, count, onChange, t, label }: { page: number; count: number; onChange: (page: number) => void; t: Translate; label?: string }) {
  return <div className="pager"><button disabled={page <= 0} onClick={() => onChange(page - 1)}>← {t('previous')}</button>
    <span>{label && `${label} `}{Math.min(page + 1, Math.max(count, 1))} / {Math.max(count, 1)}</span>
    <button disabled={page >= count - 1} onClick={() => onChange(page + 1)}>{t('next')} →</button></div>;
}
export function AdaptiveCollection<T>({ items, rowHeight, mode, render, t, empty }: {
  items: T[]; rowHeight: number; mode: 'paged' | 'scroll'; render: (item: T) => ReactNode; t: Translate; empty?: string;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const [size, setSize] = useState(1); const [page, setPage] = useState(0);
  useEffect(() => {
    const node = ref.current; if (!node) return;
    const observer = new ResizeObserver(([entry]) => setSize(Math.max(1, Math.min(8, Math.floor((entry.contentRect.height + 12) / (rowHeight + 12))))));
    observer.observe(node); return () => observer.disconnect();
  }, [rowHeight]);
  const count = Math.ceil(items.length / size); const actualPage = Math.min(page, Math.max(0, count - 1));
  return <div className="collection"><div ref={ref} className={`collection-viewport ${mode === 'scroll' ? 'scroll' : ''}`}>
    {items.length ? (mode === 'scroll' ? items : items.slice(actualPage * size, (actualPage + 1) * size)).map((item, index) =>
      <div key={index} style={{ height: rowHeight, flexShrink: 0 }}>{render(item)}</div>) : <div className="empty-small">{empty ?? t('noResults')}</div>}
  </div>{mode === 'paged' && <Pager page={actualPage} count={count} onChange={setPage} t={t} />}</div>;
}
