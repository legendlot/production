// LOT Scanner — Service Worker
// Network-first for the app shell (index.html / navigations) so deployed updates
// always land when online; cache-first for static assets; offline falls back to cache.
// ⛔ BUMPED v3 -> v4 on 2026-09-04 (S345 hostile review) TO EVICT A POSSIBLY POISONED SHELL.
// The install-banner link was a same-origin NAVIGATION to lot-scanner.apk, so isShell() matched
// it and the network-first branch cached the APK BINARY as './index.html'. Any device that
// tapped it would, on its next OFFLINE load, be served APK bytes as the app shell — the scanner
// would download instead of render, and `install`/addAll never re-runs while sw.js is unchanged,
// so the poisoned entry would survive every reload. Changing this name forces a fresh install.
const CACHE = 'lot-scanner-v4';
const ASSETS = [
  './index.html',
  './manifest.json',
  './scanner.svg',
  './icons/icon-32.png',
  './icons/icon-180.png',
  './icons/icon-192x192.png',
  './icons/icon-512x512.png',
];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(ASSETS)));
  self.skipWaiting();
});

self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys().then(keys =>
      Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k)))
    )
  );
  self.clients.claim();
});

function isShell(req) {
  if (req.mode === 'navigate') return true;
  const u = new URL(req.url);
  return u.pathname.endsWith('/') || u.pathname.endsWith('/index.html');
}

self.addEventListener('fetch', e => {
  const req = e.request;

  // Only handle same-origin GETs; let API/cross-origin (lotopsproxy, supabase,
  // apps script) pass straight through to the network.
  if (req.method !== 'GET' || new URL(req.url).origin !== self.location.origin) {
    return;
  }

  // App shell → network-first (keeps the deployed build fresh), cache fallback offline.
  if (isShell(req)) {
    e.respondWith(
      fetch(req).then(res => {
        // ⛔ ONLY cache a response that is actually HTML. isShell() matches on request.mode
        // === 'navigate', which is ALSO true for a link to a same-origin FILE (the APK link
        // that shipped earlier today), and caching that response as './index.html' bricks the
        // scanner offline. Guard on what came back, not on what was asked for.
        const ct = res.headers.get('content-type') || '';
        if (res.ok && ct.includes('text/html')) {
          const copy = res.clone();
          caches.open(CACHE).then(c => c.put('./index.html', copy)).catch(() => {});
        }
        return res;
      }).catch(() => caches.match('./index.html'))
    );
    return;
  }

  // Static assets → cache-first, then network (and cache it).
  e.respondWith(
    caches.match(req).then(cached => cached || fetch(req).then(res => {
      const copy = res.clone();
      caches.open(CACHE).then(c => c.put(req, copy)).catch(() => {});
      return res;
    }))
  );
});
