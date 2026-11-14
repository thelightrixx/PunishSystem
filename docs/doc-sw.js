const cacheName = 'punishsystem-docs-v5';
const urls = ['./', './index.html', './doc-chat.css', './doc-chat.js', './doc-search-data.js', './doc-chat-model.js', './chat-model/web-llm.mjs',
  './chat-model/qwen/resolve/main/tensor-cache.json']
  .map((path) => new URL(path, self.location).href);

self.addEventListener('install', (event) => {
  event.waitUntil((async () => {
    const cache = await caches.open(cacheName);
    await cache.addAll(urls.map((url) => new Request(url, { cache: 'reload' })));
    await self.skipWaiting();
  })());
});

self.addEventListener('activate', (event) => {
  event.waitUntil((async () => {
    const names = await caches.keys();
    await Promise.all(names.filter((name) => name.startsWith('punishsystem-docs-') && name !== cacheName)
      .map((name) => caches.delete(name)));
    await self.clients.claim();
  })());
});

self.addEventListener('fetch', (event) => {
  const { request } = event;
  if (request.method !== 'GET') return;
  const url = new URL(request.url);
  url.search = '';
  if (!urls.includes(url.href)) return;
  event.respondWith((async () => {
    const cache = await caches.open(cacheName);
    try {
      const response = await fetch(request);
      if (response.ok) {
        await cache.put(url.href, response.clone()).catch((error) => console.debug('Doku-Cache nicht beschreibbar', error));
        return response;
      }
      return (await cache.match(url.href)) || response;
    } catch (error) {
      const cached = await cache.match(url.href);
      if (cached) return cached;
      throw error;
    }
  })());
});
