import { ApiError } from './errors';
import { normalizeGlossary } from './personalLibrary';
import { validRecordId } from './validation';
import type { NovelChapter, NovelDetail, StartTranslation, StoredChapter, TranslationJob, WorkflowClient } from './workflowApi';

export type BulkSettings = Pick<StartTranslation, 'providerKind' | 'targetLanguage' | 'sourceLanguage' | 'endpoint' | 'model' | 'glossary'>;
export type BulkItem = {
  order: number; title: string; url: string; chapterId: string; sourceLanguage: string;
  requestId: string; chapterRecordId?: string; jobId?: string; retryOf?: string; translationRecordId?: string;
  state: 'pending' | 'imported' | 'submitting' | 'running' | 'complete' | 'failed';
  completedParagraphs: number; totalParagraphs: number;
};
export type BulkQueue = {
  version: 1; queueId: string; username: string; bookUrl: string; bookId: string; sourceId: string; title: string;
  state: 'ready' | 'running' | 'paused' | 'cancelling' | 'cancelled' | 'failed' | 'completed';
  cursor: number; items: BulkItem[]; settings: BulkSettings | null; requiresConfiguration: boolean; updatedAt: string;
  error?: string; owner?: string; leaseUntil?: number;
};
type Options = { indexedDB?: IDBFactory; dbName?: string; now?: () => number };
const failure = (message = '일괄 작업 기록을 확인할 수 없습니다.') => new Error(message);
const states = ['ready', 'running', 'paused', 'cancelling', 'cancelled', 'failed', 'completed'];
const itemStates = ['pending', 'imported', 'submitting', 'running', 'complete', 'failed'];
const active = (job: TranslationJob) => job.status === 'RUNNING' || job.status === 'QUEUED';
function check(condition: unknown): asserts condition { if (!condition) throw failure(); }
function sourceUrl(value: string) {
  const url = new URL(value);
  check(['http:', 'https:'].includes(url.protocol) && !url.username && !url.password && !url.hash);
  return value;
}
/** Explicit projection is essential: never persist the caller's API key or generated request object. */
export function bulkSettings(input: BulkSettings): BulkSettings {
  check(['GOOGLE_WEB_TRANSLATE_HTML', 'GOOGLE_CLOUD', 'DEEPSEEK', 'OPENAI_COMPATIBLE_LLM'].includes(input.providerKind));
  const language = /^[A-Za-z][A-Za-z0-9-]{0,23}$/;
  check(language.test(input.targetLanguage) && input.targetLanguage.toLowerCase() !== 'auto');
  if (input.sourceLanguage) check(language.test(input.sourceLanguage));
  if (input.endpoint) {
    sourceUrl(input.endpoint);
    const endpoint = new URL(input.endpoint);
    check(!endpoint.search && (endpoint.protocol === 'https:' || ['localhost', '127.0.0.1', '[::1]'].includes(endpoint.hostname)));
  }
  check(!input.model || input.model.length <= 200);
  return { providerKind: input.providerKind, targetLanguage: input.targetLanguage,
    sourceLanguage: input.sourceLanguage || undefined, endpoint: input.endpoint || undefined, model: input.model || undefined,
    glossary: normalizeGlossary(input.glossary ?? []) };
}
function validate(value: unknown, username: string): BulkQueue {
  check(!!value && typeof value === 'object');
  const q = value as BulkQueue;
  check(q.version === 1 && q.username === username && validRecordId(q.queueId) && states.includes(q.state));
  sourceUrl(q.bookUrl);
  check(typeof q.bookId === 'string' && typeof q.sourceId === 'string' && typeof q.title === 'string');
  check(Array.isArray(q.items) && q.items.length > 0 && q.items.length <= 20);
  check(Number.isInteger(q.cursor) && q.cursor >= 0 && q.cursor <= q.items.length);
  check(Number.isFinite(Date.parse(q.updatedAt)));
  check(q.settings === null || (!!q.settings && typeof q.settings === 'object'));
  check(typeof q.requiresConfiguration === 'boolean' && (!q.requiresConfiguration || q.settings === null));
  if (q.owner !== undefined) check(typeof q.owner === 'string' && Number.isFinite(q.leaseUntil));
  for (const item of q.items) {
    check(Number.isInteger(item.order) && item.order > 0 && typeof item.title === 'string' && typeof item.chapterId === 'string');
    check(typeof item.sourceLanguage === 'string' && validRecordId(item.requestId) && itemStates.includes(item.state));
    sourceUrl(item.url);
    for (const id of [item.chapterRecordId, item.jobId, item.retryOf, item.translationRecordId]) if (id !== undefined) check(validRecordId(id));
    check(Number.isInteger(item.completedParagraphs) && item.completedParagraphs >= 0 && Number.isInteger(item.totalParagraphs) && item.totalParagraphs >= item.completedParagraphs);
    if (item.state !== 'pending' && item.state !== 'failed') check(!!item.chapterRecordId);
    if (item.state === 'running') check(!!item.jobId);
    if (item.state === 'complete' && q.settings) check(!!item.translationRecordId);
  }
  check(new Set(q.items.map(i => i.url)).size === q.items.length && new Set(q.items.map(i => i.requestId)).size === q.items.length);
  check(q.items.every((i, n) => !n || i.order > q.items[n - 1].order));
  check(q.items.slice(0, q.cursor).every(i => i.state === 'complete'));
  if (q.state === 'completed') check(q.cursor === q.items.length);
  // A corrupt/old record that contains credentials must never be replayed.
  const forbidden = (v: unknown): boolean => !!v && typeof v === 'object' && Object.entries(v).some(([key, child]) =>
    ['apikey', 'password', 'authorization'].includes(key.toLowerCase()) || forbidden(child));
  check(!forbidden(value));
  if (q.settings) q.settings = bulkSettings(q.settings);
  return structuredClone(q);
}

