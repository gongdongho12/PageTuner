import { translate as t } from "../lib/locale";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { type GlossaryEntry } from "../lib/personalLibrary";
import { GlossaryEditor } from "./GlossaryEditor";
import { usePersonalLibrary } from "./usePersonalLibrary";
import type {
  StartTranslation,
  StoredChapter,
  TranslationJob,
  TranslationProvider,
  ProviderKind,
} from "../lib/workflowTypes";
import { Icon } from "./Icon";
export function TranslationSetup({
  chapter,
  providers,
  retry,
  busy,
  onSubmit,
  onBack,
  onReadOriginal,
  initialSettings,
  readingPreview = false,
  defaultTargetLanguage,
  username,
}: {
  chapter: StoredChapter;
  providers: TranslationProvider[];
  retry?: TranslationJob;
  busy: boolean;
  onSubmit: (input: StartTranslation) => Promise<void>;
  onBack: () => void;
  onReadOriginal?: () => void;
  initialSettings?: Partial<StartTranslation>;
  readingPreview?: boolean;
  defaultTargetLanguage: string;
  username: string;
}) {
  const [section, setSection] = useState<"basic" | "connection" | "glossary">(
    "basic",
  );
  const personal = usePersonalLibrary(username)!;
  const [glossary, setGlossary] = useState<GlossaryEntry[]>(
    retry?.settings.glossary ?? [],
  );
  const [glossaryReady, setGlossaryReady] = useState(!!retry);
  useEffect(() => {
    let active = true;
    if (!retry)
      void personal
        .getGlossary(chapter.providerId, chapter.bookId)
        .then((value) => {
          if (active) {
            setGlossary(value.entries);
            setGlossaryReady(true);
          }
        })
        .catch((error) => {
          if (active)
            setValidation(error instanceof Error ? error.message : "");
        });
    return () => {
      active = false;
    };
  }, [personal, chapter.providerId, chapter.bookId, retry]);
  const [kind, setKind] = useState<ProviderKind>(
    retry?.providerKind ?? initialSettings?.providerKind ?? "GOOGLE_WEB_TRANSLATE_HTML",
  );
  const [target, setTarget] = useState(
    retry?.settings.targetLanguage ?? initialSettings?.targetLanguage ?? defaultTargetLanguage,
  );
  const [source, setSource] = useState(
    retry?.settings.sourceLanguage ?? initialSettings?.sourceLanguage ?? chapter.sourceLanguage,
  );
  const [apiKey, setApiKey] = useState(initialSettings?.apiKey ?? "");
  const [endpoint, setEndpoint] = useState(retry?.settings.endpoint ?? initialSettings?.endpoint ?? "");
  const [model, setModel] = useState(retry?.settings.model ?? initialSettings?.model ?? "");
  const [validation, setValidation] = useState("");
  const submitted = useRef<{
    signature: string;
    id: string;
  } | null>(null);
  const selected = providers.find((p) => p.id === kind);
  const needsKey = selected?.requiresKey && !selected.configured;
  const advanced = kind === "DEEPSEEK" || kind === "OPENAI_COMPATIBLE_LLM";
  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (busy || !glossaryReady) return;
    if (!selected || !target.trim() || !source.trim()) {
      setValidation(t("번역기와 원문\u00B7대상 언어를 선택해 주세요."));
      return;
    }
    const language = /^[A-Za-z][A-Za-z0-9-]{0,23}$/;
    if (
      !language.test(source.trim()) ||
      !language.test(target.trim()) ||
      target.trim().toLowerCase() === "auto" ||
      source.trim().toLowerCase() === target.trim().toLowerCase()
    ) {
      setValidation(
        t(
          "서로 다른 원문·번역 언어를 입력해 주세요. 자동 감지는 원문에만 사용할 수 있습니다.",
        ),
      );
      setSection("basic");
      return;
    }
    if (needsKey && !apiKey.trim()) {
      setValidation(t("이 번역기의 API 키를 입력해 주세요."));
      setSection("connection");
      return;
    }
    const options = {
      chapterRecordId: chapter.recordId,
      providerKind: kind,
      targetLanguage: target.trim(),
      sourceLanguage: source.trim(),
      glossary,
      ...(apiKey ? { apiKey } : {}),
      ...(endpoint.trim() ? { endpoint: endpoint.trim() } : {}),
      ...(model.trim() ? { model: model.trim() } : {}),
      ...(retry
        ? { retryOf: retry.jobId, glossary: retry.settings.glossary }
        : {}),
    };
    // Reuse the id after an uncertain transport failure; editing settings creates a distinct request.
    const signature = JSON.stringify(options);
    if (submitted.current?.signature !== signature)
      submitted.current = { signature, id: crypto.randomUUID() };
    setValidation("");
    await onSubmit({ ...options, idempotencyKey: submitted.current.id });
  };
  if (section === "glossary")
    return (
      <section className="novel-workspace">
        <div className="workflow-heading">
          <button className="button-quiet" onClick={() => setSection("basic")}>
            {t("번역 설정으로")}
          </button>
          <h2>{t("책별 용어집")}</h2>
        </div>
        <GlossaryEditor
          storage={personal}
          providerId={chapter.providerId}
          bookId={chapter.bookId}
          onChange={(entries) => {
            setGlossary(entries);
            setGlossaryReady(true);
          }}
        />
      </section>
    );
  return (
    <form className="workflow-form" onSubmit={(event) => void submit(event)}>
      <div className="workflow-heading">
        <button
          type="button"
          className="icon-button"
          onClick={onBack}
          disabled={busy}
          aria-label={t("번역 설정 닫기")}
        >
          <Icon name="back" />
        </button>
        <div>
          <span className="eyebrow">TRANSLATION SETTINGS</span>
          <h2>{readingPreview ? t("읽기 번역 설정") : retry ? t("번역 다시 시도") : t("번역 준비")}</h2>
        </div>
      </div>
      <p className="workflow-book-name" title={chapter.chapterTitle}>
        {chapter.chapterTitle}
      </p>
      <div className="workflow-subtabs" aria-label={t("번역 설정 항목")}>
        <button
          type="button"
          aria-pressed={section === "basic"}
          onClick={() => setSection("basic")}
        >
          {t("번역기\u00B7언어")}
        </button>
        <button
          type="button"
          aria-pressed={section === "connection"}
          onClick={() => setSection("connection")}
        >
          {t("연결 설정")}
        </button>
        <button
          type="button"
          aria-pressed={false}
          disabled={!!retry || busy}
          onClick={() => setSection("glossary")}
        >
          {t("용어집")}
        </button>
      </div>
      <div className="workflow-fields">
        {section === "basic" ? (
          <>
            <label>
              {t("번역기")}
              <select
                value={kind}
                disabled={busy || !!retry}
                onChange={(event) => {
                  setKind(event.target.value as ProviderKind);
                  setApiKey("");
                  setEndpoint("");
                  setModel("");
                  submitted.current = null;
                }}
              >
                {providers.map((provider) => (
                  <option key={provider.id} value={provider.id}>
                    {t(provider.displayName)}
                  </option>
                ))}
              </select>
            </label>
            <div className="workflow-field-pair">
              <label>
                {t("원문 언어")}
                <input
                  value={source}
                  onChange={(e) => setSource(e.target.value)}
                  placeholder="auto, en, zh"
                  maxLength={16}
                  disabled={busy || !!retry}
                />
              </label>
              <label>
                {t("번역 언어")}
                <select
                  value={target}
                  onChange={(e) => setTarget(e.target.value)}
                  disabled={busy || !!retry}
                >
                  <option value="ko">{t("한국어")}</option>
                  <option value="en">English</option>
                  <option value="ja">日本語</option>
                  <option value="zh">中文</option>
                  {!["ko", "en", "ja", "zh"].includes(target) && (
                    <option value={target}>{target}</option>
                  )}
                </select>
              </label>
            </div>
            <p className="workflow-help">
              {needsKey
                ? t("연결 설정에서 API 키를 입력해 주세요.")
                : selected?.configured
                  ? t("서버에 준비된 번역기를 사용합니다.")
                  : t("선택한 번역기로 요청을 보냅니다.")}{" "}
              {readingPreview
                ? t("현재 쪽부터 읽는 속도에 맞춰 번역합니다. 결과는 이번 읽기에만 표시되며 전체 번역으로 저장되지 않습니다.")
                : <>{t("원문")}{chapter.paragraphs.length}{t("개 문단을 번역합니다.")}</>}
              {retry &&
                t(
                  " 이전 작업의 설정과 용어집 {0}개를 유지해 이어서 번역합니다.",
                  [retry.settings.glossary.length],
                )}
            </p>
          </>
        ) : (
          <>
            <label>
              {t("API 키")}
              {selected?.configured ? t("(선택)") : ""}
              <input
                type="password"
                autoComplete="off"
                value={apiKey}
                onChange={(e) => setApiKey(e.target.value)}
                disabled={busy || !selected?.requiresKey}
                placeholder={
                  selected?.requiresKey
                    ? t("이번 번역에 사용할 키")
                    : t("이 번역기는 키가 필요하지 않습니다")
                }
              />
            </label>
            {advanced && (
              <div className="workflow-field-pair">
                <label>
                  {t("서버 주소")}
                  <input
                    type="url"
                    value={endpoint}
                    onChange={(e) => setEndpoint(e.target.value)}
                    placeholder={
                      selected?.defaultEndpoint || t("기본 주소 사용")
                    }
                    disabled={busy || !!retry}
                  />
                </label>
                <label>
                  {t("모델")}
                  <input
                    value={model}
                    onChange={(e) => setModel(e.target.value)}
                    placeholder={selected?.defaultModel || t("기본 모델 사용")}
                    disabled={busy || !!retry}
                  />
                </label>
              </div>
            )}
            <p className="workflow-help">
              {t(
                "API 키는 이번 작업을 위해 서버에 전달하며 이 기기에는 저장하지 않습니다.",
              )}
            </p>
          </>
        )}
      </div>
      {validation && (
        <p className="workflow-message" role="alert">
          {t(validation)}
          {!glossaryReady && (
            <button
              type="button"
              className="button-text"
              onClick={() => {
                setGlossary([]);
                setGlossaryReady(true);
                setValidation("이번 번역은 용어집 없이 진행합니다.");
              }}
            >
              {t("용어집 없이 진행")}
            </button>
          )}
        </p>
      )}
      <div className="workflow-form-actions">
        {onReadOriginal && <button type="button" className="button-outline" disabled={busy} onClick={onReadOriginal}>
          {t("원문 열고 읽으며 번역")}
        </button>}
        <button
          className="button-primary"
          disabled={busy || !selected || !glossaryReady}
        >
          {busy
            ? t("번역 요청 중\u2026")
            : readingPreview
              ? t("현재 쪽 번역")
              : retry
              ? t("다시 번역하기")
              : t("번역 시작")}
          <Icon name="arrow" />
        </button>
      </div>
    </form>
  );
}
