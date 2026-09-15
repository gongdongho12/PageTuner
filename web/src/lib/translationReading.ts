import type { TranslationResponse } from "./api";
import {
  createOfflineLibrary,
  type OfflineLibrary,
  type ReadingAnchor,
} from "./offline";
import { createReadingNotes } from "./readingNotes";
import { validAnchor, type ReadingDocument } from "./readingDocument";
import type { DeviceDatabaseOptions } from "./deviceReadingDatabase";

type Options = {
  offline?: OfflineLibrary;
  device?: DeviceDatabaseOptions;
  storage?: Pick<Storage, "getItem" | "setItem">;
};
const legacyDocumentId = (translation: TranslationResponse) =>
  `translation:${translation.recordId}:${translation.revision}`;
const positionKey = (username: string, translation: TranslationResponse) =>
  `pageturner.position:${encodeURIComponent(username)}:${translation.recordId}:${translation.revision}`;
const pendingPositions = new Map<string, Promise<void>>();

/** A stored record is immutable. All entry points use its ID for reading notes. */
export function translationReadingDocument(
  translation: TranslationResponse,
): ReadingDocument {
  return {
    id: translation.recordId,
    kind: "translation",
    bookTitle: translation.bookTitle || translation.bookId,
    chapterTitle: translation.chapterTitle || translation.chapterId,
    language: translation.targetLanguage,
    paragraphs: translation.paragraphs,
    glossaryIdentity: { providerId: translation.contentProviderId, bookId: translation.bookId },
  };
}

function readPosition(
  username: string,
  translation: TranslationResponse,
  options: Options,
  legacy: boolean,
): ReadingAnchor | undefined {
  const document = translationReadingDocument(translation);
  const keys = [
    positionKey(username, translation),
    ...(legacy
      ? [
          `pageturner.workflow-position:${encodeURIComponent(username)}:${encodeURIComponent(legacyDocumentId(translation))}`,
        ]
      : []),
  ];
  for (const key of keys) {
    try {
      const value = JSON.parse(
        (options.storage ?? localStorage).getItem(key) ?? "null",
      );
      if (value && validAnchor(document, value))
        return {
          paragraphId: value.paragraphId,
          characterOffset: value.characterOffset,
        };
    } catch {
      /* A corrupt legacy key must not hide another valid saved position. */
    }
  }
}
export function getTranslationPosition(
  username: string,
  translation: TranslationResponse,
  options: Options = {},
) {
  return readPosition(username, translation, options, true);
}

/** Recover old workflow notes before opening either reader; failed storage never prevents reading. */
export async function openTranslationReading(
  username: string,
  translation: TranslationResponse,
  options: Options = {},
) {
  const document = translationReadingDocument(translation);
  const notes = createReadingNotes(username, options.device);
  await pendingPositions
    .get(positionKey(username, translation))
    ?.catch(() => undefined);
  let anchor = readPosition(username, translation, options, false);
  try {
    await notes.migrateDocument(
      { ...document, id: legacyDocumentId(translation) },
      document,
    );
    anchor ??= await notes.getPosition(document);
  } catch {
    /* Original notes remain intact if migration/storage is unavailable. */
  }
  anchor ??= getTranslationPosition(username, translation, options);
  const offline = options.offline ?? createOfflineLibrary(username);
  try {
    anchor ??= (await offline.get(translation.recordId))?.anchor;
  } catch {
    /* The network copy remains readable when device storage is unavailable. */
  } finally {
    if (!options.offline) offline.close();
  }
  if (anchor && validAnchor(document, anchor))
    return { document, translation, anchor };
  return { document, translation, anchor: undefined };
}

/** Write through to every available device store so offline reopening restores the same anchor. */
export async function rememberTranslationPosition(
  username: string,
  translation: TranslationResponse,
  anchor: ReadingAnchor,
  options: Options = {},
) {
  const document = translationReadingDocument(translation);
  if (!validAnchor(document, anchor))
    throw new Error("이 문서에 없는 읽기 위치입니다.");
  let persisted = false;
  try {
    (options.storage ?? localStorage).setItem(
      positionKey(username, translation),
      JSON.stringify(anchor),
    );
    persisted = true;
  } catch {
    /* IndexedDB can still retain progress when localStorage is blocked. */
  }
  const key = positionKey(username, translation);
  const pending = (pendingPositions.get(key) ?? Promise.resolve())
    .catch(() => undefined)
    .then(async () => {
      const notes = createReadingNotes(username, options.device);
      try {
        await notes.setPosition(document, anchor);
        persisted = true;
      } catch {
        /* Try the saved translation too. */
      }
      const offline = options.offline ?? createOfflineLibrary(username);
      try {
        const stored = await offline.get(translation.recordId);
        if (stored?.translation.revision === translation.revision) {
          await offline.setAnchor(translation.recordId, anchor);
          persisted = true;
        }
      } catch {
        /* A failed store must not roll back the other successful stores. */
      } finally {
        if (!options.offline) offline.close();
      }
      if (!persisted)
        throw new Error("읽은 위치를 보관함에 저장하지 못했습니다.");
    });
  pendingPositions.set(key, pending);
  try {
    await pending;
  } finally {
    if (pendingPositions.get(key) === pending) pendingPositions.delete(key);
  }
}
