"""Bounded official TTS CPU reference and first-step parity evidence."""
import argparse
import json
from pathlib import Path
import time

import numpy as np
import soundfile as sf
import torch
from qwen_tts import Qwen3TTSModel


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--streaming-text', action='store_true')
    p.add_argument('--frames', type=int, default=8)
    args = p.parse_args()
    args.output.mkdir(exist_ok=False, parents=True)
    torch.set_num_threads(4)
    torch.manual_seed(42)
    root = Path(__file__).resolve().parents[1]
    start = time.monotonic()
    model = Qwen3TTSModel.from_pretrained(str(root/'models/tts'), dtype=torch.float32,
                                        device_map='cpu', attn_implementation='sdpa')
    def before(module, positional, kwargs):
        if not (args.output/'prompt.f32').exists():
            kwargs['inputs_embeds'].detach().float().numpy().tofile(args.output/'prompt.f32')
    def after(module, positional, kwargs, output):
        if not (args.output/'logits.f32').exists():
            output.logits[0, -1].detach().float().numpy().tofile(args.output/'logits.f32')
    model.model.talker.register_forward_pre_hook(before, with_kwargs=True)
    model.model.talker.register_forward_hook(after, with_kwargs=True)
    wavs, sr = model.generate_custom_voice(text='빨간 컵은 왼쪽에 있어요.', language='Korean', speaker='Sohee',
                                          non_streaming_mode=not args.streaming_text, max_new_tokens=args.frames)
    sf.write(args.output/'speech.wav', wavs[0], sr)
    result = {'seconds':time.monotonic()-start,'samples':len(wavs[0]),'sample_rate':sr,
              'peak':float(np.abs(wavs[0]).max()),'streaming_text':args.streaming_text,
              'max_frames':args.frames, 'device':'cpu'}
    (args.output/'report.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result))


if __name__ == '__main__': main()
