// Simplified sound simulation: picks the nearest sound sources around a point from the map data and
// plays the app's clip bank through Web Audio HRTF panners. (The Android app's SceneBuilder has many
// more rules; this keeps the main ones: traffic by road class, rail, water, and shops/amenities.)

const M_PER_DEG = 6378137 * Math.PI / 180;

const ROAD_CLIP = {
	motorway: ['car_fast', 1.0], trunk: ['car_fast', 0.9], primary: ['car', 0.85], secondary: ['car', 0.7],
	tertiary: ['car_slow', 0.55], residential: ['car_slow', 0.3], unclassified: ['car_slow', 0.3], living_street: ['footsteps', 0.3],
	pedestrian: ['footsteps', 0.4], cycleway: ['bicycle', 0.35], busway: ['bus', 0.6],
};
const NODE_CLIP = {
	'25:cafe': 'cafe', '25:restaurant': 'restaurant', '25:fast_food': 'restaurant', '25:pub': 'pub', '25:bar': 'pub', '25:biergarten': 'pub',
	'25:marketplace': 'market', '25:fountain': 'fountain', '25:school': 'schoolyard', '25:kindergarten': 'children',
	'25:place_of_worship': 'church_bells', '25:fuel': 'fuel_station', '25:car_wash': 'car_wash', '25:atm': 'atm',
	'25:vending_machine': 'vending', '25:recycling': 'recycling_glass', '25:cinema': 'cinema_lobby', '25:university': 'university',
	'25:nightclub': 'nightclub', '25:ice_cream': 'kiosk', '25:bicycle_parking': 'bicycle_bell',
	'20:bakery': 'bakery', '20:supermarket': 'supermarket', '20:convenience': 'shop_bell', '20:hairdresser': 'hairdresser',
	'20:laundry': 'laundry', '20:dry_cleaning': 'laundry', '20:butcher': 'butcher', '20:greengrocer': 'greengrocer',
	'20:bicycle': 'bicycle_repair', '20:clothes': 'clothes_shop', '20:shoes': 'clothes_shop', '20:electronics': 'electronics_shop',
	'20:mobile_phone': 'electronics_shop', '20:pet': 'pet_shop', '20:kiosk': 'kiosk', '20:car_repair': 'garage', '20:tailor': 'sewing',
	'30:playground': 'playground', '30:park': 'birds', '30:garden': 'birds', '30:pitch': 'sports', '30:sports_centre': 'sports',
	'30:fitness_centre': 'gym', '30:swimming_pool': 'swimming', '30:stadium': 'stadium', '30:amusement_arcade': 'arcade',
	'35:hotel': 'hotel', '35:theme_park': 'theme_park', '35:zoo': 'zoo',
	'10:bus_stop': 'bus', '10:traffic_signals': 'ped_beep', '10:level_crossing': 'crossing_bell', '10:tram_stop': 'tram_bell',
	'10:station': 'station_hall', '10:construction': 'construction',
};
const ONESHOT = new Set(['church_bells', 'atm', 'vending', 'recycling_glass', 'children', 'bicycle_bell', 'butcher', 'clothes_shop',
	'electronics_shop', 'pet_shop', 'kiosk', 'hotel', 'zoo', 'shop_bell', 'tram_bell', 'bicycle_repair']);

function labelOf(clip) { return clip.replace(/_/g, ' '); }
function compass(east, north) {
	const deg = (Math.atan2(east, north) * 180 / Math.PI + 360) % 360;
	return ['n', 'ne', 'e', 'se', 's', 'sw', 'w', 'nw'][Math.round(deg / 45) % 8];
}

export class Soundscape {
	constructor(base) {
		this.base = base;
		this.ctx = null;
		this.buffers = new Map();
		this.nodes = [];
		this.timers = [];
		this.facing = 0;
		this.pos = [0, 0];
	}
	unlock() {
		const AC = window.AudioContext || window.webkitAudioContext;
		if (!AC) return;
		if (!this.ctx) this.ctx = new AC();
		if (this.ctx.state === 'suspended') this.ctx.resume();
	}
	async buffer(name) {
		if (!this.buffers.has(name)) {
			this.buffers.set(name, fetch(this.base + name + '.m4a').then((r) => r.arrayBuffer())
				.then((a) => new Promise((ok, bad) => this.ctx.decodeAudioData(a, ok, bad))));
		}
		return this.buffers.get(name);
	}

