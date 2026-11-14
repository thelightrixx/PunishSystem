// Das bisherige Skript unter derselben URL für bereits registrierte Browser ablösen.
self.addEventListener('install', (event) => {
  event.waitUntil(self.skipWaiting());
});

self.addEventListener('activate', (event) => {
  event.waitUntil((async () => {
    const names = await caches.keys();
    await Promise.all(names.filter((name) => /^punishsystem-docs-v\d+$/.test(name))
      .map((name) => caches.delete(name)));
    await self.registration.unregister();
  })());
});
