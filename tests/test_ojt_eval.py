import json

from live.ojt_eval import examples, grade


def test_observation_tasks_do_not_promote_history_to_current(tmp_path):
    row = {'id':'gqa:1:1:current','images':[{'path':'a.jpg','view':'current','captured_at_ms':0}],
           'question_at_ms':0,'prompt':'컵 위치','response':'현재 관측에서 컵의 중심은 화면 왼쪽 구역에 있습니다.'}
    p = tmp_path/'data.jsonl';p.write_text(json.dumps(row)+'\n')
    current, history, unknown = examples(p)
    assert current['images'][0]['view']=='current'
    assert all(r['images'][0]['view']=='history' and r['question_at_ms']==10000 for r in [history,unknown])
    assert not grade(unknown, '지금 화면 왼쪽입니다.')['rule_pass']
    assert grade(unknown, '현재 위치는 확인할 수 없습니다.')['rule_pass']
    assert not grade(history, '현재 왼쪽입니다.')['rule_pass']
    assert not grade(current, '왼쪽 또는 오른쪽입니다.')['rule_pass']


def test_visor_timestamps_and_tasks_are_preserved(tmp_path):
    row = {'id':'visor:P01_03:0:history','question_at_ms':4312,
           'images':[{'path':'a.jpg','view':'history','captured_at_ms':1300}],
           'prompt':'이전에 본 컵 위치','response':'왼쪽에 있었습니다.'}
    p=tmp_path/'data.jsonl';p.write_text(json.dumps(row)+'\n')
    assert examples(p)==[{**row,'task':'history'}]
