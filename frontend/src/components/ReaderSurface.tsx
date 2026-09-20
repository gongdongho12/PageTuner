'use client';
import { forwardRef, useEffect, useImperativeHandle, useLayoutEffect, useRef, useState } from 'react';
import type { Book, Settings } from '@/lib/model';
import type { Translate } from '@/lib/i18n';
import { fitText, partAt, type TextPart } from '@/lib/app-ui-contract';
import { Pager } from './AdaptiveCollection';

export type PagedTextHandle = { turn: (direction: -1 | 1) => boolean; at: () => number };
export const PagedText = forwardRef<PagedTextHandle, {
  text: string; settings: Settings; t: Translate; focus?: boolean; initialOffset?: number;
  onPosition?: (offset: number) => void; fromEnd?: boolean;
}>(function PagedText({ text, settings, t, focus = false, initialOffset = 0, onPosition, fromEnd = false }, ref) {
  const viewport = useRef<HTMLDivElement>(null); const measure = useRef<HTMLDivElement>(null);
  const [parts, setParts] = useState<TextPart[]>([{ text, offset: 0 }]); const [part, setPart] = useState(0);
  const anchor = useRef(fromEnd ? text.length : initialOffset); const index = useRef(0);
  const currentParts = useRef(parts); const notify = useRef(onPosition); notify.current = onPosition;
  const change = (next: number) => {
    const bounded = Math.max(0, Math.min(currentParts.current.length - 1, next));
    index.current = bounded; anchor.current = currentParts.current[bounded].offset; setPart(bounded);
    notify.current?.(anchor.current);
  };
  useImperativeHandle(ref, () => ({
    turn(direction) {
      const next = index.current + direction;
      if (next < 0 || next >= currentParts.current.length) return false;
      change(next); return true;
    }, at: () => anchor.current,
  }));
  useLayoutEffect(() => {
    const node = viewport.current; const probe = measure.current; if (!node || !probe) return;
    const fit = () => {
      if (node.clientHeight < 1 || node.clientWidth < 1) return;
      probe.style.width = `${node.clientWidth}px`;
      const output = fitText(text, candidate => { probe.textContent = candidate; return probe.offsetHeight <= node.clientHeight; });
      const next = partAt(output, anchor.current);
      probe.textContent = ''; currentParts.current = output; index.current = next;
      setParts(output); setPart(next);
    };
    const observer = new ResizeObserver(fit); observer.observe(node); fit();
    return () => observer.disconnect();
  }, [text, settings.fontSize, settings.lineHeight, settings.margin]);
  return <div className="text-reader" style={{ fontSize: settings.fontSize, lineHeight: settings.lineHeight }}>
    <div className="text-viewport" ref={viewport}><div className="reader-copy">{parts[Math.min(part, parts.length - 1)].text}</div>
      <div ref={measure} aria-hidden="true" className="reader-copy measure" /></div>
    {!focus && <div className="subpager">{parts.length > 1 && <Pager label={t('viewPage')} page={part} count={parts.length} onChange={change} t={t} />}</div>}
  </div>;
});
export function PdfPage({ book, t }: { book: Book; t: Translate }) {
  const canvas = useRef<HTMLCanvasElement>(null); const [error, setError] = useState('');
  useEffect(() => {
    let disposed = false; let destroy: (() => void) | undefined; setError('');
    void (async () => {
      const pdfjs = await import('pdfjs-dist'); if (disposed) return;
      pdfjs.GlobalWorkerOptions.workerSrc = '/pdf.worker.min.mjs';
      const task = pdfjs.getDocument({ data: Uint8Array.from(atob(book.pdf!), c => c.charCodeAt(0)) });
      destroy = () => { void task.destroy(); };
      const doc = await task.promise; if (disposed) return;
      const page = await doc.getPage(book.currentPage + 1); if (disposed || !canvas.current) return;
      const view = page.getViewport({ scale: 1.4 });
      const target = canvas.current; target.width = view.width; target.height = view.height;
      await page.render({ canvas: target, viewport: view }).promise;
    })().catch(() => { if (!disposed) setError(t('unavailable')); });
    return () => { disposed = true; destroy?.(); };
  }, [book.id, book.currentPage, book.pdf, t]);
  return <div className="pdf-surface">{error ? <p role="alert">{error}</p> : <canvas ref={canvas} aria-label={`${book.title} ${book.currentPage + 1}`} />}</div>;
}