/** One explicit queue per account; a completed/cancelled queue is replaced only by create(). */
export function createBulkQueueStorage(username: string, options: Options = {}) {
  check(username.trim().length > 0 && username.length <= 200);
  const now = options.now ?? Date.now;
  async function transaction<T>(mode: IDBTransactionMode, action: (store: IDBObjectStore, done: (value: T) => void, fail: (error: unknown) => void) => void) {
    const factory = options.indexedDB ?? globalThis.indexedDB;
    if (!factory) throw failure('이 브라우저에서는 일괄 작업을 보관할 수 없습니다.');
    const db = await new Promise<IDBDatabase>((resolve, reject) => {
      const request = factory.open(options.dbName ?? 'pageturner-bulk-novel-queue', 1);
      let abandoned = false;
      request.onupgradeneeded = () => request.result.createObjectStore('queues', { keyPath: 'username' });
      request.onblocked = () => { abandoned = true; reject(failure('다른 창을 닫고 다시 시도해 주세요.')); };
      request.onerror = () => reject(failure('일괄 작업 저장소를 열지 못했습니다.'));
      request.onsuccess = () => { if (abandoned) { request.result.close(); return; } request.result.onversionchange = () => request.result.close(); resolve(request.result); };
    });
    return new Promise<T>((resolve, reject) => {
      let result: T, error: unknown;
      const tx = db.transaction('queues', mode);
      tx.oncomplete = () => { db.close(); resolve(result); };
      tx.onabort = () => { db.close(); reject(error ?? failure(tx.error?.name === 'QuotaExceededError' ? '기기 저장 공간이 부족합니다.' : '일괄 작업을 저장하지 못했습니다.')); };
      tx.onerror = () => {};
      try { action(tx.objectStore('queues'), value => { result = value; }, cause => { error = cause; tx.abort(); }); }
      catch (cause) { error = cause; tx.abort(); }
    });
  }
  const load = () => transaction<BulkQueue | null>('readonly', (store, done, fail) => {
    const request = store.get(username);
    request.onsuccess = () => { try { done(request.result ? validate(request.result, username) : null); } catch (error) { fail(error); } };
  });
  const update = (change: (queue: BulkQueue | null) => BulkQueue) => transaction<BulkQueue>('readwrite', (store, done, fail) => {
    const request = store.get(username);
    request.onsuccess = () => { try {
      const queue = change(request.result ? validate(request.result, username) : null);
      queue.updatedAt = new Date(now()).toISOString();
      const clean = validate(queue, username);
      store.put(clean); done(clean);
    } catch (error) { fail(error); } };
  });
  return {
    load,
    discardCorrupt() {
      return transaction<void>('readwrite', (store, done, fail) => {
        const request = store.get(username);
        request.onsuccess = () => {
          if (!request.result) { done(); return; }
          try { validate(request.result, username); }
          catch { store.delete(username); done(); return; }
          fail(failure('정상 작업 기록은 완료하거나 취소한 뒤 교체해 주세요.'));
        };
      });
    },
    create(detail: NovelDetail, chapters: Array<NovelChapter & { order: number }>) {
      return update(previous => {
        if (previous && !['completed', 'cancelled'].includes(previous.state)) throw failure('기존 일괄 작업을 완료하거나 취소한 뒤 새로 선택해 주세요.');
        return { version: 1, username, queueId: crypto.randomUUID(), bookUrl: detail.url, bookId: detail.bookId, sourceId: detail.sourceId,
          title: detail.title, state: 'ready', cursor: 0, settings: null, requiresConfiguration: false, updatedAt: new Date(now()).toISOString(),
          items: chapters.map(c => ({ order: c.order, title: c.title, url: c.url, chapterId: c.chapterId, sourceLanguage: c.sourceLanguage,
            requestId: crypto.randomUUID(), state: 'pending', completedParagraphs: 0, totalParagraphs: 0 })) };
      });
    },
    configure(settings: BulkSettings | null) {
      const clean = settings ? bulkSettings(settings) : null;
      return update(q => { check(q && q.state === 'ready' && !q.owner); q.settings = clean; q.requiresConfiguration = false; return q; });
    },
    command(state: 'paused' | 'cancelling') {
      return update(q => { check(q); if (q.state !== 'completed') q.state = state; return q; });
    },
    async claim(owner: string) {
      return update(q => {
        check(q);
        if (q.owner && q.owner !== owner && (q.leaseUntil ?? 0) > now()) throw failure('다른 창에서 일괄 작업을 진행하고 있습니다. 잠시 후 다시 시도해 주세요.');
        q.owner = owner; q.leaseUntil = now() + 120_000;
        if (q.state !== 'cancelling' && q.state !== 'completed') q.state = 'running';
        q.error = undefined;
        return q;
      });
    },
    commit(owner: string, mutate: (queue: BulkQueue) => void) {
      return update(q => { check(q); if (q.owner !== owner) throw failure('일괄 작업의 실행 권한이 다른 창으로 변경되었습니다.');
        mutate(q); q.leaseUntil = now() + 120_000; return q; });
    },
    release(owner: string) {
      return update(q => { check(q); if (q.owner === owner) { q.owner = undefined; q.leaseUntil = undefined; if (q.state === 'running') q.state = 'paused'; } return q; });
    },
  };
}
export type BulkQueueStorage = ReturnType<typeof createBulkQueueStorage>;

