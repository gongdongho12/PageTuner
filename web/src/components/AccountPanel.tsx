import { useEffect, useRef, useState } from "react";
import {
  createAccountClient,
  validateRegistration,
  type AccountClient,
  type AccountProfile,
  type AccountLanguages,
} from "../lib/accountApi";
import { ApiError } from "../lib/errors";
import { useLocale, effectiveLocale } from "../lib/locale";
import { Icon } from "./Icon";

function LanguageField({
  label,
  value,
  onChange,
  languages,
  disabled,
  target = false,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  languages: AccountLanguages | null;
  disabled: boolean;
  target?: boolean;
}) {
  const { t } = useLocale();
  const listed = target
    ? [
        { tag: "ko", nativeName: "한국어" },
        { tag: "en", nativeName: "English" },
        { tag: "ja", nativeName: "日本語" },
        { tag: "zh", nativeName: "中文" },
      ]
    : (languages?.items ?? [
        { tag: "ko", nativeName: "한국어" },
        { tag: "en", nativeName: "English" },
      ]);
  const [custom, setCustom] = useState(
    !listed.some((item) => item.tag === value),
  );
  return (
    <label>
      {t(label)}
      <select
        disabled={disabled}
        value={custom ? "__custom" : value}
        onChange={(e) => {
          setCustom(e.target.value === "__custom");
          if (e.target.value !== "__custom") onChange(e.target.value);
        }}
      >
        {listed.map((item) => (
          <option key={item.tag} value={item.tag}>
            {item.nativeName}
          </option>
        ))}
        <option value="__custom">{t("다른 언어 태그")}</option>
      </select>
      {custom && (
        <input
          aria-label={t("언어 태그")}
          maxLength={target ? 24 : 35}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          placeholder="fr, de, pt-BR…"
          disabled={disabled}
        />
      )}
    </label>
  );
}

