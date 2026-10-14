from __future__ import annotations

import asyncio
import base64
import time
import uuid
from collections import deque
from dataclasses import dataclass

import numpy as np


@dataclass(frozen=True)
class Frame:
    jpeg: str
    captured_at: float


class Session:
    """One owner, bounded input, monotonic cancellation epochs, ephemeral memory."""
    def __init__(self, backend, emit, profile='', clock=time.monotonic):
        self.backend, self.emit, self.profile, self.clock = backend, emit, profile[:500], clock
        self.session_id = uuid.uuid4().hex
        self.epoch = 0
        self.response = None
        self.tasks = set()
        self.frames = deque(maxlen=4)
        self.history = deque(maxlen=8)
        self.latest = None
        self.frame_ticket = None
        self.pre_roll = deque(maxlen=10)
        self.audio = []
        self.speaking = False
        self.voiced = self.silent = 0
        self.closed = False

    def issue_frame_ticket(self):
        # One pending capture only. Its server issue time is a conservative
        # lower bound for a compliant client's subsequent camera capture.
        ticket = uuid.uuid4().hex
        self.frame_ticket = (ticket, self.clock())
        return ticket

    def frame(self, jpeg, ticket=None):
        captured_at = self.clock()
        if ticket is not None:
            pending = self.frame_ticket
            if pending is None or ticket != pending[0]:
                return False
            self.frame_ticket = None
            captured_at = pending[1]
            if self.clock() - captured_at > 3:
                return False
        self.latest = Frame(jpeg, captured_at)
        return True

    def camera_off(self):
        self.latest = None
        self.frame_ticket = None

    async def interrupt(self):
        self.epoch += 1
        if self.response and not self.response.done():
            self.response.cancel()
        await self.emit({'type': 'playback_stop', 'epoch': self.epoch})
        return self.epoch

    async def audio_chunk(self, samples):
        # Exactly 20 ms PCM float32 at 16 kHz; bounded work on the receive loop.
        if samples.shape != (320,) or not np.isfinite(samples).all():
            raise ValueError('audio must contain 320 finite float32 samples')
        rms = float(np.sqrt(np.mean(samples * samples)))
        voiced = rms >= 0.018
        self.voiced = self.voiced + 1 if voiced else 0
        if not self.speaking:
            self.pre_roll.append(samples.copy())
            if self.voiced < 3:
                return
            await self.interrupt()
            self.speaking = True
            self.silent = 0
            self.audio = list(self.pre_roll)
            self.pre_roll.clear()
            await self.emit({'type': 'speech_started', 'epoch': self.epoch})
        else:
            self.audio.append(samples.copy())
        self.silent = 0 if voiced else self.silent + 1
        if self.silent >= 30 or len(self.audio) >= 750:
            audio = np.concatenate(self.audio)
            self.audio = []
            self.speaking = False
            self.voiced = self.silent = 0
            epoch = self.epoch
            await self.emit({'type': 'speech_ended', 'epoch': epoch})
            frame = self.latest if self.latest and self.clock() - self.latest.captured_at <= 3 else None
            self.start_answer(epoch, audio=audio, frame=frame)

    async def text(self, text):
        epoch = await self.interrupt()
        frame = self.latest if self.latest and self.clock() - self.latest.captured_at <= 3 else None
        self.start_answer(epoch, text=text[:2000], frame=frame)

    def start_answer(self, epoch, **kwargs):
        self.response = asyncio.create_task(self.answer(epoch, **kwargs))
        self.tasks.add(self.response)
        self.response.add_done_callback(self.tasks.discard)

    async def answer(self, epoch, audio=None, text=None, frame=None):
        started = self.clock()
        request_id = f'{self.session_id}-{epoch}'
        async def send(kind, **values):
            if epoch == self.epoch and not self.closed:
                await self.emit({'type': kind, 'epoch': epoch, **values})
        try:
            if text is None:
                text = await self.backend.transcribe(audio, request_id)
            if epoch != self.epoch or not text.strip():
                return
            await send('transcript', text=text)
            await send('response_start')
            answer = ''
            first_audio = True
            async for sentence in self.backend.respond(text, frame, list(self.frames), list(self.history), self.profile, request_id):
                if epoch != self.epoch:
                    return
                answer += sentence
                await send('text', text=sentence)
                async for pcm, rate in self.backend.speak(sentence, request_id):
                    await send('audio', pcm=base64.b64encode(pcm).decode(), sample_rate=rate,
                               first_audio_ms=round((self.clock()-started)*1000) if first_audio else None)
                    first_audio = False
            if epoch == self.epoch:
                self.history.append({'user': text, 'assistant': answer})
                if frame is not None:
                    self.frames.append(frame)
                await send('response_done', elapsed_ms=round((self.clock()-started)*1000))
        except asyncio.CancelledError:
            await self.backend.cancel(request_id)
            raise
        except Exception as exc:
            await send('error', message=f'{type(exc).__name__}: {exc}')
        finally:
            await self.backend.release(request_id)

    async def reset(self):
        await self.interrupt()
        self.frames.clear()
        self.history.clear()
        self.latest = None
        self.frame_ticket = None
        self.profile = ''
        self.audio.clear()
        self.pre_roll.clear()
        self.speaking = False
        self.voiced = self.silent = 0

    async def close(self):
        self.closed = True
        for task in self.tasks:
            task.cancel()
        await asyncio.gather(*self.tasks, return_exceptions=True)
        self.frames.clear()
        self.history.clear()
        self.latest = None
        self.frame_ticket = None
        self.audio.clear()
