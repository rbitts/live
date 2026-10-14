"""Build the shared-engine PC verification executables against pinned local MNN."""
import hashlib
import json
from pathlib import Path
import subprocess

root=Path(__file__).resolve().parents[1]
mnn=root/'.upstream/mnn'
expected=json.loads((root/'configs/upstreams.json').read_text())['mnn']['revision']
actual=subprocess.check_output(['git','-C',str(mnn),'rev-parse','HEAD'],text=True).strip()
if actual!=expected:raise SystemExit('MNN revision mismatch')
library=root/'.runtime/ojt/mnn-build'
if not (library/'libllm.so').is_file():raise SystemExit('build the pinned MNN C++ runtime first; see mobile-pc-runbook.md')
out=root/'.runtime/ojt/bin';out.mkdir(exist_ok=True,parents=True)
includes=[mnn/'include',mnn/'transformers/llm/engine/include',mnn/'transformers/llm/engine/src',mnn/'3rd_party',mnn/'3rd_party/rapidjson/include']
records=[]
for name in ('pc_mnn_runner','pc_live_e2e','pc_cancel_stress','pc_policy_test'):
    target=out/name
    command=['g++','-std=c++17','-O2','-pthread',str(root/'native'/f'{name}.cpp'),
             *[f'-I{path}' for path in includes],f'-L{library}',f'-Wl,-rpath,{library}',
             '-lllm','-lMNN','-o',str(target)]
    subprocess.run(command,check=True)
    records.append({'name':name,'sha256':hashlib.sha256(target.read_bytes()).hexdigest(),'command':command})
subprocess.run([str(out/'pc_policy_test')],check=True)
(out/'build-manifest.json').write_text(json.dumps({'mnn_revision':actual,'binaries':records},indent=2)+'\n')
print(json.dumps({'output':str(out),'binaries':len(records)}))
