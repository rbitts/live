"""GPU synthetic-speech replay. No claim of human mic/speaker acceptance."""
import asyncio
import base64
import io
import json
import time
from pathlib import Path

import httpx
import numpy as np
from PIL import Image, ImageDraw
import websockets

ROOT = Path(__file__).resolve().parents[1]

def frame():
    image=Image.new('RGB',(640,480),'#b9a387')
    draw=ImageDraw.Draw(image)
    draw.rectangle((90,190,190,310),fill='#dd2424')
    draw.ellipse((155,210,220,280),outline='#dd2424',width=15)
    draw.rectangle((410,190,510,310),fill='white')
    draw.ellipse((480,210,540,280),outline='white',width=15)
    buff=io.BytesIO();image.save(buff,format='JPEG')
    return base64.b64encode(buff.getvalue()).decode()

async def main():
    report={'kind':'gpu-synthetic-replay', 'human_live_acceptance':False, 'models':json.loads((ROOT/'configs/models.json').read_text()), 'events':[]}
    async with httpx.AsyncClient(timeout=180) as client:
        report['workers']=[(await client.get(f'http://127.0.0.1:{p}/health')).json() for p in [8101,8102,8103]]
        start=time.monotonic()
        response=await client.post('http://127.0.0.1:8103/infer',json={'request_id':f'fixture-{time.time_ns()}', 'text':'빨간 컵은 어디에 있어요?'})
        response.raise_for_status();result=response.json()
        pcm=np.frombuffer(base64.b64decode(result['pcm']),dtype='<f4')
        sr=result['sample_rate']
        pcm=np.interp(np.arange(0,len(pcm),sr/16000),np.arange(len(pcm)),pcm).astype('<f4')
        report['fixture_tts_seconds']=time.monotonic()-start
        response=await client.post('http://127.0.0.1:8101/infer',json={'request_id':f'asr-smoke-{time.time_ns()}', 'pcm':base64.b64encode(pcm.tobytes()).decode()})
        response.raise_for_status();report['asr']=response.json()
    first_audio=asyncio.Event();done=asyncio.Event();interrupted=asyncio.Event()
    audio_count=0;interruption_sent=None;epoch=None
    async with websockets.connect('ws://127.0.0.1:8080/ws',max_size=8_000_000) as ws:
        start=time.monotonic()
        async def receive():
            nonlocal audio_count,epoch
            async for raw in ws:
                msg=json.loads(raw);kind=msg['type']
                entry={k:v for k,v in msg.items() if k!='pcm'}
                entry['t']=round(time.monotonic()-start,3)
                report['events'].append(entry)
                if kind=='error':
                    raise RuntimeError(msg['message'])
                if kind=='audio':
                    assert len(base64.b64decode(msg['pcm']))>0
                    audio_count+=1
                    first_audio.set()
                if kind=='speech_started' and interruption_sent is not None:
                    report['server_barge_ack_ms']=round((time.monotonic()-interruption_sent)*1000,1)
                    epoch=msg['epoch'];interrupted.set()
                if kind=='response_done' and epoch==msg['epoch']:
                    done.set();return
        receiver=asyncio.create_task(receive())
        async def send_audio():
            sequence=np.concatenate([pcm,np.zeros(16000,dtype='<f4')])
            for i in range(0,len(sequence),320):
                chunk=sequence[i:i+320]
                if len(chunk)<320:chunk=np.pad(chunk,(0,320-len(chunk)))
                await ws.send(json.dumps({'type':'audio','pcm':base64.b64encode(chunk.astype('<f4').tobytes()).decode()}))
                if i%16000==0:
                    await ws.send(json.dumps({'type':'frame','jpeg':frame()}))
                await asyncio.sleep(.02)
        try:
            await ws.send(json.dumps({'type':'profile','text':'한국어로 한 문장으로 짧게 답해주세요.'}))
            await send_audio()
            waiter=asyncio.create_task(first_audio.wait())
            await asyncio.wait([waiter,receiver],timeout=180,return_when=asyncio.FIRST_COMPLETED)
            if receiver.done():await receiver
            if not first_audio.is_set():raise TimeoutError('first audio')
            interruption_sent=time.monotonic()
            await send_audio()
            await asyncio.wait_for(receiver,180)
            assert interrupted.is_set() and done.is_set() and audio_count>=2
            report['passed']=True
            report['audio_chunks']=audio_count
            report['limits']='Synthetic replay; no physical playback or human microphone/camera acceptance. Barge ack includes fixture leading silence.'
        finally:
            receiver.cancel()
            await asyncio.gather(receiver,return_exceptions=True)
    (ROOT/'reports/qwen-smoke.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({k:v for k,v in report.items() if k not in ['events','models','workers']},ensure_ascii=False,indent=2))

asyncio.run(main())
