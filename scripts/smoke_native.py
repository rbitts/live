"""Paced 1s audio + JPEG replay against official native duplex websocket."""
import argparse
import asyncio
import base64
import io
import json
import time
import urllib.request
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw
import soundfile as sf
import websockets

ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser()
p.add_argument('--seconds',type=int,default=30)
p.add_argument('--report',default='native-smoke.json')
args=p.parse_args()
if not 15 <= args.seconds <= 280: p.error('--seconds must be between 15 and 280')

async def main():
    url='https://qianwen-res.oss-cn-beijing.aliyuncs.com/Qwen3-ASR-Repo/asr_en.wav'
    data=await asyncio.to_thread(lambda:urllib.request.urlopen(url,timeout=30).read())
    wave,sr=sf.read(io.BytesIO(data),dtype='float32')
    if wave.ndim>1:wave=wave.mean(axis=1)
    wave=np.interp(np.arange(0,len(wave),sr/16000),np.arange(len(wave)),wave).astype('<f4')
    image=Image.new('RGB',(640,480),'#b9a387');draw=ImageDraw.Draw(image);draw.rectangle((90,190,190,310),fill='red')
    buff=io.BytesIO();image.save(buff,format='JPEG');jpeg=base64.b64encode(buff.getvalue()).decode()
    report={'kind':'native-paced-duplex-smoke','human_live_acceptance':False,'fixture_source':url,'model':json.loads((ROOT/'configs/models.json').read_text())['minicpm'],'results':[]}
    async with websockets.connect('ws://127.0.0.1:8006/v1/realtime?mode=video',max_size=16_000_000) as ws:
        async def until(kind,timeout=90):
            while True:
                msg=json.loads(await asyncio.wait_for(ws.recv(),timeout))
                if msg['type']=='error':raise RuntimeError(msg)
                if msg['type']==kind:return msg
        await until('session.queue_done')
        await ws.send(json.dumps({'type':'session.init','payload':{'system_prompt':'You are a helpful assistant. Briefly respond in English after listening.','config':{'generate_audio':True,'chunk_ms':1000}}}))
        await until('session.created')
        start=time.monotonic();audio_seen=False;inputs_after_output=0
        async def receive():
            nonlocal audio_seen
            async for raw in ws:
                msg=json.loads(raw)
                if msg['type']=='error':raise RuntimeError(msg)
                if msg['type']=='response.output.delta':
                    raw_audio=base64.b64decode(msg.get('audio') or '')
                    pcm=np.frombuffer(raw_audio,dtype='<f4')
                    assert np.isfinite(pcm).all()
                    audio_seen=audio_seen or (len(pcm)>0 and bool(np.any(pcm)))
                    report['results'].append({**{k:v for k,v in msg.items() if k!='audio'},'audio_bytes':len(raw_audio),'audio_seconds':len(pcm)/24000,'wall_seconds':round(time.monotonic()-start,3)})
                if msg['type']=='session.closed':return
        receiver=asyncio.create_task(receive())
        try:
            for i in range(args.seconds):
                chunk=wave[(i%15)*16000:((i%15)+1)*16000]
                chunk=np.pad(chunk,(0,16000-len(chunk))).astype('<f4')
                await ws.send(json.dumps({'type':'input.append','input':{'input_id':f'chunk-{i}', 'audio':base64.b64encode(chunk.tobytes()).decode(),'video_frames':[jpeg],'force_listen':i<3,'max_slice_nums':1}}))
                if audio_seen:inputs_after_output+=1
                await asyncio.sleep(max(0,start+i+1-time.monotonic()))
                if receiver.done():await receiver
            # Drain a bounded backlog, then stop; large backlog is a performance failure.
            deadline=time.monotonic()+60
            while len({r.get('input_id') for r in report['results']})<args.seconds and time.monotonic()<deadline:
                if receiver.done():await receiver
                await asyncio.sleep(.2)
            await ws.send(json.dumps({'type':'session.close','reason':'smoke_finished'}))
            await asyncio.wait_for(receiver,20)
        finally:
            receiver.cancel();await asyncio.gather(receiver,return_exceptions=True)
        steps={r.get('input_id'):r.get('metrics',{}).get('wall_clock_ms') for r in report['results']}
        costs=[value for value in steps.values() if value is not None]
        report.update(audio_output=audio_seen,inputs_after_output=inputs_after_output,received_steps=len(steps),sent_steps=args.seconds,
                      p95_step_ms=float(np.percentile(costs,95)) if costs else None,
                      model_smoke_passed=audio_seen and inputs_after_output>0 and len(steps)==args.seconds,
                      elapsed_seconds=round(time.monotonic()-start,2),
                      realtime_step_target_passed=bool(costs) and float(np.percentile(costs[3:],95))<=1000 if len(costs)>3 else False)
    (ROOT/'reports'/args.report).write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({k:v for k,v in report.items() if k not in ['results','model']},indent=2))

asyncio.run(main())
