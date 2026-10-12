"""Verify streamed audio contains the expected Korean speech; collect chunk timing."""
import asyncio,base64,json,time,re
from pathlib import Path
import httpx,numpy as np

async def main():
    expected='빨간 컵은 왼쪽에 있어요.'
    chunks=[];timings=[];samples=0
    async with httpx.AsyncClient(timeout=120) as client:
        start=time.monotonic()
        async with client.stream('POST','http://127.0.0.1:8103/tts_stream',json={'request_id':f'tts-stream-{time.time_ns()}','text':expected,'seed':0}) as response:
            response.raise_for_status()
            async for line in response.aiter_lines():
                data=json.loads(line)
                if 'error' in data:raise RuntimeError(data['error'])
                pcm=np.frombuffer(base64.b64decode(data['pcm']),dtype='<f4')
                assert len(pcm)>0 and np.isfinite(pcm).all()
                chunks.append(pcm);samples+=len(pcm);sr=data['sample_rate']
                timings.append({'arrival_ms':round((time.monotonic()-start)*1000,1),'audio_ms':round(len(pcm)/sr*1000,1)})
        audio=np.concatenate(chunks)
        resampled=np.interp(np.arange(0,len(audio),sr/16000),np.arange(len(audio)),audio).astype('<f4')
        response=await client.post('http://127.0.0.1:8101/infer',json={'request_id':f'verify-tts-{time.time_ns()}','pcm':base64.b64encode(resampled.tobytes()).decode()})
        response.raise_for_status();recognized=response.json()['text']
        normalize=lambda s:re.sub(r'[^가-힣a-zA-Z0-9]','',s)
        report={'kind':'streamed-tts-roundtrip','text':expected,'recognized':recognized,'passed':normalize(expected)==normalize(recognized),'chunks':timings,'total_audio_seconds':samples/sr,'first_audio_ms':timings[0]['arrival_ms']}
        end = timings[0]['arrival_ms'] + 25
        gap = 0
        for c in timings:
            gap += max(0, c['arrival_ms']-end)
            end = max(end, c['arrival_ms'])+c['audio_ms']
        report['playback_gap_proxy_ms'] = round(gap, 1)
        report['gap_measurement'] = 'Server arrival timing with 25ms initial buffer; not physical speaker measurement.'
        Path('reports/tts-stream.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
        print(json.dumps(report,ensure_ascii=False,indent=2))
        assert report['passed']
asyncio.run(main())
