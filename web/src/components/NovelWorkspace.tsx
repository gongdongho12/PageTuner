import {
  getWorkflowPosition as position,
  setWorkflowPosition,
} from "../lib/workflowPosition";
import { translate as t } from "../lib/locale";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { usePersonalLibrary } from "./usePersonalLibrary";
import {
  ApiError,
  type TranslationResponse,
  type createTranslationClient,
} from "../lib/api";
import type {
  WorkflowClient,
  NovelSource,
  NovelCatalog,
  NovelDetail,
  NovelBook,
  StoredChapter,
  ChapterSummary,
  TranslationProvider,
  TranslationJob,
  Page,
  StartTranslation,
  CatalogFilters,
} from "../lib/workflowApi";
import type { ReadingAnchor } from "../lib/offline";
import { AdaptiveCollection } from "./AdaptiveCollection";
import { Icon } from "./Icon";
import type { ReadingDocument } from "./PagedReader";
import { TranslationComparisonReader } from './TranslationComparisonReader';
import { TranslationSetup } from "./TranslationSetup";
import { PersonalShelf } from "./PersonalShelf";
import { CatalogFilterPanel } from "./CatalogFilterPanel";
import { CatalogTranslationPanel } from './CatalogTranslationPanel';
import { BulkNovelWorkspace } from "./BulkNovelWorkspace";
import { localUploadInput } from "../lib/localUpload";
import { openTranslationReading, rememberTranslationPosition } from '../lib/translationReading';
import { createCatalogCache, createCachedCatalogLoader, type CatalogResult } from '../lib/catalogCache';
import { CachedCatalogBrowser } from './CachedCatalogBrowser';
type View =
  | "sources"
  | "favorites"
  | "bulk"
  | "filters"
  | "catalog"
  | "detail"
  | "chapters"
  | "jobs"
  | "job"
  | "settings";
type Reader = {
  document: ReadingDocument;
  chapter?: StoredChapter;
  translation?: TranslationResponse;
  sourceRecordId?: string;
  anchor?: ReadingAnchor;
};
const statusLabels: Record<TranslationJob["status"], string> = {
  QUEUED: "순서를 기다리는 중",
  RUNNING: "번역하는 중",
  COMPLETED: "번역 완료",
  FAILED: "번역 실패",
  CANCELLED: "번역 취소됨",
  INTERRUPTED: "번역 중단됨",
};
const activeJob = (job: TranslationJob) =>
  job.status === "QUEUED" || job.status === "RUNNING";
const message = (error: unknown) =>
  error instanceof Error
    ? t(error.message)
    : t("작업을 완료하지 못했습니다. 다시 시도해 주세요.");
