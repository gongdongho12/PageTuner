/** A row is indivisible: do not mount one unless its entire fixed height fits. */
export function collectionPageSize(height: number, rowHeight: number): number {
  return Number.isFinite(height) && Number.isFinite(rowHeight) && height > 0 && rowHeight > 0
    ? Math.max(0, Math.floor(height / rowHeight)) : 0;
}

/** Navigation and the fallback message share the same bounded collection slot. */
export function collectionViewportPlan(height: number, navigationHeight: number, rowHeight: number,
  noticeHeight: number, symbolHeight: number, hasItems: boolean, paged: boolean) {
  const available = Number.isFinite(height) ? Math.max(0, Math.floor(height)) : 0;
  const navigation = Number.isFinite(navigationHeight) ? Math.max(0, Math.ceil(navigationHeight)) : 0;
  const size = collectionPageSize(Math.max(0, available - navigation), rowHeight);
  const compact = available < navigation || (paged && hasItems && size === 0);
  const notice = !compact ? 'none' : noticeHeight > 0 && noticeHeight <= available ? 'full'
    : symbolHeight > 0 && symbolHeight <= available ? 'symbol' : 'none';
  return { size: compact ? 0 : size, compact, notice } as const;
}

/** Keep the visible item when asynchronous refreshes insert/remove preceding rows. */
export function collectionAnchorIndex(keys: readonly string[], key: string | undefined, fallback: number): number {
  const found = key === undefined ? -1 : keys.indexOf(key);
  return found >= 0 ? found : Math.max(0, Math.min(fallback, keys.length - 1));
}

export function reconcileCollectionAnchor(keys: readonly string[], key: string | undefined, index: number, awaitingLast: boolean) {
  // Loading may temporarily empty a remote batch. Do not forget its target item,
  // or the requested final page of a batch that has not arrived yet.
  if (!keys.length) return { key, index, awaitingLast };
  const next = awaitingLast ? keys.length - 1 : collectionAnchorIndex(keys, key, index);
  return { key: keys[next], index: next, awaitingLast: false };
}
