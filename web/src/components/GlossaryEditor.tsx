import { useEffect, useState } from "react";
import {
  exportGlossary,
  parseGlossaryFile,
  mergeGlossaryEntries,
  type GlossaryEntry,
  type PersonalGlossary,
  type PersonalLibrary,
} from "../lib/personalLibrary";
import { translate as t } from "../lib/locale";
import { AdaptiveCollection } from "./AdaptiveCollection";

export function GlossaryEditor({
  storage,
  providerId,
  bookId,
  onChange,
}: {
  storage: PersonalLibrary;
  providerId: string;
  bookId: string;
  onChange: (entries: GlossaryEntry[]) => void;
}) {
  const [glossary, setGlossary] = useState<PersonalGlossary>({
      entries: [],
      revision: "",
      updatedAt: "",
    }),
    [tab, setTab] = useState<"list" | "edit" | "file">("list"),
    [source, setSource] = useState(""),
    [target, setTarget] = useState(""),
    [displayTerm, setDisplayTerm] = useState(''),
    [kind, setKind] = useState<NonNullable<GlossaryEntry['kind']>>('Character'),
    [caseSensitive, setCaseSensitive] = useState(false),
    [enabled, setEnabled] = useState(true),
    [displaySettings, setDisplaySettings] = useState(false),
    [editing, setEditing] = useState<string>(),
    [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  useEffect(() => {
    let active = true;
    setBusy(true);
    void storage
      .getGlossary(providerId, bookId)
      .then((value) => {
        if (active) {
          setGlossary(value);
          onChange(value.entries);
        }
      })
      .catch((e) => {
        if (active) setError(e instanceof Error ? e.message : "");
      })
      .finally(() => {
        if (active) setBusy(false);
      });
    return () => {
      active = false;
    };
  }, [storage, providerId, bookId]);
  const save = async (entries: GlossaryEntry[]) => {
    setBusy(true);
    setError("");
    try {
      const next = await storage.saveGlossary(providerId, bookId, entries);
      setGlossary(next);
      onChange(next.entries);
      setTab("list");
      setSource("");
      setTarget("");
      setEditing(undefined);
    } catch (e) {
      setError(e instanceof Error ? e.message : "");
    } finally {
      setBusy(false);
    }
  };
  const download = () => {
    try {
    const url = URL.createObjectURL(
      new Blob([exportGlossary(glossary, bookId)], { type: "application/json" }),
    );
    const a = document.createElement("a");
    a.href = url;
    a.download = "pageturner-glossary.json";
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 0);
    } catch (error) { setError(error instanceof Error ? error.message : ''); }
  };
  return (
    <section className="novel-workspace">
      <nav className="workflow-subtabs">
        <button
          disabled={busy}
          aria-pressed={tab === "list"}
          onClick={() => setTab("list")}
        >
          {t("용어 목록")}
        </button>
        <button
          aria-pressed={tab === "edit"}
          disabled={busy}
          onClick={() => {
            setEditing(undefined);
            setSource("");
            setTarget("");
            setDisplayTerm(''); setKind('Character'); setCaseSensitive(false); setEnabled(true); setDisplaySettings(false);
            setTab("edit");
          }}
        >
          {t("용어 추가")}
        </button>
        <button
          disabled={busy}
          aria-pressed={tab === "file"}
          onClick={() => setTab("file")}
        >
          {t("가져오기·내보내기")}
        </button>
      </nav>
      {error && (
        <div className="workflow-message" role="alert">
          {t(error)}
        </div>
      )}
      {tab === "edit" ? (
        <form
          className="workflow-form"
          onSubmit={(e) => {
            e.preventDefault();
            void save([
              ...glossary.entries.filter((entry) => entry.source !== editing),
              { source, target, displayTerm, kind, caseSensitive, enabled },
            ]);
          }}
        >
          <div className="workflow-subtabs"><button type="button" aria-pressed={!displaySettings} onClick={() => setDisplaySettings(false)}>{t('번역 용어')}</button><button type="button" aria-pressed={displaySettings} onClick={() => setDisplaySettings(true)}>{t('표시·적용 설정')}</button></div>
          <div className="workflow-fields">{!displaySettings ? <>
            <label>
              {t("원문 용어")}
              <input
                value={source}
                onChange={(e) => setSource(e.target.value)}
                maxLength={200}
                disabled={busy}
              />
            </label>
            <label>
              {t("번역 표기")}
              <input
                value={target}
                onChange={(e) => setTarget(e.target.value)}
                maxLength={200}
                disabled={busy}
              />
            </label>
            </> : <>
              <label>{t('용어 종류')}<select value={kind} disabled={busy} onChange={event => setKind(event.target.value as NonNullable<GlossaryEntry['kind']>)}>
                <option value="Character">{t('인물')}</option><option value="Place">{t('장소')}</option><option value="Term">{t('일반 용어')}</option>
              </select></label>
              <label>{t('읽기 표시 이름')}<input value={displayTerm} maxLength={200} disabled={busy} onChange={event => setDisplayTerm(event.target.value)} /></label>
              <div className="workflow-subtabs"><button type="button" disabled={busy} aria-pressed={enabled} onClick={() => setEnabled(value => !value)}>{t(enabled ? '용어 적용 켜짐' : '용어 적용 꺼짐')}</button>
                <button type="button" disabled={busy} aria-pressed={caseSensitive} onClick={() => setCaseSensitive(value => !value)}>{t(caseSensitive ? '대소문자 구분' : '대소문자 무시')}</button></div>
            </>}
            <p className="workflow-help">
              {t("한 책에 최대 200개 용어를 저장하고 다음 번역에 적용합니다.")}
              {' '}{t('표시 이름은 저장된 번역을 바꾸지 않고 읽기 화면에만 적용됩니다.')}
            </p>
          </div>
          <div className="workflow-form-actions">
            <button className="button-primary" disabled={busy}>
              {t(busy ? "저장 중…" : "저장")}
            </button>
          </div>
        </form>
      ) : tab === "file" ? (
        <div className="workflow-about">
          <label className="workflow-help">
            {t("JSON 용어집 가져오기")}
            <input
              type="file"
              accept=".json,application/json"
              disabled={busy}
              onChange={(e) => {
                const file = e.target.files?.[0];
                e.target.value = "";
                if (!file) return;
                if (file.size > 256 * 1024) {
                  setError("용어집 파일은 256KB 이하로 선택해 주세요.");
                  return;
                }
                void file
                  .text()
                  .then((text) => save(mergeGlossaryEntries(glossary.entries, parseGlossaryFile(text))))
                  .catch((error) =>
                    setError(error instanceof Error ? error.message : ""),
                  );
              }}
            />
          </label>
          <p>
            {t(
              "앱과 공유하는 JSON 용어집입니다. 기존 용어를 유지하고 새로운 원문 용어만 추가합니다.",
            )}
          </p>
          <button className="button-outline" disabled={busy} onClick={download}>
            {t("용어집 내보내기")}
          </button>
        </div>
      ) : glossary.entries.length ? (
        <AdaptiveCollection
          key={glossary.updatedAt}
          items={glossary.entries}
          itemKey={(entry) => entry.source}
          rowHeight={110}
          renderItem={(entry) => (
            <div className="workflow-row">
              <button
                className="workflow-row-copy button-quiet"
                onClick={() => {
                  setSource(entry.source);
                  setTarget(entry.target);
                  setDisplayTerm(entry.displayTerm ?? ''); setKind(entry.kind ?? 'Character'); setCaseSensitive(entry.caseSensitive ?? false); setEnabled(entry.enabled ?? true); setDisplaySettings(false);
                  setEditing(entry.source);
                  setTab("edit");
                }}
              >
                <strong>{entry.source}</strong>
                <span>{entry.target}</span>
                <span>{t((entry.kind ?? 'Character') === 'Character' ? '인물' : entry.kind === 'Place' ? '장소' : '일반 용어')} · {t(entry.enabled === false ? '용어 적용 꺼짐' : '용어 적용 켜짐')}{entry.displayTerm ? ` · ${entry.displayTerm}` : ''}</span>
              </button>
              <button
                className="button-quiet"
                disabled={busy}
                onClick={() =>
                  void save(
                    glossary.entries.filter((e) => e.source !== entry.source),
                  )
                }
              >
                {t("삭제")}
              </button>
            </div>
          )}
        />
      ) : (
        <div className="workflow-feedback">
          <p>{t("아직 저장한 용어가 없습니다.")}</p>
          <button className="button-outline" onClick={() => setTab("edit")}>
            {t("용어 추가")}
          </button>
        </div>
      )}
    </section>
  );
}
