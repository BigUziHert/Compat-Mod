"""Assert opt-in Sable reduced-tick runtime observations, optionally against a full-tick run.

Run each fixture with -Demfattachments.sableReducedTickAudit=true. A forced
baseline also uses -Demfattachments.sableFullTicks=true. --expect-full-fallback
instead verifies full ticks with that property absent. Trigger the existing
injected or natural Sable occlusion sequence in a disposable .local/client directory.
"""
import argparse
import hashlib
import json
from collections import defaultdict
from pathlib import Path


ATTACHED = {'ATTACHED', 'PENDING_PLOT', 'INVISIBLE_ATTACHED'}


def verify(path, expected_full, expected_control=None):
    path = Path(path).resolve()
    report = json.loads(path.read_text(encoding='utf-8-sig'))
    checks = []

    def check(name, passed, detail=None):
        checks.append({'check': name, 'passed': bool(passed), 'detail': detail})

    check('completed capture', report.get('status') == 'CAPTURED', report.get('status'))
    check('opt-in audit enabled', report.get('reducedTickAudit') is True)
    if expected_control is None:
        expected_control = expected_full
    check('expected full-tick control property', report.get('fullTickControl') is expected_control)
    check('production mixins enabled', report.get('productionMixinsDisabled') is False)
    for key in ('platformRemoved', 'witnessesRemoved', 'entityCullingStateRestored',
                'inputsRestored', 'cameraRestored', 'fovRestored', 'serverStateRestored',
                'wallServerRestored', 'wallClientRestored', 'removedControlReferenceActuallyRemoved'):
        check(key, report.get(key) is True)
    rows = report.get('tickObservations', [])
    check('tick observations captured', len(rows) >= 1000, len(rows))
    groups = defaultdict(list)
    for row in rows:
        groups[(row['stage'], row['kind'])].append(row)
    check('one aiStep per tick', all(r['aiStepDelta'] == 1 for r in rows),
          [r for r in rows if r['aiStepDelta'] != 1][:4])
    check('one tickCount increment per tick', all(r['nativeTickDelta'] == 1 for r in rows),
          [r for r in rows if r['nativeTickDelta'] != 1][:4])
    check('at most one full Entity.tick per tick', all(r['fullEntityTickDelta'] in (0, 1) for r in rows))
    for field, tolerance in (('projectionError', 1e-5), ('plotInterpolationError', 1e-6),
                             ('yawInterpolationError', 1e-4), ('pitchInterpolationError', 1e-4)):
        observed = [r[field] for r in rows if field in r]
        check(field + ' observed', len(observed) >= 100, len(observed))
        check(field + ' within tolerance', bool(observed) and max(observed) <= tolerance,
              {'max': max(observed, default=None), 'tolerance': tolerance})
    packet_rows = [r for r in rows if r['packetStepsBefore'] > 0]
    check('packet interpolation exercised', len(packet_rows) >= 90, len(packet_rows))
    check('all packet interpolation steps witnessed',
          {r['packetStepsBefore'] for r in packet_rows} == {1, 2, 3})
    projected = [r for r in rows if 'expectedWorld' in r]
    check('live plot establishes tracking', all(r['trackingAfter'] and not r['trackingRemovedAfter'] for r in projected))
    first_pending = next((r for r in rows if r['kind'] == 'PENDING_PLOT'), None)
    check('pending fixture begins without tracking', first_pending is not None and first_pending['trackingBefore'] == '')
    check('pending fixture receives full initialization tick', first_pending is not None and first_pending['fullEntityTickDelta'] == 1)

    natural = report.get('naturalWorker') is True
    hidden_stages = ['natural-hidden-rotating', 'natural-camera-away'] if natural else ['injected-culled-and-out-of-camera-moving']
    reduced_count = 0
    for stage in hidden_stages:
        for kind in ATTACHED:
            stage_rows = [r for r in groups[(stage, kind)] if r['stageTick'] >= 30]
            culled = [r for r in stage_rows if r['culledBefore'] or r['outOfCameraBefore']]
            reduced = [r for r in culled if r['fullEntityTickDelta'] == 0]
            reduced_count += len(reduced)
            # The full-tick control exits EC's tick callback at its exemption
            # branch, before setOutOfCamera(true). Looking away therefore does
            # not set either flag in that baseline. Require actual full ticks,
            # motion, camera orientation and suppressed rendering instead.
            baseline_camera_away = expected_full and natural and stage == 'natural-camera-away'
            coverage = stage_rows if baseline_camera_away else culled
            check(stage + '/' + kind + ' hidden tick coverage', len(coverage) >= 20, len(coverage))
            check(stage + '/' + kind + (' full baseline ticks' if expected_full else ' reduced ticks'),
                  bool(stage_rows) and all(r['fullEntityTickDelta'] == 1 for r in stage_rows)
                  if expected_full else len(reduced) >= 20, len(reduced))
            positions = [r['positionAfter'] for r in coverage]
            displacement = max((sum((b - a) ** 2 for a, b in zip(positions[0], p)) ** .5
                                for p in positions), default=0) if positions else 0
            check(stage + '/' + kind + ' follows moving deck', displacement > .01, displacement)

    stage_rows = report.get('stages', [])
    hidden_render_stage = hidden_stages[0]
    for stage in hidden_stages:
        for kind in ATTACHED:
            row = next((r for r in stage_rows if r['stage'] == stage and r['kind'] == kind), {})
            check(stage + '/' + kind + ' hidden render suppressed', row.get('settledPlayerRendererReturns') == 0)
    visible_stage = 'natural-camera-reentry-and-board' if natural else 'cleared-flags-moving-native-control'
    for kind in ('ATTACHED', 'PENDING_PLOT'):
        row = next((r for r in stage_rows if r['stage'] == visible_stage and r['kind'] == kind), {})
        check(visible_stage + '/' + kind + ' visible renderer returns', row.get('settledPlayerRendererReturns', 0) > 0)
        check(visible_stage + '/' + kind + ' visible full ticks resume', row.get('fullEntityTicks', 0) > 0)
    invisible = [r for r in stage_rows if r['kind'] == 'INVISIBLE_ATTACHED']
    check('actual invisibility preserved', bool(invisible) and all(r['visibleSkinDecisions'] == 0 for r in invisible))
    detached_stage = 'natural-detached-hidden' if natural else 'detached-injected-cull-control'
    removal_stage = 'natural-unloaded-stale-reference' if natural else 'removed-sublevel-stale-reference-control'
    for stage in (detached_stage, removal_stage):
        settled = [r for r in rows if r['stage'] == stage and r['stageTick'] >= 5 and r['kind'] in ATTACHED]
        check(stage + ' settled coverage', len(settled) >= 100, len(settled))
        check(stage + ' clears plot and stale tracking', bool(settled) and all(
            not r['plotAfter'] and not r['trackingAfter'] and not r['trackingRemovedAfter'] for r in settled))
    ordinary_stage = hidden_stages[0]
    ordinary = [r for r in groups[(ordinary_stage, 'ORDINARY_WORLD')] if r['stageTick'] >= 30]
    check('ordinary-world tick culling preserved', sum(r['fullEntityTickDelta'] == 0 for r in ordinary) >= 20)
    exempt = [r for r in rows if r['kind'] == 'NATIVE_EXEMPT']
    check('native exemption preserved', bool(exempt) and all(r['fullEntityTickDelta'] == 1 for r in exempt))
    if natural:
        away_samples = [r for r in report.get('samples', []) if r['stage'] == 'natural-camera-away' and r['stageTick'] >= 30]
        check('camera actually faces away', bool(away_samples) and all(abs(r['cameraYaw'] - 180) < .1 for r in away_samples))
        hidden_samples = [r for r in report.get('samples', []) if r['stage'] == hidden_render_stage and r['stageTick'] >= 30 and r['kind'] == 'ATTACHED']
        check('real wall blocks line of sight', bool(hidden_samples) and all(r['wallBlocksSight'] for r in hidden_samples))
        rotations = {tuple(r['platformRotationQuaternion']) for r in hidden_samples if 'platformRotationQuaternion' in r}
        check('rotating platform observed', len(rotations) > 5, len(rotations))
    return {'report': str(path), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
            'natural': natural, 'fullTickControl': expected_control,
            'expectedFullTicks': expected_full, 'observations': len(rows),
            'hiddenReducedTicks': reduced_count, 'passed': all(c['passed'] for c in checks), 'checks': checks}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('report', type=Path)
    parser.add_argument('--baseline', type=Path, help='matching audit with sableFullTicks=true')
    full_mode = parser.add_mutually_exclusive_group()
    full_mode.add_argument('--expect-full', action='store_true', help='verify a forced full-tick baseline report')
    full_mode.add_argument('--expect-full-fallback', action='store_true',
                           help='verify full ticks from the default fallback, with no forced-control property')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    result = verify(args.report, args.expect_full or args.expect_full_fallback,
                    expected_control=False if args.expect_full_fallback else None)
    if args.baseline:
        baseline = verify(args.baseline, True)
        result['baseline'] = baseline
        result['matchingFixture'] = result['natural'] == baseline['natural']
        result['passed'] = result['passed'] and baseline['passed'] and result['matchingFixture']
    text = json.dumps(result, indent=2) + '\n'
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text, encoding='utf-8')
    summary = {'passed': result['passed'], 'observations': result['observations'],
               'hiddenReducedTicks': result['hiddenReducedTicks'],
               'failed': [c for c in result['checks'] if not c['passed']]}
    if 'baseline' in result:
        summary['matchingFixture'] = result['matchingFixture']
        summary['baseline'] = {'passed': baseline['passed'], 'observations': baseline['observations'],
                               'hiddenReducedTicks': baseline['hiddenReducedTicks'],
                               'failed': [c for c in baseline['checks'] if not c['passed']]}
    print(json.dumps(summary, indent=2))
    raise SystemExit(0 if result['passed'] else 1)


if __name__ == '__main__':
    main()
