#!/usr/bin/env python3
"""Summarize raw fixed-scenario measurements plus explicit, answer-bound reviews.

No model judge. Recipe quality is reviewed against the preregistered rubric and
bound to the exact answer hash, so changed answers cannot reuse old ratings.
"""
import argparse
from collections import defaultdict
import hashlib
import json
from pathlib import Path
import statistics
import unicodedata


def normalized(text):
    return ''.join(c for c in unicodedata.normalize('NFC', text)
                   if not c.isspace() and not unicodedata.category(c).startswith('P'))


def semantic_form(text):
    # Only equivalent numeric spelling; never "repair" misspelled recognized speech.
    return normalized(text).replace('30초', '삼십초').replace('20초', '이십초').replace('10초', '십초')


def distance(reference, hypothesis):
    row = list(range(len(hypothesis) + 1))
    for i, left in enumerate(reference, 1):
        new = [i]
        for j, right in enumerate(hypothesis, 1):
            new.append(min(new[-1] + 1, row[j] + 1, row[j-1] + (left != right)))
        row = new
    return row[-1]


def ratio(passed, total):
    return {'passed': passed, 'total': total,
            'percent': round(100 * passed / total, 2) if total else None}


def summarize(data, reviews):
    by_key = defaultdict(list)
    all_rows = []
    for run in data['rounds']:
        for row in run['turns']:
            by_key[row['key']].append(row)
            all_rows.append(row)
    def asr_pass(row, normalizer):
        return row.get('asr_final', False) and normalizer(row.get('recognized', '')) == normalizer(row['input'])
    edits = sum(distance(normalized(r['input']), normalized(r.get('recognized', ''))) for r in all_rows)
    characters = sum(len(normalized(r['input'])) for r in all_rows)
    summary = {
        'status': data['status'], 'planned_rounds': data['planned_rounds'],
        'completed_rounds': sum(r['status'] == 'completed' for r in data['rounds']),
        'observed_turns': len(all_rows),
        'final_asr': ratio(sum(r.get('asr_final', False) for r in all_rows), len(all_rows)),
        'asr_exact': ratio(sum(asr_pass(r, normalized) for r in all_rows), len(all_rows)),
        'asr_numeric_equivalent': ratio(sum(asr_pass(r, semantic_form) for r in all_rows), len(all_rows)),
        'cer': {'edits': edits, 'reference_characters': characters,
                'percent': round(100 * edits / characters, 2) if characters else None},
        'by_phrase': {},
    }
    for key, rows in by_key.items():
        summary['by_phrase'][key] = {
            'input': rows[0]['input'],
            'asr_exact': ratio(sum(asr_pass(r, normalized) for r in rows), len(rows)),
            'asr_numeric_equivalent': ratio(sum(asr_pass(r, semantic_form) for r in rows), len(rows)),
            'recognized': [r.get('recognized', '') for r in rows],
        }
    timer_pass = []
    for key, goal in [('start30', '30초 타이머'), ('change10', '10초 타이머'), ('change20', '20초 타이머')]:
        rows = by_key[key]
        results = [r.get('asr_final', False) and r.get('goal') == goal for r in rows]
        summary['by_phrase'].setdefault(key, {})['task_success'] = ratio(sum(results), len(results))
        timer_pass.extend(results)
    summary['timer_task_success'] = ratio(sum(timer_pass), len(timer_pass))
    core = []
    for run in data['rounds']:
        passed = (run.get('changed_to20') and run.get('old_timer_cancelled')
                  and run.get('spontaneous_result') and '20초 타이머가 끝났어요' in run.get('first_result', '')
                  and run.get('held') and run.get('pending_after_stop') == 1
                  and run.get('delivered_after_stop') == 0 and run.get('no_intrusion_while_held')
                  and run.get('result_delivered_once') and run.get('no_duplicate'))
        core.append(bool(passed))
    summary['reentry_complete_flow'] = ratio(sum(core), len(core))
    interrupted = [r for r in all_rows if r.get('during_playback')]
    summary['stale_pcm_rejection_during_playback'] = ratio(
        sum(bool(r.get('onset') and r.get('old_pcm_rejected')) for r in interrupted), len(interrupted))
    summary['intentional_playback_interruptions'] = len(interrupted)
    review_map = {r['round']: r for r in reviews}
    quality = []
    for run in data['rounds']:
        recipes = [r for r in run['turns'] if r['key'] == 'recipe']
        if not recipes:
            continue
        answer = recipes[0].get('response', '')
        review = review_map[run['round']]
        if review['answer_sha256'] != hashlib.sha256(answer.encode()).hexdigest():
            raise ValueError('Review does not match the recorded answer')
        quality.append(bool(review['pass']))
    summary['recipe_quality'] = ratio(sum(quality), len(quality))
    recipes = by_key['recipe']
    summary['recipe_audio_delivered'] = ratio(sum(r.get('audio_delivered', False) for r in recipes), len(recipes))
    summary['complete_scenario_including_quality'] = ratio(sum(
        c and all(x.get('goal') == g for k,g in [('start30','30초 타이머'),('change10','10초 타이머'),('change20','20초 타이머')]
                  for x in run['turns'] if x['key'] == k) and review_map.get(run['round'], {}).get('pass', False)
        for c,run in zip(core, data['rounds'])), len(data['rounds']))
    values = [r['first_write_ms'] for r in recipes if r.get('audio_started') and r.get('first_write_ms', -1) >= 0]
    summary['recipe_first_write_ms'] = {
        'values': values, 'median': statistics.median(values) if values else None,
        'maximum': max(values) if values else None,
    }
    return summary


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('raw', type=Path)
    parser.add_argument('reviews', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = summarize(json.loads(args.raw.read_text()), json.loads(args.reviews.read_text()))
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
