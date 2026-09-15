import { createHash } from 'node:crypto';
import { readdir, readFile, writeFile } from 'node:fs/promises';

const dist = new URL('../dist/', import.meta.url);
const assetFiles = await readdir(new URL('assets/', dist), { recursive: true, withFileTypes: true });
// Vite emits flat hashed bundles. Reject unexpected nested output instead of missing offline assets.
if (assetFiles.some(file => file.isDirectory())) throw new Error('Update the shell manifest for nested assets.');
const shell = ['/', '/index.html', '/icon.svg', '/manifest.webmanifest', ...assetFiles.filter(file => file.isFile()).map(file => `/assets/${file.name}`)];
const hash = createHash('sha256');
for (const path of shell.filter(path => path !== '/')) hash.update(await readFile(new URL(path.slice(1), dist)));
const cacheName = `pageturner-shell-${hash.digest('hex').slice(0, 16)}`;
const script = `/* Generated public-shell manifest. Never caches authenticated content. */
const CACHE = ${JSON.stringify(cacheName)};
const SHELL = ${JSON.stringify(shell)};
self.addEventListener('install', event => {
  event.waitUntil(caches.open(CACHE).then(cache => cache.addAll(SHELL.map(path => new Request(path, {credentials: 'omit', cache: 'reload'})))));
});
self.addEventListener('activate', event => {
  event.waitUntil(caches.keys().then(keys => Promise.all(keys.filter(key => key.startsWith('pageturner-shell-') && key !== CACHE).map(key => caches.delete(key)))).then(() => self.clients.claim()));
});
self.addEventListener('fetch', event => {
  const request = event.request;
  const url = new URL(request.url);
  if (request.method !== 'GET' || url.origin !== self.location.origin || request.headers.has('Authorization') || url.pathname.startsWith('/api/') || url.search || !SHELL.includes(url.pathname)) return;
  // A matching-version shell is cache-first. Updates activate on the next visit, never mid-page.
  event.respondWith(caches.open(CACHE).then(async cache => (await cache.match(url.pathname)) || fetch(request, {credentials: 'omit'})));
});
`;
await writeFile(new URL('sw.js', dist), script);
console.log(`Prepared public offline shell (${shell.length} entries).`);
