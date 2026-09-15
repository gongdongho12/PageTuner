import { useEffect, useSyncExternalStore } from "react";
import { englishMessages } from "./localeMessages";
import { exchangeEnglish } from './exchangeMessages';

export type MessageCatalog = Record<string, string>;
const packs = new Map<string, MessageCatalog>([
  ["ko", {}],
  ["en", { ...englishMessages, ...exchangeEnglish }],
]);
const listeners = new Set<() => void>();
let catalogVersion = 0;
let preference = (() => {
  try {
    return localStorage.getItem("pageturner.locale") || "ko";
  } catch {
    return "ko";
  }
})();
export function effectiveLocale(tag: string) {
  const exact = tag.toLowerCase(),
    language = exact.split("-")[0];
  return packs.has(exact) ? exact : packs.has(language) ? language : "en";
}
export function registerLanguagePack(tag: string, messages: MessageCatalog) {
  packs.set(tag.toLowerCase(), messages);
  catalogVersion++;
  listeners.forEach((listener) => listener());
}
export function setLocale(tag: string) {
  preference = tag;
  try {
    localStorage.setItem("pageturner.locale", tag);
  } catch {
    /* Language still changes for this visit. */
  }
  if (typeof document !== "undefined")
    document.documentElement.lang = effectiveLocale(tag);
  listeners.forEach((listener) => listener());
}
export function translate(key: string, values: unknown[] = []) {
  const language = effectiveLocale(preference);
  const message =
    packs.get(language)?.[key] ??
    (language === "ko" ? key : englishMessages[key]) ??
    key;
  return message.replace(/\{(\d+)\}/g, (_, index: string) =>
    String(values[Number(index)] ?? ""),
  );
}
const subscribe = (listener: () => void) => {
  listeners.add(listener);
  return () => listeners.delete(listener);
};
export function useLocale() {
  useSyncExternalStore(subscribe, () => `${preference}:${catalogVersion}`);
  const locale = preference;
  useEffect(() => {
    document.documentElement.lang = effectiveLocale(locale);
  }, [locale, catalogVersion]);
  return {
    locale,
    effectiveLocale: effectiveLocale(locale),
    setLocale,
    t: translate,
  };
}
