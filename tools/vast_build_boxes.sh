#!/bin/bash
# Runs on a rented vast.ai machine, from a folder holding the two tools/*.py files this needs:
#   nohup bash vast_build_boxes.sh > /workspace/boxes.log 2>&1 &
# Result: /workspace/out/box_*.db.gz + boxes_manifest.json. Nothing is uploaded; copy them off before
# destroying the instance. If it stops while writing boxes, run it again: it continues from the
# snapshot without reading the planet again.
set -e
cd "$(dirname "$0")"
pip install -q osmium numpy msgpack psutil
free -g; df -h /workspace; nproc

mkdir -p /workspace/out /workspace/snap
# The container's real CPU share, which on vast is usually less than what nproc reports.
WORKERS=${WORKERS:-$(python3 -c "import os;q,p=open('/sys/fs/cgroup/cpu.max').read().split();print(int(q)//int(p) if q!='max' else os.cpu_count())" 2>/dev/null || nproc)}

if [ -f /workspace/snap/snapshot.json ]; then
  python3 -u build_planet_boxes_inmemory.py --out-dir /workspace/out --workers "$WORKERS" \
      --from-snapshot /workspace/snap
else
  python3 -u build_planet_boxes_inmemory.py --out-dir /workspace/out --workers "$WORKERS" \
      --download-dir /workspace --snapshot-dir /workspace/snap
fi
du -sh /workspace/out
echo ALL_DONE
