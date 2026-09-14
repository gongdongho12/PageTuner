// Opt-in live integration: imports one public chapter and translates it with Google Web.
// No fixtures, fallback translations, or credentials persisted in the report.
import assert from 'node:assert/strict';
import { randomUUID, createHash } from 'node:crypto';
import { writeFile } from 'node:fs/promises';

assert.equal(process.env.RUN_LIVE_TRANSLATION_TESTS, '1', 'Enable the explicit live test first.');
const origin = process.env.PAGETUNER_SERVER_URL ?? 'http://127.0.0.1:8080';
const username = process.env.PAGETUNER_SERVER_USERNAME;
const password = process.env.PAGETUNER_SERVER_PASSWORD;
assert(username && password, 'Supply the test server credentials through environment variables.');
const authorization = `Basic ${Buffer.from(`${username}:${password}`).toString('base64')}`;
let cookie = '';
async function request(path, method = 'GET', body, expected = 200) {
  const headers = { Authorization: authorization, Accept: 'application/json' };
  if (method !== 'GET') {
    const csrf = await request('/api/v1/csrf');
    assert.equal(csrf.headerName, 'X-CSRF-TOKEN');
    headers[csrf.headerName] = csrf.token;
    headers['Content-Type'] = 'application/json';
  }
  if (cookie) headers.Cookie = cookie;
  const response = await fetch(new URL(path, origin), {
    method, headers, body: body === undefined ? undefined : JSON.stringify(body),
    redirect: 'error', signal: AbortSignal.timeout(120_000),
  });
  const session = response.headers.getSetCookie().find(value => value.startsWith('JSESSIONID='));
  if (session) cookie = session.split(';')[0];
  assert.equal(response.status, expected, `${method} ${new URL(path, origin).pathname} failed: ${response.status}`);
  return response.json();
}

const sources = await request('/api/v1/novel-sources');
assert(sources.items.some(item => item.id === 'novelbuddy'));
const catalog = await request('/api/v1/novels/catalog?sourceId=novelbuddy&query=shadow%20slave&page=1');
assert(catalog.items.length > 0);
const book = catalog.items[0];
const detail = await request(`/api/v1/novels/detail?url=${encodeURIComponent(book.url)}&page=0&size=12`);
assert(detail.chapters.length > 0);
const source = await request('/api/v1/chapters/import', 'POST', { url: detail.chapters[0].url, bookUrl: book.url });
assert.equal(source.bookId, detail.bookId);
assert.equal(source.chapterId, detail.chapters[0].chapterId);
assert(source.paragraphs.length > 0);
const original = await request(`/api/v1/chapters/${source.recordId}`);
assert.deepEqual(original.paragraphs, source.paragraphs);
const sourceList = await request('/api/v1/chapters?page=0&size=12');
assert(sourceList.items.some(item => item.recordId === source.recordId && item.paragraphCount === source.paragraphs.length && item.paragraphs === undefined));
const input = { chapterRecordId: source.recordId, providerKind: 'GOOGLE_WEB_TRANSLATE_HTML', targetLanguage: 'ko', idempotencyKey: randomUUID() };
let job = await request('/api/v1/translation-jobs', 'POST', input);
assert.equal((await request('/api/v1/translation-jobs', 'POST', input)).jobId, job.jobId);
const deadline = Date.now() + 10 * 60_000;
let progress = -1;
while (job.status === 'QUEUED' || job.status === 'RUNNING') {
  assert(Date.now() < deadline, 'Full chapter translation exceeded ten minutes.');
  if (progress !== job.completedParagraphs) {
    progress = job.completedParagraphs;
    console.log(`Google Web chapter progress: ${progress}/${job.totalParagraphs}`);
  }
  await new Promise(resolve => setTimeout(resolve, 2000));
  job = await request(`/api/v1/translation-jobs/${job.jobId}`);
}
assert.equal(job.status, 'COMPLETED', `Translation did not complete: ${job.errorCode ?? job.status}`);
assert.equal(job.completedParagraphs, source.paragraphs.length);
const translation = await request(`/api/v1/translations/${job.translationRecordId}`);
assert.deepEqual(translation.paragraphs.map(item => item.paragraphId), source.paragraphs.map(item => item.paragraphId));
assert(translation.paragraphs.every(item => item.text.trim().length > 0));
assert(translation.paragraphs.some(item => /[가-힣]/.test(item.text)));
assert.equal(translation.sourceRevision, source.sourceRevision);
assert.equal(translation.payloadHash, createHash('sha256').update(translation.paragraphs.map(item => `${item.paragraphId}:${item.text}`).join('\n')).digest('hex'));
assert.equal(translation.bookTitle, source.bookTitle);
assert.equal((await request('/api/v1/translation-jobs', 'POST', { ...input, idempotencyKey: randomUUID() })).jobId, job.jobId);
const report = {
  verifiedAt: new Date().toISOString(), sourceId: source.providerId,
  bookTitle: source.bookTitle, chapterTitle: source.chapterTitle,
  chapterRecordId: source.recordId, translationRecordId: translation.recordId, jobId: job.jobId,
  paragraphs: source.paragraphs.length, sourceCharacters: source.paragraphs.reduce((sum, item) => sum + item.text.length, 0),
  translatedCharacters: translation.paragraphs.reduce((sum, item) => sum + item.text.length, 0),
  sourceRevision: source.sourceRevision, payloadHash: translation.payloadHash,
  providerId: translation.translationProviderId, result: 'PASSED',
};
if (process.env.PAGETUNER_LIVE_REPORT) await writeFile(process.env.PAGETUNER_LIVE_REPORT, `${JSON.stringify(report, null, 2)}\n`);
console.log(JSON.stringify(report, null, 2));
