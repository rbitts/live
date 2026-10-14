#!/usr/bin/env python3
"""Prepare a continuous EPIC cooking replay; annotations are evaluator-only.

Run in the isolated video-tools environment (PyAV). Originals/derived media stay
under .runtime. This does not run a model or turn annotation timestamps into
runtime observations, step completions, or proactive notifications.
"""
import argparse
import csv
from fractions import Fraction
import hashlib
import json
from pathlib import Path

import av


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def seconds(value):
    h, m, s = value.split(':')
    return int(h) * 3600 + int(m) * 60 + float(s)


def prepare(source, annotations, video_id, output):
    root = Path(__file__).resolve().parents[1]
    if not output.resolve().is_relative_to(root / '.runtime'):
        raise ValueError('Media output must remain under .runtime')
    output.mkdir(parents=True, exist_ok=True)
    target = output / f'{video_id}-continuous.mp4'
    manifest_path = output / 'manifest.json'
    if target.exists() or manifest_path.exists():
        raise ValueError('Use a fresh output folder; do not overwrite frozen evidence')
    rows = sorted((row for row in csv.DictReader(annotations.open())
                   if row['video_id'] == video_id),
                  key=lambda row: seconds(row['start_timestamp']))
    if not rows:
        raise ValueError('No annotations for this video')
    temporary = output / 'encoding.part.mp4'
    count = 0
    first = last = None
    with av.open(str(source)) as movie:
        video = movie.streams.video[0]
        duration = movie.duration / av.time_base
        if not 0 < duration <= 600:
            raise ValueError('Replay exceeds the app 10-minute input limit')
        metadata = {'width': video.width, 'height': video.height,
                    'duration_s': duration, 'average_rate': str(video.average_rate),
                    'has_audio': bool(movie.streams.audio)}
        width = 640
        height = round(video.height * width / video.width / 2) * 2
        with av.open(str(temporary), 'w') as encoded:
            stream = encoded.add_stream('libx264', rate=15)
            stream.width, stream.height, stream.pix_fmt = width, height, 'yuv420p'
            stream.codec_context.thread_count = 4
            stream.options = {'crf': '23', 'preset': 'fast'}
            next_at = 0.0
            for frame in movie.decode(video=0):
                at = frame.time
                if at is None:
                    raise ValueError('Source frame has no presentation timestamp')
                if first is None:
                    first = at
                relative = at - first
                if relative + 1e-6 < next_at:
                    continue
                scaled = frame.reformat(width, height, format='yuv420p')
                scaled.pts = round(relative * 90000)
                scaled.time_base = Fraction(1, 90000)
                for packet in stream.encode(scaled):
                    encoded.mux(packet)
                count += 1
                last = at
                next_at = (int(relative * 15 + 1e-6) + 1) / 15
                if count % 900 == 0:
                    print(f'Encoded {relative:.1f}s', flush=True)
            for packet in stream.encode():
                encoded.mux(packet)
    if not count or temporary.stat().st_size > 256 * 1024 ** 2:
        raise ValueError('Empty replay or app size limit exceeded')
    # Decode the whole artifact, including its tail. A playable first frame is
    # insufficient evidence that a long task replay was acquired correctly.
    with av.open(str(temporary)) as check:
        checked = sum(1 for _ in check.decode(video=0))
        replay_duration = check.duration / av.time_base
        if checked != count or abs(replay_duration - duration) > .2:
            raise ValueError('Decoded duration/frame count differs from original')
    temporary.rename(target)
    (output / 'annotations.json').write_text(json.dumps(rows, indent=2) + '\n')
    manifest = {
        'video_id': video_id, 'source_file': str(source.resolve()),
        'source_sha256': sha256(source), 'source_metadata': metadata,
        'annotation_source_sha256': sha256(annotations), 'annotation_count': len(rows),
        'annotations_role': 'evaluator only; never supplied to runtime inference',
        'output_file': str(target.resolve()), 'output_sha256': sha256(target),
        'output_bytes': target.stat().st_size, 'output_duration_s': replay_duration,
        'decoded_frames': checked, 'output_size': [width, height], 'audio': False,
        'source_start_pts_s': first, 'source_last_selected_pts_s': last,
        'editing': 'Entire original chronological video; spatial resize and ~15fps '
                   'temporal subsampling; audio omitted; no cuts, loops or time acceleration.',
        'timestamp_tolerance_s': 1 / 15,
        'attribution': 'Dima Damen et al., Scaling Egocentric Vision: The '
                       'EPIC-KITCHENS Dataset (2018); EPIC-KITCHENS-100 action annotations.',
        'license': 'CC-BY-NC-4.0 (dataset-specific video README and annotation license)',
        'license_url': 'https://creativecommons.org/licenses/by-nc/4.0/',
        'model_acceptance': False,
    }
    manifest_path.write_text(json.dumps(manifest, indent=2) + '\n')
    return manifest


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--annotations', type=Path, required=True)
    parser.add_argument('--video-id', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(prepare(args.source, args.annotations, args.video_id, args.output), indent=2))
