import { webcrypto } from 'node:crypto';
import { IDBFactory } from 'fake-indexeddb';
import { beforeAll, describe, expect, it, vi } from 'vitest';
import { ApiError } from './errors';
import { bulkSettings, collectBulkChapters, createBulkQueueStorage, prepareBulkTranslation, runBulkQueue } from './bulkNovelQueue';
import type { NovelDetail, StartTranslation, StoredChapter, TranslationJob, WorkflowClient } from './workflowApi';

beforeAll(() => vi.stubGlobal('crypto', webcrypto));
const id = (n: number) => `00000000-0000-0000-0000-${String(n).padStart(12, '0')}`;
const detail: NovelDetail = { sourceId: 'site', bookId: 'book', url: 'https://source.example/book', title: 'Book', author: 'Author', sourceLanguage: 'en',
  status: '', totalChapters: 40, summary: '', tags: [], coverUrl: null, page: 0, size: 20, totalItems: 40, totalPages: 2, hasNext: true,
  chapters: Array.from({ length: 20 }, (_, i) => ({ chapterId: `c${i + 1}`, number: i + 100, title: `Chapter ${i + 1}`,
    url: `https://source.example/book/${i + 1}`, sourceLanguage: 'en' })) };
const chosen = (count = 2) => detail.chapters.slice(0, count).map((c, i) => ({ ...c, order: i + 1 }));
const settings = { providerKind: 'GOOGLE_WEB_TRANSLATE_HTML' as const, targetLanguage: 'ko', sourceLanguage: 'en', glossary: [] };
function chapter(number: number): StoredChapter { return {
  recordId: id(number), providerId: 'site', bookId: 'book', bookTitle: 'Book', bookUrl: detail.url,
  chapterId: `c${number}`, chapterTitle: `Chapter ${number}`, chapterUrl: `${detail.url}/${number}`, sourceLanguage: 'en',
  sourceRevision: 'a'.repeat(64), paragraphs: [{ paragraphId: `p${number}`, ordinal: 0, text: 'Owned test text.' }], createdAt: new Date(0).toISOString(),
}; }
function job(chapterRecordId: string, status: TranslationJob['status'] = 'COMPLETED'): TranslationJob { return {
  jobId: id(100 + Number(chapterRecordId.slice(-3))), chapterRecordId, bookTitle: 'Book', chapterTitle: 'Chapter', providerKind: 'GOOGLE_WEB_TRANSLATE_HTML', targetLanguage: 'ko',
  settings: { ...settings, endpoint: '', model: 'google-web-public' }, status, completedParagraphs: status === 'COMPLETED' ? 1 : 0, totalParagraphs: 1,
  translationRecordId: status === 'COMPLETED' ? id(200 + Number(chapterRecordId.slice(-3))) : null,
  errorCode: null, errorMessage: null, canRetry: ['FAILED', 'CANCELLED', 'INTERRUPTED'].includes(status), createdAt: new Date(0).toISOString(), updatedAt: new Date(0).toISOString(),
}; }
function client(overrides: Partial<Pick<WorkflowClient, 'importChapter' | 'chapter' | 'start' | 'job' | 'cancel'>> = {}) {
  return {
    importChapter: vi.fn(async (url: string) => chapter(Number(url.split('/').at(-1)))),
    chapter: vi.fn(async (record: string) => chapter(Number(record.slice(-3)))),
    start: vi.fn(async (input: StartTranslation) => job(input.chapterRecordId)),
    job: vi.fn(async (record: string) => job(id(Number(record.slice(-3)) - 100))),
    cancel: vi.fn(async (record: string) => job(id(Number(record.slice(-3)) - 100), 'CANCELLED')),
    ...overrides,
  };
}
const repo = (factory = new IDBFactory(), username = 'reader') => createBulkQueueStorage(username, { indexedDB: factory });

