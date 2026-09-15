import {
  kotlinTrim,
  sha256,
  validRecordId,
  validTimestamp,
} from "./validation";
import { validateStoredChapter } from "./workflowApi";
import type { StoredChapter } from "./workflowTypes";

import { normalizeGlossary, glossaryRevision, type GlossaryEntry, type PersonalGlossary } from "./glossary";
export { normalizeGlossary, glossaryRevision, parseGlossaryFile, exportGlossary, mergeGlossaryEntries, type GlossaryEntry, type PersonalGlossary } from "./glossary";
export type FavoriteBook = {
  sourceId: string;
  bookId: string;
  title: string;
  url: string;
  savedAt: string;
};
export type SavedOriginal = { chapter: StoredChapter; savedAt: string };
type Kind = "original" | "glossary" | "favorite" | "source";
type Row = {
  username: string;
  kind: Kind;
  id: string;
  value: unknown;
  digest: string;
};
type Options = { indexedDB?: IDBFactory; dbName?: string };
const corrupt = () =>
  new Error("보관한 항목을 확인할 수 없습니다. 원본에서 다시 가져와 주세요.");
const bookKey = (providerId: string, bookId: string) =>
  `${kotlinTrim(providerId)}:${kotlinTrim(bookId)}`;

export async function validateOriginal(value: unknown): Promise<StoredChapter> {
  const chapter = validateStoredChapter(value);
  if (
    !validTimestamp(chapter.createdAt) ||
    chapter.paragraphs.length > 10_000 ||
    chapter.paragraphs.reduce((n, p) => n + p.text.length, 0) > 1_000_000
  )
    throw corrupt();
  const source = await Promise.all(
    chapter.paragraphs.map(
      async (paragraph) =>
        `${paragraph.paragraphId}:${paragraph.ordinal}:${await sha256(paragraph.text)}`,
    ),
  );
  if ((await sha256(source.join("\n"))) !== chapter.sourceRevision)
    throw corrupt();
  return chapter;
}
export function createPersonalLibrary(username: string, options: Options = {}) {
  if (!username.trim())
    throw new Error("기기 보관함에 사용할 계정 이름을 입력해 주세요.");
  const namespace = username;
  let closed = false;
  async function transaction<T>(
    mode: IDBTransactionMode,
    action: (store: IDBObjectStore, done: (value: T) => void) => void,
  ): Promise<T> {
    if (closed) throw new Error("보관함이 닫혔습니다.");
    const factory = options.indexedDB ?? globalThis.indexedDB;
    if (!factory)
      throw new Error("이 브라우저에서는 기기 저장소를 사용할 수 없습니다.");
    const db = await new Promise<IDBDatabase>((resolve, reject) => {
      const request = factory.open(
        options.dbName ?? "pageturner-personal-library",
        1,
      );
      let settled = false;
      request.onupgradeneeded = () => {
        const store = request.result.createObjectStore("items", {
          keyPath: ["username", "kind", "id"],
        });
        store.createIndex("scope", ["username", "kind"]);
      };
      request.onerror = () => {
        settled = true;
        reject(request.error);
      };
      request.onblocked = () => {
        settled = true;
        reject(
          new Error(
            "다른 창이 저장소를 사용 중입니다. 창을 닫고 다시 시도해 주세요.",
          ),
        );
      };
      request.onsuccess = () => {
        if (settled || closed) {
          request.result.close();
          reject(new Error("보관함이 닫혔습니다."));
          return;
        }
        request.result.onversionchange = () => request.result.close();
        resolve(request.result);
      };
    });
    return new Promise<T>((resolve, reject) => {
      let result: T;
      const tx = db.transaction("items", mode);
      tx.oncomplete = () => {
        db.close();
        resolve(result);
      };
      tx.onabort = () => {
        db.close();
        reject(tx.error ?? new Error("기기 저장을 완료하지 못했습니다."));
      };
      try {
        action(tx.objectStore("items"), (value) => {
          result = value;
        });
      } catch (error) {
        tx.abort();
        db.close();
        reject(error);
      }
    });
  }
  async function read(kind: Kind, id: string): Promise<Row | undefined> {
    return transaction("readonly", (store, done) => {
      const req = store.get([namespace, kind, id]);
      req.onsuccess = () => done(req.result);
    });
  }
  async function write(kind: Kind, id: string, value: unknown) {
    const digest = await sha256(JSON.stringify(value));
    await transaction<void>("readwrite", (store) => {
      store.put({ username: namespace, kind, id, value, digest } satisfies Row);
    });
  }
  async function verified(
    row: Row | undefined,
    kind: Kind,
    id: string,
  ): Promise<unknown> {
    if (!row) return undefined;
    if (
      row.username !== namespace ||
      row.kind !== kind ||
      row.id !== id ||
      (await sha256(JSON.stringify(row.value))) !== row.digest
    )
      throw corrupt();
    return row.value;
  }
  async function list(kind: Kind): Promise<{ id: string; row: Row }[]> {
    return transaction("readonly", (store, done) => {
      const rows: { id: string; row: Row }[] = [];
      const cursor = store.index("scope").openCursor([namespace, kind]);
      cursor.onsuccess = () => {
        const value = cursor.result;
        if (!value) {
          done(rows);
          return;
        }
        const key = value.primaryKey as IDBValidKey[];
        rows.push({ id: String(key[2]), row: value.value as Row });
        value.continue();
      };
    });
  }
  const remove = (kind: Kind, id: string) =>
    transaction<void>("readwrite", (store) => {
      store.delete([namespace, kind, id]);
    });
  return {
    close() {
      closed = true;
    },
    async saveOriginal(value: StoredChapter): Promise<SavedOriginal> {
      const chapter = await validateOriginal(value);
      const old = await read("original", chapter.recordId);
      if (old) {
        const saved = (await verified(
          old,
          "original",
          chapter.recordId,
        )) as SavedOriginal;
        if (saved.chapter.sourceRevision !== chapter.sourceRevision)
          throw new Error("같은 원문 식별자에 다른 내용이 저장되어 있습니다.");
      }
      const saved = { chapter, savedAt: new Date().toISOString() };
      await write("original", chapter.recordId, saved);
      return saved;
    },
    async originals(): Promise<{
      books: SavedOriginal[];
      damagedIds: string[];
    }> {
      const books: SavedOriginal[] = [],
        damagedIds: string[] = [];
      for (const item of await list("original")) {
        try {
          const value = (await verified(
            item.row,
            "original",
            item.id,
          )) as SavedOriginal;
          if (
            !validTimestamp(value.savedAt) ||
            value.chapter.recordId !== item.id
          )
            throw corrupt();
          books.push({
            chapter: await validateOriginal(value.chapter),
            savedAt: value.savedAt,
          });
        } catch {
          damagedIds.push(item.id);
        }
      }
      return {
        books: books.sort((a, b) => b.savedAt.localeCompare(a.savedAt)),
        damagedIds,
      };
    },
    removeOriginal: (id: string) => {
      if (!id) throw corrupt();
      return remove("original", id);
    },
    async getGlossary(
      providerId: string,
      bookId: string,
    ): Promise<PersonalGlossary> {
      const id = bookKey(providerId, bookId),
        raw = (await verified(await read("glossary", id), "glossary", id)) as
          | PersonalGlossary
          | undefined;
      if (!raw) return { entries: [], revision: "", updatedAt: "" };
      const entries = normalizeGlossary(raw.entries);
      if (
        !validTimestamp(raw.updatedAt) ||
        (await glossaryRevision(entries)) !== raw.revision
      )
        throw corrupt();
      return { entries, revision: raw.revision, updatedAt: raw.updatedAt };
    },
    async saveGlossary(
      providerId: string,
      bookId: string,
      value: unknown,
    ): Promise<PersonalGlossary> {
      const entries = normalizeGlossary(value),
        glossary = {
          entries,
          revision: await glossaryRevision(entries),
          updatedAt: new Date().toISOString(),
        };
      await write("glossary", bookKey(providerId, bookId), glossary);
      return glossary;
    },
    async saveFavorite(input: Omit<FavoriteBook, "savedAt">) {
      const url = new URL(input.url);
      if (
        !["http:", "https:"].includes(url.protocol) ||
        url.username ||
        url.password ||
        !input.title.trim() ||
        !input.sourceId ||
        !input.bookId
      )
        throw new Error("즐겨찾기의 책 정보와 주소를 확인해 주세요.");
      const favorite = {
        sourceId: input.sourceId,
        bookId: input.bookId,
        title: input.title,
        url: url.href,
        savedAt: new Date().toISOString(),
      };
      await write("favorite", bookKey(input.sourceId, input.bookId), favorite);
      return favorite;
    },
    async favorites(): Promise<{
      books: FavoriteBook[];
      damagedIds: string[];
    }> {
      const books: FavoriteBook[] = [],
        damagedIds: string[] = [];
      for (const item of await list("favorite")) {
        try {
          const value = (await verified(
            item.row,
            "favorite",
            item.id,
          )) as FavoriteBook;
          if (
            !value ||
            !validTimestamp(value.savedAt) ||
            bookKey(value.sourceId, value.bookId) !== item.id ||
            typeof value.title !== "string" ||
            typeof value.url !== "string"
          )
            throw corrupt();
          books.push(value);
        } catch {
          damagedIds.push(item.id);
        }
      }
      return {
        books: books.sort((a, b) => b.savedAt.localeCompare(a.savedAt)),
        damagedIds,
      };
    },
    removeFavorite: (sourceId: string, bookId: string) =>
      remove("favorite", bookKey(sourceId, bookId)),
    async saveSource(input: { sourceId: string; title: string; url: string }) {
      const url = new URL(input.url);
      if (
        !["http:", "https:"].includes(url.protocol) ||
        url.username ||
        url.password ||
        !input.title.trim() ||
        !input.sourceId
      )
        throw new Error("저장할 소스 이름과 주소를 확인해 주세요.");
      const source: FavoriteBook = {
        sourceId: input.sourceId,
        bookId: url.href,
        title: input.title.trim(),
        url: url.href,
        savedAt: new Date().toISOString(),
      };
      await write("source", bookKey(input.sourceId, url.href), source);
      return source;
    },
    async sources(): Promise<{ books: FavoriteBook[]; damagedIds: string[] }> {
      const books: FavoriteBook[] = [],
        damagedIds: string[] = [];
      for (const item of await list("source")) {
        try {
          const value = (await verified(
            item.row,
            "source",
            item.id,
          )) as FavoriteBook;
          if (
            !value ||
            !validTimestamp(value.savedAt) ||
            bookKey(value.sourceId, value.url) !== item.id
          )
            throw corrupt();
          books.push(value);
        } catch {
          damagedIds.push(item.id);
        }
      }
      return {
        books: books.sort((a, b) => b.savedAt.localeCompare(a.savedAt)),
        damagedIds,
      };
    },
    removeSource: (sourceId: string, url: string) =>
      remove("source", bookKey(sourceId, url)),
    removeDamaged: (kind: "favorite" | "source", id: string) =>
      remove(kind, id),
  };
}
export type PersonalLibrary = ReturnType<typeof createPersonalLibrary>;
