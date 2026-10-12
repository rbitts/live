"""Recreate pinned upstream sources and isolated environments; no global installs."""
import argparse
import json
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser()
p.add_argument('--models', action='store_true')
a = p.parse_args()
def run(*args):
    subprocess.run(args, cwd=root, check=True)
for name, entry in json.loads((root/'configs/upstreams.json').read_text()).items():
    target = root/'.upstream'/name
    if not target.exists():
        run('git','clone','--no-checkout',entry['url'],str(target))
    if subprocess.check_output(['git','-C',str(target),'status','--porcelain'],text=True).strip():
        raise RuntimeError(f'upstream has changes: {target}')
    run('git','-C',str(target),'checkout','--detach',entry['revision'])
for role in ['core','asr','tts','vlm','native']:
    env = '.venv' if role == 'core' else f'.venv-{role}'
    run('uv','venv','--allow-existing','--python','3.11',env)
    install = ['uv','pip','install','--python',env+'/bin/python','-r',f'requirements/{role}.lock']
    if role == 'native':
        install += ['--override', 'requirements/native-overrides.txt']
    run(*install)
    if role == 'core':
        run('uv','pip','install','--python',env+'/bin/python','--no-deps','-e','.')
    if role in ['asr','tts']:
        run('uv','pip','install','--python',env+'/bin/python','--no-deps',f'./.upstream/qwen-{role}')
if a.models:
    run(str(root/'.venv/bin/python'),'scripts/download_models.py')
