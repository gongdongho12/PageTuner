import { useEffect, useMemo, useRef, useState } from 'react';
import { translate as t } from '../lib/locale';
import { createBulkQueueStorage, collectBulkChapters, prepareBulkTranslation, runBulkQueue, type BulkQueue } from '../lib/bulkNovelQueue';
import type { NovelDetail, StoredChapter, TranslationProvider, WorkflowClient } from '../lib/workflowApi';
import { AdaptiveCollection } from './AdaptiveCollection';
import { TranslationSetup } from './TranslationSetup';

export type BulkNovelWorkspaceProps = {
  client: WorkflowClient; username: string; detail?: NovelDetail | null; defaultTargetLanguage: string;
  onOpenOriginal: (chapter: StoredChapter) => void; onBack: () => void;
};
const itemLabels = { pending: '대기', imported: '원문 보관됨', submitting: '번역 요청 확인 중', running: '번역하는 중', complete: '완료', failed: '재시도 필요' };

export function BulkNovelWorkspace({ client, username, detail, defaultTargetLanguage, onOpenOriginal, onBack }: BulkNovelWorkspaceProps) {
  const storage = useMemo(() => createBulkQueueStorage(username), [username]);
  const session = useMemo(() => ({ storage, client }), [storage, client]);
  const currentSession = useRef(session);
  currentSession.current = session;
  const [queue, setQueue] = useState<BulkQueue | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [from, setFrom] = useState((detail?.page ?? 0) * (detail?.size ?? 20) + 1);
  const [to, setTo] = useState(Math.min(detail?.totalItems ?? 1, from + 19));
  const [apiKey, setApiKey] = useState('');
  const [prepared, setPrepared] = useState<StoredChapter | null>(null);
  const [providers, setProviders] = useState<TranslationProvider[]>([]);
  const [panel, setPanel] = useState<'progress' | 'select' | 'key'>('progress');
  const mounted = useRef(false);
  const control = useRef<AbortController | null>(null);
  const current = () => mounted.current && currentSession.current === session;
  useEffect(() => {
    mounted.current = true;
    let current = true;
    setLoaded(false); setQueue(null); setPrepared(null); setApiKey(''); setBusy(false); setError('');
    void storage.load().then(value => { if (current) { setQueue(value); setPanel(value ? 'progress' : 'select'); setLoaded(true); } })
      .catch(() => { if (current) { setError(t('일괄 작업 기록을 읽지 못했습니다. 저장소를 확인해 주세요.')); setLoaded(true); } });
    return () => { current = false; mounted.current = false; control.current?.abort(); };
  }, [session, storage]);
  const accept = (value: BulkQueue) => { if (current()) setQueue(value); };
  const work = async (action: () => Promise<void>) => {
    if (busy) return;
    setBusy(true); setError('');
    try { await action(); }
    catch (cause) { if (current()) setError(cause instanceof Error ? t(cause.message) : t('작업을 완료하지 못했습니다. 다시 시도해 주세요.')); }
    finally { if (current()) setBusy(false); }
  };
  const execute = async (key = apiKey) => {
    const controller = new AbortController(); control.current = controller;
    const result = await runBulkQueue(storage, client, { apiKey: key, signal: controller.signal, onChange: accept });
    if (current() && ['completed', 'cancelled'].includes(result.state)) setApiKey('');
  };
  const command = async (state: 'paused' | 'cancelling') => {
    try {
      accept(await storage.command(state));
      if (current() && !busy && state === 'cancelling') await work(() => execute());
    } catch (cause) { if (current()) setError(cause instanceof Error ? t(cause.message) : t('작업을 완료하지 못했습니다. 다시 시도해 주세요.')); }
  };
  if (prepared) return <section className="novel-workspace">
    <p className="workflow-help">{t('선택한 {0}회차에 같은 번역 설정을 적용합니다.', [queue?.items.length ?? 0])}</p>
    <TranslationSetup chapter={prepared} providers={providers} busy={busy} username={username} defaultTargetLanguage={defaultTargetLanguage}
      onBack={() => setPrepared(null)} onSubmit={async input => {
        await work(async () => {
          accept(await storage.configure(input)); if (!current()) return;
          setApiKey(input.apiKey ?? ''); setPrepared(null); setPanel('progress');
          await execute(input.apiKey ?? '');
        });
      }} />
  </section>;
  const replaceable = !queue || ['completed', 'cancelled'].includes(queue.state);
  return <section className="novel-workspace">
    <div className="workflow-heading">
      <button className="button-quiet" onClick={onBack}>{t('뒤로')}</button>
      <div><span className="eyebrow">CHAPTER QUEUE</span><h2>{t('여러 회차 가져오기')}</h2></div>
    </div>
    <div className="workflow-subtabs">
      <button aria-pressed={panel === 'progress'} onClick={() => setPanel('progress')}>{t('진행')}</button>
      <button aria-pressed={panel === 'select'} onClick={() => setPanel('select')}>{t('회차 선택')}</button>
      {queue?.settings && queue.settings.providerKind !== 'GOOGLE_WEB_TRANSLATE_HTML' &&
        <button aria-pressed={panel === 'key'} onClick={() => setPanel('key')}>{t('API 키')}</button>}
    </div>
    {error && <p className="workflow-message" role="alert">{error}</p>}
    {loaded && error && !queue && <button className="button-outline" disabled={busy} onClick={() => void work(async () => {
      await storage.discardCorrupt(); if (current()) { setError(''); setPanel('select'); }
    })}>{t('손상된 기기 기록만 지우기 (서버 작업 유지)')}</button>}
    {!loaded ? <p>{t('기록을 불러오는 중…')}</p> : panel === 'select' ? <div className="workflow-form">
      <p className="workflow-book-name">{detail?.title ?? t('목차에서 책을 선택해 주세요.')}</p>
      <div className="workflow-fields"><div className="workflow-field-pair">
        <label>{t('시작 목차 순서')}<input type="number" min={1} max={detail?.totalItems} value={from} onChange={event => setFrom(Number(event.target.value))} disabled={busy || !replaceable} /></label>
        <label>{t('마지막 목차 순서')}<input type="number" min={from} max={Math.min(detail?.totalItems ?? from, from + 19)} value={to} onChange={event => setTo(Number(event.target.value))} disabled={busy || !replaceable} /></label>
      </div></div>
      <p className="workflow-help">{t('목차에 표시된 순서로 최대 20회차를 선택합니다. 완료한 뒤 다음 범위를 추가할 수 있습니다.')}</p>
      {!replaceable && <p className="workflow-message">{t('기존 일괄 작업을 완료하거나 취소한 뒤 새로 선택해 주세요.')}</p>}
      <div className="workflow-form-actions"><button className="button-primary" disabled={busy || !detail || !replaceable}
        onClick={() => void work(async () => {
          const chapters = await collectBulkChapters(client, detail!, from, to);
          if (!current()) return;
          accept(await storage.create(detail!, chapters)); if (current()) setPanel('progress');
        })}>{t('선택한 회차 보관')}</button></div>
    </div> : panel === 'key' ? <div className="workflow-form">
      <div className="workflow-fields"><label>{t('API 키')}<input type="password" autoComplete="off" value={apiKey} onChange={event => setApiKey(event.target.value)} disabled={busy} /></label></div>
      <p className="workflow-help">{t('키는 이 화면의 메모리에만 보관됩니다. 재방문 시 다시 입력하거나 서버에 설정된 키를 사용합니다.')}</p>
      <button className="button-outline" onClick={() => setPanel('progress')}>{t('진행 화면으로')}</button>
    </div> : !queue ? <p className="workflow-help">{t('목차에서 책을 선택하고 회차 범위를 보관해 주세요.')}</p> : <>
      <p className="workflow-book-name" title={queue.title}>{queue.title} · {queue.cursor}/{queue.items.length}</p>
      <p className="workflow-help">{busy ? t('회차를 순서대로 처리하고 있습니다.') : t('재개는 직접 눌러 시작합니다. 창을 닫아도 이미 제출한 서버 번역은 계속됩니다.')}</p>
      {queue.error && <p className="workflow-message" role="alert">{t(queue.error)}</p>}
      <AdaptiveCollection items={queue.items} itemKey={item => item.url} rowHeight={100} renderItem={item =>
        <div className="workflow-row"><span className="workflow-row-number">{item.order}</span>
          <span className="workflow-row-copy"><strong>{item.title}</strong><span>{t(itemLabels[item.state])} {item.totalParagraphs > 0 && `${item.completedParagraphs}/${item.totalParagraphs}`}</span></span>
          {item.chapterRecordId && <button className="button-quiet" disabled={busy} onClick={() => void work(async () => {
            const chapter = await client.chapter(item.chapterRecordId!); if (current()) onOpenOriginal(chapter);
          })}>{t('원문 읽기')}</button>}
        </div>} />
      <div className="workflow-form-actions" style={{ gap: 8, flexWrap: 'wrap', justifyContent: 'flex-start' }}>
        {(queue.state === 'ready' || queue.requiresConfiguration) && !queue.settings ? <>
          {!queue.requiresConfiguration && <button className="button-primary" disabled={busy} onClick={() => void work(() => execute())}>{t('원문만 가져오기')}</button>}
          <button className="button-outline" disabled={busy} onClick={() => void work(async () => {
            const chapter = await prepareBulkTranslation(storage, client);
            if (!current()) return;
            const list = await client.providers();
            const stored = await storage.load(); if (stored) accept(stored);
            if (current() && stored?.state === 'cancelling') { await execute(); return; }
            if (current() && stored?.state === 'ready') { setProviders(list); setPrepared(chapter); }
          })}>{t('가져오기와 번역 설정')}</button>
        </> : queue.state !== 'completed' && <button className="button-primary" disabled={busy} onClick={() => void work(() => execute())}>{t('재개 / 실패 회차 재시도')}</button>}
        {busy && <button className="button-outline" onClick={() => void command('paused')}>{t('일시정지')}</button>}
        {!['completed', 'cancelled'].includes(queue.state) && <button className="button-outline" onClick={() => void command('cancelling')}>{t('남은 작업 취소')}</button>}
      </div>
    </>}
  </section>;
}
