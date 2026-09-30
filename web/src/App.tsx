import { translate as t } from "./lib/locale";
import {
  useCallback,
  useEffect,
  useRef,
  useState,
  useSyncExternalStore,
  type ReactNode,
} from "react";
import {
  ApiError,
  createTranslationClient,
  type TranslationPage,
  type TranslationResponse,
  type TranslationSummary,
} from "./lib/api";
import {
  OfflineError,
  createOfflineLibrary,
  type CorruptOfflineRecord,
  type ReadingAnchor,
  type StoredBook,
} from "./lib/offline";
import {
  getOfflineShellStatus,
  subscribeOfflineShell,
} from "./lib/serviceWorker";
import { previewTranslation, demoTitle } from "./demo";
import { AdaptiveCollection } from "./components/AdaptiveCollection";
import { Icon, type IconName } from "./components/Icon";
import { TranslationComparisonReader } from "./components/TranslationComparisonReader";
import { NovelWorkspace } from "./components/NovelWorkspace";
import { AccountPanel } from "./components/AccountPanel";
import { ReadingProgressProvider } from './components/ReadingProgressProvider';
import { createReadingProgressClient, type ReadingProgressClient } from './lib/readingProgressApi';
import { ReadingNoteProvider } from './components/ReadingNoteProvider';
import { createReadingNoteClient, type ReadingNoteClient } from './lib/readingNoteApi';
import { LocalWorkspace } from "./components/LocalWorkspace";
import { LibraryExchangeWorkspace } from "./components/LibraryExchangeWorkspace";
import { OriginalLibrary } from "./components/OriginalLibrary";
import { JsonCatalogWorkspace } from './components/JsonCatalogWorkspace';
import { createJsonCatalogClient, type JsonCatalogClient } from './lib/jsonCatalogApi';
import { ReaderPreferencesProvider } from "./components/ReaderPreferences";
import type { ReadingDocument } from "./lib/readingDocument";
import { openTranslationReading, rememberTranslationPosition, translationReadingDocument } from './lib/translationReading';
import {
  createAccountClient,
  type AccountClient,
  type AccountProfile,
} from "./lib/accountApi";
import { useLocale } from "./lib/locale";
import { createWorkflowClient, type WorkflowClient } from "./lib/workflowApi";
type Tab = "novels" | "server" | "device" | "connection";
type Client = ReturnType<typeof createTranslationClient>;
type Library = ReturnType<typeof createOfflineLibrary>;
type Reading = {
  translation: TranslationResponse;
  preview: boolean;
  anchor?: ReadingAnchor;
};
type LoadState = "idle" | "loading" | "ready" | "error";
type DeviceItem =
  | {
      kind: "book";
      book: StoredBook;
    }
  | {
      kind: "corrupt";
      record: CorruptOfflineRecord;
    };
