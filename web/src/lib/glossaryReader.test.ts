import { describe, expect, it } from 'vitest';
import { canonicalReaderPages, glossaryReaderParts, glossaryReaderProjection } from './glossaryReader';
import { readingRangeText } from './readingSelection';
import { reflowReaderLocation } from '../components/readerPosition';
import type { ReadingDocument } from './readingDocument';
describe('display aliases preserve the original reader coordinate system', () => {
  const document: ReadingDocument = { id: 'immutable', kind: 'original', bookTitle: 'Book', chapterTitle: 'One', language: 'en', paragraphs: [
    { paragraphId: 'p1', text: 'Alice goes to City.' }, { paragraphId: 'p2', text: 'Alice returns.' }] };
  it('maps positions and selected quotes back to immutable text after alias length changes', () => {
    const projection = glossaryReaderProjection(document, [{ source: 'Alice', target: '앨리스', displayTerm: 'A' }, { source: 'City', target: '도시', displayTerm: 'Big City', kind: 'Place' }]);
    expect(projection.document.paragraphs[0].text).toBe('A goes to Big City.');
    const source = { paragraphId: 'p1', characterOffset: 6 };
    expect(projection.sourceAnchor(projection.displayAnchor(source))).toEqual(source);
    const range = { start: projection.sourceAnchor({ paragraphId: 'p1', characterOffset: 0 }), end: projection.sourceAnchor({ paragraphId: 'p1', characterOffset: 1 }) };
    expect(readingRangeText(document, range)).toBe('Alice');
    expect(document.paragraphs[0].text).toBe('Alice goes to City.');
    const text = projection.document.paragraphs[0].text;
    expect(reflowReaderLocation([[{ paragraphId: 'p1', text: text.slice(0, 2), start: 0, end: 2 }], [{ paragraphId: 'p1', text: text.slice(2), start: 2, end: text.length }]], projection.displayAnchor(source)).page).toBe(1);
  });
  it('combines alias emphasis with user highlights without changing or duplicating display text', () => {
    const parts = glossaryReaderParts('Name abc', 10, [{ start: 10, end: 14 }], [{ text: 'Na', highlighted: false }, { text: 'me a', highlighted: true }, { text: 'bc', highlighted: false }]);
    expect(parts.map(part => part.text).join('')).toBe('Name abc');
    expect(parts.find(part => part.text === 'me')).toMatchObject({ emphasized: true, highlighted: true });
    expect(parts.find(part => part.text === ' a')).toMatchObject({ emphasized: false, highlighted: true });
  });
  it('expands partial alias selections and saved highlights to original term boundaries', () => {
    const projection = glossaryReaderProjection(document, [{ source: 'Alice', target: '앨리스', displayTerm: 'The Main Character' }]);
    const partialAlias = { start: projection.sourceAnchor({ paragraphId: 'p1', characterOffset: 4 }, 'start'),
      end: projection.sourceAnchor({ paragraphId: 'p1', characterOffset: 8 }, 'end') };
    expect(readingRangeText(document, partialAlias)).toBe('Alice');
    expect(partialAlias).toEqual({ start: { paragraphId: 'p1', characterOffset: 0 }, end: { paragraphId: 'p1', characterOffset: 5 } });
    const storedPartialTerm = { start: { paragraphId: 'p1', characterOffset: 1 }, end: { paragraphId: 'p1', characterOffset: 3 } };
    expect(projection.displayRange(storedPartialTerm)).toEqual({ start: { paragraphId: 'p1', characterOffset: 0 },
      end: { paragraphId: 'p1', characterOffset: 18 } });
    // A selection ending immediately before the alias must never include it.
    expect(projection.sourceAnchor({ paragraphId: 'p1', characterOffset: 0 }, 'end').characterOffset).toBe(0);
    expect(projection.displayAnchor({ paragraphId: 'p1', characterOffset: 0 }, 'end').characterOffset).toBe(0);
  });
  it('reports page fragments using canonical source boundaries for translation callers', () => {
    const projection = glossaryReaderProjection(document, [{ source: 'Alice', target: '앨리스', displayTerm: 'The Main Character' }]);
    const pages = canonicalReaderPages([[{ paragraphId: 'p1', start: 0, end: 8 }], [{ paragraphId: 'p1', start: 8, end: 24 }]], projection);
    expect(pages[0]).toEqual([{ paragraphId: 'p1', start: 0, end: 5 }]);
    expect(pages[1]).toEqual([{ paragraphId: 'p1', start: 0, end: 11 }]);
    expect(document.paragraphs[0].text.slice(pages[0][0].start, pages[0][0].end)).toBe('Alice');
  });
});
