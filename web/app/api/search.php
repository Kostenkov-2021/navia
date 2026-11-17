<?php
// GET search.php?q=<text>[&lat=..&lon=..]  ->  JSON list of world places (GeoNames) whose name
// starts with (or contains) the text, closest first.
require __DIR__ . '/common.php';
ini_set('serialize_precision', '-1');

$q = trim((string)($_GET['q'] ?? ''));
if (mb_strlen($q) < 2 || mb_strlen($q) > 80) fail(400, 'query length');
$lat = filter_input(INPUT_GET, 'lat', FILTER_VALIDATE_FLOAT);
$lon = filter_input(INPUT_GET, 'lon', FILTER_VALIDATE_FLOAT);

$db = new SQLite3(dirname(__DIR__, 2) . '/world_places.db', SQLITE3_OPEN_READONLY);
$like = str_replace(['\\', '%', '_'], ['\\\\', '\\%', '\\_'], $q);
$st = $db->prepare("SELECT name, type, lat, lon, cat_id FROM nodes
	WHERE name LIKE :p ESCAPE '\\' LIMIT 400");
$results = [];
foreach ([$like . '%', '%' . $like . '%'] as $pattern) {
	$st->reset();
	$st->bindValue(':p', $pattern);
	$r = $st->execute();
	while ($row = $r->fetchArray(SQLITE3_ASSOC)) {
		$key = $row['name'] . '|' . round($row['lat'], 3) . '|' . round($row['lon'], 3);
		if (isset($results[$key])) continue;
		$row['prefix'] = $pattern[0] !== '%';
		$results[$key] = $row;
	}
	if (count($results) >= 30) break;
}

// Bigger kinds of place first, then nearest.
$rank = ['city' => 0, 'town' => 1, 'district' => 2, 'village' => 3];
$results = array_values($results);
foreach ($results as &$r) {
	$r['d'] = ($lat !== null && $lat !== false && $lon !== null && $lon !== false)
		? 6371000 * 2 * asin(sqrt(sin(deg2rad($r['lat'] - $lat) / 2) ** 2
			+ cos(deg2rad($lat)) * cos(deg2rad($r['lat'])) * sin(deg2rad($r['lon'] - $lon) / 2) ** 2)) : null;
}
unset($r);
usort($results, function ($a, $b) use ($rank) {
	if ($a['prefix'] !== $b['prefix']) return $a['prefix'] ? -1 : 1;
	if ($a['d'] !== null && $b['d'] !== null && abs($a['d'] - $b['d']) > 1) {
		// Within 50 km, distance decides; farther away, bigger places win.
		if ($a['d'] < 50000 || $b['d'] < 50000) return $a['d'] <=> $b['d'];
	}
	$ra = $rank[$a['type']] ?? 4; $rb = $rank[$b['type']] ?? 4;
	if ($ra !== $rb) return $ra <=> $rb;
	return ($a['d'] ?? 0) <=> ($b['d'] ?? 0);
});
header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: public, max-age=86400');
echo json_encode(array_slice(array_map(fn($r) => [$r['name'], $r['type'], $r['lat'], $r['lon'], $r['d'] === null ? null : (int)$r['d']],
	$results), 0, 20), JSON_UNESCAPED_UNICODE | JSON_INVALID_UTF8_SUBSTITUTE);
