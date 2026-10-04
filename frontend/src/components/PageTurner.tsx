'use client';
import { useCallback, useEffect, useState } from 'react';
import { messages, type MessageKey } from '@/lib/i18n';
import { importBook } from '@/lib/import';
import { translationKey, type Book, type Settings } from '@/lib/model';
import { mergeBackup, exportRecoveryData, download } from '@/lib/storage';
import { useLibrary, useTranslationQueue } from '@/lib/use-library';
import { ServerLibraryPanel } from './ServerLibraryPanel';
import { CatalogPanel } from './CatalogPanel';
import { LibraryPanel } from './LibraryPanel';
import { ReaderPanel } from './ReaderPanel';
import { BackupPanel } from './BackupPanel';
import { SettingsPanel } from './SettingsPanel';
import type { Credentials } from '@/lib/server-api';

type Screen = 'library' | 'reader' | 'translation' | 'backup' | 'settings';
const symbols = { library: '▤', reader: '◫', translation: '文', backup: '↧', settings: '☷' };
export default function PageTurner() {
  const { state, current, update, loaded, storageError, loadError } = useLibrary();
  const { queue, start, pause, resume, cancel, retry } = useTranslationQueue(current, update);
  const [libraryTab, setLibraryTab] = useState<'device' | 'server' | 'catalog'>('device');
  const [screen, setScreen] = useState<Screen>('library'); const [apiKey, setApiKey] = useState('');
  const [credentials, setCredentials] = useState<Credentials>({ username: 'local-reader', password: '' });
  const [status, setStatus] = useState(''); const [importing, setImporting] = useState(false); const [editing, setEditing] = useState<Book | null>(null);
  const t = useCallback((key: MessageKey) => messages[state.settings.locale][key], [state.settings.locale]);
  const book = state.books.find(b => b.id === state.activeId);
  const activeTranslation = book ? state.translations.find(v => v.key === translationKey(book.id, book.currentPage, state.settings)) : undefined;
  const cache = book ? state.translations.filter(v => v.bookId === book.id && v.key === translationKey(book.id, v.page, state.settings)) : [];
  const busy = queue.status === 'running' || queue.status === 'paused';
  useEffect(() => { document.documentElement.lang = state.settings.locale; }, [state.settings.locale]);
  const setSettings = (settings: Settings) => update(s => ({ ...s, settings }));
  const open = (id: string) => { update(s => ({ ...s, activeId: id, books: s.books.map(b => b.id === id ? { ...b, openedAt: new Date().toISOString() } : b) })); setScreen('reader'); };
  const changeBook = (book: Book) => update(s => ({ ...s, books: s.books.map(b => b.id === book.id ? book : b) }));
  const importFiles = async (files: File[]) => {
    if (!files.length || importing) return; setImporting(true); setStatus(t('importing'));
    try {
      for (const file of files) {
        const imported = await importBook(file);
        update(s => ({ ...s, activeId: imported.id, books: s.books.some(b => b.id === imported.id) ? s.books : [...s.books, imported] }));
      }
      setStatus(t('saved'));
    } catch (error) { setStatus(error instanceof Error ? error.message : t('unavailable')); }
    finally { setImporting(false); }
  };
  const translatePage = () => { if (book) { start(book, state.settings, [book.currentPage], apiKey); setSettings({ ...state.settings, display: 'translation' }); } };
  const providerError = (code: string) => code in messages.en ? t(code as MessageKey) : t(code === 'provider' ? 'providerError' : 'network');
  if (loadError) return <main className="loading-screen"><div className="form-panel"><h1>PageTurner</h1><p role="alert">{t('loadFailure')}</p><button onClick={() => { void exportRecoveryData().then(raw => download('pageturner-recovery.json', raw)).catch(() => setStatus(t('storageError'))); }}>{t('recovery')}</button><button onClick={() => window.location.reload()}>{t('reload')}</button><p role="status">{status}</p></div></main>;
  if (!loaded) return <main className="loading-screen" role="status">PageTurner · {t('loading')}</main>;
  return <div className="app-shell"><aside className="sidebar"><a className="brand" href="/" onClick={e => { e.preventDefault(); setScreen('library'); }}><span className="brand-symbol">P<span>t</span></span><span>PageTurner<small>WEB READER</small></span></a>
    <nav aria-label="PageTurner">{(['library', 'reader', 'translation', 'backup', 'settings'] as const).map(key => <button key={key} aria-current={screen === key ? 'page' : undefined} onClick={() => setScreen(key)}><span className="nav-symbol" aria-hidden="true">{symbols[key]}</span><span>{t(key)}</span>{key === 'library' && <small>{state.books.length}</small>}</button>)}</nav>
    <div className="sidebar-bottom"><p>{t('tagline')}</p><span>LOCAL FIRST · V0.1</span></div>
  </aside><div className="workspace"><header className="topbar"><span>WORKSPACE <span className="slash">/</span> {t(screen)}</span><select aria-label={t('locale')} value={state.settings.locale} onChange={e => setSettings({ ...state.settings, locale: e.target.value as Settings['locale'] })}><option value="en">English</option><option value="ko">한국어</option></select></header>
    <main className="main-content">
      {screen === 'library' && <div className="library-workspace"><div className="segments">{(['device', 'server', 'catalog'] as const).map(key => <button key={key} aria-pressed={libraryTab === key} onClick={() => { setStatus(''); setLibraryTab(key); }}>{t(key)}</button>)}</div>{libraryTab === 'server' ? <ServerLibraryPanel setCredentials={setCredentials} settings={state.settings} credentials={credentials} localBook={book} t={t} report={setStatus} /> : libraryTab === 'catalog' ? <CatalogPanel catalogs={state.catalogs ?? []} save={catalogs => update(s => ({ ...s, catalogs }))} importFiles={importFiles} settings={state.settings} t={t} report={setStatus} /> : <LibraryPanel books={state.books} settings={state.settings} open={open} importFiles={files => void importFiles(files)} busy={importing} edit={setEditing} t={t} remove={id => {
        if (!window.confirm(t('deleteConfirm'))) return;
        if (busy) cancel();
        update(s => ({ ...s, books: s.books.filter(b => b.id !== id), translations: s.translations.filter(v => v.bookId !== id), activeId: s.activeId === id ? null : s.activeId }));
      }} />}</div>}
      {screen === 'reader' && (book ? <ReaderPanel key={book.id} book={book} settings={state.settings} translation={activeTranslation} setSettings={setSettings} changeBook={changeBook} translate={translatePage} busy={busy} t={t} /> : <div className="panel empty-state"><h1>{t('reader')}</h1><p>{t('noBook')}</p><button onClick={() => setScreen('library')}>{t('library')} →</button></div>)}
      {screen === 'translation' && <section className="panel"><div className="section-heading"><div className="eyebrow">READ BEYOND LANGUAGE</div><h1>{t('translation')}</h1></div>
        <div className="form-panel"><h2>{book?.title ?? t('noBook')}</h2><div className="translation-pair"><span>{state.settings.source}</span><span>→</span><span>{state.settings.target}</span><button onClick={() => setScreen('settings')}>{t('settings')}</button></div>
          <p className="help">{state.settings.provider} {state.settings.provider === 'llm' && `· ${state.settings.model}`}</p>
          <div className="stats"><div><strong>{cache.length}<small> / {book?.pages.length ?? 0}</small></strong><span>{t('cached')}</span></div><div><strong>{queue.failed.length}</strong><span>{t('failed')}</span></div></div>
          <div className="actions wrap"><button className="primary" disabled={!book || busy} onClick={translatePage}>{t('translatePage')}</button><button disabled={!book || busy} onClick={() => { if (book) start(book, state.settings, book.pages.map((_, i) => i), apiKey); }}>{t('translateAll')}</button></div>
          <div className="form-grid"><label>{t('pacing')}<select value={state.settings.pacing} onChange={e => setSettings({ ...state.settings, pacing: e.target.value as Settings['pacing'] })}><option value="paced">{t('paced')}</option><option value="fast">{t('fast')}</option></select></label><label>{t('delay')}<input type="number" min={1} max={120} value={state.settings.delaySeconds} onChange={e => setSettings({ ...state.settings, delaySeconds: Math.min(120, Math.max(1, Math.round(+e.target.value))) })} /></label></div>
          <button disabled={!book || !cache.length || busy} onClick={() => { if (book && window.confirm(t('clearConfirm'))) update(s => ({ ...s, translations: s.translations.filter(v => !cache.some(c => c.key === v.key)) })); }}>{t('clearCache')}</button>
        </div>
      </section>}
      {screen === 'backup' && <BackupPanel state={state} book={book} translation={activeTranslation} credentials={credentials} restore={incoming => update(s => mergeBackup(s, incoming))} t={t} report={setStatus} />}
      {screen === 'settings' && <SettingsPanel settings={state.settings} set={setSettings} t={t} apiKey={apiKey} setApiKey={setApiKey} credentials={credentials} setCredentials={setCredentials} />}
    </main>
    {queue.status !== 'ready' && <div className="queue-bar" role="status"><div className="queue-detail"><strong>{t(queue.status)} · {queue.completed}/{queue.total}</strong><span>{queue.bookTitle}{queue.failed.length ? ` · ${t('failed')} ${queue.failed.length}` : ''}</span>{queue.error && <span className="queue-error">{providerError(queue.error)}</span>}<progress aria-label={t('queue')} value={queue.completed} max={queue.total || 1} /></div><div className="actions">{queue.status === 'running' && <button onClick={pause}>{t('pause')}</button>}{queue.status === 'paused' && <button onClick={() => resume(apiKey)}>{t('resume')}</button>}{queue.status === 'done' && queue.failed.length > 0 && <button onClick={() => retry(apiKey)}>{t('retry')}</button>}<button onClick={cancel}>{t(queue.status === 'done' ? 'close' : 'cancel')}</button></div></div>}
    <footer className="statusbar"><span role={storageError ? 'alert' : 'status'}>{storageError ? t('storageError') : status || t(screen === 'library' && libraryTab === 'server' ? 'serverPrivacy' : 'privacy')}</span>{status && <button aria-label={t('close')} onClick={() => setStatus('')}>×</button>}</footer>
  </div>
    {editing && <div className="modal-backdrop"><form className="dialog" role="dialog" aria-modal="true" aria-labelledby="edit-title" onSubmit={e => { e.preventDefault(); changeBook(editing); setEditing(null); }}><h2 id="edit-title">{t('edit')}</h2><label>{t('title')}<input autoFocus required maxLength={1000} value={editing.title} onChange={e => setEditing({ ...editing, title: e.target.value })} /></label><label>{t('folder')}<input maxLength={500} value={editing.folder} onChange={e => setEditing({ ...editing, folder: e.target.value })} /></label><label>{t('tags')}<input maxLength={1000} value={editing.tags} onChange={e => setEditing({ ...editing, tags: e.target.value })} /></label><div className="actions"><button className="primary" type="submit">{t('save')}</button><button type="button" onClick={() => setEditing(null)}>{t('cancel')}</button></div></form></div>}
  </div>;
}
