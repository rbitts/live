"""Small, annotation-grounded Korean GQA audit sets, with no model or media download."""
from __future__ import annotations

from collections import Counter
import hashlib
import json
import math
from pathlib import Path
import zipfile

from live.ojt_data import canonical, digest


SOURCE_URL = 'https://downloads.cs.stanford.edu/nlp/data/gqa/sceneGraphs.zip'
VERSION = 'gqa-screen-center-ko-v2'
# Aliases sharing a Korean name also share the uniqueness check.
NAMES = {'cup': '컵', 'coffee cup': '컵', 'mug': '컵', 'coffee mug': '컵',
         'bottle': '병', 'water bottle': '병', 'bowl': '그릇',
         'book': '책', 'phone': '전화기', 'cell phone': '전화기'}
# "plate" can mean a vehicle license plate; "pot" can mean a plant pot.
# Exclude both until relation-aware sense disambiguation is visually audited.


def position_example(image_id, scene, seed=42):
    """One unambiguous annotated class, with the bbox center away from thirds boundaries.

    Annotation uniqueness is only a candidate filter: incomplete annotations still
    require visual human review. No support relation or object motion is inferred.
    """
    if not isinstance(image_id, str) or not image_id.isascii() or not image_id.isdecimal():
        raise ValueError('GQA image id must contain ASCII digits only')
    width, height = scene['width'], scene['height']
    if any(type(v) not in (int, float) or not math.isfinite(v) or v <= 0 for v in (width, height)):
        return None
    objects = scene['objects']
    counts = Counter(NAMES[o['name']] for o in objects.values() if o['name'] in NAMES)
    for object_id in sorted(objects, key=lambda value: digest([seed, image_id, value])):
        obj = objects[object_id]
        label = NAMES.get(obj['name'])
        if not label or counts[label] != 1:
            continue
        x, y, w, h = (obj[k] for k in ('x', 'y', 'w', 'h'))
        if any(type(v) not in (int, float) or not math.isfinite(v) for v in (x, y, w, h)):
            continue
        if x < 0 or y < 0 or w <= 0 or h <= 0 or x + w > width or y + h > height:
            continue
        center = (x + w / 2) / width
        if min(abs(center - 1 / 3), abs(center - 2 / 3)) < .04:
            continue
        location = ('왼쪽', '가운데', '오른쪽')[min(2, int(center * 3))]
        row = {'id': f'gqa:{image_id}:{object_id}:current', 'group_id': f'gqa:image:{image_id}',
               'prompt': f'[현재 관측: 0ms, 질문: 0ms]\n화면을 가로로 삼등분하면 {label}의 중심은 어느 구역에 있나요?',
               'response': f'현재 관측에서 {label}의 중심은 화면 {location} 구역에 있습니다.',
               'question_at_ms': 0,
               'images': [{'path': f'{image_id}.jpg', 'view': 'current', 'captured_at_ms': 0}]}
        evidence = {'image_id': image_id, 'object_id': object_id, 'object_name': obj['name'],
                    'label_ko': label, 'bbox_xywh': [x, y, w, h], 'image_size': [width, height],
                    'screen_region': location, 'clock': 'synthetic_single_snapshot_not_video_time'}
        return row, evidence
    return None


def build_audit(archive: Path, output: Path, counts=(140, 30, 30), seed=42):
    """Select official train -> train/dev and official val -> OJT test, without extraction."""
    if len(counts) != 3 or any(type(n) is not int or n < 1 for n in counts):
        raise ValueError('each split needs a positive count')
    if output.exists():
        raise FileExistsError(output)
    with archive.open('rb') as handle:
        archive_hash = hashlib.file_digest(handle, 'sha256').hexdigest()
    records, provenance, split_map, requests, selected_images = [], [], {}, [], set()
    with zipfile.ZipFile(archive) as handle:
        for source_split, targets in [('train', [('train', counts[0]), ('validation', counts[1])]),
                                      ('val', [('test', counts[2])])]:
            entry = f'{source_split}_sceneGraphs.json'
            if handle.getinfo(entry).file_size > 512 * 1024 ** 2:
                raise ValueError('annotation member exceeds 512 MiB limit')
            raw = handle.read(entry)
            annotation_hash = hashlib.sha256(raw).hexdigest()
            scenes = json.loads(raw)
            del raw
            candidates = iter(sorted(scenes, key=lambda value: digest([seed, value])))
            for target, requested in targets:
                added = 0
                for image_id in candidates:
                    result = position_example(image_id, scenes[image_id], seed)
                    if result is None:
                        continue
                    if image_id in selected_images:
                        raise ValueError('image identity occurs in multiple official splits')
                    row, evidence = result
                    records.append(row)
                    selected_images.add(image_id)
                    split_map[row['group_id']] = target
                    provenance.append({'id': row['id'], 'source': 'gqa', 'source_release': 'sceneGraphs-v1.1',
                                       'source_url': SOURCE_URL, 'source_sha256': annotation_hash,
                                       'source_archive_sha256': archive_hash, 'source_member': entry,
                                       'source_split': source_split, 'source_item': image_id,
                                       'leakage_keys': [f'gqa:image:{image_id}', f'visual-genome:image:{image_id}'],
                                       'transform_version': VERSION, 'review_status': 'pending',
                                       'record_sha256': digest(row), 'evidence': evidence})
                    requests.append({'path': row['images'][0]['path'], 'source_split': source_split,
                                     'image_size': evidence['image_size']})
                    added += 1
                    if added == requested:
                        break
                if added != requested:
                    raise ValueError(f'insufficient eligible {source_split} images for {target}: {added}/{requested}')
            del scenes
    # Nothing is written until all source validation and selection succeeds.
    output.mkdir(parents=True, exist_ok=False)
    files = {'records.jsonl': ''.join(canonical(row) + '\n' for row in records),
             'provenance.jsonl': ''.join(canonical(row) + '\n' for row in provenance),
             'split-map.json': canonical(split_map) + '\n',
             'media-requests.jsonl': ''.join(canonical(row) + '\n' for row in requests)}
    for name, content in files.items():
        (output / name).write_text(content, encoding='utf-8')
    report = {'status': 'annotation_audit_only', 'model_acceptance': False, 'images_acquired': False,
              'labels_approved': False, 'source_url': SOURCE_URL, 'source_archive_sha256': archive_hash,
              'seed': seed, 'transform_version': VERSION, 'counts': dict(zip(('train', 'validation', 'test'), counts)),
              'classes': dict(Counter(row['evidence']['label_ko'] for row in provenance)),
              'sha256': {name: hashlib.sha256(content.encode()).hexdigest() for name, content in files.items()},
              'notes': ['Candidate bbox-center labels, not original translated GQA questions.',
                        'Acquire matching images and visually review every label before approval.',
                        'This subset is not an official GQA benchmark or a temporal memory evaluation.']}
    (output / 'manifest.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    return report
