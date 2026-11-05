#!/usr/bin/env python3
r"""
Builds every map box db from one OSM planet file, holding the whole map in RAM until it is written.

This is the big-memory counterpart of build_global_cluster_dbs.py ("v1"), which was written for a
64 GB machine: v1 reads 500-odd Geofabrik extracts one at a time and pushes rows into SQLite as it
goes. This tool makes one pass over the planet, keeps everything it wants in memory, then writes each
box in one go. Nothing is written to a box until reading has finished, so there is no WAL contention,
no connection pool, no region bookkeeping and no way-cut-at-a-region-border.

The boxes are NOT recomputed
----------------------------
Box bounds come from the live boxes_manifest.json, unchanged, with the same indices. No sampling
step exists here. v1's split tree (world_boxes.json) no longer exists anywhere, but the manifest
lists leaves in depth-first order, so the tree can be rebuilt exactly (rebuild_tree) and v1's own
route_to_box walks it, which keeps v1's rule for points on a shared edge. Any mismatch aborts the run.

What a box holds
----------------
The tables v1 writes keep exactly the same schema and the same selection rules, reused from v1 by
import rather than copied. Released apps run `INSERT OR IGNORE ... SELECT *` on nodes, ways, way_cells
and way_regions, so these must not gain columns, and new rows in `ways` would be drawn, announced and
turned into fake street junctions. So the new layers live in two new tables that those apps ignore:

  env_features(id, kind, type, name, role, relation_id, tags, min_lat, max_lat, min_lon, max_lon, points)
  env_cells(feature_id, cell_x, cell_y)

  kind     type (examples)                 comes from
  river    river stream canal river_area   waterway=*, natural=water + water=river|canal|..., riverbank
  rail     rail subway tram light_rail     railway=* (running track only, not disused or abandoned)
  airport  aerodrome runway terminal       aeroway=*
  sea      coastline sea ocean bay strait  natural=coastline, place=sea|ocean, natural=bay|strait
  lake     lake pond reservoir lagoon      natural=water, landuse=reservoir|basin

ENV_RULES below is the only place that decides this; adding a layer means adding a line there.

  * `points` is the same msgpack [[lat_e7, lon_e7, 0], ...] as ways.points, in OSM way order. That
    order matters for natural=coastline: OSM draws it with the water on the right.
  * The planet has no sea polygons, only those coastline lines.
  * Multipolygon relations (big lakes, riverbanks, airports) are stored as one row per member way,
    with the relation's tags, name and id and the member's role (outer/inner). No polygon is
    assembled, so a huge lake is never copied whole into dozens of boxes: each piece of shore goes
    only where it is. The price: a box lying entirely inside a lake or sea holds no row for it, and
    a box holding part of a ring cannot do a point-in-polygon test. Sound needs the distance to the
    water's edge, which this answers.
  * A feature goes into every box its line passes through, including boxes a long segment crosses
    without a vertex in them, and the same for env_cells (way_cells is by bbox instead). Ways and
    nodes keep v1's every-box-a-vertex-touches rule.
  * id is stable across boxes, so merging two boxes de-duplicates: way = way id; relation member =
    relation id << 33 | way id; node = -node id.

Memory
------
Rows live as packed bytes in fixed-size anonymous memory chunks (Arena), so no Python object per
feature survives; each box only keeps a u64 per row, and a way touching 5 boxes is stored once.
Untagged nodes are dropped in C++ by KeyFilter after the location index has seen them. Peak is reached
at the end of the read: the node location index (~110 GB for the planet, flex_mem) plus the arena.

Linux shares the arena with the box writers by fork; Windows, which has no fork, by named mapping.

Usage
-----
    # Whole planet: downloads from planet.openstreetmap.org, checks md5, resumes a broken download
    python build_planet_boxes_inmemory.py --workers 64 --snapshot-dir /data/snap

    # Already have the file
    python build_planet_boxes_inmemory.py --planet-pbf /data/planet.osm.pbf --workers 64

    # The writing part failed or was interrupted: continue without re-reading the planet
    python build_planet_boxes_inmemory.py --from-snapshot /data/snap --workers 64

    # Small test: an extract, writing only the boxes it touches
    python build_planet_boxes_inmemory.py --planet-pbf monaco.osm.pbf --skip-empty --chunk-mb 64

Requires: pip install osmium numpy msgpack (psutil optional, only for memory figures in the log)

Output: tools/out/planet_boxes/box_{i:06d}.db.gz, boxes_manifest.json (same format as v1, plus
env_features per box). Nothing is uploaded.
"""
import argparse
import array
import gc
import hashlib
import json
import math
import mmap
import multiprocessing
import os
import pickle
import platform
import struct
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import FIRST_COMPLETED, ProcessPoolExecutor, wait

import msgpack
import numpy as np
import osmium

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build_global_cluster_dbs as v1  # noqa: E402  (schema, tag rules, intersections, finalize)

PLANET_URL = "https://planet.openstreetmap.org/pbf/planet-latest.osm.pbf"
BOXES_MANIFEST_URL = "https://wikiponder.com/navia/global_boxes/boxes_manifest.json"
USER_AGENT = "Navia-planet-box-builder"

OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out", "planet_boxes")
STATS_NAME = "box_stats.json"
DEFAULT_CHUNK_MB = 1024
LOG_EVERY = 10_000_000

# A long segment is walked in steps of this many degrees (~2 km) when deciding which boxes and cells
# it passes through. The smallest boxes are several km across and cells are 8 km.
SEGMENT_STEP_DEG = 0.02
RELATION_SHIFT = 33


def log(msg: str):
    v1.log("planet", msg)


def rss_gb() -> str:
    try:
        import psutil
        return f"{psutil.Process().memory_info().rss / 1e9:.1f} GB"
    except ImportError:
        return "?"


# ------------------------------------------------------------------------------- layer definitions

# key -> {value: (kind, type)}; a value of None under "*" means any value of that key.
ENV_RULES = {
    "waterway": {
        "river": ("river", "river"), "stream": ("river", "stream"), "canal": ("river", "canal"),
        "tidal_channel": ("river", "tidal_channel"), "riverbank": ("river", "river_area"),
        "waterfall": ("river", "waterfall"), "rapids": ("river", "rapids"),
        "dam": ("river", "dam"), "weir": ("river", "weir"),
    },
    "railway": {
        "rail": ("rail", "rail"), "light_rail": ("rail", "light_rail"), "subway": ("rail", "subway"),
        "tram": ("rail", "tram"), "narrow_gauge": ("rail", "narrow_gauge"),
        "monorail": ("rail", "monorail"), "funicular": ("rail", "funicular"),
    },
    "aeroway": {
        "aerodrome": ("airport", "aerodrome"), "runway": ("airport", "runway"),
        "taxiway": ("airport", "taxiway"), "apron": ("airport", "apron"),
        "terminal": ("airport", "terminal"), "helipad": ("airport", "helipad"),
        "heliport": ("airport", "heliport"),
    },
    "natural": {
        "coastline": ("sea", "coastline"), "bay": ("sea", "bay"), "strait": ("sea", "strait"),
    },
    "place": {"sea": ("sea", "sea"), "ocean": ("sea", "ocean")},
    "landuse": {"reservoir": ("lake", "reservoir"), "basin": ("lake", "basin")},
}
RIVER_WATER_VALUES = {"river", "canal", "stream", "rapids", "tidal", "ditch", "drain", "moat"}
NOT_NATURAL_WATER = {"wastewater"}
# A node only counts for types that really are a point; a stray node tagged railway=rail is noise.
POINT_TYPES = {"aerodrome", "heliport", "helipad", "terminal", "waterfall", "rapids", "dam", "weir",
               "sea", "ocean", "bay", "strait"}
ENV_KEYS = frozenset(ENV_RULES)
V1_WAY_KEYS = frozenset(("highway", "barrier", "public_transport", "railway", "building"))
INTERESTING_WAY_KEYS = V1_WAY_KEYS | ENV_KEYS


def env_kind(tags: dict):
    """(kind, type) of the layer these tags belong to, or None."""
    if tags.get("natural") == "water":
        water = tags.get("water", "lake")
        if water in NOT_NATURAL_WATER:
            return None
        if water in RIVER_WATER_VALUES:
            return "river", "river_area"
        return "lake", water
    for key, values in ENV_RULES.items():
        value = tags.get(key)
        if value is not None:
            hit = values.get(value)
            if hit is not None:
                return hit
    return None


def node_filter_keys():
    keys = {tag for tag, _ in v1.IMPORTANT_NODE_TAGS if tag != "name"}
    keys |= {"addr:housenumber", "addr:street", "addr:place"}
    keys |= ENV_KEYS
    return sorted(keys)


# ------------------------------------------------------------------------------- box definition

def load_boxes(source: str) -> list:
    """Box list from the manifest, by URL or path, sorted by index and required to be complete."""
    if source.startswith(("http://", "https://")):
        req = urllib.request.Request(source, headers={"User-Agent": USER_AGENT})
        with urllib.request.urlopen(req, timeout=120) as r:
            doc = json.loads(r.read().decode("utf-8"))
    else:
        with open(source, encoding="utf-8") as f:
            doc = json.load(f)
    boxes = sorted(doc["boxes"], key=lambda b: b["index"])
    if [b["index"] for b in boxes] != list(range(doc["num_boxes"])):
        raise RuntimeError(f"manifest is not complete: {len(boxes)} boxes of {doc['num_boxes']}")
    return boxes


def _e7(x: float) -> int:
    v = round(x * 1e7)
    if abs(x * 1e7 - v) > 1e-3:
        raise RuntimeError(f"box bound {x} is not an exact E7 coordinate")
    return v


