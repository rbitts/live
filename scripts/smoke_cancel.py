"""Repeat real TTS cancellation and resource release, without retaining media."""
import asyncio
import json
import time
from pathlib import Path
import httpx
import numpy as np

async def main():
    runs=[]
    async with httpx.AsyncClient(timeout=60) as client:
        for _ in range(10):
            request_id=f'cancel-smoke-{time.time_ns()}'
            task=asyncio.create_task(client.post('http://127.0.0.1:8103/infer',json={'request_id':request_id,'text':'이것은 음성 생성 중단을 검증하기 위한 긴 문장입니다. 새 지시가 들어오면 즉시 멈추고 이전 음성이 뒤늦게 재생되지 않아야 합니다.'}))
            await asyncio.sleep(.5)
            before=(await client.get('http://127.0.0.1:8103/health')).json()
            assert before['busy'] and not task.done(), 'cancellation must target active inference'
            start=time.monotonic()
            response=await client.post(f'http://127.0.0.1:8103/cancel/{request_id}')
            response.raise_for_status()
            result=await task
            release_ms=round((time.monotonic()-start)*1000,1)
            health=(await client.get('http://127.0.0.1:8103/health')).json()
            runs.append({'cancel_to_release_ms':release_ms,'inference_http_status':result.status_code,'worker_busy_after':health['busy']})
        report={'kind':'real-tts-cancellation','runs':runs,'p95_cancel_to_release_ms':float(np.percentile([r['cancel_to_release_ms'] for r in runs],95)),
                'passed':all(not r['worker_busy_after'] and r['cancel_to_release_ms']<2000 for r in runs),'physical_playback_stop_tested':False}
        Path('reports/qwen-cancel.json').write_text(json.dumps(report,indent=2)+'\n')
        print(json.dumps(report,indent=2))
        assert report['passed']
asyncio.run(main())
