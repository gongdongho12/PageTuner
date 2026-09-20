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
  it('bootstraps CSRF for login and mutation and uses cookies without persisting credentials', async () => {
    const calls: { path: string; init?: RequestInit }[] = [];
    const api = new ServerLibraryApi((async (url, init) => {
      calls.push({ path: String(url), init });
      if (String(url).endsWith('/csrf')) return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'fresh' });
      if (init?.method === 'POST') return new Response(null, { status: 204 });
      return Response.json({ username: 'reader' });
    }) as typeof fetch);
    await api.login({ username: 'reader', password: 'password' });
    await api.logout();
    expect(calls.map(c => c.path)).toEqual(['/api/v1/csrf','/api/v1/session','/api/v1/session','/api/v1/csrf','/api/v1/session/logout']);
    expect(calls[1].init?.headers).toEqual({ 'X-CSRF-TOKEN': 'fresh' });
    expect(calls.every(c => c.init?.credentials === 'include')).toBe(true);
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
