import { ApiError } from "./errors";
import { normalizeGlossary } from './glossary';
import { validRecordId, validTimestamp } from "./validation";
import { validateCatalogTranslation, type CatalogTranslationRequest } from './catalogTranslation';
import type {
  NovelSource,
  NovelCatalog,
  NovelBook,
  NovelChapter,
  NovelDetail,
  StoredChapter,
  ChapterSummary,
  TranslationProvider,
  TranslationJob,
  StartTranslation,
  Page,
  CatalogFilters,
  UploadChapter,
} from "./workflowTypes";
export type * from "./workflowTypes";

const invalid = () =>
  new ApiError(
    "invalid-response",
    "서버 응답을 확인할 수 없습니다. 다시 불러와 주세요.",
  );
const object = (v: unknown): Record<string, unknown> => {
  if (!v || typeof v !== "object" || Array.isArray(v)) throw invalid();
  return v as Record<string, unknown>;
};
const string = (v: unknown): string => {
  if (typeof v !== "string") throw invalid();
  return v;
};
const nullableString = (v: unknown) => (v === null ? null : string(v));
const integer = (v: unknown): number => {
  if (typeof v !== "number" || !Number.isSafeInteger(v) || v < 0)
    throw invalid();
  return v;
};
const nullableInteger = (v: unknown) => (v === null ? null : integer(v));
const boolean = (v: unknown): boolean => {
  if (typeof v !== "boolean") throw invalid();
  return v;
};
const array = <T>(v: unknown, parse: (v: unknown) => T): T[] => {
  if (!Array.isArray(v)) throw invalid();
  return v.map(parse);
};
const uuid = (v: unknown): string => {
  const s = string(v);
  if (!validRecordId(s)) throw invalid();
  return s;
};
const kinds = [
  "GOOGLE_CLOUD",
  "GOOGLE_WEB_TRANSLATE_HTML",
  "DEEPSEEK",
  "OPENAI_COMPATIBLE_LLM",
] as const;
function providerKind(value: unknown) {
  if (!kinds.includes(value as (typeof kinds)[number])) throw invalid();
  return value as (typeof kinds)[number];
}
const fields = (o: Record<string, unknown>, keys: string[]) =>
  Object.fromEntries(keys.map((k) => [k, string(o[k])]));