	/** Chooses sources around `at` from map elements and starts playing. Returns their descriptions. */
	// i18n: { t, describe(el) -> spoken name, dir(code) -> direction word }
	start(at, elements, i18n) {
		this.stop();
		this.unlock();
		if (!this.ctx) return [];
		this.facing = 0;
		this.pos = [0, 0];
		const kx = M_PER_DEG * Math.cos(at.lat * Math.PI / 180), ky = M_PER_DEG;
		const local = (lat, lon) => [(lon - at.lon) * kx, (lat - at.lat) * ky];
		const nearestOnLine = (el) => {
			let best = null, bd = Infinity;
			for (let i = 1; i < el.lat.length; i++) {
				const [ax, ay] = local(el.lat[i - 1], el.lon[i - 1]), [bx, by] = local(el.lat[i], el.lon[i]);
				const dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy;
				const t = l2 ? Math.max(0, Math.min(1, -(ax * dx + ay * dy) / l2)) : 0;
				const qx = ax + t * dx, qy = ay + t * dy, d = Math.hypot(qx, qy);
				if (d < bd) { bd = d; best = [qx, qy]; }
			}
			return best ? { p: best, d: bd } : null;
		};
		const cand = new Map();   // clip -> best candidate, so one clip plays once (nearest instance)
		const add = (clip, p, d, gain, what) => {
			const cur = cand.get(clip);   // one instance per clip: the nearest
			if (what && what.toLowerCase() === labelOf(clip)) what = '';
			if (!cur || cur.d > d) cand.set(clip, { clip, p, d, gain, what });
		};
		let busy = 0, green = 0, water = false;
		for (const el of elements) {
			if (el.kind === 'n') {
				const p = local(el.lat, el.lon), d = Math.hypot(p[0], p[1]);
				if (d > 70) continue;
				busy++;
				const clip = NODE_CLIP[el.cat + ':' + el.type];
				if (clip) add(clip, p, d, 0.7, i18n.describe(el));
				if (el.cat === 30 || el.cat === 50) green++;
			} else if (el.kind === 'w') {
				const rc = ROAD_CLIP[el.type];
				if (!rc || !el.lat.length) continue;
				const r = nearestOnLine(el);
				if (!r || r.d > 120) continue;
				add(rc[0], r.p, r.d, rc[1], i18n.describe(el));
			} else if (el.kind === 'e') {
				const r = nearestOnLine(el);
				if (!r) continue;
				if (el.env === 'rail' && r.d < 150 && el.type !== 'subway') add(el.type === 'tram' ? 'tram' : 'train', r.p, r.d, 0.8, el.name || el.type.replace(/_/g, ' '));
				else if (el.env === 'river' && r.d < 120) { water = true; add(el.type === 'stream' || el.type === 'ditch' ? 'stream' : el.type === 'waterfall' ? 'waterfall' : 'river', r.p, r.d, 0.7, el.name || el.type.replace(/_/g, ' ')); }
				else if (el.env === 'lake' && r.d < 100) { water = true; add(el.type === 'harbour' ? 'harbor' : el.type === 'fountain' ? 'fountain' : 'rocky_shore', r.p, r.d, 0.5, el.name || el.type.replace(/_/g, ' ')); }
			}
		}
		// Keep the eight most prominent sources: loud and close first.
		const picked = [...cand.values()].sort((a, b) => b.gain / (1 + b.d / 15) - a.gain / (1 + a.d / 15)).slice(0, 8);
		const bed = busy > 25 ? 'city_hum' : green > 3 || !picked.length ? 'birds' : 'countryside';
		this.sources = picked.map((s) => ({ ...s, description: i18n.t('sound_src', { what: s.what || labelOf(s.clip), d: Math.round(s.d), dir: i18n.dir(compass(s.p[0], s.p[1])) }) }));
		for (const s of this.sources) this.play(s);
		this.play({ clip: bed, p: null, d: 0, gain: 0.25 });
		if (water) { /* water already a source */ }
		this.updateListener();
		return this.sources;
	}

	async play(s) {
		const ctx = this.ctx;
		let buf;
		try { buf = await this.buffer(s.clip); } catch { return; }
		if (!this.sources) return;
		const gain = ctx.createGain();
		gain.gain.value = s.gain * (s.p ? 1.5 : 1);   // the panner does the distance attenuation
		let out = gain;
		if (s.p) {
			const pan = ctx.createPanner();
			pan.panningModel = 'HRTF';
			pan.distanceModel = 'inverse';
			pan.refDistance = 5; pan.rolloffFactor = 0.6;
			// Web Audio: x = east, y = up, z = south.
			if (pan.positionX) { pan.positionX.value = s.p[0]; pan.positionY.value = 0; pan.positionZ.value = -s.p[1]; }
			else pan.setPosition(s.p[0], 0, -s.p[1]);
			gain.connect(pan);
			pan.connect(ctx.destination);
			out = pan;
		} else gain.connect(ctx.destination);
		const startOne = () => {
			const src = ctx.createBufferSource();
			src.buffer = buf;
			src.connect(gain);
			const oneshot = ONESHOT.has(s.clip);
			src.loop = !oneshot;
			if (!oneshot) { src.loopStart = 0.05; src.loopEnd = buf.duration - 0.05; }
			src.start(0, oneshot ? 0 : Math.random() * buf.duration * 0.8);
			this.nodes.push(src);
			if (oneshot) this.timers.push(setTimeout(startOne, 6000 + Math.random() * 14000 + buf.duration * 1000));
		};
		startOne();
		this.nodes.push(gain, out);
	}

	updateListener() {
		const L = this.ctx.listener, a = this.facing * Math.PI / 180;
		const fx = Math.sin(a), fz = -Math.cos(a);
		const [px, pn] = this.pos;
		if (L.forwardX) {
			L.positionX.value = px; L.positionY.value = 0; L.positionZ.value = -pn;
			L.forwardX.value = fx; L.forwardY.value = 0; L.forwardZ.value = fz;
			L.upX.value = 0; L.upY.value = 1; L.upZ.value = 0;
		} else {
			L.setPosition(px, 0, -pn);
			L.setOrientation(fx, 0, fz, 0, 1, 0);
		}
	}
	turn(deg) { this.facing = (this.facing + deg + 360) % 360; if (this.ctx) this.updateListener(); return this.facing; }
	step(m) {
		const a = this.facing * Math.PI / 180;
		this.pos = [this.pos[0] + Math.sin(a) * m, this.pos[1] + Math.cos(a) * m];
		if (this.ctx) this.updateListener();
	}
	stop() {
		this.sources = null;
		this.timers.forEach(clearTimeout);
		this.timers = [];
		for (const n of this.nodes) { try { n.stop?.(); } catch { } try { n.disconnect(); } catch { } }
		this.nodes = [];
	}
}
