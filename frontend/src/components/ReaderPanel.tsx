'use client';
import { useEffect, useRef, useState } from 'react';
import type { Book, Settings, Translation } from '@/lib/model';
import type { Translate } from '@/lib/i18n';
import { AdaptiveCollection } from './AdaptiveCollection';
import { PagedText, PdfPage, type PagedTextHandle } from './ReaderSurface';
import { appText, pageKey, readerTabs, tapAction, type ReaderTab } from '@/lib/app-ui-contract';
import { download } from '@/lib/storage';
export function ReaderPanel({ book, settings, translation, setSettings, changeBook, translate, busy, t }: {
  book: Book; settings: Settings; translation?: Translation; setSettings: (s: Settings) => void;
  changeBook: (book: Book) => void; translate: () => void; busy: boolean; t: Translate;
}) {
  const [tab, setTab] = useState<ReaderTab>('reader');
  const label = appText(settings.locale);
  const original = useRef<PagedTextHandle>(null); const translated = useRef<PagedTextHandle>(null);
  const [fromEnd, setFromEnd] = useState(false);
  const [focus, setFocus] = useState(false); const [note, setNote] = useState(''); const [query, setQuery] = useState('');
  const page = book.pages[book.currentPage];
  const display = !translation ? 'original' : settings.display;
  const go = (next: number) => changeBook({ ...book, currentPage: Math.max(0, Math.min(book.pages.length - 1, next)), openedAt: new Date().toISOString() });
  const turn = (direction: -1 | 1) => {
    const a = original.current?.turn(direction) ?? false;
    const b = translated.current?.turn(direction) ?? false;
    if (!a && !b) { setFromEnd(direction < 0); go(book.currentPage + direction); }
  };
  useEffect(() => {
    const handler = (event: KeyboardEvent) => {
      if (event.target instanceof HTMLElement && (event.target.closest('input,textarea,select,button') || event.target.isContentEditable)) return;
      if (event.key === 'Escape') setFocus(false);
      if (tab !== 'reader') return;
      const direction = pageKey(event.key, event.shiftKey);
      if (direction) { event.preventDefault(); turn(direction); }
    };
    window.addEventListener('keydown', handler); return () => window.removeEventListener('keydown', handler);
  });
  const bookmarks = book.bookmarks.map(p => ({ id: `bookmark-${p}`, page: p, text: t('bookmark'), kind: 'bookmark' }));
  const entries = (tab === 'bookmarks' ? bookmarks : book.notes.map(n => ({ ...n, kind: 'note' }))).sort((a, b) => a.page - b.page);
  const results = query.trim() ? book.pages.map((p, index) => ({ ...p, index })).filter(p => p.text.toLowerCase().includes(query.toLowerCase())) : [];
  const tap = (event: React.MouseEvent<HTMLDivElement>) => {
    if (event.target instanceof HTMLElement && event.target.closest('button')) return;
    if (window.getSelection()?.toString()) return;
    const rect = event.currentTarget.getBoundingClientRect(); const x = (event.clientX - rect.left) / rect.width;
    const action = tapAction(x, settings.tapMode, focus);
    if (action === 'exit') setFocus(false); else if (action) turn(action);
  };
  return <section className={`panel reader-panel ${focus ? 'focus-mode' : ''}`}>
    {!focus && <><div className="reader-heading"><div><div className="eyebrow">{page.chapter}</div><h1>{book.title}</h1></div><button onClick={() => setFocus(true)}>{t('fullscreen')} ⛶</button></div>
      <div className="segments reader-tabs">{readerTabs.map(key => <button key={key} aria-pressed={tab === key} onClick={() => setTab(key)}>{key === 'bookmarks' || key === 'notes' ? label(key) : t(key)}</button>)}</div></>}
    {tab === 'reader' && <>
      {!focus && <div className="reader-toolbar"><select title={!translation ? t('originalFallback') : undefined} aria-label={t('display')} value={display} onChange={e => setSettings({ ...settings, display: e.target.value as Settings['display'] })}><option value="original">{t('original')}</option><option value="translation" disabled={!translation}>{t('translated')}</option><option value="both" disabled={!translation}>{t('both')}</option></select>
        <button aria-pressed={book.bookmarks.includes(book.currentPage)} onClick={() => changeBook({ ...book, bookmarks: book.bookmarks.includes(book.currentPage) ? book.bookmarks.filter(p => p !== book.currentPage) : [...book.bookmarks, book.currentPage] })}>{t(book.bookmarks.includes(book.currentPage) ? 'bookmarked' : 'bookmark')}</button>
        <button disabled={busy || !page.text.trim()} onClick={translate}>{t('translatePage')}</button></div>}
      <div className={`reading-surface ${display === 'both' ? 'dual' : ''}`} style={{ padding: focus ? 8 : settings.margin }} onClick={tap}>
        {display !== 'translation' && <div className="reading-column">{display === 'both' && <span className="column-label">{t('original')}</span>}{book.pdf ? <PdfPage book={book} t={t} /> : <PagedText key={`${book.id}:${book.currentPage}:original`} ref={original} text={page.text} settings={settings} t={t} focus={focus} fromEnd={fromEnd} />}</div>}
        {display !== 'original' && <div className="reading-column">{display === 'both' && <span className="column-label">{t('translated')}</span>}<PagedText key={`${book.id}:${book.currentPage}:translation`} ref={translated} text={translation?.text ?? t(page.text.trim() ? 'noTranslation' : 'noText')} settings={settings} t={t} focus={focus} fromEnd={fromEnd} /></div>}
      </div>
      {!focus ? <div className="pager"><button onClick={() => turn(-1)}>← {t('previous')}</button><span>{book.currentPage + 1} / {book.pages.length}</span><button onClick={() => turn(1)}>{t('next')} →</button></div> : <button className="exit-focus" aria-label={t('exitFocus')} onClick={() => setFocus(false)}>×</button>}
    </>}
    {(tab === 'notes' || tab === 'bookmarks') && <>{tab === 'notes' && <div className="note-editor"><label>{t('note')}<input maxLength={20_000} value={note} onChange={e => setNote(e.target.value)} /></label><button disabled={!note.trim()} onClick={() => { changeBook({ ...book, notes: [...book.notes, { id: crypto.randomUUID(), page: book.currentPage, text: note.trim() }] }); setNote(''); }}>{t('addNote')}</button><button onClick={() => download(`${book.title}-notes.txt`, entries.map(n => `${t('page')} ${n.page + 1}\n${n.text}`).join('\n\n'), 'text/plain')}>{t('exportNotes')}</button></div>}
      <AdaptiveCollection items={entries} rowHeight={100} mode={settings.listMode} t={t} render={entry => <article className="entry-row"><p title={entry.text}>{entry.text}</p><div className="actions"><button onClick={() => { setFromEnd(false); go(entry.page); setTab('reader'); }}>{t('page')} {entry.page + 1} ↗</button><button onClick={() => changeBook(entry.kind === 'bookmark' ? { ...book, bookmarks: book.bookmarks.filter(p => p !== entry.page) } : { ...book, notes: book.notes.filter(n => n.id !== entry.id) })}>{t('remove')}</button></div></article>} />
    </>}
    {tab === 'find' && <><input aria-label={t('find')} placeholder={t('find')} value={query} onChange={e => setQuery(e.target.value)} /><AdaptiveCollection items={results} rowHeight={100} mode={settings.listMode} t={t} render={result => <article className="entry-row"><p>{result.text.slice(Math.max(0, result.text.toLowerCase().indexOf(query.toLowerCase()) - 30), Math.max(0, result.text.toLowerCase().indexOf(query.toLowerCase()) - 30) + 120)}</p><button onClick={() => { setFromEnd(false); go(result.index); setTab('reader'); }}>{t('page')} {result.index + 1} ↗</button></article>} /></>}
    {tab === 'details' && <div className="form-panel"><h2>{book.title}</h2><p>{book.format} · {book.pages.length} {t('size')}</p><p>{t('folder')}: {book.folder || '—'}</p><p>{t('tags')}: {book.tags || '—'}</p><label>{t('chapter')}<select value={book.currentPage} onChange={e => { setFromEnd(false); go(+e.target.value); setTab('reader'); }}>{book.pages.map((p, i) => (i === 0 || p.chapter !== book.pages[i - 1].chapter || book.format === 'PDF') && <option key={i} value={i}>{p.chapter} · {i + 1}</option>)}{book.currentPage > 0 && book.pages[book.currentPage - 1].chapter === page.chapter && book.format !== 'PDF' && <option value={book.currentPage}>{page.chapter} · {book.currentPage + 1}</option>}</select></label></div>}
  </section>;
}
