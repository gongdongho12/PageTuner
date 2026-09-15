import { describe, expect, it } from "vitest";
import {
  reflowReaderLocation,
  turnReaderPage,
  type ReaderFragment,
} from "./readerPosition";

function fragment(
  paragraphId: string,
  start: number,
  end: number,
): ReaderFragment {
  return { paragraphId, start, end, text: "x".repeat(end - start) };
}

describe("reader logical position", () => {
  it("retains p9 across six larger-font reflows and the reverse resizes", () => {
    const original = { paragraphId: "preview-p-9", characterOffset: 0 };
    let location = { page: 6, anchor: original };
    // Each layout starts the containing page earlier. Reusing that page's first
    // fragment as the anchor would successively drift to p8, p7 and p6.
    const layouts = Array.from({ length: 6 }, (_, index) => [
      [fragment(`preview-p-${6 + Math.floor(index / 2)}`, 0, 157)],
      [
        fragment(`preview-p-${6 + Math.floor(index / 2)}`, 157, 220),
        fragment("preview-p-9", 0, 30 + index),
      ],
      [fragment("preview-p-9", 30 + index, 100)],
    ]);
    for (const pages of [...layouts, ...[...layouts].reverse()]) {
      const relocated = reflowReaderLocation(pages, location.anchor);
      expect(relocated.page).toBe(1);
      expect(relocated.anchor).toBe(original);
      location = { page: relocated.page, anchor: relocated.anchor! };
    }
  });

  it("preserves a UTF-16 offset when font and viewport boundaries change", () => {
    const anchor = { paragraphId: "p1", characterOffset: 157 };
    const large = [
      [fragment("p1", 0, 130)],
      [fragment("p1", 130, 180)],
      [fragment("p1", 180, 240)],
    ];
    const small = [[fragment("p1", 0, 200)], [fragment("p1", 200, 240)]];
    let location = reflowReaderLocation(large, anchor);
    expect(location).toEqual({ page: 1, anchor });
    location = reflowReaderLocation(small, location.anchor);
    expect(location).toEqual({ page: 0, anchor });
    expect(reflowReaderLocation(large, location.anchor)).toEqual({
      page: 1,
      anchor,
    });
  });

  it("changes the logical anchor only for actual previous or next navigation", () => {
    const pages = [
      [fragment("p1", 0, 100)],
      [fragment("p1", 100, 200)],
      [fragment("p2", 0, 50)],
    ];
    const anchor = { paragraphId: "p1", characterOffset: 157 };
    const initial = reflowReaderLocation(pages, anchor);
    expect(turnReaderPage(pages, initial, 1)).toEqual({
      page: 2,
      anchor: { paragraphId: "p2", characterOffset: 0 },
    });
    expect(turnReaderPage(pages, initial, -1)).toEqual({
      page: 0,
      anchor: { paragraphId: "p1", characterOffset: 0 },
    });
    const first = reflowReaderLocation(pages, {
      paragraphId: "p1",
      characterOffset: 70,
    });
    expect(turnReaderPage(pages, first, -1)).toBe(first);
    const last = reflowReaderLocation(pages, {
      paragraphId: "p2",
      characterOffset: 20,
    });
    expect(turnReaderPage(pages, last, 1)).toBe(last);
  });

  it("keeps a saved anchor while no page fits and restores its exact boundary later", () => {
    const anchor = { paragraphId: "p1", characterOffset: 100 };
    const pages = [[fragment("p1", 0, 100)], [fragment("p1", 100, 200)]];
    const empty = reflowReaderLocation([], anchor);
    expect(empty.anchor).toBe(anchor);
    expect(turnReaderPage([], empty, 1)).toBe(empty);
    expect(reflowReaderLocation(pages, empty.anchor)).toEqual({
      page: 1,
      anchor,
    });
    expect(reflowReaderLocation(pages)).toEqual({
      page: 0,
      anchor: { paragraphId: "p1", characterOffset: 0 },
    });
  });
});
