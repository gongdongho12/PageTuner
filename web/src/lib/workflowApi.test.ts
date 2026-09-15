import { describe, expect, it, vi } from "vitest";
import {
  createWorkflowClient,
  validateJob,
  validateStoredChapter,
  validateChapterSummary,
  type StartTranslation,
} from "./workflowApi";

const id = "00000000-0000-0000-0000-000000000001";
const otherId = "00000000-0000-0000-0000-000000000002";
const chapter = {
  recordId: id,
  providerId: "novelbuddy",
  bookId: "https://novelbuddy.me/story",
  bookTitle: "읽을 이야기",
  bookUrl: "https://novelbuddy.me/story",
  chapterId: "chapter-1",
  chapterTitle: "첫 번째 이야기",
  chapterUrl: "https://novelbuddy.me/story/chapter-1",
  sourceLanguage: "en",
  sourceRevision: "a".repeat(64),
  paragraphs: [
    { paragraphId: "p1", ordinal: 0, text: "First paragraph." },
    { paragraphId: "p2", ordinal: 1, text: "Second paragraph." },
  ],
  createdAt: "2026-09-14T00:00:00Z",
};
const job = {
  jobId: otherId,
  status: "QUEUED",
  chapterRecordId: id,
  bookTitle: chapter.bookTitle,
  chapterTitle: chapter.chapterTitle,
  providerKind: "GOOGLE_WEB_TRANSLATE_HTML",
  targetLanguage: "ko",
  settings: {
    sourceLanguage: "en",
    targetLanguage: "ko",
    endpoint: "",
    model: "",
    glossary: [],
  },
  completedParagraphs: 0,
  totalParagraphs: 2,
  translationRecordId: null,
  errorCode: null,
  errorMessage: null,
  createdAt: chapter.createdAt,
  updatedAt: chapter.createdAt,
  canRetry: false,
};
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
const credentials = { username: "reader", password: "memory-password" };
const input: StartTranslation = {
  chapterRecordId: id,
  providerKind: "DEEPSEEK",
  targetLanguage: "ko",
  apiKey: "memory-provider-key",
  idempotencyKey: otherId,
};

