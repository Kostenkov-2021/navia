#!/usr/bin/env python3
r"""
Builds every map box from Geofabrik's regional extracts, for a machine that cannot hold the planet in
RAM, using as little scratch disk as it can. Same output as build_planet_boxes_inmemory.py: it uses
that tool's reading rules, box definition (the live manifest) and table writing, by import.

Scratch goes on a fast drive and finished .db.gz files on any big one (--scratch-dir, --out-dir).

How disk stays low
------------------
  1. A worker downloads one region, reads it with build_planet_boxes_inmemory.read_planet, and writes
     its rows to one zlib-compressed spool file per box it touched, then deletes the pbf.
  2. A box is written as soon as every region overlapping it is finished; its spool is then deleted
     and its .db.gz moved to --out-dir.
  3. Regions run in Hilbert-curve order of their position, so neighbours run close together and a
     box's spool is short-lived. --plan simulates this and prints the expected peak without
     downloading anything.
  4. No new region starts while the scratch drive has less than --min-free-gb to spare.

Regions overlap at their borders, since an extract holds whole ways, so one feature often arrives
twice. INSERT OR IGNORE and stable ids make that harmless. A long way can also reach a box beyond its
region's outline, after that box was written. Such a box is reopened and rewritten, so no row is lost.

A box is never written while a region overlapping it has failed or is unfinished. Re-running with the
same --scratch-dir and --out-dir continues where it stopped; a region that was interrupted starts over.

Usage
-----
    # What will this need? (no downloads)
    python build_boxes_by_region.py --plan

    python build_boxes_by_region.py --out-dir F:\navia_boxes --scratch-dir C:\navia_scratch

    # A test: only these regions, writing only the boxes they touch
    python build_boxes_by_region.py --out-dir F:\t --scratch-dir C:\t --only monaco,luxembourg --skip-empty

Output: --out-dir\box_{i:06d}.db.gz, boxes_manifest.json. Nothing is uploaded.
"""
import argparse
import gc
import gzip
import json
import multiprocessing
import os
import shutil
import struct
import sys
import time
import zlib
from concurrent.futures import FIRST_COMPLETED, ProcessPoolExecutor, ThreadPoolExecutor, wait

import msgpack
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build_global_cluster_dbs as v1  # noqa: E402
import build_planet_boxes_inmemory as planet  # noqa: E402

GB = 1e9
DEFAULT_REGION_WORKERS = 10
DEFAULT_BOX_WORKERS = 6
DEFAULT_MEMORY_BUDGET_GB = 48.0
DEFAULT_MIN_FREE_GB = 15.0
CHUNK_BYTES = 64 << 20
OVERLAP_MARGIN_DEG = 0.1
MAX_REGION_ATTEMPTS = 3
STATE_NAME = "region_state.json"
REGIONS_NAME = "regions.json"

# Calibrated by measuring real regions; only used to decide how many regions run at once and for --plan.
RAM_PER_PBF_BYTE = 4.0  # measured: sweden, 0.95 GB pbf, peak 3.75 GB
RAM_BASE_BYTES = 300_000_000
SPOOL_PER_PBF_BYTE = 0.85


def log(msg: str):
    v1.log("regions", msg)


# ------------------------------------------------------------------------------- regions and boxes

def hilbert_index(lat: float, lon: float, order: int = 12) -> int:
    n = 1 << order
    x = min(n - 1, int((lon + 180.0) / 360.0 * n))
    y = min(n - 1, int((lat + 90.0) / 180.0 * n))
    d = 0
    s = n >> 1
    while s > 0:
        rx = 1 if x & s else 0
        ry = 1 if y & s else 0
        d += s * s * ((3 * rx) ^ ry)
        if ry == 0:
            if rx == 1:
                x, y = s - 1 - (x & (s - 1)), s - 1 - (y & (s - 1))
            else:
                x, y = x & (s - 1), y & (s - 1)
            x, y = y, x
        else:
            x, y = x & (s - 1), y & (s - 1)
        s >>= 1
    return d


def polygon_bboxes(geometry: dict) -> list:
    """One (lat0, lat1, lon0, lon1) per polygon rather than one per region: a region crossing the
    antimeridian (the Russian far east, Alaska, Fiji) would otherwise claim every box around the world."""
    polygons = geometry["coordinates"] if geometry["type"] == "MultiPolygon" else [geometry["coordinates"]]
    out = []
    for polygon in polygons:
        lons = [p[0] for p in polygon[0]]
        lats = [p[1] for p in polygon[0]]
        out.append((min(lats), max(lats), min(lons), max(lons)))
    return out


