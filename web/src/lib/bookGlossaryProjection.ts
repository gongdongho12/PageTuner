import { validateBookGlossary, validateBookGlossaryScope, type BookGlossary, type BookGlossaryScope } from './bookGlossaryApi'
import { normalizeGlossary, type GlossaryEntry } from './glossary'
import type { BookGlossaryState } from './bookGlossarySync'
/** Canonical source identities are supplied by a server source. Local ZIP/content hashes are not inferred. */
export function bookGlossaryScope(providerId: string, bookId: string, language: string): BookGlossaryScope | undefined {
  try { return validateBookGlossaryScope({ providerId, bookId, targetLanguage: language.toLowerCase() }) } catch { return undefined }
}
export function bookGlossaryDisplay(entries: BookGlossary): GlossaryEntry[] {
  return (validateBookGlossary(entries) ?? []).map(e => ({ id: e.id, source: e.sourceTerm, target: e.translatedTerm, displayTerm: e.displayTerm, kind: e.kind, caseSensitive: e.caseSensitive, enabled: e.enabled }))
}
export function bookGlossaryWorkflow(entries: BookGlossary): GlossaryEntry[] {
  const active = bookGlossaryDisplay(entries).filter(e => e.enabled)
  if (active.length > 200) throw new Error('번역에 적용할 계정 용어가 200개를 넘습니다. 사용할 용어를 줄여 주세요. 계정의 전체 용어집은 유지됩니다.')
  return normalizeGlossary(active)
}
/** A selected deletion is deliberately empty; falling back to legacy terms would resurrect it. */
export function selectedBookGlossary(state: BookGlossaryState | undefined, fallback: GlossaryEntry[]): GlossaryEntry[] {
  if (!state || state.status === 'loading' || state.errorCode === 'storage') throw new Error('계정 용어집을 확인한 뒤 번역할 수 있습니다. 다시 시도해 주세요.')
  return state.enabled ? bookGlossaryWorkflow(state.local) : fallback
}
