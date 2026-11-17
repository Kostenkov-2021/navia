<?php
// GET version.php -> {"v": map_version}. The web app clears its offline tiles when this changes.
require __DIR__ . '/common.php';
header('Content-Type: application/json');
header('Cache-Control: no-cache');
echo json_encode(['v' => manifest()[0]]);