def load_regions(scratch: str, only: set) -> list:
    """Leaf regions with size and outline, in processing order. Sizes and outlines are kept on disk so a
    resumed run works on the same list."""
    path = os.path.join(scratch, REGIONS_NAME)
    if os.path.exists(path):
        regions = v1.read_json(path)
    else:
        index = v1.load_geofabrik_index()
        geometry = {f["properties"].get("id"): f.get("geometry") for f in index["features"]}
        regions = v1.fetch_leaf_regions(index)
        def size_of(region):
            # fetch_pbf_size reports any failure as 0. Measured: 8 parallel HEADs had 70 of 510 time out
            # at Geofabrik, 2 parallel had none.
            for attempt in range(6):
                size = v1.fetch_pbf_size(region)
                if size > 0:
                    return size
                time.sleep(5 * (attempt + 1))
            return 0
        with ThreadPoolExecutor(max_workers=2) as pool:
            sizes = list(pool.map(size_of, regions))
        for region, size in zip(regions, sizes):
            region["pbf_size"] = size
            region["bboxes"] = polygon_bboxes(geometry[region["id"]])
            if size <= 0:
                raise RuntimeError(f"no size for region {region['id']}")
        def position(r):
            biggest = max(r["bboxes"], key=lambda b: (b[1] - b[0]) * (b[3] - b[2]))
            return hilbert_index((biggest[0] + biggest[1]) / 2, (biggest[2] + biggest[3]) / 2)
        regions.sort(key=position)
        os.makedirs(scratch, exist_ok=True)
        v1.write_json(path, regions)
    if only:
        unknown = only - {r["id"] for r in regions}
        if unknown:
            raise RuntimeError(f"unknown regions: {sorted(unknown)}")
        regions = [r for r in regions if r["id"] in only]
    return regions


def boxes_per_region(regions: list, boxes: list) -> dict:
    lat0 = np.array([b["lat_min"] for b in boxes])
    lat1 = np.array([b["lat_max"] for b in boxes])
    lon0 = np.array([b["lon_min"] for b in boxes])
    lon1 = np.array([b["lon_max"] for b in boxes])
    out = {}
    m = OVERLAP_MARGIN_DEG
    for region in regions:
        hit = np.zeros(len(boxes), dtype=bool)
        for a, b, c, d in region["bboxes"]:
            hit |= (lat0 <= b + m) & (lat1 >= a - m) & (lon0 <= d + m) & (lon1 >= c - m)
        out[region["id"]] = set(np.nonzero(hit)[0].tolist())
    return out


def plan(regions: list, boxes: list):
    """Simulates the run one region at a time and reports the largest the spool gets. A region's spool
    is spread over the boxes it overlaps in proportion to their size in the current manifest."""
    per_region = boxes_per_region(regions, boxes)
    waiting = {}
    for rid, touched in per_region.items():
        for b in touched:
            waiting.setdefault(b, set()).add(rid)
    weight = [max(0.01, b.get("db_size_mb", 1.0)) for b in boxes]
    spool = {}
    live = peak = 0.0
    peak_after = ""
    for region in regions:
        rid = region["id"]
        touched = per_region[rid]
        total = sum(weight[b] for b in touched) or 1.0
        add = region["pbf_size"] * SPOOL_PER_PBF_BYTE
        for b in touched:
            part = add * weight[b] / total
            spool[b] = spool.get(b, 0.0) + part
            live += part
        if live > peak:
            peak, peak_after = live, rid
        for b in touched:
            waiting[b].discard(rid)
            if not waiting[b]:
                live -= spool.pop(b, 0.0)
    biggest = max(regions, key=lambda r: r["pbf_size"])
    total = sum(r["pbf_size"] for r in regions)
    print(f"{len(regions)} regions, {total / GB:.1f} GB to download; largest {biggest['id']} "
          f"{biggest['pbf_size'] / GB:.2f} GB")
    print(f"simulated peak spool, one region at a time: {peak / GB:.1f} GB (after {peak_after})")
    print(f"RAM for the largest region: about "
          f"{(biggest['pbf_size'] * RAM_PER_PBF_BYTE + RAM_BASE_BYTES) / GB:.1f} GB")


# ------------------------------------------------------------------------------- spool files

def spool_dir(scratch: str, box: int) -> str:
    return os.path.join(scratch, "spool", f"box_{box:06d}")


def spool_files(scratch: str, box: int) -> list:
    d = spool_dir(scratch, box)
    return [os.path.join(d, n) for n in os.listdir(d) if n.endswith(".z")] if os.path.isdir(d) else []


def region_of_spool_file(path: str) -> str:
    return os.path.basename(path)[:-2]


