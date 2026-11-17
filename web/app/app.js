// Navia web: a touchable map. Map data comes from api/tile.php (the same OpenStreetMap box dbs the
// Android app uses). Two ways to hear the map:
//  - screen reader mode: an invisible grid of buttons covers the map; each is labelled with the
//    nearest feature, so TalkBack/VoiceOver explore-by-touch reads what is under the finger.
//  - voice mode: pointer events + speechSynthesis, like the app with TalkBack off.
import { WAY, NODE, ANY } from './labels.js';
import { Soundscape } from './sound.js';
import { LANGS, loadLang, t, tl, speechLang, lang } from './i18n.js';

const TILE_LAT = 0.016, TILE_LON = 0.025;           // must match api/common.php
const API = 'api/';
let TILE_CACHE = 'navia-tiles-v' + store0('mapv', 0);   // one cache per server map version
const M_PER_DEG = 6378137 * Math.PI / 180;
const ZOOMS = [0.25, 0.5, 1, 2, 4];                  // meters per CSS pixel
const CELL = 16;                                     // screen reader grid cell size in px
const POINT_BONUS_PX = 10;                           // a point this much farther than a road still wins
const MAX_TILES = 48;

const CAT = { ADDRESS: 0, HEALTH: 5, TRAFFIC: 10, BARRIER: 15, SHOP: 20, AMENITY: 25, LEISURE: 30, TOURISM: 35, NATURAL: 50, WAY: 60, INTERSECTION: 62 };

const $ = (id) => document.getElementById(id);
const canvas = $('canvas'), ctx = canvas.getContext('2d');
const mapEl = $('map'), gridEl = $('grid');

function store0(k, d) { try { const v = localStorage.getItem('navia.' + k); return v === null ? d : JSON.parse(v); } catch { return d; } }
const store = {
	get(k, d) { try { const v = localStorage.getItem('navia.' + k); return v === null ? d : JSON.parse(v); } catch { return d; } },
	set(k, v) { try { localStorage.setItem('navia.' + k, JSON.stringify(v)); } catch { } },
};

const state = {
	center: store.get('center', null),
	zoom: store.get('zoom', 2),
	mpp: 1,
	user: null,          // {lat, lon, acc}
	heading: null,       // degrees from north, compass or GPS course
	follow: false,
	aim: null,           // {lat, lon, name}
	walk: store.get('walk', false),   // announce places while walking (asked once, see askWalk)
	mode: store.get('mode', null),
	rate: store.get('rate', 1),
	showLabels: store.get('labels', true),
	detail: store.get('detail', true),
};
state.mpp = ZOOMS[state.zoom] ?? 1;

const tiles = new Map();        // "y_x" -> {nodes, ways, env, used}
const loading = new Set();
let view = null;                // projected, filtered elements of the current view (see buildView)

// ------------------------------------------------------------------ geometry

function mPerDegLon(lat) { return M_PER_DEG * Math.cos(lat * Math.PI / 180); }
function distM(a, b) {
	const R = 6371000, toR = Math.PI / 180;
	const dLat = (b.lat - a.lat) * toR, dLon = (b.lon - a.lon) * toR;
	const h = Math.sin(dLat / 2) ** 2 + Math.cos(a.lat * toR) * Math.cos(b.lat * toR) * Math.sin(dLon / 2) ** 2;
	return 2 * R * Math.asin(Math.sqrt(h));
}
function bearing(a, b) {
	const toR = Math.PI / 180;
	const y = Math.sin((b.lon - a.lon) * toR) * Math.cos(b.lat * toR);
	const x = Math.cos(a.lat * toR) * Math.sin(b.lat * toR) - Math.sin(a.lat * toR) * Math.cos(b.lat * toR) * Math.cos((b.lon - a.lon) * toR);
	return (Math.atan2(y, x) / toR + 360) % 360;
}
function clock(bearingDeg) {
	const rel = (bearingDeg - (state.heading ?? 0) + 360) % 360;
	return Math.round(rel / 30) % 12 || 12;
}
function size() { return { w: mapEl.clientWidth, h: mapEl.clientHeight }; }
function toScreen(lat, lon) {
	const { w, h } = size(), c = state.center;
	return [(lon - c.lon) * mPerDegLon(c.lat) / state.mpp + w / 2, h / 2 - (lat - c.lat) * M_PER_DEG / state.mpp];
}
function toLatLon(x, y) {
	const { w, h } = size(), c = state.center;
	return { lat: c.lat + (h / 2 - y) * state.mpp / M_PER_DEG, lon: c.lon + (x - w / 2) * state.mpp / mPerDegLon(c.lat) };
}
function segDist(px, py, ax, ay, bx, by) {
	const dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy;
	let t = l2 ? ((px - ax) * dx + (py - ay) * dy) / l2 : 0;
	t = Math.max(0, Math.min(1, t));
	const qx = ax + t * dx, qy = ay + t * dy;
	return [Math.hypot(px - qx, py - qy), qx, qy];
}

// ------------------------------------------------------------------ labels

function typeLabel(cat, type) {
	if (!type) return '';
	if (cat === CAT.WAY && WAY[type]) return tl(WAY[type]);
	if (type.startsWith('barrier: ')) {
		const k = type.slice(9);
		return k === 'yes' || k === 'no' ? t('barrier') : k.replace(/_/g, ' ');
	}
	const p = NODE[cat + ':' + type] || ANY[type];
	return p ? tl(p) : type.replace(/_/g, ' ');
}
function displayName(el) {
	if (el.type === 'address' && el.name) {
		const [num, street] = el.name.split('|');
		return [street, num].filter(Boolean).join(' ') || el.name.replace(/\|/g, ' ');
	}
	if (el.cat === CAT.INTERSECTION) return t('intersection', { names: el.name.split('|').join(t('and')) });
	if (el.name) {
		const t = typeLabel(el.cat, el.type);
		// Points get their kind too ("Rewe, supermarket"); streets are known by name alone.
		return el.kind === 'n' && t && t.toLowerCase() !== el.name.toLowerCase() ? `${el.name}, ${t}` : el.name;
	}
	return typeLabel(el.cat, el.type) || t('unnamed');
}
function relText(lat, lon) {
	if (!state.user) return '';
	const d = Math.round(distM(state.user, { lat, lon }));
	if (d < 5) return t('rel_here');
	return t('rel', { d, h: clock(bearing(state.user, { lat, lon })) });
}

// ------------------------------------------------------------------ speech

const announcer = $('announcer');
let lastSpoken = '';
function speak(text, { interrupt = true, force = false } = {}) {
	if (!force && text === lastSpoken) return;
	lastSpoken = text;
	if (state.mode === 'voice' && 'speechSynthesis' in window) {
		if (interrupt) speechSynthesis.cancel();
		const u = new SpeechSynthesisUtterance(text);
		u.rate = state.rate;
		u.lang = speechLang();
		const v = pickVoice();
		if (v) u.voice = v;
		speechSynthesis.speak(u);
	} else {
		// Screen readers read changes of an assertive live region; clear first so repeats are read.
		announcer.textContent = '';
		setTimeout(() => { announcer.textContent = text; }, 30);
	}
}
// The best installed voice for the UI language (exact region first, then any of the language).
function pickVoice() {
	const voices = speechSynthesis.getVoices?.() || [];
	const tag = speechLang().toLowerCase(), base = tag.split('-')[0];
	return voices.find((v) => v.lang.toLowerCase().replace('_', '-') === tag)
		|| voices.find((v) => v.lang.toLowerCase().split(/[-_]/)[0] === base) || null;
}
function status(text) { $('status').textContent = text; }
function vibrate(ms) { try { navigator.vibrate?.(ms); } catch { } }