function source(value: unknown): NovelSource {
  const o = object(value);
  const filters = object(o.filters);
  const options = (value: unknown) =>
    array(value, (value) => {
      const option = object(value);
      return { value: string(option.value), label: string(option.label) };
    });
  return {
    id: string(o.id),
    displayName: string(o.displayName),
    defaultCatalogUrl: nullableString(o.defaultCatalogUrl),
    remoteSearch: boolean(o.remoteSearch),
    requiresUrl: boolean(o.requiresUrl),
    filters: {
      genres: options(filters.genres),
      sort: options(filters.sort),
      status: options(filters.status),
      directions: options(filters.directions),
    },
  };
}
function book(value: unknown): NovelBook {
  const o = object(value);
  return {
    bookId: string(o.bookId),
    title: string(o.title),
    url: string(o.url),
    authors: array(o.authors, string),
    sourceLanguage: string(o.sourceLanguage),
    coverUrl: nullableString(o.coverUrl),
    description: nullableString(o.description),
    chapterCount: nullableInteger(o.chapterCount),
    tags: array(o.tags, string),
  };
}
function novelChapter(value: unknown): NovelChapter {
  const o = object(value);
  return {
    chapterId: string(o.chapterId),
    number: integer(o.number),
    title: string(o.title),
    url: string(o.url),
    sourceLanguage: string(o.sourceLanguage),
  };
}
function catalog(value: unknown): NovelCatalog {
  const o = object(value);
  return {
    sourceId: string(o.sourceId),
    url: string(o.url),
    currentPage: integer(o.currentPage),
    totalPages: nullableInteger(o.totalPages),
    totalItems: nullableInteger(o.totalItems),
    hasPreviousPage: boolean(o.hasPreviousPage),
    hasNextPage: boolean(o.hasNextPage),
    items: array(o.items, book),
  };
}
export { catalog as validateNovelCatalog, source as validateNovelSource };
function pagination(o: Record<string, unknown>, maxSize = 50) {
  const result = {
    page: integer(o.page),
    size: integer(o.size),
    totalItems: integer(o.totalItems),
    totalPages: integer(o.totalPages),
    hasNext: boolean(o.hasNext),
  };
  if (
    result.size < 1 ||
    result.size > maxSize ||
    result.page * result.size > 2_147_483_647 ||
    result.totalPages !== Math.ceil(result.totalItems / result.size) ||
    result.hasNext !== result.page + 1 < result.totalPages
  )
    throw invalid();
  return result;
}
function detail(value: unknown): NovelDetail {
  const o = object(value);
  return {
    ...fields(o, [
      "sourceId",
      "bookId",
      "title",
      "url",
      "author",
      "sourceLanguage",
      "status",
      "summary",
    ]),
    totalChapters: integer(o.totalChapters),
    tags: array(o.tags, string),
    coverUrl: nullableString(o.coverUrl),
    chapters: array(o.chapters, novelChapter),
    ...pagination(o, 100),
  } as NovelDetail;
}
export function validateStoredChapter(value: unknown): StoredChapter {
  const o = object(value);
  const paragraphs = array(o.paragraphs, (value) => {
    const p = object(value);
    return {
      paragraphId: string(p.paragraphId),
      ordinal: integer(p.ordinal),
      text: string(p.text),
    };
  });
  if (
    !paragraphs.length ||
    paragraphs.length > 10_000 ||
    paragraphs.reduce((n, p) => n + p.text.length, 0) > 1_000_000 ||
    new Set(paragraphs.map((p) => p.paragraphId)).size !== paragraphs.length ||
    paragraphs.some(
      (p, i) =>
        !p.paragraphId ||
        p.paragraphId.length > 200 ||
        !p.text.trim() ||
        p.ordinal !== i,
    )
  )
    throw invalid();
  validateOriginalMetadata(o);
  return {
    ...fields(o, [
      "providerId",
      "bookId",
      "bookTitle",
      "bookUrl",
      "chapterId",
      "chapterTitle",
      "chapterUrl",
      "sourceLanguage",
      "sourceRevision",
      "createdAt",
    ]),
    recordId: uuid(o.recordId),
    paragraphs,
  } as StoredChapter;
}
export function validateChapterSummary(value: unknown): ChapterSummary {
  const o = object(value);
  const paragraphCount = integer(o.paragraphCount);
  if (paragraphCount < 1 || paragraphCount > 10_000) throw invalid();
  validateOriginalMetadata(o);
  return {
    ...fields(o, [
      "providerId",
      "bookId",
      "bookTitle",
      "bookUrl",
      "chapterId",
      "chapterTitle",
      "chapterUrl",
      "sourceLanguage",
      "sourceRevision",
      "createdAt",
    ]),
    recordId: uuid(o.recordId),
    paragraphCount,
  } as ChapterSummary;
}
function validateOriginalMetadata(o: Record<string, unknown>) {
  if (
    !/^[a-f0-9]{64}$/.test(string(o.sourceRevision)) ||
    !validTimestamp(string(o.createdAt)) ||
    !string(o.providerId) ||
    !string(o.bookId) ||
    !string(o.chapterId) ||
    !string(o.sourceLanguage) ||
    string(o.sourceLanguage).length > 32
  )
    throw invalid();
}
function provider(value: unknown): TranslationProvider {
  const o = object(value);
  return {
    id: providerKind(o.id),
    displayName: string(o.displayName),
    configured: boolean(o.configured),
    requiresKey: boolean(o.requiresKey),
    defaultEndpoint: string(o.defaultEndpoint),
    defaultModel: string(o.defaultModel),
  };
}
export function validateJob(value: unknown): TranslationJob {
  const o = object(value);
  const settings = object(o.settings);
  const safeSettings = {
    sourceLanguage: string(settings.sourceLanguage),
    targetLanguage: string(settings.targetLanguage),
    endpoint: string(settings.endpoint),
    model: string(settings.model),
    glossary: (() => { try { return normalizeGlossary(settings.glossary); } catch { throw invalid(); } })(),
  };
  if (safeSettings.targetLanguage !== o.targetLanguage) throw invalid();
  const language = /^[A-Za-z][A-Za-z0-9-]{0,23}$/;
  if (
    !language.test(safeSettings.sourceLanguage) ||
    !language.test(safeSettings.targetLanguage) ||
    safeSettings.glossary.length > 200 ||
    safeSettings.glossary.some(
      (item) =>
        !item.source.trim() ||
        !item.target.trim() ||
        item.source.length > 200 ||
        item.target.length > 200,
    ) ||
    !validTimestamp(string(o.createdAt)) ||
    !validTimestamp(string(o.updatedAt))
  )
    throw invalid();
  const status = string(o.status);
  if (
    ![
      "QUEUED",
      "RUNNING",
      "COMPLETED",
      "FAILED",
      "CANCELLED",
      "INTERRUPTED",
    ].includes(status)
  )
    throw invalid();
  const completedParagraphs = integer(o.completedParagraphs),
    totalParagraphs = integer(o.totalParagraphs);
  if (
    totalParagraphs < 1 ||
    totalParagraphs > 10_000 ||
    completedParagraphs > totalParagraphs
  )
    throw invalid();
  const translationRecordId =
    o.translationRecordId === null ? null : uuid(o.translationRecordId);
  if (
    status === "COMPLETED" &&
    (!translationRecordId || completedParagraphs !== totalParagraphs)
  )
    throw invalid();
  if (
    boolean(o.canRetry) !==
    ["FAILED", "CANCELLED", "INTERRUPTED"].includes(status)
  )
    throw invalid();
  return {
    ...fields(o, [
      "bookTitle",
      "chapterTitle",
      "targetLanguage",
      "createdAt",
      "updatedAt",
    ]),
    jobId: uuid(o.jobId),
    chapterRecordId: uuid(o.chapterRecordId),
    status,
    providerKind: providerKind(o.providerKind),
    settings: safeSettings,
    completedParagraphs,
    totalParagraphs,
    translationRecordId,
    errorCode: nullableString(o.errorCode),
    errorMessage: nullableString(o.errorMessage),
    canRetry: boolean(o.canRetry),
  } as TranslationJob;
}
function page<T>(value: unknown, parse: (v: unknown) => T): Page<T> {
  const o = object(value);
  const result = { items: array(o.items, parse), ...pagination(o) };
  if (
    result.items.length > result.size ||
    result.items.length >
      Math.max(0, result.totalItems - result.page * result.size)
  )
    throw invalid();
  return result;
}
const requestId = (value: string) => {
  if (!validRecordId(value))
    throw new ApiError("invalid-request", "읽을 항목을 다시 선택해 주세요.");
  return encodeURIComponent(value);
};
const pageNumber = (value: number) => {
  if (!Number.isSafeInteger(value) || value < 0)
    throw new ApiError("invalid-request", "페이지를 다시 선택해 주세요.");
  return value;
};
function sourceUrl(value: string): string {
  try {
    const url = new URL(value);
    if (
      !["https:", "http:"].includes(url.protocol) ||
      url.username ||
      url.password
    )
      throw new Error();
    return url.href;
  } catch {
    throw new ApiError(
      "invalid-request",
      "웹소설의 http 또는 https 주소를 입력해 주세요.",
    );
  }
}
function httpError(status: number) {
  if (status === 401)
    return new ApiError(
      "authentication",
      "계정 연결이 필요합니다. 다시 연결해 주세요.",
      status,
    );
  if (status === 403)
    return new ApiError(
      "forbidden",
      "요청 권한을 확인할 수 없습니다. 다시 연결해 주세요.",
      status,
    );
  if (status === 404)
    return new ApiError(
      "not-found",
      "항목을 찾을 수 없습니다. 목록을 다시 불러와 주세요.",
      status,
    );
  if (status === 409)
    return new ApiError(
      "conflict",
      "작업 상태가 변경되었습니다. 진행 상황을 다시 확인해 주세요.",
      status,
    );
  if (status >= 400 && status < 500)
    return new ApiError(
      "invalid-request",
      "주소와 번역 설정을 확인해 주세요.",
      status,
    );
  return new ApiError(
    "server",
    "불러오지 못했습니다. 잠시 후 다시 시도해 주세요.",
    status,
  );
}

