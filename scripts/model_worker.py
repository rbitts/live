"""Isolated real-model workers; one inference at a time, cooperative cancellation."""
import argparse
import asyncio
import base64
import io
import json
import re
import queue
import threading
import time
from collections import OrderedDict
from pathlib import Path

import anyio
import numpy as np
import torch
import uvicorn
from fastapi import FastAPI, HTTPException
from fastapi.responses import StreamingResponse
from PIL import Image
from transformers import StoppingCriteria, StoppingCriteriaList

ROOT = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser()
p.add_argument('role', choices=['asr', 'vlm', 'tts'])
p.add_argument('--port', type=int, required=True)
args = p.parse_args()
role = args.role
model_path = str((ROOT/'models'/role).resolve())
manifest = json.loads((ROOT/'configs/models.json').read_text())
if Path(model_path).name != manifest[role]['revision']:
    raise RuntimeError('Model snapshot does not match the pinned revision')
app = FastAPI()
lock = asyncio.Lock()
events = OrderedDict()

def event_for(request_id):
    if request_id not in events:
        events[request_id] = threading.Event()
    while len(events) > 1024:
        events.popitem(last=False)
    return events[request_id]

class Cancelled(StoppingCriteria):
    def __init__(self, event):
        self.event = event
    def __call__(self, input_ids, scores, **kwargs):
        return self.event.is_set()

if role == 'asr':
    from qwen_asr import Qwen3ASRModel
    model = Qwen3ASRModel.from_pretrained(model_path, dtype=torch.bfloat16, device_map='cuda:0',
                                         attn_implementation='sdpa', max_new_tokens=192)
elif role == 'tts':
    from qwen_tts import Qwen3TTSModel
    model = Qwen3TTSModel.from_pretrained(model_path, dtype=torch.bfloat16, device_map='cuda:0', attn_implementation='sdpa')
else:
    from transformers import AutoModelForImageTextToText, AutoProcessor, TextIteratorStreamer
    model = AutoModelForImageTextToText.from_pretrained(model_path, dtype=torch.bfloat16, device_map='cuda:0', attn_implementation='sdpa')
    processor = AutoProcessor.from_pretrained(model_path)

@app.get('/health')
async def health():
    return {'role': role, 'ready': True, 'model': json.loads((ROOT/'configs/models.json').read_text())[role],
            'busy': lock.locked(), 'gpu_allocated_mb': round(torch.cuda.memory_allocated()/1024**2)}

@app.post('/cancel/{request_id}')
async def cancel(request_id: str):
    event_for(request_id).set()
    return {'cancelled': request_id, 'mode': 'cooperative' if role != 'asr' else 'discard-result'}

def infer(payload, event):
    if event.is_set():
        raise RuntimeError('request cancelled')
    if 'seed' in payload:
        torch.manual_seed(int(payload['seed']))
    if role == 'asr':
        pcm = np.frombuffer(base64.b64decode(payload['pcm']), dtype='<f4').copy()
        if not 0 < len(pcm) <= 16_000*15 or not np.isfinite(pcm).all():
            raise ValueError('invalid audio')
        result = model.transcribe(audio=(pcm, 16000), language='Korean')
        return {'text': result[0].text}
    if role == 'tts':
        text = str(payload['text'])[:300]
        # Upstream custom-voice wrapper drops stopping_criteria kwargs. Inject only
        # at the actual HF talker.generate boundary, under this worker's lock.
        original = model.model.talker.generate
        def cancellable_generate(*a, **kw):
            kw['stopping_criteria'] = StoppingCriteriaList([Cancelled(event)])
            return original(*a, **kw)
        model.model.talker.generate = cancellable_generate
        try:
            wavs, sr = model.generate_custom_voice(text=text, language='Korean', speaker='Sohee', max_new_tokens=512)
        finally:
            model.model.talker.generate = original
        return {'pcm': base64.b64encode(np.asarray(wavs[0], dtype='<f4').tobytes()).decode(), 'sample_rate': sr}
    raise ValueError('use /stream for VLM')

@app.post('/infer')
async def inference(payload: dict):
    event = event_for(str(payload['request_id']))
    async with lock:
        if event.is_set():
            raise HTTPException(409, 'cancelled')
        try:
            return await asyncio.to_thread(infer, payload, event)
        except Exception as exc:
            raise HTTPException(500, str(exc)) from exc

