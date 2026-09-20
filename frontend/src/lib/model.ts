import { z } from 'zod';
import { savedCatalogSchema } from './catalog';

export const pageSchema = z.object({ text: z.string().max(200_000), chapter: z.string().max(1000) });
export const bookSchema = z.object({
  id: z.string().min(1).max(100), title: z.string().min(1).max(1000),
  format: z.enum(['TXT', 'MD', 'EPUB', 'PDF']), pages: z.array(pageSchema).min(1).max(20_000),
  currentPage: z.number().int().nonnegative(), folder: z.string().max(500), tags: z.string().max(1000),
  importedAt: z.string(), openedAt: z.string(),
  pdf: z.string().max(70_000_000).optional(),
  bookmarks: z.array(z.number().int().nonnegative()).max(20_000),
  notes: z.array(z.object({ id: z.string(), page: z.number().int().nonnegative(), text: z.string().max(20_000) })).max(20_000),
}).superRefine((book, ctx) => {
  if ([book.currentPage, ...book.bookmarks, ...book.notes.map(n => n.page)].some(p => p >= book.pages.length))
    ctx.addIssue({ code: 'custom', message: 'Page reference is outside the document.' });
});
export const settingsSchema = z.object({
  locale: z.enum(['en', 'ko']), fontSize: z.number().min(14).max(32), lineHeight: z.number().min(1.1).max(2.2),
  margin: z.number().min(8).max(48), provider: z.enum(['google-web', 'google-cloud', 'llm']),
  source: z.string().max(20), target: z.string().max(20), model: z.string().max(200),
  pacing: z.enum(['paced', 'fast']), delaySeconds: z.number().int().min(1).max(120),
  display: z.enum(['original', 'translation', 'both']), listMode: z.enum(['paged', 'scroll']),
  tapMode: z.enum(['normal', 'reverse', 'buttons']),
});
export const translationSchema = z.object({
  key: z.string(), bookId: z.string(), page: z.number().int().nonnegative(),
  provider: settingsSchema.shape.provider, source: z.string(), target: z.string(), model: z.string(),
  sourceRevision: z.string(), text: z.string().min(1).max(200_000), createdAt: z.string(),
});
export const stateSchema = z.object({
  catalogs: z.array(savedCatalogSchema).max(100).optional(),
  books: z.array(bookSchema).max(1000), translations: z.array(translationSchema).max(100_000),
  settings: settingsSchema, activeId: z.string().nullable(),
}).superRefine((state, ctx) => {
  const books = new Map(state.books.map(b => [b.id, b]));
  if (books.size !== state.books.length || new Set(state.translations.map(t => t.key)).size !== state.translations.length)
    ctx.addIssue({ code: 'custom', message: 'Duplicate identifiers.' });
  if (state.activeId && !books.has(state.activeId)) ctx.addIssue({ code: 'custom', message: 'Unknown active book.' });
  for (const t of state.translations) {
    const book = books.get(t.bookId);
    if (!book || t.page >= book.pages.length || t.key !== translationKey(t.bookId, t.page, t))
      ctx.addIssue({ code: 'custom', message: 'Invalid translation reference.' });
  }
});
export type Book = z.infer<typeof bookSchema>;
export type Settings = z.infer<typeof settingsSchema>;
export type Translation = z.infer<typeof translationSchema>;
export type LibraryState = z.infer<typeof stateSchema>;
export const defaultState: LibraryState = {
  books: [], translations: [], activeId: null,
  settings: { locale: 'en', fontSize: 18, lineHeight: 1.35, margin: 18, provider: 'google-web',
    source: 'auto', target: 'ko', model: '', pacing: 'paced', delaySeconds: 10,
    display: 'translation', listMode: 'paged', tapMode: 'normal' },
};
export function translationKey(bookId: string, page: number, s: Pick<Settings, 'provider' | 'source' | 'target' | 'model'>) {
  return JSON.stringify([bookId, page, s.provider, s.source, s.target, s.provider === 'llm' ? s.model : '', 'web-v1']);
}
export async function sha256(data: string | ArrayBuffer): Promise<string> {
  const bytes = typeof data === 'string' ? new TextEncoder().encode(data) : data;
  return Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', bytes))).map(n => n.toString(16).padStart(2, '0')).join('');
}
export function splitText(text: string, size = 620): string[] {
  const clean = text.replace(/\r\n?/g, '\n').trim();
  const pages: string[] = [];
  let offset = 0;
  while (offset < clean.length) {
    let end = Math.min(offset + size, clean.length);
    if (end < clean.length) {
      const boundary = Math.max(clean.lastIndexOf('\n', end - 1), clean.lastIndexOf(' ', end - 1));
      if (boundary > offset + size / 2) end = boundary + 1;
      // Never split a UTF-16 surrogate pair.
      if (/[\uD800-\uDBFF]/.test(clean[end - 1])) end--;
    }
    pages.push(clean.slice(offset, end)); offset = end;
  }
  return pages.length ? pages : [''];
}
