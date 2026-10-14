import hashlib
import json
from pathlib import Path
import zipfile

from PIL import Image
import pytest

from live.ojt_data import canonical, digest, prepare
from live.ojt_gqa import build_audit, position_example


ROOT = Path(__file__).resolve().parents[1]


def scene(x=10):
    return {'width': 120, 'height': 80, 'objects': {
        '1': {'name': 'cup', 'x': x, 'y': 10, 'w': 10, 'h': 20}}}


def audit(tmp_path):
    archive = tmp_path / 'source.zip'
    with zipfile.ZipFile(archive, 'w') as handle:
        handle.writestr('train_sceneGraphs.json', canonical({'1': scene(), '2': scene(90)}))
        handle.writestr('val_sceneGraphs.json', canonical({'3': scene(50)}))
    output = tmp_path / 'audit'
    build_audit(archive, output, counts=(1, 1, 1))
    for i, color in enumerate(('red', 'green', 'blue'), 1):
        Image.new('RGB', (120, 80), color).save(tmp_path / f'{i}.jpg')
    return output


def prep(tmp_path, folder, **kw):
    return prepare(folder / 'records.jsonl', tmp_path, tmp_path / 'prepared', ROOT / 'configs/models.json',
                   split_map=folder / 'split-map.json', provenance=folder / 'provenance.jsonl', **kw)


def rewrite(path, transform):
    rows = [json.loads(line) for line in path.read_text().splitlines()]
    transform(rows)
    path.write_text(''.join(canonical(row) + '\n' for row in rows))


def test_real_source_contract_pending_review_and_hashes(tmp_path):
    folder = audit(tmp_path)
    with pytest.raises(ValueError, match='not approved'):
        prep(tmp_path, folder)
    assert not (tmp_path / 'prepared').exists()
    report = prep(tmp_path, folder, allow_unreviewed=True)
    assert report['counts'] == {'train': 1, 'validation': 1, 'test': 1}
    assert report['split_mode'] == 'explicit'
    assert not report['labels_approved'] and not report['model_acceptance']
    assert (tmp_path / 'prepared/provenance.jsonl').read_bytes() == (folder / 'provenance.jsonl').read_bytes()
    with pytest.raises(FileExistsError):
        build_audit(tmp_path / 'source.zip', folder, (1, 1, 1))


@pytest.mark.parametrize('failure', ['official_split', 'identity', 'missing_group', 'extra_id', 'changed_record', 'rejected'])
def test_invalid_provenance_never_creates_output(tmp_path, failure):
    folder = audit(tmp_path)
    p = folder / 'provenance.jsonl'
    if failure == 'official_split':
        rewrite(p, lambda rows: rows[-1].update(source_split='train'))
    if failure == 'identity':
        rewrite(p, lambda rows: rows[1].update(leakage_keys=rows[0]['leakage_keys']))
    if failure == 'extra_id':
        rewrite(p, lambda rows: rows.append({**rows[0], 'id': 'extra'}))
    if failure == 'rejected':
        rewrite(p, lambda rows: rows[0].update(review_status='rejected'))
    if failure == 'changed_record':
        rewrite(folder / 'records.jsonl', lambda rows: rows[0].update(response='different answer'))
    if failure == 'missing_group':
        path = folder / 'split-map.json'
        mapping = json.loads(path.read_text()); mapping.pop(next(iter(mapping)))
        path.write_text(canonical(mapping))
    with pytest.raises(ValueError):
        prep(tmp_path, folder, allow_unreviewed=True)
    assert not (tmp_path / 'prepared').exists()


def test_approved_source_and_seed_independent_explicit_split(tmp_path):
    folder = audit(tmp_path)
    rewrite(folder / 'provenance.jsonl', lambda rows: [row.update(
        review_status='approved', media_sha256={f'{row["source_item"]}.jpg':
            hashlib.sha256((tmp_path / f'{row["source_item"]}.jpg').read_bytes()).hexdigest()}) for row in rows])
    result = prep(tmp_path, folder, seed=99)
    assert result['labels_approved']
    mapping = json.loads((folder / 'split-map.json').read_text())
    for name in ('train', 'validation', 'test'):
        row = json.loads((tmp_path / 'prepared' / f'{name}.jsonl').read_text())
        assert mapping[row['group_id']] == name


def test_gqa_skips_ambiguous_invalid_and_boundary_boxes():
    value = scene()
    value['objects']['2'] = {**value['objects']['1'], 'name': 'coffee cup'}
    assert position_example('1', value) is None
    assert position_example('1', scene(x=-1)) is None
    assert position_example('1', scene(x=35)) is None
    assert position_example('1', scene(x=119)) is None
    with pytest.raises(ValueError, match='ASCII digits'):
        position_example('../1', scene())
    row, evidence = position_example('1', scene())
    assert '왼쪽' in row['response'] and '현재 관측' in row['prompt']
    assert evidence['clock'].startswith('synthetic')


@pytest.mark.parametrize('noun', ['plate', 'pot'])
def test_polysemous_gqa_names_require_disambiguation(noun):
    value = scene()
    value['objects']['1']['name'] = noun
    assert position_example('1', value) is None


def test_approval_cannot_silently_reuse_replaced_image(tmp_path):
    folder = audit(tmp_path)
    rewrite(folder / 'provenance.jsonl', lambda rows: [row.update(review_status='approved', media_sha256={}) for row in rows])
    with pytest.raises(ValueError, match='reviewed image hashes'):
        prep(tmp_path, folder)


def test_insufficient_source_does_not_leave_partial_audit(tmp_path):
    archive = tmp_path / 'empty.zip'
    with zipfile.ZipFile(archive, 'w') as handle:
        handle.writestr('train_sceneGraphs.json', '{}')
        handle.writestr('val_sceneGraphs.json', '{}')
    with pytest.raises(ValueError, match='insufficient'):
        build_audit(archive, tmp_path / 'out', (1, 1, 1))
    assert not (tmp_path / 'out').exists()
