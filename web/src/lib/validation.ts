import { ApiError } from "./errors";
import type {
  ReadingAnchor,
  TranslationPage,
  TranslationResponse,
  TranslationSummary,
} from "./types";

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const hash = /^[0-9a-f]{64}$/;
const metadataKeys = [
  "recordId",
  "contentProviderId",
  "bookId",
  "chapterId",
  "sourceRevision",
  "sourceLanguage",
  "targetLanguage",
  "translationProviderId",
  "modelId",
  "promptRevision",
  "glossaryRevision",
  "artifactId",
  "revision",
  "payloadHash",
  "createdAt",
] as const;
const titleKeys = ["bookTitle", "chapterTitle"] as const;
type Metadata = Pick<
  TranslationResponse,
  (typeof metadataKeys)[number] | (typeof titleKeys)[number]
>;

// Kotlin/JVM Char.isWhitespace combines Character.isWhitespace and isSpaceChar. In particular,
// Java trims U+001C..001F but does not trim U+FEFF; JavaScript String.trim() differs on both.
const kotlinWhitespace =
  "[\\u0009-\\u000d\\u001c-\\u0020\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000]";
const edgeWhitespace = new RegExp(
  `^${kotlinWhitespace}+|${kotlinWhitespace}+$`,
  "gu",
);
export const kotlinTrim = (value: string): string =>
  value.replace(edgeWhitespace, "");

function invalid(): never {
  throw new ApiError("invalid-response", "서버 응답 형식이 올바르지 않습니다.");
}

export function objectValue(value: unknown): Record<string, unknown> {
  if (value === null || typeof value !== "object" || Array.isArray(value))
    invalid();
  return value as Record<string, unknown>;
}

export function exactKeys(
  value: Record<string, unknown>,
  keys: readonly string[],
  optional: readonly string[] = [],
): void {
  if (
    keys.some((key) => !Object.prototype.hasOwnProperty.call(value, key)) ||
    Object.keys(value).some(
      (key) => !keys.includes(key) && !optional.includes(key),
    )
  )
    invalid();
}

function stringValue(value: unknown, allowBlank = false): string {
  if (
    typeof value !== "string" ||
    (!allowBlank && kotlinTrim(value).length === 0)
  )
    invalid();
  return value;
}

export function validRecordId(value: unknown): value is string {
  return typeof value === "string" && uuid.test(value);
}

export function validTimestamp(value: unknown): value is string {
  return (
    typeof value === "string" &&
    /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/.test(
      value,
    ) &&
    Number.isFinite(Date.parse(value))
  );
}

function metadata(value: Record<string, unknown>): Metadata {
  const result = Object.fromEntries(
    metadataKeys.map((key) => [
      key,
      stringValue(
        value[key],
        ["modelId", "promptRevision", "glossaryRevision"].includes(key),
      ),
    ]),
  ) as Metadata;
  if (
    !validRecordId(result.recordId) ||
    !validTimestamp(result.createdAt) ||
    !hash.test(result.artifactId) ||
    !hash.test(result.revision) ||
    !hash.test(result.payloadHash)
  )
    invalid();
  for (const key of titleKeys) {
    if (Object.prototype.hasOwnProperty.call(value, key))
      result[key] = stringValue(value[key], true);
  }
  return result;
}

export async function sha256(value: string): Promise<string> {
  if (!globalThis.crypto?.subtle)
    throw new ApiError(
      "unsupported",
      "번역 검증을 위해 HTTPS 또는 localhost에서 열어 주세요.",
    );
  const digest = await globalThis.crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(value),
  );
  return Array.from(new Uint8Array(digest), (byte) =>
    byte.toString(16).padStart(2, "0"),
  ).join("");
}

async function verifyMetadata(value: Metadata): Promise<void> {
  const chapter = [value.contentProviderId, value.bookId, value.chapterId]
    .map(kotlinTrim)
    .join(":");
  const artifactId = await sha256(
    [
      chapter,
      value.sourceRevision,
      value.sourceLanguage,
      value.targetLanguage,
      value.translationProviderId,
      value.modelId,
      value.promptRevision,
      value.glossaryRevision,
    ].join("|"),
  );
  const revision = await sha256(`${artifactId}|${value.payloadHash}`);
  if (artifactId !== value.artifactId || revision !== value.revision) {
    throw new ApiError(
      "integrity",
      "번역 식별자 검증에 실패했습니다. 저장하거나 읽을 수 없습니다.",
    );
  }
}

