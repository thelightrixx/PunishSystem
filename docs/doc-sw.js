const cacheName = 'punishsystem-docs-v6';
const runtime = new URL('./chat-model/web-llm.mjs', self.location).href;
const urls = ['./', './index.html', './doc-chat.css', './doc-chat.js', './doc-search-data.js', './doc-chat-model.js']
  .map((path) => new URL(path, self.location).href);

self.addEventListener('install', (event) => {
  event.waitUntil((async () => {
    const cache = await caches.open(cacheName);
    await cache.addAll(urls.map((url) => new Request(url, { cache: 'reload' })));
    const installedRuntime = await caches.match(runtime, { cacheName: 'punishsystem-docs-v5' });
    if (installedRuntime) await cache.put(runtime, installedRuntime);
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
  if (!urls.includes(url.href) && url.href !== runtime) return;
  event.respondWith((async () => {
    const cache = await caches.open(cacheName);
    if (url.href === runtime) {
      const installedRuntime = await cache.match(runtime);
      if (installedRuntime) return installedRuntime;
    }
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