describe('durable bulk chapter queue', () => {
  it('uses table-of-contents positions across pages and limits a selection to twenty chapters', async () => {
    const second = { ...detail, page: 1, hasNext: false, chapters: detail.chapters.map((c, i) => ({ ...c, chapterId: `c${i + 21}`, url: `${detail.url}/${i + 21}` })) };
    const source = { detail: vi.fn(async () => second) };
    const selected = await collectBulkChapters(source, detail, 19, 22);
    expect(selected.map(c => c.order)).toEqual([19, 20, 21, 22]);
    expect(source.detail).toHaveBeenCalledWith(detail.url, 1, undefined);
    await expect(collectBulkChapters(source, detail, 1, 21)).rejects.toThrow();
    await expect(collectBulkChapters({ detail: async () => ({ ...second, bookId: 'different' }) }, detail, 19, 22)).rejects.toThrow();
  });

  it('stores no credentials, separates accounts, and never starts work when a record is loaded', async () => {
    const factory = new IDBFactory(), storage = repo(factory);
    await storage.create(detail, chosen());
    const input: StartTranslation = { ...settings, chapterRecordId: id(1), idempotencyKey: id(999), apiKey: 'private-memory-key' };
    await storage.configure(bulkSettings(input));
    const loaded = await repo(factory).load();
    expect(JSON.stringify(loaded)).not.toContain('private-memory-key');
    expect(JSON.stringify(loaded)).not.toContain('apiKey');
    expect(loaded?.state).toBe('ready');
    expect(await repo(factory, 'other-reader').load()).toBeNull();
  });

  it('processes one chapter at a time and saves completed identifiers before importing the next', async () => {
    const storage = repo(); await storage.create(detail, chosen()); await storage.configure(settings);
    const api = client(); const changes: number[] = [];
    const result = await runBulkQueue(storage, api, { wait: async () => {}, onChange: q => changes.push(q.cursor) });
    expect(result.state).toBe('completed'); expect(result.cursor).toBe(2);
    expect(api.start).toHaveBeenCalledTimes(2); expect(api.importChapter).toHaveBeenCalledTimes(2);
    expect(result.items.every(i => !!i.chapterRecordId && !!i.jobId && !!i.translationRecordId)).toBe(true);
    expect(changes).toContain(1); expect(result.owner).toBeUndefined();
  });

  it('persists the idempotency key before an uncertain POST and reuses it after reload', async () => {
    const factory = new IDBFactory(), storage = repo(factory);
    await storage.create(detail, chosen(1)); await storage.configure(settings);
    let failed = false; const ids: string[] = [];
    const api = client({ start: async input => {
      ids.push(input.idempotencyKey);
      if (!failed) { failed = true; throw new ApiError('network', 'Connection interrupted'); }
      return job(input.chapterRecordId);
    } });
    await expect(runBulkQueue(storage, api)).rejects.toThrow('Connection interrupted');
    expect((await storage.load())?.items[0].state).toBe('submitting');
    const result = await runBulkQueue(repo(factory), api);
    expect(result.state).toBe('completed'); expect(new Set(ids).size).toBe(1);
  });

  it('pause waits for an in-flight import checkpoint and does not import the next chapter', async () => {
    const storage = repo(); await storage.create(detail, chosen());
    let release!: (chapter: StoredChapter) => void, entered!: () => void;
    const waiting = new Promise<void>(resolve => { entered = resolve; });
    const api = client({ importChapter: vi.fn(() => { entered(); return new Promise<StoredChapter>(resolve => { release = resolve; }); }) });
    const running = runBulkQueue(storage, api); await waiting;
    await storage.command('paused'); release(chapter(1));
    const result = await running;
    expect(result.state).toBe('paused'); expect(result.items[0].chapterRecordId).toBe(id(1));
    expect(api.importChapter).toHaveBeenCalledTimes(1); expect(result.cursor).toBe(0);
    expect((await runBulkQueue(storage, client())).cursor).toBe(2);
  });

  it('cancel preserves originals and retries a cancelled job with its previous settings and a new request ID', async () => {
    const storage = repo(); await storage.create(detail, chosen(1)); await storage.configure(settings);
    const original = await storage.load(); let cancelled = false;
    const api = client({ start: async input => job(input.chapterRecordId, input.retryOf ? 'COMPLETED' : 'RUNNING'),
      job: async () => job(id(1), cancelled ? 'CANCELLED' : 'RUNNING'),
      cancel: async () => { cancelled = true; return job(id(1), 'CANCELLED'); } });
    const result = await runBulkQueue(storage, api, { wait: async () => { await storage.command('cancelling'); } });
    expect(result.state).toBe('cancelled'); expect(result.items[0].chapterRecordId).toBe(id(1));
    const retries: StartTranslation[] = [];
    const resumed = await runBulkQueue(storage, client({ start: async input => { retries.push(input); return job(input.chapterRecordId); },
      job: async record => job(id(Number(record.slice(-3)) - 100), retries.length ? 'COMPLETED' : 'CANCELLED') }));
    expect(resumed.state).toBe('completed'); expect(retries[0].retryOf).toBe(id(101));
    expect(retries[0].idempotencyKey).not.toBe(original!.items[0].requestId);
    expect(retries[0].model).toBe('google-web-public');
  });

  it('an aborted read preserves the known job for explicit resume and competing tabs cannot both own execution', async () => {
    const factory = new IDBFactory(), storage = repo(factory); await storage.create(detail, chosen(1)); await storage.configure(settings);
    await storage.claim('first-window');
    await expect(repo(factory).claim('other-window')).rejects.toThrow('다른 창');
    await storage.release('first-window');
    const controller = new AbortController();
    const api = client({ start: async input => job(input.chapterRecordId, 'RUNNING'), job: async () => {
      controller.abort(); throw new ApiError('aborted', 'Stopped');
    } });
    await expect(runBulkQueue(storage, api, { signal: controller.signal })).rejects.toThrow('Stopped');
    const result = await storage.load(); expect(result?.state).toBe('paused'); expect(result?.items[0].jobId).toBe(id(101));
    expect((await runBulkQueue(repo(factory), client())).state).toBe('completed');
  });

  it('refuses corrupt checkpoints and removes them only through an explicit recovery action', async () => {
    const factory = new IDBFactory(), storage = repo(factory); await storage.create(detail, chosen());
    await expect(storage.discardCorrupt()).rejects.toThrow('정상 작업');
    await new Promise<void>((resolve, reject) => {
      const request = factory.open('pageturner-bulk-novel-queue', 1);
      request.onerror = () => reject(request.error);
      request.onsuccess = () => {
        const db = request.result, tx = db.transaction('queues', 'readwrite'), objectStore = tx.objectStore('queues');
        const get = objectStore.get('reader');
        get.onsuccess = () => objectStore.put({ ...get.result, cursor: 99 });
        tx.oncomplete = () => { db.close(); resolve(); };
      };
    });
    await expect(storage.load()).rejects.toThrow();
    const api = client(); await expect(runBulkQueue(storage, api)).rejects.toThrow();
    expect(api.importChapter).not.toHaveBeenCalled();
    await storage.discardCorrupt(); expect(await storage.load()).toBeNull();
  });

  it('a reload during translation setup cannot silently continue as an originals-only queue', async () => {
    const factory = new IDBFactory(), storage = repo(factory); await storage.create(detail, chosen());
    await prepareBulkTranslation(storage, client());
    const loaded = repo(factory), api = client();
    await expect(runBulkQueue(loaded, api)).rejects.toThrow('번역 설정');
    expect(api.importChapter).not.toHaveBeenCalled();
    await prepareBulkTranslation(loaded, api); await loaded.configure(settings);
    expect((await runBulkQueue(loaded, api)).state).toBe('completed');
  });
});
