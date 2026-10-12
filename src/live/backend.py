import asyncio
import base64
import json
import time
import uuid

import httpx


class QwenBackend:
    def __init__(self):
        self.client = httpx.AsyncClient(timeout=120)
        self.speech_ids = {}
        self.ports = {'asr': 8101, 'vlm': 8102, 'tts': 8103}

    async def call(self, role, payload):
        response = await self.client.post(f'http://127.0.0.1:{self.ports[role]}/infer', json=payload)
        response.raise_for_status()
        return response.json()

    async def transcribe(self, audio, request_id):
        result = await self.call('asr', {'request_id': request_id, 'pcm': base64.b64encode(audio.astype('<f4').tobytes()).decode()})
        return result['text']

    async def respond(self, text, frame, memories, history, profile, request_id):
        now = time.monotonic()
        def encode(f):
            return {'jpeg': f.jpeg, 'age_seconds': round(now-f.captured_at, 2)}
        payload = {'request_id': request_id, 'text': text, 'frame': encode(frame) if frame else None,
                   'memories': [encode(f) for f in memories], 'history': history, 'profile': profile}
        async with self.client.stream('POST', 'http://127.0.0.1:8102/stream', json=payload) as response:
            response.raise_for_status()
            async for line in response.aiter_lines():
                if line:
                    event = json.loads(line)
                    if 'error' in event:
                        raise RuntimeError(event['error'])
                    yield event['text']

    async def speak(self, text, request_id):
        # Keep TTS cancellation separate from VLM stream completion.
        speech_id = request_id + '-speech-' + uuid.uuid4().hex
        self.speech_ids[request_id] = speech_id
        async with self.client.stream('POST', 'http://127.0.0.1:8103/tts_stream',
                                      json={'request_id': speech_id, 'text': text}) as response:
            response.raise_for_status()
            async for line in response.aiter_lines():
                if line:
                    result = json.loads(line)
                    if 'error' in result:
                        raise RuntimeError(result['error'])
                    yield base64.b64decode(result['pcm']), result['sample_rate']

    async def cancel(self, request_id):
        requests = [self.client.post(f'http://127.0.0.1:{p}/cancel/{request_id}', timeout=5)
                    for p in self.ports.values()]
        if request_id in self.speech_ids:
            requests.append(self.client.post(f'http://127.0.0.1:8103/cancel/{self.speech_ids[request_id]}', timeout=5))
        await asyncio.gather(*requests, return_exceptions=True)

    async def release(self, request_id):
        self.speech_ids.pop(request_id, None)

    async def close(self):
        await self.client.aclose()