/** Only fixed same-origin API paths are fetched. Passwords and provider keys never enter persistent storage. */
export function createWorkflowClient(
  credentials: { username: string; password: string },
  options: { fetch?: typeof fetch; timeoutMs?: number } = {},
) {
  if (
    !credentials.username.trim() ||
    /[:\r\n]/.test(credentials.username) ||
    /[\r\n]/.test(credentials.password)
  )
    throw new ApiError(
      "invalid-request",
      "계정 이름과 비밀번호 형식을 확인해 주세요.",
    );
  let authorization = `Basic ${btoa(Array.from(new TextEncoder().encode(`${credentials.username}:${credentials.password}`), (byte) => String.fromCharCode(byte)).join(""))}`;
  const transport = options.fetch ?? globalThis.fetch.bind(globalThis);
  const controllers = new Set<AbortController>();
  let closed = false;
  async function request<T>(
    path: string,
    parse: (value: unknown) => T,
    signal?: AbortSignal,
    body?: unknown,
    csrf?: { token: string; headerName: string },
  ): Promise<T> {
    if (closed || signal?.aborted)
      throw new ApiError("aborted", "요청이 취소되었습니다.");
    const controller = new AbortController();
    controllers.add(controller);
    let timeout = false;
    const abort = () => controller.abort();
    signal?.addEventListener("abort", abort, { once: true });
    const timer = setTimeout(() => {
      timeout = true;
      controller.abort();
    }, options.timeoutMs ?? 90_000);
    try {
      const headers: Record<string, string> = {
        Authorization: authorization,
        Accept: "application/json",
        "X-Requested-With": "XMLHttpRequest",
      };
      if (body !== undefined) {
        headers["Content-Type"] = "application/json";
        if (!csrf) throw invalid();
        headers[csrf.headerName] = csrf.token;
      }
      const response = await transport(path, {
        method: body === undefined ? "GET" : "POST",
        credentials: "same-origin",
        redirect: "error",
        mode: "same-origin",
        cache: "no-store",
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
        signal: controller.signal,
      });
      if (!response.ok) throw httpError(response.status);
      if (
        response.redirected ||
        !response.headers
          .get("content-type")
          ?.toLowerCase()
          .startsWith("application/json") ||
        Number(response.headers.get("content-length")) > 8 * 1024 * 1024
      )
        throw invalid();
      const reader = response.body?.getReader();
      if (!reader) throw invalid();
      let text = "",
        bytes = 0;
      const decoder = new TextDecoder("utf-8", { fatal: true });
      try {
        while (true) {
          const part = await reader.read();
          if (part.done) break;
          bytes += part.value.length;
          if (bytes > 8 * 1024 * 1024) throw invalid();
          text += decoder.decode(part.value, { stream: true });
        }
        text += decoder.decode();
      } finally {
        await reader.cancel().catch(() => undefined);
        reader.releaseLock();
      }
      if (controller.signal.aborted || closed)
        throw new DOMException("Aborted", "AbortError");
      let value: unknown;
      try {
        value = JSON.parse(text);
      } catch {
        throw invalid();
      }
      return parse(value);
    } catch (error) {
      if (controller.signal.aborted || closed)
        throw new ApiError(
          timeout ? "timeout" : "aborted",
          timeout
            ? "응답이 늦어지고 있습니다. 다시 확인해 주세요."
            : "요청이 취소되었습니다.",
        );
      if (error instanceof ApiError) throw error;
      throw new ApiError(
        "network",
        "서버에 연결할 수 없습니다. 연결 상태를 확인해 주세요.",
      );
    } finally {
      clearTimeout(timer);
      signal?.removeEventListener("abort", abort);
      controllers.delete(controller);
    }
  }
  async function write<T>(
    path: string,
    body: unknown,
    parse: (value: unknown) => T,
    signal?: AbortSignal,
  ) {
    const csrf = await request(
      "/api/v1/csrf",
      (value) => {
        const o = object(value);
        const headerName = string(o.headerName),
          token = string(o.token);
        if (
          !["x-csrf-token", "x-xsrf-token"].includes(
            headerName.toLowerCase(),
          ) ||
          !token ||
          /[\r\n]/.test(token)
        )
          throw invalid();
        return { headerName, token };
      },
      signal,
    );
    return request(path, parse, signal, body, csrf);
  }
  return {
    close() {
      closed = true;
      authorization = "";
      controllers.forEach((c) => c.abort());
      controllers.clear();
    },
    startCatalogTranslation: (input: CatalogTranslationRequest, signal?: AbortSignal) =>
      write('/api/v1/catalog-translations', input, validateCatalogTranslation, signal),
    getCatalogTranslation: (id: string, signal?: AbortSignal) =>
      request(`/api/v1/catalog-translations/${requestId(id)}`, validateCatalogTranslation, signal),
    cancelCatalogTranslation: (id: string, signal?: AbortSignal) =>
      write(`/api/v1/catalog-translations/${requestId(id)}/cancel`, {}, validateCatalogTranslation, signal),
    sources: (signal?: AbortSignal) =>
      request(
        "/api/v1/novel-sources",
        (v) => array(object(v).items, source),
        signal,
      ),
    catalog(
      sourceId: string,
      url: string | undefined,
      query = "",
      remotePage = 1,
      signal?: AbortSignal,
      filters: CatalogFilters = {},
    ) {
      const params = new URLSearchParams({
        sourceId,
        query,
        page: String(pageNumber(remotePage)),
      });
      if (url) params.set("url", sourceUrl(url));
      for (const key of ["genre", "orderBy", "order", "status"] as const)
        if (filters[key] !== undefined) params.set(key, filters[key]!);
      return request(`/api/v1/novels/catalog?${params}`, catalog, signal);
    },
    detail(url: string, n = 0, signal?: AbortSignal) {
      return request(
        `/api/v1/novels/detail?${new URLSearchParams({ url: sourceUrl(url), page: String(pageNumber(n)), size: "20" })}`,
        detail,
        signal,
      );
    },
    chapters: (n = 0, signal?: AbortSignal) =>
      request(
        `/api/v1/chapters?page=${pageNumber(n)}&size=12`,
        (v) => page(v, validateChapterSummary),
        signal,
      ),
    chapter: (id: string, signal?: AbortSignal) =>
      request(
        `/api/v1/chapters/${requestId(id)}`,
        (v) => {
          const result = validateStoredChapter(v);
          if (result.recordId !== id) throw invalid();
          return result;
        },
        signal,
      ),
    importChapter: (url: string, bookUrl?: string, signal?: AbortSignal) =>
      write(
        "/api/v1/chapters/import",
        {
          url: sourceUrl(url),
          ...(bookUrl ? { bookUrl: sourceUrl(bookUrl) } : {}),
        },
        validateStoredChapter,
        signal,
      ),
    uploadChapter: (input: UploadChapter, signal?: AbortSignal) =>
      write("/api/v1/chapters/upload", input, validateStoredChapter, signal),
    providers: (signal?: AbortSignal) =>
      request(
        "/api/v1/translation-providers",
        (v) => array(object(v).providers, provider),
        signal,
      ),
    jobs: (n = 0, signal?: AbortSignal) =>
      request(
        `/api/v1/translation-jobs?page=${pageNumber(n)}&size=12`,
        (v) => page(v, validateJob),
        signal,
      ),
    job: (id: string, signal?: AbortSignal) =>
      request(
        `/api/v1/translation-jobs/${requestId(id)}`,
        (v) => {
          const result = validateJob(v);
          if (result.jobId !== id) throw invalid();
          return result;
        },
        signal,
      ),
    start(input: StartTranslation, signal?: AbortSignal) {
      requestId(input.chapterRecordId);
      requestId(input.idempotencyKey);
      if (input.retryOf) requestId(input.retryOf);
      if (!kinds.includes(input.providerKind) || !input.targetLanguage.trim())
        throw new ApiError(
          "invalid-request",
          "번역기와 대상 언어를 선택해 주세요.",
        );
      return write("/api/v1/translation-jobs", input, validateJob, signal);
    },
    cancel: (id: string, signal?: AbortSignal) =>
      write(
        `/api/v1/translation-jobs/${requestId(id)}/cancel`,
        {},
        validateJob,
        signal,
      ),
  };
}
export type WorkflowClient = ReturnType<typeof createWorkflowClient>;