def rebuild_tree(boxes: list):
    """v1's split tree, from its leaves.

    v1 numbers leaves depth-first with the south/west child first, so the leaves below any tree node
    are a run [a, b) of indices and its two children split that run at one m: everything before m lies
    on one side of a single line and everything from m on lies on the other. A run that can be cut at
    several m (three boxes side by side) routes the same whichever is taken, since the edges and the
    per-axis rule are the same."""
    rects = [(_e7(b["lat_min"]), _e7(b["lat_max"]), _e7(b["lon_min"]), _e7(b["lon_max"])) for b in boxes]

    def split_of(a, b):
        for axis, lo, hi in (("lat", 0, 1), ("lon", 2, 3)):
            high = [rects[i][hi] for i in range(a, b)]
            low = [rects[i][lo] for i in range(a, b)]
            suffix_min = low[:]
            for i in range(len(low) - 2, -1, -1):
                suffix_min[i] = min(suffix_min[i], suffix_min[i + 1])
            prefix_max = high[0]
            for k in range(1, len(high)):
                if prefix_max <= suffix_min[k]:
                    return axis, prefix_max, a + k
                prefix_max = max(prefix_max, high[k])
        raise RuntimeError(f"boxes {a}..{b - 1} are not the leaves of one split: the manifest does "
                           f"not have v1's depth-first order")

    def build(a, b):
        if b - a == 1:
            return v1._Leaf(a)
        axis, value, m = split_of(a, b)
        return v1._Split(axis, value, build(a, m), build(m, b))

    sys.setrecursionlimit(max(sys.getrecursionlimit(), 10_000))
    root = build(0, len(boxes))
    check_tree(root, rects)
    return root


