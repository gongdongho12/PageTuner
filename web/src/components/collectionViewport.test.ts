import { describe, expect, it } from 'vitest';
import { collectionAnchorIndex, collectionPageSize, collectionViewportPlan, reconcileCollectionAnchor } from './collectionViewport';

describe('collection viewport', () => {
  it('fills a tall viewport beyond eight rows without a partial final row', () => {
    expect(collectionPageSize(1450, 132)).toBe(10);
    expect(collectionPageSize(1452, 132)).toBe(11);
    expect(collectionPageSize(263, 132)).toBe(1);
    expect(collectionPageSize(264, 132)).toBe(2);
  });
  it('waits for a usable viewport instead of mounting a clipped row', () => {
    for (const height of [0, 1, 131, -1, Infinity, NaN]) expect(collectionPageSize(height, 132)).toBe(0);
    expect(collectionPageSize(800, 0)).toBe(0);
  });
  it('keeps the same item across asynchronous insertion, reorder and removal', () => {
    expect(collectionAnchorIndex(['new', 'a', 'b', 'c'], 'b', 1)).toBe(2);
    expect(collectionAnchorIndex(['b', 'a', 'c'], 'b', 2)).toBe(0);
    expect(collectionAnchorIndex(['a'], 'b', 2)).toBe(0);
    expect(collectionAnchorIndex([], 'b', 2)).toBe(0);
  });
  it('honors a previous remote batch final page after its items arrive', () => {
    const pending = reconcileCollectionAnchor([], undefined, 0, true);
    expect(pending.awaitingLast).toBe(true);
    expect(reconcileCollectionAnchor(['a', 'b', 'c'], pending.key, pending.index, pending.awaitingLast))
      .toEqual({ key: 'c', index: 2, awaitingLast: false });
  });
  it('retains an item across the temporary empty state of an asynchronous refresh', () => {
    const pending = reconcileCollectionAnchor([], 'b', 1, false);
    expect(reconcileCollectionAnchor(['new', 'a', 'b'], pending.key, pending.index, pending.awaitingLast))
      .toEqual({ key: 'b', index: 2, awaitingLast: false });
  });
  it('uses the entire collection slot for a complete fallback instead of clipping it above navigation', () => {
    expect(collectionViewportPlan(100, 60, 132, 50, 32, true, true))
      .toEqual({ size: 0, compact: true, notice: 'full' });
    expect(collectionViewportPlan(40, 60, 132, 50, 32, true, true))
      .toEqual({ size: 0, compact: true, notice: 'symbol' });
    for (const height of [0, 1, 31]) expect(collectionViewportPlan(height, 60, 132, 50, 32, true, true))
      .toEqual({ size: 0, compact: true, notice: 'none' });
  });
  it('restores normal rows and navigation exactly when both fit', () => {
    expect(collectionViewportPlan(191, 60, 132, 50, 32, true, true))
      .toEqual({ size: 0, compact: true, notice: 'full' });
    expect(collectionViewportPlan(192, 60, 132, 50, 32, true, true))
      .toEqual({ size: 1, compact: false, notice: 'none' });
    expect(collectionViewportPlan(1512, 60, 132, 50, 32, true, true).size).toBe(11);
  });
  it('reserves actual navigation height and avoids fractional pixel overflow', () => {
    expect(collectionViewportPlan(192.8, 60.2, 132, 50, 32, true, true).compact).toBe(true);
    expect(collectionViewportPlan(193, 60.2, 132, 50, 32, true, true).size).toBe(1);
    expect(collectionViewportPlan(170, 80, 96, 50, 32, true, true).compact).toBe(true);
  });
  it('retains explicit scroll mode unless the navigation itself cannot fit', () => {
    expect(collectionViewportPlan(100, 60, 132, 50, 32, true, false).compact).toBe(false);
    expect(collectionViewportPlan(59, 60, 132, 50, 32, true, false).compact).toBe(true);
    expect(collectionViewportPlan(60, 60, 132, 50, 32, false, true).compact).toBe(false);
  });
});
