import { get, set } from 'idb-keyval';
import { defaultState, sha256, stateSchema, type LibraryState } from './model';
import { z } from 'zod';
const KEY = 'pageturner-web-v1';
export async function exportRecoveryData() { return JSON.stringify(await get(KEY), null, 2); }
export async function loadState(): Promise<LibraryState> {
  const saved = await get(KEY);
  return saved === undefined ? structuredClone(defaultState) : stateSchema.parse(saved);
}
let writes = Promise.resolve();
export function saveState(state: LibraryState) {
  const next = writes.catch(() => {}).then(() => set(KEY, state));
  writes = next;
  return next;
}
const backupSchema = z.object({
  format: z.literal('pageturner-web-backup'), schemaVersion: z.literal(1),
  exportedAt: z.string(), payloadHash: z.string(), data: stateSchema,
});
export async function exportBackup(state: LibraryState) {
  const data = stateSchema.parse(state); // Whitelists fields; credentials can never enter a backup.
  const serialized = JSON.stringify({ format: 'pageturner-web-backup', schemaVersion: 1,
    exportedAt: new Date().toISOString(), payloadHash: await sha256(JSON.stringify(data)), data }, null, 2);
  if (new TextEncoder().encode(serialized).byteLength > 100_000_000) throw new Error('Backup exceeds 100 MB. Export individual books. / 100 MB를 초과했습니다. 책별로 내보내세요.');
  return serialized;
}
export async function parseBackup(text: string) {
  if (new TextEncoder().encode(text).byteLength > 100_000_000) throw new Error('Backup exceeds 100 MB.');
  const backup = backupSchema.parse(JSON.parse(text));
  if (await sha256(JSON.stringify(backup.data)) !== backup.payloadHash) throw new Error('Backup integrity check failed.');
  for (const t of backup.data.translations) {
    const book = backup.data.books.find(b => b.id === t.bookId)!;
    if (await sha256(book.pages[t.page].text) !== t.sourceRevision) throw new Error('Translation source mismatch.');
  }
  return backup.data;
}
export function mergeBackup(current: LibraryState, incoming: LibraryState): LibraryState {
  // Existing local books and cache entries win; restoration never silently overwrites reading work.
  return stateSchema.parse({ ...current,
    ...((current.catalogs || incoming.catalogs) ? { catalogs: [...(current.catalogs ?? []), ...(incoming.catalogs ?? []).filter(c => !(current.catalogs ?? []).some(existing => existing.url === c.url))] } : {}),
    books: [...current.books, ...incoming.books.filter(b => !current.books.some(c => c.id === b.id))],
    translations: [...current.translations, ...incoming.translations.filter(t => {
      const existing = current.books.find(b => b.id === t.bookId);
      const source = incoming.books.find(b => b.id === t.bookId);
      return !current.translations.some(c => c.key === t.key) && (!existing || existing.pages[t.page]?.text === source?.pages[t.page]?.text);
    })],
    activeId: current.activeId ?? incoming.activeId,
  });
}
export function download(name: string, content: string, type = 'application/json') {
  const url = URL.createObjectURL(new Blob([content], { type }));
  const link = document.createElement('a'); link.href = url; link.download = name;
  document.body.append(link); link.click(); link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
}
