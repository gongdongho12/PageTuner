import { describe, expect, it } from 'vitest';
import { initialReadingTranslationTarget, sameReadingTranslationLanguage } from './readingTranslationLanguage';

describe('reading translation languages', () => {
  it('replaces a same-language account default before starting Korean or English originals', () => {
    expect(initialReadingTranslationTarget('ko', 'ko')).toBe('en');
    expect(initialReadingTranslationTarget('en', 'en')).toBe('ko');
    expect(initialReadingTranslationTarget(' KO ', 'ko')).toBe('en');
    expect(initialReadingTranslationTarget('ja', 'JA')).toBe('ko');
  });
  it('retains a different account choice and the auto-detect to Korean path', () => {
    expect(initialReadingTranslationTarget('ko', 'ja')).toBe('ja');
    expect(initialReadingTranslationTarget('auto', 'ko')).toBe('ko');
    expect(initialReadingTranslationTarget('ko')).toBe('en');
    expect(initialReadingTranslationTarget('en')).toBe('ko');
  });
  it('rejects an explicitly equal pair, case-insensitively, without inferring auto source text', () => {
    expect(sameReadingTranslationLanguage(' ko ', 'KO')).toBe(true);
    expect(sameReadingTranslationLanguage('zh-CN', 'zh-cn')).toBe(true);
    expect(sameReadingTranslationLanguage('auto', 'ko')).toBe(false);
    expect(sameReadingTranslationLanguage('en', 'ko')).toBe(false);
  });
});