def _write_spool(scratch: str, code: str, box: int, arena, tables):
    parts = []
    for kind, addresses in enumerate((tables.nodes[box], tables.ways[box], tables.env[box])):
        for address in addresses:
            row = arena.raw(address)
            parts.append(struct.pack("<BI", kind, len(row)))
            parts.append(row)
    d = spool_dir(scratch, box)
    os.makedirs(d, exist_ok=True)
    path = os.path.join(d, f"{code}.z")
    with open(path + ".tmp", "wb") as f:
        f.write(zlib.compress(b"".join(parts), 1))
    os.replace(path + ".tmp", path)


def _read_spool(path: str):
    """Rows of one spool file as (nodes, ways, env) lists."""
    with open(path, "rb") as f:
        data = zlib.decompress(f.read())
    rows = ([], [], [])
    pos = 0
    while pos < len(data):
        kind, n = struct.unpack_from("<BI", data, pos)
        pos += 5
        rows[kind].append(msgpack.unpackb(data[pos:pos + n], raw=False))
        pos += n
    return rows


# ------------------------------------------------------------------------------- workers

def _region_worker(region: dict, scratch: str, boxes: list):
    """Download, read, spool, delete. Runs in a process of its own, so its memory all goes back."""
    code = v1.region_code(region)
    pbf_dir = os.path.join(scratch, "pbf", code)
    try:
        tree = planet.rebuild_tree(boxes)
        pbf = planet.download_planet(region["pbf_url"], pbf_dir)
        t0 = time.time()
        arena, tables, stats = planet.read_planet(pbf, tree, boxes, CHUNK_BYTES, "flex_mem")
        touched = [i for i in range(len(boxes))
                   if len(tables.nodes[i]) or len(tables.ways[i]) or len(tables.env[i])]
        for box in touched:
            _write_spool(scratch, code, box, arena, tables)
        peak = _peak_rss()
        log(f"{region['id']}: {stats['nodes_kept']:,} nodes, {stats['ways_kept']:,} ways, "
            f"{stats['env_ways'] + stats['env_members'] + stats['env_nodes']:,} env, {len(touched)} boxes, "
            f"{time.time() - t0:,.0f}s, peak {peak / GB:.2f} GB")
        return region["id"], touched, peak, None
    except Exception as e:
        return region["id"], None, 0, f"{type(e).__name__}: {e}"
    finally:
        gc.collect()
        shutil.rmtree(pbf_dir, ignore_errors=True)


def _peak_rss() -> int:
    try:
        import psutil
        info = psutil.Process().memory_info()
        return getattr(info, "peak_wset", info.rss)
    except ImportError:
        return 0


def _box_worker(box: int, scratch: str, out_dir: str, reopen: bool):
    """Writes one box from its spool (on top of the finished box when reopening), moves its .gz to the
    output drive, and only then deletes the spool files it read. Any failure leaves those in place."""
    try:
        work = os.path.join(scratch, "boxes")
        os.makedirs(work, exist_ok=True)
        path = v1.box_db_path(work, box)
        files = spool_files(scratch, box)
        final = v1.box_db_path(out_dir, box) + ".gz"
        if reopen:
            with gzip.open(final) as src, open(path, "wb") as dst:
                shutil.copyfileobj(src, dst, length=1 << 20)
            conn = v1.open_box_db(path)
            # Intersections come back from every way, including the ones arriving now.
            conn.execute("DELETE FROM nodes WHERE cat_id=?", (v1.CATEGORY_INTERSECTION,))
        else:
            conn = v1.create_box_db(path)
            conn.executescript(planet.ENV_SCHEMA)
            conn.execute("BEGIN")
        for spool in files:
            nodes, ways, env = _read_spool(spool)
            planet.fill_box(conn, nodes, ways, env)
        env_count = conn.execute("SELECT COUNT(*) FROM env_features").fetchone()[0]
        conn.commit()
        conn.close()

        stats = v1._finalize_box(box, work)
        stats["env_features"] = env_count
        tmp = final + ".tmp"
        shutil.copyfile(path + ".gz", tmp)
        os.replace(tmp, final)
        os.remove(path + ".gz")
        for spool in files:
            os.remove(spool)
        return box, stats, None
    except Exception as e:
        return box, None, f"{type(e).__name__}: {e}"


# ------------------------------------------------------------------------------- the run