function bookTitle(book: { bookId: string }) {
  return "bookTitle" in book &&
    typeof book.bookTitle === "string" &&
    book.bookTitle
    ? book.bookTitle
    : book.bookId;
}
function chapterTitle(book: { chapterId: string }) {
  return "chapterTitle" in book &&
    typeof book.chapterTitle === "string" &&
    book.chapterTitle
    ? book.chapterTitle
    : book.chapterId;
}
const accountKey = "pageturner.last-account";
function lastAccount() {
  try {
    return localStorage.getItem(accountKey) ?? "";
  } catch {
    return "";
  }
}
function friendlyError(error: unknown, fallback: string) {
  if (error instanceof ApiError || error instanceof OfflineError)
    return t(error.message);
  const kind =
    error && typeof error === "object" && "kind" in error
      ? String(error.kind).toLowerCase()
      : "";
  if (/auth|unauthorized/.test(kind))
    return t("계정 이름과 비밀번호를 확인해 주세요.");
  if (/network|timeout/.test(kind))
    return t(
      "서버에 연결할 수 없습니다. 연결 상태를 확인한 뒤 다시 시도해 주세요.",
    );
  if (/corrupt|integrity|invalid/.test(kind))
    return t("번역본을 확인할 수 없습니다. 서버에서 다시 불러와 주세요.");
  if (/quota/.test(kind))
    return t(
      "이 기기의 저장 공간이 부족합니다. 보관한 책을 정리한 뒤 다시 시도해 주세요.",
    );
  return fallback;
}
function dateLabel(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? ""
    : new Intl.DateTimeFormat("ko-KR", {
        month: "long",
        day: "numeric",
      }).format(date);
}
function BookRow({
  book,
  index,
  local,
  saved,
  busy,
  onOpen,
  onSave,
  onDelete,
}: {
  book: TranslationSummary | TranslationResponse;
  index: number;
  local?: boolean;
  saved: boolean;
  busy: boolean;
  onOpen: () => void;
  onSave: () => void;
  onDelete: (trigger: HTMLButtonElement) => void;
}) {
  return (
    <article className="book-row">
      <button
        className="book-open"
        onClick={onOpen}
        aria-label={t("{0}, {1} 읽기", [bookTitle(book), chapterTitle(book)])}
      >
        <span className="book-cover" aria-hidden="true">
          <span className="cover-line" />
          <span>{String(index + 1).padStart(2, "0")}</span>
          <Icon name="book" size={18} />
        </span>
        <span className="book-details">
          <span className="book-language">
            {book.sourceLanguage.toUpperCase()}{" "}
            <span aria-hidden="true">→</span>{" "}
            {book.targetLanguage.toUpperCase()}{" "}
            <span className="book-edition">{t("번역본")}</span>
          </span>
          <strong title={bookTitle(book)}>{bookTitle(book)}</strong>
          <span className="book-meta">
            <span title={chapterTitle(book)}>{chapterTitle(book)}</span>
            <span className="book-date">{dateLabel(book.createdAt)}</span>
          </span>
        </span>
      </button>
      <div className="book-action">
        {local ? (
          <button
            className="icon-button"
            title={t("이 기기에서 삭제")}
            aria-label={t("{0} 이 기기에서 삭제", [bookTitle(book)])}
            onClick={(event) => onDelete(event.currentTarget)}
            disabled={busy}
          >
            <Icon name="trash" size={19} />
          </button>
        ) : (
          <button
            className="icon-button"
            title={saved ? t("기기에 보관됨") : t("이 기기에 보관")}
            aria-label={`${bookTitle(book)} ${saved ? t("기기에 보관됨") : t("이 기기에 보관")}`}
            onClick={onSave}
            disabled={busy || saved}
          >
            <Icon
              name={busy ? "book" : saved ? "check" : "download"}
              size={19}
            />
          </button>
        )}
      </div>
    </article>
  );
}
function EmptyState({
  icon = "book",
  title,
  children,
  action,
  actionLabel,
}: {
  icon?: IconName;
  title: string;
  children: ReactNode;
  action?: () => void;
  actionLabel?: string;
}) {
  return (
    <div className="empty-state">
      <div className="empty-icon">
        <Icon name={icon} size={32} />
      </div>
      <h2>{title}</h2>
      <p>{children}</p>
      {action && (
        <button className="button-outline" onClick={action}>
          {actionLabel}
          <Icon name="arrow" size={18} />
        </button>
      )}
    </div>
  );
}
export default function App() {
  const { setLocale } = useLocale();
  const [tab, setTab] = useState<Tab>("novels");
  const [deviceView, setDeviceView] = useState<
    "translations" | "originals" | "files" | "exchange"
  >("translations");
  const [username, setUsername] = useState(lastAccount);
  const [formUsername, setFormUsername] = useState(lastAccount);
  const [client, setClient] = useState<Client | null>(null);
  const [jsonCatalogClient, setJsonCatalogClient] = useState<JsonCatalogClient | null>(null);
  const [jsonCatalogOpen, setJsonCatalogOpen] = useState(false);
  const [accountClient, setAccountClient] = useState<AccountClient | null>(
    null,
  );
  const [accountProfile, setAccountProfile] = useState<AccountProfile | null>(
    null,
  );
  const accountClientRef = useRef<AccountClient | null>(null);
  const [progressClient, setProgressClient] = useState<ReadingProgressClient | null>(null);
  const progressClientRef = useRef<ReadingProgressClient | null>(null);
  const [noteClient, setNoteClient] = useState<ReadingNoteClient | null>(null);
  const noteClientRef = useRef<ReadingNoteClient | null>(null);
  const [workflowClient, setWorkflowClient] = useState<WorkflowClient | null>(
    null,
  );
  const workflowClientRef = useRef<WorkflowClient | null>(null);
  const [workflowReading, setWorkflowReading] = useState(false);
  const [uploadDocument, setUploadDocument] = useState<ReadingDocument>();
  const [connecting, setConnecting] = useState(false);
  const [connectionError, setConnectionError] = useState("");
  const [serverPage, setServerPage] = useState<TranslationPage | null>(null);
  const [serverState, setServerState] = useState<LoadState>("idle");
  const [serverError, setServerError] = useState("");
  const [requestedPage, setRequestedPage] = useState(0);
  const [batchStart, setBatchStart] = useState<"first" | "last">("first");
  const [localBooks, setLocalBooks] = useState<StoredBook[]>([]);
  const [corruptRecords, setCorruptRecords] = useState<CorruptOfflineRecord[]>(
    [],
  );
  const [localState, setLocalState] = useState<LoadState>("idle");
  const [localError, setLocalError] = useState("");
  const [reading, setReading] = useState<Reading | null>(null);
  const [opening, setOpening] = useState(false);
  const [busyRecord, setBusyRecord] = useState<string | null>(null);
  const [deleteBook, setDeleteBook] = useState<{
    title: string;
    recordId?: string;
    recoveryId?: string;
  } | null>(null);
  const [notice, setNotice] = useState("");
  const library = useRef<Library | null>(null);
  const session = useRef(0);
  const request = useRef<AbortController | null>(null);
  const currentAnchor = useRef<ReadingAnchor | undefined>(undefined);
  const dialog = useRef<HTMLElement>(null);
  const deleteTrigger = useRef<HTMLElement | null>(null);
  const dialogWasOpen = useRef(false);
  const refreshButton = useRef<HTMLButtonElement>(null);
  const shell = useSyncExternalStore(
    subscribeOfflineShell,
    getOfflineShellStatus,
  );
  const title =
    tab === "novels"
      ? t("웹소설")
      : tab === "server"
        ? t("나의 서재")
        : tab === "device"
          ? t("이 기기 보관")
          : t("서버 연결");
  useEffect(() => {
    if (!deleteBook && dialogWasOpen.current) {
      const trigger = deleteTrigger.current;
      const fallback =
        refreshButton.current ??
        document.querySelector<HTMLElement>(".nav-item.active");
      if (
        trigger?.isConnected &&
        !(trigger instanceof HTMLButtonElement && trigger.disabled)
      )
        trigger.focus();
      else fallback?.focus();
      deleteTrigger.current = null;
    }
    dialogWasOpen.current = !!deleteBook;
  }, [deleteBook]);
  const openDeleteConfirmation = (
    book: NonNullable<typeof deleteBook>,
    trigger: HTMLElement,
  ) => {
    deleteTrigger.current = trigger;
    setDeleteBook(book);
  };
  useEffect(() => {
    if (!deleteBook) return;
    const node = dialog.current;
    const firstAvailable = node?.querySelector<HTMLButtonElement>(
      "button:not(:disabled)",
    );
    if (node && (!node.contains(document.activeElement) || !firstAvailable))
      node.focus();
    else if (node === document.activeElement) firstAvailable?.focus();
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape" && !busyRecord) {
        event.preventDefault();
        setDeleteBook(null);
      }
      if (event.key !== "Tab") return;
      const controls = dialog.current?.querySelectorAll<HTMLButtonElement>(
        "button:not(:disabled)",
      );
      if (!controls?.length) {
        event.preventDefault();
        dialog.current?.focus();
        return;
      }
      const first = controls[0];
      const last = controls[controls.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("keydown", onKey);
    };
  }, [deleteBook, busyRecord]);
  useEffect(() => {
    let active = true;
    setLocalBooks([]);
    setCorruptRecords([]);
    setLocalError("");
    if (!username) {
      setLocalState("idle");
      return;
    }
    let storage: Library;
    try {
      storage = createOfflineLibrary(username);
    } catch (error) {
      setLocalState("error");
      setLocalError(friendlyError(error, t("보관함을 열지 못했습니다.")));
      return;
    }
    library.current = storage;
    setLocalState("loading");
    storage
      .list()
      .then((snapshot) => {
        if (active) {
          setLocalBooks(snapshot.books);
          setCorruptRecords(snapshot.corruptRecords);
          setLocalState("ready");
        }
      })
      .catch((error) => {
        if (active) {
          setLocalState("error");
          setLocalError(
            friendlyError(
              error,
              t(
                "이 기기의 보관함을 열지 못했습니다. 브라우저의 저장 공간 권한을 확인해 주세요.",
              ),
            ),
          );
        }
      });
    try {
      localStorage.setItem(accountKey, username);
    } catch {
      /* IndexedDB errors are reported by the library itself. */
    }
    return () => {
      active = false;
      storage.close();
      if (library.current === storage) library.current = null;
    };
  }, [username]);
  useEffect(
    () => () => {
      request.current?.abort();
      workflowClientRef.current?.close();
      accountClientRef.current?.close();
      progressClientRef.current?.close();
      noteClientRef.current?.close();
      session.current += 1;
    },
    [],
  );
  const resetSession = () => {
    session.current += 1;
    request.current?.abort();
    request.current = null;
    setClient(null);
    setJsonCatalogClient(null);
    setJsonCatalogOpen(false);
    accountClientRef.current?.close();
    accountClientRef.current = null;
    progressClientRef.current?.close();
    progressClientRef.current = null;
    setProgressClient(null);
    noteClientRef.current?.close();
    noteClientRef.current = null;
    setNoteClient(null);
    setAccountClient(null);
    setAccountProfile(null);
    workflowClientRef.current?.close();
    workflowClientRef.current = null;
    setWorkflowClient(null);
    setWorkflowReading(false);
    setUploadDocument(undefined);
    setReading(null);
    setOpening(false);
    setBusyRecord(null);
    setDeleteBook(null);
    setServerPage(null);
    setServerState("idle");
    setServerError("");
    setNotice("");
  };
  const connect = async (details: {
    username: string;
    password: string;
  }): Promise<boolean> => {
    if (!details.username.trim() || !details.password || connecting)
      return false;
    resetSession();
    const version = session.current;
    const controller = new AbortController();
    request.current = controller;
    setConnecting(true);
    setConnectionError("");
    let nextAccountClient: AccountClient | null = null;
    try {
      const nextClient = createTranslationClient({
        username: details.username,
        password: details.password,
      });
      const page = await nextClient.list(0, controller.signal);
      nextAccountClient = createAccountClient(details);
      const profile = await nextAccountClient.me(controller.signal);
      if (version !== session.current) {
        nextAccountClient.close();
        return false;
      }
      accountClientRef.current = nextAccountClient;
      setAccountClient(nextAccountClient);
      setAccountProfile(profile);
      const nextProgressClient = createReadingProgressClient({ username: profile.username, password: details.password });
      progressClientRef.current = nextProgressClient;
      setProgressClient(nextProgressClient);
      const nextNoteClient = createReadingNoteClient({ username: profile.username, password: details.password });
      noteClientRef.current = nextNoteClient;
      setNoteClient(nextNoteClient);
      setLocale(profile.locale);
      setUsername(profile.username);
      setFormUsername(profile.username);
      setClient(nextClient);
      const nextWorkflowClient = createWorkflowClient({
        username: details.username,
        password: details.password,
      });
      workflowClientRef.current = nextWorkflowClient;
      setWorkflowClient(nextWorkflowClient);
      setJsonCatalogClient(createJsonCatalogClient({ username: profile.username, password: details.password }));
      setServerPage(page);
      setServerState("ready");
      setRequestedPage(0);
      setBatchStart("first");
      setTab("novels");
      return true;
    } catch (error) {
      nextAccountClient?.close();
      if (version === session.current && !controller.signal.aborted)
        setConnectionError(
          friendlyError(
            error,
            t(
              "서버에 연결하지 못했습니다. 계정 정보와 서버 연결을 확인해 주세요.",
            ),
          ),
        );
      return false;
    } finally {
      if (version === session.current) setConnecting(false);
    }
  };
  const disconnect = () => {
    resetSession();
    setConnecting(false);
    setConnectionError("");
    setTab("connection");
    setNotice(
      t(
        "서버 연결을 해제했습니다. 이 기기에 보관한 책은 계속 읽을 수 있습니다.",
      ),
    );
  };
  const loadServer = useCallback(
    async (page: number, start: "first" | "last" = "first") => {
      if (!client) return;
      request.current?.abort();
      const controller = new AbortController();
      request.current = controller;
      const version = session.current;
      setRequestedPage(page);
      setBatchStart(start);
      setServerPage(null);
      setServerState("loading");
      setServerError("");
      try {
        const response = await client.list(page, controller.signal);
        if (version === session.current && !controller.signal.aborted) {
          setServerPage(response);
          setServerState("ready");
        }
      } catch (error) {
        if (version === session.current && !controller.signal.aborted) {
          setServerState("error");
          setServerError(
            friendlyError(
              error,
              t("서재를 불러오지 못했습니다. 다시 시도해 주세요."),
            ),
          );
        }
      }
    },
    [client],
  );
  const refreshLocal = useCallback(async () => {
    const storage = library.current;
    if (!storage) return;
    const version = session.current;
    setLocalState("loading");
    setLocalBooks([]);
    setCorruptRecords([]);
    try {
      const snapshot = await storage.list();
      if (version === session.current) {
        setLocalBooks(snapshot.books);
        setCorruptRecords(snapshot.corruptRecords);
        setLocalState("ready");
      }
    } catch (error) {
      if (version === session.current) {
        setLocalState("error");
        setLocalError(
          friendlyError(
            error,
            t("보관함을 불러오지 못했습니다. 다시 시도해 주세요."),
          ),
        );
      }
    }
  }, []);
  const openBook = async (recordId: string, local = false) => {
    request.current?.abort();
    const controller = new AbortController();
    request.current = controller;
    const version = session.current;
    setReading(null);
    setOpening(true);
    setNotice("");
    try {
      const stored = local ? await library.current?.get(recordId) : undefined;
      const translation = local
        ? stored?.translation
        : await client?.get(recordId, controller.signal);
      if (!translation) throw new Error("Missing translation");
      if (version !== session.current || controller.signal.aborted) return;
      const { anchor } = await openTranslationReading(username, translation, { offline: library.current ?? undefined });
      if (version !== session.current || controller.signal.aborted) return;
      currentAnchor.current = anchor;
      setReading({ translation, preview: false, anchor });
    } catch (error) {
      if (version === session.current && !controller.signal.aborted)
        setNotice(
          friendlyError(
            error,
            t("책을 열지 못했습니다. 목록에서 다시 선택해 주세요."),
          ),
        );
    } finally {
      if (version === session.current) setOpening(false);
    }
  };
  const saveBook = async (
    recordId: string,
    available?: TranslationResponse,
    recoveryId?: string,
  ) => {
    const storage = library.current;
    if (!storage || busyRecord) return;
    const version = session.current;
    const controller = new AbortController();
    request.current?.abort();
    request.current = controller;
    setBusyRecord(recordId);
    try {
      const translation =
        available ?? (await client?.get(recordId, controller.signal));
      if (
        !translation ||
        version !== session.current ||
        controller.signal.aborted
      )
        return;
      const repairId =
        recoveryId ??
        corruptRecords.find((record) => record.recordId === recordId)
          ?.recoveryId;
      if (repairId) await storage.replaceCorrupt(repairId, translation);
      else await storage.save(translation);
      const anchor = reading?.translation.recordId === recordId ? currentAnchor.current :
        (await openTranslationReading(username, translation, { offline: storage })).anchor;
      if (anchor)
        await storage.setAnchor(recordId, anchor);
      const snapshot = await storage.list();
      if (version === session.current) {
        setLocalBooks(snapshot.books);
        setCorruptRecords(snapshot.corruptRecords);
        setLocalState("ready");
        const currentShell = getOfflineShellStatus();
        setNotice(
          currentShell.state === "ready"
            ? t(
                "이 기기에 보관했습니다. 다음 방문에도 연결 없이 읽을 수 있습니다.",
              )
            : currentShell.state === "pending"
              ? t(
                  "번역본을 보관했습니다. 오프라인 읽기 화면을 준비하고 있어요.",
                )
              : currentShell.state === "error"
                ? t(
                    "번역본을 보관했습니다. 오프라인 재방문을 위해 연결된 상태에서 화면을 다시 열어 주세요.",
                  )
                : t(
                    "번역본을 보관했습니다. 현재 화면에서 연결 없이 읽을 수 있습니다.",
                  ),
        );
      }
    } catch (error) {
      if (version === session.current && !controller.signal.aborted)
        setNotice(
          friendlyError(
            error,
            t(
              "이 기기에 보관하지 못했습니다. 저장 공간을 확인한 뒤 다시 시도해 주세요.",
            ),
          ),
        );
    } finally {
      if (version === session.current) setBusyRecord(null);
    }
  };
  const removeBook = async () => {
    const storage = library.current;
    const book = deleteBook;
    if (!storage || !book) return;
    const version = session.current;
    setBusyRecord(book.recordId ?? book.recoveryId ?? "deleting");
    try {
      if (book.recoveryId) await storage.removeCorrupt(book.recoveryId);
      else if (book.recordId) await storage.remove(book.recordId);
      const snapshot = await storage.list();
      if (version === session.current) {
        setLocalBooks(snapshot.books);
        setCorruptRecords(snapshot.corruptRecords);
        setDeleteBook(null);
        setNotice(
          t("이 기기에서 삭제했습니다. 서버의 번역본은 그대로 있습니다."),
        );
      }
    } catch {
      if (version === session.current)
        setNotice(t("보관한 책을 삭제하지 못했습니다. 다시 시도해 주세요."));
    } finally {
      if (version === session.current) setBusyRecord(null);
    }
  };
  const rememberAnchor = useCallback(
    (anchor: ReadingAnchor) => {
      currentAnchor.current = anchor;
      if (!reading || reading.preview || !username) return;
      const version = session.current;
      void rememberTranslationPosition(username, reading.translation, anchor, { offline: library.current ?? undefined })
        .catch(() => { if (version === session.current) setNotice(t("읽은 위치를 보관함에 저장하지 못했습니다.")); });
    },
    [reading, username],
  );
  const openPreview = () => {
    request.current?.abort();
    setOpening(false);
    currentAnchor.current = undefined;
    setReading({ translation: previewTranslation, preview: true });
    setNotice("");
  };
  const selectTab = (next: Tab) => {
    request.current?.abort();
    setReading(null);
    setOpening(false);
    setNotice("");
    setWorkflowReading(false);
    setTab(next);
    if (
      next === "server" &&
      client &&
      (serverState === "idle" || serverState === "loading")
    )
      void loadServer(requestedPage);
  };
  const isSaved = (recordId: string, revision: string) =>
    localBooks.some(
      (book) =>
        book.translation.recordId === recordId &&
        book.translation.revision === revision,
    );
  const deviceItems: DeviceItem[] = [
    ...localBooks.map((book) => ({ kind: "book" as const, book })),
    ...corruptRecords.map((record) => ({ kind: "corrupt" as const, record })),
  ];
  const shellLabel =
    shell.state === "ready"
      ? shell.updateFailed
        ? t("오프라인 읽기 가능 \u00B7 새 화면 업데이트 대기")
        : t("오프라인 읽기 준비 완료")
      : shell.state === "pending"
        ? t("오프라인 화면 준비 중")
        : shell.state === "error"
          ? t("오프라인 화면 준비 실패 \u00B7 온라인에서 다시 열어 주세요")
          : t("현재 화면에서 오프라인 읽기 가능");
  return (
    <ReaderPreferencesProvider namespace={username}>
    <ReadingProgressProvider username={username} client={progressClient}>
    <ReadingNoteProvider username={username} client={noteClient}>
      <div className="app-shell">
        <header className="app-header">
          <a
            className="brand"
            href="#"
            onClick={(event) => {
              event.preventDefault();
              selectTab("novels");
            }}
            aria-label={t("PageTurner 서재")}
          >
            <Icon name="book" size={29} />
            <span>
              PageTurner<span className="brand-period">.</span>
            </span>
          </a>
          <span className="brand-note">
            {t("이야기와 나 사이, 한 페이지.")}
          </span>
          <button
            className="account-button"
            onClick={() => selectTab("connection")}
          >
            <span className={`status-dot ${client ? "connected" : ""}`} />
            <span>
              {client
                ? accountProfile?.displayName || username
                : t("서버 연결")}
            </span>
            <Icon name="link" size={17} />
          </button>
        </header>
        {notice && (
          <div className="notice" role="status">
            <span>{notice}</span>
            <button
              className="icon-button"
              aria-label={t("알림 닫기")}
              onClick={() => setNotice("")}
            >
              <Icon name="close" size={16} />
            </button>
          </div>
        )}
        <div
          className={`workspace ${reading || workflowReading ? "workspace-reading" : ""}`}
        >
          {!reading && !workflowReading && (
            <aside className="sidebar">
              <div className="sidebar-label eyebrow">MY READING SPACE</div>
              <nav className="primary-nav" aria-label={t("서재 메뉴")}>
                {(
                  [
                    { id: "novels", label: t("웹소설"), icon: "book" },
                    { id: "server", label: t("서버 서재"), icon: "shelf" },
                    { id: "device", label: t("이 기기 보관"), icon: "device" },
                    { id: "connection", label: t("연결"), icon: "link" },
                  ] as const
                ).map((item) => (
                  <button
                    key={item.id}
                    className={`nav-item ${tab === item.id ? "active" : ""}`}
                    onClick={() => selectTab(item.id)}
                    aria-current={tab === item.id ? "page" : undefined}
                  >
                    <Icon name={item.icon} size={21} />
                    <span>{item.label}</span>
                    {item.id === "device" && localBooks.length > 0 && (
                      <span className="nav-count">{localBooks.length}</span>
                    )}
                  </button>
                ))}
              </nav>
              <div className="sidebar-bottom">
                <div className="side-rule" />
                <Icon name="book" size={25} />
                <p>
                  {t("서두르지 않아도 좋은")}
                  <br />
                  {t("당신만의 읽는 시간.")}
                </p>
                <span className="eyebrow">DESIGNED FOR READING</span>
                <span className="sidebar-version">PAGE BY PAGE · 01</span>
              </div>
            </aside>
          )}
          <main
            className={
              reading || workflowReading ? "reader-main" : "main-content"
            }
            id="main-content"
          >
            {reading ? (
              <TranslationComparisonReader
                key={`${reading.preview ? "preview" : username}:${reading.translation.recordId}`}
                document={translationReadingDocument(reading.translation)}
                translation={reading.translation}
                workflowClient={workflowClient}
                anchor={reading.anchor}
                preview={reading.preview}
                notesNamespace={reading.preview ? undefined : username}
                saved={isSaved(
                  reading.translation.recordId,
                  reading.translation.revision,
                )}
                saving={busyRecord === reading.translation.recordId}
                onClose={() => selectTab(tab)}
                onSave={() =>
                  void saveBook(
                    reading.translation.recordId,
                    reading.translation,
                  )
                }
                onAnchorChange={rememberAnchor}
              />
            ) : (
              <>
                {!workflowReading && (
                  <header className="section-header">
                    <div>
                      <span className="eyebrow">
                        {tab === "novels"
                          ? "DISCOVER YOUR NEXT STORY"
                          : tab === "server"
                            ? "YOUR LIBRARY"
                            : tab === "device"
                              ? "SAVED ON THIS DEVICE"
                              : "A CONNECTED LIBRARY"}
                      </span>
                      <h1>
                        {title}
                        <span className="heading-period">.</span>
                      </h1>
                    </div>
                    {tab !== "connection" &&
                      ((tab === "server" && client) ||
                        (tab === "device" &&
                          deviceView === "translations" &&
                          username)) && (
                        <button
                          ref={refreshButton}
                          className="button-outline refresh-button"
                          onClick={() =>
                            tab === "server"
                              ? void loadServer(requestedPage)
                              : void refreshLocal()
                          }
                          disabled={
                            opening ||
                            (serverState === "loading" && tab === "server") ||
                            (localState === "loading" && tab === "device")
                          }
                        >
                          <Icon name="refresh" size={18} />
                          <span>{t("새로고침")}</span>
                        </button>
                      )}
                  </header>
                )}
                {tab === "device" && !workflowReading && (
                  <nav
                    className="workflow-subtabs"
                    aria-label={t("기기 보관함 종류")}
                  >
                    <button
                      aria-pressed={deviceView === "translations"}
                      onClick={() => setDeviceView("translations")}
                    >
                      {t("번역 보관")}
                    </button>
                    <button
                      aria-pressed={deviceView === "originals"}
                      onClick={() => setDeviceView("originals")}
                    >
                      {t("원문 보관")}
                    </button>
                    <button
                      aria-pressed={deviceView === "files"}
                      onClick={() => setDeviceView("files")}
                    >
                      {t("로컬 파일")}
                    </button>
                    <button aria-pressed={deviceView === 'exchange'} onClick={() => setDeviceView('exchange')}>{t('앱 · 웹 ZIP 교환')}</button>
                  </nav>
                )}
                {opening ? (
                  <EmptyState title={t("책을 펼치고 있습니다")}>
                    {t("번역문과 읽은 위치를 확인하고 있어요.")}
                  </EmptyState>
                ) : tab === 'novels' && jsonCatalogOpen && jsonCatalogClient ? (
                  <JsonCatalogWorkspace key={username} username={username} client={jsonCatalogClient}
                    onBack={() => { setWorkflowReading(false); setJsonCatalogOpen(false); }}
                    onReadingChange={setWorkflowReading} onTranslate={document => {
                      setWorkflowReading(false); setJsonCatalogOpen(false); setUploadDocument(document);
                    }}/>
                ) : tab === "novels" ? (
                  <NovelWorkspace
                    key={
                      username + (workflowClient ? ":connected" : ":offline")
                    }
                    client={workflowClient}
                    translationClient={client}
                    username={username}
                    onConnect={() => selectTab("connection")}
                    onPreview={openPreview}
                    onOpenJsonCatalog={() => setJsonCatalogOpen(true)}
                    onReadingChange={setWorkflowReading}
                    onSaveTranslation={(translation) =>
                      void saveBook(translation.recordId, translation)
                    }
                    isSaved={isSaved}
                    saving={busyRecord}
                    defaultTargetLanguage={
                      accountProfile?.targetLanguage ?? "ko"
                    }
                    initialDocument={uploadDocument}
                    onDocumentImported={() => setUploadDocument(undefined)}
                  />
                ) : tab === "connection" ? (
                  <AccountPanel
                    client={accountClient}
                    profile={accountProfile}
                    connecting={connecting}
                    connectionError={connectionError}
                    initialUsername={formUsername}
                    onLogin={connect}
                    onProfile={setAccountProfile}
                    onDisconnect={disconnect}
                    onPasswordChanged={() => {
                      if (accountClientRef.current !== accountClient) return;
                      disconnect();
                      setNotice(t('비밀번호를 변경했습니다. 새 비밀번호로 다시 로그인해 주세요.'));
                    }}
                    onPasswordUncertain={() => {
                      if (accountClientRef.current !== accountClient) return;
                      disconnect();
                      setNotice(t('비밀번호 변경 결과를 확인하지 못했습니다. 새 비밀번호로 로그인을 확인해 주세요.'));
                    }}
                    onBrowse={() => selectTab("novels")}
                  />
                ) : tab === 'device' && deviceView === 'exchange' && username ? (
                  <LibraryExchangeWorkspace key={username} username={username} onReadingChange={setWorkflowReading}/>
                ) : tab === "device" && deviceView === "files" && username ? (
                  <LocalWorkspace
                    key={username}
                    username={username}
                    onReadingChange={setWorkflowReading}
                    onTranslate={(document) => {
                      setWorkflowReading(false);
                      if (!workflowClient) {
                        selectTab("connection");
                        setNotice(t("문서를 번역하려면 서버에 연결해 주세요."));
                        return;
                      }
                      setUploadDocument(document);
                      setJsonCatalogOpen(false);
                      selectTab("novels");
                    }}
                  />
                ) : tab === "device" &&
                  deviceView === "originals" &&
                  username ? (
                  <OriginalLibrary
                    key={username}
                    username={username}
                    client={workflowClient}
                    defaultTargetLanguage={accountProfile?.targetLanguage ?? "ko"}
                    onReadingChange={setWorkflowReading}
                  />
                ) : tab === "server" && !client ? (
                  <section className="welcome">
                    <div className="welcome-copy">
                      <div className="welcome-kicker">
                        <span className="short-rule" />
                        <span className="eyebrow">A LITTLE SPACE TO READ</span>
                      </div>
                      <h2>
                        {t("한 페이지씩,")}
                        <br />
                        {t("당신의 속도로.")}
                      </h2>
                      <p>
                        {t("흩어져 있던 이야기를 한곳에.")}
                        <br />
                        {t("번역한 책을 꺼내고, 마지막으로 읽은")}
                        <br className="mobile-break" />
                        {t("페이지에서 다시 시작하세요.")}
                      </p>
                      <div className="welcome-actions">
                        <button
                          className="button-primary"
                          onClick={() => selectTab("connection")}
                        >
                          {t("내 서재 연결")}
                          <Icon name="arrow" />
                        </button>
                        <button className="button-text" onClick={openPreview}>
                          {t("미리보기 읽기")}
                          <Icon name="book" size={18} />
                        </button>
                      </div>
                      <span className="welcome-footnote">
                        {t("복잡한 화면 없이, 읽는 일에만 집중하세요.")}
                      </span>
                    </div>
                    <div className="welcome-art" aria-hidden="true">
                      <div className="art-top-label">
                        <span>THE READING ROOM</span>
                        <span>NO. 001</span>
                      </div>
                      <div className="illustrated-book">
                        <span className="illustrated-spine" />
                        <div className="illustrated-cover">
                          <span className="illustrated-volume">
                            P / T<br />
                            <small>COLLECTED STORIES</small>
                          </span>
                          <span className="illustrated-book-title">
                            A page.
                            <br />A pause.
                            <br />A world.
                          </span>
                          <div className="illustrated-cover-footer">
                            <span>
                              PAGE
                              <br />
                              TURNER
                            </span>
                            <Icon name="book" size={25} />
                          </div>
                        </div>
                      </div>
                      <div className="art-bottom-label">
                        <span>{t("이야기는, 다음 장에도.")}</span>
                        <span>↗</span>
                      </div>
                    </div>
                    <div className="welcome-bottom">
                      <span>{t("번역본 보관")}</span>
                      <span>{t("오프라인 읽기")}</span>
                      <span>{t("읽은 위치 기억")}</span>
                    </div>
                  </section>
                ) : tab === "server" ? (
                  <section className="library-panel">
                    <div className="library-caption">
                      <span>{t("저장한 이야기들")}</span>
                      <span>
                        {serverPage
                          ? t("{0}개의 번역본", [serverPage.totalItems])
                          : t("서버 서재")}
                      </span>
                    </div>
                    {serverState === "loading" ? (
                      <EmptyState title={t("서재를 불러오고 있습니다")}>
                        {t("잠시만 기다려 주세요.")}
                      </EmptyState>
                    ) : serverState === "error" ? (
                      <EmptyState
                        icon="link"
                        title={t("서재에 연결하지 못했습니다")}
                        action={() => void loadServer(requestedPage)}
                        actionLabel={t("다시 시도")}
                      >
                        {serverError}
                      </EmptyState>
                    ) : serverPage?.items.length ? (
                      <AdaptiveCollection
                        key={`${username}:${serverPage.page}`}
                        items={serverPage.items}
                        itemKey={(book) => book.recordId}
                        rowHeight={112}
                        offset={serverPage.page * serverPage.size}
                        total={serverPage.totalItems}
                        initialPage={batchStart}
                        onPreviousBatch={
                          serverPage.page > 0
                            ? () => void loadServer(serverPage.page - 1, "last")
                            : undefined
                        }
                        onNextBatch={
                          serverPage.hasNext
                            ? () => void loadServer(serverPage.page + 1)
                            : undefined
                        }
                        renderItem={(book, index) => (
                          <BookRow
                            book={book}
                            index={index}
                            saved={isSaved(book.recordId, book.revision)}
                            busy={busyRecord === book.recordId}
                            onOpen={() => void openBook(book.recordId)}
                            onSave={() => void saveBook(book.recordId)}
                            onDelete={() => {}}
                          />
                        )}
                      />
                    ) : (
                      <EmptyState
                        title={t("첫 이야기를 기다리는 서재")}
                        action={openPreview}
                        actionLabel={t("미리보기 읽기")}
                      >
                        {t(
                          "앱에서 번역본을 저장하면 이곳에서 이어 읽을 수 있어요.",
                        )}
                      </EmptyState>
                    )}
                  </section>
                ) : (
                  <section className="library-panel">
                    <div className="library-caption">
                      <span>
                        {username
                          ? t("{0}의 기기 보관함", [username])
                          : t("나만의 기기 보관함")}
                      </span>
                      <span>
                        {localState === "ready"
                          ? t("{0}개 보관", [localBooks.length])
                          : "OFFLINE LIBRARY"}
                      </span>
                    </div>
                    {!username ? (
                      <div className="local-account">
                        <Icon name="device" size={34} />
                        <h2>{t("이 기기에 남겨 둔 이야기")}</h2>
                        <p>
                          {t("보관할 때 사용한 계정 이름으로")}
                          <br />
                          {t("오프라인 서재를 열어 보세요.")}
                        </p>
                        <form
                          onSubmit={(event) => {
                            event.preventDefault();
                            if (formUsername.trim()) {
                              resetSession();
                              setUsername(formUsername);
                            }
                          }}
                        >
                          <label
                            className="sr-only"
                            htmlFor="local-account-name"
                          >
                            {t("보관한 계정 이름")}
                          </label>
                          <input
                            id="local-account-name"
                            value={formUsername}
                            onChange={(event) =>
                              setFormUsername(event.target.value)
                            }
                            placeholder={t("계정 이름")}
                            required
                          />
                          <button className="button-primary" type="submit">
                            {t("보관함 열기")}
                            <Icon name="arrow" />
                          </button>
                        </form>
                      </div>
                    ) : localState === "loading" ? (
                      <EmptyState
                        icon="device"
                        title={t("보관함을 열고 있습니다")}
                      >
                        {t("이 기기에 저장한 번역본을 확인하고 있어요.")}
                      </EmptyState>
                    ) : localState === "error" ? (
                      <EmptyState
                        icon="device"
                        title={t("보관함을 열지 못했습니다")}
                        action={() => void refreshLocal()}
                        actionLabel={t("다시 시도")}
                      >
                        {localError}
                      </EmptyState>
                    ) : deviceItems.length ? (
                      <AdaptiveCollection
                        items={deviceItems}
                        itemKey={(item) =>
                          item.kind === "book"
                            ? item.book.translation.recordId
                            : item.record.recoveryId
                        }
                        rowHeight={112}
                        keyboardEnabled={!deleteBook}
                        renderItem={(item, index) =>
                          item.kind === "book" ? (
                            <BookRow
                              book={item.book.translation}
                              index={index}
                              local
                              saved
                              busy={
                                busyRecord === item.book.translation.recordId
                              }
                              onOpen={() =>
                                void openBook(
                                  item.book.translation.recordId,
                                  true,
                                )
                              }
                              onSave={() => {}}
                              onDelete={(trigger) =>
                                openDeleteConfirmation(
                                  {
                                    recordId: item.book.translation.recordId,
                                    title: bookTitle(item.book.translation),
                                  },
                                  trigger,
                                )
                              }
                            />
                          ) : (
                            <article className="corrupt-book">
                              <div className="corrupt-icon">
                                <Icon name="book" size={25} />
                              </div>
                              <div className="corrupt-details">
                                <strong>{t("다시 보관이 필요한 책")}</strong>
                                <span>
                                  {t("저장한 파일을 확인할 수 없습니다.")}
                                </span>
                              </div>
                              <div className="corrupt-actions">
                                {client && item.record.recordId && (
                                  <button
                                    className="icon-button"
                                    title={t("서버에서 다시 받기")}
                                    aria-label={t(
                                      "손상된 책 서버에서 다시 받기",
                                    )}
                                    disabled={!!busyRecord}
                                    onClick={() =>
                                      void saveBook(
                                        item.record.recordId!,
                                        undefined,
                                        item.record.recoveryId,
                                      )
                                    }
                                  >
                                    <Icon name="refresh" size={18} />
                                  </button>
                                )}
                                <button
                                  className="icon-button"
                                  title={t("이 기기에서 삭제")}
                                  aria-label={t("손상된 책 이 기기에서 삭제")}
                                  disabled={!!busyRecord}
                                  onClick={(event) =>
                                    openDeleteConfirmation(
                                      {
                                        title: t("다시 보관이 필요한 책"),
                                        recoveryId: item.record.recoveryId,
                                      },
                                      event.currentTarget,
                                    )
                                  }
                                >
                                  <Icon name="trash" size={18} />
                                </button>
                              </div>
                            </article>
                          )
                        }
                      />
                    ) : (
                      <EmptyState
                        icon="device"
                        title={t("가져가고 싶은 책을 담아 보세요")}
                        action={() => selectTab("server")}
                        actionLabel={t("서버 서재 열기")}
                      >
                        {t("서재에서 보관 버튼을 누르면")}
                        <br />
                        {t("연결이 없어도 이 기기에서 읽을 수 있어요.")}
                      </EmptyState>
                    )}
                  </section>
                )}
                {tab !== "connection" &&
                  tab !== "novels" &&
                  !(tab === "device" && deviceView !== "translations") &&
                  !opening && (
                    <footer
                      className={`library-footer ${tab === "device" ? "device-footer" : ""}`}
                    >
                      <div className="footer-copy">
                        <span>
                          {tab === "device"
                            ? t("이 브라우저에 보관한 번역본만 표시됩니다.")
                            : t("나의 문장, 나의 속도.")}
                        </span>
                        {tab === "device" && (
                          <span className="shell-status" role="status">
                            {shellLabel}
                          </span>
                        )}
                      </div>
                      {tab === "device" && username ? (
                        <button
                          className="button-text preview-footer-button"
                          onClick={() => {
                            resetSession();
                            setUsername("");
                            setFormUsername("");
                            setConnecting(false);
                          }}
                        >
                          {t("보관함 바꾸기")}
                          <Icon name="arrow" size={15} />
                        </button>
                      ) : (
                        <button
                          className="button-text preview-footer-button"
                          onClick={openPreview}
                          title={demoTitle}
                        >
                          {t("미리보기 읽기")}
                          <Icon name="arrow" size={15} />
                        </button>
                      )}
                    </footer>
                  )}
              </>
            )}
          </main>
        </div>
        {deleteBook && (
          <div className="dialog-backdrop">
            <section
              ref={dialog}
              className="confirm-dialog"
              role="dialog"
              aria-modal="true"
              aria-labelledby="delete-title"
              tabIndex={-1}
            >
              <span className="eyebrow">ON THIS DEVICE</span>
              <h2 id="delete-title">{t("이 기기에서 삭제할까요?")}</h2>
              <p className="delete-book-name">{deleteBook.title}</p>
              <p>
                {t("서버의 번역본은 그대로 있습니다.")}
                <br />
                {t("필요할 때 다시 보관할 수 있어요.")}
              </p>
              <div className="dialog-actions">
                <button
                  className="button-outline"
                  onClick={() => setDeleteBook(null)}
                  disabled={!!busyRecord}
                  autoFocus
                >
                  {t("취소")}
                </button>
                <button
                  className="button-primary"
                  onClick={() => void removeBook()}
                  disabled={!!busyRecord}
                >
                  {busyRecord ? t("삭제 중\u2026") : t("기기에서 삭제")}
                </button>
              </div>
            </section>
          </div>
        )}
      </div>
    </ReadingNoteProvider>
    </ReadingProgressProvider>
    </ReaderPreferencesProvider>
  );
}
