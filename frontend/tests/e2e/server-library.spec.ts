import { test, expect } from '@playwright/test';

test('server library reads without skipping subpages, saves anchors and resolves conflicts', async ({ page }) => {
  const text = 'BEGIN ' + Array.from({ length: 1200 }, (_, i) => `word${i}`).join(' ') + ' END';
  const book = { id: 'book-1', title: 'Server reading book', author: 'Reader', sourceLanguage: 'en', chapterCount: 1, createdAt: '' };
  const chapter = { id: 'chapter-1', bookId: book.id, ordinal: 0, title: 'Chapter one', sourceRevision: 'r1', sourceLanguage: 'en',
    paragraphs: [{ paragraphId: 'p1', text }, { paragraphId: 'p2', text: 'NEXT PARAGRAPH' }] };
  const envelope = (items: unknown[]) => ({ items, page: 0, size: 20, totalItems: items.length, totalPages: items.length ? 1 : 0 });
  let position = { anchor: null as null | { chapterId: string; paragraphId: string; characterOffset: number }, version: 0, updatedAt: null as null | string };
  let conflict = false; let writes = 0; const errors: string[] = [];
  const marks: { id: string; anchor: unknown; note: string; createdAt: string }[] = [];
  page.on('pageerror', e => errors.push(e.message));
  await page.route('**/api/v1/**', async route => {
    const url = new URL(route.request().url()); const method = route.request().method();
    if (url.pathname.endsWith('/csrf')) return route.fulfill({ json: { headerName: 'X-CSRF-TOKEN', token: 'test-token' } });
    if (url.pathname.endsWith('/session')) return route.fulfill({ json: { username: 'reader' } });
    if (url.pathname.endsWith('/translations')) return route.fulfill({ json: envelope([]) });
    if (url.pathname.endsWith('/books')) return route.fulfill({ json: envelope([book]) });
    if (url.pathname.endsWith('/chapters')) return route.fulfill({ json: envelope([chapter]) });
    if (url.pathname.endsWith('/chapter-1')) return route.fulfill({ json: chapter });
    if (url.pathname.endsWith('/progress')) {
      if (method === 'PUT') {
        writes++; const body = route.request().postDataJSON();
        expect(route.request().headers()['x-csrf-token']).toBe('test-token');
        if (conflict) { conflict = false; position.version++; return route.fulfill({ status: 409, json: {} }); }
        expect(body.version).toBe(position.version);
        position = { anchor: body.anchor, version: position.version + 1, updatedAt: '2026-09-05T12:00:00Z' };
      }
      return route.fulfill({ json: position });
    }
    if (url.pathname.endsWith('/bookmarks')) {
      if (method === 'POST') { const body = route.request().postDataJSON(); const mark = { id: 'bookmark-1', ...body, createdAt: '' }; marks.push(mark); return route.fulfill({ status: 201, json: mark }); }
      return route.fulfill({ json: envelope(marks) });
    }
    return route.fulfill({ status: 404, json: {} });
  });
  await page.goto('/');
  await page.getByRole('button', { name: 'Server', exact: true }).click();
  await page.getByRole('button', { name: 'Read', exact: true }).click();
  await page.getByRole('button', { name: 'Read', exact: true }).click();
  const copy = page.locator('.reading-surface .reader-copy').first();
  await expect(copy).toContainText('BEGIN');
  await expect.poll(() => writes).toBe(1);
  await page.evaluate(() => (document.activeElement as HTMLElement)?.blur());
  await page.keyboard.press('ArrowRight');
  await expect(copy).not.toContainText('BEGIN');
  await expect(page.locator('.reader-panel > .pager span')).toHaveText('1 / 2');
  await expect.poll(() => position.anchor?.characterOffset ?? 0).toBeGreaterThan(0);
  conflict = true;
  await page.keyboard.press('ArrowRight');
  await expect(page.locator('.main-content').getByRole('alert')).toContainText('Another device');
  await page.getByRole('button', { name: 'Save this position', exact: true }).click();
  await expect(page.locator('.main-content').getByRole('alert')).toHaveCount(0);
  await page.getByRole('button', { name: 'Bookmarks', exact: true }).click();
  await page.getByRole('textbox', { name: 'Bookmark note' }).fill('Keep reading here');
  await page.getByRole('button', { name: 'Bookmark', exact: true }).click();
  await expect(page.getByText('Keep reading here', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Read', exact: true }).click();
  await expect(copy).not.toContainText('BEGIN');
  await page.getByRole('button', { name: 'Focus', exact: true }).click();
  await expect(page.locator('.focus-mode .subpager')).toHaveCount(0);
  await page.evaluate(() => (document.activeElement as HTMLElement)?.blur());
  await page.keyboard.press('Escape');
  await expect(page.locator('.focus-mode')).toHaveCount(0);
  const overflow = await page.locator('.main-content').evaluate(node => {
    const bounds = node.getBoundingClientRect();
    return Array.from(node.querySelectorAll('button,input,select')).filter(el => el.getClientRects().length)
      .some(el => { const r = el.getBoundingClientRect(); return r.bottom > bounds.bottom + 1 || r.right > bounds.right + 1; });
  });
  expect(overflow).toBe(false); expect(errors).toEqual([]);
  await page.screenshot({ path: `test-results/server-reader-${test.info().project.name}.png` });
});
