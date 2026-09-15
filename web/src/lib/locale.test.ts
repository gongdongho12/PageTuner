import { afterEach, describe, expect, it } from "vitest";
import {
  effectiveLocale,
  registerLanguagePack,
  setLocale,
  translate,
} from "./locale";
afterEach(() => setLocale("ko"));
describe("extensible interface language packs", () => {
  it("uses Korean and English regional fallbacks and English for unavailable preferences", () => {
    expect(effectiveLocale("ko-KR")).toBe("ko");
    expect(effectiveLocale("en-GB")).toBe("en");
    expect(effectiveLocale("fr-FR")).toBe("en");
    setLocale("ko-KR");
    expect(translate("회원가입")).toBe("회원가입");
    setLocale("fr-FR");
    expect(translate("회원가입")).toBe("Create account");
  });
  it("interpolates interface values without interpreting them as HTML or changing content strings", () => {
    setLocale("en");
    expect(translate("{0}, {1} 읽기", ["<book>", "1"])).toBe("Read <book>, 1");
    expect(translate("A reader-owned original paragraph.")).toBe(
      "A reader-owned original paragraph.",
    );
  });
  it("accepts a future regional language pack without changing account or reading models", () => {
    registerLanguagePack("es-MX", { 회원가입: "Crear cuenta" });
    setLocale("es-MX");
    expect(effectiveLocale("es-MX")).toBe("es-mx");
    expect(translate("회원가입")).toBe("Crear cuenta");
    expect(translate("로그인")).toBe("Sign in");
  });
});
