import asyncio
import base64
import binascii
import io
from pathlib import Path

import numpy as np
from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse
from PIL import Image

from .backend import QwenBackend
from .session import Session

app = FastAPI()
ROOT = Path(__file__).resolve().parents[2]
active = asyncio.Lock()

@app.get('/')
async def index():
    return FileResponse(ROOT/'web/index.html')

@app.get('/worklet.js')
async def worklet():
    return FileResponse(ROOT/'web/worklet.js', media_type='application/javascript')

@app.get('/health')
async def health():
    return {'service': 'live', 'track': 'qwen-system-duplex', 'model_readiness': 'check worker /health', 'session_active': active.locked()}

@app.websocket('/ws')
async def websocket(ws: WebSocket):
    # Browser cross-site websocket requests are rejected; non-browser local smoke is allowed.
    origin = ws.headers.get('origin')
    if origin and origin.removeprefix('http://').removeprefix('https://') != ws.headers.get('host'):
        await ws.close(code=1008)
        return
    await ws.accept()
    if active.locked():
        await ws.send_json({'type': 'error', 'message': '이미 사용 중인 세션이 있습니다.'})
        await ws.close(code=1013)
        return
    async with active:
        backend = QwenBackend()
        session = Session(backend, ws.send_json)
        try:
            await ws.send_json({'type': 'ready', 'track': 'system-duplex'})
            while True:
                message = await ws.receive_json()
                kind = message.get('type')
                if kind == 'audio':
                    raw = message.get('pcm', '')
                    if len(raw) > 1800:
                        raise ValueError('audio packet too large')
                    await session.audio_chunk(np.frombuffer(base64.b64decode(raw, validate=True), dtype='<f4'))
                elif kind == 'frame':
                    jpeg = message.get('jpeg', '')
                    if len(jpeg) > 400_000:
                        raise ValueError('frame too large')
                    image = Image.open(io.BytesIO(base64.b64decode(jpeg, validate=True)))
                    if image.format != 'JPEG' or max(image.size) > 1280:
                        raise ValueError('expected JPEG up to 1280 pixels')
                    image.verify()
                    session.frame(jpeg)
                elif kind == 'camera_off':
                    session.latest = None
                elif kind == 'interrupt':
                    await session.interrupt()
                elif kind == 'text':
                    await session.text(str(message.get('text', '')))
                elif kind == 'profile':
                    session.profile = str(message.get('text', ''))[:500]
                elif kind == 'reset':
                    await session.reset()
                    await ws.send_json({'type': 'reset_done'})
                else:
                    raise ValueError('unknown message type')
        except WebSocketDisconnect:
            pass
        except (ValueError, binascii.Error) as exc:
            await ws.send_json({'type': 'error', 'message': str(exc)})
            await ws.close(code=1008)
        finally:
            await session.close()
            await backend.close()
