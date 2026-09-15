import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import type { ProviderCheckInput, ProviderCheckResult } from '../lib/providerCheck';
import { ProviderCheckError, providerFailureMessage } from '../lib/providerCheck';
import type { TranslationProvider } from '../lib/workflowTypes';
import { setLocale } from '../lib/locale';
import { ProviderCheckSession, providerCheckInput } from './ProviderCheckSession';
import { ProviderCheckPanel } from './ProviderCheckPanel';
import { ProviderConnectionFields } from './ProviderConnectionFields';

const input: ProviderCheckInput = { providerKind: 'DEEPSEEK', sourceLanguage: 'auto', targetLanguage: 'ko', apiKey: 'screen-only-test-key', endpoint: 'https://api.deepseek.com/chat/completions', model: 'deepseek-v4-flash' };
const result: ProviderCheckResult = { status: 'SUCCESS', code: 'PROVIDER_CHECK_OK', message: 'Verified', providerKind: 'DEEPSEEK', sourceLanguage: 'auto', targetLanguage: 'ko', model: 'deepseek-v4-flash' };
const provider: TranslationProvider = { id: 'DEEPSEEK', displayName: 'DeepSeek', requiresKey: true, configured: false,
  defaultEndpoint: 'https://api.deepseek.com/chat/completions', defaultModel: 'deepseek-v4-flash' };
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>(done => { resolve = done; }); return { promise, resolve }; }
afterEach(() => setLocale('ko'));

describe('mounted provider connection check', () => {
  it('projects only provider settings and always requests automatic detection of the server sample', () => {
    const request = providerCheckInput({ ...input, sourceLanguage: 'ja', chapterRecordId: 'private-book', text: 'private chapter', glossary: ['secret glossary'] } as ProviderCheckInput);
    expect(request).toEqual({ ...input, sourceLanguage: 'auto' });
    expect(request).not.toHaveProperty('chapterRecordId'); expect(request).not.toHaveProperty('text'); expect(request).not.toHaveProperty('glossary');
  });
  it('sends one request for repeated clicks and makes the result apply only to that configuration', async () => {
    const reply = deferred<ProviderCheckResult>(); const check = vi.fn((_input: ProviderCheckInput, _signal?: AbortSignal) => reply.promise); const session = new ProviderCheckSession(check);
    const first = session.run(input); const repeated = session.run(input);
    expect(check).toHaveBeenCalledTimes(1); expect(session.snapshot().busy).toBe(true);
    expect(check.mock.calls[0][0]).toEqual({ ...input, sourceLanguage: 'auto' });
    reply.resolve(result); await Promise.all([first, repeated]);
    expect(session.snapshot().result).toEqual(result);
    session.configure({ ...input, model: 'different-model' });
    expect(session.snapshot().result).toBeNull(); expect(session.matches(input)).toBe(false);
  });
  it('aborts a replaced configuration and suppresses a late response even when its transport ignores abort', async () => {
    const reply = deferred<ProviderCheckResult>(); let signal: AbortSignal | undefined;
    const session = new ProviderCheckSession(async (_input, supplied) => { signal = supplied; return reply.promise; });
    const pending = session.run(input);
    session.configure({ ...input, apiKey: 'replacement-key' });
    expect(signal?.aborted).toBe(true); reply.resolve(result); await pending;
    expect(session.snapshot()).toEqual({ busy: false, result: null, failure: null, failureCode: null });
  });
  it('cancels checks when their panel or account session closes without publishing old results', async () => {
    const reply = deferred<ProviderCheckResult>(); let signal: AbortSignal | undefined;
    const session = new ProviderCheckSession(async (_input, supplied) => { signal = supplied; return reply.promise; });
    const pending = session.run(input); session.cancel();
    expect(signal?.aborted).toBe(true); expect(session.matches(input)).toBe(false);
    reply.resolve(result); await pending; expect(session.snapshot().result).toBeNull();
  });
  it('does not expose arbitrary transport messages that contain credentials', async () => {
    const session = new ProviderCheckSession(async () => { throw Object.assign(new Error(`URL failed: ${input.apiKey}`), { code: 'ENDPOINT_NOT_ALLOWED' }); });
    await session.run(input);
    expect(session.snapshot().failure).toBe('connection'); expect(session.snapshot().failureCode).toBeNull();
    expect(JSON.stringify(session.snapshot())).not.toContain(input.apiKey);
  });
  it('preserves only validated provider-check error codes for actionable settings and rate-limit guidance', async () => {
    for (const [code, status, message] of [
      ['ENDPOINT_NOT_ALLOWED', 400, '서버에서 허용한 제공자 주소를 사용해 주세요.'],
      ['PROVIDER_CHECK_BUSY', 429, '연결 확인 요청이 많습니다. 잠시 후 다시 시도해 주세요.'],
    ] as const) {
      const session = new ProviderCheckSession(async () => { throw new ProviderCheckError(code, status); });
      await session.run(input);
      expect(session.snapshot().failureCode).toBe(code);
      expect(providerFailureMessage(session.snapshot().failureCode)).toBe(message);
      session.configure({ ...input, targetLanguage: 'ja' }); expect(session.snapshot().failureCode).toBeNull();
    }
  });
  it('renders a separate sample-check action without showing a key or sending book content during render', () => {
    const check = vi.fn(async () => result);
    const html = renderToStaticMarkup(<ProviderCheckPanel provider={provider} input={input} onCheck={check}/>);
    expect(html).toContain('짧은 예문으로 확인'); expect(html).toContain('책 본문은 보내지 않으며');
    expect(html).not.toContain(input.apiKey); expect(check).not.toHaveBeenCalled();
  });
  it('uses the same provider defaults and key requirements for paid and keyless connection fields', () => {
    const props = { provider, kind: 'DEEPSEEK' as const, apiKey: '', endpoint: '', model: '', onApiKey: () => {}, onEndpoint: () => {}, onModel: () => {} };
    const paid = renderToStaticMarkup(<ProviderConnectionFields {...props}/>);
    expect(paid).toContain('type="password"'); expect(paid).toContain('deepseek-v4-flash'); expect(paid).toContain('API 키가 필요합니다.');
    const configured = renderToStaticMarkup(<ProviderConnectionFields {...props} provider={{ ...provider, configured: true }}/>);
    expect(configured).toContain('서버에 설정된 키를 사용합니다.');
    const keyless = renderToStaticMarkup(<ProviderConnectionFields {...props} kind="GOOGLE_WEB_TRANSLATE_HTML" provider={{ ...provider, id: 'GOOGLE_WEB_TRANSLATE_HTML', requiresKey: false, configured: true }}/>);
    expect(keyless).toContain('disabled=""'); expect(keyless).not.toContain('type="url"');
  });
});
