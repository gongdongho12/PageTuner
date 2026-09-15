import { webcrypto } from "node:crypto";
import { IDBFactory } from "fake-indexeddb";
import { beforeEach, describe, expect, it, vi } from "vitest";
import fixture from "../../../contracts/fixtures/translation-v1/stored-response.json";
import type { TranslationResponse } from "./api";
import { createOfflineLibrary } from "./offline";
import { createReadingNotes } from "./readingNotes";
import {
  getTranslationPosition,
  openTranslationReading,
  rememberTranslationPosition,
  translationReadingDocument,
} from "./translationReading";

beforeEach(() => {
  vi.stubGlobal("crypto", webcrypto);
});
const translation = fixture as TranslationResponse;
const anchor = {
  paragraphId: translation.paragraphs[1].paragraphId,
  characterOffset: 2,
};
function memoryStorage() {
  const values = new Map<string, string>();
  return {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => {
      values.set(key, value);
    },
  };
}

describe("one translation identity across job results, server library and offline reopening", () => {
  it("shares the actual note and offline stores after reading a job result and saving it", async () => {
    const indexedDB = new IDBFactory(),
      offline = createOfflineLibrary("alice", { indexedDB }),
      device = { indexedDB },
      storage = memoryStorage();
    const options = { offline, device, storage },
      notes = createReadingNotes("alice", device);
    try {
      const jobReader = await openTranslationReading(
        "alice",
        translation,
        options,
      );
      await notes.add(jobReader.document, {
        kind: "bookmark",
        title: "My place",
        anchor,
      });
      await rememberTranslationPosition("alice", translation, anchor, options);
      await offline.save(translation);
      const libraryReader = await openTranslationReading(
        "alice",
        translation,
        options,
      );
      expect(libraryReader.document.id).toBe(jobReader.document.id);
      expect(libraryReader.anchor).toEqual(anchor);
      expect((await notes.list(libraryReader.document)).items[0].title).toBe(
        "My place",
      );
      await rememberTranslationPosition("alice", translation, anchor, options);
      expect((await offline.get(translation.recordId))?.anchor).toEqual(anchor);
      const reloaded = await openTranslationReading("alice", translation, {
        ...options,
        storage: memoryStorage(),
      });
      expect(reloaded.anchor).toEqual(anchor);
      expect(
        (await createReadingNotes("bob", device).list(jobReader.document))
          .items,
      ).toEqual([]);
    } finally {
      offline.close();
    }
  });

  it("migrates workflow notes once, preserves legacy progress and does not resurrect deleted notes", async () => {
    const indexedDB = new IDBFactory(),
      device = { indexedDB },
      storage = memoryStorage(),
      offline = createOfflineLibrary("alice", { indexedDB });
    const legacyId = `translation:${translation.recordId}:${translation.revision}`;
    const legacy = { ...translationReadingDocument(translation), id: legacyId },
      notes = createReadingNotes("alice", device);
    storage.setItem(
      `pageturner.workflow-position:alice:${encodeURIComponent(legacyId)}`,
      JSON.stringify(anchor),
    );
    const note = await notes.add(legacy, {
      kind: "note",
      title: "Old note",
      text: "Keep this",
      anchor,
    });
    try {
      const opened = await openTranslationReading("alice", translation, {
        offline,
        device,
        storage,
      });
      expect(opened.anchor).toEqual(anchor);
      expect((await notes.list(opened.document)).items[0]).toMatchObject({
        id: note.id,
        text: "Keep this",
        documentId: translation.recordId,
      });
      expect((await notes.list(legacy)).items).toEqual([]);
      await notes.remove(opened.document.id, note.id);
      await openTranslationReading("alice", translation, {
        offline,
        device,
        storage,
      });
      expect((await notes.list(opened.document)).items).toEqual([]);
      expect(
        getTranslationPosition("bob", translation, { storage }),
      ).toBeUndefined();
      const nextAnchor = { ...anchor, characterOffset: 3 };
      await rememberTranslationPosition("alice", translation, nextAnchor, {
        offline,
        device,
        storage,
      });
      expect(getTranslationPosition("alice", translation, { storage })).toEqual(
        nextAnchor,
      );
    } finally {
      offline.close();
    }
  });

  it("uses a saved offline anchor when both progress stores are empty and rejects foreign offsets", async () => {
    const indexedDB = new IDBFactory(),
      offline = createOfflineLibrary("alice", { indexedDB }),
      options = { offline, device: { indexedDB }, storage: memoryStorage() };
    try {
      await offline.save(translation);
      await offline.setAnchor(translation.recordId, anchor);
      expect(
        (await openTranslationReading("alice", translation, options)).anchor,
      ).toEqual(anchor);
      await expect(
        rememberTranslationPosition(
          "alice",
          translation,
          { ...anchor, characterOffset: 999999 },
          options,
        ),
      ).rejects.toThrow();
      expect((await offline.get(translation.recordId))?.anchor).toEqual(anchor);
    } finally {
      offline.close();
    }
  });
  it("keeps newer IndexedDB progress ahead of legacy keys when localStorage writes are blocked", async () => {
    const indexedDB = new IDBFactory(),
      offline = createOfflineLibrary("alice", { indexedDB }),
      storage = memoryStorage();
    const legacyId = `translation:${translation.recordId}:${translation.revision}`;
    storage.setItem(
      `pageturner.workflow-position:alice:${encodeURIComponent(legacyId)}`,
      JSON.stringify(anchor),
    );
    const blocked = {
      getItem: storage.getItem,
      setItem: () => {
        throw new Error("Storage denied");
      },
    };
    const options = { offline, device: { indexedDB }, storage: blocked };
    try {
      await offline.save(translation);
      const newer = { ...anchor, characterOffset: 3 },
        latest = { ...anchor, characterOffset: 4 };
      await Promise.all([
        rememberTranslationPosition("alice", translation, newer, options),
        rememberTranslationPosition("alice", translation, latest, options),
      ]);
      expect(
        (await openTranslationReading("alice", translation, options)).anchor,
      ).toEqual(latest);
      expect((await offline.get(translation.recordId))?.anchor).toEqual(latest);
    } finally {
      offline.close();
    }
  });
});
