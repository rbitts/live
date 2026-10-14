"""Run the native CPU INT4 VLM against the same frozen pilot split, without network."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT/'src'))
from live.ojt_eval import examples, grade


def prompt(row, root):
    parts = []
    for item in row['images']:
        path = (root/item['path']).resolve()
        if not path.is_relative_to(root) or hashlib.sha256(path.read_bytes()).hexdigest() != item['sha256']:
            raise ValueError('image provenance changed')
        age = (row['question_at_ms']-item['captured_at_ms'])/1000
        label = '현재 화면' if item['view']=='current' else '과거 관측, 현재 화면 아님'
        parts.append(f'{label}: {age:g}초 전.<img>{path}</img>')
    if not any(i['view']=='current' for i in row['images']):
        parts.append('현재 화면은 제공되지 않았습니다.')
    return ''.join(parts) + row['prompt']


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--data', type=Path, required=True)
    p.add_argument('--config', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--split', choices=['validation','test'], default='test')
    p.add_argument('--limit', type=int, default=0)
    p.add_argument('--score-existing', action='store_true', help='score completed native output without rerunning inference')
    p.add_argument('--runner', type=Path, default=ROOT/'.runtime/ojt/bin/pc_mnn_runner')
    args = p.parse_args()
    if not args.output.resolve().is_relative_to(ROOT/'.runtime/ojt'):
        p.error('output must be inside .runtime/ojt')
    manifest = json.loads((args.data/'manifest.json').read_text())
    data = args.data/f'{args.split}.jsonl'
    if hashlib.sha256(data.read_bytes()).hexdigest()!=manifest['split_sha256'][args.split]:
        raise ValueError('split changed')
    rows = examples(data)
    if args.limit: rows=rows[:args.limit]
    if not args.score_existing: args.output.mkdir(parents=True,exist_ok=False)
    requests = args.output/'requests.jsonl'
    request_text=''.join(json.dumps({'id':r['id'], 'prompt':prompt(r,Path(manifest['media_root']))},ensure_ascii=False)+'\n' for r in rows)
    if args.score_existing:
        if requests.read_text()!=request_text: raise ValueError('request provenance mismatch')
    else: requests.write_text(request_text)
    results = args.output/'native.jsonl'
    if not args.score_existing:
        with (args.output/'native.log').open('w') as log:
            process=subprocess.run([str(ROOT/'.venv/bin/python'),str(ROOT/'scripts/run_offline.py'),str(args.runner),
                            'vlm',str(args.config),str(requests),str(results)],stdout=log,stderr=subprocess.STDOUT,check=False,timeout=7200)
        (args.output/'exit-code.txt').write_text(str(process.returncode)+'\n')
    native=[]
    for line in results.read_bytes().splitlines():
        valid=True
        try: text=line.decode('utf-8')
        except UnicodeDecodeError: valid=False;text=line.decode('utf-8',errors='replace')
        native.append({**json.loads(text),'valid_utf8':valid})
    if [r['id'] for r in native]!=[r['id'] for r in rows]: raise ValueError('incomplete result set')
    scored = [{**n, 'task':r['task'],'expected':r['response'], **grade(r,n['prediction'])} for r,n in zip(rows,native)]
    for row in scored:
        if not row['valid_utf8'] or row['generated_tokens']>=96 or row['status'] not in (1,2): row['rule_pass']=False
    (args.output/'predictions.jsonl').write_text(''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in scored))
    report = {'backend':'MNN CPU 4 threads', 'network':'seccomp denied', 'split':args.split,
              'data_manifest_sha256':hashlib.sha256((args.data/'manifest.json').read_bytes()).hexdigest(),
              'config':str(args.config.resolve()),'config_sha256':hashlib.sha256(args.config.read_bytes()).hexdigest(),
              'labels_approved':manifest['labels_approved'],'model_acceptance':False,'grader_version':'region-and-safe-abstention-v2',
              'generation_caps':sum(r['generated_tokens']>=96 for r in native),
              'invalid_utf8':sum(not r['valid_utf8'] for r in native),
              'metrics':{task:{'count':len(s),'rule_pass_rate':sum(r['rule_pass'] for r in s)/len(s),
                              'mean_seconds':sum(r['seconds'] for r in s)/len(s)}
                         for task in ('current','history','unknown') if (s:=[r for r in scored if r['task']==task])},
              'peak_rss_kib':max(r['peak_rss_kib'] for r in native)}
    (args.output/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(report,ensure_ascii=False))


if __name__=='__main__':main()
