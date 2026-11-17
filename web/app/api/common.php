<?php
// Shared helpers for the Navia web API. Map data comes from the same box dbs the Android app
// downloads; boxes are fetched from B2 on first use and kept in an LRU cache outside public_html.

const EARTH_RADIUS = 6378137.0;
const CELL_SIZE = 8000.0;            // matches Contract.CELL_SIZE_METERS
const TILE_LAT = 0.016;              // web tile size (~1.8 km); must match app.js
const TILE_LON = 0.025;
const BOX_URL = 'https://f003.backblazeb2.com/file/wikiponder-navia/navia/global_boxes_v2/box_%06d.db.gz';
const CACHE_BYTES = 5 * 1024 * 1024 * 1024;

function cache_dir(): string {
	$d = getenv('HOME') . '/navia_web_cache';
	if (!is_dir($d)) mkdir($d, 0700, true);
	return $d;
}

function fail(int $code, string $msg) {
	http_response_code($code);
	header('Content-Type: application/json');
	echo json_encode(['error' => $msg]);
	exit;
}

// Crawlers get nothing (same list as navia/.htaccess); a cold tile can cost a 70 MB box download.
if (preg_match('/bot|crawl|spider|slurp|Bytespider|CCBot|facebookexternalhit|CensysInspect|PerplexityBot|ClaudeBot|GPTBot|Amazonbot|AhrefsBot|PetalBot/i',
		$_SERVER['HTTP_USER_AGENT'] ?? '') || empty($_SERVER['HTTP_USER_AGENT'])) {
	fail(403, 'forbidden');
}

const BOX_FETCHES_PER_IP_HOUR = 8;
const BOX_FETCHES_PER_HOUR = 60;
const TILE_CACHE_BYTES = 3 * 1024 * 1024 * 1024;

/** Rate limit for box downloads from B2 (only cold boxes count). Exits with 429 when over. */
function allow_box_fetch() {
	$f = cache_dir() . '/box_fetches.json';
	$fh = fopen($f, 'c+');
	flock($fh, LOCK_EX);
	$list = json_decode(stream_get_contents($fh) ?: '[]', true) ?: [];
	$now = time();
	$list = array_values(array_filter($list, fn($e) => $e[0] > $now - 3600));
	$ip = $_SERVER['REMOTE_ADDR'] ?? '';
	$mine = count(array_filter($list, fn($e) => $e[1] === $ip));
	$ok = $mine < BOX_FETCHES_PER_IP_HOUR && count($list) < BOX_FETCHES_PER_HOUR;
	if ($ok) $list[] = [$now, $ip];
	ftruncate($fh, 0); rewind($fh); fwrite($fh, json_encode($list));
	flock($fh, LOCK_UN); fclose($fh);
	if (!$ok) { header('Retry-After: 900'); fail(429, 'busy'); }
}

/** Deletes least recently written tiles until the tile cache is under TILE_CACHE_BYTES. */
function prune_tiles(string $root) {
	$list = []; $total = 0;
	foreach (glob("$root/tiles_v*/*.json.gz") as $f) { $s = filesize($f); $total += $s; $list[] = [filemtime($f), $f, $s]; }
	if ($total <= TILE_CACHE_BYTES) return;
	sort($list);
	foreach ($list as [$t, $f, $s]) { if ($total <= TILE_CACHE_BYTES * 0.9) break; @unlink($f); $total -= $s; }
}

function java_round(float $x): int { return (int)floor($x + 0.5); }

function lat_lon_to_index(float $lat, float $lon): array {
	$latRad = deg2rad($lat);
	$y = java_round(EARTH_RADIUS * $latRad / CELL_SIZE);
	$w = CELL_SIZE / EARTH_RADIUS / cos($latRad);
	return [java_round(deg2rad($lon) / $w), $y];
}

