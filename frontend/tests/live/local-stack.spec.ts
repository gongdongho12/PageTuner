import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';

const credentials = Object.fromEntries(readFileSync('../.local/stack.env', 'utf8').trim().split('\n').map(line => {
  const index = line.indexOf('='); return [line.slice(0, index), line.slice(index + 1)];
}));
const title = '__local_check__ PageTurner';
const text = 'A local deployment check. '.repeat(100) + '\n\nThe final paragraph stays readable.';

test('real local UI, Next proxy, Spring session, PostgreSQL and backup round trip', async ({ page }) => {
  const pageErrors: string[] = []; page.on('pageerror', e => pageErrors.push(e.message));
  await page.goto('/');
  await page.locator('input[type=file]').first().setInputFiles({ name: `${title}.txt`, mimeType: 'text/plain', buffer: Buffer.from(text) });
  await page.getByRole('button', { name: 'Read ↗', exact: true }).click();
  await page.getByRole('navigation').getByRole('button', { name: 'Settings', exact: true }).click();
  await page.getByRole('button', { name: 'Server connection', exact: true }).click();
  await page.locator('input[autocomplete=username]').fill(credentials.PAGETUNER_LOCAL_USER);
  await page.locator('input[type=password]').fill(credentials.PAGETUNER_LOCAL_PASSWORD);
  await page.getByRole('navigation').getByRole('button', { name: /Library/ }).click();
  await page.getByRole('button', { name: 'Server', exact: true }).click();
  await page.getByRole('button', { name: 'Connect', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Disconnect', exact: true })).toBeVisible();
  const session = await page.request.get('/api/v1/session'); expect(session.status()).toBe(200);
  expect((await session.json()).username).toBe(credentials.PAGETUNER_LOCAL_USER);
  const list = await (await page.request.get(`/api/v1/library/books?query=${encodeURIComponent(title)}`)).json();
  let book = list.items.find((item: { title: string }) => item.title === title);
  if (!book) {
    await page.getByRole('button', { name: '＋ Save', exact: true }).click();
    await page.getByPlaceholder('ko / en / ja').fill('en');
    await page.getByRole('button', { name: 'Save', exact: true }).click();
    await expect(page.getByRole('button', { name: 'Continue reading', exact: true })).toBeVisible();
    const stored = await (await page.request.get(`/api/v1/library/books?query=${encodeURIComponent(title)}`)).json();
    book = stored.items.find((item: { title: string }) => item.title === title);
  } else {
    await page.getByRole('textbox', { name: 'Search books, folders, tags' }).fill(title);
    await page.getByRole('button', { name: 'Find in book', exact: true }).click();
    await page.getByRole('button', { name: 'Read', exact: true }).click();
  }
  expect(book).toBeTruthy();
  await page.getByRole('button', { name: 'Read', exact: true }).click();
  await expect(page.locator('.reading-surface .reader-copy').first()).toContainText('A local deployment check.');
  const progressPath = `/api/v1/library/books/${book.id}/progress`;
  const firstProgress = await (await page.request.get(progressPath)).json();
  await page.evaluate(() => (document.activeElement as HTMLElement)?.blur());
  await page.keyboard.press('ArrowRight');
  await expect.poll(async () => (await (await page.request.get(progressPath)).json()).version).toBeGreaterThan(firstProgress.version);
  await page.getByRole('button', { name: 'Bookmarks', exact: true }).click();
  const existingMarks = await (await page.request.get(`/api/v1/library/books/${book.id}/bookmarks`)).json();
  if (!existingMarks.items.some((m: { note: string }) => m.note === '__local_check__ bookmark')) {
    await page.getByRole('textbox', { name: 'Bookmark note' }).fill('__local_check__ bookmark');
    await page.getByRole('button', { name: 'Bookmark', exact: true }).click();
  }
  await expect(page.getByText('__local_check__ bookmark', { exact: true })).toBeVisible();
  // Real API requests use the same browser cookie jar and Next reverse proxy.
  const summaries = await (await page.request.get(`/api/v1/library/books/${book.id}/chapters`)).json();
  const chapter = await (await page.request.get(`/api/v1/library/books/${book.id}/chapters/${summaries.items[0].id}`)).json();
  const csrf = await (await page.request.get('/api/v1/csrf')).json();
  const headers = { [csrf.headerName]: csrf.token };
  const saved = await page.request.post('/api/v1/translations', { headers, data: {
    contentProviderId: 'library', bookId: book.id, chapterId: chapter.id, sourceRevision: chapter.sourceRevision,
    sourceLanguage: 'en', targetLanguage: 'ko', translationProviderId: 'local-check',
    paragraphs: [{ paragraphId: chapter.paragraphs[0].paragraphId, text: '로컬 배포 검증용 번역입니다.' }],
  } });
  expect([200, 201]).toContain(saved.status()); const record = await saved.json();
  const backup = await page.request.get(`/api/v1/translations/${record.recordId}/backup`);
  expect(backup.status()).toBe(200); expect(backup.headers()['content-disposition']).toContain('attachment');
  const restored = await page.request.post('/api/v1/translations/restore', { headers, data: await backup.json() });
  expect(restored.status()).toBe(200); expect((await restored.json()).recordId).toBe(record.recordId);
  await page.reload();
  await page.getByRole('button', { name: 'Server', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Disconnect', exact: true })).toBeVisible();
  await page.getByRole('textbox', { name: 'Search books, folders, tags' }).fill(title);
  await page.getByRole('button', { name: 'Find in book', exact: true }).click();
  await page.getByRole('button', { name: 'Read', exact: true }).click();
  await page.getByRole('button', { name: 'Continue reading', exact: true }).click();
  await expect(page.locator('.reading-surface')).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({ path: '../.local/live-reader-mobile.png' });
  expect(pageErrors).toEqual([]);
});

test('signs in directly from the server library and signs out without visiting settings', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('button', { name: 'Server', exact: true }).click();
  await page.getByRole('textbox', { name: 'Server username', exact: true }).fill(credentials.PAGETUNER_LOCAL_USER);
  await page.getByLabel('Server password (this session only)', { exact: true }).fill(credentials.PAGETUNER_LOCAL_PASSWORD);
  await page.getByRole('button', { name: 'Connect', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Disconnect', exact: true })).toBeVisible();
  expect((await page.request.get('/api/v1/session')).status()).toBe(200);
  await page.getByRole('button', { name: 'Disconnect', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Connect', exact: true })).toBeVisible();
  expect((await page.request.get('/api/v1/session')).status()).toBe(401);
});
