import type { Book, Translation } from './model';
export type Credentials = { username: string; password: string };
function auth(credentials: Credentials) {
  if (!credentials.username || !credentials.password) throw new Error('Server credentials are required. / 서버 계정을 입력하세요.');
  return 'Basic ' + btoa(String.fromCharCode(...new TextEncoder().encode(`${credentials.username}:${credentials.password}`)));
}
export async function serverRequest(path: string, credentials: Credentials, body?: unknown): Promise<Response> {
  const headers: Record<string, string> = { Authorization: auth(credentials) };
  if (body !== undefined) {
    const csrf = await fetch('/api/v1/csrf', { headers, cache: 'no-store', credentials: 'same-origin' });
    if (!csrf.ok) throw new Error(`Server authentication / 서버 인증: ${csrf.status}`);
    const { headerName, token } = await csrf.json();
    if (typeof headerName !== 'string' || typeof token !== 'string') throw new Error('Invalid CSRF response.');
    headers[headerName] = token; headers['Content-Type'] = 'application/json';
  }
  const response = await fetch(`/api/v1/translations${path}`, {
    method: body === undefined ? 'GET' : 'POST', headers, credentials: 'same-origin',
    cache: 'no-store', ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    signal: AbortSignal.timeout(30_000),
  });
  if (!response.ok) throw new Error(`Server request / 서버 요청: ${response.status}`);
  return response;
}
export function toServerTranslation(book: Book, translation: Translation) {
  return { contentProviderId: 'pageturner-web', bookId: book.id, chapterId: `page-${translation.page}`,
    sourceRevision: translation.sourceRevision, sourceLanguage: translation.source,
    targetLanguage: translation.target, translationProviderId: translation.provider,
    modelId: translation.model, promptRevision: 'web-v1', glossaryRevision: '',
    paragraphs: [{ paragraphId: `page-${translation.page}-text`, text: translation.text }] };
}