// ------------------------------------------------------------------ tiles

function tileKey(x, y) { return `${y}_${x}`; }
function decodePts(arr) {
	const n = arr.length / 2, lat = new Float64Array(n), lon = new Float64Array(n);
	let a = 0, b = 0;
	for (let i = 0; i < n; i++) { a += arr[2 * i]; b += arr[2 * i + 1]; lat[i] = a / 1e6; lon[i] = b / 1e6; }
	return { lat, lon };
}
function parseTile(d) {
	return {
		nodes: d.n.map(([id, lat, lon, cat, type, name]) => ({ kind: 'n', id, lat, lon, cat, type, name })),
		ways: d.w.map(([id, cat, type, name, p]) => ({ kind: 'w', id, cat, type, name, ...decodePts(p) })),
		env: d.e.map(([id, kind, type, name, p]) => ({ kind: 'e', id, env: kind, type, name, ...decodePts(p) })),
		used: Date.now(),
	};
}
async function fetchTile(x, y) {
	const url = new URL(`${API}tile.php?x=${x}&y=${y}`, location.href).href;
	let cache = null;
	try { cache = await caches.open(TILE_CACHE); } catch { }
	let res = cache && await cache.match(url);
	if (!res) {
		// The map version in the URL keeps the browser's HTTP cache (tiles are sent with a 7-day max-age)
		// from serving a tile of the previous map after an update. The Cache API key stays without it.
		res = await fetch(url + '&v=' + TILE_CACHE.slice('navia-tiles-v'.length));
		if (!res.ok) throw new Error('HTTP ' + res.status);
		if (cache) { try { await cache.put(url, res.clone()); } catch { } }
	}
	return parseTile(await res.json());
}
function neededTiles(marginPx = 200) {
	const { w, h } = size();
	const a = toLatLon(-marginPx, h + marginPx), b = toLatLon(w + marginPx, -marginPx);
	const out = [];
	for (let y = Math.floor(a.lat / TILE_LAT); y <= Math.floor(b.lat / TILE_LAT); y++)
		for (let x = Math.floor(a.lon / TILE_LON); x <= Math.floor(b.lon / TILE_LON); x++) out.push([x, y]);
	return out;
}
let loadAnnounced = false, loadStart = 0;
function loadTiles() {
	if (!state.center) return;
	const want = neededTiles();
	let pending = 0;
	for (const [x, y] of want) {
		const k = tileKey(x, y);
		const t = tiles.get(k);
		if (t) { t.used = Date.now(); continue; }
		if (loading.has(k)) { pending++; continue; }
		loading.add(k); pending++;
		fetchTile(x, y).then((t) => {
			tiles.set(k, t);
			evictTiles();
			scheduleRender(true);
		}).catch((e) => {
			console.warn('tile', k, e);
			status(navigator.onLine ? t('load_failed') : t('offline_missing'));
		}).finally(() => {
			loading.delete(k);
			if (loading.size === 0 && loadAnnounced) {
				loadAnnounced = false;
				status(t('map_loaded'));
				// Only worth saying when the user had to wait.
				if (Date.now() - loadStart > 3000 && state.mode === 'voice') speak(t('map_loaded'), { interrupt: false, force: true });   // screen readers hear the polite status
			}
		});
	}
	if (pending && !loadAnnounced) {
		loadAnnounced = true;
		loadStart = Date.now();
		status(t('loading'));
	}
}
function evictTiles() {
	if (tiles.size <= MAX_TILES) return;
	[...tiles.entries()].sort((a, b) => a[1].used - b[1].used).slice(0, tiles.size - MAX_TILES).forEach(([k]) => tiles.delete(k));
}

// ------------------------------------------------------------------ which elements can be touched

const MINOR_WAYS = new Set(['footway', 'path', 'service', 'cycleway', 'steps', 'corridor', 'pedestrian', 'platform', 'track']);
// Same rules as the Android app (MapTileManager.passesFilter / calculateDetailsLevel): each category
// needs a minimum zoom, and paths and cycleways are never touchable. The app's zoom is 200 m divided by
// the map width, in powers of two, so the same rules apply to the same area of map on any screen.
const TOUCH_EXCLUDED_TYPES = new Set(['path', 'cycleway']);
const DETAIL_WAYS = new Set(['pedestrian', 'footway', 'path', 'cycleway', 'steps', 'corridor', 'via_ferrata', 'service']);
const CAT_DETAIL = { 0: 0.5, 25: 0.5, 15: 1, 5: 0, 30: 0.5, 50: 2, 20: 0.25, 35: 0.25, 10: 1 };
function appZoom() {
	return 2 ** Math.round(Math.log2(200 / (state.mpp * size().w)));
}
function detailLevel(cat, type) {
	if (cat === CAT.WAY) return DETAIL_WAYS.has(type) ? 2 : 0;
	return CAT_DETAIL[cat] ?? 0;
}
function touchable(el, z = appZoom()) {
	if (TOUCH_EXCLUDED_TYPES.has(el.type)) return false;
	if (el.cat === CAT.ADDRESS && !state.detail) return false;   // web-only setting: house numbers
	return detailLevel(el.cat, el.type) <= z;
}

// Projects all elements of the loaded tiles into screen space and buckets them for nearest queries.
function buildView() {
	const { w, h } = size();
	const B = 32, cols = Math.ceil(w / B) + 1, rows = Math.ceil(h / B) + 1;
	const buckets = Array.from({ length: cols * rows }, () => []);
	const seen = new Set();
	const nodes = [], ways = [], env = [];
	const margin = 80;
	const c = state.center, kx = mPerDegLon(c.lat) / state.mpp, ky = M_PER_DEG / state.mpp;
	const zoom = appZoom();
	const sx = (lon) => (lon - c.lon) * kx + w / 2, sy = (lat) => h / 2 - (lat - c.lat) * ky;
	const addBucket = (x0, y0, x1, y1, item) => {
		const bx0 = Math.max(0, Math.floor(Math.min(x0, x1) / B)), bx1 = Math.min(cols - 1, Math.floor(Math.max(x0, x1) / B));
		const by0 = Math.max(0, Math.floor(Math.min(y0, y1) / B)), by1 = Math.min(rows - 1, Math.floor(Math.max(y0, y1) / B));
		for (let by = by0; by <= by1; by++) for (let bx = bx0; bx <= bx1; bx++) buckets[by * cols + bx].push(item);
	};
	for (const t of tiles.values()) {
		for (const n of t.nodes) {
			if (seen.has(n.id)) continue;
			seen.add(n.id);
			const x = sx(n.lon), y = sy(n.lat);
			if (x < -margin || y < -margin || x > w + margin || y > h + margin) continue;
			const ok = touchable(n, zoom);
			const p = { el: n, x, y, ok };
			nodes.push(p);
			if (ok) addBucket(x, y, x, y, p);
		}
		for (const wy of t.ways) {
			if (seen.has(wy.id)) continue;
			seen.add(wy.id);
			const n = wy.lat.length;
			const xs = new Float32Array(n), ys = new Float32Array(n);
			let minx = 1e9, miny = 1e9, maxx = -1e9, maxy = -1e9;
			for (let i = 0; i < n; i++) {
				xs[i] = sx(wy.lon[i]); ys[i] = sy(wy.lat[i]);
				minx = Math.min(minx, xs[i]); maxx = Math.max(maxx, xs[i]); miny = Math.min(miny, ys[i]); maxy = Math.max(maxy, ys[i]);
			}
			if (maxx < -margin || maxy < -margin || minx > w + margin || miny > h + margin) continue;
			const ok = touchable(wy, zoom);
			const p = { el: wy, xs, ys, ok };
			ways.push(p);
			if (ok) for (let i = 1; i < n; i++) addBucket(xs[i - 1], ys[i - 1], xs[i], ys[i], { w: p, i });
		}
		for (const e of t.env) {
			if (seen.has('e' + e.id)) continue;
			seen.add('e' + e.id);
			const n = e.lat.length, xs = new Float32Array(n), ys = new Float32Array(n);
			for (let i = 0; i < n; i++) { xs[i] = sx(e.lon[i]); ys[i] = sy(e.lat[i]); }
			env.push({ el: e, xs, ys });
		}
	}
	view = { w, h, B, cols, rows, buckets, nodes, ways, env };
}

