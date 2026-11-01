#!/bin/bash
# Runs on a rented vast.ai GPU box from /workspace/vpr (contains tools/vpr/*.py):
#   nohup bash tools/vpr/vast_run.sh > /workspace/run.log 2>&1 &
# Downloads google-landmarks-v2-mini, exports + quantizes the MegaLoc variants, runs
# eval_robustness.py. Results land in /workspace/vpr/out/robust.
set -e
cd /workspace/vpr
pip install -q onnx onnxruntime onnx_ir pyarrow huggingface_hub safetensors pillow
python -c "import torch; print(torch.__version__, torch.cuda.get_device_name(0))"; nproc

mkdir -p data out/vpr
for f in test-00000-of-00002 test-00001-of-00002 train-00000-of-00005 train-00001-of-00005 \
         train-00002-of-00005 train-00003-of-00005 train-00004-of-00005; do
  [ -f data/$f.parquet ] || curl -sL -o data/$f.parquet \
    "https://huggingface.co/datasets/zguo0525/google-landmarks-v2-mini/resolve/main/data/$f.parquet" &
done
for head in full salad; do for size in 322 224; do
  python tools/vpr/export_megaloc.py --head $head --size $size --out out/vpr 2>&1 | grep wrote
  python tools/vpr/quantize.py out/vpr/megaloc_${head}_${size}.onnx --mode hybrid 2>&1 | grep wrote
done; done
wait
ls -la data
echo EXPORT_DONE

ORT_THREADS=${ORT_THREADS:-$(python -c "import os;q,p=open('/sys/fs/cgroup/cpu.max').read().split();print(int(q)//int(p) if q!='max' else os.cpu_count())" 2>/dev/null || nproc)} \
  python tools/vpr/eval_robustness.py --data data --out out/robust \
  --onnx out/vpr/megaloc_full_322_hq.onnx out/vpr/megaloc_full_224_hq.onnx \
         out/vpr/megaloc_salad_322_hq.onnx out/vpr/megaloc_salad_224_hq.onnx 2>&1 | grep -vi warn
echo ALL_DONE
