import type { components } from "../generated/accounts";
import { ApiError } from "./errors";
import { validRecordId } from "./validation";
export type AccountProfile = components["schemas"]["AccountProfile"];
export type AccountPreferences = components["schemas"]["AccountPreferences"];
export type RegisterAccount = components["schemas"]["RegisterAccountRequest"];
export type AccountLanguages = components["schemas"]["AccountLanguages"];
const invalid = () =>
  new ApiError("invalid-response", "계정 응답을 확인할 수 없습니다.");
const object = (v: unknown): Record<string, unknown> => {
  if (!v || typeof v !== "object" || Array.isArray(v)) throw invalid();
  return v as Record<string, unknown>;
};
const text = (v: unknown): string => {
  if (typeof v !== "string") throw invalid();
  return v;
};
function validLanguageTag(value:string,max:number){
  if(value.length>max||!/^[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*$/.test(value)||value.split('-')[0].toLowerCase()==='und')return false;
  try{return Intl.getCanonicalLocales(value).length===1}catch{return false}
}
function profile(v: unknown): AccountProfile {
  const o = object(v);
  const accountId = text(o.accountId),
    effectiveLocale = text(o.effectiveLocale);
  if (!validRecordId(accountId) || !validLanguageTag(effectiveLocale,35))
    throw invalid();
  const result = {
    accountId,
    username: text(o.username),
    displayName: text(o.displayName),
    locale: text(o.locale),
    targetLanguage: text(o.targetLanguage),
    effectiveLocale,
  };
  if(!result.username||!result.displayName.trim()||result.displayName.length>80||/[\u0000-\u001f\u007f-\u009f]/.test(result.displayName)||!validLanguageTag(result.locale,35)||!validLanguageTag(result.targetLanguage,24)||result.targetLanguage.toLowerCase()==='auto')throw invalid();
  return result;
}
function languages(v: unknown): AccountLanguages {
  const o = object(v);
  if (!Array.isArray(o.items)) throw invalid();
  return {
    defaultTag: text(o.defaultTag),
    items: o.items.map((item) => {
      const p = object(item);
      if (typeof p.available !== "boolean") throw invalid();
      return {
        tag: text(p.tag),
        nativeName: text(p.nativeName),
        displayName: text(p.displayName),
        available: p.available,
        fallbackTag: text(p.fallbackTag),
      };
    }),
  };
}
export function validateRegistration(input: RegisterAccount) {
  const username = input.username.trim().toLowerCase();
  if (!/^[a-z0-9][a-z0-9_.-]{2,39}$/.test(username))
    throw new ApiError(
      "invalid-request",
      "계정 이름은 영문 소문자·숫자·밑줄·점·하이픈으로 3~40자 입력해 주세요.",
    );
  if (
    Array.from(input.password).length < 10 ||
    new TextEncoder().encode(input.password).length > 72 ||
    /[\u0000-\u001f\u007f-\u009f]/.test(input.password)
  )
    throw new ApiError(
      "invalid-request",
      "비밀번호는 10자 이상, UTF-8 72바이트 이내로 입력해 주세요. 제어문자는 사용할 수 없습니다.",
    );
  return { ...validatePreferences(input), username, password: input.password };
}
export function validatePreferences(
  input: AccountPreferences,
): AccountPreferences {
  const displayName = input.displayName.trim(),
    locale = input.locale.trim(),
    targetLanguage = input.targetLanguage.trim();
  if (!displayName || displayName.length > 80||/[\u0000-\u001f\u007f-\u009f]/.test(displayName))
    throw new ApiError(
      "invalid-request",
      "표시 이름은 1~80자로 입력해 주세요.",
    );
  if (
    !validLanguageTag(locale, 35) ||
    !validLanguageTag(targetLanguage, 24) ||
    targetLanguage.toLowerCase() === "auto"
  )
    throw new ApiError(
      "invalid-request",
      "올바른 언어 태그를 입력해 주세요. 번역 언어에는 auto를 사용할 수 없습니다.",
    );
  return { displayName, locale, targetLanguage };
}
export function createAccountClient(
  credentials?: { username: string; password: string },
  options: { fetch?: typeof fetch } = {},
) {
  let authorization = credentials
    ? `Basic ${btoa(Array.from(new TextEncoder().encode(`${credentials.username}:${credentials.password}`), (byte) => String.fromCharCode(byte)).join(""))}`
    : "";
  let closed = false;
  const controllers = new Set<AbortController>();
  const transport = options.fetch ?? globalThis.fetch.bind(globalThis);
  async function request<T>(
    path: string,
    parse: (v: unknown) => T,
    signal?: AbortSignal,
    method = "GET",
    body?: unknown,
    csrf?: { headerName: string; token: string },
  ): Promise<T> {
    if (closed || signal?.aborted)
      throw new ApiError("aborted", "요청이 취소되었습니다.");
    const controller = new AbortController();
    controllers.add(controller);
    const abort = () => controller.abort();
    signal?.addEventListener("abort", abort, { once: true });
    const timer = setTimeout(abort, 20_000);
    try {
      const headers: Record<string, string> = {
        Accept: "application/json",
        "X-Requested-With": "XMLHttpRequest",
      };
      if (authorization) headers.Authorization = authorization;
      if (body !== undefined) headers["Content-Type"] = "application/json";
      if (csrf) headers[csrf.headerName] = csrf.token;
      const response = await transport(path, {
        method,
        body: body === undefined ? undefined : JSON.stringify(body),
        headers,
        signal: controller.signal,
        credentials: "same-origin",
        mode: "same-origin",
        redirect: "error",
        cache: "no-store",
      });
      if (!response.ok) {
        if (response.status === 409)
          throw new ApiError(
            "conflict",
            "이미 사용 중인 계정 이름입니다.",
            409,
          );
        if (response.status === 401)
          throw new ApiError(
            "authentication",
            "계정 이름과 비밀번호를 확인해 주세요.",
            401,
          );
        if (response.status === 403)
          throw new ApiError(
            "forbidden",
            "연결을 다시 확인한 뒤 시도해 주세요.",
            403,
          );
        throw new ApiError(
          response.status < 500 ? "invalid-request" : "server",
          "계정 정보를 처리하지 못했습니다. 입력 내용을 확인한 뒤 다시 시도해 주세요.",
          response.status,
        );
      }
      if (
        response.redirected ||
        !response.headers.get("content-type")?.includes("application/json")
      )
        throw invalid();
      const reader = response.body?.getReader();
      if (!reader) throw invalid();
      let size = 0,
        value = "";
      const decoder = new TextDecoder("utf-8", { fatal: true });
      try {
        while (true) {
          const part = await reader.read();
          if (part.done) break;
          size += part.value.length;
          if (size > 128 * 1024) throw invalid();
          value += decoder.decode(part.value, { stream: true });
        }
        value += decoder.decode();
      } finally {
        await reader.cancel().catch(() => undefined);
        reader.releaseLock();
      }
      if (closed || controller.signal.aborted)
        throw new ApiError("aborted", "요청이 취소되었습니다.");
      let parsed: unknown;
      try {
        parsed = JSON.parse(value);
      } catch {
        throw invalid();
      }
      return parse(parsed);
    } catch (error) {
      if (closed || controller.signal.aborted)
        throw new ApiError("aborted", "요청이 취소되었습니다.");
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
  async function write(
    path: string,
    body: unknown,
    method: "POST" | "PATCH",
    signal?: AbortSignal,
  ) {
    const csrf = await request(
      authorization ? "/api/v1/csrf" : "/api/v1/accounts/csrf",
      (v) => {
        const o = object(v),
          headerName = text(o.headerName),
          token = text(o.token);
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
    return request(path, profile, signal, method, body, csrf);
  }
  return {
    close() {
      closed = true;
      authorization = "";
      controllers.forEach((c) => c.abort());
      controllers.clear();
    },
    languages: (signal?: AbortSignal) =>
      request("/api/v1/accounts/languages", languages, signal),
    me: (signal?: AbortSignal) =>
      request("/api/v1/accounts/me", profile, signal),
    register: (input: RegisterAccount, signal?: AbortSignal) =>
      write(
        "/api/v1/accounts/register",
        validateRegistration(input),
        "POST",
        signal,
      ),
    update: (input: AccountPreferences, signal?: AbortSignal) =>
      write("/api/v1/accounts/me", validatePreferences(input), "PATCH", signal),
  };
}
export type AccountClient = ReturnType<typeof createAccountClient>;
