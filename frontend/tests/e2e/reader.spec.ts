import { test, expect } from '@playwright/test';
import { readFile } from 'node:fs/promises';
const text = ('A quiet morning. The reader opened a book beside the window. A new chapter begins here.\n\n').repeat(40);
async function importSample(page: import('@playwright/test').Page) {
  await page.goto('/');
  await page.locator('input[type=file]').setInputFiles({ name: 'Quiet mornings.txt', mimeType: 'text/plain', buffer: Buffer.from(text) });
  await expect(page.getByRole('heading', { name: 'Quiet mornings' })).toBeVisible();
}
test('imports, reads, bookmarks, persists and restores a verified backup', async ({ page }) => {
  const errors: string[] = []; page.on('pageerror', error => errors.push(error.message));
  await importSample(page);
  await page.getByRole('button', { name: 'Read ↗', exact: true }).click();
  await page.locator('.reader-toolbar select').selectOption('original');
  await expect(page.locator('.reader-copy').first()).toContainText('A quiet morning.');
  await page.getByRole('button', { name: 'Bookmark', exact: true }).click();
  for (let i = 0; i < 20 && (await page.locator('.reader-panel > .pager span').innerText()).startsWith('1 /'); i++) {
    await page.locator('.reader-panel > .pager').getByRole('button', { name: 'Next' }).click();
  }
  await expect(page.locator('.reader-panel > .pager span')).toContainText('2 /');
  await page.getByRole('navigation').getByRole('button', { name: 'Library' }).click();
  await expect(page.locator('.book-progress')).toContainText('2/');
  await page.reload();
  await page.getByRole('navigation').getByRole('button', { name: 'Reader', exact: true }).click();
  await expect(page.locator('.reader-panel > .pager span')).toContainText('2 /');
  await page.getByRole('navigation').getByRole('button', { name: 'Backup' }).click();
  const downloadEvent = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Download library backup' }).click();
  const file = await (await downloadEvent).path(); const backup = await readFile(file!, 'utf8');
  expect(JSON.parse(backup).data.books[0].bookmarks).toEqual([0]);
  expect(JSON.parse(backup).data.books[0].currentPage).toBe(1);
  await page.getByText('Choose library backup', { exact: true }).locator('input').setInputFiles({ name: 'backup.json', mimeType: 'application/json', buffer: Buffer.from(backup) });
  await expect(page.getByText('Backup preview:', { exact: false })).toBeVisible();
  await page.getByRole('button', { name: 'Merge into library' }).click();
  await expect(page.getByRole('status').last()).toContainText('Backup restored');
  await page.getByRole('navigation').getByRole('button', { name: 'Library' }).click();
  await expect(page.locator('.book-row')).toHaveCount(1);
  await page.screenshot({ path: `test-results/library-${test.info().project.name}.png` });
  expect(errors).toEqual([]);
});
test('translates, reuses offline cache and changes interface language', async ({ page }) => {
  await importSample(page);
  let calls = 0;
  await page.route('**/api/translate', async route => { calls++; await route.fulfill({ json: { text: '고요한 아침입니다. 독자가 창가에서 책을 펼쳤습니다. '.repeat(40) } }); });
  await page.getByRole('button', { name: 'Read ↗', exact: true }).click();
  await page.getByRole('button', { name: 'Translate this page', exact: true }).click();
  await expect(page.locator('.reader-copy').first()).toContainText('고요한 아침');
  await expect(page.locator('.queue-detail strong')).toContainText('Completed');
  await page.getByRole('button', { name: 'Translate this page', exact: true }).click();
  await expect(page.locator('.queue-detail strong')).toContainText('Completed');
  expect(calls).toBe(1);
  await page.getByRole('combobox', { name: 'Interface language' }).selectOption('ko');
  await expect(page.getByRole('navigation').getByRole('button', { name: '서재' })).toBeVisible();
  const fits = await page.locator('.text-viewport').evaluate(node => {
    const child = node.querySelector('.reader-copy')!;
    return child.getBoundingClientRect().height <= node.getBoundingClientRect().height + 1;
  });
  expect(fits).toBe(true);
});
test('keeps primary controls within desktop and phone viewports', async ({ page }) => {
  if (test.info().project.name === 'mobile') await page.setViewportSize({ width: 375, height: 667 });
  await importSample(page);
  for (const screen of ['Library', 'Translation', 'Backup', 'Settings']) {
    await page.getByRole('navigation').getByRole('button', { name: new RegExp(screen) }).click();
    const overflow = await page.locator('.main-content').evaluate(node => {
      const bounds = node.getBoundingClientRect();
      return Array.from(node.querySelectorAll('button,input,select')).filter(el => el.getClientRects().length && getComputedStyle(el).opacity !== '0')
        .some(el => { const box = el.getBoundingClientRect(); return box.bottom > bounds.bottom + 1 || box.right > bounds.right + 1; });
    });
    expect(overflow, `${screen} controls must fit`).toBe(false);
  }
  await page.screenshot({ path: `test-results/settings-${test.info().project.name}.png` });
});
test('reopens the production app and a saved book offline', async ({ page, context }) => {
  test.skip(!process.env.E2E_PRODUCTION, 'Service worker is production-only.');
  await importSample(page);
  await page.evaluate(async () => { await navigator.serviceWorker.ready; });
  await expect.poll(() => page.evaluate(async () => (await caches.open('pageturner-shell-v1')).keys().then(keys => keys.filter(k => k.url.includes('/_next/static/')).length))).toBeGreaterThan(3);
  await context.setOffline(true);
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Quiet mornings' })).toBeVisible();
  await page.getByRole('button', { name: 'Read ↗', exact: true }).click();
  await page.locator('.reader-toolbar select').selectOption('original');
  await expect(page.locator('.reader-copy').first()).toContainText('A quiet morning.');
});
test('imports EPUB spine text and renders a PDF page with extracted text', async ({ page }) => {
  const { zipSync, strToU8 } = await import('fflate');
  const epub = zipSync({
    'META-INF/container.xml': strToU8('<?xml version="1.0"?><container><rootfiles><rootfile full-path="OPS/book.opf"/></rootfiles></container>'),
    'OPS/book.opf': strToU8('<package><metadata><title>Spine example</title></metadata><manifest><item id="ch1" href="chapter.xhtml"/></manifest><spine><itemref idref="ch1"/></spine></package>'),
    'OPS/chapter.xhtml': strToU8('<html><head><script>throw new Error("must not execute")</script></head><body><h1>Chapter one</h1><p>Safe EPUB reading content.</p></body></html>'),
  });
  await page.goto('/');
  await page.locator('input[type=file]').setInputFiles({ name: 'book.epub', mimeType: 'application/epub+zip', buffer: Buffer.from(epub) });
  await page.getByRole('button', { name: 'Read ↗', exact: true }).click();
  await page.locator('.reader-toolbar select').selectOption('original');
  await expect(page.locator('.reader-copy').first()).toContainText('Safe EPUB reading content.');
  await expect(page.locator('.reader-copy').first()).not.toContainText('must not execute');
  const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>', '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 400] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>',
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
    '<< /Length 49 >>\nstream\nBT /F1 18 Tf 30 340 Td (Hello PDF reader) Tj ET\nendstream',
  ];
  let pdf = '%PDF-1.4\n'; const offsets = [0];
  objects.forEach((object, i) => { offsets.push(Buffer.byteLength(pdf)); pdf += `${i + 1} 0 obj\n${object}\nendobj\n`; });
  const xref = Buffer.byteLength(pdf); pdf += `xref\n0 6\n0000000000 65535 f \n${offsets.slice(1).map(o => `${String(o).padStart(10, '0')} 00000 n \n`).join('')}trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF`;
  await page.getByRole('navigation').getByRole('button', { name: 'Library' }).click();
  await page.locator('input[type=file]').setInputFiles({ name: 'PDF example.pdf', mimeType: 'application/pdf', buffer: Buffer.from(pdf) });
  await expect(page.getByRole('heading', { name: 'PDF example' })).toBeVisible();
  await page.locator('.book-row').filter({ hasText: 'PDF example' }).getByRole('button', { name: 'Read ↗', exact: true }).click();
  await expect(page.locator('.pdf-surface canvas')).toBeVisible();
  await expect.poll(() => page.locator('.pdf-surface canvas').evaluate((el: HTMLCanvasElement) => el.width)).toBeGreaterThan(300);
  await page.getByRole('button', { name: 'Find in book', exact: true }).click();
  await page.getByRole('textbox', { name: 'Find in book' }).fill('Hello PDF');
  await expect(page.locator('.entry-row')).toContainText('Hello PDF reader');
});
test('loads a remote catalog and imports its relative book URL', async ({ page }) => {
  await page.route('https://catalog.example/**', async route => {
    if (route.request().url().endsWith('catalog.json')) await route.fulfill({ json: {
      version: 'pagetuner.catalog.v0', title: 'My catalog', items: [{ id: 'one', title: 'Remote story', format: 'txt', href: 'books/one.txt' }],
    } }); else await route.fulfill({ contentType: 'text/plain', body: 'A story from the remote catalog.' });
  });
  await page.goto('/');
  await page.getByRole('button', { name: 'Web catalog', exact: true }).click();
  await page.getByRole('textbox', { name: 'Catalog URL' }).fill('https://catalog.example/catalog.json');
  await page.getByRole('button', { name: 'Load catalog', exact: true }).click();
  await page.getByRole('button', { name: 'Import', exact: true }).click();
  await page.getByRole('button', { name: 'Device', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Remote story' })).toBeVisible();
});
test('retries failed queue pages without retranslating successful pages', async ({ page }) => {
  await importSample(page);
  let calls = 0;
  await page.route('**/api/translate', async route => {
    calls++;
    if (calls === 1) await route.fulfill({ status: 429, json: { code: 'rate-limit' } });
    else await route.fulfill({ json: { text: '저장된 번역입니다.' } });
  });
  await page.getByRole('navigation').getByRole('button', { name: 'Translation', exact: true }).click();
  await page.getByRole('combobox', { name: 'Translation pacing' }).selectOption('fast');
  await page.getByRole('button', { name: 'Translate whole book' }).click();
  await expect(page.locator('.queue-detail strong')).toContainText('Completed', { timeout: 15_000 });
  const firstRun = calls;
  await page.getByRole('button', { name: 'Retry failed pages' }).click();
  await expect(page.locator('.queue-detail strong')).toContainText('Completed');
  expect(calls).toBe(firstRun + 1);
  await expect(page.getByRole('button', { name: 'Retry failed pages' })).toHaveCount(0);
});
test('pauses and resumes an in-flight queue and cancels it without further requests', async ({ page }) => {
  await importSample(page);
  let calls = 0;
  await page.route('**/api/translate', async route => {
    calls++;
    await new Promise(resolve => setTimeout(resolve, 250));
    await route.fulfill({ json: { text: '번역 결과입니다.' } }).catch(() => {});
  });
  await page.getByRole('navigation').getByRole('button', { name: 'Translation', exact: true }).click();
  await page.getByRole('combobox', { name: 'Translation pacing' }).selectOption('fast');
  await page.getByRole('button', { name: 'Translate whole book' }).click();
  await expect.poll(() => calls).toBeGreaterThan(0);
  await page.getByRole('button', { name: 'Pause', exact: true }).click();
  await expect(page.locator('.queue-detail strong')).toContainText('Paused');
  await page.getByRole('button', { name: 'Resume', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Pause', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Cancel', exact: true }).click();
  await expect(page.locator('.queue-bar')).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Translate whole book' })).toBeEnabled();
});
test('opens the optional sample with readable original text before translation', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('button', { name: 'Try a sample book' }).click();
  await page.getByRole('button', { name: 'Read ↗', exact: true }).click();
  await expect(page.locator('.reader-copy').first()).toContainText('A quieter morning');
  await expect(page.locator('.reader-toolbar select')).toHaveValue('original');
  await expect(page.locator('.reader-toolbar option[value=translation]')).toBeDisabled();
  await expect(page.locator('.reader-copy').first()).not.toContainText('No cached translation');
  await page.screenshot({ path: `test-results/sample-reader-${test.info().project.name}.png` });
});
