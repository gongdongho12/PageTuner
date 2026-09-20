'use client';
import { useEffect, useMemo, useRef, useState } from 'react';
import type { Book, Settings } from '@/lib/model';
import type { Credentials } from '@/lib/server-api';
import type { Translate } from '@/lib/i18n';
import { appText, pageKey, tapAction } from '@/lib/app-ui-contract';
import { ProgressWriter, ServerApiError, ServerLibraryApi, type ServerAnchor, type ServerBook,
  type ServerBookmark, type ServerChapter, type ServerChapterSummary, type ServerPage, type ServerProgress } from '@/lib/server-library';
import { AdaptiveCollection, Pager } from './AdaptiveCollection';
import { PagedText, type PagedTextHandle } from './ReaderSurface';
const empty = <T,>(): ServerPage<T> => ({ items: [], page: 0, size: 20, totalItems: 0, totalPages: 0 });
export function ServerLibraryPanel({ settings, credentials, localBook, t, report, setCredentials }: {
  settings: Settings; credentials: Credentials; setCredentials: (credentials: Credentials) => void; localBook?: Book; t: Translate; report: (message: string) => void;
}) {
  const api = useMemo(() => new ServerLibraryApi(), []); const label = appText(settings.locale);
  const [user, setUser] = useState(''); const [busy, setBusy] = useState(false); const [error, setError] = useState('');
  const [view, setView] = useState<'books' | 'chapters' | 'reader' | 'bookmarks' | 'upload'>('books');
  const [books, setBooks] = useState(empty<ServerBook>); const [chapters, setChapters] = useState(empty<ServerChapterSummary>);
  const [bookmarks, setBookmarks] = useState(empty<ServerBookmark>); const [query, setQuery] = useState('');
  const [book, setBook] = useState<ServerBook>(); const [chapter, setChapter] = useState<ServerChapter>();
  const [paragraph, setParagraph] = useState(0); const [start, setStart] = useState(0); const [fromEnd, setFromEnd] = useState(false);
  const [focus, setFocus] = useState(false); const [note, setNote] = useState('');
  const [sourceLanguage, setSourceLanguage] = useState(settings.source === 'auto' ? '' : settings.source);
  const [progress, setProgress] = useState<ServerProgress>({ anchor: null, version: 0, updatedAt: null });
  const [syncError, setSyncError] = useState<'conflict' | 'network' | null>(null); const [revision, setRevision] = useState(0);
  const [translated, setTranslated] = useState<Record<string, string>>({});
  const [translationProvider, setTranslationProvider] = useState('');
  const writer = useRef<ProgressWriter | null>(null); const anchor = useRef<ServerAnchor | null>(null);
  const sourceText = useRef<PagedTextHandle>(null); const translationText = useRef<PagedTextHandle>(null);
  const alive = useRef(true);
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  useEffect(() => { let active = true;
    api.session().then(async session => { const list = await api.books(); if (active) { setUser(session.username); setBooks(list); } })
      .catch(e => { if (active && !(e instanceof ServerApiError && e.status === 401)) setError(String(e)); });
    return () => { active = false; };
  }, [api]);
  const run = async (action: () => Promise<void>) => {
    setBusy(true); setError('');
    try { await action(); } catch (e) {
      if (alive.current) {
        const message = e instanceof ServerApiError && e.status === 401 ? label('connectionRequired') : String(e);
        setError(message); report(message);
        if (e instanceof ServerApiError && e.status === 401) { setUser(''); setBooks(empty()); setChapter(undefined); writer.current?.stop(); }
      }
    } finally { if (alive.current) setBusy(false); }
  };
  const saveAnchor = (next: ServerAnchor) => {
    anchor.current = next; const current = writer.current; if (!current || syncError) return;
    void current.save(next).then(value => { if (alive.current && writer.current === current) setProgress(value); })
      .catch(e => { if (alive.current && writer.current === current) setSyncError(e instanceof ServerApiError && e.status === 409 ? 'conflict' : 'network'); });
  };
  const openBook = async (next: ServerBook) => {
    const [list, position] = await Promise.all([api.chapters(next.id), api.progress(next.id)]);
    if (!alive.current) return;
    writer.current = new ProgressWriter(api, next.id, position); anchor.current = position.anchor;
    setBook(next); setChapter(undefined); setChapters(list); setProgress(position); setSyncError(null); setView('chapters');
  };
  const openAnchor = async (next: ServerAnchor) => {
    if (!book) return;
    const content = await api.chapter(book.id, next.chapterId); if (!alive.current) return;
    const index = content.paragraphs.findIndex(p => p.paragraphId === next.paragraphId);
    if (index < 0) throw new Error('Reading paragraph was not found.');
    setTranslated({}); setChapter(content); setParagraph(index); setStart(next.characterOffset); setFromEnd(false);
    anchor.current = next; setRevision(n => n + 1); setView('reader');
  };
  const openChapter = async (id: string) => {
    if (!book) return;
    const content = await api.chapter(book.id, id); if (!alive.current) return;
    const next = { chapterId: id, paragraphId: content.paragraphs[0].paragraphId, characterOffset: 0 };
    setTranslated({}); setChapter(content); setParagraph(0); setStart(0); setFromEnd(false); anchor.current = next;
    setRevision(n => n + 1); setView('reader'); saveAnchor(next);
  };
  useEffect(() => { let active = true; setTranslated({}); setTranslationProvider('');
    if (book && chapter && user) void api.translations(book, chapter, settings.target).then(async list => {
      if (!list.items.length) return;
      const result = await api.translation(list.items[0].recordId);
      if (active) { setTranslated(Object.fromEntries(result.paragraphs.map(p => [p.paragraphId, p.text]))); setTranslationProvider([list.items[0].translationProviderId, list.items[0].modelId].filter(Boolean).join(' · ')); }
    }).catch(e => { if (active) setError(String(e)); });
    return () => { active = false; };
  }, [api, book, chapter, settings.target, user]);
  const current = chapter?.paragraphs[paragraph];
  const hasTranslation = !!current && translated[current.paragraphId] !== undefined;
  const display = hasTranslation ? settings.display : 'original';
  const onPosition = (offset: number) => { if (chapter && current) saveAnchor({ chapterId: chapter.id, paragraphId: current.paragraphId, characterOffset: offset }); };
  const turn = (direction: -1 | 1) => {
    const a = sourceText.current?.turn(direction) ?? false; const b = translationText.current?.turn(direction) ?? false;
    if (a || b || !chapter) return;
    const next = paragraph + direction; if (next < 0 || next >= chapter.paragraphs.length) return;
    const p = chapter.paragraphs[next]; setParagraph(next); setFromEnd(direction < 0); setStart(0);
    saveAnchor({ chapterId: chapter.id, paragraphId: p.paragraphId, characterOffset: direction < 0 ? p.text.length : 0 });
  };
  useEffect(() => {
    const key = (e: KeyboardEvent) => {
      if (view !== 'reader' || e.target instanceof HTMLElement && (e.target.closest('input,textarea,select,button') || e.target.isContentEditable)) return;
      if (e.key === 'Escape') { setFocus(false); return; }
      const direction = pageKey(e.key, e.shiftKey); if (direction) { e.preventDefault(); turn(direction); }
    }; window.addEventListener('keydown', key); return () => window.removeEventListener('keydown', key);
  });
  const resolve = async (useServer: boolean) => {
    if (!book) return;
    const latest = await api.progress(book.id); writer.current?.stop(); writer.current = new ProgressWriter(api, book.id, latest);
    setProgress(latest); setSyncError(null);
    if (useServer && latest.anchor) await openAnchor(latest.anchor);
    else if (anchor.current) {
      try { setProgress(await writer.current.save(anchor.current)); } catch (e) { setSyncError(e instanceof ServerApiError && e.status === 409 ? 'conflict' : 'network'); throw e; }
    }
  };
  if (!user) return <section className="panel"><h1>{label('server')}</h1><div className="form-panel"><p>{label('serverHelp')}</p>
    <label>{t('username')}<input autoComplete="username" value={credentials.username} onChange={e => setCredentials({ ...credentials, username: e.target.value })} /></label><label>{t('password')}<input type="password" autoComplete="off" value={credentials.password} onChange={e => setCredentials({ ...credentials, password: e.target.value })} /></label><button disabled={busy || !credentials.username || !credentials.password} onClick={() => void run(async () => {
      const session = await api.login(credentials); const list = await api.books(); setUser(session.username); setBooks(list); setView('books');
    })}>{label('connect')}</button>{error && <p role="alert">{error}</p>}</div></section>;
  return <section className={`panel reader-panel ${focus ? 'focus-mode' : ''}`}>
    {!focus && <><div className="section-heading"><h2 title={book?.title}>{view === 'books' || view === 'upload' ? label('server') : book?.title}</h2>
      {view === 'books' ? <button disabled={busy} onClick={() => void run(async () => {
        await api.logout(); writer.current?.stop(); setUser(''); setBooks(empty()); setBook(undefined); setChapter(undefined);
      })}>{label('disconnect')}</button> : <button disabled={busy} onClick={() => setView(view === 'reader' || view === 'bookmarks' ? 'chapters' : 'books')}>{t('previous')}</button>}</div>
      {error && <p role="alert">{error}</p>}{busy && <p role="status">{t('loading')}</p>}</>}
    {view === 'books' && <>
      <form className="library-tools" onSubmit={e => { e.preventDefault(); void run(async () => setBooks(await api.books(0, query))); }}>
        <input maxLength={200} value={query} aria-label={t('search')} onChange={e => setQuery(e.target.value)} /><button disabled={busy}>{t('find')}</button>
        <button type="button" disabled={!localBook || busy} onClick={() => setView('upload')}>＋ {t('save')}</button></form>
      <AdaptiveCollection items={books.items} rowHeight={100} mode={settings.listMode} t={t} empty={label('serverEmpty')} render={item =>
        <article className="entry-row"><p title={item.title}>{item.title}</p><div className="actions"><span>{item.chapterCount} {t('chapter')}</span><button disabled={busy} onClick={() => void run(() => openBook(item))}>{t('open')}</button></div></article>} />
      {books.totalPages > 1 && <Pager page={books.page} count={books.totalPages} t={t} onChange={page => void run(async () => setBooks(await api.books(page, query)))} />}
    </>}
    {view === 'upload' && <div className="form-panel"><h2>{localBook?.title}</h2><p>{label('uploadBook')}</p>
      <label>{t('source')}<input value={sourceLanguage} maxLength={24} onChange={e => setSourceLanguage(e.target.value)} placeholder="ko / en / ja" /></label>
      <button className="primary" disabled={busy || !localBook || !sourceLanguage.trim()} onClick={() => void run(async () => {
        const created = await api.importBook(localBook!, sourceLanguage); setBooks(await api.books(0, query)); await openBook(created); report(t('saved'));
      })}>{t('save')}</button></div>}
    {view === 'chapters' && book && <>
      <div className="segments"><button disabled={busy || !progress.anchor} onClick={() => void run(() => openAnchor(progress.anchor!))}>{label('resume')}</button>
        <button disabled={busy} onClick={() => void run(async () => { setBookmarks(await api.bookmarks(book.id)); setView('bookmarks'); })}>{label('bookmarks')}</button></div>
      <AdaptiveCollection items={chapters.items} rowHeight={100} mode={settings.listMode} t={t} render={item => <article className="entry-row">
        <p title={item.title}>{item.ordinal + 1}. {item.title}</p><button disabled={busy} onClick={() => void run(() => openChapter(item.id))}>{t('open')}</button></article>} />
      {chapters.totalPages > 1 && <Pager page={chapters.page} count={chapters.totalPages} t={t} onChange={page => void run(async () => setChapters(await api.chapters(book.id, page)))} />}
    </>}
    {view === 'bookmarks' && book && <>
      <div className="note-editor"><input maxLength={2000} aria-label={label('bookmarkNote')} value={note} onChange={e => setNote(e.target.value)} />
        <button disabled={busy || !anchor.current} onClick={() => void run(async () => { await api.addBookmark(book.id, anchor.current!, note); setNote(''); setBookmarks(await api.bookmarks(book.id)); })}>{t('bookmark')}</button></div>
      <AdaptiveCollection items={bookmarks.items} rowHeight={100} mode={settings.listMode} t={t} render={item => <article className="entry-row"><p title={item.note}>{item.note || label('bookmarks')}</p>
        <div className="actions"><button disabled={busy} onClick={() => void run(() => openAnchor(item.anchor))}>{t('open')}</button><button disabled={busy} onClick={() => void run(async () => {
          await api.deleteBookmark(book.id, item.id); setBookmarks(await api.bookmarks(book.id, Math.max(0, bookmarks.items.length === 1 ? bookmarks.page - 1 : bookmarks.page)));
        })}>{t('remove')}</button></div></article>} />
      {bookmarks.totalPages > 1 && <Pager page={bookmarks.page} count={bookmarks.totalPages} t={t} onChange={page => void run(async () => setBookmarks(await api.bookmarks(book.id, page)))} />}
    </>}
    {view === 'reader' && chapter && current && <>
      {!focus && <div className="reader-toolbar"><span title={chapter.title}>{chapter.title}{hasTranslation && display !== 'original' ? ` · ${translationProvider}` : ` · ${label('sourceOnly')}`}</span><button onClick={() => setFocus(true)}>{t('fullscreen')}</button>
        <button disabled={busy} onClick={() => void run(async () => { setBookmarks(await api.bookmarks(book!.id)); setView('bookmarks'); })}>{label('bookmarks')}</button></div>}
      {!focus && syncError && <div role="alert"><p>{syncError === 'conflict' ? label('conflict') : t('network')}</p><div className="actions"><button disabled={busy} onClick={() => void run(() => resolve(true))}>{label('useServer')}</button>
        <button disabled={busy} onClick={() => void run(() => resolve(false))}>{label('useHere')}</button></div></div>}
      <div className={`reading-surface ${display === 'both' ? 'dual' : ''}`} style={{ padding: focus ? 8 : settings.margin }} onClick={e => {
        if (e.target instanceof HTMLElement && e.target.closest('button') || window.getSelection()?.toString()) return;
        const rect = e.currentTarget.getBoundingClientRect(); const action = tapAction((e.clientX - rect.left) / rect.width, settings.tapMode, focus);
        if (action === 'exit') setFocus(false); else if (action) turn(action);
      }}>
        {display !== 'translation' && <div className="reading-column"><PagedText key={`${chapter.id}:${paragraph}:${revision}`} ref={sourceText} text={current.text} settings={settings} t={t} focus={focus} initialOffset={start} fromEnd={fromEnd} onPosition={onPosition} /></div>}
        {display !== 'original' && <div className="reading-column"><PagedText key={`${chapter.id}:${paragraph}:${revision}:translation`} ref={translationText} text={translated[current.paragraphId]} settings={settings} t={t} focus={focus} fromEnd={fromEnd} /></div>}
      </div>
      {!focus && <div className="pager"><button onClick={() => turn(-1)}>← {t('previous')}</button><span>{paragraph + 1} / {chapter.paragraphs.length}</span><button onClick={() => turn(1)}>{t('next')} →</button></div>}
    </>}
  </section>;
}
