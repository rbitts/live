"""Fetch hash-pinned offline Android VAD artifacts; never store weights in git."""
from pathlib import Path
import hashlib
import json
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def prepare(manifest_path, directory):
    manifest = json.loads(manifest_path.read_text())
    for item in manifest.values():
        target = directory / item['path']
        def digest(path):
            with path.open('rb') as source:
                return hashlib.file_digest(source, 'sha256').hexdigest()
        if target.exists() and digest(target) == item['sha256']:
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        temp = target.with_suffix(target.suffix + '.part')
        try:
            count = 0
            with urllib.request.urlopen(item['url'], timeout=60) as response, temp.open('wb') as out:
                while chunk := response.read(1024 * 1024):
                    count += len(chunk)
                    if count > item.get('bytes', 200 * 1024 * 1024):
                        raise ValueError(f"Android artifact size limit: {item['path']}")
                    out.write(chunk)
            if digest(temp) != item['sha256']:
                raise ValueError(f"Android artifact hash mismatch: {item['path']}")
            temp.replace(target)
        finally:
            temp.unlink(missing_ok=True)
    print(f'Verified {manifest_path.name} artifacts')


if __name__ == '__main__':
    prepare(ROOT / 'configs/android-vad.json', ROOT / '.runtime/android-vad')
