import json, shutil, subprocess, platform
from pathlib import Path
root = Path(__file__).resolve().parents[1]
def run(cmd):
    result = subprocess.run(cmd, capture_output=True, text=True)
    return {'returncode': result.returncode, 'output': result.stdout.strip() or result.stderr.strip()}
report = {'profile': 'external-dev', 'python': platform.python_version(), 'free_disk_gb': round(shutil.disk_usage(root).free / 1e9, 1),
          'gpu': run(['nvidia-smi', '--query-gpu=index,name,memory.total,memory.free,driver_version', '--format=csv,noheader']),
          'models': {k: (root/'models'/k).exists() for k in json.loads((root/'configs/models.json').read_text())}}
print(json.dumps(report, ensure_ascii=False, indent=2))
