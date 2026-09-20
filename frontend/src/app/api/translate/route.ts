import { executeTranslation, ProviderError, requestSchema } from '@/lib/translation-server';
export const runtime = 'nodejs';
export const maxDuration = 65;
export async function POST(request: Request) {
  const headers = { 'Cache-Control': 'no-store' };
  if (request.headers.get('origin') !== new URL(request.url).origin)
    return Response.json({ code: 'origin' }, { status: 403, headers });
  if (!request.headers.get('content-type')?.startsWith('application/json'))
    return Response.json({ code: 'request' }, { status: 415, headers });
  try {
    const reader = request.body?.getReader();
    if (!reader) throw new ProviderError(400, 'request');
    const chunks: Uint8Array[] = []; let length = 0;
    while (true) {
      const { done, value } = await reader.read(); if (done) break;
      length += value.length;
      if (length > 150_000) { await reader.cancel(); throw new ProviderError(413, 'request'); }
      chunks.push(value);
    }
    const input = requestSchema.safeParse(JSON.parse(Buffer.concat(chunks).toString('utf8')));
    if (!input.success) throw new ProviderError(400, 'request');
    return Response.json({ text: await executeTranslation(input.data) }, { headers });
  } catch (error) {
    const known = error instanceof ProviderError;
    return Response.json({ code: known ? error.code : 'request' }, { status: known ? error.status : 400, headers });
  }
}
