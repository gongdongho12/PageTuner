import { afterEach, describe, expect, it, vi } from 'vitest';
import { executeTranslation, requestSchema } from '../src/lib/translation-server';
import { POST } from '../src/app/api/translate/route';
import { serverRequest } from '../src/lib/server-api';
const input = requestSchema.parse({ text: 'Hello', source: 'auto', target: 'ko', provider: 'google-web' });
afterEach(() => vi.unstubAllGlobals());
describe('translation gateway', () => {
  it('joins Google segments and categorizes throttling and malformed results', async () => {
    expect(await executeTranslation(input, vi.fn().mockResolvedValue(Response.json([[['안녕', 'Hello'], ['하세요', '']]])))).toBe('안녕하세요');
    await expect(executeTranslation(input, vi.fn().mockResolvedValue(new Response('', { status: 429 })))).rejects.toMatchObject({ code: 'rate-limit' });
    await expect(executeTranslation(input, vi.fn().mockResolvedValue(Response.json({ wrong: true })))).rejects.toMatchObject({ code: 'response-format' });
  });
  it('validates keys before making paid requests and uses only the configured LLM endpoint', async () => {
    const fetcher = vi.fn().mockResolvedValue(Response.json({ choices: [{ message: { content: '안녕' } }] }));
    await expect(executeTranslation({ ...input, provider: 'llm' }, fetcher)).rejects.toMatchObject({ code: 'credentials' });
    expect(fetcher).not.toHaveBeenCalled();
    await executeTranslation({ ...input, provider: 'llm', apiKey: 'secret', model: 'reader-model' }, fetcher);
    const [url, init] = fetcher.mock.calls[0];
    expect(url.hostname).toBe('api.openai.com'); expect(init.redirect).toBe('error');
    expect(init.headers.Authorization).toBe('Bearer secret');
    expect(JSON.parse(init.body).messages[1].content).toBe('Hello');
  });
  it('blocks cross-origin requests, invalid payloads, and oversized bodies before fetching', async () => {
    const fetcher = vi.fn(); vi.stubGlobal('fetch', fetcher);
    const request = (body: string, origin = 'http://localhost:3000') => new Request('http://localhost:3000/api/translate', { method: 'POST', headers: { origin, 'Content-Type': 'application/json' }, body });
    expect((await POST(request(JSON.stringify(input), 'https://elsewhere.test'))).status).toBe(403);
    expect((await POST(request('{}'))).status).toBe(400);
    expect((await POST(request('x'.repeat(150_001)))).status).toBe(413);
    expect(fetcher).not.toHaveBeenCalled();
  });
});
describe('Spring backup adapter', () => {
  it('obtains a session CSRF token before writes, retains credentials and surfaces rejection without retry', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(Response.json({ headerName: 'X-CSRF-TOKEN', token: 'token-1' })).mockResolvedValueOnce(new Response('', { status: 403 }));
    vi.stubGlobal('fetch', fetcher);
    await expect(serverRequest('/restore', { username: 'local-reader', password: 'pass' }, { schemaVersion: 1 })).rejects.toThrow('403');
    expect(fetcher).toHaveBeenCalledTimes(2);
    expect(fetcher.mock.calls[0][0]).toBe('/api/v1/csrf');
    expect(fetcher.mock.calls[1][1]).toMatchObject({ method: 'POST', credentials: 'same-origin', headers: { 'X-CSRF-TOKEN': 'token-1' } });
  });
});
