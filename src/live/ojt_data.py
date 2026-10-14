"""CPU-only preparation of session-disjoint VLM fine-tuning datasets."""
from __future__ import annotations

import hashlib
import json
import math
from pathlib import Path


SPLITS = ('train', 'validation', 'test')


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'))


def digest(value):
    return hashlib.sha256(canonical(value).encode()).hexdigest()


def read_records(source: Path, media_root: Path):
    """Validate local media and explicit current/history evidence; never load a model."""
    records, ids = [], set()
    root = media_root.resolve(strict=True)
    if not root.is_dir():
        raise ValueError('media root must be a directory')
    with source.open(encoding='utf-8') as handle:
        for line_number, line in enumerate(handle, 1):
            if not line.strip():
                continue
            try:
                row = json.loads(line)
                if not isinstance(row, dict):
                    raise ValueError('record must be an object')
                if set(row) != {'id', 'group_id', 'prompt', 'response', 'question_at_ms', 'images'}:
                    raise ValueError('expected id, group_id, prompt, response, question_at_ms, images')
                for key in ('id', 'group_id', 'prompt', 'response'):
                    if not isinstance(row[key], str) or not row[key].strip():
                        raise ValueError(f'{key} must be nonempty text')
                if row['id'] in ids:
                    raise ValueError('duplicate id')
                question_time = row['question_at_ms']
                if type(question_time) not in (int, float) or not math.isfinite(question_time) or question_time < 0:
                    raise ValueError('question_at_ms must be finite and nonnegative')
                if not isinstance(row['images'], list) or len(row['images']) > 3:
                    raise ValueError('images must be a list of at most 3 observations')
                current_count = history_count = 0
                observations = []
                for item in row['images']:
                    if not isinstance(item, dict) or set(item) != {'path', 'view', 'captured_at_ms'}:
                        raise ValueError('image requires path, view, captured_at_ms')
                    if item['view'] not in ('current', 'history'):
                        raise ValueError('view must be current or history')
                    captured = item['captured_at_ms']
                    if type(captured) not in (int, float) or not math.isfinite(captured) or captured < 0:
                        raise ValueError('captured_at_ms must be finite and nonnegative')
                    age = question_time - captured
                    if age < 0 or (item['view'] == 'current' and age > 3000):
                        raise ValueError('future observation or stale current frame')
                    current_count += item['view'] == 'current'
                    history_count += item['view'] == 'history'
                    if not isinstance(item['path'], str) or not item['path'] or Path(item['path']).is_absolute():
                        raise ValueError('image path must be relative to media root')
                    path = (root / item['path']).resolve(strict=True)
                    if not path.is_relative_to(root) or not path.is_file():
                        raise ValueError('image must be a file inside media root')
                    from PIL import Image
                    with Image.open(path) as image:
                        if image.format != 'JPEG' or max(image.size) > 1280:
                            raise ValueError('expected JPEG up to 1280 pixels')
                        image.verify()
                    observations.append({**item, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()})
                if current_count > 1 or history_count > 2:
                    raise ValueError('at most 1 current and 2 historical images')
                ids.add(row['id'])
                records.append({**row, 'images': observations})
            except (ValueError, OSError, TypeError) as exc:
                raise ValueError(f'line {line_number}: {exc}') from exc
    if not records:
        raise ValueError('dataset is empty')
    return records