def check_tree(root, rects):
    """Every box must own its own interior and its inclusive edges (lat_max, lon_min) in the tree."""
    for i, (la0, la1, lo0, lo1) in enumerate(rects):
        points = [((la0 + la1) // 2, (lo0 + lo1) // 2)]
        if la1 > la0 and lo1 > lo0:
            points.append((la1, lo0))              # the corner this box owns: top edge, west edge
            points.append((la0 + 1, lo1 - 1))      # just inside the opposite corner
        for la, lo in points:
            got = v1.route_to_box(root, la, lo)
            if got != i:
                raise RuntimeError(f"rebuilt tree sends ({la},{lo}) to box {got}, not {i}")


# ------------------------------------------------------------------------------- the arena

_arena_chunks = []  # worker-side view, set by _attach_arena on Windows and inherited on Linux


def _new_chunk(size: int, tag):
    return mmap.mmap(-1, size, tagname=tag) if tag else mmap.mmap(-1, size)


class Arena:
    """Append-only store of length-prefixed byte rows, in fixed chunks that never move. A row's address
    is chunk << 32 | position. Fixed chunks because growing one huge buffer means copying it, which at
    this size is slow and, as v1 found, can fail with far more free RAM than the data needs."""

    def __init__(self, chunk_size: int):
        self.chunk_size = chunk_size
        self.named = platform.system() == "Windows"
        self.prefix = f"navia_arena_{os.getpid()}_"
        self.chunks = []
        self.pos = chunk_size  # forces a chunk on first add
        self.rows = 0

    def _grow(self):
        tag = f"{self.prefix}{len(self.chunks)}" if self.named else None
        self.chunks.append(_new_chunk(self.chunk_size, tag))
        self.pos = 0

    def add(self, row: bytes) -> int:
        need = len(row) + 4
        if need > self.chunk_size:
            raise RuntimeError(f"a row of {need:,} bytes does not fit a chunk; raise --chunk-mb")
        if self.pos + need > self.chunk_size:
            self._grow()
        chunk = self.chunks[-1]
        struct.pack_into("<I", chunk, self.pos, len(row))
        chunk[self.pos + 4:self.pos + need] = row
        address = (len(self.chunks) - 1) << 32 | self.pos
        self.pos += need
        self.rows += 1
        return address

    def raw(self, address: int) -> bytes:
        chunk = self.chunks[address >> 32]
        pos = address & 0xffffffff
        n = struct.unpack_from("<I", chunk, pos)[0]
        return chunk[pos + 4:pos + 4 + n]

    def bytes_used(self) -> int:
        return max(0, len(self.chunks) - 1) * self.chunk_size + (self.pos if self.chunks else 0)

    def attach_args(self):
        """What a spawned worker needs to open these chunks; None when it inherits them by fork."""
        if not self.named:
            return None
        return [f"{self.prefix}{i}" for i in range(len(self.chunks))], self.chunk_size

    def save(self, directory: str):
        os.makedirs(directory, exist_ok=True)
        for i, chunk in enumerate(self.chunks):
            with open(os.path.join(directory, f"chunk_{i:06d}.bin"), "wb") as f:
                f.write(chunk)

    @staticmethod
    def load(directory: str, count: int, chunk_size: int) -> "Arena":
        arena = Arena(chunk_size)
        for i in range(count):
            arena._grow()
            with open(os.path.join(directory, f"chunk_{i:06d}.bin"), "rb") as f:
                f.readinto(arena.chunks[-1])
        return arena


def _attach_arena(args):
    global _arena_chunks
    if args is not None:
        names, size = args
        _arena_chunks = [_new_chunk(size, name) for name in names]


def _read_row(address: int):
    chunk = _arena_chunks[address >> 32]
    pos = address & 0xffffffff
    n = struct.unpack_from("<I", chunk, pos)[0]
    return msgpack.unpackb(chunk[pos + 4:pos + 4 + n], raw=False)


# ------------------------------------------------------------------------------- geometry

def polyline_samples(lat_e7: list, lon_e7: list):
    """The vertices plus points along long segments, as E7 integer arrays."""
    la = np.asarray(lat_e7, dtype=np.int64)
    lo = np.asarray(lon_e7, dtype=np.int64)
    if len(la) < 2:
        return la, lo
    step = SEGMENT_STEP_DEG * 1e7
    d_la = np.diff(la)
    d_lo = np.diff(lo)
    # Longitudes jump by ~360 degrees where a way crosses the antimeridian; never walk across the planet.
    long_ones = np.nonzero((np.maximum(np.abs(d_la), np.abs(d_lo)) > step)
                           & (np.abs(d_lo) < 1_800_000_000))[0]
    if len(long_ones) == 0:
        return la, lo
    extra_la = []
    extra_lo = []
    for i in long_ones.tolist():
        n = int(max(abs(d_la[i]), abs(d_lo[i])) // step) + 1
        t = np.arange(1, n) / n
        extra_la.append((la[i] + d_la[i] * t).astype(np.int64))
        extra_lo.append((lo[i] + d_lo[i] * t).astype(np.int64))
    return np.concatenate([la, *extra_la]), np.concatenate([lo, *extra_lo])


def boxes_of(tree, la: np.ndarray, lo: np.ndarray) -> set:
    return {v1.route_to_box(tree, a, b) for a, b in set(zip(la.tolist(), lo.tolist()))}


def cells_of(lat_e7: list, lon_e7: list) -> set:
    """8 km cells a line passes through, each point mapped exactly as v1.cells_for_bbox maps a one-point
    bbox: y from its latitude, x using the latitude of that cell row, as the app's own queries do."""
    la, lo = polyline_samples(lat_e7, lon_e7)
    cell = v1.CELL_SIZE_METERS
    earth = v1.EARTH_RADIUS
    y = np.floor(earth * np.radians(la / 1e7) / cell + 0.5)
    row_lat = np.radians(np.degrees(y * cell / earth))
    x = np.floor(np.radians(lo / 1e7) / (cell / earth / np.cos(row_lat)) + 0.5)
    return set(zip(x.astype(np.int64).tolist(), y.astype(np.int64).tolist()))


# ------------------------------------------------------------------------------- reading the planet

class Tables:
    """Per-box row addresses for the three kinds of row."""

    def __init__(self, n: int):
        self.nodes = [array.array("Q") for _ in range(n)]
        self.ways = [array.array("Q") for _ in range(n)]
        self.env = [array.array("Q") for _ in range(n)]


def read_relations(pbf: str):
    """Multipolygon relations in a layer: way id -> [(relation id, role, kind, type, name, tags blob)].
    A pass of its own because a pbf has relations after the ways they name, and a member way often has
    no tags at all, so nothing else would make us keep it."""
    members = {}
    count = 0
    processor = osmium.FileProcessor(pbf, osmium.osm.RELATION).with_filter(osmium.filter.KeyFilter("type"))
    for r in processor:
        if r.tags.get("type") != "multipolygon":
            continue
        tags = dict(r.tags)
        hit = env_kind(tags)
        if hit is None:
            continue
        count += 1
        name = tags.get("name", "")
        blob = v1.pack_tags(tags)
        for m in r.members:
            if m.type == "w" and m.role in ("outer", "inner", ""):
                members.setdefault(m.ref, []).append((r.id, m.role or "outer", hit[0], hit[1], name, blob))
    log(f"relations: {count:,} in a layer, {len(members):,} member ways, rss {rss_gb()}")
    return members


def read_planet(pbf: str, tree, boxes: list, chunk_size: int, location_index: str):
    members = read_relations(pbf)
    arena = Arena(chunk_size)
    tables = Tables(len(boxes))
    route = v1.route_to_box
    packb = msgpack.packb
    stats = {"nodes_kept": 0, "ways_kept": 0, "env_nodes": 0, "env_ways": 0, "env_members": 0,
             "ways_seen": 0, "ways_incomplete": 0}
    t0 = time.time()
    seen = 0
    rects = [(_e7(b["lat_min"]), _e7(b["lat_max"]), _e7(b["lon_min"]), _e7(b["lon_max"])) for b in boxes]

    def single_box(lat_e7, lon_e7, la0, la1, lo0, lo1):
        """The one box holding this whole bbox, or None. Boxes are rectangles, so most ways need one
        tree walk instead of one per vertex. Uses the same (lat_min, lat_max] [lon_min, lon_max) edges
        as the tree; anything on the planet's outer edge just takes the slow way."""
        box = route(tree, lat_e7, lon_e7)
        r = rects[box]
        return box if la0 > r[0] and la1 <= r[1] and lo0 >= r[2] and lo1 < r[3] else None

    store = osmium.index.create_map(location_index)
    # C++ drops everything uninteresting before Python sees it, after the location index has seen it.
    processor = (osmium.FileProcessor(pbf, osmium.osm.NODE | osmium.osm.WAY)
                 .with_locations(store)
                 .with_filter(osmium.filter.KeyFilter(*node_filter_keys()).enable_for(osmium.osm.NODE))
                 .with_filter(osmium.filter.KeyFilter(*INTERESTING_WAY_KEYS).enable_for(osmium.osm.WAY)))
    for o in processor:
        seen += 1
        if seen % LOG_EVERY == 0:
            log(f"read {seen:,} objects in {time.time() - t0:,.0f}s: {stats}, "
                f"arena {arena.bytes_used() / 1e9:.1f} GB, rss {rss_gb()}")

        if o.is_node():
            loc = o.location
            if not loc.valid():
                continue
            tags = dict(o.tags)
            lat_e7, lon_e7 = loc.y, loc.x
            box = None

            cat_id, ntype, name = v1.build_name_and_cat(tags)
            if cat_id != v1.CAT_SKIP:
                box = route(tree, lat_e7, lon_e7)
                row = (abs(o.id) + v1.ID_OFFSET, loc.lat, loc.lon, cat_id, ntype, name, v1.pack_tags(tags))
                tables.nodes[box].append(arena.add(packb(row, use_bin_type=True)))
                stats["nodes_kept"] += 1

            hit = env_kind(tags)
            if hit is not None and hit[1] in POINT_TYPES:
                if box is None:
                    box = route(tree, lat_e7, lon_e7)
                lat, lon = lat_e7 / 1e7, lon_e7 / 1e7
                row = (-abs(o.id), hit[0], hit[1], tags.get("name", ""), "", 0, v1.pack_tags(tags),
                       lat, lat, lon, lon, [[lat_e7, lon_e7, v1.REGION_WORLD]])
                tables.env[box].append(arena.add(packb(row, use_bin_type=True)))
                stats["env_nodes"] += 1
            continue

        # ------- way
        stats["ways_seen"] += 1
        way_id = o.id
        if len(o.nodes) < 2:
            continue
        tags = dict(o.tags)

        # Same decisions, in the same order, as v1's RegionImporter.way.
        wtype = v1.get_way_type(tags)
        way_name = ""
        if wtype:
            if not v1.filter_navigation_tags(tags, None) and wtype != "address":
                wtype = ""
            elif wtype == "address":
                _, _, way_name = v1.build_name_and_cat(tags)
                if not way_name:
                    wtype = ""
            else:
                way_name = tags.get("name", "")
        hit = env_kind(tags)
        if not wtype and hit is None:
            continue

        lats = []
        lons = []
        for nd in o.nodes:
            loc = nd.location
            if not loc.valid():
                lats = None
                break
            lats.append(loc.y)
            lons.append(loc.x)
        if lats is None:
            stats["ways_incomplete"] += 1
            continue

        la0, la1, lo0, lo1 = min(lats), max(lats), min(lons), max(lons)
        min_lat, max_lat = la0 / 1e7, la1 / 1e7
        min_lon, max_lon = lo0 / 1e7, lo1 / 1e7
        points = [[a, b, v1.REGION_WORLD] for a, b in zip(lats, lons)]
        only_box = single_box(lats[0], lons[0], la0, la1, lo0, lo1)

        if wtype:
            cat_id = 0 if wtype == "address" else 15 if wtype.startswith("barrier:") else 60
            row = (abs(way_id) + v1.ID_OFFSET, cat_id, wtype, way_name, v1.pack_tags(tags),
                   min_lat, max_lat, min_lon, max_lon, packb(points, use_bin_type=True))
            address = arena.add(packb(row, use_bin_type=True))
            touched = (only_box,) if only_box is not None else {route(tree, a, b) for a, b in zip(lats, lons)}
            for box in touched:
                tables.ways[box].append(address)
            stats["ways_kept"] += 1

        if hit is not None:
            touched = (only_box,) if only_box is not None else boxes_of(tree, *polyline_samples(lats, lons))
            row = (abs(way_id), hit[0], hit[1], tags.get("name", ""), "", 0, v1.pack_tags(tags),
                   min_lat, max_lat, min_lon, max_lon, points)
            address = arena.add(packb(row, use_bin_type=True))
            for box in touched:
                tables.env[box].append(address)
            stats["env_ways"] += 1

    log(f"nodes and tagged ways read in {time.time() - t0:,.0f}s: {stats}, "
        f"arena {arena.bytes_used() / 1e9:.1f} GB, rss {rss_gb()}")
    del processor

    # Members of a layer's relations, many of them untagged. A pass of its own, reading ways only, so
    # the billion other ways never reach Python just to be asked whether they are members.
    if members:
        ids = osmium.filter.IdFilter(members.keys()).enable_for(osmium.osm.WAY)
        for o in osmium.FileProcessor(pbf, osmium.osm.WAY).with_filter(ids):
            lats = []
            lons = []
            try:
                for nd in o.nodes:
                    loc = store.get(nd.ref)
                    lats.append(loc.y)
                    lons.append(loc.x)
            except KeyError:
                stats["ways_incomplete"] += 1
                continue
            if len(lats) < 2:
                continue
            la0, la1, lo0, lo1 = min(lats), max(lats), min(lons), max(lons)
            only_box = single_box(lats[0], lons[0], la0, la1, lo0, lo1)
            touched = (only_box,) if only_box is not None else boxes_of(tree, *polyline_samples(lats, lons))
            points = [[a, b, v1.REGION_WORLD] for a, b in zip(lats, lons)]
            for rel_id, role, kind, etype, name, blob in members[o.id]:
                row = (rel_id << RELATION_SHIFT | o.id, kind, etype, name, role, rel_id, blob,
                       la0 / 1e7, la1 / 1e7, lo0 / 1e7, lo1 / 1e7, points)
                address = arena.add(packb(row, use_bin_type=True))
                for box in touched:
                    tables.env[box].append(address)
                stats["env_members"] += 1

    log(f"read finished in {time.time() - t0:,.0f}s: {stats}, arena {arena.bytes_used() / 1e9:.1f} GB, "
        f"rss {rss_gb()}")
    del store, members
    gc.collect()
    return arena, tables, stats


# ------------------------------------------------------------------------------- writing boxes

ENV_SCHEMA = """
    CREATE TABLE env_features(
      id INTEGER PRIMARY KEY,
      kind TEXT NOT NULL,
      type TEXT NOT NULL,
      name TEXT,
      role TEXT,
      relation_id INTEGER NOT NULL DEFAULT 0,
      tags BLOB,
      min_lat REAL, max_lat REAL,
      min_lon REAL, max_lon REAL,
      points BLOB
    );
    CREATE TABLE env_cells(
      feature_id INTEGER NOT NULL,
      cell_x INTEGER NOT NULL,
      cell_y INTEGER NOT NULL,
      PRIMARY KEY(cell_x, cell_y, feature_id)
    );
    CREATE INDEX IF NOT EXISTS env_cells_feature ON env_cells(feature_id);
"""


def _node_rows(rows):
    for node_id, lat, lon, cat_id, ntype, name, tags in rows:
        cell_x, cell_y = v1.lat_lon_to_index(lat, lon)
        yield node_id, lat, lon, cell_x, cell_y, cat_id, 0, ntype, name, tags, v1.REGION_WORLD


def fill_box(conn, nodes, ways, env):
    """Inserts unpacked rows into an open box db. INSERT OR IGNORE, so the same feature arriving twice
    (from two regions, or a box being reopened) is harmless."""
    conn.executemany(
        "INSERT OR IGNORE INTO nodes (id,lat,lon,cell_x,cell_y,cat_id,favorite,type,name,tags,region) "
        "VALUES (?,?,?,?,?,?,?,?,?,?,?)", _node_rows(nodes))
    for row in ways:
        way_id = row[0]
        conn.execute(
            "INSERT OR IGNORE INTO ways (id,cat_id,type,name,tags,min_lat,max_lat,min_lon,max_lon,points) "
            "VALUES (?,?,?,?,?,?,?,?,?,?)", row)
        conn.executemany(
            "INSERT OR IGNORE INTO way_cells (way_id,cell_x,cell_y) VALUES (?,?,?)",
            [(way_id, cx, cy) for cx, cy in v1.cells_for_bbox(row[5], row[6], row[7], row[8])])
        conn.execute("INSERT OR IGNORE INTO way_regions (region_id,way_id) VALUES (?,?)",
                     (v1.REGION_WORLD, way_id))
    for fid, kind, etype, name, role, rel_id, tags, a, b, c, d, points in env:
        conn.execute(
            "INSERT OR IGNORE INTO env_features "
            "(id,kind,type,name,role,relation_id,tags,min_lat,max_lat,min_lon,max_lon,points) "
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
            (fid, kind, etype, name, role, rel_id, tags, a, b, c, d,
             msgpack.packb(points, use_bin_type=True)))
        conn.executemany(
            "INSERT OR IGNORE INTO env_cells (feature_id,cell_x,cell_y) VALUES (?,?,?)",
            [(fid, cx, cy) for cx, cy in cells_of([p[0] for p in points], [p[1] for p in points])])


def _write_box(task):
    index, node_bytes, way_bytes, env_bytes, out_dir = task
    try:
        conn = v1.create_box_db(v1.box_db_path(out_dir, index))
        conn.executescript(ENV_SCHEMA)
        conn.execute("BEGIN")
        fill_box(conn,
                 map(_read_row, array.array("Q", node_bytes)),
                 map(_read_row, array.array("Q", way_bytes)),
                 map(_read_row, array.array("Q", env_bytes)))
        env_count = conn.execute("SELECT COUNT(*) FROM env_features").fetchone()[0]
        conn.commit()
        conn.close()

        stats = v1._finalize_box(index, out_dir)  # intersections, VACUUM, gzip, removes the plain .db
        stats["env_features"] = env_count
        return stats, None
    except Exception as e:
        return {"index": index}, f"{type(e).__name__}: {e}"


def write_boxes(arena: Arena, tables: Tables, boxes: list, out_dir: str, workers: int, skip_empty: bool):
    stats_path = os.path.join(out_dir, STATS_NAME)
    results = {}
    if os.path.exists(stats_path):
        # A box counts as done only if its .gz is really there.
        for entry in v1.read_json(stats_path):
            if os.path.exists(v1.box_db_path(out_dir, entry["index"]) + ".gz"):
                results[entry["index"]] = entry

    todo = []
    for i in range(len(boxes)):
        if i in results:
            continue
        if skip_empty and not (len(tables.nodes[i]) or len(tables.ways[i]) or len(tables.env[i])):
            continue
        todo.append(i)
    log(f"writing {len(todo)} boxes with {workers} workers ({len(results)} already done)")

    arena_view = arena.attach_args()
    if arena_view is None:
        # Linux: workers inherit the chunks. fork, not the default forkserver, is what shares them.
        global _arena_chunks
        _arena_chunks = arena.chunks
        ctx = multiprocessing.get_context("fork")
    else:
        ctx = multiprocessing.get_context("spawn")

    failed = {}
    t0 = time.time()
    with ProcessPoolExecutor(max_workers=workers, mp_context=ctx, initializer=_attach_arena,
                             initargs=(arena_view,)) as pool:
        next_task = 0
        running = set()
        done_count = 0
        while next_task < len(todo) or running:
            # A small window: address lists are copied to the worker, so don't queue all of them at once.
            while next_task < len(todo) and len(running) < workers * 2:
                i = todo[next_task]
                next_task += 1
                running.add(pool.submit(_write_box, (i, tables.nodes[i].tobytes(), tables.ways[i].tobytes(),
                                                     tables.env[i].tobytes(), out_dir)))
            finished, running = wait(running, return_when=FIRST_COMPLETED)
            for future in finished:
                stats, error = future.result()
                i = stats["index"]
                done_count += 1
                if error is not None:
                    failed[i] = error
                    log(f"FAILED box_{i:06d}: {error}")
                    continue
                results[i] = stats
            if done_count % 16 == 0 or finished and not running:
                v1.write_json(stats_path, list(results.values()))
                log(f"{done_count}/{len(todo)} boxes in {time.time() - t0:,.0f}s")
    v1.write_json(stats_path, list(results.values()))
    return results, failed


def write_manifest(results: dict, boxes: list, out_dir: str, source: str, skip_empty: bool) -> list:
    entries = []
    for i in sorted(results):
        b = boxes[i]
        entries.append({**results[i], "lat_min": b["lat_min"], "lat_max": b["lat_max"],
                        "lon_min": b["lon_min"], "lon_max": b["lon_max"],
                        "sampled_nodes": b.get("sampled_nodes")})
    v1.write_json(os.path.join(out_dir, v1.MANIFEST_NAME), {
        "num_boxes": len(boxes),
        "num_boxes_finalized": len(entries),
        "total_db_size_mb": round(sum(e["db_size_mb"] for e in entries), 2),
        "total_gz_size_mb": round(sum(e["gz_size_mb"] for e in entries), 2),
        "source": os.path.basename(source),
        "boxes": entries,
    })
    missing = sorted(set(range(len(boxes))) - set(results))
    if skip_empty:
        log(f"wrote {v1.MANIFEST_NAME}: {len(entries)} boxes ({len(missing)} empty ones skipped, so this "
            f"manifest is for testing only)")
        return []
    if missing:
        log(f"WARNING: {len(missing)}/{len(boxes)} boxes are MISSING. See {v1.MISSING_BOXES_NAME}.")
        v1.write_json(os.path.join(out_dir, v1.MISSING_BOXES_NAME), {
            "missing_count": len(missing), "total_boxes": len(boxes),
            "boxes": [{k: boxes[i].get(k) for k in ("index", "lat_min", "lat_max", "lon_min", "lon_max")}
                      for i in missing]})
    else:
        log(f"wrote {v1.MANIFEST_NAME}: all {len(boxes)} boxes present.")
    return missing


# ------------------------------------------------------------------------------- snapshot

def save_snapshot(directory: str, arena: Arena, tables: Tables, source: str, stats: dict):
    t0 = time.time()
    arena.save(directory)
    with open(os.path.join(directory, "tables.pkl"), "wb") as f:
        pickle.dump({"nodes": tables.nodes, "ways": tables.ways, "env": tables.env}, f, protocol=5)
    # Written last: its presence means the snapshot is complete.
    v1.write_json(os.path.join(directory, "snapshot.json"), {
        "chunks": len(arena.chunks), "chunk_size": arena.chunk_size, "pos": arena.pos,
        "source": source, "stats": stats})
    log(f"snapshot saved to {directory} in {time.time() - t0:,.0f}s")


def load_snapshot(directory: str):
    meta = v1.read_json(os.path.join(directory, "snapshot.json"))
    arena = Arena.load(directory, meta["chunks"], meta["chunk_size"])
    arena.pos = meta["pos"]
    with open(os.path.join(directory, "tables.pkl"), "rb") as f:
        doc = pickle.load(f)
    tables = Tables(0)
    tables.nodes, tables.ways, tables.env = doc["nodes"], doc["ways"], doc["env"]
    log(f"snapshot loaded from {directory}: {arena.bytes_used() / 1e9:.1f} GB, source {meta['source']}")
    return arena, tables, meta["source"]


# ------------------------------------------------------------------------------- download

def _md5_of(path: str) -> str:
    h = hashlib.md5()
    with open(path, "rb") as f:
        while True:
            block = f.read(1 << 24)
            if not block:
                return h.hexdigest()
            h.update(block)


def download_planet(url: str, directory: str) -> str:
    """Downloads to a name fixed by where -latest redirects right now, so a resume after a new planet
    has come out keeps fetching the same file. Resumes with Range requests, retries, and checks the
    md5 the server publishes. A file whose md5 cannot be checked is an error."""
    os.makedirs(directory, exist_ok=True)

    def retrying(call, tries=6):
        for attempt in range(tries):
            try:
                return call()
            except (urllib.error.URLError, OSError, TimeoutError) as e:
                if attempt == tries - 1:
                    raise
                log(f"{url}: {e}, retrying")
                time.sleep(10 * (attempt + 1))

    def head():
        req = urllib.request.Request(url, method="HEAD", headers={"User-Agent": USER_AGENT})
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.url, int(r.headers.get("Content-Length", 0))

    def md5_at(address):
        req = urllib.request.Request(address, headers={"User-Agent": USER_AGENT})
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.read().decode("ascii").split()[0]

    final_url, total = retrying(head)
    name = os.path.basename(urllib.parse.urlparse(final_url).path)
    try:
        md5 = md5_at(final_url + ".md5")
    except urllib.error.HTTPError:
        md5 = retrying(lambda: md5_at(url + ".md5"))
    except (urllib.error.URLError, OSError, TimeoutError):
        md5 = retrying(lambda: md5_at(final_url + ".md5"))

    dest = os.path.join(directory, name)
    if os.path.exists(dest) and os.path.getsize(dest) == total and _md5_of(dest) == md5:
        log(f"already have {name}, md5 ok")
        return dest

    part = dest + ".part"
    failures = 0
    while True:
        have = os.path.getsize(part) if os.path.exists(part) else 0
        if total and have >= total:
            break
        try:
            headers = {"User-Agent": USER_AGENT}
            if have:
                headers["Range"] = f"bytes={have}-"
            with urllib.request.urlopen(urllib.request.Request(final_url, headers=headers), timeout=120) as r:
                if have and r.status != 206:
                    have = 0  # server ignored the range: start over
                with open(part, "ab" if have else "wb") as f:
                    last_log = time.time()
                    while True:
                        block = r.read(1 << 22)
                        if not block:
                            break
                        f.write(block)
                        have += len(block)
                        failures = 0
                        if time.time() - last_log > 60:
                            last_log = time.time()
                            log(f"downloading {name}: {have / 1e9:.1f}/{total / 1e9:.1f} GB")
            if not total:
                break
        except (urllib.error.URLError, OSError, TimeoutError) as e:
            failures += 1
            if failures > 50:
                raise
            log(f"download interrupted ({e}), retry {failures} from {have / 1e9:.1f} GB")
            time.sleep(min(300, 5 * failures))

    got = _md5_of(part)
    if got != md5:
        os.remove(part)
        raise RuntimeError(f"{name}: md5 {got} is not the published {md5}; the file was removed")
    os.replace(part, dest)
    log(f"downloaded {name}, md5 ok")
    return dest


# ------------------------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser(description="Build all box dbs from one planet file, in memory.")
    ap.add_argument("--planet-pbf", help="Use this file instead of downloading.")
    ap.add_argument("--planet-url", default=PLANET_URL, help=f"Where to download (default {PLANET_URL}).")
    ap.add_argument("--download-dir", default=None, help="Where the download goes (default --out-dir).")
    ap.add_argument("--boxes-manifest", default=BOXES_MANIFEST_URL,
                    help=f"Source of the box bounds, URL or path (default {BOXES_MANIFEST_URL}).")
    ap.add_argument("--out-dir", default=OUT_DIR, help=f"Output directory (default {OUT_DIR}).")
    ap.add_argument("--workers", type=int, default=os.cpu_count() or 8, help="Parallel box writers.")
    ap.add_argument("--location-index", default="flex_mem", help="osmium node location index.")
    ap.add_argument("--chunk-mb", type=int, default=DEFAULT_CHUNK_MB, help="Arena chunk size.")
    ap.add_argument("--snapshot-dir", help="After reading, save the in-memory map here, so a failure "
                                           "while writing never costs another read of the planet.")
    ap.add_argument("--from-snapshot", help="Skip download and read; continue from this snapshot.")
    ap.add_argument("--skip-empty", action="store_true", help="Testing: only write boxes with data.")
    args = ap.parse_args()

    os.makedirs(args.out_dir, exist_ok=True)
    v1.raise_file_limit(8192)

    boxes = load_boxes(args.boxes_manifest)
    tree = rebuild_tree(boxes)
    log(f"{len(boxes)} boxes from {args.boxes_manifest}; split tree rebuilt and checked")

    if args.from_snapshot:
        arena, tables, source = load_snapshot(args.from_snapshot)
    else:
        source = args.planet_pbf or download_planet(args.planet_url, args.download_dir or args.out_dir)
        arena, tables, stats = read_planet(source, tree, boxes, args.chunk_mb << 20, args.location_index)
        if args.snapshot_dir:
            save_snapshot(args.snapshot_dir, arena, tables, source, stats)

    results, failed = write_boxes(arena, tables, boxes, args.out_dir, max(1, args.workers), args.skip_empty)
    missing = write_manifest(results, boxes, args.out_dir, source, args.skip_empty)
    if failed or missing:
        log(f"NOT COMPLETE: {len(failed)} failed, {len(missing)} missing. Re-run with --from-snapshot to "
            f"retry just those.")
        sys.exit(1)
    log(f"done: {args.out_dir}")


if __name__ == "__main__":
    main()