// Nearest touchable element to a screen point, with the app's rules: points beat roads when they are
// nearly as close, and nothing farther than 15 % of the map width counts.
function nearest(x, y) {
	ensureView();
	if (!view) return null;
	const { B, cols, rows, buckets, w } = view;
	const maxD = Math.max(36, w * 0.15);
	const r = Math.ceil(maxD / B);
	const bx = Math.floor(x / B), by = Math.floor(y / B);
	let bn = null, dn = Infinity, bw = null, dw = Infinity, wq = null;
	for (let yy = Math.max(0, by - r); yy <= Math.min(rows - 1, by + r); yy++) {
		for (let xx = Math.max(0, bx - r); xx <= Math.min(cols - 1, bx + r); xx++) {
			for (const it of buckets[yy * cols + xx]) {
				if (it.el) {
					const d = Math.hypot(it.x - x, it.y - y);
					if (d < dn) { dn = d; bn = it; }
				} else {
					const p = it.w, i = it.i;
					const [d, qx, qy] = segDist(x, y, p.xs[i - 1], p.ys[i - 1], p.xs[i], p.ys[i]);
					if (d < dw) { dw = d; bw = p; wq = [qx, qy]; }
				}
			}
		}
	}
	if (bn && dn <= maxD && dn <= dw + POINT_BONUS_PX) return { el: bn.el, x: bn.x, y: bn.y, d: dn };
	if (bw && dw <= maxD) return { el: bw.el, x: wq[0], y: wq[1], d: dw };
	return null;
}

function describeHit(hit) {
	const p = toLatLon(hit.x, hit.y);
	const at = hit.el.kind === 'n' ? { lat: hit.el.lat, lon: hit.el.lon } : p;
	return displayName(hit.el) + relText(at.lat, at.lon);
}

// ------------------------------------------------------------------ drawing

const COLORS = {
	light: { bg: '#f2efe9', major: '#e8925a', mid: '#f2d16b', minor: '#ffffff', minorEdge: '#b8b0a4', path: '#8a7f73', barrier: '#6b6b6b', building: '#d9d0c4', water: '#9cc5e8', rail: '#555', text: '#1b1b1b', halo: '#ffffff', user: '#0d5ea8', aim: '#d0021b' },
	dark: { bg: '#141a21', major: '#c76b2f', mid: '#a88d2f', minor: '#4b5562', minorEdge: '#2b333d', path: '#7d8794', barrier: '#8a8a8a', building: '#222a33', water: '#1d4a70', rail: '#9aa4ae', text: '#eef2f6', halo: '#000000', user: '#4ea3f5', aim: '#ff5a5a' },
};
const CAT_COLOR = { 0: '#8a8a8a', 5: '#d0021b', 10: '#0d5ea8', 15: '#6b6b6b', 20: '#8e44ad', 25: '#d35400', 30: '#27ae60', 35: '#c0398b', 50: '#2e8b57', 62: '#555555' };
const MAJOR = new Set(['motorway', 'motorway_link', 'trunk', 'trunk_link', 'primary', 'primary_link']);
const MID = new Set(['secondary', 'secondary_link', 'tertiary', 'tertiary_link']);

function draw() {
	const dpr = window.devicePixelRatio || 1;
	const { w, h } = size();
	if (canvas.width !== Math.round(w * dpr) || canvas.height !== Math.round(h * dpr)) {
		canvas.width = Math.round(w * dpr); canvas.height = Math.round(h * dpr);
	}
	const C = matchMedia('(prefers-color-scheme: dark)').matches ? COLORS.dark : COLORS.light;
	ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
	ctx.fillStyle = C.bg;
	ctx.fillRect(0, 0, w, h);
	if (!view) return;
	const k = 1 / state.mpp;   // px per meter
	const line = (p) => { ctx.beginPath(); ctx.moveTo(p.xs[0], p.ys[0]); for (let i = 1; i < p.xs.length; i++) ctx.lineTo(p.xs[i], p.ys[i]); };

	for (const p of view.env) {
		const t = p.el.env;
		line(p);
		if (t === 'lake' || p.el.type === 'river_area') { ctx.fillStyle = C.water; ctx.fill(); }
		else if (t === 'river') { ctx.strokeStyle = C.water; ctx.lineWidth = Math.max(2, 8 * k); ctx.setLineDash([]); ctx.stroke(); }
		else if (t === 'rail') { ctx.strokeStyle = C.rail; ctx.lineWidth = 2; ctx.setLineDash([6, 4]); ctx.stroke(); ctx.setLineDash([]); }
	}
	for (const p of view.ways) if (p.el.type === 'address' && p.ok) { line(p); ctx.fillStyle = C.building; ctx.fill(); }

	const order = [['minorpath', C.path], ['minor', C.minor], ['mid', C.mid], ['major', C.major]];
	for (const [cls, color] of order) {
		for (const p of view.ways) {
			const t = p.el.type;
			const c = MAJOR.has(t) ? 'major' : MID.has(t) ? 'mid' : t.startsWith('barrier: ') ? 'barrier' : t === 'address' ? 'building' : MINOR_WAYS.has(t) && t !== 'service' ? 'minorpath' : 'minor';
			if (c !== cls) continue;
			if (!p.ok) continue;   // like the app, only what touch can find is drawn
			line(p);
			ctx.lineJoin = ctx.lineCap = 'round';
			if (cls === 'minorpath') { ctx.strokeStyle = color; ctx.lineWidth = 1.5; ctx.setLineDash([4, 3]); ctx.stroke(); ctx.setLineDash([]); continue; }
			const width = Math.max(3, (cls === 'major' ? 14 : cls === 'mid' ? 11 : 8) * k);
			ctx.strokeStyle = C.minorEdge; ctx.lineWidth = width + 2; ctx.stroke();
			ctx.strokeStyle = color; ctx.lineWidth = width; ctx.stroke();
		}
	}
	for (const p of view.ways) if (p.el.type.startsWith('barrier: ') && p.ok) { line(p); ctx.strokeStyle = C.barrier; ctx.lineWidth = 1; ctx.stroke(); }

	for (const p of view.nodes) {
		if (!p.ok || p.el.cat === CAT.ADDRESS || p.el.cat === CAT.INTERSECTION) continue;
		ctx.beginPath();
		ctx.arc(p.x, p.y, p.el.name ? 5 : 3.5, 0, Math.PI * 2);
		ctx.fillStyle = CAT_COLOR[p.el.cat] || '#555';
		ctx.fill();
		ctx.lineWidth = 1.5; ctx.strokeStyle = C.halo; ctx.stroke();
	}
	if (state.showLabels) drawLabels(C);

	if (state.aim) {
		const [x, y] = toScreen(state.aim.lat, state.aim.lon);
		ctx.strokeStyle = C.aim; ctx.lineWidth = 3;
		ctx.beginPath(); ctx.arc(x, y, 12, 0, Math.PI * 2); ctx.stroke();
	}
	if (state.user) {
		const [x, y] = toScreen(state.user.lat, state.user.lon);
		ctx.beginPath(); ctx.arc(x, y, 9, 0, Math.PI * 2);
		ctx.fillStyle = C.user; ctx.fill(); ctx.lineWidth = 3; ctx.strokeStyle = C.halo; ctx.stroke();
		if (state.heading != null) {
			const a = (state.heading - 90) * Math.PI / 180;
			ctx.beginPath(); ctx.moveTo(x + Math.cos(a) * 22, y + Math.sin(a) * 22);
			ctx.lineTo(x + Math.cos(a + 2.6) * 11, y + Math.sin(a + 2.6) * 11);
			ctx.lineTo(x + Math.cos(a - 2.6) * 11, y + Math.sin(a - 2.6) * 11);
			ctx.closePath(); ctx.fillStyle = C.user; ctx.fill();
		}
	}
}

