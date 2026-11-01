#!/usr/bin/env python3
"""Quantize an exported MegaLoc ONNX model.

--mode dynamic   int8 weights AND activations (MatMulInteger) — the fast path on ARM CPUs.
                 Quantizing everything wrecks DINOv2 (descriptors ~0.2 cosine against fp32):
                 the activation x activation MatMuls inside attention (Q.K^T, attn.V) and the
                 aggregator are kept in fp32; --keep-fc2 also keeps every MLP fc2 in fp32, the
                 layer that produces DINOv2's few huge-norm outlier activations.
--mode hybrid    (default) dynamic int8 with fc2 kept out, then fc2 stored weight-only int8.
--mode w8 / w4   weight-only (MatMulNBits): accurate, smaller, but slower than dynamic int8 on
                 the phones we measured, because activations stay fp32.

Usage:
  python tools/vpr/quantize.py tools/out/vpr/megaloc_salad_322.onnx --mode dynamic
  python tools/vpr/quantize.py tools/out/vpr/megaloc_salad_322.onnx --mode w8
"""
import argparse
import os

import onnx


def dynamic(path, out, keep_fc2):
    from onnxruntime.quantization import QuantType, quantize_dynamic
    graph = onnx.load(path, load_external_data=False).graph
    exclude = []
    for n in graph.node:
        if n.op_type not in ("MatMul", "Gemm"):
            continue
        if (n.name.endswith("/attn/MatMul") or n.name.endswith("/attn/MatMul_1")
                or n.name.startswith("/agg") or (keep_fc2 and "/mlp/fc2/" in n.name)):
            exclude.append(n.name)
    quantize_dynamic(path, out, weight_type=QuantType.QInt8, per_channel=True,
                     op_types_to_quantize=["MatMul", "Gemm"], nodes_to_exclude=exclude)


def weight_only(path, out, bits, block):
    from onnxruntime.quantization.matmul_nbits_quantizer import DefaultWeightOnlyQuantConfig, MatMulNBitsQuantizer
    config = DefaultWeightOnlyQuantConfig(block_size=block, is_symmetric=True, bits=bits)
    quant = MatMulNBitsQuantizer(onnx.load(path), algo_config=config)
    quant.process()
    quant.model.save_model_to_file(out, use_external_data_format=False)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("model")
    ap.add_argument("--mode", choices=["hybrid", "dynamic", "w8", "w4"], default="hybrid")
    ap.add_argument("--keep-fc2", action="store_true")
    ap.add_argument("--block", type=int, default=32)
    args = ap.parse_args()

    if args.mode == "dynamic":
        out = args.model.replace(".onnx", "_dq" + ("f" if args.keep_fc2 else "") + ".onnx")
        dynamic(args.model, out, args.keep_fc2)
    elif args.mode == "hybrid":
        # Dynamic int8 everywhere except fc2, then fc2's weights (the only fp32 weight MatMuls
        # left) stored as weight-only int8, so the file isn't ~85 MB larger than plain dynamic.
        tmp = args.model.replace(".onnx", "_dqf_tmp.onnx")
        dynamic(args.model, tmp, keep_fc2=True)
        out = args.model.replace(".onnx", "_hq.onnx")
        weight_only(tmp, out, 8, args.block)
        os.remove(tmp)
    else:
        out = args.model.replace(".onnx", f"_{args.mode}.onnx")
        weight_only(args.model, out, int(args.mode[1]), args.block)
    print("wrote", out, round(os.path.getsize(out) / 1e6, 1), "MB")


if __name__ == "__main__":
    main()
