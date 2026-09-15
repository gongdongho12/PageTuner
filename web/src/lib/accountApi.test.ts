import { describe, expect, it, vi } from "vitest";
import {
  createAccountClient,
  validatePreferences,
  validateRegistration,
} from "./accountApi";
const profile = {
  accountId: "00000000-0000-0000-0000-000000000001",
  username: "new-reader",
  displayName: "Reader",
  locale: "fr",
  targetLanguage: "ja",
  effectiveLocale: "en",
};
const json = (value: unknown, status = 200) =>
  new Response(JSON.stringify(value), {
    status,
    headers: { "Content-Type": "application/json" },
  });
const registration = {
  username: "  New-Reader  ",
  password: "  long password  ",
  displayName: " Reader ",
  locale: "fr",
  targetLanguage: "ja",
};
describe("account registration and preferences", () => {
  it("normalizes account fields while preserving password whitespace and the separate language preferences", () => {
    expect(validateRegistration(registration)).toEqual({
      ...registration,
      username: "new-reader",
      displayName: "Reader",
    });
    expect(
      validatePreferences({
        displayName: " Reader ",
        locale: "pt-BR",
        targetLanguage: "ko",
      }),
    ).toEqual({ displayName: "Reader", locale: "pt-BR", targetLanguage: "ko" });
    expect(() =>
      validatePreferences({
        displayName: "Reader",
        locale: "en",
        targetLanguage: "auto",
      }),
    ).toThrow();
    expect(() =>
      validatePreferences({
        displayName: "Reader",
        locale: "not_a_tag",
        targetLanguage: "ko",
      }),
    ).toThrow();
    for(const locale of ['und','x-private','en_US'])expect(()=>validatePreferences({displayName:'Reader',locale,targetLanguage:'ko'})).toThrow();
    expect(()=>validatePreferences({displayName:'Reader\u0001',locale:'en',targetLanguage:'ko'})).toThrow();
  });
  it("checks BCrypt UTF-8 limits, password length, control characters and username shape before network access", () => {
    for (const password of [
      "short",
      "한".repeat(25),
      "a".repeat(73),
      "long\npassword",
      "😀".repeat(5),
    ])
      expect(() =>
        validateRegistration({ ...registration, password }),
      ).toThrow();
    expect(
      validateRegistration({ ...registration, password: "한".repeat(24) })
        .password,
    ).toBe("한".repeat(24));
    expect(validateRegistration({ ...registration, password: "😀".repeat(10) }).password).toBe("😀".repeat(10));
    for (const username of ["aa", "bad:name", "-reader", "a".repeat(41)])
      expect(() =>
        validateRegistration({ ...registration, username }),
      ).toThrow();
  });
  it("registers through public same-origin CSRF without an Authorization header", async () => {
    const transport = vi.fn(
      async (url: RequestInfo | URL, _options?: RequestInit) =>
        json(
          String(url).endsWith("/csrf")
            ? { token: "public-token", headerName: "X-CSRF-TOKEN" }
            : profile,
          String(url).endsWith("/csrf") ? 200 : 201,
        ),
    );
    const client = createAccountClient(undefined, { fetch: transport });
    expect(await client.register(registration)).toEqual(profile);
    expect(transport.mock.calls.map((call) => call[0])).toEqual([
      "/api/v1/accounts/csrf",
      "/api/v1/accounts/register",
    ]);
    const options = transport.mock.calls[1][1]!;
    expect(options).toMatchObject({
      method: "POST",
      credentials: "same-origin",
      redirect: "error",
      cache: "no-store",
      mode: "same-origin",
    });
    expect(options.headers).not.toHaveProperty("Authorization");
    expect(options.headers).toHaveProperty("X-CSRF-TOKEN", "public-token");
    expect(JSON.parse(options.body as string)).toEqual(
      validateRegistration(registration),
    );
  });
  it("updates every preference with authenticated PATCH and excludes secrets from returned profile", async () => {
    const transport = vi.fn(
      async (url: RequestInfo | URL, _options?: RequestInit) =>
        json(
          String(url).endsWith("/csrf")
            ? { token: "private-token", headerName: "X-CSRF-TOKEN" }
            : { ...profile, password: "never-retain" },
        ),
    );
    const client = createAccountClient(
      { username: "reader", password: "password-in-memory" },
      { fetch: transport },
    );
    const preferences = {
      displayName: "Reader",
      locale: "fr",
      targetLanguage: "ja",
    };
    expect(await client.update(preferences)).toEqual(profile);
    expect(transport.mock.calls[0][0]).toBe("/api/v1/csrf");
    const options = transport.mock.calls[1][1]!;
    expect(options.method).toBe("PATCH");
    expect(JSON.parse(options.body as string)).toEqual(preferences);
    expect(options.headers).toHaveProperty(
      "Authorization",
      `Basic ${btoa("reader:password-in-memory")}`,
    );
  });
  it("does not register when CSRF fails or an account closes during bootstrap", async () => {
    const denied = vi.fn(async () => json({}, 403));
    await expect(
      createAccountClient(undefined, { fetch: denied }).register(registration),
    ).rejects.toMatchObject({ kind: "forbidden" });
    expect(denied).toHaveBeenCalledTimes(1);
    let deliver: ((response: Response) => void) | undefined;
    const transport = vi.fn(
      () =>
        new Promise<Response>((resolve) => {
          deliver = resolve;
        }),
    );
    const client = createAccountClient(undefined, { fetch: transport }),
      pending = client.register(registration);
    client.close();
    deliver!(json({ token: "token", headerName: "X-CSRF-TOKEN" }));
    await expect(pending).rejects.toMatchObject({ kind: "aborted" });
    expect(transport).toHaveBeenCalledTimes(1);
  });
});