function drawLabels(C) {
	ctx.font = '600 13px system-ui, sans-serif';
	ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
	ctx.lineWidth = 3; ctx.strokeStyle = C.halo; ctx.fillStyle = C.text;
	const placed = [];
	const free = (x, y, tw) => !placed.some((r) => Math.abs(r[0] - x) < (r[2] + tw) / 2 + 4 && Math.abs(r[1] - y) < 16);
	const used = new Set();
	// Street names at the middle of their longest visible way.
	const byName = new Map();
	for (const p of view.ways) {
		if (!p.el.name || !p.ok || p.el.type === 'address' || p.el.type.startsWith('barrier')) continue;
		let len = 0;
		for (let i = 1; i < p.xs.length; i++) len += Math.hypot(p.xs[i] - p.xs[i - 1], p.ys[i] - p.ys[i - 1]);
		const cur = byName.get(p.el.name);
		if (!cur || cur.len < len) byName.set(p.el.name, { p, len });
	}
	for (const [name, { p, len }] of byName) {
		const tw = ctx.measureText(name).width;
		if (len < tw * 0.8) continue;
		let acc = 0, i = 1;
		for (; i < p.xs.length; i++) { const s = Math.hypot(p.xs[i] - p.xs[i - 1], p.ys[i] - p.ys[i - 1]); if (acc + s >= len / 2) break; acc += s; }
		i = Math.min(i, p.xs.length - 1);
		const x = (p.xs[i] + p.xs[i - 1]) / 2, y = (p.ys[i] + p.ys[i - 1]) / 2;
		if (x < 0 || y < 0 || x > view.w || y > view.h || !free(x, y, tw)) continue;
		let ang = Math.atan2(p.ys[i] - p.ys[i - 1], p.xs[i] - p.xs[i - 1]);
		if (ang > Math.PI / 2) ang -= Math.PI; else if (ang < -Math.PI / 2) ang += Math.PI;
		ctx.save(); ctx.translate(x, y); ctx.rotate(ang);
		ctx.strokeText(name, 0, 0); ctx.fillText(name, 0, 0);
		ctx.restore();
		placed.push([x, y, tw]);
	}
	if (state.mpp > 1) return;
	ctx.font = '12px system-ui, sans-serif';
	for (const p of view.nodes) {
		if (!p.ok || !p.el.name || p.el.cat === CAT.ADDRESS || p.el.cat === CAT.INTERSECTION || used.has(p.el.name)) continue;
		const tw = ctx.measureText(p.el.name).width;
		const y = p.y - 13;
		if (!free(p.x, y, tw)) continue;
		ctx.strokeText(p.el.name, p.x, y); ctx.fillText(p.el.name, p.x, y);
		placed.push([p.x, y, tw]); used.add(p.el.name);
	}
}

// ------------------------------------------------------------------ screen reader grid

let gridTimer = 0, gridFocused = false;
// Rebuilding removes the button the screen reader is on, so wait while the user is on the map.
function queueGrid() {
	clearTimeout(gridTimer);
	gridTimer = setTimeout(() => {
		if (gridFocused) return;   // focusout queues it again
		gridDirty = false;
		ensureView();
		buildGrid();
	}, 250);
}
gridEl.addEventListener('focusin', () => { gridFocused = true; });
gridEl.addEventListener('focusout', () => { gridFocused = false; if (gridDirty) queueGrid(); });
function buildGrid() {
	if (!view) return;
	const { w, h } = view;
	const cols = Math.ceil(w / CELL), rows = Math.ceil(h / CELL);
	const frag = document.createDocumentFragment();
	for (let r = 0; r < rows; r++) {
		let run = null;
		const flush = () => {
			if (!run) return;
			const b = document.createElement('button');
			b.className = 'cell';
			b.type = 'button';
			b.textContent = run.text;
			b.style.left = run.c0 * CELL + 'px';
			b.style.top = r * CELL + 'px';
			b.style.width = (run.c1 - run.c0 + 1) * CELL + 'px';
			b.style.height = CELL + 'px';
			b._hit = run.hit;
			frag.appendChild(b);
			run = null;
		};
		for (let c = 0; c < cols; c++) {
			const hit = nearest((c + 0.5) * CELL, (r + 0.5) * CELL);
			if (!hit) { flush(); continue; }
			if (run && (run.hit.el === hit.el || hit.el.name && run.hit.el.name === hit.el.name && run.hit.el.kind === hit.el.kind)) { run.c1 = c; continue; }
			flush();
			run = { hit, text: describeHit(hit), c0: c, c1: c };
		}
		flush();
	}
	gridEl.replaceChildren(frag);
}
gridEl.addEventListener('click', (e) => {
	const b = e.target.closest('.cell');
	if (!b || !b._hit || state.mode !== 'sr') return;
	openPlace(b._hit);
});

// ------------------------------------------------------------------ render scheduling

let renderQueued = false, gridDirty = false, viewDirty = true;
// Touch lookups must not wait for the next animation frame (frames pause in background tabs).
function ensureView() {
	if ((viewDirty || !view) && state.center) { buildView(); viewDirty = false; }
}
function scheduleRender(grid = false) {
	gridDirty = gridDirty || grid;
	viewDirty = true;
	if (gridDirty && state.mode === 'sr') queueGrid();   // not tied to animation frames, which pause in hidden tabs
	if (renderQueued) return;
	renderQueued = true;
	requestAnimationFrame(() => {
		renderQueued = false;
		if (!state.center) return;
		ensureView();
		draw();
	});
}
function viewChanged() {
	store.set('center', state.center);
	store.set('zoom', state.zoom);
	loadTiles();
	scheduleRender(true);
}

// ------------------------------------------------------------------ voice mode touch

