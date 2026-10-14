#!/usr/bin/env python3
"""Build a silent, repeatable clip from the W3C-hosted Big Buck Bunny trailer.

Developer tool only: run with PyAV/Pillow in an isolated tools environment.
Media stays under .runtime. No generated question/answer is fed to the model.
"""
from fractions import Fraction
import hashlib
import json
from pathlib import Path
import av

ROOT = Path(__file__).resolve().parents[1]
folder = ROOT / '.runtime/video-replay'
source = folder / 'sample.mp4'
output = folder / 'scenario.mp4'
expected_source = 'b2ded9ae5a20fa36ca8cec49ef923bffb5e1a51d9e8c1f8336273d2fa9d35ff0'
if hashlib.sha256(source.read_bytes()).hexdigest() != expected_source:
    raise SystemExit('Source differs from inspected trailer; review before changing the pin.')
# Each 30s section repeats actual moving frames from the identified source range.
sections = [(10, 11, 'large light rabbit, grass, sky')] * 2 + [
    (15, 15.5, 'three small animals by a tree')] + [
    (5, 6, 'tree, grass, dark hole at its base; no rabbit')] * 7
ranges = sorted(set((start,end) for start,end,_ in sections))
frames = {}
with av.open(str(source)) as movie:
    for frame in movie.decode(video=0):
        for start, end in ranges:
            if start <= frame.time < end:
                frames.setdefault(start, []).append(frame.reformat(640, 360, format='yuv420p'))
with av.open(str(output), 'w') as movie:
    stream = movie.add_stream('libx264', rate=24)
    stream.width, stream.height, stream.pix_fmt = 640, 360, 'yuv420p'
    stream.options = {'crf': '23', 'preset': 'fast'}
    index = 0
    for start, end, _ in sections:
        for n in range(30 * 24):
            frame = frames[start][n % len(frames[start])]
            frame.pts = index
            frame.time_base = Fraction(1,24)
            for packet in stream.encode(frame): movie.mux(packet)
            index += 1
    for packet in stream.encode(): movie.mux(packet)
manifest = {
    'source_url': 'https://media.w3.org/2010/05/bunny/trailer.mp4',
    'source_sha256': hashlib.sha256(source.read_bytes()).hexdigest(),
    'output_sha256': hashlib.sha256(output.read_bytes()).hexdigest(),
    'attribution': '(c) copyright 2008, Blender Foundation / www.bigbuckbunny.org',
    'license_url': 'https://peach.blender.org/about/',
    'duration_s': len(sections)*30, 'fps': 24, 'size': [640, 360], 'audio': False,
    'editing': 'First 60s rabbit, next 30s small animals, final 210s forest; each repeats a moving excerpt, not continuous original action.',
    'sections': [{'start_s': n * 30, 'end_s': (n+1)*30, 'source_s': [a,b], 'reviewed_scene': c}
                 for n,(a,b,c) in enumerate(sections)],
}
(folder / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+'\n')
print(json.dumps(manifest, ensure_ascii=False))