/** Import just the first selected chapter so the existing translation settings form can be reused. */
export async function prepareBulkTranslation(storage: BulkQueueStorage, client: Pick<WorkflowClient, 'importChapter' | 'chapter'>) {
  const owner = crypto.randomUUID();
  const queue = await storage.claim(owner);
  try {
    check(queue.cursor === 0 && !queue.settings);
    await storage.commit(owner, q => { q.requiresConfiguration = true; });
    const first = queue.items[0];
    const chapter = first.chapterRecordId ? await client.chapter(first.chapterRecordId) : await client.importChapter(first.url, queue.bookUrl);
    check(chapter.bookId === queue.bookId && chapter.chapterId === first.chapterId);
    await storage.commit(owner, q => {
      Object.assign(q.items[0], { chapterRecordId: chapter.recordId, state: 'imported', totalParagraphs: chapter.paragraphs.length });
      if (q.state === 'running') q.state = 'ready';
    });
    return chapter;
  } finally { await storage.release(owner); }
}

/** Ranges are positions in the table of contents, independent of provider chapter-number conventions. */
export async function collectBulkChapters(client: Pick<WorkflowClient, 'detail'>, detail: NovelDetail, from: number, to: number, signal?: AbortSignal) {
  if (!Number.isInteger(from) || !Number.isInteger(to) || from < 1 || to < from || to > detail.totalItems || to - from >= 20) {
    throw failure('목차 순서로 한 번에 1~20회차를 선택해 주세요.');
  }
  check(detail.size > 0);
  const result: Array<NovelChapter & { order: number }> = [];
  for (let page = Math.floor((from - 1) / detail.size); page <= Math.floor((to - 1) / detail.size); page++) {
    const value = page === detail.page ? detail : await client.detail(detail.url, page, signal);
    check(value.bookId === detail.bookId && value.sourceId === detail.sourceId && value.page === page && value.size === detail.size);
    value.chapters.forEach((chapter, index) => { const order = page * value.size + index + 1; if (order >= from && order <= to) result.push({ ...chapter, order }); });
  }
  check(result.length === to - from + 1 && new Set(result.map(c => c.url)).size === result.length);
  return result;
}

