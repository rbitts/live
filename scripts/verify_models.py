"""Check pinned local snapshots and required inference assets, without GPU allocation."""
import json
from pathlib import Path

root=Path(__file__).resolve().parents[1]
manifest=json.loads((root/'configs/models.json').read_text())
results={}
for name, spec in manifest.items():
    path=(root/'models'/name).resolve()
    required={'config.json'}
    index=path/'model.safetensors.index.json'
    if index.exists():
        required.update(json.loads(index.read_text())['weight_map'].values())
    else:
        required.add('model.safetensors')
    if name=='minicpm':
        required.update('assets/token2wav/'+x for x in ['flow.pt','hift.pt','campplus.onnx','speech_tokenizer_v2_25hz.onnx'])
    if name=='tts':
        required.add('speech_tokenizer/model.safetensors')
    missing=[str(p) for p in required if not (path/p).is_file() or (path/p).stat().st_size==0]
    results[name]={'repo':spec['repo'],'revision':path.name,'revision_matches':path.name==spec['revision'],
                   'missing':missing,'required_file_bytes':sum((path/p).stat().st_size for p in required if (path/p).is_file())}
print(json.dumps(results,indent=2))
assert all(x['revision_matches'] and not x['missing'] for x in results.values())
