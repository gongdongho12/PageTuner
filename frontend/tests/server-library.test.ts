import { describe, expect, it, vi } from 'vitest';
import { ProgressWriter, ServerLibraryApi, ServerApiError, bookForServer } from '../src/lib/server-library';
import type { Book } from '../src/lib/model';
const book: Book = { id: 'local-book', title: 'Book', format: 'TXT', pages: [{ chapter: 'First', text: 'a'.repeat(99999) + '😀end' }],
  currentPage: 0, folder: '', tags: '', importedAt: '', openedAt: '', bookmarks: [], notes: [] };
describe('server library data flow', () => {
  it('uploads bounded source paragraphs without losing unicode or transmitting PDF data', () => {
    const payload = bookForServer({ ...book, pdf: 'secret-image-data' }, 'en');
    expect(payload.chapters[0].paragraphs.map(p => p.text).join('')).toBe(book.pages[0].text);
    expect(payload.chapters[0].paragraphs.every(p => p.text.length <= 100000)).toBe(true);
    expect(JSON.stringify(payload)).not.toContain('secret-image-data');
    expect(() => bookForServer(book, 'auto')).toThrow();
    expect(() => bookForServer({ ...book, pages: [{ chapter: 'Image', text: '' }] }, 'en')).toThrow();
  });
  it('authenticates every request with memory-only Basic credentials and bootstraps CSRF for writes', async () => {
    const calls: { path: string; init?: RequestInit }[] = [];
    const api = new ServerLibraryApi((async (url, init) => {
      calls.push({ path: String(url), init });
      if (String(url).endsWith('/csrf')) return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'fresh' });
      if (init?.method === 'POST') return new Response(null, { status: 204 });
      return Response.json({ username: 'reader' });
    }) as typeof fetch);
    await api.login({ username: 'reader', password: 'password' });
    await api.request('/library/books', 'POST', {});
    api.logout();
    await expect(api.session()).rejects.toMatchObject({ status: 401 });
    expect(calls.map(c => c.path)).toEqual(['/api/v1/session','/api/v1/csrf','/api/v1/library/books']);
    expect(new Headers(calls[2].init?.headers).get('X-CSRF-TOKEN')).toBe('fresh');
    expect(calls.every(c => new Headers(c.init?.headers).get('Authorization') === 'Basic ' + btoa('reader:password'))).toBe(true);
    expect(calls.every(c => c.init?.credentials === 'same-origin')).toBe(true);
  });
  it('refuses stale mutation work when an account disconnects during CSRF retrieval', async () => {
    let release!: (response: Response) => void;
    const fetcher = vi.fn().mockResolvedValueOnce(Response.json({ username: 'reader' }))
      .mockImplementationOnce(() => new Promise<Response>(resolve => { release = resolve; }));
    const api = new ServerLibraryApi(fetcher);
    await api.login({ username: 'reader', password: 'password' });
    const write = api.request('/library/books', 'POST', {});
    api.logout();
    release(Response.json({ headerName: 'X-CSRF-TOKEN', token: 'old-account' }));
    await expect(write).rejects.toMatchObject({ status: 401 });
    expect(fetcher).toHaveBeenCalledTimes(2);
  });
  it('drops failed connection credentials instead of silently keeping an authenticated client', async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response(null, { status: 401 }));
    const api = new ServerLibraryApi(fetcher);
    await expect(api.login({ username: 'reader', password: 'invalid' })).rejects.toMatchObject({ status: 401 });
    await expect(api.books()).rejects.toMatchObject({ status: 401 });
    expect(fetcher).toHaveBeenCalledTimes(1);
  });
  it('discards a previous account response when disconnected while its body is being decoded', async () => {
    let release!: (value: unknown) => void;
    let started!: () => void;
    const decoding = new Promise<void>(resolve => { started = resolve; });
    const response = Response.json({});
    vi.spyOn(response, 'json').mockImplementation(() => {
      started(); return new Promise(resolve => { release = resolve; });
    });
    const fetcher = vi.fn().mockResolvedValueOnce(Response.json({ username: 'reader' })).mockResolvedValueOnce(response);
    const api = new ServerLibraryApi(fetcher);
    await api.login({ username: 'reader', password: 'password' });
    const read = api.books();
    await decoding;
    api.logout();
    release({ items: [{ id: 'previous-account-book' }] });
    await expect(read).rejects.toMatchObject({ status: 401 });
  });
  it('requests translations in the exact original chapter and source language within the server page limit', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(Response.json({ username: 'reader' })).mockResolvedValueOnce(Response.json({ items: [] }));
    const api = new ServerLibraryApi(fetcher);
    await api.login({ username: 'reader', password: 'password' });
    await api.translations({ id: 'source/book', title: '', author: '', sourceLanguage: 'en', chapterCount: 1, createdAt: '' },
      { id: 'chapter/1', bookId: 'source/book', sourceLanguage: 'en', sourceRevision: 'full:revision', ordinal: 0, title: '', paragraphs: [] }, 'ko');
    const params = new URL(String(fetcher.mock.calls[1][0]), 'https://reader.test').searchParams;
    expect(Object.fromEntries(params)).toEqual({ contentProviderId: 'library', bookId: 'source/book', chapterId: 'chapter/1',
      sourceRevision: 'full:revision', sourceLanguage: 'en', targetLanguage: 'ko', size: '50' });
  });
  it('serializes rapid page turns against the last confirmed version', async () => {
    const api = new ServerLibraryApi();
    const save = vi.spyOn(api, 'saveProgress').mockImplementation(async (_, anchor, version) => ({ anchor, version: version + 1, updatedAt: '' }));
    const writer = new ProgressWriter(api, 'book', { anchor: null, version: 0, updatedAt: null });
    const anchor = { chapterId: 'chapter', paragraphId: 'p1', characterOffset: 0 };
    await Promise.all([writer.save(anchor), writer.save({ ...anchor, characterOffset: 5 }), writer.save({ ...anchor, characterOffset: 10 })]);
    expect(save.mock.calls.map(c => c[2])).toEqual([0, 1, 2]);
  });
  it('stops queued writes after conflict instead of overwriting another device', async () => {
    const api = new ServerLibraryApi();
    const conflict = new ServerApiError(409);
    const save = vi.spyOn(api, 'saveProgress').mockRejectedValue(conflict);
    const writer = new ProgressWriter(api, 'book', { anchor: null, version: 0, updatedAt: null });
    const anchor = { chapterId: 'chapter', paragraphId: 'p1', characterOffset: 0 };
    const results = await Promise.allSettled([writer.save(anchor), writer.save(anchor)]);
    expect(results.every(r => r.status === 'rejected')).toBe(true); expect(save).toHaveBeenCalledTimes(1);
    // Every queued caller must retain the conflict classification used by the recovery UI.
    expect(results.map(r => r.status === 'rejected' ? r.reason : null)).toEqual([conflict, conflict]);
    await expect(writer.save(anchor)).rejects.toBe(conflict);
    expect(save).toHaveBeenCalledTimes(1);
  });
});