describe("web novel workflow transport", () => {
  it("reads advertised filter values and passes the selected values unchanged on every catalog page", async () => {
    const filters = {
      genres: [
        { value: "", label: "All" },
        { value: "magic", label: "Magic" },
      ],
      sort: [{ value: "views", label: "Views" }],
      status: [{ value: "completed", label: "Completed" }],
      directions: [{ value: "asc", label: "Ascending" }],
    };
    const source = {
      id: "wtr-lab",
      displayName: "WTR",
      defaultCatalogUrl: "https://wtr-lab.com/en/novel-list",
      remoteSearch: true,
      requiresUrl: false,
      filters,
    };
    const catalog = {
      sourceId: source.id,
      url: source.defaultCatalogUrl,
      currentPage: 2,
      totalPages: null,
      totalItems: null,
      hasPreviousPage: true,
      hasNextPage: false,
      items: [],
    };
    const transport = vi.fn(async (url: RequestInfo | URL) =>
      json(
        String(url).endsWith("novel-sources") ? { items: [source] } : catalog,
      ),
    );
    const client = createWorkflowClient(credentials, { fetch: transport });
    expect((await client.sources())[0].filters).toEqual(filters);
    await client.catalog(
      source.id,
      source.defaultCatalogUrl,
      "some story",
      2,
      undefined,
      { genre: "magic", orderBy: "views", order: "asc", status: "completed" },
    );
    const query = new URL(
      String(transport.mock.calls[1][0]),
      "https://local.example",
    ).searchParams;
    expect(Object.fromEntries(query)).toMatchObject({
      genre: "magic",
      orderBy: "views",
      order: "asc",
      status: "completed",
      page: "2",
      query: "some story",
    });
  });
  it("uploads projected local text with a session CSRF token and accepts empty original URLs", async () => {
    const uploaded = {
      ...chapter,
      providerId: "uploaded-document",
      bookUrl: "",
      chapterUrl: "",
    };
    const transport = vi.fn(
      async (url: RequestInfo | URL, _options?: RequestInit) =>
        json(
          String(url).endsWith("/csrf")
            ? { token: "csrf-value", headerName: "X-CSRF-TOKEN" }
            : uploaded,
        ),
    );
    const client = createWorkflowClient(credentials, { fetch: transport });
    const upload = {
      bookId: "local:hash",
      bookTitle: "Book",
      chapterId: "document",
      chapterTitle: "Document",
      sourceLanguage: "auto",
      paragraphs: chapter.paragraphs,
    };
    expect(await client.uploadChapter(upload)).toEqual(uploaded);
    expect(transport.mock.calls[1][0]).toBe("/api/v1/chapters/upload");
    expect(transport.mock.calls[1][1]).toMatchObject({
      credentials: "same-origin",
      headers: { "X-CSRF-TOKEN": "csrf-value" },
      body: JSON.stringify(upload),
    });
  });
  it("lists source summaries without paragraph bodies and retrieves the selected body separately", async () => {
    const { paragraphs, ...metadata } = chapter;
    const summary = { ...metadata, paragraphCount: paragraphs.length };
    const listing = {
      items: [summary],
      page: 0,
      size: 12,
      totalItems: 1,
      totalPages: 1,
      hasNext: false,
    };
    const transport = vi.fn(async (url: RequestInfo | URL) =>
      json(String(url).includes("?") ? listing : chapter),
    );
    const client = createWorkflowClient(credentials, { fetch: transport });
    expect(await client.chapters()).toEqual(listing);
    expect(validateChapterSummary(summary)).not.toHaveProperty("paragraphs");
    expect(await client.chapter(id)).toEqual(chapter);
    expect(transport.mock.calls.map((call) => call[0])).toEqual([
      "/api/v1/chapters?page=0&size=12",
      `/api/v1/chapters/${id}`,
    ]);
  });
  it("obtains a session CSRF token before writes and keeps secrets out of URLs", async () => {
    const transport = vi.fn(
      async (url: RequestInfo | URL, _options?: RequestInit) =>
        json(
          String(url).endsWith("/csrf")
            ? { token: "csrf-value", headerName: "X-CSRF-TOKEN" }
            : job,
        ),
    );
    const client = createWorkflowClient(credentials, { fetch: transport });
    expect(await client.start(input)).toEqual(job);
    expect(transport.mock.calls.map((call) => call[0])).toEqual([
      "/api/v1/csrf",
      "/api/v1/translation-jobs",
    ]);
    for (const [url, options] of transport.mock.calls) {
      expect(String(url)).toMatch(/^\/api\/v1\//);
      expect(String(url)).not.toContain("memory-");
      expect(options).toMatchObject({
        credentials: "same-origin",
        cache: "no-store",
        redirect: "error",
        mode: "same-origin",
      });
      expect(options!.headers).toMatchObject({
        Authorization: `Basic ${btoa("reader:memory-password")}`,
      });
    }
    const write = transport.mock.calls[1][1]!;
    expect(write.method).toBe("POST");
    expect(write.headers).toMatchObject({
      "X-CSRF-TOKEN": "csrf-value",
      "Content-Type": "application/json",
    });
    expect(JSON.parse(write.body as string)).toEqual(input);
  });

  it("does not write after CSRF failure or accept an arbitrary authentication header", async () => {
    for (const response of [
      json({}, 403),
      json({ token: "injected", headerName: "Authorization" }),
    ]) {
      const transport = vi.fn(async () => response);
      const client = createWorkflowClient(credentials, { fetch: transport });
      await expect(client.start(input)).rejects.toBeInstanceOf(Error);
      expect(transport).toHaveBeenCalledTimes(1);
    }
  });

  it("does not replay a failed mutation and preserves the supplied idempotency identity", async () => {
    const transport = vi.fn(async (url: RequestInfo | URL) =>
      json(
        String(url).endsWith("/csrf")
          ? { token: "csrf-value", headerName: "X-CSRF-TOKEN" }
          : { detail: "private-provider-key" },
        String(url).endsWith("/csrf") ? 200 : 503,
      ),
    );
    const client = createWorkflowClient(credentials, { fetch: transport });
    const failure = await client.start(input).catch((error) => error);
    expect(failure).toMatchObject({ kind: "server", status: 503 });
    expect(failure.message).not.toContain("private-provider-key");
    expect(transport).toHaveBeenCalledTimes(2);
  });

  it("only sends a source URL to the same-origin import endpoint", async () => {
    const transport = vi.fn(
      async (url: RequestInfo | URL, _options?: RequestInit) =>
        json(
          String(url).endsWith("/csrf")
            ? { token: "csrf-value", headerName: "X-CSRF-TOKEN" }
            : chapter,
        ),
    );
    const client = createWorkflowClient(credentials, { fetch: transport });
    expect(
      await client.importChapter(chapter.chapterUrl, chapter.bookUrl),
    ).toEqual(chapter);
    expect(transport.mock.calls[1][0]).toBe("/api/v1/chapters/import");
    expect(JSON.parse(transport.mock.calls[1][1]!.body as string)).toEqual({
      url: chapter.chapterUrl,
      bookUrl: chapter.bookUrl,
    });
    expect(() =>
      client.importChapter("https://user:password@example.com/chapter"),
    ).toThrow();
    expect(() => client.importChapter("file:///private")).toThrow();
    expect(transport).toHaveBeenCalledTimes(2);
  });

  it("closing an account aborts pending reads and prevents the write after CSRF returns", async () => {
    let deliver: ((response: Response) => void) | undefined;
    const transport = vi.fn(
      (_url: RequestInfo | URL, _options?: RequestInit) =>
        new Promise<Response>((resolve) => {
          deliver = resolve;
        }),
    );
    const client = createWorkflowClient(credentials, { fetch: transport });
    const pending = client.start(input);
    client.close();
    deliver!(json({ token: "csrf-value", headerName: "X-CSRF-TOKEN" }));
    await expect(pending).rejects.toMatchObject({ kind: "aborted" });
    expect(transport.mock.calls[0][1]!.signal!.aborted).toBe(true);
    expect(transport).toHaveBeenCalledTimes(1);
    await expect(client.sources()).rejects.toMatchObject({ kind: "aborted" });
  });

  it("validates identities before requests and verifies returned chapter/job identities", async () => {
    const transport = vi.fn(async (url: RequestInfo | URL) =>
      json(
        String(url).includes("/chapters/")
          ? { ...chapter, recordId: otherId }
          : { ...job, jobId: id },
      ),
    );
    const client = createWorkflowClient(credentials, { fetch: transport });
    expect(() => client.job("../../outside")).toThrow();
    expect(() => client.chapters(-1)).toThrow();
    expect(transport).not.toHaveBeenCalled();
    await expect(client.chapter(id)).rejects.toMatchObject({
      kind: "invalid-response",
    });
    await expect(client.job(otherId)).rejects.toMatchObject({
      kind: "invalid-response",
    });
  });
});

describe("workflow reading and progress contracts", () => {
  it("preserves normalized retry settings while excluding any unexpected credential field", () => {
    const settings = {
      sourceLanguage: "zh",
      targetLanguage: "ko",
      endpoint: "https://api.example.com/v1/chat/completions",
      model: "chosen-model",
      glossary: [{ source: "Name", target: "이름" }],
    };
    const parsed = validateJob({
      ...job,
      settings: { ...settings, apiKey: "must-not-be-retained" },
    });
    expect(parsed.settings).toEqual(settings);
    expect(parsed.settings).not.toHaveProperty("apiKey");
    expect(() =>
      validateJob({ ...job, settings: { ...settings, targetLanguage: "en" } }),
    ).toThrow();
  });
  it("preserves extended glossary options in retries and rejects malformed matching options", () => {
    const glossary = [{ source: 'City', target: '도시', kind: 'Place' as const, displayTerm: '마을', caseSensitive: true, enabled: false }];
    expect(validateJob({ ...job, settings: { ...job.settings, glossary } }).settings.glossary).toEqual(glossary);
    expect(validateJob({ ...job, settings: { ...job.settings, glossary: [{ source: 'Name', target: '이름',
      kind: 'Character', displayTerm: '', enabled: true, caseSensitive: false }] } }).settings.glossary).toEqual([{ source: 'Name', target: '이름' }]);
    for (const entry of [{ ...glossary[0], kind: 'Other' }, { ...glossary[0], enabled: 'false' },
      { ...glossary[0], caseSensitive: 1 }, { ...glossary[0], displayTerm: 'a'.repeat(201) }]) {
      expect(() => validateJob({ ...job, settings: { ...job.settings, glossary: [entry] } })).toThrow();
    }
  });
  it("rejects damaged original paragraph identity and ordering", () => {
    expect(validateStoredChapter(chapter)).toEqual(chapter);
    for (const paragraphs of [
      [],
      [chapter.paragraphs[0], chapter.paragraphs[0]],
      [...chapter.paragraphs].reverse(),
      [{ ...chapter.paragraphs[0], text: "" }],
    ]) {
      expect(() => validateStoredChapter({ ...chapter, paragraphs })).toThrow();
    }
  });
  it("rejects original bounds and dates before allowing a reader or storage write", () => {
    for (const overrides of [
      { createdAt: "yesterday" },
      { sourceRevision: "unverified" },
      {
        paragraphs: [
          { paragraphId: "p".repeat(201), ordinal: 0, text: "text" },
        ],
      },
      { paragraphs: [{ paragraphId: "p", ordinal: 1, text: "text" }] },
      {
        paragraphs: [
          { paragraphId: "p", ordinal: 0, text: "x".repeat(1_000_001) },
        ],
      },
    ])
      expect(() =>
        validateStoredChapter({ ...chapter, ...overrides }),
      ).toThrow();
    const { paragraphs, ...summary } = chapter;
    expect(() =>
      validateChapterSummary({ ...summary, paragraphCount: 10001 }),
    ).toThrow();
  });
  it("rejects inconsistent server pagination instead of exposing stale navigation", async () => {
    const client = createWorkflowClient(credentials, {
      fetch: async () =>
        json({
          items: [],
          page: 0,
          size: 0,
          totalItems: 0,
          totalPages: 0,
          hasNext: false,
        }),
    });
    await expect(client.chapters()).rejects.toMatchObject({
      kind: "invalid-response",
    });
  });
  it("rejects impossible progress and completed jobs without a readable result", () => {
    expect(validateJob(job)).toEqual(job);
    expect(() => validateJob({ ...job, completedParagraphs: 3 })).toThrow();
    expect(() => validateJob({ ...job, status: "COMPLETED" })).toThrow();
    expect(() => validateJob({ ...job, status: "UNKNOWN" })).toThrow();
    expect(() => validateJob({ ...job, totalParagraphs: 0 })).toThrow();
    expect(() => validateJob({ ...job, canRetry: true })).toThrow();
    expect(() =>
      validateJob({
        ...job,
        status: "COMPLETED",
        translationRecordId: id,
        completedParagraphs: 1,
      }),
    ).toThrow();
    expect(
      validateJob({
        ...job,
        status: "COMPLETED",
        completedParagraphs: 2,
        translationRecordId: id,
      }).translationRecordId,
    ).toBe(id);
  });
});
