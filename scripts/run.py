"""Foreground supervisor: only terminates processes it created; localhost only."""
import argparse
import fcntl
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser()
p.add_argument('track', choices=['qwen', 'native'])
p.add_argument('--native-gpu', default='1')
a = p.parse_args()
children = []
registry = ROOT/'.runtime/supervisor.json'
logfiles = []
runtime = ROOT/'.runtime'
runtime.mkdir(exist_ok=True)
allocation = open(runtime/'gpu.lock', 'w')
try:
    fcntl.flock(allocation, fcntl.LOCK_EX | fcntl.LOCK_NB)
except BlockingIOError:
    sys.exit('Another Live track owns the GPUs. Stop it before switching tracks.')
probe = subprocess.check_output(['nvidia-smi', '--query-gpu=index,name,memory.free', '--format=csv,noheader,nounits'], text=True)
print('GPU preflight:\n'+probe, flush=True)
free = {line.split(',')[0].strip(): int(line.split(',')[-1].strip()) for line in probe.splitlines()}
needed = {a.native_gpu: 22000} if a.track == 'native' else {'0': 4000, '1': 15000}
for gpu, minimum in needed.items():
    if free.get(gpu, 0) < minimum:
        sys.exit(f'GPU {gpu} requires at least {minimum} MiB free; no other processes will be stopped.')

registry.write_text(json.dumps({'pid':os.getpid(),'track':a.track,'start_ticks':Path('/proc/self/stat').read_text().rsplit(') ',1)[1].split()[19]}))

def start(name, cmd, gpu=None, cwd=ROOT):
    env = os.environ.copy()
    env.setdefault('OMP_NUM_THREADS', '4')
    env.setdefault('MKL_NUM_THREADS', '4')
    env.setdefault('PYTORCH_CUDA_ALLOC_CONF', 'expandable_segments:True')
    if gpu is not None:
        env['CUDA_VISIBLE_DEVICES'] = gpu
    env['PYTHONPATH'] = str(cwd)
    logfile = open(runtime/f'{name}.log', 'a')
    logfiles.append(logfile)
    proc = subprocess.Popen([str(x) for x in cmd], cwd=cwd, env=env, stdout=logfile, stderr=subprocess.STDOUT, start_new_session=True)
    children.append(proc)
    print(f'{name}: pid={proc.pid}, log={logfile.name}', flush=True)
    return proc

def wait_ready(url, timeout=240):
    deadline = time.monotonic()+timeout
    while time.monotonic()<deadline:
        if any(p.poll() is not None for p in children):
            raise RuntimeError('a child exited; inspect .runtime/*.log')
        try:
            with urllib.request.urlopen(url, timeout=2) as response:
                if response.status == 200:
                    body = json.load(response)
                    if body.get('model_loaded', True) and body.get('ready', True):
                        return
        except Exception:
            time.sleep(1)
    raise TimeoutError(url)

def stop(*_):
    raise KeyboardInterrupt

signal.signal(signal.SIGTERM, stop)
try:
    if a.track == 'qwen':
        for role, port, gpu in [('asr',8101,'0'), ('tts',8103,'1'), ('vlm',8102,'1')]:
            start(role, [ROOT/f'.venv-{role}/bin/python', ROOT/'scripts/model_worker.py', role, '--port', str(port)], gpu)
        for port in [8101,8102,8103]:
            wait_ready(f'http://127.0.0.1:{port}/health')
        start('gateway', [ROOT/'.venv/bin/python','-m','uvicorn','live.app:app','--host','127.0.0.1','--port','8080', '--ws-max-size', '500000', '--ws-max-queue', '8'])
        wait_ready('http://127.0.0.1:8080/health')
        print('READY http://localhost:8080', flush=True)
    else:
        upstream = ROOT/'.upstream/minicpm-demo'
        config = json.loads((upstream/'config.example.json').read_text())
        config['model']['model_path'] = str((ROOT/'models/minicpm').resolve())
        config['model']['attn_implementation'] = 'sdpa'
        config['service']['data_dir'] = str(runtime/'native-data')
        config['recording'] = {'enabled': False}
        (upstream/'config.json').write_text(json.dumps(config, indent=2))
        python = ROOT/'.venv-native/bin/python'
        start('native-backend', [python,ROOT/'scripts/native_backend.py','--host','127.0.0.1','--port','22500','--gpu-id','0'], a.native_gpu, upstream)
        wait_ready('http://127.0.0.1:22500/health', 360)
        start('native-worker', [python,'worker.py','--host','127.0.0.1','--worker-index','0','--gpu-id','0','--backend-server-url','http://127.0.0.1:22500'], cwd=upstream)
        wait_ready('http://127.0.0.1:22400/health')
        start('native-gateway', [python,'gateway.py','--host','127.0.0.1','--port','8006','--internal-port','8007','--http'], cwd=upstream)
        wait_ready('http://127.0.0.1:8006/health')
        req=urllib.request.Request('http://127.0.0.1:8007/internal/workers/live-local', method='PUT',
                data=json.dumps({'endpoint':'127.0.0.1:22400','gpu_group':f'gpu-{a.native_gpu}'}).encode(), headers={'content-type':'application/json'})
        with urllib.request.urlopen(req, timeout=10) as response:
            print(response.read().decode(), flush=True)
        print('READY http://localhost:8006 — choose Omnimodal Full-Duplex', flush=True)
    while True:
        time.sleep(1)
        if any(p.poll() is not None for p in children):
            raise RuntimeError('a child exited; inspect .runtime/*.log')
except KeyboardInterrupt:
    print('Stopping owned processes.', flush=True)
finally:
    for process in children:
        if process.poll() is None:
            os.killpg(process.pid, signal.SIGTERM)
    for process in children:
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()
    for logfile in logfiles:
        logfile.close()
    if registry.exists() and json.loads(registry.read_text()).get('pid') == os.getpid():
        registry.unlink()
