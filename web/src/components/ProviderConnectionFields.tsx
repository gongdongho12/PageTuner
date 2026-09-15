import { useId } from 'react';
import type { ProviderKind, TranslationProvider } from '../lib/workflowTypes';
import { translate as t } from '../lib/locale';
import './providerConnection.css';

export function ProviderConnectionFields({ provider, kind, apiKey, endpoint, model, onApiKey, onEndpoint, onModel, disabled = false, locked = false }: {
  provider?: TranslationProvider; kind: ProviderKind; apiKey: string; endpoint: string; model: string;
  onApiKey: (value: string) => void; onEndpoint: (value: string) => void; onModel: (value: string) => void;
  disabled?: boolean; locked?: boolean;
}) {
  const helpId = useId();
  const requiresKey = provider?.requiresKey ?? kind !== 'GOOGLE_WEB_TRANSLATE_HTML';
  const advanced = kind === 'DEEPSEEK' || kind === 'OPENAI_COMPATIBLE_LLM';
  return <div className="provider-connection-fields">
    <label>{t('API 키')}{provider?.configured && requiresKey ? t('(선택)') : ''}
      <input type="password" autoComplete="off" value={apiKey} maxLength={4096} aria-describedby={helpId}
        onChange={event => onApiKey(event.target.value)} disabled={disabled || !requiresKey}
        placeholder={requiresKey ? t('이번 화면에서만 사용할 키') : t('이 번역기는 키가 필요하지 않습니다')}/>
    </label>
    {advanced && <div className="workflow-field-pair">
      <label>{t('서버 주소')}<input type="url" value={endpoint} maxLength={2000} onChange={event => onEndpoint(event.target.value)}
        placeholder={provider?.defaultEndpoint || t('기본 주소 사용')} disabled={disabled || locked}/></label>
      <label>{t('모델')}<input value={model} maxLength={200} onChange={event => onModel(event.target.value)}
        placeholder={provider?.defaultModel || t('기본 모델 사용')} disabled={disabled || locked}/></label>
    </div>}
    <p id={helpId} className="workflow-help">
      {requiresKey && (provider?.configured ? t('키를 비워 두면 서버에 설정된 키를 사용합니다.') : t('이 번역기는 API 키가 필요합니다.'))}{' '}
      {t('API 키는 이번 작업을 위해 서버에 전달하며 이 기기에는 저장하지 않습니다.')}
    </p>
  </div>;
}