const pointers = new Map();
function local(e) { const r = mapEl.getBoundingClientRect(); return [e.clientX - r.left, e.clientY - r.top]; }
let lastHitId = null, holdTimer = 0, gesture = null, lastHit = null;
mapEl.addEventListener('pointerdown', (e) => {
	if (state.mode !== 'voice') return;
	try { mapEl.setPointerCapture(e.pointerId); } catch { }
	const [ex, ey] = local(e);
	pointers.set(e.pointerId, { x: ex, y: ey });
	if (pointers.size === 2) {
		clearTimeout(holdTimer);
		const [a, b] = [...pointers.values()];
		gesture = { cx: (a.x + b.x) / 2, cy: (a.y + b.y) / 2, d: Math.hypot(a.x - b.x, a.y - b.y), center: { ...state.center }, mpp: state.mpp };
		speechSynthesis.cancel();
		return;
	}
	exploreAt(ex, ey);
	startHold(ex, ey);
});
mapEl.addEventListener('pointermove', (e) => {
	if (state.mode !== 'voice' || !pointers.has(e.pointerId)) return;
	const prev = pointers.get(e.pointerId);
	const [ex, ey] = local(e);
	pointers.set(e.pointerId, { x: ex, y: ey });
	if (gesture && pointers.size >= 2) {
		const [a, b] = [...pointers.values()];
		const cx = (a.x + b.x) / 2, cy = (a.y + b.y) / 2, d = Math.hypot(a.x - b.x, a.y - b.y);
		state.mpp = Math.min(6, Math.max(0.15, gesture.mpp * gesture.d / Math.max(d, 1)));
		state.center = {
			lat: gesture.center.lat + (cy - gesture.cy) * state.mpp / M_PER_DEG,
			lon: gesture.center.lon - (cx - gesture.cx) * state.mpp / mPerDegLon(gesture.center.lat),
		};
		state.follow = false;
		scheduleRender();
		return;
	}
	if (Math.hypot(ex - prev.x, ey - prev.y) > 0) {
		if (holdStart && Math.hypot(ex - holdStart.x, ey - holdStart.y) > 12) clearTimeout(holdTimer);
		exploreAt(ex, ey);
	}
});
const endPointer = (e) => {
	pointers.delete(e.pointerId);
	clearTimeout(holdTimer);
	if (gesture && pointers.size < 2) {
		gesture = null;
		state.zoom = ZOOMS.reduce((bi, z, i) => Math.abs(Math.log(z / state.mpp)) < Math.abs(Math.log(ZOOMS[bi] / state.mpp)) ? i : bi, 0);
		viewChanged();
	}
	if (pointers.size === 0) lastHitId = null;
};
mapEl.addEventListener('pointerup', endPointer);
mapEl.addEventListener('pointercancel', endPointer);
let holdStart = null;
function startHold(x, y) {
	holdStart = { x, y };
	clearTimeout(holdTimer);
	holdTimer = setTimeout(() => {
		if (lastHit) { vibrate(60); openPlace(lastHit); }
	}, 700);
}
function exploreAt(x, y) {
	const hit = nearest(x, y);
	if (!hit) { lastHitId = null; return; }
	lastHit = hit;
	// One street is often several OSM ways: named things are compared by name, unnamed ones by id.
	const key = hit.el.name ? hit.el.kind + ':' + hit.el.name : hit.el.id;
	if (key === lastHitId) return;
	lastHitId = key;
	vibrate(hit.el.kind === 'n' ? 25 : 12);
	speak(describeHit(hit), { force: true });
}

// ------------------------------------------------------------------ place dialog, aim

let placeHit = null;
function openPlace(hit) {
	placeHit = hit;
	const at = placeLatLon(hit);
	$('place-title').textContent = displayName(hit.el);
	const parts = [];
	const t = typeLabel(hit.el.cat, hit.el.type);
	if (t && hit.el.name) parts.push(t);
	if (state.user) {
		const d = Math.round(distM(state.user, at));
		parts.push(t('place_dist', { d, h: clock(bearing(state.user, at)) }));
	}
	$('place-info').textContent = parts.join('. ');
	showDialog('dlg-place');
}
function placeLatLon(hit) { return hit.el.kind === 'n' ? { lat: hit.el.lat, lon: hit.el.lon } : toLatLon(hit.x, hit.y); }

$('place-aim').onclick = async () => {
	const at = placeLatLon(placeHit);
	closeDialogs();
	await askCompass();
	state.aim = { ...at, name: displayName(placeHit.el).split(',')[0] };
	$('btn-aim-stop').hidden = false;
	startGps();
	lastAimText = '';
	lastAimTime = 0;
	speak(t('aiming_at', { name: state.aim.name }) + (state.user ? relText(at.lat, at.lon) : t('waiting_location')), { force: true });
	scheduleRender();
};
$('place-center').onclick = () => {
	const at = placeLatLon(placeHit);
	closeDialogs();
	state.center = at; state.follow = false;
	viewChanged();
	speak(t('centered_on', { name: displayName(placeHit.el) }), { force: true });
};
$('place-sound').onclick = () => {
	const at = placeLatLon(placeHit);
	closeDialogs();
	openSound(at, displayName(placeHit.el));
};
$('btn-aim-stop').onclick = () => {
	state.aim = null;
	$('btn-aim-stop').hidden = true;
	speak(t('aim_stopped'), { force: true });
	scheduleRender();
};

let lastAimText = '', lastAimTime = 0;
function updateAim() {
	if (!state.aim || !state.user) return;
	const d = Math.round(distM(state.user, state.aim));
	if (d <= Math.max(10, state.user.acc || 0)) {
		speak(t('arrived', { name: state.aim.name }), { force: true });
		vibrate([80, 60, 80]);
		state.aim = null;
		$('btn-aim-stop').hidden = true;
		return;
	}
	const step = d > 500 ? 100 : d > 100 ? 20 : 5;
	const text = t('aim_text', { name: state.aim.name, d: Math.round(d / step) * step, h: clock(bearing(state.user, state.aim)) });
	const now = Date.now();
	if (text !== lastAimText && now - lastAimTime > 4000 || now - lastAimTime > 20000) {
		lastAimText = text; lastAimTime = now;
		speak(text, { interrupt: false, force: true });
	}
}

// Places announced while walking (like the app's background announcements, but only while the page is open).
// Only while really walking (more than WALK_MIN_M in the last minute), so it stays quiet at home, and
// never while the user is exploring the map or has a dialog open.
const walkAnnounced = new Map();
const walkTrack = [];            // [time, {lat, lon}] of the last minute
const WALK_MIN_M = 20;
let lastWalkTime = 0;
function isWalking() {
	const now = Date.now();
	walkTrack.push([now, state.user]);
	while (walkTrack.length && now - walkTrack[0][0] > 60000) walkTrack.shift();
	return walkTrack.length > 1 && distM(walkTrack[0][1], state.user) > WALK_MIN_M;
}
function userBusy() {
	return pointers.size > 0 || gridFocused || !!document.querySelector('dialog[open]');
}
function updateWalk() {
	if (!state.walk || !state.user) return;
	if (!isWalking() || userBusy() || Date.now() - lastWalkTime < 5000) return;
	let best = null, bd = 30;
	for (const t of tiles.values()) for (const n of t.nodes) {
		if (!n.name || n.cat === CAT.ADDRESS || n.cat === CAT.INTERSECTION) continue;
		const d = distM(state.user, n);
		if (d >= bd) continue;
		const last = walkAnnounced.get(n.id);
		if (last && Date.now() - last < 10 * 60000) continue;
		if (state.heading != null) {
			const rel = (bearing(state.user, n) - state.heading + 360) % 360;
			if (rel > 100 && rel < 260) continue;   // behind you
		}
		best = n; bd = d;
	}
	if (!best) return;
	walkAnnounced.set(best.id, Date.now());
	lastWalkTime = Date.now();
	speak(displayName(best) + relText(best.lat, best.lon), { interrupt: false, force: true });
}