type QueueClient = Pick<WorkflowClient, 'importChapter' | 'chapter' | 'start' | 'job' | 'cancel'>;
type RunOptions = { apiKey?: string; signal?: AbortSignal; onChange?: (queue: BulkQueue) => void; wait?: () => Promise<void> };
/** No automatic execution on load. Each run is explicitly invoked and owns an expiring IndexedDB lease. */
export async function runBulkQueue(storage: BulkQueueStorage, client: QueueClient, options: RunOptions = {}) {
  const owner = crypto.randomUUID();
  let queue = await storage.claim(owner);
  const emit = () => options.onChange?.(structuredClone(queue));
  const commit = async (mutate: (q: BulkQueue) => void) => { queue = await storage.commit(owner, mutate); emit(); };
  const wait = options.wait ?? (() => new Promise<void>(resolve => setTimeout(resolve, 1500)));
  const verifyJob = (job: TranslationJob, item: BulkItem) => {
    check(job.chapterRecordId === item.chapterRecordId && job.providerKind === queue.settings?.providerKind && job.targetLanguage === queue.settings?.targetLanguage);
  };
  emit();
  try {
    if (queue.requiresConfiguration && queue.state !== 'cancelling') throw failure('번역 설정을 완료한 뒤 일괄 작업을 시작해 주세요.');
    while (queue.cursor < queue.items.length) {
      await commit(() => {}); // observes pause/cancel commands and renews ownership before any side effect
      if (options.signal?.aborted || queue.state === 'paused') break;
      const index = queue.cursor;
      let item = queue.items[index];
      if (queue.state === 'cancelling') {
        // A request whose response was lost is recovered using its persisted idempotency key first.
        if (!item.jobId && item.state === 'submitting' && queue.settings && item.chapterRecordId) {
          const recovered = await client.start({ ...queue.settings, chapterRecordId: item.chapterRecordId, idempotencyKey: item.requestId, retryOf: item.retryOf, apiKey: options.apiKey });
          verifyJob(recovered, item);
          await commit(q => { q.items[index].jobId = recovered.jobId; }); item = queue.items[index];
        }
        if (item.jobId) {
          const previous = await client.job(item.jobId);
          const job = active(previous) ? await client.cancel(previous.jobId) : previous;
          verifyJob(job, item);
          await commit(q => {
            if (job.status === 'COMPLETED') { Object.assign(q.items[index], { state: 'complete', translationRecordId: job.translationRecordId }); q.cursor++; }
            else q.items[index].state = 'failed';
          });
        }
        await commit(q => { q.state = 'cancelled'; }); break;
      }
      if (!item.chapterRecordId) {
        // Do not abort a mutation on Pause: record its result, then stop before the next operation.
        const chapter = await client.importChapter(item.url, queue.bookUrl);
        check(chapter.bookId === queue.bookId && chapter.chapterId === item.chapterId);
        await commit(q => { Object.assign(q.items[index], { chapterRecordId: chapter.recordId, state: 'imported', totalParagraphs: chapter.paragraphs.length }); });
        continue;
      }
      if (!queue.settings) {
        await commit(q => { q.items[index].state = 'complete'; q.cursor++; }); continue;
      }
      if (item.jobId) {
        const job = await client.job(item.jobId, options.signal);
        verifyJob(job, item);
        await commit(q => { const current = q.items[index]; current.completedParagraphs = job.completedParagraphs; current.totalParagraphs = job.totalParagraphs; });
        if (job.status === 'COMPLETED') {
          check(!!job.translationRecordId);
          await commit(q => { Object.assign(q.items[index], { state: 'complete', translationRecordId: job.translationRecordId }); q.cursor++; }); continue;
        }
        if (active(job)) { await wait(); continue; }
        if (item.state !== 'failed') {
          await commit(q => { q.items[index].state = 'failed'; q.state = 'failed'; q.error = '회차 번역이 중단되었습니다. 설정을 유지해 다시 시도할 수 있습니다.'; }); break;
        }
        // Explicit resume after failure/cancellation creates a new request linked to committed checkpoints.
        await commit(q => {
          q.settings = bulkSettings({ providerKind: job.providerKind, ...job.settings });
          Object.assign(q.items[index], { retryOf: job.jobId, jobId: undefined, requestId: crypto.randomUUID(), state: 'imported' });
        });
        continue;
      }
      await commit(q => { q.items[index].state = 'submitting'; });
      item = queue.items[index];
      if (queue.state !== 'running' || options.signal?.aborted) continue;
      const job = await client.start({ ...queue.settings, sourceLanguage: queue.settings.sourceLanguage || item.sourceLanguage,
        chapterRecordId: item.chapterRecordId!, idempotencyKey: item.requestId, retryOf: item.retryOf, apiKey: options.apiKey });
      verifyJob(job, item);
      await commit(q => { Object.assign(q.items[index], { jobId: job.jobId, state: 'running', totalParagraphs: job.totalParagraphs }); });
    }
    if (queue.cursor === queue.items.length) await commit(q => { q.state = 'completed'; });
  } catch (error) {
    if (!options.signal?.aborted) {
      // Preserve submitting state: its response may be lost after the server has accepted the request.
      await commit(q => { q.state = 'failed'; q.error = error instanceof ApiError ? error.message : '일괄 작업을 완료하지 못했습니다. 연결과 저장 공간을 확인한 뒤 다시 시도해 주세요.'; });
    }
    throw error;
  } finally {
    queue = await storage.release(owner); emit();
  }
  return queue;
}
