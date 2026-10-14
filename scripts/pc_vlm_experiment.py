"""Bounded local VLM baseline/LoRA experiment. No mobile or human-acceptance claim."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import random
import time
import sys

import torch
from PIL import Image
from transformers import AutoModelForImageTextToText, AutoProcessor

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'src'))


from live.ojt_eval import examples, grade


def messages(row, media_root):
    content = []
    for item in row['images']:
        age = (row['question_at_ms'] - item['captured_at_ms']) / 1000
        label = '현재 화면' if item['view'] == 'current' else '과거 관측, 현재 화면 아님'
        content += [{'type': 'text', 'text': f'{label}: {age:g}초 전.'},
                    {'type': 'image', 'image': Image.open(media_root / item['path']).convert('RGB')}]
    if not any(image['view'] == 'current' for image in row['images']):
        content.append({'type': 'text', 'text': '현재 화면은 제공되지 않았습니다.'})
    content.append({'type': 'text', 'text': row['prompt']})
    return [{'role': 'system', 'content': '짧은 한국어로 답하세요. 현재 화면과 과거 관측을 구분하고 관측하지 않은 현재 위치는 추측하지 마세요.'},
            {'role': 'user', 'content': content}]


def tokenize(processor, row, media_root, training=False):
    chat = messages(row, media_root)
    prefix = processor.apply_chat_template(chat, tokenize=True, add_generation_prompt=True, enable_thinking=False,
                                           return_dict=True, return_tensors='pt')
    if not training:
        return prefix
    full = processor.apply_chat_template(chat + [{'role': 'assistant', 'content': row['response']}], tokenize=True,
                                         add_generation_prompt=False, enable_thinking=False, return_dict=True, return_tensors='pt')
    length = prefix['input_ids'].shape[1]
    if not torch.equal(full['input_ids'][:, :length], prefix['input_ids']):
        raise ValueError('assistant prefix mismatch: refusing incorrectly masked training')
    if full['input_ids'].shape[1] > 2048:
        raise ValueError('2048 token training bound exceeded')
    labels = full['input_ids'].clone()
    labels[:, :length] = -100
    if not (labels != -100).any():
        raise ValueError('assistant answer has no supervised tokens')
    full['labels'] = labels
    return full



def evaluate(model, processor, rows, media_root, output, limit=0):
    model.eval()
    predictions = []
    for i, row in enumerate(rows[:limit] if limit else rows):
        start = time.monotonic()
        inputs = tokenize(processor, row, media_root).to(model.device)
        with torch.inference_mode():
            ids = model.generate(**inputs, max_new_tokens=96, do_sample=False, use_cache=True)
        torch.cuda.synchronize()
        answer = processor.decode(ids[0, inputs['input_ids'].shape[1]:], skip_special_tokens=True)
        item = {'id': row['id'], 'task': row['task'], 'expected': row['response'], 'prediction': answer,
                'seconds': time.monotonic() - start, **grade(row, answer)}
        predictions.append(item)
        with output.open('a', encoding='utf-8') as handle:
            handle.write(json.dumps(item, ensure_ascii=False) + '\n')
        if (i + 1) % 10 == 0:
            print(f'evaluation {i + 1}/{limit or len(rows)}', flush=True)
    return {task: {'count': len(selected), 'rule_pass_rate': sum(r['rule_pass'] for r in selected) / len(selected),
                   'mean_seconds': sum(r['seconds'] for r in selected) / len(selected)}
            for task in ('current', 'history', 'unknown') if (selected := [r for r in predictions if r['task'] == task])}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--data', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--mode', choices=['baseline', 'lora', 'qat', 'merged'], required=True)
    parser.add_argument('--adapter', type=Path)
    parser.add_argument('--steps', type=int, default=60)
    parser.add_argument('--limit', type=int, default=0)
    parser.add_argument('--eval-split', choices=['validation', 'test'], default='validation')
    parser.add_argument('--allow-unreviewed-experiment', action='store_true')
    parser.add_argument('--quantize-shared-head', action='store_true')
    args = parser.parse_args()
    if not args.output.resolve().is_relative_to(ROOT / '.runtime/ojt'):
        parser.error('output must be inside .runtime/ojt')
    manifest = json.loads((args.data / 'manifest.json').read_text())
    if not manifest.get('labels_approved') and not args.allow_unreviewed_experiment:
        parser.error('unreviewed labels require explicit experimental mode')
    pinned = json.loads((ROOT / 'configs/models.json').read_text())['vlm']
    model_path = (ROOT / 'models/vlm').resolve()
    if model_path.name != pinned['revision']:
        raise ValueError('model revision differs from pinned snapshot')
    for split, sha in manifest['split_sha256'].items():
        if hashlib.sha256((args.data / f'{split}.jsonl').read_bytes()).hexdigest() != sha:
            raise ValueError('prepared data changed')
    args.output.mkdir(parents=True, exist_ok=False)
    from pc_preflight import gpu_preflight
    gpu_lease = gpu_preflight(args.output, 18000 if args.mode in ('lora', 'qat') else 12000)
    random.seed(42); torch.manual_seed(42); torch.set_num_threads(4)
    processor = AutoProcessor.from_pretrained(model_path, local_files_only=True)
    processor.image_processor.size = {'shortest_edge': 65536, 'longest_edge': 262144}
    model = AutoModelForImageTextToText.from_pretrained(model_path, dtype=torch.bfloat16, device_map='cuda:0',
                                                       attn_implementation='sdpa', local_files_only=True)
    report = {'mode': args.mode, 'model': pinned, 'data_manifest_sha256': hashlib.sha256((args.data/'manifest.json').read_bytes()).hexdigest(),
              'labels_approved': manifest.get('labels_approved', False), 'model_acceptance': False,
              'hardware': torch.cuda.get_device_name(), 'cuda_visible_devices': os.environ.get('CUDA_VISIBLE_DEVICES'),
              'image_pixels_max': 262144, 'max_new_tokens': 96, 'seed': 42,
              'evaluation_split': args.eval_split,
              'grader_version': 'region-and-safe-abstention-v2',
              'scope': 'GQA synthetic clocks and VISOR provided observations; rule scoring, no human acceptance'}
    media_root = Path(manifest['media_root'])
    if args.mode in ('lora', 'qat'):
        from peft import LoraConfig, get_peft_model, PeftModel
        targets = [name for name, module in model.named_modules() if isinstance(module, torch.nn.Linear)
                   and name.startswith('model.language_model.layers.')
                   and name.rsplit('.', 1)[-1] in ('q_proj', 'v_proj', 'in_proj_qkv', 'out_proj')]
        if args.adapter:
            model = PeftModel.from_pretrained(model, args.adapter, is_trainable=True)
        else:
            model = get_peft_model(model, LoraConfig(r=8, lora_alpha=16, lora_dropout=.05, target_modules=targets,
                                                   bias='none', task_type='CAUSAL_LM'))
        if args.mode == 'qat':
            from live.ojt_quant import enable_reference_qat
            report['quantization'] = enable_reference_qat(model, args.quantize_shared_head)
        model.enable_input_require_grads()
        model.gradient_checkpointing_enable(gradient_checkpointing_kwargs={'use_reentrant': False})
        model.config.use_cache = False
        train = examples(args.data / 'train.jsonl')
        random.shuffle(train)
        learning_rate = 2e-5 if args.mode == 'qat' else 1e-4
        optimizer = torch.optim.AdamW([p for p in model.parameters() if p.requires_grad], lr=learning_rate)
        report['lora'] = {'r': 8, 'alpha': 16, 'targets': targets, 'steps': args.steps, 'accumulation': 4, 'learning_rate': learning_rate,
                          'trainable_parameters': sum(p.numel() for p in model.parameters() if p.requires_grad)}
        model.train()
        for step in range(args.steps):
            optimizer.zero_grad(set_to_none=True)
            losses = []
            for micro in range(4):
                row = train[(step * 4 + micro) % len(train)]
                inputs = tokenize(processor, row, media_root, training=True).to(model.device)
                loss = model(**inputs, use_cache=False).loss
                if not torch.isfinite(loss):
                    raise ValueError('non-finite training loss')
                (loss / 4).backward(); losses.append(loss.item())
            torch.nn.utils.clip_grad_norm_([p for p in model.parameters() if p.requires_grad], 1.0)
            optimizer.step()
            event = {'step': step + 1, 'loss': sum(losses) / 4}
            with (args.output / 'training.jsonl').open('a') as handle: handle.write(json.dumps(event) + '\n')
            print(json.dumps(event), flush=True)
        model.save_pretrained(args.output / 'adapter', safe_serialization=True)
        model.gradient_checkpointing_disable()
        model.config.use_cache = True
    elif args.adapter:
        from peft import PeftModel
        model = PeftModel.from_pretrained(model, args.adapter)
        if args.mode == 'merged':
            model = model.merge_and_unload(safe_merge=True)
            model.save_pretrained(args.output / 'merged', safe_serialization=True, max_shard_size='2GB')
            processor.save_pretrained(args.output / 'merged')
    report['metrics'] = evaluate(model, processor, examples(args.data / f'{args.eval_split}.jsonl'), media_root,
                                 args.output / 'predictions.jsonl', args.limit)
    report['peak_allocated_mib'] = torch.cuda.max_memory_allocated() / 1024 ** 2
    report['peak_reserved_mib'] = torch.cuda.max_memory_reserved() / 1024 ** 2
    (args.output / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(report['metrics'], ensure_ascii=False), flush=True)


if __name__ == '__main__':
    main()
