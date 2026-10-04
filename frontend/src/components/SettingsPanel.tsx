'use client';
import { useState } from 'react';
import { appText, readerDefaults, readerRanges, settingsTabs, type SettingsTab } from '@/lib/app-ui-contract';
import type { Settings } from '@/lib/model';
import type { Translate } from '@/lib/i18n';
import type { Credentials } from '@/lib/server-api';
export function SettingsPanel({ settings: s, set, t, apiKey, setApiKey, credentials, setCredentials }: {
  settings: Settings; set: (s: Settings) => void; t: Translate; apiKey: string; setApiKey: (key: string) => void;
  credentials: Credentials; setCredentials: (value: Credentials) => void;
}) {
  const [tab, setTab] = useState<SettingsTab>('display');
  const label = appText(s.locale);
  return <section className="panel settings-panel"><div className="section-heading"><div className="eyebrow">PREFERENCES</div><h1>{t('settings')}</h1></div>
    <div className="segments">{settingsTabs.map(key => <button key={key} aria-pressed={tab === key} onClick={() => setTab(key)}>{label(key)}</button>)}</div>
    <div className="form-panel">
      {tab === 'display' && <>
        <label>{t('locale')}<select value={s.locale} onChange={e => set({ ...s, locale: e.target.value as Settings['locale'] })}><option value="en">English</option><option value="ko">한국어</option></select></label>
        <label>{t('listMode')}<select value={s.listMode} onChange={e => set({ ...s, listMode: e.target.value as Settings['listMode'] })}><option value="paged">{t('paged')}</option><option value="scroll">{t('scroll')}</option></select></label>
        <label>{t('tapMode')}<select value={s.tapMode} onChange={e => set({ ...s, tapMode: e.target.value as Settings['tapMode'] })}>{(['normal', 'reverse', 'buttons'] as const).map(v => <option key={v} value={v}>{t(v)}</option>)}</select></label>
      </>}
      {tab === 'appearance' && <>
        <label>{t('fontSize')} · {s.fontSize}px<input type="range" min={readerRanges.fontSize[0]} max={readerRanges.fontSize[1]} value={s.fontSize} onChange={e => set({ ...s, fontSize: +e.target.value })} /></label>
        <label>{t('lineHeight')} · {s.lineHeight}<input type="range" min={readerRanges.lineHeight[0]} max={readerRanges.lineHeight[1]} step="0.05" value={s.lineHeight} onChange={e => set({ ...s, lineHeight: +e.target.value })} /></label>
        <label>{t('margin')} · {s.margin}px<input type="range" min={readerRanges.margin[0]} max={readerRanges.margin[1]} value={s.margin} onChange={e => set({ ...s, margin: +e.target.value })} /></label>
        <button onClick={() => set({ ...s, ...readerDefaults })}>{label('preferencesReset')}</button>
      </>}
      {tab === 'languages' && <>
        <label>{t('provider')}<select value={s.provider} onChange={e => { setApiKey(''); set({ ...s, provider: e.target.value as Settings['provider'] }); }}><option value="google-web">Google Web</option><option value="google-cloud">Google Cloud</option><option value="llm">OpenAI-compatible LLM</option></select></label>
        <div className="form-grid"><label>{t('source')}<input maxLength={20} value={s.source} onChange={e => set({ ...s, source: e.target.value })} /></label><label>{t('target')}<input maxLength={20} value={s.target} onChange={e => set({ ...s, target: e.target.value })} /></label></div>
        {s.provider !== 'google-web' && <label>{t('apiKey')}<input type="password" autoComplete="off" value={apiKey} onChange={e => setApiKey(e.target.value)} /></label>}
        {s.provider === 'llm' && <label>{t('model')}<input value={s.model} onChange={e => set({ ...s, model: e.target.value })} placeholder="model-id" /></label>}
        {s.provider === 'google-web' && <p className="help">{t('webWarning')}</p>}
      </>}
      {tab === 'account' && <>
        <p className="help">{label('serverHelp')}</p>
        <label>{t('username')}<input autoComplete="username" value={credentials.username} onChange={e => setCredentials({ ...credentials, username: e.target.value })} /></label>
        <label>{t('password')}<input type="password" autoComplete="off" value={credentials.password} onChange={e => setCredentials({ ...credentials, password: e.target.value })} /></label>
      </>}
    </div>
  </section>;
}
