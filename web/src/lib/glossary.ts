import type { components } from '../generated/workflow';
import { kotlinTrim, sha256 } from './validation';

export type GlossaryEntry = components['schemas']['GlossaryEntry'];
export type PersonalGlossary = { entries: GlossaryEntry[]; revision: string; updatedAt: string };

/** Omit default options so existing IndexedDB records and job settings remain compatible. */
export function normalizeGlossary(value: unknown): GlossaryEntry[] {
  if (!Array.isArray(value) || value.length > 200) throw new Error('용어집은 최대 200개까지 사용할 수 있습니다.');
  const entries: GlossaryEntry[] = value.map(v => {
    if (!v || typeof v !== 'object' || typeof v.source !== 'string' || typeof v.target !== 'string') throw new Error('용어집의 원문과 번역을 확인해 주세요.');
    const source = kotlinTrim(v.source), target = kotlinTrim(v.target);
    if (!source || !target || source.length > 200 || target.length > 200) throw new Error('각 용어와 번역은 1~200자로 입력해 주세요.');
    if ((v.kind !== undefined && !['Character', 'Place', 'Term'].includes(v.kind)) ||
      (v.displayTerm !== undefined && (typeof v.displayTerm !== 'string' || kotlinTrim(v.displayTerm).length > 200)) ||
      (v.caseSensitive !== undefined && typeof v.caseSensitive !== 'boolean') || (v.enabled !== undefined && typeof v.enabled !== 'boolean')) {
      throw new Error('용어의 종류와 표시 설정을 확인해 주세요.');
    }
    return { source, target, ...(v.kind && v.kind !== 'Character' ? { kind: v.kind } : {}),
      ...(v.displayTerm && kotlinTrim(v.displayTerm) ? { displayTerm: kotlinTrim(v.displayTerm) } : {}),
      ...(v.caseSensitive === true ? { caseSensitive: true } : {}), ...(v.enabled === false ? { enabled: false } : {}) };
  });
  if (new Set(entries.map(e => e.source.toLowerCase())).size !== entries.length) throw new Error('중복된 원문 용어가 있습니다. 대소문자를 구분하지 않습니다.');
  return entries.sort((a, b) => a.source < b.source ? -1 : a.source > b.source ? 1 : 0);
}

/** Mirrors the server's stable entry IDs and BookGlossary.translationFingerprint. */
export async function glossaryRevision(value: unknown): Promise<string> {
  const entries = normalizeGlossary(value).filter(e => e.enabled !== false);
  if (!entries.length) return '';
  const rows = await Promise.all(entries.map(async e => ({ id: (await sha256(e.source.toLowerCase())).slice(0, 24), ...e })));
  rows.sort((a, b) => a.id < b.id ? -1 : a.id > b.id ? 1 : 0);
  return (await sha256(rows.map(e => [e.id, e.source, e.target, String(e.caseSensitive ?? false)].join('\u001f')).join('\n'))).slice(0, 16);
}

export function parseGlossaryFile(text: string): GlossaryEntry[] {
  if (new TextEncoder().encode(text).length > 256 * 1024) throw new Error('용어집 파일은 256KB 이하로 선택해 주세요.');
  let value: unknown;
  try { value = JSON.parse(text); } catch { throw new Error('올바른 JSON 용어집을 선택해 주세요.'); }
  if (value && typeof value === 'object' && 'schema' in value) {
    const shared = value as Record<string, unknown>;
    if (shared.schema !== 'pagetuner-book-glossary' || shared.version !== 1 || !Array.isArray(shared.entries)) throw new Error('지원하지 않는 용어집 파일입니다.');
    return normalizeGlossary(shared.entries.map(entry => {
      if (!entry || typeof entry !== 'object') throw new Error('용어집의 원문과 번역을 확인해 주세요.');
      return { source: entry.sourceTerm, target: entry.translatedTerm, kind: entry.kind, displayTerm: entry.displayTerm,
        caseSensitive: entry.caseSensitive, enabled: entry.enabled };
    }));
  }
  return normalizeGlossary(Array.isArray(value) ? value : value && typeof value === 'object' && 'entries' in value ? value.entries : undefined);
}

/** Android-compatible, provider-independent package; no credentials or reader state. */
export function exportGlossary(glossary: PersonalGlossary, sourceBookId = '', bookTitle = ''): string {
  const entries = normalizeGlossary(glossary.entries);
  // Android's v1 importer caps every term at 160. Reject instead of silently changing an exported term.
  if (entries.some(e => Math.max(e.source.length, e.target.length, e.displayTerm?.length ?? 0) > 160)) throw new Error('앱과 공유하려면 각 용어와 표시 이름을 160자 이하로 줄여 주세요.');
  return JSON.stringify({ schema: 'pagetuner-book-glossary', version: 1, sourceBookId, bookTitle,
    entries: entries.map(e => ({ sourceTerm: e.source, translatedTerm: e.target, displayTerm: e.displayTerm ?? '',
      kind: e.kind ?? 'Character', caseSensitive: e.caseSensitive ?? false, enabled: e.enabled ?? true })) }, null, 2);
}

/** App import semantics keep existing edits and append only previously unknown source terms. */
export function mergeGlossaryEntries(existing: GlossaryEntry[], incoming: GlossaryEntry[]): GlossaryEntry[] {
  const sources = new Set(existing.map(e => kotlinTrim(e.source).toLowerCase()));
  return normalizeGlossary([...existing, ...incoming.filter(e => !sources.has(kotlinTrim(e.source).toLowerCase()))]);
}
