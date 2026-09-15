import { webcrypto } from 'node:crypto';
import { beforeAll, describe, expect, it, vi } from 'vitest';
import { exportGlossary, glossaryRevision, mergeGlossaryEntries, normalizeGlossary, parseGlossaryFile } from './glossary';
beforeAll(() => vi.stubGlobal('crypto', webcrypto));
describe('compatible glossary options', () => {
  const old = [{ source: 'Alice', target: '앨리스' }, { source: 'City', target: '도시' }];
  it('preserves the legacy fingerprint and excludes display options and disabled entries', async () => {
    expect(await glossaryRevision(old)).toBe('e8fb496c68249c6e');
    expect(await glossaryRevision(old.map(entry => ({ ...entry, kind: 'Place', displayTerm: '읽기 이름' })))).toBe('e8fb496c68249c6e');
    expect(await glossaryRevision([...old, { source: 'Other', target: '다른', enabled: false }])).toBe('e8fb496c68249c6e');
    expect(await glossaryRevision(old.map(entry => ({ ...entry, caseSensitive: true })))).not.toBe('e8fb496c68249c6e');
    expect(await glossaryRevision(old.map(entry => ({ ...entry, enabled: false })))).toBe('');
    expect(normalizeGlossary(old.map(entry => ({ ...entry, enabled: true, caseSensitive: false, displayTerm: '', kind: 'Character' })))).toEqual(old);
  });
  it('shares all nondefault options with Android without silently truncating or overwriting local terms', () => {
    const entries = [{ source: 'City', target: '도시', kind: 'Place' as const, displayTerm: '마을', caseSensitive: true, enabled: false }];
    const shared = exportGlossary({ entries, revision: '', updatedAt: '' }, 'book-id', 'Book title');
    expect(JSON.parse(shared)).toMatchObject({ schema: 'pagetuner-book-glossary', version: 1, sourceBookId: 'book-id', bookTitle: 'Book title' });
    expect(parseGlossaryFile(shared)).toEqual(entries);
    expect(mergeGlossaryEntries(old, [{ source: 'Alice', target: '다른 표기' }, { source: 'New', target: '새 용어' }])).toEqual([...old, { source: 'New', target: '새 용어' }]);
    expect(() => exportGlossary({ entries: [{ source: 'x'.repeat(161), target: 'word' }], revision: '', updatedAt: '' })).toThrow('160');
    expect(() => normalizeGlossary([{ source: 'Name', target: '이름', enabled: 'false' }])).toThrow();
    expect(() => parseGlossaryFile('{"schema":"unknown","entries":[]}')).toThrow();
  });
});
