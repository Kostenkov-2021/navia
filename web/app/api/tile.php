<?php
// GET tile.php?x=<tx>&y=<ty>  ->  gzipped JSON with the nodes, ways and environment features of one
// web tile (TILE_LAT x TILE_LON degrees). Results are cached per map version.
//   n: [[id, lat, lon, cat_id, type, name], ...]
//   w: [[id, cat_id, type, name, [lat0, lon0, dlat, dlon, ...] (E6, delta-encoded)], ...]
//   e: [[id, kind, type, name, points], ...]
require __DIR__ . '/common.php';
ini_set('serialize_precision', '-1');

$x = filter_input(INPUT_GET, 'x', FILTER_VALIDATE_INT);
$y = filter_input(INPUT_GET, 'y', FILTER_VALIDATE_INT);
if ($x === null || $x === false || $y === null || $y === false) fail(400, 'x and y required');
if (abs($y) > 90 / TILE_LAT || abs($x) > 180 / TILE_LON + 1) fail(400, 'out of range');

[$version, $boxes] = manifest();
$dir = cache_dir() . "/tiles_v$version";
if (!is_dir($dir)) mkdir($dir, 0700, true);
$file = "$dir/{$y}_{$x}.json.gz";

if (!is_file($file)) {
	@set_time_limit(600);
	ignore_user_abort(true);
	$minLat = $y * TILE_LAT; $maxLat = $minLat + TILE_LAT;
	$minLon = $x * TILE_LON; $maxLon = $minLon + TILE_LON;
	$cells = cells_for_bbox($minLat, $maxLat, $minLon, $maxLon);
	$nodes = []; $ways = []; $env = [];
	foreach (boxes_for_bbox($boxes, $minLat, $maxLat, $minLon, $maxLon) as $idx) {
		$db = new SQLite3(ensure_box($idx, $version), SQLITE3_OPEN_READONLY);
		$db->busyTimeout(30000);
		$qn = $db->prepare('SELECT id, lat, lon, cat_id, type, name FROM nodes
			WHERE cell_x = :cx AND cell_y = :cy AND lat >= :a AND lat < :b AND lon >= :c AND lon < :d');
		$qw = $db->prepare('SELECT w.id, w.cat_id, w.type, w.name, w.points FROM way_cells wc JOIN ways w ON w.id = wc.way_id
			WHERE wc.cell_x = :cx AND wc.cell_y = :cy AND w.max_lat >= :a AND w.min_lat < :b AND w.max_lon >= :c AND w.min_lon < :d');
		$hasEnv = $db->querySingle("SELECT count(*) FROM sqlite_master WHERE name = 'env_features'") > 0;
		$qe = $hasEnv ? $db->prepare('SELECT f.id, f.kind, f.type, f.name, f.points FROM env_cells ec JOIN env_features f ON f.id = ec.feature_id
			WHERE ec.cell_x = :cx AND ec.cell_y = :cy AND f.max_lat >= :a AND f.min_lat < :b AND f.max_lon >= :c AND f.min_lon < :d') : null;
		foreach ($cells as [$cx, $cy]) {
			foreach (array_filter([$qn, $qw, $qe]) as $q) {
				$q->reset();
				$q->bindValue(':cx', $cx, SQLITE3_INTEGER); $q->bindValue(':cy', $cy, SQLITE3_INTEGER);
				$q->bindValue(':a', $minLat); $q->bindValue(':b', $maxLat);
				$q->bindValue(':c', $minLon); $q->bindValue(':d', $maxLon);
			}
			$r = $qn->execute();
			while ($row = $r->fetchArray(SQLITE3_NUM)) {
				$nodes[$row[0]] = [(string)$row[0], round($row[1], 6), round($row[2], 6), $row[3], (string)$row[4], (string)$row[5]];
			}
			$r = $qw->execute();
			while ($row = $r->fetchArray(SQLITE3_NUM)) {
				if (isset($ways[$row[0]])) continue;
				$ways[$row[0]] = [(string)$row[0], $row[1], (string)$row[2], (string)$row[3], pack_points($row[4])];
			}
			if ($qe) {
				$r = $qe->execute();
				while ($row = $r->fetchArray(SQLITE3_NUM)) {
					if (isset($env[$row[0]])) continue;
					$env[$row[0]] = [(string)$row[0], $row[1], $row[2], (string)$row[3], pack_points($row[4], 400)];
				}
			}
		}
		$db->close();
	}
	$json = json_encode(['v' => $version, 'x' => $x, 'y' => $y, 'n' => array_values($nodes), 'w' => array_values($ways),
		'e' => array_values($env)], JSON_UNESCAPED_UNICODE | JSON_INVALID_UTF8_SUBSTITUTE);
	file_put_contents("$file.tmp", gzencode($json, 6));
	rename("$file.tmp", $file);
	if (mt_rand(1, 50) === 1) prune_tiles(cache_dir());
}

header('Content-Type: application/json; charset=utf-8');
header('Content-Encoding: gzip');
header('Cache-Control: public, max-age=604800');
header('Content-Length: ' . filesize($file));
readfile($file);