def split_records(records, seed=42, assignments=None, provenance=None):
    """Split by capture session/subject group, detecting exact cross-split leakage."""
    groups = sorted({r['group_id'] for r in records}, key=lambda g: (digest([seed, g]), g))
    if len(groups) < 3:
        raise ValueError('at least 3 independent groups are required for train/validation/test')
    if assignments is None:
        held_out = max(1, len(groups) // 10)
        assignments = {group: ('test' if i < held_out else 'validation' if i < held_out * 2 else 'train')
                       for i, group in enumerate(groups)}
    if (not isinstance(assignments, dict) or set(assignments) != set(groups)
            or any(value not in SPLITS for value in assignments.values())
            or set(assignments.values()) != set(SPLITS)):
        raise ValueError('split map must cover exactly all groups and all three splits')
    splits = {name: [] for name in SPLITS}
    seen_media, seen_examples, seen_sources = {}, {}, {}
    for row in sorted(records, key=lambda r: r['id']):
        split = assignments[row['group_id']]
        # Ignore identifiers, file names and clock origin when checking identical examples.
        fingerprint = digest([row['prompt'], row['response'],
                              [(im['sha256'], im['view'], row['question_at_ms'] - im['captured_at_ms'])
                               for im in row['images']]])
        source_keys = ([*provenance[row['id']]['leakage_keys'],
                        canonical([provenance[row['id']]['source'], provenance[row['id']]['source_item']])]
                       if provenance else [])
        for registry, keys in ((seen_examples, [fingerprint]), (seen_sources, source_keys),
                               (seen_media, [im['sha256'] for im in row['images']])):
            for key in keys:
                if key in registry and registry[key] != split:
                    raise ValueError('identical example, image or source identity crosses splits; fix group_id before training')
                registry[key] = split
        splits[split].append(row)
    return splits


def read_provenance(path, records, assignments, allow_unreviewed=False):
    """Require one auditable source row per example and preserve official holdouts."""
    rows = {}
    for line in path.read_text(encoding='utf-8').splitlines():
        if not line.strip():
            continue
        item = json.loads(line)
        fields = ('id', 'source', 'source_release', 'source_url', 'source_sha256',
                  'source_split', 'source_item', 'transform_version', 'review_status')
        if not isinstance(item, dict) or any(not isinstance(item.get(k), str) or not item[k].strip() for k in fields):
            raise ValueError('provenance requires nonempty source, revision, identity and review fields')
        if item['id'] in rows:
            raise ValueError('duplicate provenance id')
        sha = item['source_sha256']
        if len(sha) != 64 or any(c not in '0123456789abcdef' for c in sha):
            raise ValueError('source_sha256 must be a SHA256 digest')
        keys = item.get('leakage_keys')
        if not isinstance(keys, list) or not keys or any(not isinstance(k, str) or not k.strip() for k in keys):
            raise ValueError('provenance needs source identity leakage_keys')
        if item['review_status'] not in ('pending', 'approved', 'rejected'):
            raise ValueError('invalid review_status')
        if item['review_status'] == 'rejected' or (not allow_unreviewed and item['review_status'] != 'approved'):
            raise ValueError('provenance is not approved; use audit mode for pending labels')
        rows[item['id']] = item
    if set(rows) != {r['id'] for r in records}:
        raise ValueError('provenance must cover exactly all record ids')
    for row in records:
        item = rows[row['id']]
        original = {**row, 'images': [{k: v for k, v in image.items() if k != 'sha256'} for image in row['images']]}
        if item.get('record_sha256') != digest(original):
            raise ValueError('record content changed after provenance was created')
        if item['review_status'] == 'approved':
            expected_media = {im['path']: im['sha256'] for im in row['images']}
            if item.get('media_sha256') != expected_media:
                raise ValueError('approved provenance must match the reviewed image hashes')
        # This OJT uses official train for train/dev and public val for frozen test.
        allowed = {'train': ('train', 'validation'), 'val': ('test',)}
        if assignments.get(row['group_id']) not in allowed.get(item['source_split'], ()):
            raise ValueError('official source split conflicts with OJT split policy')
    return rows


def prepare(source: Path, media_root: Path, output: Path, models: Path, seed=42, fixture=False,
            split_map: Path | None = None, provenance: Path | None = None, allow_unreviewed=False):
    records = read_records(source, media_root)
    if (split_map is None) != (provenance is None):
        raise ValueError('split map and provenance must be provided together')
    assignments = json.loads(split_map.read_text()) if split_map else None
    if assignments is not None and not isinstance(assignments, dict):
        raise ValueError('split map must be an object')
    sources = read_provenance(provenance, records, assignments, allow_unreviewed) if provenance else None
    splits = split_records(records, seed, assignments, sources)
    pinned = json.loads(models.read_text())
    for name in ('asr', 'vlm', 'tts'):
        model = pinned[name]
        revision = model['revision']
        if not isinstance(revision, str) or len(revision) != 40 or any(c not in '0123456789abcdef' for c in revision):
            raise ValueError(f'{name}: an immutable model revision is required')
    # Never silently overwrite an earlier experiment's split or provenance.
    output.mkdir(parents=True, exist_ok=False)
    split_hashes = {}
    for name, rows in splits.items():
        content = ''.join(canonical(row) + '\n' for row in rows)
        (output / f'{name}.jsonl').write_text(content, encoding='utf-8')
        split_hashes[name] = hashlib.sha256(content.encode()).hexdigest()
    report = {
        'schema_version': 2, 'seed': seed, 'fixture': fixture,
        'status': 'data_prepared_only',
        'model_acceptance': False,
        'labels_approved': bool(sources) and all(r['review_status'] == 'approved' for r in sources.values()),
        'split_mode': 'explicit' if assignments is not None else 'automatic_fixture_or_local',
        'split_map_sha256': hashlib.sha256(split_map.read_bytes()).hexdigest() if split_map else None,
        'provenance_sha256': hashlib.sha256(provenance.read_bytes()).hexdigest() if provenance else None,
        'source_sha256': hashlib.sha256(source.read_bytes()).hexdigest(),
        'source_path': str(source.resolve()), 'media_root': str(media_root.resolve()),
        'models': {name: pinned[name] for name in ('asr', 'vlm', 'tts')},
        'counts': {name: len(rows) for name, rows in splits.items()},
        'group_counts': {name: len({r['group_id'] for r in rows}) for name, rows in splits.items()},
        'split_sha256': split_hashes,
        'calibration_split': 'train', 'qat_training_split': 'train',
        'notes': ['Split is deterministic for the same complete input and seed; freeze it before experiments.',
                  'Exact duplicate detection does not detect near-duplicate scenes or subject leakage.',
                  'Native trainer conversion, rights/consent review and human label review are still required.'],
    }
    if sources:
        (output / 'provenance.jsonl').write_bytes(provenance.read_bytes())
        (output / 'split-map.json').write_bytes(split_map.read_bytes())
    (output / 'manifest.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    return report
