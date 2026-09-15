import { useEffect, useMemo, useState } from "react";
import { type SavedOriginal } from "../lib/personalLibrary";
import { createReadingNotes } from "../lib/readingNotes";
import { translate as t } from "../lib/locale";
import type { ReadingAnchor } from "../lib/offline";
import type { ReadingDocument } from "../lib/readingDocument";
import { AdaptiveCollection } from "./AdaptiveCollection";
import { PagedReader } from "./PagedReader";
import { usePersonalLibrary } from "./usePersonalLibrary";
import {
  getWorkflowPosition,
  setWorkflowPosition,
} from "../lib/workflowPosition";

export function OriginalLibrary({
  username,
  onReadingChange,
}: {
  username: string;
  onReadingChange: (reading: boolean) => void;
}) {
  const storage = usePersonalLibrary(username)!,
    notes = useMemo(() => createReadingNotes(username), [username]);
  const [books, setBooks] = useState<SavedOriginal[]>([]),
    [damaged, setDamaged] = useState<string[]>([]),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false),
    [remove, setRemove] = useState<string>();
  const [reading, setReading] = useState<{
    document: ReadingDocument;
    anchor?: ReadingAnchor;
  }>();
  const refresh = async () => {
    setBusy(true);
    setBooks([]);
    setDamaged([]);
    try {
      const result = await storage.originals();
      setBooks(result.books);
      setDamaged(result.damagedIds);
      setError("");
    } catch (e) {
      setError(e instanceof Error ? e.message : "");
    } finally {
      setBusy(false);
    }
  };
  useEffect(() => {
    void refresh();
  }, [storage]);
  useEffect(() => {
    onReadingChange(!!reading);
    return () => onReadingChange(false);
  }, [!!reading, onReadingChange]);
  const open = async (saved: SavedOriginal) => {
    const c = saved.chapter;
    const document: ReadingDocument = {
      id: `original:${c.recordId}:${c.sourceRevision}`,
      kind: "original",
      bookTitle: c.bookTitle,
      chapterTitle: c.chapterTitle,
      language: c.sourceLanguage,
      paragraphs: c.paragraphs,
    };
    try {
      setReading({
        document,
        anchor:
          getWorkflowPosition(username, document) ??
          (await notes.getPosition(document)),
      });
    } catch (e) {
      setError(e instanceof Error ? e.message : "");
      setReading({ document });
    }
  };
  if (reading)
    return (
      <PagedReader
        document={reading.document}
        anchor={reading.anchor}
        notesNamespace={username}
        onClose={() => setReading(undefined)}
        onAnchorChange={(anchor) => {
          try {
            setWorkflowPosition(username, reading.document, anchor);
          } catch {
            /* The IndexedDB progress store remains available. */
          }
          void notes
            .setPosition(reading.document, anchor)
            .catch((e) => setError(e instanceof Error ? e.message : ""));
        }}
        positionNote={
          error
            ? t(error)
            : t("기기에 보관한 원문입니다. 연결 없이 읽을 수 있습니다.")
        }
      />
    );
  return (
    <section className="novel-workspace">
      <div className="workflow-heading">
        <div>
          <span className="eyebrow">OFFLINE ORIGINALS</span>
          <h2>{t("기기에 보관한 원문")}</h2>
        </div>
        <button
          className="button-outline"
          onClick={() => void refresh()}
          disabled={busy}
        >
          {t("새로고침")}
        </button>
      </div>
      {error && (
        <div className="workflow-message" role="alert">
          {t(error)}
        </div>
      )}
      {remove ? (
        <div className="workflow-about">
          <h3>{t("이 기기에서 원문을 삭제할까요?")}</h3>
          <p>{t("서버에 보관한 원문은 그대로 유지됩니다.")}</p>
          <div className="workflow-job-actions">
            <button
              className="button-outline"
              disabled={busy}
              onClick={() => setRemove(undefined)}
            >
              {t("취소")}
            </button>
            <button
              className="button-primary"
              disabled={busy}
              onClick={() => {
                setBusy(true);
                void storage
                  .removeOriginal(remove)
                  .then(() => {
                    setRemove(undefined);
                    return refresh();
                  })
                  .catch((e) => {
                    setError(e instanceof Error ? e.message : "");
                    setBusy(false);
                  });
              }}
            >
              {t("삭제")}
            </button>
          </div>
        </div>
      ) : (
        <>
          {damaged.length > 0 && (
            <div className="workflow-message">
              {t("다시 보관해야 하는 원문 {0}개가 있습니다.", [damaged.length])}
              <button
                className="button-text"
                onClick={() => setRemove(damaged[0])}
              >
                {t("손상된 항목 삭제")}
              </button>
            </div>
          )}
          {busy ? (
            <div className="workflow-feedback">
              <p>{t("보관함을 열고 있습니다")}</p>
            </div>
          ) : books.length ? (
            <AdaptiveCollection
              items={books}
              itemKey={(book) => book.chapter.recordId}
              rowHeight={112}
              renderItem={(book) => (
                <div className="workflow-row">
                  <button
                    className="workflow-row-copy button-quiet"
                    onClick={() => void open(book)}
                  >
                    <span>{book.chapter.bookTitle}</span>
                    <strong>{book.chapter.chapterTitle}</strong>
                    <span>
                      {book.chapter.sourceLanguage.toUpperCase()} · {t("원문")}
                    </span>
                  </button>
                  <button
                    className="button-quiet"
                    onClick={() => setRemove(book.chapter.recordId)}
                  >
                    {t("삭제")}
                  </button>
                </div>
              )}
            />
          ) : (
            <div className="workflow-feedback">
              <h2>{t("아직 기기에 보관한 원문이 없습니다.")}</h2>
              <p>{t("웹소설 원문 리더에서 기기에 보관을 눌러 주세요.")}</p>
            </div>
          )}
        </>
      )}
    </section>
  );
}
