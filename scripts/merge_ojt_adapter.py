"""Merge a local OJT adapter into the pinned base on CPU for native export."""
import argparse
import hashlib
import json
from pathlib import Path

import torch
from peft import PeftModel
from transformers import AutoModelForImageTextToText, AutoProcessor


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adapter', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    pinned = json.loads((root/'configs/models.json').read_text())['vlm']
    base = (root/'models/vlm').resolve()
    if base.name != pinned['revision']:
        parser.error('base revision mismatch')
    if not args.output.resolve().is_relative_to(root/'.runtime/ojt'):
        parser.error('output must be inside .runtime/ojt')
    args.output.mkdir(parents=True, exist_ok=False)
    torch.set_num_threads(4)
    model = AutoModelForImageTextToText.from_pretrained(base, dtype=torch.bfloat16,
              device_map='cpu', attn_implementation='sdpa', local_files_only=True)
    model = PeftModel.from_pretrained(model, args.adapter).merge_and_unload(safe_merge=True)
    model.save_pretrained(args.output, safe_serialization=True, max_shard_size='2GB')
    AutoProcessor.from_pretrained(base, local_files_only=True).save_pretrained(args.output)
    report = {'base': pinned, 'adapter_sha256': hashlib.sha256((args.adapter/'adapter_model.safetensors').read_bytes()).hexdigest(),
              'device':'cpu', 'dtype':'bfloat16', 'inference_verified':False}
    (args.output/'merge-provenance.json').write_text(json.dumps(report,indent=2)+'\n')


if __name__ == '__main__': main()
