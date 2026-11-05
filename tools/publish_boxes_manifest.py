"""Stamp a built manifest with its map version and publish it for the app.

Run this only after every box of that build is reachable through the server's redirect: phones that see
the new version will offer to update, and box file names do not change between versions.

    python publish_boxes_manifest.py --version 2

Refuses a manifest whose rectangles differ from the bundled one, because box boundaries never change.
Steps: bundled asset, then server files, then updates.json last, which is what phones look at.
"""
import argparse, gzip, json, os, subprocess, sys, tempfile, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ASSET = os.path.normpath(os.path.join(HERE, "..", "app", "src", "main", "assets", "boxes_manifest.json"))
KEY = os.path.expanduser("~/.ssh/wikipond")
SCP = ["-P", "2200", "-i", KEY]
HOST = "wikipond@wikiponder.com"
REMOTE = "public_html/navia"
PUBLIC = "https://wikiponder.com/navia"
RECT = ("index", "lat_min", "lat_max", "lon_min", "lon_max")


def ssh(command):
    args = ["ssh", "-p", "2200", "-i", KEY, HOST, command]
    return subprocess.run(args, check=True, capture_output=True, text=True).stdout


def fetch(url):
    request = urllib.request.Request(url, headers={"User-Agent": "TouchMap-Android"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default="F:/navia_boxes/boxes_manifest.json")
    ap.add_argument("--version", type=int, required=True)
    args = ap.parse_args()

    manifest = json.load(open(args.src, encoding="utf-8"))
    bundled = json.load(open(ASSET, encoding="utf-8"))
    rects = lambda m: [[b[k] for k in RECT] for b in m["boxes"]]
    if rects(manifest) != rects(bundled):
        sys.exit("box rectangles differ from the bundled manifest: refusing to publish")
    if args.version < bundled.get("map_version", 1):
        sys.exit(f"version {args.version} is older than the bundled {bundled['map_version']}")

    manifest["map_version"] = args.version
    body = json.dumps(manifest, separators=(",", ":")).encode()
    packed = gzip.compress(body, 9, mtime=0)
    with open(ASSET, "wb") as f:
        f.write(body)
    print(f"manifest {len(body):,} bytes, gz {len(packed):,} bytes, version {args.version}")

    with tempfile.TemporaryDirectory() as tmp:
        for name, data in (("boxes_manifest.json", body), ("boxes_manifest.json.gz", packed)):
            path = os.path.join(tmp, name)
            with open(path, "wb") as f:
                f.write(data)
            subprocess.run(["scp", "-q", *SCP, path, f"{HOST}:{REMOTE}/global_boxes/{name}.new"], check=True)
            ssh(f"cd {REMOTE}/global_boxes && chmod 644 {name}.new && mv {name}.new {name}")

    if fetch(f"{PUBLIC}/global_boxes/boxes_manifest.json.gz") != packed:
        sys.exit("the public .gz does not match what was uploaded; updates.json left unchanged")

    updates = json.loads(fetch(f"{PUBLIC}/updates.json"))
    updates["boxes"] = args.version
    with tempfile.TemporaryDirectory() as tmp:
        path = os.path.join(tmp, "updates.json")
        with open(path, "w") as f:
            json.dump(updates, f)
        subprocess.run(["scp", "-q", *SCP, path, f"{HOST}:{REMOTE}/updates.json.new"], check=True)
        ssh(f"cd {REMOTE} && chmod 644 updates.json.new && mv updates.json.new updates.json")
    print("updates.json:", fetch(f"{PUBLIC}/updates.json").decode())


if __name__ == "__main__":
    main()