function cells_for_bbox(float $minLat, float $maxLat, float $minLon, float $maxLon): array {
	$cells = [];
	$y0 = lat_lon_to_index($minLat, $minLon)[1];
	$y1 = lat_lon_to_index($maxLat, $minLon)[1];
	for ($cy = $y0; $cy <= $y1; $cy++) {
		$rowLat = rad2deg($cy * CELL_SIZE / EARTH_RADIUS);
		$xa = lat_lon_to_index($rowLat, $minLon)[0];
		$xb = lat_lon_to_index($rowLat, $maxLon)[0];
		for ($cx = min($xa, $xb); $cx <= max($xa, $xb); $cx++) $cells[] = [$cx, $cy];
	}
	// Cells are not rectangles in lat/lon (x width depends on each point's own latitude), so
	// also include the cells of the bbox corners and center to never miss one.
	foreach ([[$minLat, $minLon], [$minLat, $maxLon], [$maxLat, $minLon], [$maxLat, $maxLon],
				 [($minLat + $maxLat) / 2, ($minLon + $maxLon) / 2]] as $p) {
		$c = lat_lon_to_index($p[0], $p[1]);
		if (!in_array($c, $cells)) $cells[] = $c;
	}
	return $cells;
}

/** Compact manifest: [map_version, [[index, lat_min, lat_max, lon_min, lon_max], ...]]. */
function manifest(): array {
	$src = dirname(__DIR__, 2) . '/global_boxes/boxes_manifest.json';
	$compact = cache_dir() . '/manifest.php.ser';
	if (is_file($compact) && filemtime($compact) >= filemtime($src)) {
		return unserialize(file_get_contents($compact));
	}
	$m = json_decode(file_get_contents($src), true);
	$boxes = [];
	foreach ($m['boxes'] as $b) $boxes[] = [$b['index'], $b['lat_min'], $b['lat_max'], $b['lon_min'], $b['lon_max']];
	$out = [$m['map_version'] ?? 1, $boxes];
	file_put_contents($compact . '.tmp', serialize($out));
	rename($compact . '.tmp', $compact);
	return $out;
}

function boxes_for_bbox(array $boxes, float $minLat, float $maxLat, float $minLon, float $maxLon): array {
	$r = [];
	foreach ($boxes as $b) {
		if ($b[2] >= $minLat && $b[1] <= $maxLat && $b[4] >= $minLon && $b[3] <= $maxLon) $r[] = $b[0];
	}
	return $r;
}

/** Local path of an uncompressed box db, downloading it from B2 the first time. */
function ensure_box(int $index, int $version): string {
	$dir = cache_dir() . "/boxes_v$version";
	if (!is_dir($dir)) mkdir($dir, 0700, true);
	$path = sprintf('%s/box_%06d.db', $dir, $index);
	if (is_file($path)) { touch($path); return $path; }

	$lock = fopen($path . '.lock', 'c');
	flock($lock, LOCK_EX);
	try {
		if (is_file($path)) { touch($path); return $path; }
		allow_box_fetch();
		$gz = $path . '.gz.tmp';
		$fh = fopen($gz, 'w');
		$ch = curl_init(sprintf(BOX_URL, $index));
		curl_setopt_array($ch, [CURLOPT_FILE => $fh, CURLOPT_FOLLOWLOCATION => true, CURLOPT_FAILONERROR => true,
			CURLOPT_CONNECTTIMEOUT => 20, CURLOPT_TIMEOUT => 600]);
		$ok = curl_exec($ch);
		curl_close($ch);
		fclose($fh);
		if (!$ok) { @unlink($gz); fail(502, "box $index download failed"); }

		$in = gzopen($gz, 'rb');
		$tmp = $path . '.tmp';
		$out = fopen($tmp, 'wb');
		while (!gzeof($in)) fwrite($out, gzread($in, 1 << 20));
		gzclose($in);
		fclose($out);
		unlink($gz);
		rename($tmp, $path);
		prune_boxes(dirname($dir));
		return $path;
	} finally {
		flock($lock, LOCK_UN);
		fclose($lock);
	}
}

/** Deletes least recently used box dbs until the cache is under CACHE_BYTES. */
function prune_boxes(string $root) {
	$files = glob("$root/boxes_v*/box_*.db");
	$total = 0;
	$list = [];
	foreach ($files as $f) { $s = filesize($f); $total += $s; $list[] = [filemtime($f), $f, $s]; }
	if ($total <= CACHE_BYTES) return;
	sort($list);
	foreach ($list as [$t, $f, $s]) {
		if ($total <= CACHE_BYTES) break;
		foreach (['', '-shm', '-wal', '.lock'] as $suffix) @unlink($f . $suffix);
		$total -= $s;
	}
}