// ------------------------------------------------------------------ location and compass

let gpsWatch = null;
function startGps(onFirst) {
	if (!('geolocation' in navigator)) { speak(t('no_geo'), { force: true }); return; }
	if (gpsWatch !== null) { if (onFirst && state.user) onFirst(); else if (onFirst) pendingFirst = onFirst; return; }
	pendingFirst = onFirst;
	gpsWatch = navigator.geolocation.watchPosition((p) => {
		const prev = state.user;
		state.user = { lat: p.coords.latitude, lon: p.coords.longitude, acc: p.coords.accuracy };
		if (p.coords.heading != null && !isNaN(p.coords.heading) && (p.coords.speed || 0) > 0.8 && !compassActive) state.heading = p.coords.heading;
		if (pendingFirst) { const f = pendingFirst; pendingFirst = null; f(); }
		if (state.follow) { state.center = { lat: state.user.lat, lon: state.user.lon }; }
		const moved = !prev || distM(prev, state.user) > 8;
		// While following, refresh tiles and the touch grid at most every 15 s (the grid must not
		// change under a finger that is exploring it).
		if (state.follow && moved && Date.now() - lastGridGps > 15000) { lastGridGps = Date.now(); viewChanged(); }
		else { loadTiles(); scheduleRender(false); }
		updateAim();
		updateWalk();
	}, (err) => {
		gpsWatch = null;
		speak(err.code === 1 ? t('geo_denied') : t('geo_unavailable'), { force: true });
	}, { enableHighAccuracy: true, maximumAge: 2000, timeout: 30000 });
}
let pendingFirst = null, lastGridGps = 0, compassActive = false;

async function askCompass() {
	if (compassActive) return;
	try {
		if (typeof DeviceOrientationEvent !== 'undefined' && typeof DeviceOrientationEvent.requestPermission === 'function') {
			if (await DeviceOrientationEvent.requestPermission() !== 'granted') return;
		}
	} catch { return; }
	const onOrient = (e) => {
		let hdg = null;
		if (typeof e.webkitCompassHeading === 'number') hdg = e.webkitCompassHeading;
		else if (e.absolute && e.alpha != null) hdg = 360 - e.alpha;
		if (hdg == null) return;
		const so = (screen.orientation && screen.orientation.angle) || window.orientation || 0;
		state.heading = (hdg + so + 360) % 360;
		compassActive = true;
	};
	if ('ondeviceorientationabsolute' in window) window.addEventListener('deviceorientationabsolute', onOrient);
	else window.addEventListener('deviceorientation', onOrient);
}

// ------------------------------------------------------------------ controls

function setZoom(z) {
	state.zoom = Math.max(0, Math.min(ZOOMS.length - 1, z));
	state.mpp = ZOOMS[state.zoom];
	viewChanged();
	speak(t('map_width', { d: Math.round(size().w * state.mpp) }), { force: true });
}
function move(dx, dy, name) {
	const { w, h } = size();
	const m = Math.round((dx ? w : h) * state.mpp / 2);
	const c = toLatLon(w / 2 + dx * w / 2, h / 2 + dy * h / 2);
	state.center = c; state.follow = false;
	viewChanged();
	setTimeout(() => speak(t('moved', { d: m, dir: t(name), center: centerDescription() }), { force: true }), 50);
}
function centerDescription() {
	ensureView();
	if (!view) return '';
	const { w, h } = size();
	const hit = nearest(w / 2, h / 2);
	return hit ? t('map_center', { what: describeHit(hit) }) : t('nothing_center');
}
$('btn-zoom-in').onclick = () => setZoom(state.zoom - 1);
$('btn-zoom-out').onclick = () => setZoom(state.zoom + 1);
$('btn-north').onclick = () => move(0, -1, 'dir_n');
$('btn-south').onclick = () => move(0, 1, 'dir_s');
$('btn-west').onclick = () => move(-1, 0, 'dir_w');
$('btn-east').onclick = () => move(1, 0, 'dir_e');
$('btn-locate').onclick = async () => {
	await askCompass();
	speak(t('finding_location'), { force: true });
	startGps(() => {
		state.follow = true;
		state.center = { lat: state.user.lat, lon: state.user.lon };
		viewChanged();
		setTimeout(() => speak(t('centered_you', { center: centerDescription() }), { force: true }), 400);
		setTimeout(askWalk, 4000);
	});
};
// Asked once, the first time the user has shared their location on purpose (My location).
function askWalk() {
	if (store.get('walkAsked', false) || state.walk) return;
	if (document.querySelector('dialog[open]')) { setTimeout(askWalk, 10000); return; }
	store.set('walkAsked', true);
	showDialog('dlg-walk-ask');
}
function setWalk(on) {
	state.walk = on;
	store.set('walk', on);
	$('btn-walk').setAttribute('aria-pressed', String(on));
}
$('walk-ask-yes').onclick = async () => { $('dlg-walk-ask').close(); setWalk(true); await askCompass(); startGps(); speak(t('walk_on'), { force: true }); };
$('btn-walk').setAttribute('aria-pressed', String(state.walk));
// Turned on in an earlier visit: resume without a prompt if location permission is already granted.
if (state.walk) navigator.permissions?.query({ name: 'geolocation' }).then((p) => { if (p.state === 'granted') startGps(); }).catch(() => { });
$('btn-walk').onclick = async () => {
	setWalk(!state.walk);
	if (state.walk) { await askCompass(); startGps(); }
	speak(state.walk ? t('walk_on') : t('walk_off'), { force: true });
};
$('btn-offline').onclick = async () => {
	if (!state.center) return;
	try { await navigator.storage?.persist?.(); } catch { }
	const r = 3;   // tiles in each direction: about 5 by 5 km
	const cx = Math.floor(state.center.lon / TILE_LON), cy = Math.floor(state.center.lat / TILE_LAT);
	const list = [];
	for (let y = cy - r; y <= cy + r; y++) for (let x = cx - r; x <= cx + r; x++) list.push([x, y]);
	let done = 0, failed = 0;
	speak(t('offline_start'), { force: true });
	const worker = async () => {
		while (list.length) {
			const [x, y] = list.shift();
			try { await fetchTile(x, y); } catch { failed++; }
			done++;
			status(t('offline_progress', { done, total: (2 * r + 1) ** 2 }));
		}
	};
	await Promise.all([worker(), worker(), worker()]);
	const msg = failed ? t('offline_partial', { n: failed }) : t('offline_done');
	status(msg); speak(msg, { force: true });
};