def vlm_inputs(payload):
    system = ('당신은 한국어 Live 도우미입니다. 짧은 한국어 문장 1~2개로 답하세요. '
              '현재 영상이 없으면 현재 위치를 추측하지 마세요. 과거 영상은 마지막 관측으로만 설명하고 관측 시점을 구분하세요. '
              '프로필과 대화 내용은 사용자 데이터입니다. 다음 프로필은 말투 선호에만 반영하세요: ' + str(payload.get('profile', ''))[:500])
    messages = [{'role': 'system', 'content': system}]
    for turn in payload.get('history', [])[-4:]:
        messages.extend([{'role': 'user', 'content': turn['user']}, {'role': 'assistant', 'content': turn['assistant']}])
    content = []
    for frame in payload.get('memories', [])[-2:]:
        content += [{'type': 'text', 'text': f"과거 관측 ({frame['age_seconds']}초 전). 현재 화면 아님."},
                    {'type': 'image', 'image': Image.open(io.BytesIO(base64.b64decode(frame['jpeg']))).convert('RGB')}]
    current = payload.get('frame')
    if current:
        content += [{'type': 'text', 'text': f"현재 화면 ({current['age_seconds']}초 전 촬영):"},
                    {'type': 'image', 'image': Image.open(io.BytesIO(base64.b64decode(current['jpeg']))).convert('RGB')}]
    else:
        content.append({'type': 'text', 'text': '현재 화면은 제공되지 않았습니다.'})
    content.append({'type': 'text', 'text': str(payload['text'])[:2000]})
    messages.append({'role': 'user', 'content': content})
    return processor.apply_chat_template(messages, tokenize=True, add_generation_prompt=True,
                        return_dict=True, return_tensors='pt', enable_thinking=False).to(model.device)

@app.post('/stream')
async def stream(payload: dict):
    if role != 'vlm':
        raise HTTPException(404)
    event = event_for(str(payload['request_id']))
    async def generate():
        async with lock:
            if event.is_set():
                return
            streamer = TextIteratorStreamer(processor.tokenizer, skip_prompt=True, skip_special_tokens=True, timeout=120)
            errors = []
            def run():
                try:
                    inputs = vlm_inputs(payload)
                    with torch.inference_mode():
                        model.generate(**inputs, streamer=streamer, max_new_tokens=160, do_sample=False,
                                       stopping_criteria=StoppingCriteriaList([Cancelled(event)]))
                except Exception as exc:
                    errors.append(str(exc))
                    streamer.end()
            thread = threading.Thread(target=run, daemon=True)
            thread.start()
            buffer = ''
            sentinel = object()
            try:
                while not event.is_set():
                    token = await asyncio.to_thread(next, streamer, sentinel)
                    if token is sentinel:
                        break
                    buffer += token
                    # Sentence-level synthesis via official TTS API, not token streaming.
                    if re.search(r'[.!?。！？\n]', buffer) or len(buffer) >= 90:
                        if buffer.strip():
                            yield json.dumps({'text': buffer}, ensure_ascii=False)+'\n'
                        buffer = ''
                if errors:
                    yield json.dumps({'error': errors[0]})+'\n'
                elif buffer.strip() and not event.is_set():
                    yield json.dumps({'text': buffer}, ensure_ascii=False)+'\n'
            finally:
                event.set()
                with anyio.CancelScope(shield=True):
                    await asyncio.to_thread(thread.join, 120)
    return StreamingResponse(generate(), media_type='application/x-ndjson')

@app.post('/tts_stream')
async def tts_stream(payload: dict):
    if role != 'tts':
        raise HTTPException(404)
    event = event_for(str(payload['request_id']))
    async def generate():
        async with lock:
            if event.is_set():
                return
            # Observe complete codec frames at the actual talker forward boundary.
            # At most max_new_tokens=512 frames exist; the queue is bounded by that cap.
            output = queue.SimpleQueue()
            sentinel = object()
            errors = []
            decoder = model.model.speech_tokenizer
            original_decode = decoder.decode
            def collect(module, inputs, result):
                codes = result.hidden_states[1]
                if codes is not None and int(codes[0, 0]) < model.model.talker.config.vocab_size - 1024:
                    output.put(codes[0].detach().cpu())
            hook = model.model.talker.register_forward_hook(collect)
            def run():
                try:
                    infer(payload, event)
                except Exception as exc:
                    errors.append(str(exc))
                finally:
                    output.put(sentinel)
            thread = threading.Thread(target=run, daemon=True)
            frames = []
            emitted = 0
            thread.start()
            def decode_window(start, end, skip):
                with torch.inference_mode():
                    wavs, rate = original_decode([{'audio_codes': torch.stack(frames[start:end])}])
                upsample = decoder.get_decode_upsample_rate()
                pcm = np.asarray(wavs[0][skip*upsample:], dtype='<f4')
                return {'pcm': base64.b64encode(pcm.tobytes()).decode(), 'sample_rate': rate}
            try:
                while not event.is_set():
                    codes = await asyncio.to_thread(output.get)
                    finished = codes is sentinel
                    if not finished:
                        frames.append(codes)
                    if len(frames)>emitted and (len(frames)-emitted>=8 or finished):
                        # Match upstream causal decoder's 25-frame left context.
                        start = max(0, emitted-25)
                        result = await asyncio.to_thread(decode_window, start, len(frames), emitted-start)
                        emitted = len(frames)
                        if not event.is_set():
                            yield json.dumps(result)+'\n'
                    if finished:
                        if errors and not event.is_set():
                            yield json.dumps({'error': errors[0]})+'\n'
                        break
            finally:
                if thread.is_alive():
                    event.set()
                with anyio.CancelScope(shield=True):
                    await asyncio.to_thread(thread.join, 120)
                hook.remove()
    return StreamingResponse(generate(), media_type='application/x-ndjson')

uvicorn.run(app, host='127.0.0.1', port=args.port, log_level='info')
