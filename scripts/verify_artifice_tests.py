"""Check complete native gameplay records; visual alignment still needs image review."""
import argparse
import hashlib
import json
from pathlib import Path

from summarize_artifice_tests import summarize


def verify(path, fixed):
    raw = json.loads(path.read_text(encoding='utf-8'))
    result = summarize(path)
    failures = []

    def check(condition, message):
        if not condition:
            failures.append(message)

    check(raw.get('finished') is True and raw['status'] == 'CAPTURED', 'Native lifecycle did not finish')
    check(raw.get('completedContexts') == raw.get('plannedContexts'), 'Incomplete context matrix')
    for name in ('inventoryAccessoriesRestored', 'clientSettingsRestored', 'arenaRestored', 'projectileEntitiesCleaned'):
        check(raw.get(name) is True, 'Cleanup: ' + name)
    for index, case in enumerate(raw.get('cases', []), 1):
        check(case['status'] == 'CAPTURED', f'Case {index}: incomplete')
        for name in ('reloadVerified', 'sprintReloadVerified', 'nativeRollVerified', 'nativeShotVerified', 'switchAwayVerified'):
            check(case.get(name) is True, f'Case {index}: missing {name}')
        for name in ('walkDistance', 'sprintDistance', 'sprintReloadDistance'):
            check(case.get(name, 0) >= .3, f'Case {index}: no observed {name}')
        captures = [c for c in raw['captures'] if c['case'] == index]
        for phase in ('reload_early', 'reload_late', 'sprint_reload_early', 'sprint_reload_late'):
            frames = [c for c in captures if c['name'] == phase]
            check(len(frames) == 1 and frames[0]['reloading'], f'Case {index}: missing actual {phase}')
            if phase.startswith('sprint_') and frames:
                check(frames[0]['sprinting'] and frames[0]['server']['sprinting'],
                      f'Case {index}: client/server did not sprint during {phase}')

    for frame in result['frames']:
        label = frame['screenshot']
        check((path.parent / label).is_file(), 'Missing screenshot: ' + label)
        check(frame['invalidHandVertices'] == 0, 'Nonfinite rendered hand geometry: ' + label)
        if not fixed:
            continue
        error = frame['armRotationScaleError']
        check(error is None or error < 1e-5, 'Native arm rotation/scale lost: ' + label)
        error = frame['armPositionError']
        check(error is None or error < 1e-5, 'Native torso-relative arm position lost: ' + label)
        if frame['view']['camera'] != 'FIRST_PERSON' or frame['nativeHands'] == 0:
            continue
        # Free-arm fixtures intentionally equip two different physical hands,
        # with independent hidden/cosmetic flags. The ownership verifier checks
        # each selected slot against the actual native/body arm that rendered it.
        if raw.get('freeArmMode'):
            continue
        if frame['view']['hidden']:
            check(frame['accessoryVertices'] == 0, 'Hidden glove emitted geometry: ' + label)
        else:
            check(frame['accessoryVertices'] > 0, 'Visible glove missing from native gun hands: ' + label)
            if frame['view']['cosmetic']:
                check(any('fire_gauntlet' in texture for texture in frame['accessoryTextures']),
                      'Cosmetic glove absent: ' + label)
                check(not any('power_glove' in texture for texture in frame['accessoryTextures']),
                      'Functional glove leaked through cosmetic: ' + label)
    result['checks'] = dict(passed=not failures, failures=failures, expectedFixed=fixed,
                            accessorySelection=('Requires verify_artifice_body_hands.py --mask ownership'
                                                if raw.get('freeArmMode') else 'Checked here'),
                            visualAlignment='Not an automated assertion; see separate original-image reviews.')
    result['reportSha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('report', type=Path)
    parser.add_argument('--fixed', action='store_true')
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    result = verify(args.report, args.fixed)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result['checks']))
    raise SystemExit(0 if result['checks']['passed'] else 1)