class Run:
    def __init__(self, regions, boxes, scratch, out_dir, args):
        self.regions = regions
        self.boxes = boxes
        self.scratch = scratch
        self.out_dir = out_dir
        self.args = args
        self.state_path = os.path.join(scratch, STATE_NAME)
        state = v1.read_json(self.state_path) if os.path.exists(self.state_path) else {}
        self.regions_done = set(state.get("regions_done", []))
        self.results = {int(k): v for k, v in state.get("boxes", {}).items()}
        self.touched = set(state.get("touched", []))  # boxes with data so far

        self.by_id = {r["id"]: r for r in regions}
        self.overlaps = boxes_per_region(regions, boxes)
        self.waiting = {}
        for rid, overlapped in self.overlaps.items():
            for b in overlapped:
                self.waiting.setdefault(b, set()).add(rid)
        for b in self.waiting:
            self.waiting[b] -= self.regions_done

        self._drop_unfinished_spools()
        self.dirty = {b for b in range(len(boxes)) if spool_files(scratch, b)}
        self.dirty.update(b for b in self.touched if b not in self.results)
        self.pending = [r for r in regions if r["id"] not in self.regions_done]
        self.attempts = {}
        self.failed_regions = {}
        self.failed_boxes = {}

    def _drop_unfinished_spools(self):
        codes = {v1.region_code(self.by_id[r]) for r in self.regions_done if r in self.by_id}
        root = os.path.join(self.scratch, "spool")
        if not os.path.isdir(root):
            return
        for d in os.listdir(root):
            for name in os.listdir(os.path.join(root, d)):
                if name.endswith(".tmp") or region_of_spool_file(name) not in codes:
                    os.remove(os.path.join(root, d, name))

    def save(self):
        v1.write_json(self.state_path, {"regions_done": sorted(self.regions_done),
                                        "touched": sorted(self.touched),
                                        "boxes": {str(k): v for k, v in self.results.items()}})

    def free_bytes(self) -> int:
        return shutil.disk_usage(self.scratch).free

    def execute(self):
        args = self.args
        ctx = multiprocessing.get_context("spawn")
        memory_budget = args.memory_budget_gb * GB
        min_free = args.min_free_gb * GB
        running_regions = {}
        running_boxes = {}
        memory_used = disk_reserved = 0
        t0 = time.time()
        total_bytes = sum(r["pbf_size"] for r in self.pending)
        done_bytes = 0
        peak_scratch = 0

        with ProcessPoolExecutor(max_workers=args.region_workers, mp_context=ctx,
                                 max_tasks_per_child=1) as region_pool, \
                ProcessPoolExecutor(max_workers=args.box_workers, mp_context=ctx) as box_pool:
            while True:
                # Boxes first: writing one is what gives disk back.
                busy = set(running_boxes.values())
                for b in sorted(self.dirty - busy):
                    if len(running_boxes) >= args.box_workers * 2:
                        break
                    if self.waiting.get(b):
                        continue
                    reopen = b in self.results and os.path.exists(v1.box_db_path(self.out_dir, b) + ".gz")
                    running_boxes[box_pool.submit(_box_worker, b, self.scratch, self.out_dir, reopen)] = b

                # A region starts only if its RAM and disk fit. When nothing else is running a big one
                # goes anyway on RAM, because waiting would not free any more.
                free = self.free_bytes()
                while self.pending and len(running_regions) < args.region_workers:
                    region = self.pending[0]
                    need_ram = region["pbf_size"] * RAM_PER_PBF_BYTE + RAM_BASE_BYTES
                    need_disk = region["pbf_size"] * (1 + SPOOL_PER_PBF_BYTE)
                    if running_regions and memory_used + need_ram > memory_budget:
                        break
                    if free - disk_reserved - need_disk < min_free:
                        break
                    self.pending.pop(0)
                    future = region_pool.submit(_region_worker, region, self.scratch, self.boxes)
                    running_regions[future] = (region, need_ram, need_disk)
                    memory_used += need_ram
                    disk_reserved += need_disk

                if not running_regions and not running_boxes:
                    if self.pending:
                        raise RuntimeError(
                            f"only {free / GB:.1f} GB free on the scratch drive and nothing left to "
                            f"finish that would free more; region {self.pending[0]['id']} needs more. "
                            f"Free some space and run again: it continues from here.")
                    break

                finished, _ = wait(set(running_regions) | set(running_boxes), return_when=FIRST_COMPLETED)
                for future in finished:
                    if future in running_boxes:
                        b = running_boxes.pop(future)
                        box, stats, error = future.result()
                        if error is not None:
                            self.failed_boxes[box] = error
                            self.dirty.discard(box)
                            log(f"FAILED box_{box:06d}: {error}")
                            continue
                        self.results[box] = stats
                        if not spool_files(self.scratch, box):
                            self.dirty.discard(box)
                    else:
                        region, need_ram, need_disk = running_regions.pop(future)
                        memory_used -= need_ram
                        disk_reserved -= need_disk
                        rid, touched, peak, error = future.result()
                        if error is not None:
                            self.attempts[rid] = self.attempts.get(rid, 0) + 1
                            log(f"FAILED {rid} (attempt {self.attempts[rid]}): {error}")
                            if self.attempts[rid] < MAX_REGION_ATTEMPTS:
                                self.pending.insert(0, region)
                            else:
                                self.failed_regions[rid] = error
                            continue
                        self.regions_done.add(rid)
                        done_bytes += region["pbf_size"]
                        self.touched.update(touched)
                        self.dirty.update(touched)
                        for b in self.overlaps[rid]:
                            self.waiting[b].discard(rid)
                self.save()
                used = _dir_size(self.scratch)
                peak_scratch = max(peak_scratch, used)
                elapsed = time.time() - t0
                left = (total_bytes - done_bytes) / max(1, done_bytes) * elapsed
                log(f"{len(self.regions_done)}/{len(self.regions)} regions, {len(self.results)} boxes, "
                    f"scratch {used / GB:.1f} GB (peak {peak_scratch / GB:.1f}), free {self.free_bytes() / GB:.0f} GB, "
                    f"{elapsed / 3600:.1f} h, ~{left / 3600:.1f} h left")

            # Whatever overlapped no region and got no data: ocean boxes in a full run.
            if not args.skip_empty and not self.failed_regions:
                empty = [b for b in range(len(self.boxes)) if b not in self.results]
                running = [box_pool.submit(_box_worker, b, self.scratch, self.out_dir, False) for b in empty]
                for future in running:
                    box, stats, error = future.result()
                    if error is not None:
                        self.failed_boxes[box] = error
                    else:
                        self.results[box] = stats
                self.save()
        log(f"scratch peak {peak_scratch / GB:.1f} GB; took {(time.time() - t0) / 3600:.2f} h")


