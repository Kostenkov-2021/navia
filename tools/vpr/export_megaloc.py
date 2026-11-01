#!/usr/bin/env python3
"""Export MegaLoc (MIT, DINOv2 ViT-B/14 + SALAD-style aggregation) to ONNX for on-device
visual place recognition.

The exported graph takes one RGB image, values 0..1, shape [1, 3, S, S] (ImageNet normalization
is done inside the graph), and returns two outputs from a single backbone pass:

  global  [1, D]           L2-normalized place descriptor, compared by dot product.
  patches [1, S/14, S/14, 768]  L2-normalized DINOv2 patch tokens, used to find *where* in the
                           frame a saved place is (left/right) and how big it looks (closer/farther).

--head full   keeps MegaLoc's final 16640->8448 linear layer (140M params, the bulk of the model).
--head salad  drops it and returns the 16640-dim aggregator output instead (~88M params total).

Usage:
  python tools/vpr/export_megaloc.py --size 322 --head salad --out tools/out/vpr
  python tools/vpr/export_megaloc.py --size 322 --head salad --out tools/out/vpr --quantize
"""
import argparse
import os

import torch
import torch.nn as nn
import torch.nn.functional as F


class OnDeviceMegaLoc(nn.Module):
    def __init__(self, megaloc, head):
        super().__init__()
        self.backbone = megaloc.backbone
        self.agg = megaloc.aggregator.agg
        self.linear = megaloc.aggregator.linear if head == "full" else None
        self.register_buffer("mean", torch.tensor([0.485, 0.456, 0.406]).view(1, 3, 1, 1))
        self.register_buffer("std", torch.tensor([0.229, 0.224, 0.225]).view(1, 3, 1, 1))

    def forward(self, image):
        x = (image - self.mean) / self.std
        feats, cls = self.backbone(x)
        g = self.agg((feats, cls))
        if self.linear is not None:
            g = self.linear(g)
        g = F.normalize(g, p=2, dim=-1)
        patches = F.normalize(feats.permute(0, 2, 3, 1), p=2, dim=-1)
        return g, patches


def load(head):
    megaloc = torch.hub.load("gmberton/MegaLoc", "get_trained_model", trust_repo=True, verbose=False)
    return OnDeviceMegaLoc(megaloc.eval(), head).eval()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--size", type=int, default=322, help="square input side, multiple of 14")
    ap.add_argument("--head", choices=["full", "salad"], default="salad")
    ap.add_argument("--out", default="tools/out/vpr")
    ap.add_argument("--quantize", action="store_true", help="also write a dynamic int8 copy")
    args = ap.parse_args()
    assert args.size % 14 == 0

    os.makedirs(args.out, exist_ok=True)
    model = load(args.head)
    name = f"megaloc_{args.head}_{args.size}"
    path = os.path.join(args.out, name + ".onnx")
    dummy = torch.rand(1, 3, args.size, args.size)
    with torch.no_grad():
        torch.onnx.export(model, (dummy,), path, input_names=["image"],
                          output_names=["global", "patches"], opset_version=17, dynamo=False)
    print("wrote", path, round(os.path.getsize(path) / 1e6, 1), "MB")

    if args.quantize:
        from onnxruntime.quantization import QuantType, quantize_dynamic
        qpath = os.path.join(args.out, name + "_int8.onnx")
        quantize_dynamic(path, qpath, weight_type=QuantType.QInt8,
                         op_types_to_quantize=["MatMul", "Gemm"])
        print("wrote", qpath, round(os.path.getsize(qpath) / 1e6, 1), "MB")


if __name__ == "__main__":
    main()
