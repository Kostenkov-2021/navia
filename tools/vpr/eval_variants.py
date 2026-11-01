#!/usr/bin/env python3
"""Compare exported MegaLoc variants against the PyTorch reference on a set of images.

Reports, per ONNX file: agreement with the reference descriptor (cosine), top-1 / top-5
neighbour agreement within the image set, patch-token fidelity, and recall@1/@5 for any
query images that have ground-truth positives.

Image layout (Nordland-style, aligned by file name across seasons):
  <db_dir>/images-00001.png ...      database
  <query_dir>/images-00060.png ...   queries; positives = db frames within --tol of the same index

Usage:
  python tools/vpr/eval_variants.py --db DIR --queries DIR --onnx a.onnx b.onnx ...
"""
import argparse
import os
import re
import sys

import numpy as np
import onnxruntime as ort
import torch
from PIL import Image

sys.path.insert(0, os.path.dirname(__file__))
from export_megaloc import load  # noqa: E402


def frame_index(path):
    return int(re.search(r"(\d+)\.\w+$", path).group(1))


def load_image(path, size):
    im = Image.open(path).convert("RGB").resize((size, size), Image.BICUBIC)
    return np.asarray(im, dtype=np.float32).transpose(2, 0, 1)[None] / 255.0


def recall(q_desc, db_desc, q_idx, db_idx, tol):
    sims = q_desc @ db_desc.T
    order = np.argsort(-sims, axis=1)
    hits1 = hits5 = 0
    for i, qi in enumerate(q_idx):
        pos = {j for j, d in enumerate(db_idx) if abs(d - qi) <= tol}
        if not pos:
            continue
        hits1 += order[i, 0] in pos
        hits5 += bool(pos & set(order[i, :5]))
    n = sum(1 for qi in q_idx if any(abs(d - qi) <= tol for d in db_idx))
    return hits1 / max(n, 1), hits5 / max(n, 1), n


def neighbour_agreement(a, b, k):
    na = np.argsort(-(a @ a.T), axis=1)[:, 1:k + 1]
    nb = np.argsort(-(b @ b.T), axis=1)[:, 1:k + 1]
    return np.mean([len(set(x) & set(y)) / k for x, y in zip(na, nb)])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", required=True)
    ap.add_argument("--queries", required=True)
    ap.add_argument("--db-step", type=int, default=3)
    ap.add_argument("--tol", type=int, default=3)
    ap.add_argument("--onnx", nargs="+", required=True)
    args = ap.parse_args()

    db = sorted(os.path.join(args.db, f) for f in os.listdir(args.db))[::args.db_step]
    db_idx = [frame_index(p) for p in db]
    lo, hi = min(db_idx), max(db_idx)
    qs = sorted(os.path.join(args.queries, f) for f in os.listdir(args.queries)
                if lo <= frame_index(f) <= hi)
    q_idx = [frame_index(p) for p in qs]
    paths = db + qs
    print(f"{len(db)} db images, {len(qs)} queries")

    # PyTorch reference at 322: full (with final linear) and salad (without).
    ref = {"full": [], "salad": [], "patches": []}
    for head in ("full", "salad"):
        model = load(head)
        with torch.no_grad():
            for p in paths:
                g, pt = model(torch.from_numpy(load_image(p, 322)))
                ref[head].append(g.numpy()[0])
                if head == "salad":
                    ref["patches"].append(pt.numpy()[0])
        del model
    for k in ("full", "salad"):
        ref[k] = np.stack(ref[k])
        r1, r5, n = recall(ref[k][len(db):], ref[k][:len(db)], q_idx, db_idx, args.tol)
        print(f"torch {k:5s}: R@1 {r1:.2f} R@5 {r5:.2f} (n={n})")
    print(f"torch salad vs full: top-5 neighbour agreement "
          f"{neighbour_agreement(ref['salad'], ref['full'], 5):.3f}")

    def run_onnx(path, size):
        sess = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
        gs, ps = [], []
        for p in paths:
            g, pt = sess.run(None, {"image": load_image(p, size)})
            gs.append(g[0])
            ps.append(pt[0])
        return np.stack(gs), ps

    # Fidelity reference per (head, size): the torch model at 322, else the fp32 ONNX export of
    # the same head and size sitting next to the quantized file (it matches torch exactly).
    refs = {("full", 322): (ref["full"], ref["patches"]), ("salad", 322): (ref["salad"], ref["patches"])}
    for path in args.onnx:
        name = os.path.basename(path)
        size = int(re.search(r"_(\d+)(_int8|_w\d|_dqf?|_hq)?\.onnx$", name).group(1))
        head = "full" if "_full_" in name else "salad"
        gs, ps = run_onnx(path, size)
        r1, r5, n = recall(gs[len(db):], gs[:len(db)], q_idx, db_idx, args.tol)
        line = f"{name}: R@1 {r1:.2f} R@5 {r5:.2f}"
        fp32 = os.path.join(os.path.dirname(path), f"megaloc_{head}_{size}.onnx")
        if (head, size) not in refs and os.path.exists(fp32) and os.path.abspath(fp32) != os.path.abspath(path):
            refs[(head, size)] = run_onnx(fp32, size)
        if (head, size) in refs:
            rg, rp = refs[(head, size)]
            cos = np.sum(gs * rg, axis=1)
            pcos = np.mean([np.mean(np.sum(a * b, axis=-1)) for a, b in zip(ps, rp)])
            line += (f" | cos vs fp32 min {cos.min():.4f} mean {cos.mean():.4f}"
                     f" | top1 agree {neighbour_agreement(gs, rg, 1):.3f}"
                     f" top5 {neighbour_agreement(gs, rg, 5):.3f}"
                     f" | patch cos {pcos:.4f}")
        print(line)


if __name__ == "__main__":
    main()