def _dir_size(path: str) -> int:
    total = 0
    for root, _, names in os.walk(path):
        for name in names:
            try:
                total += os.path.getsize(os.path.join(root, name))
            except OSError:
                pass
    return total


def main():
    ap = argparse.ArgumentParser(description="Build all box dbs region by region, with little disk.")
    ap.add_argument("--out-dir", help="Where finished .db.gz files go; speed barely matters.")
    ap.add_argument("--scratch-dir", default=v1.DEFAULT_SCRATCH_DIR, help="Fast drive for temporary files.")
    ap.add_argument("--boxes-manifest", default=planet.BOXES_MANIFEST_URL, help="Source of the box bounds.")
    ap.add_argument("--region-workers", type=int, default=DEFAULT_REGION_WORKERS)
    ap.add_argument("--box-workers", type=int, default=DEFAULT_BOX_WORKERS)
    ap.add_argument("--memory-budget-gb", type=float, default=DEFAULT_MEMORY_BUDGET_GB,
                    help="Estimated RAM all region workers together may use.")
    ap.add_argument("--min-free-gb", type=float, default=DEFAULT_MIN_FREE_GB,
                    help="No new region starts if the scratch drive would drop below this.")
    ap.add_argument("--only", help="Testing: only these region ids, comma-separated.")
    ap.add_argument("--skip-empty", action="store_true", help="Testing: only write boxes with data.")
    ap.add_argument("--plan", action="store_true", help="Print expected needs and stop.")
    args = ap.parse_args()

    only = {s.strip() for s in args.only.split(",")} if args.only else set()
    boxes = planet.load_boxes(args.boxes_manifest)
    planet.rebuild_tree(boxes)
    os.makedirs(args.scratch_dir, exist_ok=True)
    regions = load_regions(args.scratch_dir, only)
    if args.plan:
        plan(regions, boxes)
        return
    if not args.out_dir:
        ap.error("--out-dir is required")
    os.makedirs(args.out_dir, exist_ok=True)
    v1.raise_file_limit(8192)

    run = Run(regions, boxes, args.scratch_dir, args.out_dir, args)
    run.execute()
    missing = planet.write_manifest(run.results, boxes, args.out_dir, "geofabrik regions", args.skip_empty)
    if run.failed_regions or run.failed_boxes or missing:
        log(f"NOT COMPLETE: {len(run.failed_regions)} regions failed {sorted(run.failed_regions)[:10]}, "
            f"{len(run.failed_boxes)} boxes failed {sorted(run.failed_boxes)[:10]}, {len(missing)} missing. "
            f"Run the same command again to continue.")
        sys.exit(1)
    log(f"done: {args.out_dir}")


if __name__ == "__main__":
    main()
