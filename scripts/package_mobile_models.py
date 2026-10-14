"""Create a bounded, checksummed offline model ZIP for the Android importer."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import zipfile


def sha(path):
    h=hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda:f.read(1024*1024),b''):h.update(chunk)
    return h.hexdigest()


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for role in ('asr','vlm','tts'):p.add_argument('--'+role,type=Path,required=True)
    p.add_argument('--observer',type=Path,help='Optional pinned fast visual classifier')
    p.add_argument('--output',type=Path,required=True)
    args=p.parse_args();root=Path(__file__).resolve().parents[1]
    if not args.output.resolve().is_relative_to(root/'.runtime/ojt'):p.error('output must be inside .runtime/ojt')
    revision=subprocess.check_output(['git','-C',str(root/'.upstream/mnn'),'rev-parse','HEAD'],text=True).strip()
    upstream=json.loads((root/'configs/upstreams.json').read_text())
    if revision!=upstream['mnn']['revision']:raise ValueError('runtime revision differs from pin')
    manifest={'schema_version':1,'model_acceptance':False,'purpose':'noncommercial OJT pilot; device acceptance pending',
              'models':json.loads((root/'configs/models.json').read_text()),'mnn_revision':revision,
              'runtime_patch_sha256':sha(root/'patches/mnn-customvoice-sohee.patch'),'files':[]}
    selected=[]
    for role in ('asr','vlm','tts','observer'):
        folder=getattr(args,role)
        if folder is None:continue
        if role=='observer':
            manifest['observer']=json.loads((root/'configs/cooking-observer.json').read_text())
        for path in sorted(folder.iterdir()):
            if not path.is_file():continue
            if path.name not in ('config.json','llm_config.json') and path.suffix not in ('.mnn','.weight','.bin','.txt','.mtok'):continue
            relative=role+'/'+path.name
            manifest['files'].append({'path':relative,'bytes':path.stat().st_size,'sha256':sha(path)})
            selected.append((path,relative))
    total=sum(f['bytes'] for f in manifest['files'])
    if total>8*1024**3 or any(f['bytes']>4*1024**3 for f in manifest['files']):raise ValueError('mobile package limit')
    manifest['total_bytes']=total
    args.output.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(args.output,'x',compression=zipfile.ZIP_STORED,allowZip64=True) as archive:
        archive.writestr('manifest.json',json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
        for path,relative in selected:archive.write(path,relative)
    with zipfile.ZipFile(args.output) as archive:
        if archive.testzip() is not None:raise ValueError('package CRC verification failed')
    report={**manifest,'archive':str(args.output.resolve()),'archive_sha256':sha(args.output)}
    args.output.with_suffix('.manifest.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'total_bytes':total,'archive_sha256':report['archive_sha256'],'files':len(selected)}))


if __name__=='__main__':main()