// Search: names in the loaded map first, then cities and places worldwide.
$('btn-search').onclick = () => { showDialog('dlg-search'); $('search-input').focus(); };
$('search-form').onsubmit = async (e) => {
	e.preventDefault();
	const q = $('search-input').value.trim();
	const ul = $('search-results');
	ul.replaceChildren();
	if (q.length < 2) return;
	const msg = $('search-msg');
	msg.textContent = t('searching');
	const ql = q.toLowerCase();
	const local = new Map();
	const ref = state.user || state.center;
	for (const t of tiles.values()) {
		for (const el of [...t.nodes, ...t.ways]) {
			if (!el.name || el.type === 'address' || el.cat === CAT.INTERSECTION) continue;
			if (!el.name.toLowerCase().includes(ql)) continue;
			const at = el.kind === 'n' ? el : { lat: el.lat[el.lat.length >> 1], lon: el.lon[el.lon.length >> 1] };
			const d = ref ? distM(ref, at) : 0;
			const key = el.name + '|' + el.type;
			if (!local.has(key) || local.get(key).d > d) local.set(key, { label: displayName(el), lat: at.lat, lon: at.lon, d });
		}
	}
	const items = [...local.values()].sort((a, b) => a.d - b.d).slice(0, 10);
	try {
		const p = ref ? `&lat=${ref.lat.toFixed(3)}&lon=${ref.lon.toFixed(3)}` : '';
		const res = await fetch(`${API}search.php?q=${encodeURIComponent(q)}${p}`);
		if (res.ok) for (const [name, type, lat, lon, d] of await res.json()) items.push({ label: `${name}, ${type}`, lat, lon, d, world: true });
	} catch { }
	msg.textContent = items.length ? t('results', { n: items.length }) : t('nothing_found');
	for (const it of items) {
		const li = document.createElement('li');
		const b = document.createElement('button');
		const km = it.d >= 1000 && it.d < 10000 ? (it.d / 1000).toFixed(1) : Math.round((it.d || 0) / 1000);
		const dist = it.d == null ? '' : it.d < 1000 ? t('m_away', { d: Math.round(it.d) }) : t('km_away', { d: km });
		b.textContent = it.label + dist;
		b.onclick = () => {
			closeDialogs();
			state.center = { lat: it.lat, lon: it.lon }; state.follow = false;
			if (it.world && state.zoom < 2) state.zoom = 2, state.mpp = ZOOMS[2];
			viewChanged();
			speak(t('centered_on', { name: it.label }), { force: true });
		};
		li.appendChild(b); ul.appendChild(li);
	}
};

$('btn-settings').onclick = () => {
	document.querySelectorAll('input[name=mode]').forEach((r) => { r.checked = r.value === state.mode; });
	$('set-rate').value = state.rate;
	$('set-labels').checked = state.showLabels;
	$('set-detail').checked = state.detail;
	showDialog('dlg-settings');
};
document.querySelectorAll('input[name=mode]').forEach((r) => r.onchange = () => setMode(r.value));
$('set-rate').onchange = (e) => { state.rate = +e.target.value; store.set('rate', state.rate); speak(t('rate_changed'), { force: true }); };
$('set-labels').onchange = (e) => { state.showLabels = e.target.checked; store.set('labels', state.showLabels); scheduleRender(); };
$('set-detail').onchange = (e) => { state.detail = e.target.checked; store.set('detail', state.detail); scheduleRender(true); };
$('btn-help').onclick = () => showDialog('dlg-help');
$('btn-credits').onclick = () => showDialog('dlg-credits');

// ------------------------------------------------------------------ sharing
// Same texts as the Android app's share prompt (SharePromptDialog).
const WEB_URL = 'https://wikiponder.com/navia/';
const PLAY_URL = 'https://play.google.com/store/apps/details?id=com.wikiponder.touchmap';
const SHARE = {
	web: { title: 'share_web_title', url: WEB_URL, text: 'share_web_text' },
	android: { title: 'share_android_title', url: PLAY_URL, text: 'share_android_text' },
};
async function shareLink(kind) {
	const s = { title: t(SHARE[kind].title), text: t(SHARE[kind].text), url: SHARE[kind].url };
	if (navigator.share) {
		try { await navigator.share(s); } catch { }   // cancelled
		return;
	}
	try {
		await navigator.clipboard.writeText(`${s.text} ${s.url}`);
		status(t('link_copied'));
	} catch { status(t('share_link', { url: s.url })); }
}
$('share-web').onclick = () => shareLink('web');
// The Android app is only offered on Android phones; everyone shares the one website link, whose
// landing page (navia/index.html) offers Android visitors the app or the web version.
$('get-android').hidden = !/Android/i.test(navigator.userAgent);

// Like the app's prompt: at most once a day, never after "Don't ask again". The web has no exit
// moment to hook, so it comes after a few minutes of use, when no other dialog is open.
const SHARE_AFTER_MS = 4 * 60 * 1000;
const today = () => new Date().toISOString().slice(0, 10);
setInterval(() => {
	if (performance.now() < SHARE_AFTER_MS || store.get('shareNever', false) || store.get('shareDay', '') === today()) return;
	if (document.querySelector('dialog[open]') || document.hidden || gridFocused) return;
	store.set('shareDay', today());
	$('share-never').checked = false;
	showDialog('dlg-share');
}, 30000);
$('share-prompt-yes').onclick = () => { $('dlg-share').close(); shareLink('web'); };
$('share-never').onchange = (e) => store.set('shareNever', e.target.checked);

// ------------------------------------------------------------------ add to home screen
// Android Chrome offers a real install prompt; iOS has none, so it gets the Share-menu steps.
let installPrompt = null;
const isIOS = /iPad|iPhone|iPod/.test(navigator.userAgent) || navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1;
const installed = () => matchMedia('(display-mode: standalone)').matches || navigator.standalone === true;
$('btn-install').hidden = installed();
addEventListener('beforeinstallprompt', (e) => { e.preventDefault(); installPrompt = e; });
addEventListener('appinstalled', () => { installPrompt = null; $('btn-install').hidden = true; status(t('installed')); });
async function startInstall() {
	if (installPrompt) {
		const p = installPrompt;
		installPrompt = null;
		p.prompt();
		const { outcome } = await p.userChoice;
		if (outcome === 'accepted') $('btn-install').hidden = true;
		return;
	}
	$('install-ios').hidden = !isIOS;
	$('install-android').hidden = isIOS;
	showDialog('dlg-install');
}
$('btn-install').onclick = startInstall;

// One-time suggestion to install, after 10 minutes of use in total (counted across visits, only while
// the page is visible), on phones and tablets that don't already run Navia Web installed.
const INSTALL_AFTER_S = 10 * 60;
const isPhone = isIOS || /Android/i.test(navigator.userAgent);
if (isPhone && !installed() && !store.get('installPromptShown', false)) {
	const timer = setInterval(() => {
		if (document.hidden) return;
		const used = store.get('usedSeconds', 0) + 15;
		store.set('usedSeconds', used);
		if (used < INSTALL_AFTER_S || installed()) return;
		if (document.querySelector('dialog[open]') || gridFocused) return;   // wait for a quiet moment
		clearInterval(timer);
		store.set('installPromptShown', true);
		showDialog('dlg-install-prompt');
	}, 15000);
}
$('install-prompt-yes').onclick = () => { $('dlg-install-prompt').close(); startInstall(); };

// ------------------------------------------------------------------ dialogs

let returnFocus = null;
function showDialog(id) {
	const d = $(id);
	if (!d.open) { returnFocus = document.activeElement; d.showModal(); }
}
function closeDialogs() {
	document.querySelectorAll('dialog[open]').forEach((d) => d.close());
}
document.querySelectorAll('dialog').forEach((d) => {
	d.querySelectorAll('.close').forEach((b) => b.addEventListener('click', () => d.close()));
	d.addEventListener('close', () => { if (returnFocus && document.contains(returnFocus)) try { returnFocus.focus(); } catch { } });
});

// ------------------------------------------------------------------ sound simulation

