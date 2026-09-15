import { useEffect, useRef, useState } from "react";
import type { FavoriteBook, PersonalLibrary } from "../lib/personalLibrary";
import type { NovelSource } from "../lib/workflowTypes";
import { translate as t } from "../lib/locale";
import { AdaptiveCollection } from "./AdaptiveCollection";
export function PersonalShelf({
  storage,
  sources,
  onOpenBook,
  onOpenSource,
}: {
  storage: PersonalLibrary;
  sources: NovelSource[];
  onOpenBook: (url: string) => void;
  onOpenSource: (sourceId: string, url: string) => void;
}) {
  const [mode, setMode] = useState<"books" | "sources" | "add">("books"),
    [items, setItems] = useState<FavoriteBook[]>([]),
    [damaged, setDamaged] = useState<string[]>([]),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false);
  const [sourceId, setSourceId] = useState(sources[0]?.id ?? ""),
    [title, setTitle] = useState(""),
    [url, setUrl] = useState("");
  const requestVersion = useRef(0);
  const refresh = async (next: typeof mode = mode) => {
    const version = ++requestVersion.current;
    setBusy(true);
    setItems([]);
    setDamaged([]);
    try {
      const value = await (next === "books"
        ? storage.favorites()
        : storage.sources());
      if (version !== requestVersion.current) return;
      setItems(value.books);
      setDamaged(value.damagedIds);
      setError("");
    } catch (e) {
      if (version === requestVersion.current)
        setError(e instanceof Error ? e.message : "");
    } finally {
      if (version === requestVersion.current) setBusy(false);
    }
  };
  useEffect(() => {
    if (mode !== "add") void refresh();
    return () => {
      requestVersion.current++;
    };
  }, [storage, mode]);
  useEffect(() => {
    if (!sourceId && sources[0]) setSourceId(sources[0].id);
  }, [sources, sourceId]);
  return (
    <section className="novel-workspace">
      <nav className="workflow-subtabs">
        <button
          aria-pressed={mode === "books"}
          disabled={busy}
          onClick={() => setMode("books")}
        >
          {t("즐겨찾는 책")}
        </button>
        <button
          aria-pressed={mode === "sources"}
          disabled={busy}
          onClick={() => setMode("sources")}
        >
          {t("저장한 소스")}
        </button>
        <button
          disabled={busy}
          aria-pressed={mode === "add"}
          onClick={() => setMode("add")}
        >
          {t("소스 주소 추가")}
        </button>
      </nav>
      {error && (
        <div className="workflow-message" role="alert">
          {t(error)}
        </div>
      )}
      {mode === "add" ? (
        <form
          className="workflow-form"
          onSubmit={(e) => {
            e.preventDefault();
            setBusy(true);
            void storage
              .saveSource({ sourceId, title, url })
              .then(() => {
                setMode("sources");
                setTitle("");
                setUrl("");
              })
              .catch((error) =>
                setError(error instanceof Error ? error.message : ""),
              )
              .finally(() => setBusy(false));
          }}
        >
          <div className="workflow-fields">
            <label>
              {t("소스")}
              <select
                value={sourceId}
                onChange={(e) => setSourceId(e.target.value)}
                disabled={busy}
              >
                {sources.map((source) => (
                  <option key={source.id} value={source.id}>
                    {source.displayName}
                  </option>
                ))}
              </select>
            </label>
            <label>
              {t("이름")}
              <input
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                maxLength={100}
                required
                disabled={busy}
              />
            </label>
            <label>
              {t("목록 주소")}
              <input
                type="url"
                value={url}
                onChange={(e) => setUrl(e.target.value)}
                required
                disabled={busy}
              />
            </label>
          </div>
          <div className="workflow-form-actions">
            <button className="button-primary" disabled={busy || !sourceId}>
              {t("저장")}
            </button>
          </div>
        </form>
      ) : (
        <>
          {damaged.length > 0 && (
            <div className="workflow-message">
              {t("손상된 항목이 있습니다.")}
              <button
                className="button-text"
                onClick={() =>
                  void storage
                    .removeDamaged(
                      mode === "books" ? "favorite" : "source",
                      damaged[0],
                    )
                    .then(() => refresh())
                    .catch((e) => setError(e instanceof Error ? e.message : ""))
                }
              >
                {t("손상된 항목 삭제")}
              </button>
            </div>
          )}
          {busy ? (
            <div className="workflow-feedback">
              <p>{t("보관함을 열고 있습니다")}</p>
            </div>
          ) : items.length ? (
            <AdaptiveCollection
              items={items}
              itemKey={(item) => `${item.sourceId}:${item.bookId}`}
              rowHeight={104}
              renderItem={(item) => (
                <div className="workflow-row">
                  <button
                    className="workflow-row-copy button-quiet"
                    onClick={() =>
                      mode === "books"
                        ? onOpenBook(item.url)
                        : onOpenSource(item.sourceId, item.url)
                    }
                  >
                    <strong>{item.title}</strong>
                    <span>{item.sourceId}</span>
                  </button>
                  <button
                    className="button-quiet"
                    onClick={() => {
                      setBusy(true);
                      void (
                        mode === "books"
                          ? storage.removeFavorite(item.sourceId, item.bookId)
                          : storage.removeSource(item.sourceId, item.url)
                      )
                        .then(() => refresh())
                        .catch((e) => {
                          setError(e instanceof Error ? e.message : "");
                          setBusy(false);
                        });
                    }}
                  >
                    {t("삭제")}
                  </button>
                </div>
              )}
            />
          ) : (
            <div className="workflow-feedback">
              <p>
                {t(
                  mode === "books"
                    ? "책 소개에서 즐겨찾기를 추가해 보세요."
                    : "자주 여는 소스의 목록 주소를 저장해 보세요.",
                )}
              </p>
            </div>
          )}
        </>
      )}
    </section>
  );
}
