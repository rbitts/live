import asyncio

import numpy as np
import pytest

from live.session import Session


class Backend:
    def __init__(self, delay=0):
        self.delay = delay
        self.cancelled = []
        self.inputs = []
    async def transcribe(self, audio, request_id):
        return '빨간 컵은 어디 있어?'
    async def respond(self, text, frame, memories, history, profile, request_id):
        self.inputs.append((frame, memories, history, profile))
        await asyncio.sleep(self.delay)
        yield '책상 왼쪽에 있습니다.'
    async def speak(self, text, request_id):
        yield np.zeros(240, dtype='<f4').tobytes(), 24000
    async def cancel(self, request_id):
        self.cancelled.append(request_id)
    async def release(self, request_id):
        pass


@pytest.mark.asyncio
async def test_barge_in_keeps_capture_and_drops_old_audio():
    events = []
    async def emit(event): events.append(event)
    backend = Backend(delay=.05)
    session = Session(backend, emit)
    await session.text('처음 질문')
    await asyncio.sleep(.01)
    for _ in range(3):
        await session.audio_chunk(np.full(320, .1, dtype='float32'))
    for _ in range(30):
        await session.audio_chunk(np.zeros(320, dtype='float32'))
    await session.response
    assert backend.cancelled
    assert len([e for e in events if e['type'] == 'audio']) == 1
    assert all(e['epoch'] == 2 for e in events if e['type'] == 'audio')
    assert session.history[0]['user'] == '빨간 컵은 어디 있어?'
    await session.close()


@pytest.mark.asyncio
async def test_stale_frame_is_not_current_and_memory_is_bounded():
    async def emit(event): pass
    now = [0]
    backend = Backend()
    session = Session(backend, emit, clock=lambda: now[0])
    session.frame('frame')
    await session.text('질문')
    await session.response
    now[0] = 4
    await session.text('아까는?')
    await session.response
    current, memories, _, _ = backend.inputs[-1]
    assert current is None
    assert memories[0].jpeg == 'frame'
    for _ in range(20):
        now[0] += 1
        session.frame('frame')
        await session.text('질문')
        await session.response
    assert len(session.frames) == 4 and len(session.history) == 8
    await session.close()
    assert not session.frames and not session.history


@pytest.mark.asyncio
async def test_silence_does_not_trigger_response_and_input_is_bounded():
    events = []
    async def emit(event): events.append(event)
    session = Session(Backend(), emit)
    for _ in range(1000):
        await session.audio_chunk(np.zeros(320, dtype='float32'))
    assert session.response is None and len(session.pre_roll) == 10
    for bad in [np.zeros(1), np.full(320, np.nan)]:
        with pytest.raises(ValueError):
            await session.audio_chunk(bad)
    assert not events


@pytest.mark.asyncio
async def test_disconnect_cancels_and_erases_memory():
    async def emit(event): pass
    backend = Backend(delay=10)
    session = Session(backend, emit)
    await session.text('질문')
    await asyncio.sleep(.01)
    await session.close()
    assert session.response.done() and backend.cancelled


@pytest.mark.asyncio
async def test_late_noncooperative_backend_result_is_discarded():
    events = []
    async def emit(event): events.append(event)
    class Late(Backend):
        async def speak(self, text, request_id):
            try:
                await asyncio.sleep(10)
            except asyncio.CancelledError:
                yield b'late', 24000
    session = Session(Late(), emit)
    await session.text('질문')
    await asyncio.sleep(.01)
    await session.interrupt()
    await session.response
    assert not any(e['type'] in ('audio', 'response_done') for e in events)
    assert not session.history


@pytest.mark.asyncio
async def test_reset_erases_inflight_audio_and_profile():
    async def emit(event): pass
    session = Session(Backend(), emit, profile='private preference')
    for _ in range(4):
        await session.audio_chunk(np.full(320, .1, dtype='float32'))
    assert session.audio and session.speaking
    await session.reset()
    assert not session.audio and not session.pre_roll and not session.profile
    assert not session.speaking and session.latest is None
