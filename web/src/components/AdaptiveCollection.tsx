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
import { reconcileCollectionAnchor, collectionViewportPlan } from './collectionViewport';
type Props<T> = {
  items: readonly T[];
  itemKey: (item: T) => string;
  renderItem: (item: T, index: number) => ReactNode;
  rowHeight: number;
  total?: number;
  offset?: number;
  countLabel?: string;
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
  countLabel,
  initialPage = "first",
  onPreviousBatch,
  onNextBatch,
  keyboardEnabled = true,
  mode,
}: Props<T>) {
  const { preferences } = useReaderPreferences();
  const layout = mode ?? preferences.listMode;
  const root = useRef<HTMLDivElement>(null);
  const navigation = useRef<HTMLElement>(null);
  const noticeProbe = useRef<HTMLParagraphElement>(null), symbolProbe = useRef<HTMLParagraphElement>(null);
  const viewport = useRef<HTMLDivElement>(null);
  const [metrics, setMetrics] = useState({ height: 0, navigation: 0, notice: 0, symbol: 0 });
  const plan = collectionViewportPlan(metrics.height, metrics.navigation, rowHeight, metrics.notice, metrics.symbol, items.length > 0, layout === 'paged');
  const size = plan.size;
  const spaceNotice = t('목록을 표시할 공간이 부족합니다. 화면 높이를 늘려 주세요.');
  const [scrollStart, setScrollStart] = useState(0);
  const [scrollAtTop, setScrollAtTop] = useState(true), [scrollAtEnd, setScrollAtEnd] = useState(false);
  const rememberScroll = (element: HTMLDivElement) => {
    if (!items.length || plan.compact) return;
    const at = Math.floor(element.scrollTop / rowHeight);
    anchor.current = at; anchorKey.current = items[at] === undefined ? undefined : itemKey(items[at]); setScrollStart(at); setScrollAtTop(element.scrollTop <= 1);
    setScrollAtEnd(element.scrollTop + element.clientHeight >= element.scrollHeight - 1);
  };
  const [page, setPage] = useState(
    initialPage === "last" ? Math.max(0, items.length - 1) : 0,
  );
  const anchor = useRef(
    initialPage === "last" ? Math.max(0, items.length - 1) : 0,
  );
  const anchorKey = useRef(items[anchor.current] === undefined ? undefined : itemKey(items[anchor.current]));
  const awaitingLast = useRef(initialPage === 'last' && !items.length);
  const previousOffset = useRef(offset);
  useLayoutEffect(() => {
    const element = root.current, nav = navigation.current, notice = noticeProbe.current, symbol = symbolProbe.current;
    if (!element || !nav || !notice || !symbol) return;
    const resize = () => {
      const next = { height: element.getBoundingClientRect().height, navigation: nav.getBoundingClientRect().height,
        notice: notice.getBoundingClientRect().height, symbol: symbol.getBoundingClientRect().height };
      setMetrics(previous => previous.height === next.height && previous.navigation === next.navigation &&
        previous.notice === next.notice && previous.symbol === next.symbol ? previous : next);
    };
    const observer = new ResizeObserver(resize);
    [element, nav, notice, symbol].forEach(node => observer.observe(node));
    resize();
    return () => observer.disconnect();
  }, []);
  const last = Math.max(0, Math.ceil(items.length / Math.max(1, size)) - 1);
  const current = Math.min(page, last);
  useLayoutEffect(() => {
    const element = viewport.current;
    if (!element) return;
    if (previousOffset.current !== offset) {
      anchor.current = initialPage === 'last' ? Math.max(0, items.length - 1) : 0;
      anchorKey.current = items[anchor.current] === undefined ? undefined : itemKey(items[anchor.current]);
      awaitingLast.current = initialPage === 'last' && !items.length;
      previousOffset.current = offset;
    }
    const next = reconcileCollectionAnchor(items.map(itemKey), anchorKey.current, anchor.current, awaitingLast.current);
    anchor.current = next.index; anchorKey.current = next.key; awaitingLast.current = next.awaitingLast;
    if (!items.length || plan.compact) return;
    if (layout === 'scroll') { element.scrollTop = anchor.current * rowHeight; rememberScroll(element); }
    else setPage(Math.floor(anchor.current / Math.max(1, size)));
  }, [layout, rowHeight, size, plan.compact, items, itemKey, offset, initialPage]);
  const previous = useCallback(() => {
    if (!size) return;
    if (layout === 'scroll' && viewport.current) {
      if (viewport.current.scrollTop > 0) viewport.current.scrollTop = Math.max(0, viewport.current.scrollTop - size * rowHeight);
      else onPreviousBatch?.();
      return;
    }
    if (current > 0) {
      anchor.current = (current - 1) * size;
      anchorKey.current = itemKey(items[anchor.current]);
      setPage(current - 1);
    } else onPreviousBatch?.();
  }, [current, size, onPreviousBatch, layout, rowHeight, items, itemKey]);
  const next = useCallback(() => {
    if (!size) return;
    if (layout === 'scroll' && viewport.current) {
      const element = viewport.current;
      if (element.scrollTop + element.clientHeight < element.scrollHeight - 1) element.scrollTop += size * rowHeight;
      else onNextBatch?.();
      return;
    }
    if (current < last) {
      anchor.current = (current + 1) * size;
      anchorKey.current = itemKey(items[anchor.current]);
      setPage(current + 1);
    } else onNextBatch?.();
  }, [current, last, size, onNextBatch, layout, rowHeight, items, itemKey]);
  usePageKeys(previous, next, keyboardEnabled, preferences.pageKeys);
  const start = layout === 'scroll' ? scrollStart : current * size;
  const atStart = layout === 'scroll' ? scrollAtTop : current === 0;
  const atEnd = layout === 'scroll' ? scrollAtEnd : current === last;
  return (
    <div className="adaptive-collection" ref={root}>
      <p ref={noticeProbe} className="collection-space-notice collection-space-probe" aria-hidden="true">{spaceNotice}</p>
      <p ref={symbolProbe} className="collection-space-notice collection-space-probe" aria-hidden="true">↕</p>
      <div
        ref={viewport}
        className="collection-viewport"
        role="list"
        aria-label={t("목록")}
        style={plan.compact ? { display: 'none' } : layout === 'scroll' ? { overflowY: 'auto' } : undefined}
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
      {plan.compact && <div className="collection-space-state" role="status" aria-label={spaceNotice}>
        {plan.notice !== 'none' && <p className="collection-space-notice" aria-hidden="true">{plan.notice === 'full' ? spaceNotice : '↕'}</p>}
      </div>}
      <nav ref={navigation} className={`page-navigation${plan.compact ? ' collection-navigation-probe' : ''}`} aria-label={t("목록 페이지")} aria-hidden={plan.compact || undefined}>
        <button
          className="button-quiet"
          onClick={previous}
          disabled={!size || (atStart && !onPreviousBatch)}
        >
          <Icon name="back" />
          <span>{t(atStart && onPreviousBatch ? "이전 목록" : "이전")}</span>
        </button>
        <span className="page-count" aria-live="polite">
          {countLabel && <span className="collection-count-label">{countLabel}</span>}
          {items.length && size ? offset + start + 1 : 0}–{size ? offset + Math.min(start + size, items.length) : 0}{" "}
          <span className="muted">/ {total}</span>
        </span>
        <button
          className="button-quiet"
          onClick={next}
          disabled={!size || (atEnd && !onNextBatch)}
        >
          <span>{t(atEnd && onNextBatch ? "다음 목록" : "다음")}</span>
          <Icon name="arrow" />
        </button>
      </nav>
    </div>
  );
}
