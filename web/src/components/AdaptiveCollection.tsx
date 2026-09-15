import { translate as t } from "../lib/locale";
import {
  useCallback,
  useLayoutEffect,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { Icon } from "./Icon";
import { usePageKeys } from "./usePageKeys";
import { useReaderPreferences } from './ReaderPreferences';
type Props<T> = {
  items: readonly T[];
  itemKey: (item: T) => string;
  renderItem: (item: T, index: number) => ReactNode;
  rowHeight: number;
  total?: number;
  offset?: number;
  initialPage?: "first" | "last";
  onPreviousBatch?: () => void;
  onNextBatch?: () => void;
  keyboardEnabled?: boolean;
  mode?: 'paged' | 'scroll';
};
/** Paging is the default; scrolling requires the user's explicit saved preference. */
export function AdaptiveCollection<T>({
  items,
  itemKey,
  renderItem,
  rowHeight,
  total = items.length,
  offset = 0,
  initialPage = "first",
  onPreviousBatch,
  onNextBatch,
  keyboardEnabled = true,
  mode,
}: Props<T>) {
  const { preferences } = useReaderPreferences();
  const layout = mode ?? preferences.listMode;
  const viewport = useRef<HTMLDivElement>(null);
  const [size, setSize] = useState(1);
  const [scrollStart, setScrollStart] = useState(0);
  const [scrollAtTop, setScrollAtTop] = useState(true), [scrollAtEnd, setScrollAtEnd] = useState(false);
  const rememberScroll = (element: HTMLDivElement) => {
    const at = Math.floor(element.scrollTop / rowHeight);
    anchor.current = at; setScrollStart(at); setScrollAtTop(element.scrollTop <= 1);
    setScrollAtEnd(element.scrollTop + element.clientHeight >= element.scrollHeight - 1);
  };
  const [page, setPage] = useState(
    initialPage === "last" ? Math.max(0, items.length - 1) : 0,
  );
  const anchor = useRef(
    initialPage === "last" ? Math.max(0, items.length - 1) : 0,
  );
  const previousOffset = useRef(offset);
  useLayoutEffect(() => {
    const element = viewport.current;
    if (!element) return;
    const resize = () => {
      const nextSize = Math.max(
        1,
        Math.min(8, Math.floor(element.clientHeight / rowHeight)),
      );
      setSize(nextSize);
      setPage(Math.floor(anchor.current / nextSize));
    };
    const observer = new ResizeObserver(resize);
    observer.observe(element);
    resize();
    return () => observer.disconnect();
  }, [rowHeight]);
  const last = Math.max(0, Math.ceil(items.length / size) - 1);
  const current = Math.min(page, last);
  useLayoutEffect(() => {
    const element = viewport.current;
    if (!element) return;
    if (previousOffset.current !== offset) {
      anchor.current = initialPage === 'last' ? Math.max(0, items.length - 1) : 0;
      previousOffset.current = offset;
    }
    anchor.current = Math.min(anchor.current, Math.max(0, items.length - 1));
    if (layout === 'scroll') { element.scrollTop = anchor.current * rowHeight; rememberScroll(element); }
    else setPage(Math.floor(anchor.current / size));
  }, [layout, rowHeight, size, items.length, offset, initialPage]);
  const previous = useCallback(() => {
    if (layout === 'scroll' && viewport.current) {
      if (viewport.current.scrollTop > 0) viewport.current.scrollTop = Math.max(0, viewport.current.scrollTop - size * rowHeight);
      else onPreviousBatch?.();
      return;
    }
    if (current > 0) {
      anchor.current = (current - 1) * size;
      setPage(current - 1);
    } else onPreviousBatch?.();
  }, [current, size, onPreviousBatch, layout, rowHeight]);
  const next = useCallback(() => {
    if (layout === 'scroll' && viewport.current) {
      const element = viewport.current;
      if (element.scrollTop + element.clientHeight < element.scrollHeight - 1) element.scrollTop += size * rowHeight;
      else onNextBatch?.();
      return;
    }
    if (current < last) {
      anchor.current = (current + 1) * size;
      setPage(current + 1);
    } else onNextBatch?.();
  }, [current, last, size, onNextBatch, layout, rowHeight]);
  usePageKeys(previous, next, keyboardEnabled, preferences.pageKeys);
  const start = layout === 'scroll' ? scrollStart : current * size;
  return (
    <div className="adaptive-collection">
      <div
        ref={viewport}
        className="collection-viewport"
        role="list"
        aria-label={t("목록")}
        style={layout === 'scroll' ? { overflowY: 'auto' } : undefined}
        onScroll={layout === 'scroll' ? event => rememberScroll(event.currentTarget) : undefined}
      >
        {(layout === 'scroll' ? items : items.slice(start, start + size)).map((item, index) => (
          <div
            role="listitem"
            key={itemKey(item)}
            className="collection-row"
            style={{ height: rowHeight, minHeight: rowHeight }}
          >
            {renderItem(item, offset + (layout === 'scroll' ? 0 : start) + index)}
          </div>
        ))}
      </div>
      <nav className="page-navigation" aria-label={t("목록 페이지")}>
        <button
          className="button-quiet"
          onClick={previous}
          disabled={(layout === 'scroll' ? scrollAtTop : current === 0) && !onPreviousBatch}
        >
          <Icon name="back" />
          <span>{t("이전")}</span>
        </button>
        <span className="page-count" aria-live="polite">
          {items.length ? offset + start + 1 : 0}–{offset + Math.min(start + size, items.length)}{" "}
          <span className="muted">/ {total}</span>
        </span>
        <button
          className="button-quiet"
          onClick={next}
          disabled={(layout === 'scroll' ? scrollAtEnd : current === last) && !onNextBatch}
        >
          <span>{t("다음")}</span>
          <Icon name="arrow" />
        </button>
      </nav>
    </div>
  );
}