/** Returns a fresh projection; unknown fields (including accidental credentials) never reach storage. */
export function parseTranslationShape(value: unknown): TranslationResponse {
  const object = objectValue(value);
  exactKeys(object, [...metadataKeys, "paragraphs", "created"], titleKeys);
  const fields = metadata(object);
  if (
    typeof object.created !== "boolean" ||
    !Array.isArray(object.paragraphs) ||
    object.paragraphs.length === 0
  )
    invalid();
  const paragraphs = object.paragraphs.map((raw) => {
    const paragraph = objectValue(raw);
    exactKeys(paragraph, ["paragraphId", "text"]);
    return {
      paragraphId: stringValue(paragraph.paragraphId),
      text: stringValue(paragraph.text),
    };
  });
  if (
    new Set(paragraphs.map((item) => item.paragraphId)).size !==
    paragraphs.length
  )
    invalid();
  return { ...fields, created: object.created, paragraphs };
}

export async function validateTranslation(
  value: unknown,
): Promise<TranslationResponse> {
  const parsed = parseTranslationShape(value);
  await verifyMetadata(parsed);
  const payloadHash = await sha256(
    parsed.paragraphs
      .map((paragraph) => `${paragraph.paragraphId}:${paragraph.text}`)
      .join("\n"),
  );
  if (payloadHash !== parsed.payloadHash)
    throw new ApiError(
      "integrity",
      "번역 본문 검증에 실패했습니다. 저장하거나 읽을 수 없습니다.",
    );
  return parsed;
}

export async function validateSummary(
  value: unknown,
): Promise<TranslationSummary> {
  const object = objectValue(value);
  exactKeys(object, [...metadataKeys, "paragraphCount"], titleKeys);
  const fields = metadata(object);
  if (
    !Number.isSafeInteger(object.paragraphCount) ||
    (object.paragraphCount as number) < 1
  )
    invalid();
  await verifyMetadata(fields);
  return { ...fields, paragraphCount: object.paragraphCount as number };
}

export async function validatePage(value: unknown): Promise<TranslationPage> {
  const object = objectValue(value);
  exactKeys(object, [
    "items",
    "page",
    "size",
    "totalItems",
    "totalPages",
    "hasNext",
  ]);
  for (const field of ["page", "size", "totalItems", "totalPages"]) {
    if (!Number.isSafeInteger(object[field]) || (object[field] as number) < 0)
      invalid();
  }
  const { page, size, totalItems, totalPages } = object as {
    page: number;
    size: number;
    totalItems: number;
    totalPages: number;
  };
  if (
    size < 1 ||
    size > 100 ||
    !Array.isArray(object.items) ||
    typeof object.hasNext !== "boolean" ||
    totalPages !== Math.ceil(totalItems / size) ||
    object.hasNext !== page + 1 < totalPages ||
    object.items.length !==
      Math.max(0, Math.min(size, totalItems - page * size))
  )
    invalid();
  const items = await Promise.all(object.items.map(validateSummary));
  if (new Set(items.map((item) => item.recordId)).size !== items.length)
    invalid();
  return { items, page, size, totalItems, totalPages, hasNext: object.hasNext };
}

export function validAnchor(
  value: unknown,
  translation: TranslationResponse,
): value is ReadingAnchor {
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  const anchor = value as Record<string, unknown>;
  if (
    Object.keys(anchor).length !== 2 ||
    typeof anchor.paragraphId !== "string" ||
    !Number.isSafeInteger(anchor.characterOffset)
  )
    return false;
  const paragraph = translation.paragraphs.find(
    (item) => item.paragraphId === anchor.paragraphId,
  );
  return (
    !!paragraph &&
    (anchor.characterOffset as number) >= 0 &&
    (anchor.characterOffset as number) <= paragraph.text.length
  );
}