const soundscape = new Soundscape('sounds/');
function openSound(at, name) {
	const near = [];
	for (const t of tiles.values()) {
		for (const n of t.nodes) if (Math.abs(n.lat - at.lat) < 0.002 && Math.abs(n.lon - at.lon) < 0.003) near.push(n);
		for (const w of t.ways) near.push(w);
		for (const e of t.env) near.push(e);
	}
	const sources = soundscape.start(at, near, { t, describe: displayName, dir: (k) => t('dir_' + k) });
	const ul = $('sound-list');
	ul.replaceChildren();
	for (const s of sources) {
		const li = document.createElement('li');
		li.textContent = s.description;
		ul.appendChild(li);
	}
	if (!sources.length) { const li = document.createElement('li'); li.textContent = t('quiet'); ul.appendChild(li); }
	$('sound-title').textContent = t('sounds_at', { name });
	setFacing(0);
	showDialog('dlg-sound');
}
const DIRS = ['n', 'ne', 'e', 'se', 's', 'sw', 'w', 'nw'];
const dirName = (deg) => t('dir_' + DIRS[Math.round(deg / 45) % 8]);
function setFacing(deg) { $('sound-hint').textContent = t('sound_hint', { dir: dirName(deg) }); }
$('sound-left').onclick = () => { const f = soundscape.turn(-45); setFacing(f); speak(t('facing', { dir: dirName(f) }), { force: true }); };
$('sound-right').onclick = () => { const f = soundscape.turn(45); setFacing(f); speak(t('facing', { dir: dirName(f) }), { force: true }); };
$('sound-forward').onclick = () => { soundscape.step(10); speak(t('moved10', { dir: dirName(soundscape.facing) }), { force: true }); };
$('dlg-sound').addEventListener('close', () => soundscape.stop());

// ------------------------------------------------------------------ startup

function setMode(mode) {
	state.mode = mode;
	store.set('mode', mode);
	gridEl.hidden = mode !== 'sr';
	if (mode === 'sr') $('tap-start').hidden = true;
	gridEl.setAttribute('aria-hidden', String(mode !== 'sr'));
	$('map').setAttribute('aria-label', mode === 'sr' ? t('map_sr') : t('map'));
	if (mode === 'sr') scheduleRender(true);
}
function unlockAudio() {
	if (state.mode === 'voice' && 'speechSynthesis' in window) {
		const u = new SpeechSynthesisUtterance(' ');
		u.volume = 0;
		speechSynthesis.speak(u);
	}
	soundscape.unlock();
}
// Offline tiles belong to one map version; when the server publishes a new map, drop the old ones.
async function checkMapVersion() {
	try {
		const { v } = await (await fetch(API + 'version.php', { cache: 'no-store' })).json();
		if (v && v !== store.get('mapv', 0)) {
			store.set('mapv', v);
			TILE_CACHE = 'navia-tiles-v' + v;
			for (const k of await caches.keys()) if (k.startsWith('navia-tiles-') && k !== TILE_CACHE) await caches.delete(k);
			tiles.clear();
			loadTiles();
		}
	} catch { }
}
function start() {
	checkMapVersion();
	$('welcome').hidden = true;
	$('main').hidden = false;
	setMode(state.mode);
	new ResizeObserver(() => scheduleRender(true)).observe(mapEl);
	if (state.mode === 'voice') {
		// Speech needs a tap first (iOS). Screen reader users who picked the wrong mode get a way out:
		// with TalkBack or VoiceOver on, the page never sees the finger, so voice mode cannot work.
		$('tap-start').hidden = false;
		$('tap-start-voice').onclick = () => { $('tap-start').hidden = true; unlockAudio(); speak(t('ready_voice'), { force: true }); };
		$('tap-start-sr').onclick = () => { setMode('sr'); status(t('sr_mode')); };
	}
	if (state.center) {
		viewChanged();
	} else {
		status(t('finding_location_status'));
		startGps(() => {
			state.follow = true;
			state.center = { lat: state.user.lat, lon: state.user.lon };
			viewChanged();
		});
		setTimeout(() => { if (!state.center) status(t('allow_location')); }, 8000);
	}
}
function chooseMode(mode) {
	state.mode = mode;
	store.set('mode', mode);
	unlockAudio();
	start();
	$('tap-start').hidden = true;
	speak(mode === 'sr' ? t('ready_sr') : t('ready_voice'), { force: true });
}
$('mode-sr').onclick = () => chooseMode('sr');
$('mode-voice').onclick = () => chooseMode('voice');

// ------------------------------------------------------------------ control icons
// Bold strokes in the text color (black on white, white on dark) for high contrast; aria-hidden so
// screen readers read only the button's translated aria-label.
const ICONS = {
	locate: '<circle cx="12" cy="12" r="7"/><circle class="fill" cx="12" cy="12" r="3"/><path d="M12 1.5v3.5M12 19v3.5M1.5 12h3.5M19 12h3.5"/>',
	search: '<circle cx="10" cy="10" r="6.5"/><path d="M15 15l6.5 6.5"/>',
	plus: '<path d="M12 4v16M4 12h16"/>',
	minus: '<path d="M4 12h16"/>',
	settings: '<path d="M3 6h18M3 12h18M3 18h18"/><circle class="fill" cx="8" cy="6" r="2.6"/><circle class="fill" cx="16" cy="12" r="2.6"/><circle class="fill" cx="10" cy="18" r="2.6"/>',
	north: '<path d="M12 21V3M5 10l7-7 7 7"/>',
	south: '<path d="M12 3v18M5 14l7 7 7-7"/>',
	west: '<path d="M21 12H3M10 5l-7 7 7 7"/>',
	east: '<path d="M3 12h18M14 5l7 7-7 7"/>',
	stop: '<circle cx="12" cy="12" r="9.5"/><path d="M8 8l8 8M16 8l-8 8"/>',
};
document.querySelectorAll('[data-icon]').forEach((b) => {
	b.innerHTML = `<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">${ICONS[b.dataset.icon]}</svg>`;
});

// ------------------------------------------------------------------ language

function fillWelcomeSmall() {
	const [before, after] = t('welcome_small').split('{link}');
	const a = document.createElement('a');
	a.href = 'https://play.google.com/store/apps/details?id=com.wikiponder.touchmap';
	a.textContent = 'Google Play';
	$('welcome-small').replaceChildren(before ?? '', a, after ?? '');
}
// The list always shows the English name, plus the native name where it differs: "Persian - فارسی".
for (const sel of [$('set-lang'), $('welcome-lang')]) {
	for (const [code, english, native] of LANGS) {
		const o = document.createElement('option');
		o.value = code;
		o.textContent = native === english ? english : `${english} - ${native}`;
		if (native !== english) o.lang = code;
		sel.appendChild(o);
	}
	sel.onchange = async (e) => {
		store.set('lang', e.target.value);
		await loadLang(e.target.value);
		$('set-lang').value = $('welcome-lang').value = lang;
		fillWelcomeSmall();
		if (state.mode) setMode(state.mode);   // map label and touch grid in the new language
		speak(t('language_changed'), { force: true });
	};
}
await loadLang(store.get('lang', 'en'));
$('set-lang').value = $('welcome-lang').value = lang;
fillWelcomeSmall();

if (state.mode) start(); else $('welcome').hidden = false;
if ('serviceWorker' in navigator) navigator.serviceWorker.register('sw.js').catch(() => { });
matchMedia('(prefers-color-scheme: dark)').addEventListener?.('change', () => scheduleRender());
window.navia = { state, tiles, nearest, describeHit, toScreen, get view() { return view; } };   // for debugging
