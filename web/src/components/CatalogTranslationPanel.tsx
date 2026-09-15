import { useEffect, useRef, useState, type ReactNode } from 'react';
import type { NovelBook, ProviderKind, TranslationProvider, WorkflowClient } from '../lib/workflowApi';
import { ApiError } from '../lib/errors';
import { catalogFailureMessage, createCatalogTranslationRequest, verifyCatalogTranslation, type CatalogTranslationResponse } from '../lib/catalogTranslation';
import { translate as t } from '../lib/locale';

type Operation = { id: string; controller: AbortController; cancelled: boolean; accepted: boolean; version: number };
export function CatalogTranslationPanel({ books, client, defaultTargetLanguage, children }: {
  books: readonly NovelBook[]; client: WorkflowClient; defaultTargetLanguage: string;
  children: (books: NovelBook[]) => ReactNode;
}) {
  const [result, setResult] = useState<CatalogTranslationResponse | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [original, setOriginal] = useState(false);
  const [settings, setSettings] = useState(false);
  const [connection, setConnection] = useState(false);
  const [providers, setProviders] = useState<TranslationProvider[]>([]);
  const [kind, setKind] = useState<ProviderKind>('GOOGLE_WEB_TRANSLATE_HTML');
  const [target, setTarget] = useState(defaultTargetLanguage);
  const [apiKey, setApiKey] = useState('');
  const [endpoint, setEndpoint] = useState('');
  const [model, setModel] = useState('');
  const [truncated, setTruncated] = useState(false);
  const operation = useRef<Operation | null>(null);
  const mounted = useRef(false);
  const generation = useRef(0);
  useEffect(() => {
    mounted.current = true;
    setResult(null); setApiKey(''); setBusy(false); setError('');
    return () => {
      mounted.current = false; generation.current++;
      const current = operation.current;
      if (current) {
        current.cancelled = true; current.controller.abort();
        if (current.accepted) void client.cancelCatalogTranslation(current.id).catch(() => {});
      }
    };
  }, [client, books]);
  const isCurrent = (current: Operation) => mounted.current && operation.current === current && current.version === generation.current && !current.cancelled;
  const cancel = async () => {
    const current = operation.current;
    if (!current) return;
    current.cancelled = true; current.controller.abort();
    if (!current.accepted) return; // start is allowed to finish so its server identity can be cancelled reliably
    try { await client.cancelCatalogTranslation(current.id); }
    catch { if (mounted.current && operation.current === current && current.version === generation.current) setError(t('취소 요청을 확인하지 못했습니다. 서버 작업은 최대 2분 후 종료됩니다.')); }
    finally { if (mounted.current && operation.current === current && current.version === generation.current) setBusy(false); }
  };
  const start = async () => {
    if (busy) return;
    let current: Operation | undefined;
    setError(''); setResult(null); setBusy(true); setOriginal(false);
    try {
      const { input, truncated } = createCatalogTranslationRequest(books, { providerKind: kind, targetLanguage: target.trim(), apiKey, endpoint, model });
      setTruncated(truncated);
      current = { id: input.requestId, accepted: false, cancelled: false, controller: new AbortController(), version: generation.current };
      operation.current = current;
      // Never abort an unacknowledged POST merely to hide its response; its ID is needed for cancellation.
      let response = await client.startCatalogTranslation(input);
      current.accepted = true;
      if (!isCurrent(current)) { await client.cancelCatalogTranslation(current.id); return; }
      setApiKey('');
      while (true) {
        response = await verifyCatalogTranslation(response, input);
        if (!isCurrent(current)) return;
        setResult(response);
        if (response.status === 'FAILED') { setError(t(catalogFailureMessage(response.errorCode))); break; }
        if (!['QUEUED', 'RUNNING'].includes(response.status)) break;
        await new Promise<void>(resolve => setTimeout(resolve, 1200));
        if (!isCurrent(current)) return;
        response = await client.getCatalogTranslation(current.id, current.controller.signal);
      }
    } catch (cause) {
      if (current?.cancelled) {
        try { await client.cancelCatalogTranslation(current.id); } catch { /* Server has a bounded lifetime if it cannot be reached. */ }
      } else if (mounted.current && (!current || current.version === generation.current)) setError(cause instanceof ApiError ? t(cause.message) : t('목록 번역을 완료하지 못했습니다. 제공자 설정과 연결을 확인해 주세요.'));
    } finally {
      if ((!current || (operation.current === current && current.version === generation.current)) && mounted.current) setBusy(false);
    }
  };
  const openSettings = async () => {
    setSettings(true); setError('');
    if (providers.length) return;
    const version = generation.current;
    try { const list = await client.providers(); if (mounted.current && generation.current === version) setProviders(list); }
    catch { if (mounted.current && generation.current === version) setError(t('번역기 목록을 불러오지 못했습니다. 다시 시도해 주세요.')); }
  };
  const translated = !original && result?.status === 'COMPLETED' ? new Map(result.items.map(item => [item.key, item])) : new Map();
  const display = books.map(book => {
    const value = translated.get(book.bookId);
    return value ? { ...book, title: value.title, description: value.description ?? book.description } : book;
  });
  const selected = providers.find(provider => provider.id === kind);
  return <section className="novel-workspace">
    <div className="workflow-subtabs">
      <button disabled={busy} onClick={() => void start()}>{t('목록 번역 ({0}권)', [Math.min(24, books.length)])}</button>
      {busy ? <button onClick={() => void cancel()}>{t('번역 취소')}</button> :
        <button disabled={!result || result.status !== 'COMPLETED'} onClick={() => setOriginal(value => !value)}>{original ? t('번역 보기') : t('원문 보기')}</button>}
      <button disabled={busy} aria-pressed={settings} onClick={() => settings ? setSettings(false) : void openSettings()}>{settings ? t('목록으로') : t('번역 설정')}</button>
    </div>
    {error && <p className="workflow-message" role="alert">{error}</p>}
    {busy && <p className="workflow-help" role="status">{t('목록 번역 중: {0}/{1}구간', [result?.completedSegments ?? 0, result?.totalSegments || '…'])}</p>}
    {truncated && !busy && <p className="workflow-help">{t('최대 24권과 설명 앞부분만 번역했습니다. 전체 내용은 원문으로 확인하세요.')}</p>}
    {settings ? <div className="workflow-form">
      <div className="workflow-subtabs"><button aria-pressed={!connection} onClick={() => setConnection(false)}>{t('번역기·언어')}</button><button aria-pressed={connection} onClick={() => setConnection(true)}>{t('연결 설정')}</button></div>
      <div className="workflow-fields">{!connection ? <>
        <label>{t('번역기')}<select value={kind} onChange={event => { setKind(event.target.value as ProviderKind); setApiKey(''); setEndpoint(''); setModel(''); }}>
          {providers.length ? providers.map(provider => <option key={provider.id} value={provider.id}>{t(provider.displayName)}</option>) : <option value="GOOGLE_WEB_TRANSLATE_HTML">Google Web</option>}
        </select></label>
        <label>{t('번역 언어')}<input value={target} maxLength={24} onChange={event => setTarget(event.target.value)} /></label>
      </> : <>
        <label>{t('API 키')}<input type="password" autoComplete="off" value={apiKey} disabled={kind === 'GOOGLE_WEB_TRANSLATE_HTML'} onChange={event => setApiKey(event.target.value)} /></label>
        {['DEEPSEEK', 'OPENAI_COMPATIBLE_LLM'].includes(kind) && <>
          <label>{t('서버 주소')}<input type="url" value={endpoint} placeholder={selected?.defaultEndpoint} onChange={event => setEndpoint(event.target.value)} /></label>
          <label>{t('모델')}<input value={model} placeholder={selected?.defaultModel} onChange={event => setModel(event.target.value)} /></label>
        </>}
      </>}</div>
      <p className="workflow-help">{t('제목 400자, 설명 2,000자, 총 24,000자까지 처리합니다. 키와 결과는 디스크에 저장하지 않습니다.')}</p>
      <div className="workflow-form-actions"><button className="button-primary" onClick={() => { setSettings(false); void start(); }}>{t('목록 번역 시작')}</button></div>
    </div> : children(display)}
  </section>;
}
