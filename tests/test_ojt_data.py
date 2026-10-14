import json
from pathlib import Path

import pytest
from PIL import Image

from live.ojt_data import prepare, read_records, split_records


ROOT = Path(__file__).resolve().parents[1]


def rows():
    return [json.loads(line) for line in (ROOT / 'tests/fixtures/ojt-text.jsonl').read_text().splitlines()]


def source(tmp_path, records):
    path = tmp_path / 'input.jsonl'
    path.write_text(''.join(json.dumps(row) + '\n' for row in records))
    return path


def test_reproducible_group_split_and_frozen_manifest(tmp_path):
    records = rows()
    records.append({**records[0], 'id': 'another-turn', 'prompt': '같은 세션의 다른 질문'})
    path = source(tmp_path, records)
    a = prepare(path, tmp_path, tmp_path / 'a', ROOT / 'configs/models.json', fixture=True)
    b = prepare(path, tmp_path, tmp_path / 'b', ROOT / 'configs/models.json', fixture=True)
    assert a == b and not a['model_acceptance'] and a['fixture']
    assert sum(a['group_counts'].values()) == 3
    split = split_records(read_records(path, tmp_path))
    assert split == split_records(list(reversed(read_records(path, tmp_path))))
    assert any(len(part) == 2 for part in split.values())
    with pytest.raises(FileExistsError):
        prepare(path, tmp_path, tmp_path / 'a', ROOT / 'configs/models.json')


@pytest.mark.parametrize('mutation', ['future', 'stale', 'escape', 'missing', 'nan', 'duplicate'])
def test_invalid_data_is_rejected_before_writing(tmp_path, mutation):
    Image.new('RGB', (8, 8), 'red').save(tmp_path / 'frame.jpg')
    records = rows()
    records[0]['images'] = [{'path': 'frame.jpg', 'view': 'current', 'captured_at_ms': 0}]
    if mutation == 'future': records[0]['images'][0]['captured_at_ms'] = 1001
    if mutation == 'stale': records[0]['question_at_ms'] = 3001
    if mutation == 'escape': records[0]['images'][0]['path'] = '../outside.jpg'
    if mutation == 'missing': records[0]['images'][0]['path'] = 'missing.jpg'
    if mutation == 'nan': records[0]['question_at_ms'] = float('nan')
    if mutation == 'duplicate': records[1]['id'] = records[0]['id']
    with pytest.raises(ValueError):
        prepare(source(tmp_path, records), tmp_path, tmp_path / 'out', ROOT / 'configs/models.json')
    assert not (tmp_path / 'out').exists()


def test_copied_image_and_example_cannot_leak_across_splits(tmp_path):
    Image.new('RGB', (8, 8), 'red').save(tmp_path / 'frame.jpg')
    (tmp_path / 'copy.jpg').write_bytes((tmp_path / 'frame.jpg').read_bytes())
    records = rows()
    for row, name in zip(records, ['frame.jpg', 'copy.jpg']):
        row['images'] = [{'path': name, 'view': 'current', 'captured_at_ms': 0}]
    with pytest.raises(ValueError, match='crosses splits'):
        split_records(read_records(source(tmp_path, records), tmp_path))
    records = rows()
    records[1] = {**records[0], 'id': 'copy', 'group_id': 'another-session'}
    with pytest.raises(ValueError, match='crosses splits'):
        split_records(read_records(source(tmp_path, records), tmp_path))


def test_old_image_is_allowed_only_as_history(tmp_path):
    Image.new('RGB', (8, 8), 'red').save(tmp_path / 'frame.jpg')
    records = rows()
    records[0]['question_at_ms'] = 10000
    records[0]['images'] = [{'path': 'frame.jpg', 'view': 'history', 'captured_at_ms': 0}]
    valid = read_records(source(tmp_path, records), tmp_path)
    assert valid[0]['images'][0]['view'] == 'history'
    assert len(valid[0]['images'][0]['sha256']) == 64


def test_too_few_groups_rejected(tmp_path):
    with pytest.raises(ValueError, match='3 independent groups'):
        split_records(read_records(source(tmp_path, rows()[:2]), tmp_path))
