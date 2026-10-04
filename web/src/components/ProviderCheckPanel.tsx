import { useEffect, useMemo, useSyncExternalStore } from 'react';
import type { ProviderCheckInput } from '../lib/providerCheck';
import { providerFailureMessage } from '../lib/providerCheck';
import type { TranslationProvider } from '../lib/workflowTypes';
import { translate as t } from '../lib/locale';
import { ProviderCheckSession, type ProviderCheckHandler } from './ProviderCheckSession';
import './providerConnection.css';

export function ProviderCheckPanel({ input, provider, onCheck, disabled = false }: {
  input: ProviderCheckInput; provider?: TranslationProvider; onCheck?: ProviderCheckHandler; disabled?: boolean;
}) {
  const session = useMemo(() => new ProviderCheckSession(onCheck), [onCheck]);
  const state = useSyncExternalStore(session.subscribe, session.snapshot, session.snapshot);
  useEffect(() => {
    session.configure(input);
    return () => session.cancel();
  }, [session, input.providerKind, input.targetLanguage, input.apiKey, input.endpoint, input.model]);
  const current = session.matches(input);
  const checking = current && state.busy;
  const result = current ? state.result : null;
  const target = input.targetLanguage ?? 'ko';
  const missingKey = !!provider?.requiresKey && !provider.configured && !input.apiKey?.trim();
  const validTarget = /^[A-Za-z][A-Za-z0-9-]{0,23}$/.test(target.trim()) && target.trim().toLowerCase() !== 'auto';
  return <section className="provider-check-panel" aria-label={t('연결 확인')}>
    <div className="provider-check-summary">
      <strong>{provider ? t(provider.displayName) : input.providerKind}</strong>
      <span>{t('자동 감지 → {0}', [target || '—'])}</span>
      {(input.model || provider?.defaultModel) && <span title={input.model || provider?.defaultModel}>{t('모델: {0}', [input.model || provider?.defaultModel || '—'])}</span>}
    </div>
    <p className="workflow-help">{t('서버의 짧은 예문만 자동 감지하여 선택한 언어로 번역합니다. 책 본문은 보내지 않으며 유료 번역기는 요금이 발생할 수 있습니다.')}</p>
    {!onCheck && <p className="workflow-help">{t('서버 연결 후 확인할 수 있습니다.')}</p>}
    {missingKey && <p className="workflow-help">{t('연결 설정에서 API 키를 입력해 주세요.')}</p>}
    <div className="provider-check-actions">
      <button type="button" className="button-outline" disabled={disabled || checking || !onCheck || !provider || missingKey || !validTarget}
        onClick={() => void session.run(input)}>{checking ? t('연결 확인 중…') : t('짧은 예문으로 확인')}</button>
      {checking && <button type="button" className="button-quiet" onClick={() => session.cancel()}>{t('연결 확인 취소')}</button>}
    </div>
    <div className="provider-check-result" aria-live="polite" aria-atomic="true">
      {result && <p role={result.status === 'SUCCESS' ? 'status' : 'alert'}>
        {result.status === 'SUCCESS' ? t('선택한 설정으로 번역 연결을 확인했습니다.') : t(providerFailureMessage(result.code,
          '연결을 확인하지 못했습니다. 설정과 네트워크를 확인해 주세요.'))}
      </p>}
      {current && state.failure && <p role="alert">{t(state.failureCode ? providerFailureMessage(state.failureCode,
        '연결을 확인하지 못했습니다. 설정과 네트워크를 확인해 주세요.') : state.failure === 'authentication' ?
        '서버 로그인 상태를 확인해 주세요.' : '연결을 확인하지 못했습니다. 설정과 네트워크를 확인해 주세요.')}</p>}
      {result?.status === 'SUCCESS' && <p className="workflow-help">{t('설정을 바꾸면 다시 확인해 주세요.')}</p>}
    </div>
  </section>;
}