export function AccountPanel({
  client,
  profile,
  connecting,
  connectionError,
  initialUsername,
  onLogin,
  onProfile,
  onDisconnect,
  onBrowse,
}: {
  client: AccountClient | null;
  profile: AccountProfile | null;
  connecting: boolean;
  connectionError: string;
  initialUsername: string;
  onLogin: (credentials: {
    username: string;
    password: string;
  }) => Promise<boolean>;
  onProfile: (profile: AccountProfile) => void;
  onDisconnect: () => void;
  onBrowse: () => void;
}) {
  const { t, locale, setLocale } = useLocale();
  const [mode, setMode] = useState<"login" | "register">("login"),
    [step, setStep] = useState(0);
  const [username, setUsername] = useState(initialUsername),
    [password, setPassword] = useState(""),
    [displayName, setDisplayName] = useState(profile?.displayName ?? "");
  const [preferredLocale, setPreferredLocale] = useState(
      profile?.locale ?? locale,
    ),
    [target, setTarget] = useState(profile?.targetLanguage ?? "ko");
  const [languages, setLanguages] = useState<AccountLanguages | null>(null),
    [error, setError] = useState(""),
    [notice, setNotice] = useState(""),
    [busy, setBusy] = useState(false);
  const request = useRef<AbortController | null>(null),
    publicClient = useRef<AccountClient | null>(null);
  useEffect(() => {
    const api = createAccountClient();
    publicClient.current = api;
    const controller = new AbortController();
    void api
      .languages(controller.signal)
      .then(setLanguages)
      .catch((failure) => {
        if (!controller.signal.aborted)
          setError(
            failure instanceof ApiError
              ? failure.message
              : t("언어 목록을 불러오지 못했습니다."),
          );
      });
    return () => {
      controller.abort();
      request.current?.abort();
      api.close();
    };
  }, []);
  useEffect(() => {
    if (profile) {
      setDisplayName(profile.displayName);
      setPreferredLocale(profile.locale);
      setTarget(profile.targetLanguage);
      setPassword("");
    }
  }, [profile]);
  const submit = async () => {
    if (busy || connecting) return;
    setError("");
    setNotice("");
    if (!profile && mode === "register" && step === 0) {
      try {
        validateRegistration({
          username,
          password,
          displayName,
          locale: preferredLocale,
          targetLanguage: target,
        });
        setStep(1);
      } catch (failure) {
        setError(failure instanceof Error ? failure.message : "");
      }
      return;
    }
    const controller = new AbortController();
    request.current = controller;
    setBusy(true);
    try {
      if (profile && client) {
        const updated = await client.update(
          { displayName, locale: preferredLocale, targetLanguage: target },
          controller.signal,
        );
        if (!controller.signal.aborted) {
          onProfile(updated);
          setLocale(updated.locale);
          setNotice("계정 설정을 저장했습니다.");
        }
      } else if (mode === "register" && publicClient.current) {
        const created = await publicClient.current.register(
          {
            username,
            password,
            displayName,
            locale: preferredLocale,
            targetLanguage: target,
          },
          controller.signal,
        );
        if (controller.signal.aborted) return;
        setUsername(created.username);
        setLocale(created.locale);
        const connected = await onLogin({
          username: created.username,
          password,
        });
        setPassword("");
        if (!connected) {
          setMode("login");
          setStep(0);
          setNotice("계정이 만들어졌습니다. 로그인해 주세요.");
        }
      } else {
        const connected = await onLogin({
          username: username.trim().toLowerCase(),
          password,
        });
        if (connected) setPassword("");
      }
    } catch (failure) {
      if (!controller.signal.aborted)
        setError(failure instanceof Error ? failure.message : "");
    } finally {
      if (!controller.signal.aborted) setBusy(false);
    }
  };
  const disabled = busy || connecting;
  return (
    <section className="account-view">
      <div className="account-intro">
        <span className="eyebrow">YOUR PERSONAL READING SPACE</span>
        <h2>
          {t(
            profile
              ? "계정 설정"
              : mode === "register"
                ? "계정 만들기"
                : "서버 연결",
          )}
        </h2>
        <p>{t("화면 언어와 번역 결과의 언어는 별도로 설정합니다.")}</p>
      </div>
      <div className="account-card">
        {!profile && (
          <nav className="workflow-subtabs" aria-label={t("계정 설정")}>
            <button
              aria-pressed={mode === "login"}
              disabled={disabled}
              onClick={() => {
                setMode("login");
                setStep(0);
                setError("");
                setNotice("");
                setPassword("");
              }}
            >
              {t("로그인")}
            </button>
            <button
              aria-pressed={mode === "register"}
              disabled={disabled}
              onClick={() => {
                setMode("register");
                setStep(0);
                setError("");
                setNotice("");
                setPassword("");
              }}
            >
              {t("회원가입")}
            </button>
          </nav>
        )}
        <form
          onSubmit={(event) => {
            event.preventDefault();
            void submit();
          }}
        >
          <div className="account-fields">
            {profile ? (
              <>
                <p className="account-username">{profile.username}</p>
                <label>
                  {t("표시 이름")}
                  <input
                    value={displayName}
                    onChange={(e) => setDisplayName(e.target.value)}
                    maxLength={80}
                    disabled={disabled}
                    required
                  />
                </label>
                <div className="account-language-pair">
                  <LanguageField
                    label="화면 언어"
                    value={preferredLocale}
                    onChange={setPreferredLocale}
                    languages={languages}
                    disabled={disabled}
                  />
                  <LanguageField
                    label="기본 번역 언어"
                    value={target}
                    onChange={setTarget}
                    languages={languages}
                    disabled={disabled}
                    target
                  />
                </div>
              </>
            ) : mode === "login" ? (
              <>
                <label>
                  {t("계정 이름")}
                  <input
                    autoComplete="username"
                    value={username}
                    onChange={(e) => setUsername(e.target.value)}
                    placeholder={t("계정 이름을 입력하세요")}
                    disabled={disabled}
                    required
                  />
                </label>
                <label>
                  {t("비밀번호")}
                  <input
                    type="password"
                    autoComplete="off"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    placeholder={t("비밀번호를 입력하세요")}
                    disabled={disabled}
                    required
                  />
                </label>
              </>
            ) : step === 0 ? (
              <>
                <label>
                  {t("계정 이름")}
                  <input
                    autoComplete="username"
                    value={username}
                    onChange={(e) => setUsername(e.target.value)}
                    maxLength={40}
                    placeholder="reader-name"
                    disabled={disabled}
                    required
                  />
                </label>
                <label>
                  {t("표시 이름")}
                  <input
                    value={displayName}
                    onChange={(e) => setDisplayName(e.target.value)}
                    maxLength={80}
                    disabled={disabled}
                    required
                  />
                </label>
                <label>
                  {t("비밀번호")}
                  <input
                    type="password"
                    autoComplete="new-password"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    placeholder={t("10자 이상 · UTF-8 72바이트 이내")}
                    disabled={disabled}
                    required
                  />
                </label>
              </>
            ) : (
              <>
                <h3>{t("사용할 언어를 선택하세요")}</h3>
                <LanguageField
                  label="화면 언어"
                  value={preferredLocale}
                  onChange={setPreferredLocale}
                  languages={languages}
                  disabled={disabled}
                />
                <LanguageField
                  label="기본 번역 언어"
                  value={target}
                  onChange={setTarget}
                  languages={languages}
                  disabled={disabled}
                  target
                />
              </>
            )}
            {(profile || (mode === "register" && step === 1)) && (
              <p className="workflow-help">
                {t(
                  "언어팩이 준비되면 선택한 언어로 표시됩니다. 현재는 {0}로 표시합니다.",
                  [effectiveLocale(preferredLocale)],
                )}
              </p>
            )}
            {(error || connectionError) && (
              <p className="account-error" role="alert">
                {t(error || connectionError)}
              </p>
            )}
            {notice && (
              <p className="workflow-help" role="status">
                {t(notice)}
              </p>
            )}
          </div>
          <div className="account-actions">
            {!profile && mode === "register" && step === 1 && (
              <button
                type="button"
                className="button-outline"
                disabled={disabled}
                onClick={() => setStep(0)}
              >
                {t("이전")}
              </button>
            )}
            <button className="button-primary" disabled={disabled}>
              {t(
                disabled
                  ? "연결 확인 중…"
                  : profile
                    ? "저장"
                    : mode === "login"
                      ? "로그인"
                      : step === 0
                        ? "다음"
                        : "계정 만들기",
              )}
              <Icon name="arrow" />
            </button>
          </div>
        </form>
        {profile ? (
          <div className="account-secondary">
            <button className="button-text" onClick={onBrowse}>
              {t("서재로 이동")}
            </button>
            <button className="button-text" onClick={onDisconnect}>
              {t("연결 해제")}
            </button>
          </div>
        ) : (
          <p className="account-caption">
            {t(
              "비밀번호는 이 화면을 여는 동안만 사용하며 기기에 저장하지 않습니다.",
            )}
          </p>
        )}
        {!profile && (
          <div className="account-locale">
            <button
              aria-pressed={effectiveLocale(locale) === "ko"}
              onClick={() => {
                setLocale("ko");
                setPreferredLocale("ko");
              }}
            >
              한국어
            </button>
            <button
              aria-pressed={effectiveLocale(locale) === "en"}
              onClick={() => {
                setLocale("en");
                setPreferredLocale("en");
              }}
            >
              English
            </button>
          </div>
        )}
      </div>
    </section>
  );
}
