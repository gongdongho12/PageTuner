import { webcrypto } from 'node:crypto';
import { beforeAll, describe, expect, it, vi } from 'vitest';
import inputFixture from '../../../contracts/fixtures/catalog-translations-v1/request.json';
import resultFixture from '../../../contracts/fixtures/catalog-translations-v1/completed-response.json';
import { catalogSourceHash, createCatalogTranslationRequest, validateCatalogTranslation, verifyCatalogTranslation,
  type CatalogTranslationRequest } from './catalogTranslation';
import { createWorkflowClient, type NovelBook } from './workflowApi';

beforeAll(() => vi.stubGlobal('crypto', webcrypto));
const input = inputFixture as CatalogTranslationRequest;
const book = (n: number): NovelBook => ({ bookId: `book-${n}`, title: `Title ${n}`, url: `https://source.example/${n}`, authors: [],
  description: 'A short description.', sourceLanguage: 'en', coverUrl: null, chapterCount: 1, tags: [] });
const json = (value: unknown) => new Response(JSON.stringify(value), { headers: { 'Content-Type': 'application/json' } });

describe('catalog display translation contract', () => {
  it('matches the shared Kotlin source digest and preserves distinct catalog identities', async () => {
    expect(await catalogSourceHash(input)).toBe(resultFixture.sourceHash);
    const result = validateCatalogTranslation(resultFixture);
    expect(await verifyCatalogTranslation(result, input)).toEqual(resultFixture);
    expect(result.items.map(item => item.key)).toEqual(input.items.map(item => item.key));
  });

  it('rejects mismatched originals, missing descriptions and partial results advertised as complete', async () => {
    const result = validateCatalogTranslation(resultFixture);
    await expect(verifyCatalogTranslation(result, { ...input, items: input.items.map(item => ({ ...item, title: 'Changed source' })) })).rejects.toMatchObject({ kind: 'invalid-response' });
    await expect(verifyCatalogTranslation({ ...result, items: result.items.map(item => ({ ...item, description: null })) }, input)).rejects.toMatchObject({ kind: 'invalid-response' });
    expect(() => validateCatalogTranslation({ ...resultFixture, completedSegments: 2 })).toThrow();
    expect(() => validateCatalogTranslation({ ...resultFixture, status: 'RUNNING' })).toThrow();
  });

  it('bounds visible books and text without splitting supplementary Unicode characters', () => {
    const books = Array.from({ length: 25 }, (_, index) => ({ ...book(index), title: 'a'.repeat(399) + '😀', description: 'b'.repeat(2400) }));
    const result = createCatalogTranslationRequest(books, { providerKind: 'GOOGLE_WEB_TRANSLATE_HTML', targetLanguage: 'ko' });
    expect(result.truncated).toBe(true); expect(result.input.items).toHaveLength(24);
    expect(result.input.items.every(item => item.title.length === 399)).toBe(true);
    expect(result.input.items.reduce((sum, item) => sum + item.title.length + (item.description?.length ?? 0), 0)).toBeLessThanOrEqual(24_000);
    expect(result.input.items.every(item => (item.description?.length ?? 0) <= 2000)).toBe(true);
    expect(books[0].title).toContain('😀');
  });

  it('uses the authenticated workflow transport and same-session CSRF for create and cancel', async () => {
    const requests: Array<{ url: string; options?: RequestInit }> = [];
    const client = createWorkflowClient({ username: 'reader', password: 'memory-password' }, { fetch: async (url, options) => {
      requests.push({ url: String(url), options });
      return json(String(url).endsWith('/csrf') ? { headerName: 'X-CSRF-TOKEN', token: 'csrf-token' } : resultFixture);
    } });
    await client.startCatalogTranslation(input);
    await client.getCatalogTranslation(input.requestId);
    await client.cancelCatalogTranslation(input.requestId);
    const mutations = requests.filter(request => request.options?.method === 'POST');
    expect(mutations).toHaveLength(2);
    for (const request of mutations) {
      const headers = new Headers(request.options?.headers);
      expect(headers.get('Authorization')).toMatch(/^Basic /);
      expect(headers.get('X-CSRF-TOKEN')).toBe('csrf-token');
      expect(request.options?.credentials).toBe('same-origin');
      expect(request.options?.redirect).toBe('error');
    }
    expect(mutations[1].url).toBe(`/api/v1/catalog-translations/${input.requestId}/cancel`);
    client.close();
  });

  it('aborts outstanding progress reads when the authenticated client closes', async () => {
    let entered!: () => void;
    const started = new Promise<void>(resolve => { entered = resolve; });
    const client = createWorkflowClient({ username: 'reader', password: 'password' }, { fetch: async (_url, options) => {
      entered();
      return new Promise<Response>((_resolve, reject) => options?.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true }));
    } });
    const pending = client.getCatalogTranslation(input.requestId);
    await started; client.close();
    await expect(pending).rejects.toMatchObject({ kind: 'aborted' });
  });
});
