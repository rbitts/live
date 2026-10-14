"""Create an offline, self-contained visual review page; approval requires user selection."""
import argparse
import base64
import html
import json
from pathlib import Path

from live.ojt_data import read_provenance, read_records, split_records


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('audit', type=Path)
    parser.add_argument('--media-root', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    if not args.output.resolve().is_relative_to(root / '.runtime/ojt'):
        parser.error('--output must be inside .runtime/ojt/')
    if args.output.exists():
        parser.error('output already exists')
    rows = read_records(args.audit / 'records.jsonl', args.media_root)
    if len(rows) > 200:
        parser.error('review pages are limited to 200 records')
    mapping = json.loads((args.audit / 'split-map.json').read_text())
    sources = read_provenance(args.audit / 'provenance.jsonl', rows, mapping, allow_unreviewed=True)
    split_records(rows, assignments=mapping, provenance=sources)
    cards, exports = [], []
    for i, row in enumerate(rows):
        source = sources[row['id']]
        source = {**source, 'media_sha256': {image['path']: image['sha256'] for image in row['images']}}
        exports.append(source)
        visuals = []
        for image in row['images']:
            encoded = base64.b64encode((args.media_root / image['path']).read_bytes()).decode()
            evidence = source.get('evidence', {})
            overlay = ''
            if 'bbox_xywh' in evidence and len(row['images']) == 1:
                x, y, w, h = map(float, evidence['bbox_xywh'])
                width, height = map(float, evidence['image_size'])
                overlay = (f'<svg viewBox="0 0 {width} {height}"><rect x="{x}" y="{y}" width="{w}" height="{h}" '
                           'fill="none" stroke="#ff4242" stroke-width="3"/></svg>')
            visuals.append(f'<div class="picture"><img alt="검수 이미지" src="data:image/jpeg;base64,{encoded}">{overlay}</div>')
        options = ''.join(f'<option value="{key}" {"selected" if source["review_status"] == key else ""}>{label}</option>'
                          for key, label in [('pending', '미검수'), ('approved', '적합'), ('rejected', '수정 필요')])
        cards.append(f'<article data-index="{i}"><small>{i + 1} / {len(rows)} · {html.escape(mapping[row["group_id"]])} · '
                     f'{html.escape(row["id"])}</small>{"".join(visuals)}<p>{html.escape(row["prompt"])}</p>'
                     f'<p><b>{html.escape(row["response"])}</b></p><label>판정 <select>{options}</select></label>'
                     '<label> 검수 메모 <input aria-label="검수 메모" placeholder="모호함·잘못된 주석 등"></label></article>')
    data = json.dumps(exports, ensure_ascii=False).replace('<', '\\u003c')
    page = '''<!doctype html><html lang="ko"><meta charset="utf-8"><title>GQA 위치 QA 검수</title>
<style>body{font:16px system-ui;margin:24px;background:#f3f5f7;color:#18202b}header{position:sticky;top:0;background:#fff;padding:16px;z-index:2;border-bottom:2px solid #ccd}
main{display:grid;grid-template-columns:repeat(auto-fit,minmax(320px,1fr));gap:18px}article{background:white;border-radius:10px;padding:16px}p{white-space:pre-line}.picture{position:relative}.picture img{width:100%;display:block}.picture svg{position:absolute;inset:0;width:100%;height:100%;pointer-events:none}body.hide-box svg{display:none}button,select,input{font:inherit;padding:8px;margin:6px}input{max-width:90%}small{overflow-wrap:anywhere}</style>
<header><b>GQA 화면 위치 QA · 검수 후보</b><p>이미지에서 질문 대상이 하나로 정해지는지, 빨간 상자가 올바른 물체를 가리키는지, 답변이 맞는지 확인하세요. 학습 완료 결과가 아닙니다.</p>
<label><input id="boxes" type="checkbox" checked>대상 상자 표시</label><label><input id="pending" type="checkbox">미검수만 표시</label><button id="save">판정 파일 저장</button><span id="counts"></span><p>판정은 이 화면에서만 유지됩니다. 닫기 전에 파일로 저장하세요. 미검수·수정 필요 항목은 학습 승인으로 처리되지 않습니다.</p></header><main>'''
    page += ''.join(cards) + '</main><script>const sources=' + data + ''';
const cards=[...document.querySelectorAll('article')];
function refresh(){let approved=0,rejected=0;cards.forEach((card,i)=>{const status=card.querySelector('select').value;sources[i].review_status=status;sources[i].review_note=card.querySelector('input').value;approved+=status==='approved';rejected+=status==='rejected';card.hidden=document.querySelector('#pending').checked&&status!=='pending'});document.querySelector('#counts').textContent=`적합 ${approved} · 수정 필요 ${rejected} · 미검수 ${sources.length-approved-rejected}`}
document.querySelector('#boxes').onchange=e=>document.body.classList.toggle('hide-box',!e.target.checked);
document.querySelector('#pending').onchange=refresh;
cards.forEach(card=>card.addEventListener('change',refresh));
document.querySelector('#save').onclick=()=>{refresh();const blob=new Blob([sources.map(s=>JSON.stringify(s)).join('\\n')+'\\n'],{type:'application/x-ndjson;charset=utf-8'});const url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download='reviewed-provenance.jsonl';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000)};refresh();
</script></html>'''
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open('x', encoding='utf-8') as handle:
        handle.write(page)
    print(f'{len(rows)} pending/review records: {args.output.resolve()}')


if __name__ == '__main__':
    main()
