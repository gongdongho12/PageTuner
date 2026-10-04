'use client';
import { useState } from 'react';
import { fetchLimited, parseCatalog, type SavedCatalog } from '@/lib/catalog';
import type { Translate } from '@/lib/i18n';
import type { Settings } from '@/lib/model';
import { AdaptiveCollection } from './AdaptiveCollection';
export function CatalogPanel({ catalogs, save, importFiles, settings, t, report }: {
  catalogs: SavedCatalog[]; save: (catalogs: SavedCatalog[]) => void; importFiles: (files: File[]) => Promise<void>;
  settings: Settings; t: Translate; report: (message: string) => void;
}) {
  const [url, setUrl] = useState(''); const [active, setActive] = useState<SavedCatalog | null>(null);
  const [busy, setBusy] = useState(false); const [search, setSearch] = useState(''); const [sources, setSources] = useState(true);
  const run = async (fn: () => Promise<void>) => { setBusy(true); try { await fn(); } catch (e) { report(`${e instanceof Error ? e.message : t('network')} · ${t('catalogHelp')}`); } finally { setBusy(false); } };
  return <section className="panel"><div className="section-heading"><h1>{t('catalog')}</h1><button onClick={() => setSources(!sources)}>{t('catalogSaved')}</button></div>
    {sources ? <div className="form-panel"><p className="help">{t('catalogHelp')}</p><label>{t('catalogUrl')}<input type="url" value={url} onChange={e => setUrl(e.target.value)} placeholder="https://example.org/catalog.json" /></label>
      <button className="primary" disabled={busy || !url} onClick={() => void run(async () => {
        const catalog = parseCatalog(JSON.parse(new TextDecoder().decode(await fetchLimited(url, 5_000_000))), url);
        save([...catalogs.filter(c => c.url !== url), catalog]); setActive(catalog); setSources(false);
      })}>{t('loadCatalog')}</button>
      <label>{t('catalogSaved')}<select value={active?.url ?? ''} onChange={e => { const catalog = catalogs.find(c => c.url === e.target.value); if (catalog) { setActive(catalog); setUrl(catalog.url); setSources(false); } }}><option value="">—</option>{catalogs.map(c => <option value={c.url} key={c.url}>{c.title}</option>)}</select></label>
      {active && <button onClick={() => { save(catalogs.filter(c => c.url !== active.url)); setActive(null); }}>{t('remove')}</button>}
    </div> : <><input aria-label={t('search')} placeholder={t('search')} value={search} onChange={e => setSearch(e.target.value)} />
      <AdaptiveCollection items={(active?.items ?? []).filter(item => item.title.toLowerCase().includes(search.toLowerCase()))} rowHeight={100} mode={settings.listMode} t={t} render={item => <article className="entry-row"><p>{item.title}</p><div className="actions"><span>{item.format.toUpperCase()}</span><button disabled={busy} onClick={() => void run(async () => { const bytes = await fetchLimited(item.href, 50_000_000); await importFiles([new File([bytes], `${item.title}.${item.format}`)]); })}>{t('importRemote')}</button></div></article>} />
    </>}{busy && <div className="operation" role="status">{t('loading')}</div>}
  </section>;
}
