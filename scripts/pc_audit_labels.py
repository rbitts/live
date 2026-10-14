"""Machine cross-check of source bbox object labels; never marks human approval."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import time

from PIL import Image
import torch
from transformers import AutoModelForImageTextToText, AutoProcessor


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--audit', type=Path, required=True)
    parser.add_argument('--media-root', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    from pc_preflight import gpu_preflight
    gpu_lease = gpu_preflight(args.output, 12000)
    root = Path(__file__).resolve().parents[1]
    pinned = json.loads((root/'configs/models.json').read_text())['vlm']
    torch.set_num_threads(4); torch.manual_seed(42)
    model = AutoModelForImageTextToText.from_pretrained(root/'models/vlm', dtype=torch.bfloat16,
              device_map='cuda:0', attn_implementation='sdpa', local_files_only=True).eval()
    processor = AutoProcessor.from_pretrained(root/'models/vlm', local_files_only=True)
    processor.image_processor.size = {'shortest_edge': 65536, 'longest_edge': 65536}
    sources = [json.loads(line) for line in (args.audit/'provenance.jsonl').read_text().splitlines()]
    results = []
    name_map = {'컵': 'cup', '병': 'bottle', '그릇': 'bowl', '책': 'book', '전화기': 'phone'}
    for i, row in enumerate(sources):
        evidence = row['evidence']; x,y,w,h = evidence['bbox_xywh']
        with Image.open(args.media_root/f'{evidence["image_id"]}.jpg') as im:
            crop = im.convert('RGB').crop((x,y,x+w,y+h))
        messages = [{'role':'user', 'content':[{'type':'image','image':crop}, {'type':'text','text':
            'Name the visible object. Answer with one word from: cup, bottle, bowl, book, phone, other. If uncertain, answer other.'}]}]
        inputs = processor.apply_chat_template(messages, tokenize=True, add_generation_prompt=True, enable_thinking=False,
                    return_dict=True, return_tensors='pt').to(model.device)
        with torch.inference_mode(): prediction = model.generate(**inputs,max_new_tokens=12,do_sample=False)
        answer = processor.decode(prediction[0,inputs['input_ids'].shape[1]:],skip_special_tokens=True)
        words = re.findall(r'\b(cup|bottle|bowl|book|phone|other)\b',answer.lower())
        expected = name_map[evidence['label_ko']]
        item = {'id':row['id'],'prediction':answer,'expected_class':expected,'class_agreement':set(words)=={expected},
                'method':'base-model-bbox-crop-classification','human_approved':False,'model':pinned}
        results.append(item)
        with (args.output/'checks.jsonl').open('a',encoding='utf-8') as handle:handle.write(json.dumps(item,ensure_ascii=False)+'\n')
        if (i+1)%20==0:print(f'label cross-check {i+1}/{len(sources)}',flush=True)
    report={'checked':len(results),'agree':sum(r['class_agreement'] for r in results),'human_approved':False,
            'independent_ground_truth':False,'scope':'model/annotation agreement only; visual ambiguity may remain',
            'source_sha256':hashlib.sha256((args.audit/'provenance.jsonl').read_bytes()).hexdigest(),'model':pinned}
    (args.output/'report.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report),flush=True)


if __name__=='__main__':main()
