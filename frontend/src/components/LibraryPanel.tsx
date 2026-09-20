'use client';
import { useState } from 'react';
import { createSampleFile } from '@/lib/sample';
import type { Book, Settings } from '@/lib/model';
import type { Translate } from '@/lib/i18n';
import { AdaptiveCollection } from './AdaptiveCollection';
export function LibraryPanel({ books, settings, open, importFiles, edit, remove, busy, t }: {
  books: Book[]; settings: Settings; open: (id: string) => void; importFiles: (files: File[]) => void;
  edit: (book: Book) => void; remove: (id: string) => void; busy: boolean; t: Translate;
}) {
  const [search, setSearch] = useState(''); const [folder, setFolder] = useState('');
  const [filters, setFilters] = useState(false);
  const filtered = books.filter(b => (!folder || b.folder === folder) && `${b.title} ${b.format} ${b.folder} ${b.tags}`.toLowerCase().includes(search.toLowerCase())).sort((a, b) => b.openedAt.localeCompare(a.openedAt));
  const folders = [...new Set(books.map(b => b.folder).filter(Boolean))];
  const importControl = <label className={`file-button primary ${busy ? 'disabled' : ''}`}>＋ {t('import')}<input disabled={busy} type="file" multiple accept=".txt,.md,.epub,.pdf" onChange={e => { const files = Array.from(e.target.files ?? []); e.target.value = ''; importFiles(files); }} /></label>;
  return <section className="panel library-panel"><div className="section-heading"><div><div className="eyebrow">{t('local')}</div><h1>{t('library')}<span className="heading-count">{books.length}</span></h1></div>{importControl}</div>
    {books.length ? <>
      <div className="library-tools"><input aria-label={t('search')} placeholder={t('search')} value={search} onChange={e => setSearch(e.target.value)} /><button aria-pressed={filters} onClick={() => setFilters(!filters)}>{t('folder')}</button></div>
      {filters && <label className="folder-filter">{t('folder')}<select value={folder} onChange={e => setFolder(e.target.value)}><option value="">{t('all')}</option>{folders.map(f => <option key={f}>{f}</option>)}</select></label>}
      <AdaptiveCollection items={filtered} rowHeight={116} mode={settings.listMode} t={t} render={book => <article className="book-row">
        <div className="book-spine" aria-hidden="true"><span>{book.format}</span></div>
        <div className="book-information"><h2 title={book.title}>{book.title}</h2><p>{book.folder || book.format}{book.tags ? ` · ${book.tags}` : ''}</p>
          <div className="book-bottom"><span className="book-progress">{Math.round((book.currentPage + 1) / book.pages.length * 100)}% · {book.currentPage + 1}/{book.pages.length}</span>
            <div className="actions"><button className="primary" onClick={() => open(book.id)}>{t('open')} ↗</button><button onClick={() => edit(book)}>{t('edit')}</button><button aria-label={`${t('remove')} ${book.title}`} onClick={() => remove(book.id)}>×</button></div></div>
        </div></article>} />
    </> : <div className="empty-state"><div className="book-mark" aria-hidden="true">P<span>t</span></div><h2>{t('empty')}</h2><p>{t('emptyHelp')}</p><button disabled={busy} onClick={() => importFiles([createSampleFile(settings.locale)])}>{t('trySample')} →</button><div className="format-list">TXT <span>·</span> MARKDOWN <span>·</span> EPUB <span>·</span> PDF</div></div>}
  </section>;
}
