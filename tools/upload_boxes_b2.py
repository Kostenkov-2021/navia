"""Upload finished boxes and their manifest to a Backblaze B2 folder, then verify them.

Safe to rerun: files already in the folder with the same SHA-1 are skipped, so a stopped upload
continues where it left off. Keys are read from an env file and never printed.

    python upload_boxes_b2.py --src F:/navia_boxes --key-file C:/navia_scratch/b2.env
"""
import argparse, glob, hashlib, json, os, sys, time
from concurrent.futures import ThreadPoolExecutor, as_completed

import b2sdk.v2 as b2


def read_keys(path):
    with open(path, encoding="utf-8-sig") as f:
        env = dict(line.strip().split("=", 1) for line in f if "=" in line)
    return env["B2_APPLICATION_KEY_ID"].strip(), env["B2_APPLICATION_KEY"].strip()


def sha1_of(path):
    h = hashlib.sha1()
    with open(path, "rb") as f:
        while chunk := f.read(1 << 20):
            h.update(chunk)
    return h.hexdigest()


def remote_files(bucket, prefix):
    return {v.file_name: v for v, _ in bucket.ls(prefix, latest_only=True, recursive=True)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default="F:/navia_boxes")
    ap.add_argument("--key-file", default="C:/navia_scratch/b2.env")
    ap.add_argument("--bucket", default="wikiponder-navia")
    ap.add_argument("--prefix", default="navia/global_boxes_v2/")
    ap.add_argument("--threads", type=int, default=8)
    ap.add_argument("--hash-cache", default="C:/navia_scratch/box_sha1.json")
    args = ap.parse_args()

    files = sorted(glob.glob(f"{args.src}/box_*.db.gz")) + [f"{args.src}/boxes_manifest.json"]
    if not os.path.exists(files[-1]):
        sys.exit("no boxes_manifest.json: the build did not finish")

    cache = json.load(open(args.hash_cache)) if os.path.exists(args.hash_cache) else {}
    with ThreadPoolExecutor(args.threads) as pool:
        wanted = [f for f in files if os.path.basename(f) not in cache]
        for name, digest in zip(wanted, pool.map(sha1_of, wanted)):
            cache[os.path.basename(name)] = digest
    json.dump(cache, open(args.hash_cache, "w"))
    total = sum(os.path.getsize(f) for f in files)
    print(f"{len(files)} files, {total / 2**30:.1f} GB", flush=True)

    api = b2.B2Api(b2.InMemoryAccountInfo())
    api.authorize_account("production", *read_keys(args.key_file))
    bucket = api.get_bucket_by_name(args.bucket)
    existing = remote_files(bucket, args.prefix)

    def sent(path):
        name = args.prefix + os.path.basename(path)
        return name in existing and existing[name].content_sha1 == cache[os.path.basename(path)]

    todo = [f for f in files if not sent(f)]
    print(f"{len(files) - len(todo)} already uploaded, {len(todo)} to send", flush=True)

    def upload(path):
        for attempt in range(1, 6):
            try:
                bucket.upload_local_file(path, args.prefix + os.path.basename(path),
                                         sha1_sum=cache[os.path.basename(path)])
                return path, None
            except Exception as e:
                error = f"{type(e).__name__}: {e}"
                time.sleep(min(60, 5 * attempt))
        return path, error

    t0, done_bytes, failed = time.time(), 0, []
    with ThreadPoolExecutor(args.threads) as pool:
        futures = [pool.submit(upload, f) for f in todo]
        for n, future in enumerate(as_completed(futures), 1):
            path, error = future.result()
            if error:
                failed.append(path)
                print("FAILED", os.path.basename(path), error[:200], flush=True)
            else:
                done_bytes += os.path.getsize(path)
            if n % 100 == 0 or n == len(todo):
                rate = done_bytes / max(time.time() - t0, 1)
                left = sum(os.path.getsize(f) for f in todo) - done_bytes
                print(f"{n}/{len(todo)} files, {done_bytes / 2**30:.1f} GB, {rate * 8 / 1e6:.0f} Mbit/s, "
                      f"~{left / max(rate, 1) / 3600:.1f} h left", flush=True)

    # A fresh listing, so this reports what the bucket holds and not what the uploads returned.
    remote = remote_files(bucket, args.prefix)
    bad = [os.path.basename(f) for f in files
           if (v := remote.get(args.prefix + os.path.basename(f))) is None
           or v.content_sha1 != cache[os.path.basename(f)] or v.size != os.path.getsize(f)]
    extra = [n for n in remote if n[len(args.prefix):] not in {os.path.basename(f) for f in files}]
    print(f"verified: {len(files) - len(bad)}/{len(files)} match by name, size and SHA-1; "
          f"wrong or missing {bad[:20]}; unexpected {extra[:20]}", flush=True)
    sys.exit(1 if bad or failed else 0)


if __name__ == "__main__":
    main()
