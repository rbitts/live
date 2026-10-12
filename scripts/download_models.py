"""Download immutable model snapshots, excluding unused alternate checkpoints."""
import argparse
import json
from pathlib import Path
from huggingface_hub import snapshot_download
ROOT = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser()
p.add_argument('models', nargs='*', default=['minicpm', 'asr', 'vlm', 'tts'])
a = p.parse_args()
manifest = json.loads((ROOT / 'configs/models.json').read_text())
for key in a.models:
    entry = manifest[key]
    path = snapshot_download(entry['repo'], revision=entry['revision'],
                             ignore_patterns=['*.gguf', '*.mp4'])
    target = ROOT / 'models' / key
    target.parent.mkdir(exist_ok=True)
    if target.is_symlink():
        target.unlink()
    target.symlink_to(path, target_is_directory=True)
    print(key, path, flush=True)