function Feedback({
  title,
  children,
  retry,
}: {
  title: string;
  children?: string;
  retry?: () => void;
}) {
  return (
    <div className="workflow-feedback">
      <Icon name="book" size={28} />
      <h2>{title}</h2>
      {children && <p>{children}</p>}
      {retry && (
        <button className="button-outline" onClick={retry}>
          {t("다시 시도")}
          <Icon name="refresh" />
        </button>
      )}
    </div>
  );
}
export function NovelWorkspace({
  client,
  translationClient,
  username,
  onConnect,
  onPreview,
  onOpenJsonCatalog,
  onReadingChange,
  onSaveTranslation,
  isSaved,
  saving,
  defaultTargetLanguage,
  initialDocument,
  onDocumentImported,
}: {
  client: WorkflowClient | null;
  translationClient: ReturnType<typeof createTranslationClient> | null;
  username: string;
  onConnect: () => void;
  onPreview: () => void;
  onOpenJsonCatalog?: () => void;
  onReadingChange: (reading: boolean) => void;
  onSaveTranslation: (translation: TranslationResponse) => void;
  isSaved: (id: string, revision: string) => boolean;
  saving: string | null;
  defaultTargetLanguage: string;
  initialDocument?: ReadingDocument;
  onDocumentImported: () => void;
}) {
  const [view, setView] = useState<View>("sources");
  const catalogCache = useMemo(() => username.trim() ? createCatalogCache(username) : undefined, [username]);
  const catalogLoader = useMemo(() => catalogCache ? createCachedCatalogLoader(catalogCache, client) : undefined, [catalogCache, client]);
  const [cachedLibraryOpen, setCachedLibraryOpen] = useState(false);
  const [catalogInfo, setCatalogInfo] = useState<CatalogResult>();
  useEffect(() => () => catalogLoader?.cancel(), [catalogLoader]);
  const personal = usePersonalLibrary(username);
  const [savedOriginalIds, setSavedOriginalIds] = useState<string[]>([]);
  useEffect(() => {
    let active = true;
    void personal
      ?.originals()
      .then((result) => {
        if (active)
          setSavedOriginalIds(
            result.books.map((book) => book.chapter.recordId),
          );
      })
      .catch(() => {});
    return () => {
      active = false;
    };
  }, [personal]);
  const [sources, setSources] = useState<NovelSource[]>([]);
  const [selectedSource, setSelectedSource] = useState<NovelSource | null>(
    null,
  );
  const [catalog, setCatalog] = useState<NovelCatalog | null>(null);
  const [detail, setDetail] = useState<NovelDetail | null>(null);
  const detailDisplayBooks = useMemo<NovelBook[]>(() => detail ? [{ bookId: detail.bookId, title: detail.title, url: detail.url,
    authors: [detail.author], sourceLanguage: detail.sourceLanguage, coverUrl: detail.coverUrl, description: detail.summary,
    chapterCount: detail.totalChapters, tags: detail.tags }] : [], [detail]);
  const [bulkDetail, setBulkDetail] = useState<NovelDetail | null>(null);
  const [detailTab, setDetailTab] = useState<"chapters" | "about">("chapters");
  const [query, setQuery] = useState("");
  const [submittedQuery, setSubmittedQuery] = useState("");
  const [filters, setFilters] = useState<CatalogFilters>({});
  const [personalNotice, setPersonalNotice] = useState("");
  const [url, setUrl] = useState("");
  const [directUrl, setDirectUrl] = useState("");
  const [chapters, setChapters] = useState<Page<ChapterSummary> | null>(null);
  const [jobs, setJobs] = useState<Page<TranslationJob> | null>(null);
  const [job, setJob] = useState<TranslationJob | null>(null);
  const [chapter, setChapter] = useState<StoredChapter | null>(null);
  const [providers, setProviders] = useState<TranslationProvider[]>([]);
  const [retryJob, setRetryJob] = useState<TranslationJob | undefined>();
  const [reader, setReader] = useState<Reader | null>(null);
  const [busy, setBusy] = useState("");
  const [error, setError] = useState("");
  const [pollError, setPollError] = useState("");
  const [pollEpoch, setPollEpoch] = useState(0);
  const [batchStart, setBatchStart] = useState<"first" | "last">("first");
  const request = useRef<AbortController | null>(null);
  const generation = useRef(0);
  const retryAction = useRef<(() => void) | undefined>(undefined);
  const originalView = useRef<View>("detail");
  const run = useCallback(
    async <T,>(
      label: string,
      work: (signal: AbortSignal) => Promise<T>,
      accept: (value: T) => void,
    ): Promise<boolean> => {
      request.current?.abort();
      const controller = new AbortController();
      request.current = controller;
      const version = ++generation.current;
      setBusy(label);
      setError("");
      retryAction.current = () => {
        void run(label, work, accept);
      };
      try {
        const value = await work(controller.signal);
        if (version !== generation.current || controller.signal.aborted)
          return false;
        accept(value);
        retryAction.current = undefined;
        return true;
      } catch (failure) {
        if (version === generation.current && !controller.signal.aborted)
          setError(message(failure));
        return false;
      } finally {
        if (version === generation.current) setBusy("");
      }
    },
    [],
  );
  useEffect(() => {
    if (client && !initialDocument)
      void run(
        t("소스를 불러오고 있습니다"),
        (signal) => client.sources(signal),
        setSources,
      );
    return () => {
      generation.current++;
      request.current?.abort();
    };
  }, [client, run]);
  useEffect(() => {
    onReadingChange(!!reader);
    return () => onReadingChange(false);
  }, [!!reader, onReadingChange]);
  const stopReadRequest = () => {
    generation.current++;
    request.current?.abort();
    catalogLoader?.cancel();
    setBusy("");
    setError("");
    setPollError("");
    setPersonalNotice("");
    setCatalogInfo(undefined);
    retryAction.current = undefined;
  };
  const navigate = (next: View) => {
    stopReadRequest();
    setReader(null);
    setView(next);
  };
  const loadCatalog = (
    source: NovelSource,
    n = 1,
    search = submittedQuery,
    address = url,
    start: "first" | "last" = "first",
    filterValues: CatalogFilters = filters,
    force = false,
  ) => {
    if (!client || !catalogLoader) return;
    navigate("catalog");
    setCatalog(null);
    setSelectedSource(source);
    setBatchStart(start);
    setSubmittedQuery(search);
    setFilters(filterValues);
    void run(
      t("이야기를 찾고 있습니다"),
      (signal) => catalogLoader.load({ source, url: address || source.defaultCatalogUrl || undefined, query: search, page: n, filters: filterValues }, signal, force),
      (result) => { setCatalog(result.catalog); setCatalogInfo(result); catalogLoader.prefetchNext(result); },
    );
  };
  const loadDetail = (
    address: string,
    n = 0,
    start: "first" | "last" = "first",
  ) => {
    if (!client) return;
    navigate("detail");
    setDetail(null);
    setDetailTab("chapters");
    setBatchStart(start);
    void run(
      t("목차를 불러오고 있습니다"),
      (signal) => client.detail(address, n, signal),
      setDetail,
    );
  };
  const loadChapters = (n = 0, start: "first" | "last" = "first") => {
    if (!client) return;
    navigate("chapters");
    setChapters(null);
    setBatchStart(start);
    void run(
      t("보관한 원문을 불러오고 있습니다"),
      (signal) => client.chapters(n, signal),
      setChapters,
    );
  };
  const loadJobs = (n = 0, start: "first" | "last" = "first") => {
    if (!client) return;
    navigate("jobs");
    setJobs(null);
    setBatchStart(start);
    void run(
      t("번역 작업을 확인하고 있습니다"),
      (signal) => client.jobs(n, signal),
      setJobs,
    );
  };
  const openOriginal = (value: StoredChapter) => {
    setChapter(value);
    originalView.current = view;
    const document: ReadingDocument = {
      id: `original:${value.recordId}:${value.sourceRevision}`,
      kind: "original",
      bookTitle: value.bookTitle,
      chapterTitle: value.chapterTitle,
      language: value.sourceLanguage,
      paragraphs: value.paragraphs,
      glossaryIdentity: { providerId: value.providerId, bookId: value.bookId },
    };
    setReader({
      document,
      chapter: value,
      anchor: position(username, document),
    });
  };
  const openStoredOriginal = (recordId: string) => {
    if (!client) return;
    void run(
      t("원문을 펼치고 있습니다"),
      (signal) => client.chapter(recordId, signal),
      openOriginal,
    );
  };
  const importOriginal = (address: string, bookUrl?: string) => {
    if (!client || busy) return;
    void run(
      t("원문을 불러와 서버에 보관하고 있습니다"),
      (signal) => client.importChapter(address, bookUrl, signal),
      openOriginal,
    );
  };
  const prepareTranslation = (value: StoredChapter, retry?: TranslationJob) => {
    if (!client) return;
    setReader(null);
    navigate("settings");
    setChapter(value);
    setRetryJob(retry);
    setProviders([]);
    void run(
      t("번역기를 확인하고 있습니다"),
      (signal) => client.providers(signal),
      setProviders,
    );
  };
  const retryTranslation = (value: TranslationJob) => {
    if (!client) return;
    void run(
      t("원문을 확인하고 있습니다"),
      (signal) => client.chapter(value.chapterRecordId, signal),
      (result) => prepareTranslation(result, value),
    );
  };
  useEffect(() => {
    if (!client || !initialDocument) return;
    originalView.current = "chapters";
    void run(
      t("문서를 서버에 보관하고 있습니다"),
      async (signal) => {
        const imported = await client.uploadChapter(
          localUploadInput(initialDocument),
          signal,
        );
        return { imported, providers: await client.providers(signal) };
      },
      (result) => {
        setChapter(result.imported);
        setProviders(result.providers);
        setRetryJob(undefined);
        setView("settings");
        onDocumentImported();
      },
    );
  }, [client, initialDocument, run]);
  const startTranslation = async (input: StartTranslation) => {
    if (!client) return;
    await run(
      t("번역을 요청하고 있습니다"),
      (signal) => client.start(input, signal),
      (value) => {
        setJob(value);
        setView("job");
        setRetryJob(undefined);
      },
    );
  };
  const showJob = (value: TranslationJob) => {
    navigate("job");
    setJob(value);
    setPollEpoch((value) => value + 1);
  };
  const refreshJob = () => {
    if (!client || !job) return;
    setPollError("");
    void run(
      t("진행 상황을 확인하고 있습니다"),
      (signal) => client.job(job.jobId, signal),
      (value) => {
        setJob(value);
        setPollEpoch((epoch) => epoch + 1);
      },
    );
  };
  useEffect(() => {
    if (!client || busy || view !== "job" || !job || !activeJob(job)) return;
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;
    const id = job.jobId;
    const poll = async () => {
      try {
        const next = await client.job(id, controller.signal);
        if (controller.signal.aborted) return;
        setJob(next);
        setPollError("");
        if (activeJob(next)) timer = setTimeout(() => void poll(), 1800);
      } catch (failure) {
        if (!controller.signal.aborted)
          setPollError(
            t("{0} 마지막으로 확인한 진행 상황입니다.", [message(failure)]),
          );
      }
    };
    timer = setTimeout(() => void poll(), 1200);
    return () => {
      controller.abort();
      clearTimeout(timer);
    };
  }, [client, view, job?.jobId, pollEpoch, busy]);
  const openTranslation = (value: TranslationJob) => {
    if (!translationClient || !value.translationRecordId) return;
    void run(
      t("번역문을 펼치고 있습니다"),
      async (signal) => openTranslationReading(username, await translationClient.get(value.translationRecordId!, signal)),
      (reader) => setReader({ ...reader, sourceRecordId: value.chapterRecordId }),
    );
  };
  if (cachedLibraryOpen && catalogCache) return <CachedCatalogBrowser cache={catalogCache} onClose={() => setCachedLibraryOpen(false)} onConnect={onConnect}/>;
  if (!client)
    return (
      <section className="workflow-welcome">
        <span className="eyebrow">FIND YOUR NEXT STORY</span>
        <h2>
          {t("이야기를 찾고,")}
          <br />
          {t("당신의 언어로 읽으세요.")}
        </h2>
        <p>
          {t("웹소설의 목차와 원문을 불러오고")}
          <br />
          {t("번역한 이야기를 서재에 모아 보세요.")}
        </p>
        <button className="button-primary" onClick={onConnect}>
          {t("서버에 연결")}
          <Icon name="arrow" />
        </button>
        <button className="button-text" onClick={onPreview}>
          {t("미리보기 읽기")}
          <Icon name="book" />
        </button>
        {catalogCache && <button className="button-outline" onClick={() => setCachedLibraryOpen(true)}>{t('저장된 목록')}</button>}
      </section>
    );
  if (reader)
    return (
      <TranslationComparisonReader
        key={reader.document.id}
        document={reader.document}
        translation={reader.translation}
        workflowClient={client}
        sourceRecordId={reader.sourceRecordId}
        notesNamespace={username}
        anchor={reader.anchor}
        saved={
          reader.chapter
            ? savedOriginalIds.includes(reader.chapter.recordId)
            : !!reader.translation &&
              isSaved(reader.translation.recordId, reader.translation.revision)
        }
        saving={
          reader.chapter ? !!busy : saving === reader.translation?.recordId
        }
        onClose={() => setReader(null)}
        onSave={
          reader.chapter && personal
            ? () => {
                void run(
                  t("원문을 기기에 보관하고 있습니다"),
                  () => personal.saveOriginal(reader.chapter!),
                  (saved) =>
                    setSavedOriginalIds((ids) => [
                      ...new Set([...ids, saved.chapter.recordId]),
                    ]),
                );
              }
            : reader.translation
              ? () => onSaveTranslation(reader.translation!)
              : undefined
        }
        actionLabel={reader.chapter ? t("번역하기") : undefined}
        onAction={
          reader.chapter ? () => prepareTranslation(reader.chapter!) : undefined
        }
        positionNote={
          reader.document.kind === "original"
            ? error
              ? t(error)
              : savedOriginalIds.includes(reader.chapter?.recordId ?? "")
                ? t("기기에 보관한 원문입니다. 연결 없이 읽을 수 있습니다.")
                : t("원문은 서버에 보관되어 있습니다.")
            : undefined
        }
        actionError={reader.translation ? error : undefined}
        onAnchorChange={(anchor) => {
          if (reader.translation) {
            const version = generation.current;
            void rememberTranslationPosition(username, reader.translation, anchor).catch(failure => {
              if (version === generation.current) setError(message(failure));
            });
            return;
          }
          try {
            setWorkflowPosition(username, reader.document, anchor);
          } catch {
            /* Reading remains available without persistent progress. */
          }
        }}
      />
    );
  return (
    <section className="novel-workspace">
      <nav className="workflow-subtabs" aria-label={t("웹소설 메뉴")}>
        <button
          aria-pressed={["sources", "catalog", "detail", "filters"].includes(
            view,
          )}
          onClick={() => {
            navigate("sources");
            if (!sources.length)
              void run(
                t("소스를 불러오고 있습니다"),
                (signal) => client.sources(signal),
                setSources,
              );
          }}
        >
          {t("이야기 찾기")}
        </button>
        <button
          aria-pressed={view === "favorites"}
          onClick={() => navigate("favorites")}
        >
          {t("즐겨찾기")}
        </button>
        <button
          aria-pressed={view === "chapters"}
          onClick={() => loadChapters()}
        >
          {t("보관 원문")}
        </button>
        <button
          aria-pressed={view === "jobs" || view === "job"}
          onClick={() => loadJobs()}
        >
          {t("번역 작업")}
        </button>
        <button
          aria-pressed={view === "bulk"}
          onClick={() => {
            setBulkDetail(null);
            navigate("bulk");
          }}
        >
          {t("묶음 작업")}
        </button>
      </nav>
      {personalNotice && (
        <div className="workflow-message" role="status">
          {personalNotice}
        </div>
      )}
      {error && (
        <div className="workflow-message" role="alert">
          <span>{error}</span>
          {retryAction.current && (
            <button
              className="button-text"
              onClick={() => retryAction.current?.()}
            >
              {t("다시 시도")}
            </button>
          )}
        </div>
      )}
      {view === "settings" && chapter && providers.length ? (
        <TranslationSetup
          key={`${chapter.recordId}:${retryJob?.jobId ?? ""}`}
          chapter={chapter}
          providers={providers}
          retry={retryJob}
          busy={!!busy}
          defaultTargetLanguage={defaultTargetLanguage}
          username={username}
          onSubmit={startTranslation}
          onBack={() =>
            retryJob ? showJob(retryJob) : navigate(originalView.current)
          }
        />
      ) : busy ? (
        <Feedback title={busy}>{t("잠시만 기다려 주세요.")}</Feedback>
      ) : view === "bulk" ? (
        <BulkNovelWorkspace
          client={client}
          username={username}
          detail={bulkDetail}
          defaultTargetLanguage={defaultTargetLanguage}
          onOpenOriginal={openOriginal}
          onBack={() => navigate(bulkDetail ? "detail" : "sources")}
        />
      ) : view === "favorites" && personal ? (
        <PersonalShelf
          storage={personal}
          sources={sources}
          onOpenBook={loadDetail}
          onOpenSource={(id, address) => {
            const source = sources.find((source) => source.id === id);
            if (!source) {
              setError(t("이 소스는 현재 서버에서 사용할 수 없습니다."));
              return;
            }
            setUrl(address);
            setQuery("");
            setFilters({});
            if (source.requiresUrl) loadDetail(address);
            else loadCatalog(source, 1, "", address, "first", {});
          }}
        />
      ) : view === "filters" && selectedSource ? (
        <CatalogFilterPanel
          source={selectedSource}
          value={filters}
          onBack={() => navigate("catalog")}
          onApply={(values) =>
            loadCatalog(selectedSource, 1, submittedQuery, url, "first", values)
          }
        />
      ) : view === "sources" ? (
        <>
          <div className="workflow-intro">
            <h2>{t("어디에서 읽을까요?")}</h2>
            <p>{t("사이트를 선택하거나 책 주소로 목차를 열어 보세요.")}</p>
          </div>
          <form
            className="workflow-search"
            onSubmit={(event) => {
              event.preventDefault();
              loadDetail(directUrl);
            }}
          >
            <input
              type="url"
              aria-label={t("웹소설 책 주소")}
              placeholder={t("웹소설 책 주소 붙여넣기")}
              required
              value={directUrl}
              onChange={(e) => setDirectUrl(e.target.value)}
            />
            <button className="button-outline">{t("목차 열기")}</button>
          </form>
          {sources.length || onOpenJsonCatalog || catalogCache ? (
            <AdaptiveCollection
              key="sources"
              items={[...sources.map(source => ({ id: `source:${source.id}`, source })),
                ...(onOpenJsonCatalog ? [{ id: 'tool:json', source: undefined }] : []),
                ...(catalogCache ? [{ id: 'tool:cache', source: undefined }] : [])]}
              itemKey={(s) => s.id}
              rowHeight={96}
              renderItem={(entry) => {
                const source = entry.source;
                if (!source) return <button className="workflow-row workflow-source" onClick={() => {
                  stopReadRequest();
                  if (entry.id === 'tool:json') onOpenJsonCatalog?.(); else setCachedLibraryOpen(true);
                }}><span className="workflow-row-number"><Icon name="shelf"/></span><span className="workflow-row-copy"><strong>{t(entry.id === 'tool:json' ? 'JSON 카탈로그' : '저장된 목록')}</strong><span>{t(entry.id === 'tool:json' ? '카탈로그 주소에서 파일 가져오기' : '기기에 임시 보관한 소스 목록')}</span></span><Icon name="arrow"/></button>;
                return (
                <button
                  className="workflow-row workflow-source"
                  onClick={() => {
                    setSelectedSource(source);
                    setQuery("");
                    setSubmittedQuery("");
                    setFilters({});
                    setUrl(source.defaultCatalogUrl ?? "");
                    if (source.requiresUrl) {
                      navigate("catalog");
                      setCatalog(null);
                    } else
                      loadCatalog(
                        source,
                        1,
                        "",
                        source.defaultCatalogUrl ?? "",
                        "first",
                        {},
                      );
                  }}
                >
                  <span className="workflow-row-number">
                    <Icon name="book" />
                  </span>
                  <span className="workflow-row-copy">
                    <strong>{source.displayName}</strong>
                    <span>
                      {source.requiresUrl
                        ? t("웹소설 주소로 불러오기")
                        : source.remoteSearch
                          ? t("목록 둘러보기 \u00B7 제목 검색")
                          : t("목록 둘러보기")}
                    </span>
                  </span>
                  <Icon name="arrow" />
                </button>
              ); }}
            />
          ) : (
            !error && (
              <Feedback title={t("등록된 소스가 없습니다")}>
                {t("서버의 소스 설정을 확인해 주세요.")}
              </Feedback>
            )
          )}
        </>
      ) : view === "catalog" && selectedSource ? (
        <>
          <div className="workflow-heading">
            <button
              className="icon-button"
              aria-label={t("소스 목록으로")}
              onClick={() => navigate("sources")}
            >
              <Icon name="back" />
            </button>
            <div>
              <span className="eyebrow">
                WEB NOVEL
                {catalog
                  ? t(" \u00B7 목록 {0}{1} 페이지", [
                      catalog.currentPage,
                      catalog.totalPages ? ` / ${catalog.totalPages}` : "",
                    ])
                  : ""}
              </span>
              <h2>{selectedSource.displayName}</h2>
            </div>
          </div>
          <form
            className="workflow-search"
            onSubmit={(event) => {
              event.preventDefault();
              selectedSource.requiresUrl
                ? loadDetail(url)
                : loadCatalog(selectedSource, 1, query);
            }}
          >
            <input
              aria-label={
                selectedSource.requiresUrl
                  ? t("웹소설 책 주소")
                  : t("웹소설 검색어")
              }
              placeholder={
                selectedSource.requiresUrl
                  ? t("책 주소를 붙여넣으세요")
                  : t("제목으로 검색")
              }
              type={selectedSource.requiresUrl ? "url" : "search"}
              value={selectedSource.requiresUrl ? url : query}
              onChange={(e) =>
                selectedSource.requiresUrl
                  ? setUrl(e.target.value)
                  : setQuery(e.target.value)
              }
              disabled={
                !selectedSource.remoteSearch && !selectedSource.requiresUrl
              }
              required={selectedSource.requiresUrl}
            />
            <button
              className="button-outline"
              disabled={
                !selectedSource.remoteSearch && !selectedSource.requiresUrl
              }
            >
              {selectedSource.requiresUrl ? t("목차 열기") : t("검색")}
            </button>
            {Object.values(selectedSource.filters).some(
              (options) => options.length > 0,
            ) && (
              <button
                type="button"
                className="button-outline"
                onClick={() => navigate("filters")}
              >
                {t("필터")}
              </button>
            )}
          </form>
          {catalogInfo && <div role="status" className="workflow-message">
            <span>{catalogInfo.origin === 'cache' ? t('기기 목록 · {0}', [new Date(catalogInfo.savedAt).toLocaleString()]) : t('서버에서 받은 목록')}
              {catalogInfo.stale && ` · ${t('오래된 목록')}`}{catalogInfo.offline && ` · ${t('연결 없이 표시 중')}`}
              {catalogInfo.refreshFailed && ` · ${t('새 목록을 받지 못했습니다')}`}
              {catalogInfo.cacheUnavailable && ` · ${t('기기 목록 캐시를 사용할 수 없습니다.')}`}</span>
            <button className="button-text" onClick={() => loadCatalog(selectedSource, catalogInfo.request.page, catalogInfo.request.query, catalogInfo.request.url ?? '', 'first', catalogInfo.request.filters, true)}>{t('목록 새로 받기')}</button>
          </div>}
          {catalog?.items.length ? (
            <CatalogTranslationPanel key={`catalog-translation:${username}:${catalog.url}:${catalog.currentPage}:${submittedQuery}`}
              books={catalog.items} client={client} defaultTargetLanguage={defaultTargetLanguage}>
            {(displayBooks) => (
            <AdaptiveCollection
              key={`${catalog.url}:${catalog.currentPage}:${submittedQuery}`}
              initialPage={batchStart}
              items={displayBooks}
              itemKey={(b) => b.bookId}
              rowHeight={110}
              total={catalog.items.length}
              onPreviousBatch={
                catalog.hasPreviousPage
                  ? () =>
                      loadCatalog(
                        selectedSource,
                        catalog.currentPage - 1,
                        submittedQuery,
                        url,
                        "last",
                      )
                  : undefined
              }
              onNextBatch={
                catalog.hasNextPage
                  ? () => loadCatalog(selectedSource, catalog.currentPage + 1)
                  : undefined
              }
              renderItem={(book, index) => (
                <button
                  className="workflow-row"
                  onClick={() => loadDetail(book.url)}
                >
                  <span className="workflow-row-number">
                    {String(index + 1).padStart(2, "0")}
                  </span>
                  <span className="workflow-row-copy">
                    <span>
                      {book.sourceLanguage.toUpperCase()} ·{" "}
                      {book.authors.join(", ") || selectedSource.displayName}
                    </span>
                    <strong title={book.title}>{book.title}</strong>
                    <span title={book.description ?? undefined}>
                      {book.description || (book.chapterCount === null
                        ? t("목차 보기")
                        : t("{0}개 회차", [book.chapterCount]))}
                    </span>
                  </span>
                  <Icon name="arrow" />
                </button>
              )}
            />
            )}</CatalogTranslationPanel>
          ) : (
            !error && (
              <Feedback
                title={
                  selectedSource.requiresUrl
                    ? t("읽을 책의 주소를 입력해 주세요")
                    : t("검색 결과가 없습니다")
                }
              >
                {selectedSource.requiresUrl
                  ? t("책 소개와 목차가 있는 페이지 주소를 사용하세요.")
                  : t("다른 검색어로 다시 찾아보세요.")}
              </Feedback>
            )
          )}
        </>
      ) : view === "detail" && detail ? (
        <>
          <div className="workflow-heading">
            <button
              className="icon-button"
              aria-label={t("검색 결과로")}
              onClick={() => navigate(catalog ? "catalog" : "sources")}
            >
              <Icon name="back" />
            </button>
            <div>
              <span className="eyebrow">
                {detail.author || detail.sourceLanguage.toUpperCase()}
              </span>
              <h2 title={detail.title}>{detail.title}</h2>
            </div>
          </div>
          <div className="workflow-subtabs">
            <button
              aria-pressed={detailTab === "chapters"}
              onClick={() => setDetailTab("chapters")}
            >
              {t("목차 \u00B7")}
              {detail.totalChapters}
            </button>
            <button
              aria-pressed={detailTab === "about"}
              onClick={() => setDetailTab("about")}
            >
              {t("책 소개")}
            </button>
            <button
              onClick={() => {
                setBulkDetail(detail);
                navigate("bulk");
              }}
            >
              {t("여러 회차")}
            </button>
            <button
              disabled={!personal}
              onClick={() =>
                personal &&
                void run(
                  t("즐겨찾기에 보관하고 있습니다"),
                  () =>
                    personal.saveFavorite({
                      sourceId: detail.sourceId,
                      bookId: detail.bookId,
                      title: detail.title,
                      url: detail.url,
                    }),
                  () => setPersonalNotice(t("즐겨찾기에 보관했습니다.")),
                )
              }
            >
              {t("즐겨찾기 추가")}
            </button>
          </div>
          {detailTab === "about" ? (
            <CatalogTranslationPanel key={`about-translation:${username}:${detail.bookId}`} books={detailDisplayBooks}
              client={client} defaultTargetLanguage={defaultTargetLanguage}>
            {(displayBooks) => (
            <div className="workflow-about">
              <p>
                {detail.status || t("연재 정보 없음")} ·{" "}
                {detail.sourceLanguage.toUpperCase()}
              </p>
              <h3>{displayBooks[0].title}</h3>
              <p className="workflow-summary">
                {displayBooks[0].description || t("등록된 소개가 없습니다.")}
              </p>
              {detail.summary && (
                <button
                  className="button-outline"
                  onClick={() => {
                    const document: ReadingDocument = {
                      id: `about:${detail.bookId}`,
                      kind: "introduction",
                      bookTitle: detail.title,
                      chapterTitle: t("책 소개"),
                      language: detail.sourceLanguage,
                      paragraphs: [
                        { paragraphId: "summary", text: detail.summary },
                      ],
                    };
                    setReader({ document });
                  }}
                >
                  {t("원문 소개 전체 읽기")}
                  <Icon name="arrow" />
                </button>
              )}
            </div>
            )}</CatalogTranslationPanel>
          ) : detail.chapters.length ? (
            <AdaptiveCollection
              key={`${detail.bookId}:${detail.page}`}
              initialPage={batchStart}
              items={detail.chapters}
              itemKey={(c) => c.chapterId}
              rowHeight={96}
              total={detail.totalItems}
              offset={detail.page * detail.size}
              onPreviousBatch={
                detail.page > 0
                  ? () => loadDetail(detail.url, detail.page - 1, "last")
                  : undefined
              }
              onNextBatch={
                detail.hasNext
                  ? () => loadDetail(detail.url, detail.page + 1)
                  : undefined
              }
              renderItem={(item) => (
                <button
                  className="workflow-row"
                  onClick={() => importOriginal(item.url, detail.url)}
                >
                  <span className="workflow-row-number">{item.number}</span>
                  <span className="workflow-row-copy">
                    <strong title={item.title}>{item.title}</strong>
                    <span>
                      {t("원문 불러와 읽기 \u00B7")}
                      {item.sourceLanguage.toUpperCase()}
                    </span>
                  </span>
                  <Icon name="arrow" />
                </button>
              )}
            />
          ) : (
            <Feedback
              title={t("목차를 찾지 못했습니다")}
              retry={() => loadDetail(detail.url)}
            >
              {t("원문 사이트의 목차를 확인한 뒤 다시 시도해 주세요.")}
            </Feedback>
          )}
        </>
      ) : view === "chapters" ? (
        <>
          <div className="workflow-heading">
            <div>
              <span className="eyebrow">ORIGINAL LIBRARY</span>
              <h2>{t("보관한 원문")}</h2>
            </div>
            <button
              className="icon-button"
              aria-label={t("원문 목록 새로고침")}
              onClick={() => loadChapters(chapters?.page ?? 0)}
            >
              <Icon name="refresh" />
            </button>
          </div>
          <form
            className="workflow-search"
            onSubmit={(event) => {
              event.preventDefault();
              importOriginal(directUrl);
            }}
          >
            <input
              type="url"
              aria-label={t("읽을 회차 주소")}
              placeholder={t("회차 주소로 원문 불러오기")}
              required
              value={directUrl}
              onChange={(e) => setDirectUrl(e.target.value)}
            />
            <button className="button-outline">{t("불러오기")}</button>
          </form>
          {chapters?.items.length ? (
            <AdaptiveCollection
              key={`chapters:${chapters.page}`}
              initialPage={batchStart}
              items={chapters.items}
              itemKey={(c) => c.recordId}
              rowHeight={100}
              total={chapters.totalItems}
              offset={chapters.page * chapters.size}
              onPreviousBatch={
                chapters.page > 0
                  ? () => loadChapters(chapters.page - 1, "last")
                  : undefined
              }
              onNextBatch={
                chapters.hasNext
                  ? () => loadChapters(chapters.page + 1)
                  : undefined
              }
              renderItem={(item) => (
                <button
                  className="workflow-row"
                  onClick={() => openStoredOriginal(item.recordId)}
                >
                  <span className="workflow-row-number">
                    <Icon name="book" />
                  </span>
                  <span className="workflow-row-copy">
                    <span title={item.bookTitle}>{item.bookTitle}</span>
                    <strong title={item.chapterTitle}>
                      {item.chapterTitle}
                    </strong>
                    <span>
                      {item.sourceLanguage.toUpperCase()}
                      {t("\u00B7 원문")}
                    </span>
                  </span>
                  <Icon name="arrow" />
                </button>
              )}
            />
          ) : (
            !error && (
              <Feedback title={t("아직 보관한 원문이 없습니다")}>
                {t("목차에서 읽을 회차를 선택하거나 회차 주소를 입력하세요.")}
              </Feedback>
            )
          )}
        </>
      ) : view === "jobs" ? (
        <>
          <div className="workflow-heading">
            <div>
              <span className="eyebrow">YOUR TRANSLATIONS</span>
              <h2>{t("번역 작업")}</h2>
            </div>
            <button
              className="icon-button"
              aria-label={t("번역 작업 새로고침")}
              onClick={() => loadJobs(jobs?.page ?? 0)}
            >
              <Icon name="refresh" />
            </button>
          </div>
          {jobs?.items.length ? (
            <AdaptiveCollection
              key={`jobs:${jobs.page}`}
              initialPage={batchStart}
              items={jobs.items}
              itemKey={(j) => j.jobId}
              rowHeight={110}
              total={jobs.totalItems}
              offset={jobs.page * jobs.size}
              onPreviousBatch={
                jobs.page > 0
                  ? () => loadJobs(jobs.page - 1, "last")
                  : undefined
              }
              onNextBatch={
                jobs.hasNext ? () => loadJobs(jobs.page + 1) : undefined
              }
              renderItem={(item) => (
                <button className="workflow-row" onClick={() => showJob(item)}>
                  <span className="workflow-row-number">
                    <Icon
                      name={item.status === "COMPLETED" ? "check" : "book"}
                    />
                  </span>
                  <span className="workflow-row-copy">
                    <span>
                      {t(statusLabels[item.status])} ·{" "}
                      {item.targetLanguage.toUpperCase()}
                    </span>
                    <strong title={item.chapterTitle}>
                      {item.chapterTitle}
                    </strong>
                    <span>
                      {item.completedParagraphs} / {item.totalParagraphs}
                      {t("문단 \u00B7")} {item.bookTitle}
                    </span>
                  </span>
                  <Icon name="arrow" />
                </button>
              )}
            />
          ) : (
            !error && (
              <Feedback title={t("아직 번역 작업이 없습니다")}>
                {t("원문을 연 뒤 번역하기를 눌러 시작하세요.")}
              </Feedback>
            )
          )}
        </>
      ) : view === "job" && job ? (
        <div className="workflow-job">
          <div className="workflow-heading">
            <button
              className="icon-button"
              aria-label={t("번역 작업 목록으로")}
              onClick={() => loadJobs()}
            >
              <Icon name="back" />
            </button>
            <div>
              <span className="eyebrow">TRANSLATION PROGRESS</span>
              <h2>{t(statusLabels[job.status])}</h2>
            </div>
          </div>
          <div className="workflow-job-copy">
            <span className="eyebrow">
              {job.targetLanguage.toUpperCase()} · {job.bookTitle}
            </span>
            <h3 title={job.chapterTitle}>{job.chapterTitle}</h3>
            <p className="workflow-progress-number">
              {job.completedParagraphs}
              <span>
                {" "}
                / {job.totalParagraphs}
                {t("문단")}
              </span>
            </p>
            <progress
              max={Math.max(1, job.totalParagraphs)}
              value={job.completedParagraphs}
              aria-label={t("번역한 문단")}
            />
            <p>
              {job.status === "COMPLETED"
                ? t("번역본이 서버 서재에 저장되었습니다.")
                : activeJob(job)
                  ? t("다른 화면을 읽는 동안에도 서버에서 계속 번역합니다.")
                  : t("보관한 원문은 그대로 유지됩니다.")}
            </p>
          </div>
          {pollError && (
            <div className="workflow-message" role="alert">
              {pollError}
            </div>
          )}
          {job.errorMessage && (
            <p className="workflow-job-error" role="alert">
              {t(job.errorMessage)}
            </p>
          )}
          <div className="workflow-job-actions">
            {job.translationRecordId && (
              <button
                className="button-primary"
                onClick={() => openTranslation(job)}
              >
                {t("번역문 읽기")}
                <Icon name="arrow" />
              </button>
            )}
            {job.canRetry && (
              <button
                className="button-primary"
                onClick={() => retryTranslation(job)}
              >
                {t("설정 확인 후 재시도")}
              </button>
            )}
            {activeJob(job) && (
              <button
                className="button-outline"
                onClick={() => {
                  void run(
                    t("작업 취소를 요청하고 있습니다"),
                    (signal) => client.cancel(job.jobId, signal),
                    (value) => {
                      setJob(value);
                      setPollEpoch((epoch) => epoch + 1);
                    },
                  );
                }}
              >
                {t("번역 취소")}
              </button>
            )}
            <button className="button-outline" onClick={refreshJob}>
              {t("진행 확인")}
              <Icon name="refresh" />
            </button>
          </div>
        </div>
      ) : (
        !error && (
          <Feedback
            title={t("내용을 다시 불러와 주세요")}
            retry={() => navigate("sources")}
          />
        )
      )}
    </section>
  );
}
