import { applyGlossaryDisplay, type GlossaryDisplayEntry } from './glossaryDisplay';
import type { ReadingDocument } from './readingDocument';
import type { ReadingAnchor } from './offline';
import type { ReadingRange } from './readingSelection';

export type CanonicalReaderRange = { paragraphId: string; start: number; end: number };

/** Layout clients such as rolling translation receive original coordinates, never display aliases. */
export function canonicalReaderPages(pages: readonly (readonly CanonicalReaderRange[])[],
  projection: ReturnType<typeof glossaryReaderProjection>): CanonicalReaderRange[][] {
  return pages.map(page => page.map(fragment => ({ paragraphId: fragment.paragraphId,
    start: projection.sourceAnchor({ paragraphId: fragment.paragraphId, characterOffset: fragment.start }, 'start').characterOffset,
    end: projection.sourceAnchor({ paragraphId: fragment.paragraphId, characterOffset: fragment.end }, 'end').characterOffset })));
}

/** Reading notes and exports keep original offsets; only the layout consumes this projection. */
export function glossaryReaderProjection(document: ReadingDocument, entries: GlossaryDisplayEntry[]) {
  const displays = new Map(document.paragraphs.map(paragraph => [paragraph.paragraphId,
    applyGlossaryDisplay(paragraph.text, entries, document.kind === 'translation' ? 'translation' : 'original')]));
  const sourceAnchor = (anchor: ReadingAnchor, edge: 'start' | 'end' = 'start'): ReadingAnchor => ({ ...anchor,
    characterOffset: displays.get(anchor.paragraphId)?.sourceOffset(anchor.characterOffset, edge) ?? anchor.characterOffset });
  const displayAnchor = (anchor: ReadingAnchor, edge: 'start' | 'end' = 'start'): ReadingAnchor => ({ ...anchor,
    characterOffset: displays.get(anchor.paragraphId)?.displayOffset(anchor.characterOffset, edge) ?? anchor.characterOffset });
  return { document: { ...document, paragraphs: document.paragraphs.map(paragraph => ({ ...paragraph, text: displays.get(paragraph.paragraphId)!.text })) },
    displays, sourceAnchor, displayAnchor, displayRange: (range: ReadingRange): ReadingRange => ({ start: displayAnchor(range.start), end: displayAnchor(range.end, 'end') }) };
}

/** Split display text at both alias emphasis and existing user-highlight boundaries. */
export function glossaryReaderParts(text: string, start: number, emphasis: readonly { start: number; end: number }[],
  highlighted: readonly { text: string; highlighted: boolean }[] = [{ text, highlighted: false }]) {
  const highlights: { start: number; end: number }[] = [];
  let cursor = 0;
  for (const part of highlighted) { if (part.highlighted) highlights.push({ start: start + cursor, end: start + cursor + part.text.length }); cursor += part.text.length; }
  const spans = [...emphasis, ...highlights].map(span => ({ start: Math.max(start, span.start), end: Math.min(start + text.length, span.end) })).filter(span => span.end > span.start);
  const cuts = [...new Set([start, start + text.length, ...spans.flatMap(span => [span.start, span.end])])].sort((a, b) => a - b);
  return cuts.slice(0, -1).map((from, index) => ({ text: text.slice(from - start, cuts[index + 1] - start),
    emphasized: emphasis.some(span => span.start <= from && span.end > from), highlighted: highlights.some(span => span.start <= from && span.end > from) }));
}
