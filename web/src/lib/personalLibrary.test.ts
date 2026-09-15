import { webcrypto } from "node:crypto";
import { IDBFactory } from "fake-indexeddb";
import { beforeAll, describe, expect, it, vi } from "vitest";
import {
  createPersonalLibrary,
  exportGlossary,
  glossaryRevision,
  normalizeGlossary,
  parseGlossaryFile,
  validateOriginal,
} from "./personalLibrary";
const chapter = {
  recordId: "00000000-0000-0000-0000-000000000001",
  providerId: "source",
  bookId: "book",
  bookTitle: "Book",
  bookUrl: "https://example.com/book",
  chapterId: "c1",
  chapterTitle: "Chapter 1",
  chapterUrl: "https://example.com/book/1",
  sourceLanguage: "en",
  sourceRevision:
    "293dd3dd2fd29d0eb46d4498e49b95fc27ed77f1b1e2c51cdea1a6fc46c55589",
  paragraphs: [
    { paragraphId: "p1", ordinal: 0, text: "First paragraph." },
    { paragraphId: "p2", ordinal: 1, text: "Second paragraph." },
  ],
  createdAt: "2026-09-14T00:00:00Z",
};
beforeAll(() => vi.stubGlobal("crypto", webcrypto));
describe("personal original and glossary storage", () => {
  it("matches the source revision recipe and rejects altered paragraph content before writing", async () => {
    expect(await validateOriginal(chapter)).toEqual(chapter);
    await expect(
      validateOriginal({
        ...chapter,
        paragraphs: [
          { ...chapter.paragraphs[0], text: "Changed" },
          chapter.paragraphs[1],
        ],
      }),
    ).rejects.toThrow();
    await expect(
      validateOriginal({
        ...chapter,
        paragraphs: [...chapter.paragraphs].reverse(),
      }),
    ).rejects.toThrow();
  });
  it("keeps original bodies and glossary settings isolated by account and book", async () => {
    const options = { indexedDB: new IDBFactory() },
      a = createPersonalLibrary("reader-a", options),
      b = createPersonalLibrary("reader-b", options);
    await a.saveOriginal(chapter);
    await a.saveGlossary("source", "book", [
      { source: "Alice", target: "앨리스" },
    ]);
    expect((await a.originals()).books[0].chapter).toEqual(chapter);
    expect((await b.originals()).books).toEqual([]);
    expect((await b.getGlossary("source", "book")).entries).toEqual([]);
    expect((await a.getGlossary("source", "other-book")).entries).toEqual([]);
    await a.removeOriginal(chapter.recordId);
    expect((await a.originals()).books).toEqual([]);
  });
  it("uses the server runtime glossary fingerprint, independent of entry order", async () => {
    const entries = [
      { source: "Alice", target: "앨리스" },
      { source: "City", target: "도시" },
    ];
    expect(await glossaryRevision(entries)).toBe("e8fb496c68249c6e");
    expect(await glossaryRevision([...entries].reverse())).toBe(
      "e8fb496c68249c6e",
    );
    expect(await glossaryRevision([])).toBe("");
    expect(
      await glossaryRevision([{ source: "Alice", target: "다른 표기" }]),
    ).not.toBe(await glossaryRevision([entries[0]]));
  });
  it("limits glossary input and rejects ambiguous duplicate terms without changing saved entries", async () => {
    expect(() =>
      normalizeGlossary([
        { source: " Alice ", target: "앨리스" },
        { source: "ALICE", target: "다른 이름" },
      ]),
    ).toThrow();
    expect(() =>
      normalizeGlossary(
        Array.from({ length: 201 }, (_, i) => ({
          source: String(i),
          target: "word",
        })),
      ),
    ).toThrow();
    expect(() => parseGlossaryFile("{broken")).toThrow();
    const library = createPersonalLibrary("reader", {
      indexedDB: new IDBFactory(),
    });
    await library.saveGlossary("source", "book", [
      { source: "Name", target: "이름" },
    ]);
    await expect(
      library.saveGlossary("source", "book", [{ source: "", target: "bad" }]),
    ).rejects.toThrow();
    expect((await library.getGlossary("source", "book")).entries).toEqual([
      { source: "Name", target: "이름" },
    ]);
  });
  it("round trips an exported glossary without persisting unexpected fields", async () => {
    const library = createPersonalLibrary("reader", {
      indexedDB: new IDBFactory(),
    });
    const glossary = await library.saveGlossary("source", "book", [
      { source: " Name ", target: " 이름 ", apiKey: "must-not-save" },
    ]);
    expect(parseGlossaryFile(exportGlossary(glossary))).toEqual([
      { source: "Name", target: "이름" },
    ]);
    expect(exportGlossary(glossary)).not.toContain("apiKey");
  });
  it("isolates damaged original metadata without blocking other originals", async () => {
    const indexedDB = new IDBFactory(),
      library = createPersonalLibrary("reader", { indexedDB });
    await library.saveOriginal(chapter);
    await library.saveOriginal({
      ...chapter,
      recordId: "00000000-0000-0000-0000-000000000002",
    });
    const db = await new Promise<IDBDatabase>((resolve) => {
      const req = indexedDB.open("pageturner-personal-library", 1);
      req.onsuccess = () => resolve(req.result);
    });
    await new Promise<void>((resolve) => {
      const tx = db.transaction("items", "readwrite");
      const store = tx.objectStore("items"),
        req = store.get(["reader", "original", chapter.recordId]);
      req.onsuccess = () =>
        store.put({
          ...req.result,
          value: {
            ...req.result.value,
            chapter: { ...chapter, bookTitle: "Tampered" },
          },
        });
      tx.oncomplete = () => resolve();
    });
    db.close();
    const result = await library.originals();
    expect(result.books).toHaveLength(1);
    expect(result.damagedIds).toEqual([chapter.recordId]);
  });
  it("stores favorite titles and URLs under the correct account and rejects embedded credentials", async () => {
    const options = { indexedDB: new IDBFactory() },
      a = createPersonalLibrary("a", options),
      b = createPersonalLibrary("b", options);
    await a.saveFavorite({
      sourceId: "source",
      bookId: "book",
      title: "Book",
      url: chapter.bookUrl,
    });
    expect((await a.favorites()).books[0].title).toBe("Book");
    expect((await b.favorites()).books).toEqual([]);
    await expect(
      a.saveFavorite({
        sourceId: "source",
        bookId: "bad",
        title: "Bad",
        url: "https://user:password@example.com/book",
      }),
    ).rejects.toThrow();
  });
  it("keeps saved source URLs separate from books and removes only the selected account entry", async () => {
    const options = { indexedDB: new IDBFactory() },
      a = createPersonalLibrary("a", options),
      b = createPersonalLibrary("b", options);
    await a.saveSource({
      sourceId: "source",
      title: "My catalog",
      url: "https://example.com/catalog",
    });
    await b.saveSource({
      sourceId: "source",
      title: "Other catalog",
      url: "https://example.com/catalog",
    });
    expect((await a.favorites()).books).toHaveLength(0);
    expect((await a.sources()).books[0].title).toBe("My catalog");
    await a.removeSource("source", "https://example.com/catalog");
    expect((await a.sources()).books).toHaveLength(0);
    expect((await b.sources()).books).toHaveLength(1);
    a.close();
    await expect(
      a.saveSource({
        sourceId: "source",
        title: "Closed",
        url: "https://example.com/catalog",
      }),
    ).rejects.toThrow();
  });
});
