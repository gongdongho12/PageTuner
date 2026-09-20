import { describe, expect, it } from 'vitest';
import { defaultState, sha256, splitText, translationKey, type LibraryState } from '../src/lib/model';
import { exportBackup, mergeBackup, parseBackup } from '../src/lib/storage';
const sample: LibraryState = { ...structuredClone(defaultState), activeId: 'book', books: [{
  id: 'book', title: 'A book', format: 'TXT', pages: [{ text: 'Hello world', chapter: 'One' }],
  currentPage: 0, folder: 'Fiction', tags: 'sample', importedAt: '', openedAt: '', bookmarks: [0], notes: [{ id: 'n1', page: 0, text: 'Remember' }],
}] };
describe('portable library backups', () => {
  it('roundtrips books, Unicode, annotations, settings and verified translation cache without credentials', async () => {
    const state = structuredClone(sample);
    state.translations = [{ key: translationKey('book', 0, state.settings), bookId: 'book', page: 0,
      provider: state.settings.provider, source: 'auto', target: 'ko', model: '', sourceRevision: await sha256('Hello world'), text: '안녕하세요', createdAt: '' }];
    const file = await exportBackup({ ...state, apiKey: 'do-not-export', password: 'secret' } as LibraryState);
    expect(file).not.toContain('do-not-export'); expect(file).not.toContain('secret');
    expect(await parseBackup(file)).toEqual(state);
  });
  it('rejects tampered content, unknown schemas and invalid page references', async () => {
    const file = JSON.parse(await exportBackup(sample)); file.data.books[0].title = 'tampered';
    await expect(parseBackup(JSON.stringify(file))).rejects.toThrow('integrity');
    file.schemaVersion = 2; await expect(parseBackup(JSON.stringify(file))).rejects.toThrow();
    file.schemaVersion = 1; file.data.books[0].currentPage = 99; await expect(parseBackup(JSON.stringify(file))).rejects.toThrow();
  });
  it('preserves existing reading work when restoring repeatedly', () => {
    const incoming = structuredClone(sample); incoming.books[0].title = 'older title'; incoming.settings.fontSize = 32;
    expect(mergeBackup(sample, incoming)).toEqual(sample);
    expect(mergeBackup(mergeBackup(defaultState, sample), sample).books).toHaveLength(1);
  });
});
describe('stable reader pages and translation keys', () => {
  it('preserves every character and surrogate pair across long text pages', () => {
    const text = ('A story about 한글 and 😀.\n'.repeat(200)).trim(); const pages = splitText(text, 63);
    expect(pages.join('')).toBe(text); expect(pages.every(p => p.length <= 63)).toBe(true);
    expect(pages.some(p => /[\uD800-\uDBFF]$/.test(p))).toBe(false);
  });
  it('separates provider/model/language caches and ignores unrelated model for Google', () => {
    const base = defaultState.settings;
    expect(translationKey('a', 0, base)).not.toBe(translationKey('a', 1, base));
    expect(translationKey('a', 0, base)).not.toBe(translationKey('a', 0, { ...base, target: 'en' }));
    expect(translationKey('a', 0, base)).toBe(translationKey('a', 0, { ...base, model: 'unused' }));
    expect(translationKey('a', 0, { ...base, provider: 'llm', model: 'one' })).not.toBe(translationKey('a', 0, { ...base, provider: 'llm', model: 'two' }));
  });
});
