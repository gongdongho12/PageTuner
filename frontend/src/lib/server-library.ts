import type { Book } from './model';
import type { Credentials } from './server-api';
export type ServerPage<T> = { items: T[]; page: number; size: number; totalItems: number; totalPages: number };
export type ServerBook = { id: string; title: string; author: string; sourceLanguage: string; chapterCount: number; createdAt: string };
export type ServerChapterSummary = { id: string; ordinal: number; title: string; sourceRevision: string };
export type ServerParagraph = { paragraphId: string; text: string };
export type ServerChapter = ServerChapterSummary & { bookId: string; sourceLanguage: string; paragraphs: ServerParagraph[] };
export type ServerAnchor = { chapterId: string; paragraphId: string; characterOffset: number };
export type ServerProgress = { anchor: ServerAnchor | null; version: number; updatedAt: string | null };
export type ServerBookmark = { id: string; anchor: ServerAnchor; note: string; createdAt: string };
export type ServerTranslation = { recordId: string; translationProviderId: string; modelId: string; targetLanguage: string; createdAt: string };
export class ServerApiError extends Error {
  constructor(public status: number) { super(`Server: ${status}`); }
}
const part = encodeURIComponent;
export class ServerLibraryApi {
  constructor(private fetcher: typeof fetch = (...args) => fetch(...args)) {}
  private async raw(path: string, init: RequestInit = {}) {
    const response = await this.fetcher(`/api/v1${path}`, { credentials: 'include', cache: 'no-store', signal: AbortSignal.timeout(30_000), ...init });
    if (!response.ok) throw new ServerApiError(response.status);
    return response;
  }
  private async csrf() { return (await this.raw('/csrf')).json() as Promise<{ headerName: string; token: string }>; }
  async request<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
    const headers: Record<string, string> = {};
    if (method !== 'GET') { const csrf = await this.csrf(); headers[csrf.headerName] = csrf.token; }
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    const response = await this.raw(path, { method, headers, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    return response.status === 204 ? undefined as T : response.json();
  }
  session() { return this.request<{ username: string }>('/session'); }
  async login(credentials: Credentials) {
    const csrf = await this.csrf();
    await this.raw('/session', { method: 'POST', headers: { [csrf.headerName]: csrf.token }, body: new URLSearchParams(credentials) });
    return this.session();
  }
  logout() { return this.request<void>('/session/logout', 'POST'); }
  books(page = 0, query = '') { return this.request<ServerPage<ServerBook>>(`/library/books?${new URLSearchParams({ page: String(page), size: '20', query })}`); }
  chapters(bookId: string, page = 0) { return this.request<ServerPage<ServerChapterSummary>>(`/library/books/${part(bookId)}/chapters?page=${page}&size=20`); }
  chapter(bookId: string, chapterId: string) { return this.request<ServerChapter>(`/library/books/${part(bookId)}/chapters/${part(chapterId)}`); }
  progress(bookId: string) { return this.request<ServerProgress>(`/library/books/${part(bookId)}/progress`); }
  saveProgress(bookId: string, anchor: ServerAnchor, version: number) { return this.request<ServerProgress>(`/library/books/${part(bookId)}/progress`, 'PUT', { anchor, version }); }
  bookmarks(bookId: string, page = 0) { return this.request<ServerPage<ServerBookmark>>(`/library/books/${part(bookId)}/bookmarks?page=${page}&size=20`); }
  addBookmark(bookId: string, anchor: ServerAnchor, note: string) { return this.request<ServerBookmark>(`/library/books/${part(bookId)}/bookmarks`, 'POST', { anchor, note }); }
  deleteBookmark(bookId: string, id: string) { return this.request<void>(`/library/books/${part(bookId)}/bookmarks/${part(id)}`, 'DELETE'); }
  translations(book: ServerBook, chapter: ServerChapter, language: string) {
    return this.request<ServerPage<ServerTranslation>>(`/translations?${new URLSearchParams({ contentProviderId: 'library', bookId: book.id, chapterId: chapter.id, sourceRevision: chapter.sourceRevision, targetLanguage: language, size: '100' })}`);
  }
  translation(recordId: string) { return this.request<{ paragraphs: ServerParagraph[] }>(`/translations/${part(recordId)}`); }
  importBook(book: Book, sourceLanguage: string) { return this.request<ServerBook>('/library/books', 'POST', bookForServer(book, sourceLanguage)); }
}
/** Preserves each local page as source paragraphs; never treats PDF image data as text. */
export function bookForServer(book: Book, sourceLanguage: string) {
  if (!sourceLanguage.trim() || sourceLanguage === 'auto') throw new Error('Choose the source language before uploading. / 원문 언어를 지정하세요.');
  if (book.pages.some(p => !p.text.trim())) throw new Error('Some pages have no extracted text. / 텍스트 없는 페이지는 서버에 저장할 수 없습니다.');
  if (book.title.length > 500 || book.pages.reduce((n, p) => n + p.text.length, 0) > 5_000_000) throw new Error('Book exceeds server limits. / 서버 책 크기 제한을 초과했습니다.');
  const chapters: { title: string; paragraphs: ServerParagraph[] }[] = [];
  book.pages.forEach((page, pageIndex) => {
    let chapter = chapters.at(-1);
    if (!chapter || chapter.title !== page.chapter) {
      chapter = { title: page.chapter || book.title, paragraphs: [] }; chapters.push(chapter);
    }
    if (chapter.title.length > 500) throw new Error('Chapter title exceeds 500 characters.');
    // Avoid splitting UTF-16 pairs and keep every piece under the server's paragraph limit.
    let offset = 0, piece = 0;
    while (offset < page.text.length) {
      let end = Math.min(offset + 100_000, page.text.length);
      if (end < page.text.length && /[\uD800-\uDBFF]/.test(page.text[end - 1])) end--;
      const text = page.text.slice(offset, end);
      if (text.trim()) chapter.paragraphs.push({ paragraphId: `page-${pageIndex}-${piece++}`, text });
      offset = end;
    }
  });
  if (chapters.length > 500 || chapters.some(c => c.paragraphs.length > 10_000)) throw new Error('Book exceeds server chapter limits.');
  return { title: book.title, author: '', sourceLanguage, chapters };
}
/** Serialize device writes, and stop after a conflict or network error until explicitly resolved. */
export class ProgressWriter {
  private tail = Promise.resolve();
  private stopped = false;
  private failure: unknown;
  constructor(private api: ServerLibraryApi, private bookId: string, private current: ServerProgress) {}
  save(anchor: ServerAnchor): Promise<ServerProgress> {
    const result = this.tail.then(async () => {
      if (this.stopped) throw this.failure ?? new Error('Progress sync paused.');
      try { this.current = await this.api.saveProgress(this.bookId, anchor, this.current.version); return this.current; }
      catch (error) { this.failure = error; this.stopped = true; throw error; }
    });
    this.tail = result.then(() => {}, () => {}); return result;
  }
  stop() { this.stopped = true; }
}