// ---------------------------------------------------------------- minimal msgpack decoder

function mp_decode(string $b) { $o = 0; return mp_read($b, $o); }

function mp_read(string $b, int &$o) {
	$t = ord($b[$o++]);
	if ($t <= 0x7f) return $t;
	if ($t >= 0xe0) return $t - 256;
	if (($t & 0xf0) === 0x90) return mp_arr($b, $o, $t & 0x0f);
	if (($t & 0xf0) === 0x80) return mp_map($b, $o, $t & 0x0f);
	if (($t & 0xe0) === 0xa0) { $n = $t & 0x1f; $s = substr($b, $o, $n); $o += $n; return $s; }
	switch ($t) {
		case 0xc0: return null;
		case 0xc2: return false;
		case 0xc3: return true;
		case 0xcc: return ord($b[$o++]);
		case 0xcd: $v = unpack('n', $b, $o)[1]; $o += 2; return $v;
		case 0xce: $v = unpack('N', $b, $o)[1]; $o += 4; return $v;
		case 0xcf: $v = unpack('J', $b, $o)[1]; $o += 8; return $v;
		case 0xd0: $v = ord($b[$o++]); return $v > 127 ? $v - 256 : $v;
		case 0xd1: $v = unpack('n', $b, $o)[1]; $o += 2; return $v > 32767 ? $v - 65536 : $v;
		case 0xd2: $v = unpack('N', $b, $o)[1]; $o += 4; return $v > 2147483647 ? $v - 4294967296 : $v;
		case 0xd3: $v = unpack('q', strrev(substr($b, $o, 8)))[1]; $o += 8; return $v;
		case 0xca: $v = unpack('G', $b, $o)[1]; $o += 4; return $v;
		case 0xcb: $v = unpack('E', $b, $o)[1]; $o += 8; return $v;
		case 0xd9: case 0xc4: $n = ord($b[$o++]); $s = substr($b, $o, $n); $o += $n; return $s;
		case 0xda: case 0xc5: $n = unpack('n', $b, $o)[1]; $o += 2; $s = substr($b, $o, $n); $o += $n; return $s;
		case 0xdb: case 0xc6: $n = unpack('N', $b, $o)[1]; $o += 4; $s = substr($b, $o, $n); $o += $n; return $s;
		case 0xdc: $n = unpack('n', $b, $o)[1]; $o += 2; return mp_arr($b, $o, $n);
		case 0xdd: $n = unpack('N', $b, $o)[1]; $o += 4; return mp_arr($b, $o, $n);
		case 0xde: $n = unpack('n', $b, $o)[1]; $o += 2; return mp_map($b, $o, $n);
		case 0xdf: $n = unpack('N', $b, $o)[1]; $o += 4; return mp_map($b, $o, $n);
	}
	throw new Exception('msgpack type ' . dechex($t));
}

function mp_arr(string $b, int &$o, int $n): array { $a = []; for ($i = 0; $i < $n; $i++) $a[] = mp_read($b, $o); return $a; }
function mp_map(string $b, int &$o, int $n): array { $a = []; for ($i = 0; $i < $n; $i++) { $k = mp_read($b, $o); $a[$k] = mp_read($b, $o); } return $a; }

/** Way points blob [[lat_e7, lon_e7, region], ...] -> delta-encoded E6 ints [lat0, lon0, dlat, dlon, ...]. */
function pack_points(?string $blob, int $maxPoints = 0): array {
	if ($blob === null || $blob === '') return [];
	$pts = mp_decode($blob);
	$n = count($pts);
	$step = ($maxPoints > 0 && $n > $maxPoints) ? (int)ceil($n / $maxPoints) : 1;
	$out = [];
	$pl = 0; $po = 0;
	for ($i = 0; $i < $n; $i += $step) {
		if ($step > 1 && $i + $step >= $n) $i = $n - 1;
		$la = intdiv($pts[$i][0], 10); $lo = intdiv($pts[$i][1], 10);
		$out[] = $la - $pl; $out[] = $lo - $po;
		$pl = $la; $po = $lo;
	}
	return $out;
}
