'use client';
import { useState } from 'react';
import type { Book, LibraryState, Translation } from '@/lib/model';
import type { Translate } from '@/lib/i18n';
import { download, exportBackup, parseBackup } from '@/lib/storage';
import { serverRequest, toServerTranslation, type Credentials } from '@/lib/server-api';
export function BackupPanel({ state, book, translation, credentials, restore, t, report }: {
  state: LibraryState; book?: Book; translation?: Translation; credentials: Credentials;
  restore: (state: LibraryState) => void; t: Translate; report: (message: string) => void;
}) {
  const [tab, setTab] = useState<'local' | 'server'>('local');
  const [preview, setPreview] = useState<LibraryState | null>(null); const [busy, setBusy] = useState(false);
  const [recordId, setRecordId] = useState(''); const [scope, setScope] = useState('all');
  const exportState = scope === 'book' && book ? { ...state, activeId: book.id, books: [book], translations: state.translations.filter(t => t.bookId === book.id) } : state;
  const run = async (action: () => Promise<void>) => { setBusy(true); try { await action(); } catch (e) { report(e instanceof Error ? e.message : t('unavailable')); } finally { setBusy(false); } };
  const readFile = async (file: File) => { if (file.size > 100_000_000) throw new Error('Maximum backup size: 100 MB'); return file.text(); };
  return <section className="panel"><div className="section-heading"><div className="eyebrow">YOUR DATA, WITH YOU</div><h1>{t('backup')}</h1></div>
    <div className="segments"><button aria-pressed={tab === 'local'} onClick={() => setTab('local')}>{t('library')}</button><button aria-pressed={tab === 'server'} onClick={() => setTab('server')}>{t('serverBackup')}</button></div>
    <div className="form-panel">
      {tab === 'local' ? preview ? <div className="restore-preview"><strong>{t('restorePreview')}: {preview.books.length} {t('count')} · {preview.translations.length} {t('cached')}</strong><p>{t('mergeHelp')}</p><div className="actions"><button className="primary" onClick={() => { restore(preview); setPreview(null); report(t('restored')); }}>{t('restore')}</button><button onClick={() => setPreview(null)}>{t('cancel')}</button></div></div> : <><h2>{t('backupTitle')}</h2><p className="help">{t('backupHelp')}</p>
        <label>{t('backupScope')}<select value={scope} onChange={e => setScope(e.target.value)}><option value="all">{t('wholeLibrary')}</option><option value="book" disabled={!book}>{t('currentBook')}</option></select></label>
        <div className="stats"><div><strong>{exportState.books.length}</strong><span>{t('count')}</span></div><div><strong>{exportState.translations.length}</strong><span>{t('cached')}</span></div></div>
        <button className="primary" disabled={busy} onClick={() => void run(async () => { download(`pageturner-${new Date().toISOString().slice(0, 10)}.json`, await exportBackup(exportState)); report(t('downloaded')); })}>{t('exportBackup')} ↓</button>
        <label className={`file-button ${busy ? 'disabled' : ''}`}>{t('restoreBackup')}<input type="file" accept=".json,application/json" disabled={busy} onChange={e => { const file = e.target.files?.[0]; e.target.value = ''; if (file) void run(async () => { setPreview(null); setPreview(await parseBackup(await readFile(file))); }); }} /></label>
        
      </> : <><p className="help">{t('serverHelp')}</p>
        <button className="primary" disabled={busy || !book || !translation} onClick={() => void run(async () => {
          const saved = await (await serverRequest('', credentials, toServerTranslation(book!, translation!))).json();
          setRecordId(saved.recordId);
          const response = await serverRequest(`/${saved.recordId}/backup`, credentials);
          download(`translation-${saved.recordId}.json`, await response.text()); report(t('downloaded'));
        })}>{t('upload')}</button>
        <label>{t('recordId')}<input value={recordId} onChange={e => setRecordId(e.target.value)} placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" /></label>
        <button disabled={busy || !/^[0-9a-f-]{36}$/i.test(recordId)} onClick={() => void run(async () => {
          download(`translation-${recordId}.json`, await (await serverRequest(`/${encodeURIComponent(recordId)}/backup`, credentials)).text()); report(t('downloaded'));
        })}>{t('downloadRecord')}</button>
        <label className={`file-button ${busy ? 'disabled' : ''}`}>{t('restoreRecord')}<input type="file" accept=".json,application/json" disabled={busy} onChange={e => {
          const file = e.target.files?.[0]; e.target.value = ''; if (file) void run(async () => {
            const body = JSON.parse(await readFile(file));
            const saved = await (await serverRequest('/restore', credentials, body)).json();
            setRecordId(saved.recordId); report(`${t('serverRestored')} ${saved.recordId}`);
          });
        }} /></label>
      </>}
      {busy && <div role="status" className="operation">{t('loading')}</div>}
    </div>
  </section>;
}
