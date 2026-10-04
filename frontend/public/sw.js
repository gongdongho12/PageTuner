/* Only public application assets are cached. API responses, auth and translations never enter this cache. */
const CACHE = 'pageturner-shell-v1';
const asset = url => url.origin === self.location.origin && (url.pathname.startsWith('/_next/static/') || url.pathname === '/pdf.worker.min.mjs');
self.addEventListener('install', event => {
  event.waitUntil(caches.open(CACHE).then(cache => cache.add(new Request('/', { cache: 'reload', credentials: 'omit' }))));
});
self.addEventListener('activate', event => {
  event.waitUntil(Promise.all([self.clients.claim(), caches.keys().then(keys => Promise.all(keys.filter(key => key.startsWith('pageturner-shell-') && key !== CACHE).map(key => caches.delete(key))))]));
});
self.addEventListener('message', event => {
  if (event.data?.type !== 'CACHE_ASSETS' || !Array.isArray(event.data.urls)) return;
  const urls = event.data.urls.filter(value => typeof value === 'string' && asset(new URL(value, self.location.origin)));
  event.waitUntil(caches.open(CACHE).then(cache => Promise.allSettled(urls.map(url => cache.add(new Request(url, { credentials: 'omit' }))))));
});
self.addEventListener('fetch', event => {
  const request = event.request; const url = new URL(request.url);
  if (request.method !== 'GET' || url.origin !== self.location.origin || url.pathname.startsWith('/api/')) return;
  if (request.mode === 'navigate' && url.pathname === '/') {
    event.respondWith(fetch(request).then(response => {
      if (response.ok) { const copy = response.clone(); event.waitUntil(caches.open(CACHE).then(cache => cache.put('/', copy))); }
      return response;
    }).catch(async () => (await caches.match('/')) || Response.error()));
  } else if (asset(url)) {
    event.respondWith(caches.match(request).then(cached => cached || fetch(request).then(response => {
      if (response.ok) { const copy = response.clone(); event.waitUntil(caches.open(CACHE).then(cache => cache.put(request, copy))); }
      return response;
    })));
  }
});
