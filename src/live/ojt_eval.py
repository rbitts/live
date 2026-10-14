"""Shared pilot examples and conservative keyword grading; not human semantic acceptance."""
import json
import re


def examples(path):
    """Static observations with explicit synthetic clocks; not physical video tracking."""
    result = []
    for line in path.read_text().splitlines():
        row = json.loads(line)
        if row['id'].startswith('visor:'):
            result.append({**row, 'task': row['id'].rsplit(':', 1)[-1]})
            continue
        result.append({**row, 'task': 'current'})
        label = row['response'].split('현재 관측에서 ')[1].split('의 중심')[0]
        location = next(word for word in ('왼쪽', '가운데', '오른쪽') if word in row['response'])
        history = [{**image, 'view': 'history', 'captured_at_ms': 0} for image in row['images']]
        result.append({**row, 'id': row['id'] + ':history', 'task': 'history', 'images': history,
                       'question_at_ms': 10000, 'prompt': f'마지막으로 본 {label}의 중심은 화면의 어느 구역에 있었나요?',
                       'response': f'10초 전 마지막 제공 관측에서 {label}의 중심은 화면 {location} 구역에 있었습니다.'})
        result.append({**row, 'id': row['id'] + ':unknown', 'task': 'unknown', 'images': history,
                       'question_at_ms': 10000, 'prompt': f'지금 {label}은 어디 있나요?',
                       'response': f'현재 화면이 없어 지금 위치는 확인할 수 없습니다. 10초 전 마지막 제공 관측에서는 화면 {location} 구역에 있었습니다.'})
    return result



def grade(row, prediction):
    region = next(word for word in ('왼쪽', '가운데', '오른쪽') if word in row['response'])
    observed = {word for word in ('왼쪽', '가운데', '오른쪽') if word in prediction}
    if re.search(r'중앙|중간|중심\s*구역|^\s*중심\s*$', prediction):
        observed.add('가운데')
    region_ok = observed == {region}
    denial = any(word in prediction for word in ('확인할 수 없', '알 수 없', '모르', '보이지 않', '화면이 없', '영상이 없', '제공되지 않'))
    past = any(word in prediction for word in ('전', '관측', '있었', '마지막'))
    ok = region_ok
    if row['task'] == 'history':
        ok = ok and not any(word in prediction for word in ('지금', '현재'))
    if row['task'] == 'unknown':
        ok = denial and (not observed or region_ok)
    return {'rule_pass': ok, 'region_match': region_ok, 'abstention_marker': denial, 'past_marker': past}


