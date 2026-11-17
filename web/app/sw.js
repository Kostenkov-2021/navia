// Offline support: the app shell is precached; sound clips are cached when first played. Map tiles
// are cached by app.js itself (cache "navia-tiles-v1"), so they are left alone here.
const SHELL = 'navia-shell-v3';
const SOUNDS = 'navia-sounds-v1';
const FILES = ['./', 'index.html', 'style.css', 'app.js', 'sound.js', 'labels.js', 'i18n.js', 'i18n/en.json', 'manifest.webmanifest', 'icon-192.png', 'icon-512.png', 'apple-touch-icon.png'];

self.addEventListener('install', (e) => {
	e.waitUntil(caches.open(SHELL).then((c) => c.addAll(FILES)).then(() => self.skipWaiting()));
});
self.addEventListener('activate', (e) => {
	e.waitUntil(caches.keys().then((keys) => Promise.all(keys
		.filter((k) => k.startsWith('navia-shell-') && k !== SHELL || k.startsWith('navia-sounds-') && k !== SOUNDS)
		.map((k) => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener('fetch', (e) => {
	const url = new URL(e.request.url);
	if (e.request.method !== 'GET' || url.origin !== location.origin || url.pathname.includes('/api/')) return;
	if (url.pathname.includes('/sounds/')) {
		e.respondWith(caches.open(SOUNDS).then(async (c) => {
			const hit = await c.match(e.request);
			if (hit) return hit;
			const res = await fetch(e.request);
			if (res.ok) c.put(e.request, res.clone());
			return res;
		}));
		return;
	}
	// App shell: network first (so updates arrive), cache when offline. 'no-cache' makes the browser
	// check with the server instead of using its own HTTP cache, which could hold an old app.js.
	e.respondWith(fetch(e.request, { cache: 'no-cache' }).then((res) => {
		if (res.ok) { const copy = res.clone(); caches.open(SHELL).then((c) => c.put(e.request, copy)); }
		return res;
	}).catch(() => caches.match(e.request, { ignoreSearch: true }).then((r) => r || caches.match('index.html'))));
});
