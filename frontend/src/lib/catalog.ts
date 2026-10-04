import { z } from 'zod';
const httpUrl = z.string().url().refine(value => { const url = new URL(value); return ['https:', 'http:'].includes(url.protocol) && !url.username && !url.password; }, 'Use an HTTP(S) URL without credentials.');
export const savedCatalogSchema = z.object({
  url: httpUrl, title: z.string().max(1000),
  items: z.array(z.object({ id: z.string().max(500), title: z.string().max(1000), format: z.enum(['txt', 'md', 'epub', 'pdf']), href: httpUrl })).max(10_000),
});
export type SavedCatalog = z.infer<typeof savedCatalogSchema>;
export function parseCatalog(value: unknown, base: string): SavedCatalog {
  httpUrl.parse(base);
  const catalog = z.object({ version: z.literal('pagetuner.catalog.v0'), title: z.string().max(1000), items: z.array(z.object({
    id: z.string().max(500), title: z.string().max(1000), format: z.enum(['txt', 'md', 'epub', 'pdf']), href: z.string().max(4000),
  })).max(10_000) }).parse(value);
  return savedCatalogSchema.parse({ url: base, title: catalog.title,
    items: catalog.items.map(item => ({ ...item, href: new URL(item.href, base).href })) });
}
export async function fetchLimited(url: string, limit: number): Promise<Uint8Array<ArrayBuffer>> {
  httpUrl.parse(url);
  const response = await fetch(url, { credentials: 'omit', referrerPolicy: 'no-referrer', signal: AbortSignal.timeout(30_000) });
  if (!response.ok || !response.body) throw new Error(`Catalog/download: HTTP ${response.status}`);
  const reader = response.body.getReader(); const chunks: Uint8Array[] = []; let length = 0;
  while (true) {
    const { value, done } = await reader.read(); if (done) break;
    length += value.length;
    if (length > limit) { await reader.cancel(); throw new Error('Download exceeds the size limit.'); }
    chunks.push(value);
  }
  const result = new Uint8Array(length); let offset = 0;
  for (const chunk of chunks) { result.set(chunk, offset); offset += chunk.length; }
  return result;
}
