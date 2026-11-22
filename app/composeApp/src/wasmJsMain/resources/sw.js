// The service worker of the Web build (ARCH-145, PROD-070).
//
// Offline set: the app shell — start page, code, WebAssembly (the app, Skia, SQLite), worker
// scripts, the privacy page, the manifest and icons — is kept whole in a cache named after the
// build, so the app starts and every route opens without the network. The datasets are not here:
// the store worker keeps them (and the decomposition pack) in OPFS. The recognition models are
// kept by the recognition worker.
//
// Everything else is fetched when the app asks for it and kept as it passes through, without
// being promised offline (ARCH-143): recorded audio, OpenCC dictionaries, font files. The stroke
// graphics a Characters section has drawn go to their own cache, which no update clears — with
// the decomposition pack in OPFS they are the "once first used" members of the offline set.
//
// Update: a new build installs beside the running one and waits; the start page activates it on
// the next load, before the app starts (index.html), and its activation deletes the old shell.
//
// The distribution task replaces the next line with this build's version and shell file list.
// Left as it is (the development server), the worker caches nothing and only passes requests on.
const BUILD = null;

const SHELL_PREFIX = 'szdict-shell-';
const SHELL_CACHE = BUILD ? SHELL_PREFIX + BUILD.version : null;
const KEEP_CACHE = 'szdict-keep-v1';
const LAZY_CACHE = 'szdict-lazy-v1';
const FONT_HOSTS = ['cdn.jsdelivr.net'];
// Small files the app reads while it starts — before a first install controls the page — kept
// here so the first offline start finds which clips are recorded. The font index is in the shell.
const START_FILES = ['data/packs/audio/catalog.json'];
const scope = new URL(self.registration.scope);

self.addEventListener('install', (event) => {
  if (!BUILD) return;
  event.waitUntil((async () => {
    const cache = await caches.open(SHELL_CACHE);
    // Revalidated with the server, so a copy the HTTP cache holds from the page load is reused
    // when it is current and never trusted when it is not.
    await Promise.all(BUILD.shell.map(async (path) => {
      const response = await fetch(new Request(path, { cache: 'no-cache' }));
      if (!response.ok) throw new Error('precache failed for ' + path + ': HTTP ' + response.status);
      await cache.put(new URL(path, scope).href, response);
    }));
    const lazy = await caches.open(LAZY_CACHE);
    await Promise.all(START_FILES.map((path) => fetch(new Request(path, { cache: 'no-cache' }))
      .then((response) => (isOk(response) ? lazy.put(new URL(path, scope).href, response) : null))
      .catch(() => {})));
  })());
});

self.addEventListener('activate', (event) => {
  event.waitUntil((async () => {
    for (const name of await caches.keys()) {
      if (name.startsWith(SHELL_PREFIX) && name !== SHELL_CACHE) await caches.delete(name);
    }
    // The first install starts keeping lazily fetched files for the page that registered it.
    await self.clients.claim();
  })());
});

self.addEventListener('message', (event) => {
  if (event.data === 'activate') self.skipWaiting();
});

const isOk = (response) => response && response.status === 200 && (response.type === 'basic' || response.type === 'cors');

async function fromShell(request, key) {
  const cache = await caches.open(SHELL_CACHE);
  const hit = await cache.match(key || request, { ignoreSearch: true });
  return hit || fetch(request);
}

/** The stored copy when there is one; otherwise the network, keeping what arrives. */
async function cacheFirst(request, cacheName) {
  const cache = await caches.open(cacheName);
  const hit = await cache.match(request.url);
  if (hit) return hit;
  const response = await fetch(request.url, { mode: request.mode === 'no-cors' ? 'no-cors' : 'cors', credentials: 'omit' });
  if (isOk(response)) cache.put(request.url, response.clone()).catch(() => {});
  return response;
}

/** The stored copy at once, refreshed from the network behind it; the network when nothing is stored. */
async function staleWhileRevalidate(event, cacheName) {
  const request = event.request;
  const cache = await caches.open(cacheName);
  const hit = await cache.match(request.url);
  const refresh = fetch(request.url).then((response) => {
    if (isOk(response)) return cache.put(request.url, response.clone()).then(() => response);
    return response;
  });
  if (!hit) return refresh;
  event.waitUntil(refresh.catch(() => {}));
  return hit;
}

/** Media elements ask for byte ranges: answered from the whole stored clip. */
async function ranged(request, cacheName) {
  const whole = await cacheFirst(new Request(request.url), cacheName);
  const header = /^bytes=(\d+)-(\d*)$/.exec(request.headers.get('range') || '');
  if (!header || whole.status !== 200) return whole;
  const bytes = await whole.arrayBuffer();
  const start = Number(header[1]);
  const end = header[2] ? Math.min(Number(header[2]), bytes.byteLength - 1) : bytes.byteLength - 1;
  if (start > end) return new Response(null, { status: 416, headers: { 'Content-Range': 'bytes */' + bytes.byteLength } });
  return new Response(bytes.slice(start, end + 1), {
    status: 206,
    headers: {
      'Content-Type': whole.headers.get('Content-Type') || 'application/octet-stream',
      'Content-Range': 'bytes ' + start + '-' + end + '/' + bytes.byteLength,
      'Content-Length': String(end - start + 1),
      'Accept-Ranges': 'bytes',
    },
  });
}

self.addEventListener('fetch', (event) => {
  const request = event.request;
  if (!BUILD || request.method !== 'GET') return;
  const url = new URL(request.url);

  if (url.origin !== scope.origin) {
    // The CJK font from its host (ARCH-005): versioned files, kept once fetched.
    if (FONT_HOSTS.includes(url.hostname) && /\.(otf|ttf|woff2?)$/.test(url.pathname)) event.respondWith(cacheFirst(request, LAZY_CACHE));
    return;
  }
  if (!url.pathname.startsWith(scope.pathname)) return;
  const path = url.pathname.slice(scope.pathname.length);

  if (request.mode === 'navigate') {
    // Every route of the route table is the app (GLB-110, GLB-112); a navigation to a file is that file.
    const file = /\.[a-z0-9]+$/i.test(path);
    if (!file) event.respondWith(fromShell(request, new URL('index.html', scope).href));
    else if (BUILD.shell.includes(path)) event.respondWith(fromShell(request));
    return;
  }
  if (BUILD.shell.includes(path)) {
    event.respondWith(fromShell(request));
    return;
  }
  // Datasets and packs live in OPFS, models with the recognition worker, and their manifests are always asked fresh.
  if (path.startsWith('models/') || /^data\/[^/]+$/.test(path) || /^data\/packs\/[^/]+\.db$/.test(path)) return;

  if (path.startsWith('data/packs/audio/') && !path.endsWith('.json')) {
    event.respondWith(request.headers.has('range') ? ranged(request, LAZY_CACHE) : cacheFirst(request, LAZY_CACHE));
  } else if (path.startsWith('strokes/')) {
    event.respondWith(staleWhileRevalidate(event, KEEP_CACHE));
  } else if (path.startsWith('fonts/') && /-[0-9a-f]{12}\.(otf|ttf)$/.test(path)) {
    event.respondWith(cacheFirst(request, LAZY_CACHE));
  } else if (path.startsWith('data/packs/audio/') || path.startsWith('opencc/') || path.startsWith('fonts/')) {
    event.respondWith(staleWhileRevalidate(event, LAZY_CACHE));
  }
});
