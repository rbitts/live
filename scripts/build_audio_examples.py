"""Save synthetic Qwen speech and replay its observed delivery gaps for the guide.

Run with .venv/bin/python while the isolated local TTS worker is ready on 8103.
This does not record a microphone or replace the historical smoke reports.
"""
import asyncio
import argparse
import base64
from datetime import datetime, timezone
import hashlib
import json
import re
from pathlib import Path
import time
import wave

import httpx
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / 'docs/audio'
TEXTS = {
    'normal': '빨간 컵은 왼쪽에 있어요.',
    'interrupt': '흰색 컵은 오른쪽에 있어요.',
    'memory': '아까는 왼쪽에 있었어요. 지금 위치는 확인할 수 없어요.',
}


def write_wav(name, pcm, rate):
    path = DEST / name
    with wave.open(str(path), 'wb') as out:
        out.setnchannels(1)
        out.setsampwidth(2)
        out.setframerate(rate)
        out.writeframes((np.clip(pcm, -1, 1) * 32767).astype('<i2').tobytes())
    return {'file': name, 'seconds': round(len(pcm) / rate, 3),
            'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}


def embed_audio():
    report = json.loads((DEST / 'samples.json').read_text())
    for sample in report['samples'].values():
        for mode in ['continuous', 'arrival_replay']:
            variant = sample[mode]
            variant['data_uri'] = 'data:audio/wav;base64,' + base64.b64encode(
                (DEST / variant['file']).read_bytes()).decode()
    page = ROOT / 'docs/qwen-live-interactive.html'
    content, count = re.subn(
        r'(<script type="application/json" id="audio-examples-data">).*?(</script>)',
        lambda m: m[1] + json.dumps(report, ensure_ascii=False, separators=(',', ':')) + m[2],
        page.read_text(), flags=re.S)
    assert count == 1, 'Missing or duplicate audio data marker'
    page.write_text(content)


async def main():
    DEST.mkdir(exist_ok=True)
    report = {'created_at': datetime.now(timezone.utc).isoformat(),
              'model': json.loads((ROOT / 'configs/models.json').read_text())['tts'],
              'speaker': 'Sohee', 'seed': 0, 'initial_buffer_ms': 25,
              'scope': 'New TTS-only sample run; not the historical 1337ms trial, an end-to-end benchmark, or a physical speaker recording.',
              'samples': {}}
    async with httpx.AsyncClient(timeout=180) as client:
        for key, text in TEXTS.items():
            chunks, timings = [], []
            start = time.monotonic()
            async with client.stream('POST', 'http://127.0.0.1:8103/tts_stream',
                                     json={'request_id': f'doc-{key}-{time.time_ns()}', 'text': text, 'seed': 0}) as response:
                response.raise_for_status()
                async for line in response.aiter_lines():
                    data = json.loads(line)
                    if 'error' in data:
                        raise RuntimeError(data['error'])
                    pcm = np.frombuffer(base64.b64decode(data['pcm']), dtype='<f4')
                    assert len(pcm) and np.isfinite(pcm).all()
                    rate = data['sample_rate']
                    chunks.append(pcm)
                    timings.append({'arrival_ms': (time.monotonic() - start) * 1000,
                                    'audio_ms': len(pcm) / rate * 1000})
            assert chunks
            # Anchor playback to the first chunk + 25ms, so startup waiting is
            # excluded. Insert only underrun silence, leaving speech unchanged.
            cursor_ms = timings[0]['arrival_ms'] + 25
            replay, gaps = [], []
            for pcm, timing in zip(chunks, timings):
                gap_samples = round(max(0, timing['arrival_ms'] - cursor_ms) * rate / 1000)
                gap_ms = gap_samples / rate * 1000
                replay.extend([np.zeros(gap_samples, dtype='<f4'), pcm])
                gaps.append(round(gap_ms, 3))
                cursor_ms += gap_ms + timing['audio_ms']
            sample = {'text': text, 'sample_rate': rate,
                      'chunks': [{k: round(v, 3) for k, v in t.items()} for t in timings],
                      'gap_before_chunk_ms': gaps,
                      'gap_total_ms': round(sum(gaps), 3),
                      'first_chunk_ms': round(timings[0]['arrival_ms'], 3),
                      'continuous': write_wav(f'{key}-continuous.wav', np.concatenate(chunks), rate),
                      'arrival_replay': write_wav(f'{key}-arrival-replay.wav', np.concatenate(replay), rate)}
            report['samples'][key] = sample
            print(key, 'speech:', sample['continuous']['seconds'], 'gap ms:', sample['gap_total_ms'], flush=True)
    (DEST / 'samples.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    embed_audio()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--embed-only', action='store_true', help='Embed existing WAV files without running models')
    if parser.parse_args().embed_only:
        embed_audio()
    else:
        asyncio.run(main())
