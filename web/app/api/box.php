<?php
// GET box.php?i=<box index>: the Android/iOS app's fallback when a box can't be downloaded from
// Backblaze B2 directly (B2 seems to be blocked in Turkey). The server fetches the box from B2 once
// and keeps the .db.gz under public_html/navia/box_cache/v<map_version>/, at most APP_BOX_CACHE_BYTES,
// deleting the least recently requested boxes first.
//   302 -> the cached file, served by Apache (so large files never stream through PHP)
//   202 + Retry-After -> the server is fetching it from B2 right now; ask again later
//   429 -> too many cold fetches; 404 -> no such box
require __DIR__ . '/common.php';

const APP_BOX_CACHE_BYTES = 5 * 1024 * 1024 * 1024;
const APP_BOX_FETCHES_PER_IP_HOUR = 50;   // a "larger map" download can need 15 boxes
const APP_BOX_FETCHES_PER_HOUR = 500;

header('Cache-Control: no-store');

$i = filter_input(INPUT_GET, 'i', FILTER_VALIDATE_INT);
[$version, $boxes] = manifest();
if (!is_int($i) || !in_array($i, array_column($boxes, 0), true)) fail(404, 'unknown box');

$root = dirname(__DIR__, 2) . '/box_cache';
$dir = "$root/v$version";
if (!is_dir($dir)) {
	mkdir($dir, 0755, true);
	@file_put_contents("$root/index.html", '');   // no directory listings
	@file_put_contents("$dir/index.html", '');
}
$name = sprintf('box_%06d.db.gz', $i);
$path = "$dir/$name";

function send_cached(string $path, int $version, string $name) {
	touch($path);   // last request time, for the LRU below
	header('Location: https://' . $_SERVER['HTTP_HOST'] . "/navia/box_cache/v$version/$name", true, 302);
	exit;
}

function preparing() {
	http_response_code(202);
	header('Retry-After: 5');
	header('Content-Type: application/json');
	echo json_encode(['status' => 'preparing']);
}

if (is_file($path)) send_cached($path, $version, $name);

$lock = fopen(cache_dir() . "/app_box_v{$version}_$i.lock", 'c');
if (!flock($lock, LOCK_EX | LOCK_NB)) { preparing(); exit; }   // another request is fetching it
if (is_file($path)) { flock($lock, LOCK_UN); send_cached($path, $version, $name); }

allow_box_fetch(APP_BOX_FETCHES_PER_IP_HOUR, APP_BOX_FETCHES_PER_HOUR, 'app_box_fetches.json');

// Answer now and fetch after the response is sent: a large box can take longer than the app waits
// for a response.
preparing();
fastcgi_finish_request();
ignore_user_abort(true);
set_time_limit(0);

$tmp = "$path.part";
$fh = fopen($tmp, 'w');
$ch = curl_init(sprintf(BOX_URL, $i));
curl_setopt_array($ch, [CURLOPT_FILE => $fh, CURLOPT_FOLLOWLOCATION => true, CURLOPT_FAILONERROR => true,
	CURLOPT_CONNECTTIMEOUT => 20, CURLOPT_TIMEOUT => 1800]);
$ok = curl_exec($ch);
$expected = curl_getinfo($ch, CURLINFO_CONTENT_LENGTH_DOWNLOAD);
curl_close($ch);
fclose($fh);
if ($ok && ($expected <= 0 || filesize($tmp) == $expected)) {
	chmod($tmp, 0644);
	rename($tmp, $path);
	prune_app_boxes($root, $path);
} else {
	@unlink($tmp);
}
flock($lock, LOCK_UN);
fclose($lock);

/** Deletes the least recently requested cached boxes until the cache is under APP_BOX_CACHE_BYTES. */
function prune_app_boxes(string $root, string $keep) {
	$list = []; $total = 0;
	foreach (glob("$root/v*/box_*.db.gz") as $f) { $s = filesize($f); $total += $s; $list[] = [filemtime($f), $f, $s]; }
	if ($total <= APP_BOX_CACHE_BYTES) return;
	sort($list);
	foreach ($list as [$t, $f, $s]) {
		if ($total <= APP_BOX_CACHE_BYTES) break;
		if ($f === $keep) continue;
		@unlink($f);
		$total -= $s;
	}
}
