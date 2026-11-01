#!/usr/bin/env python3
"""Robustness of the place descriptors: is a building's descriptor under different lighting,
noise, a shifted / partial view or an occlusion still much closer to its own enrolled descriptor
than to every other building's?

Data: google-landmarks-v2-mini (Hugging Face, real photos, several per landmark).
  enrolled  one photo per landmark, the central window (x 25-75 %, y 10-90 %) = "what the user saved"
  synthetic --synthetic landmarks x 10 variants of the enrolled window (see VARIANTS)
  real      a *different* photo of the same landmark (training split: other day, camera, often another
            viewpoint), full frame, against full-frame enrolled photos — harder than the app's case
All enrolled landmarks (distinct places) are the distractors. Reported per model and per variant:
top-1 accuracy, how often the true building beats the closest other building (margin > 0),
median similarity of the true match vs. the closest other building, and the true-match rate at a
threshold that lets through 1 % / 0.1 % of non-matching pairs.

PCA rows (pca512 etc.) apply a projection fitted on held-out training photos to the full model's
output: the same as folding PCA into MegaLoc's last layer.

Usage (GPU box; ONNX models run on the CPU):
  python tools/vpr/eval_robustness.py --data DIR --out DIR --onnx a.onnx b.onnx
"""
import os

# A rented box's container may see every host core while its CPU quota is a fraction of them;
# BLAS then starts one thread per host core and the PCA's SVD crawls. Size the pools to the quota.
def _cpu_quota():
    try:
        q, p = open("/sys/fs/cgroup/cpu.max").read().split()
        return max(1, int(q) // int(p)) if q != "max" else os.cpu_count()
    except Exception:
        return os.cpu_count()

for _v in ("OMP_NUM_THREADS", "OPENBLAS_NUM_THREADS", "MKL_NUM_THREADS"):
    os.environ.setdefault(_v, str(_cpu_quota()))

import argparse
import glob
import io
import json
import os
import sys
import time

import numpy as np
import pyarrow.parquet as pq
from PIL import Image, ImageDraw, ImageFilter

sys.path.insert(0, os.path.dirname(__file__))

VARIANTS = ["dark", "bright", "haze", "color_temp", "noise_jpeg", "night",
            "shift_quarter", "shift_half", "zoom_tilt", "occlusion"]
ENROLL_BOX = (0.25, 0.10, 0.75, 0.90)


def read_parquets(paths, limit=None):
    imgs, labels = [], []
    for p in paths:
        t = pq.read_table(p)
        for img, lab in zip(t.column("image").to_pylist(), t.column("label").to_pylist()):
            imgs.append(img["bytes"])
            labels.append(lab)
            if limit and len(imgs) >= limit:
                return imgs, labels
    return imgs, labels


def crop(im, box):
    w, h = im.size
    return im.crop((int(box[0] * w), int(box[1] * h), int(box[2] * w), int(box[3] * h)))


def photometric(arr, gain=1.0, gamma=1.0, contrast=1.0, haze=0.0, wb=(1, 1, 1), noise=0.0, rng=None):
    x = arr.astype(np.float32) / 255.0
    x = x * gain
    x = np.clip(x, 0, 1) ** gamma
    x = (x - 0.5) * contrast + 0.5
    x = x * (1 - haze) + haze * 0.85
    x = x * np.array(wb, np.float32)
    if noise:
        x = x + rng.normal(0, noise, x.shape).astype(np.float32)
    return (np.clip(x, 0, 1) * 255).astype(np.uint8)


def jpeg(im, q):
    buf = io.BytesIO()
    im.save(buf, "JPEG", quality=q)
    return Image.open(io.BytesIO(buf.getvalue())).convert("RGB")


def variant(im, name, i):
    """One augmented view of photo `im` (full frame); the enrolled view is crop(im, ENROLL_BOX)."""
    rng = np.random.default_rng(1000 + i * 17 + VARIANTS.index(name))
    side = 1 if i % 2 == 0 else -1
    base = crop(im, ENROLL_BOX)
    a = np.asarray(base)
    if name == "dark":          # about -2 EV, underexposed shadows
        return Image.fromarray(photometric(a, gain=0.25, gamma=1.15))
    if name == "bright":        # about +1.3 EV, highlights clipped
        return Image.fromarray(photometric(a, gain=2.4, gamma=0.9))
    if name == "haze":          # low contrast, washed out (fog, glare)
        return Image.fromarray(photometric(a, contrast=0.55, haze=0.35))
    if name == "color_temp":    # warm sodium light or cold overcast
        wb = (1.25, 1.0, 0.7) if side > 0 else (0.75, 0.95, 1.3)
        return Image.fromarray(photometric(a, wb=wb))
    if name == "noise_jpeg":    # sensor noise + heavy compression
        return jpeg(Image.fromarray(photometric(a, noise=0.07, rng=rng)), 25)
    if name == "night":         # dark + noise + motion/focus blur
        x = Image.fromarray(photometric(a, gain=0.18, gamma=1.1, wb=(1.15, 1.0, 0.8), noise=0.05, rng=rng))
        return x.filter(ImageFilter.GaussianBlur(1.6))
    if name == "shift_quarter": # camera window moved sideways by 1/4 of its width
        dx = 0.125 * side
        return crop(im, (ENROLL_BOX[0] + dx, ENROLL_BOX[1], ENROLL_BOX[2] + dx, ENROLL_BOX[3]))
    if name == "shift_half":    # only half of the enrolled view still in frame
        dx = 0.25 * side
        return crop(im, (ENROLL_BOX[0] + dx, ENROLL_BOX[1], ENROLL_BOX[2] + dx, ENROLL_BOX[3]))
    if name == "zoom_tilt":     # 1.6x closer, phone tilted 7 degrees
        x = crop(im, (0.344, 0.25, 0.656, 0.75)).rotate(7 * side, resample=Image.BICUBIC, expand=False)
        w, h = x.size
        return x.crop((int(0.08 * w), int(0.08 * h), int(0.92 * w), int(0.92 * h)))
    if name == "occlusion":     # a passer-by / car covering ~30 % of the view, slightly darker
        x = Image.fromarray(photometric(a, gain=0.7))
        w, h = x.size
        ow, oh = int(w * 0.5), int(h * 0.6)
        x0 = int(rng.uniform(0, w - ow))
        ImageDraw.Draw(x).rectangle([x0, h - oh, x0 + ow, h], fill=tuple(int(v) for v in rng.integers(20, 120, 3)))
        return x
    raise ValueError(name)


def keep(im):
    """Downscale a finished view (max side 400) so ~15k of them fit in RAM; the models see 322 px."""
    im = im.copy()
    im.thumbnail((400, 400), Image.BICUBIC)
    return im


def to_array(im, size):
    return np.asarray(im.convert("RGB").resize((size, size), Image.BICUBIC), dtype=np.uint8)


def build_sets(args):
    test = sorted(glob.glob(os.path.join(args.data, "test-*.parquet")))
    train = sorted(glob.glob(os.path.join(args.data, "train-*.parquet")))
    imgs, labels = read_parquets(test)
    by_label = {}
    for k, lab in enumerate(labels):
        by_label.setdefault(lab, []).append(k)
    lands = sorted(by_label)[:args.landmarks]
    enrolled = [by_label[l][0] for l in lands]
    timgs, tlabels = read_parquets(train)
    tby_label = {}
    for k, lab in enumerate(tlabels):
        tby_label.setdefault(lab, []).append(k)

    pil = lambda k: Image.open(io.BytesIO(imgs[k])).convert("RGB")
    tpil = lambda k: Image.open(io.BytesIO(timgs[k])).convert("RGB")
    sets = {"enroll_win": [], "enroll_full": [], "synthetic": [], "real": []}
    meta = {"synthetic": [], "real": []}
    for j, k in enumerate(enrolled):
        im = pil(k)
        sets["enroll_win"].append(keep(crop(im, ENROLL_BOX)))
        sets["enroll_full"].append(keep(im))
        if j < args.synthetic:
            for v in VARIANTS:
                sets["synthetic"].append(keep(variant(im, v, j)))
                meta["synthetic"].append((j, v))
    # Real: another photo of the same landmark (training split), different day/camera/viewpoint.
    for j, l in enumerate(lands):
        for k in tby_label.get(l, [])[:args.real_per_landmark]:
            sets["real"].append(keep(tpil(k)))
            meta["real"].append((j, "real_photo"))

    # PCA fitting photos: training photos of landmarks that are not enrolled.
    used = set(lands)
    free = [k for k, l in enumerate(tlabels) if l not in used][:args.pca_images]
    sets["pca_fit"] = [keep(tpil(k)) for k in free]
    print({k: len(v) for k, v in sets.items()}, flush=True)
    return sets, meta


def torch_descriptors(sets, head, size, device, batch=64):
    import torch
    from export_megaloc import load
    model = load(head).to(device).eval()
    out = {}
    for name, ims in sets.items():
        if not ims:
            continue
        ds = []
        for s in range(0, len(ims), batch):
            x = np.stack([to_array(im, size) for im in ims[s:s + batch]]).transpose(0, 3, 1, 2)
            with torch.no_grad(), torch.autocast(device, enabled=False):
                g, _ = model(torch.from_numpy(x).float().div(255).to(device))
            ds.append(g.cpu().numpy())
        out[name] = np.concatenate(ds)
    del model
    torch.cuda.empty_cache()
    return out


def onnx_descriptors(sets, path, size, names):
    import onnxruntime as ort
    opts = ort.SessionOptions()
    opts.intra_op_num_threads = int(os.environ.get("ORT_THREADS", os.cpu_count()))
    sess = ort.InferenceSession(path, opts, providers=["CPUExecutionProvider"])
    out = {}
    for name in names:
        ds = []
        for im in sets[name]:
            g, _ = sess.run(None, {"image": to_array(im, size).transpose(2, 0, 1)[None].astype(np.float32) / 255})
            ds.append(g[0])
        out[name] = np.stack(ds)
    return out


def fit_pca(x, k):
    mean = x.mean(0)
    _, _, vt = np.linalg.svd(x - mean, full_matrices=False)
    return mean, vt[:k]


def project(d, pca):
    mean, comps = pca
    y = (d - mean) @ comps.T
    return y / np.linalg.norm(y, axis=1, keepdims=True)


def metrics(q, db, meta):
    """q: [Q, D], db: [N, D] (L2-normalized); meta: [(true db index, variant name)]."""
    sims = q @ db.T
    gt = np.array([m[0] for m in meta])
    names = np.array([m[1] for m in meta])
    pos = sims[np.arange(len(q)), gt]
    neg_all = sims.copy()
    neg_all[np.arange(len(q)), gt] = -np.inf
    hardest = neg_all.max(1)
    neg_flat = sims[np.ones_like(sims, bool) & (np.arange(sims.shape[1])[None] != gt[:, None])]
    thr1, thr01 = np.quantile(neg_flat, 0.99), np.quantile(neg_flat, 0.999)
    rows = {}
    for v in list(dict.fromkeys(names)) + ["ALL"]:
        m = np.ones(len(q), bool) if v == "ALL" else names == v
        rows[v] = {
            "n": int(m.sum()),
            "top1": float((sims[m].argmax(1) == gt[m]).mean()),
            "margin_pos": float((pos[m] > hardest[m]).mean()),
            "pos_med": float(np.median(pos[m])),
            "hardneg_med": float(np.median(hardest[m])),
            "tpr@fpr1%": float((pos[m] > thr1).mean()),
            "tpr@fpr0.1%": float((pos[m] > thr01).mean()),
        }
    rows["_neg"] = {"mean": float(neg_flat.mean()), "p99": float(thr1), "p99.9": float(thr01)}
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--landmarks", type=int, default=2000, help="enrolled distinct landmarks (distractors)")
    ap.add_argument("--synthetic", type=int, default=300, help="landmarks that get the 10 variants")
    ap.add_argument("--real-per-landmark", type=int, default=1)
    ap.add_argument("--pca-images", type=int, default=6000)
    ap.add_argument("--onnx", nargs="*", default=[])
    ap.add_argument("--device", default="cuda")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)

    t0 = time.time()
    sets, meta = build_sets(args)
    desc = {}
    for head in ("full", "salad"):
        for size in (322, 224):
            name = f"torch_{head}_{size}"
            desc[name] = torch_descriptors(sets, head, size, args.device)
            print(name, "done", round(time.time() - t0), "s", flush=True)
    for path in args.onnx:
        base = os.path.basename(path).replace(".onnx", "")
        size = 224 if "_224" in base else 322
        # Quantized models: the synthetic test only (the CPU is the slow part of the run).
        desc["onnx_" + base] = onnx_descriptors(sets, path, size, ["enroll_win", "synthetic"])
        print(base, "done", round(time.time() - t0), "s", flush=True)

    # Saved before any analysis, so a slow or failing analysis step never costs the inference.
    np.savez(os.path.join(args.out, "descriptors.npz"),
             **{f"{m}__{s}": v for m, d in desc.items() for s, v in d.items()})
    with open(os.path.join(args.out, "meta.json"), "w") as f:
        json.dump(meta, f)
    print("descriptors saved", round(time.time() - t0), "s", flush=True)

    # PCA folded into the full model's last layer, fitted on held-out training photos with the
    # fp32 model — a quantized model gets the projection of its fp32 original, as a real fold would.
    pcas = {}
    for name in [n for n in list(desc) if n.startswith("torch_full_")]:
        mean, comps = fit_pca(desc[name]["pca_fit"], 1024)
        for k in (256, 512, 1024):
            pcas[(name, k)] = (mean, comps[:k])
    for name in [n for n in list(desc) if "_full_" in n]:
        ref = "torch_full_" + ("224" if "_224" in name else "322")
        for k in (256, 512, 1024):
            desc[f"{name}_pca{k}"] = {s: project(d, pcas[(ref, k)]) for s, d in desc[name].items() if s != "pca_fit"}

    results = {}
    for name, d in desc.items():
        results[name] = {
            "dim": int(d["enroll_win"].shape[1]),
            "synthetic": metrics(d["synthetic"], d["enroll_win"], meta["synthetic"]),
        }
        if "real" in d and len(meta["real"]):
            results[name]["real"] = metrics(d["real"], d["enroll_full"], meta["real"])
    with open(os.path.join(args.out, "results.json"), "w") as f:
        json.dump({"args": vars(args), "counts": {k: len(v) for k, v in sets.items()}, "results": results}, f, indent=1)

    print(f"\n{'model':38s} {'dim':>5s} | synthetic: top1 margin>0 pos/hardneg tpr@1% tpr@0.1% | real: top1 margin>0 tpr@1%")
    for name, r in results.items():
        s = r["synthetic"]["ALL"]
        line = (f"{name:38s} {r['dim']:5d} | {s['top1']:.3f} {s['margin_pos']:.3f} {s['pos_med']:.2f}/{s['hardneg_med']:.2f}"
                f" {s['tpr@fpr1%']:.3f} {s['tpr@fpr0.1%']:.3f}")
        if "real" in r:
            re_ = r["real"]["ALL"]
            line += f" | {re_['top1']:.3f} {re_['margin_pos']:.3f} {re_['tpr@fpr1%']:.3f}"
        print(line)
    print("total", round(time.time() - t0), "s")


if __name__ == "__main__":
    main()
