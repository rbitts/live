"""Exclusive project GPU lease and measured capacity for standalone PC experiments."""
import fcntl
import json
import os
from pathlib import Path
import subprocess


def gpu_preflight(output, minimum_mib=18000):
    root = Path(__file__).resolve().parents[1]
    lease = (root/'.runtime/gpu.lock').open('a')
    try:
        fcntl.flock(lease, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        raise RuntimeError('another Live model run owns the GPU lease; run sequentially')
    doctor = subprocess.check_output([str(root/'.venv/bin/python'),str(root/'scripts/doctor.py')],text=True)
    report = json.loads(doctor)
    gpu = os.environ.get('CUDA_VISIBLE_DEVICES')
    if gpu not in ('0','1'):
        raise RuntimeError('select exactly one measured GPU using CUDA_VISIBLE_DEVICES=0 or 1')
    free = int(subprocess.check_output(['nvidia-smi',f'--id={gpu}','--query-gpu=memory.free','--format=csv,noheader,nounits'],text=True).strip())
    report['experiment_gpu'] = gpu;report['experiment_free_mib'] = free
    if free < minimum_mib:
        raise RuntimeError(f'GPU {gpu}: {free} MiB free, requires {minimum_mib}; no unrelated process will be stopped')
    (output/'doctor.json').write_text(json.dumps(report,indent=2)+'\n')
    return lease
