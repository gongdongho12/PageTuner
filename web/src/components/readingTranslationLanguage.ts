const language = (value: string) => value.trim().toLowerCase();

export function sameReadingTranslationLanguage(source: string, target: string): boolean {
  return language(source) !== 'auto' && language(source) === language(target);
}

/** Choose an initial language only; never silently replace a later user selection. */
export function initialReadingTranslationTarget(source: string, requested?: string): string {
  if (requested?.trim() && !sameReadingTranslationLanguage(source, requested)) return requested.trim();
  return language(source) === 'ko' ? 'en' : 'ko';
}
