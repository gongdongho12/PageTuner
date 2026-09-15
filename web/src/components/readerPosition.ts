import type { ReadingAnchor } from "../lib/offline";

export type ReaderFragment = {
  paragraphId: string;
  text: string;
  start: number;
  end: number;
};

export type ReaderLocation = {
  page: number;
  anchor?: ReadingAnchor;
};

type Pages = readonly (readonly ReaderFragment[])[];

function firstAnchor(pages: Pages, page: number): ReadingAnchor | undefined {
  const first = pages[page]?.[0];
  return first
    ? { paragraphId: first.paragraphId, characterOffset: first.start }
    : undefined;
}

/** Page boundaries may move; the logical position inside the text must not. */
export function reflowReaderLocation(
  pages: Pages,
  anchor?: ReadingAnchor,
): ReaderLocation {
  if (!pages.length) return { page: 0, anchor };
  if (anchor) {
    const page = pages.findIndex((fragments) =>
      fragments.some(
        (fragment) =>
          fragment.paragraphId === anchor.paragraphId &&
          anchor.characterOffset >= fragment.start &&
          anchor.characterOffset < fragment.end,
      ),
    );
    if (page >= 0) return { page, anchor };
  }
  return { page: 0, anchor: firstAnchor(pages, 0) };
}

/** Only an actual, explicit page turn selects a new logical reading position. */
export function turnReaderPage(
  pages: Pages,
  location: ReaderLocation,
  direction: -1 | 1,
): ReaderLocation {
  if (!pages.length) return location;
  const page = Math.max(
    0,
    Math.min(pages.length - 1, location.page + direction),
  );
  if (page === location.page) return location;
  return { page, anchor: firstAnchor(pages, page) };
}
