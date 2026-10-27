"""Read-only numeric audit of the bounded native arm matrix and controlled hook.

Does not launch Minecraft, mutate fixtures, or grade screenshots.
"""
from pathlib import Path
from collections import defaultdict, Counter
import argparse, hashlib, json, math, struct


def f32(value):
    return struct.unpack('f', struct.pack('f', value))[0]


def expected_targets(duration):
    duration = f32(duration)
    last = math.ceil(duration) - 1
    if last < 5:
        raise ValueError('TOO_SHORT_NATIVE_WINDOW')
    result = []
    previous = 0
    for i, reference in enumerate([3, 5, 7, 9, 11]):
        rounded = math.floor(f32(reference * duration) / (100 / 7) + .5)
        age = max(previous + 1, min(rounded, last - (4 - i)))
        result.append(age)
        previous = age
    return result


def timing_summary(rows):
    """Measured captured frames only; does not infer the cadence of unseen frames."""
    summary = {}
    for key in ('renderFrameIntervalMs', 'captureChecksAndSnapshotMs', 'screenshotReadbackMs'):
        values = sorted(float(row[key]) for row in rows if isinstance(row.get(key), (int, float)))
        if values:
            summary[key] = dict(samples=len(values), minimum=min(values), maximum=max(values),
                                mean=sum(values) / len(values), median=values[len(values) // 2],
                                p95=values[min(len(values) - 1, math.ceil(len(values) * .95) - 1)])
    return summary


def matrix(path):
    data = json.loads(path.read_text())
    failures, pending = [], []
    rows = data.get('captures', [])
    cases, swings = defaultdict(list), defaultdict(list)
    restored = {key: value for key, value in data.items() if 'Restored' in key or 'Reconciled' in key}
    def require(ok, description):
        if not ok: failures.append(description)
    require(data.get('armVisibilityMatrix') is True, 'Report is not the native arm visibility matrix')
    final = data.get('status') == 'CAPTURED'
    for n, row in enumerate(rows):
        prefix = f'capture {n+1}/{row.get("case")}/{row.get("phase")}'
        cases[row.get('caseNumber')].append(row)
        active = row.get('nativeAttackActive') is True
        fp = row.get('camera') == 'FIRST_PERSON'
        expected_show = row.get('requestedNativeArms')
        require(row.get('mode') == 'BASELINE', prefix + ': non-baseline control')
        require(row.get('nativeArmsEnabled') == expected_show, prefix + ': native config does not match requested cached setting')
        geometry = row.get('armGeometry', {})
        counts = geometry.get('counts', {})
        def total(label, key):
            return sum(value.get(key, 0) for category, value in counts.items() if category.startswith(label))
        models = geometry.get('rendererModels', [])
        require(bool(models), prefix + ': actual native renderer model missing')
        requested_skin = geometry.get('requestedSkin')
        require(requested_skin in ['default', 'slim'], prefix + ': requested skin invalid')
        for model in models:
            require(model.get('actualSlim') == (requested_skin == 'slim')
                    and model.get('reportedSkin') == requested_skin, prefix + ': native renderer slim/skin mismatch')
        require(total('', 'compiledWhileMasked') == 0, prefix + ': a masked arm compiled')
        wearable = row.get('wearable', '')
        if active and fp and expected_show is False:
            require(total('', 'compileCalls') == 0, prefix + ': arms-off owned attack compiled arm geometry')
            for category in ['skin:', 'armor:'] + ([f'accessory:{wearable}:'] if wearable else []):
                require(total(category, 'maskedAttempts') > 0, prefix + ': missing suppressed path ' + category)
        else:
            require(total('', 'maskedAttempts') == 0, prefix + ': mask leaked outside arms-off owned attack')
            if active or not fp:
                for category in ['skin:', 'armor:'] + ([f'accessory:{wearable}:'] if wearable else []):
                    require(total(category, 'compileCalls') > 0, prefix + ': visible compile path missing ' + category)
        expected_counts = dict(bodyPasses=1, bodyItems=1, vanillaItems=0, otherItems=0) if fp else dict(bodyPasses=0, bodyItems=0, vanillaItems=0, otherItems=1)
        require(row.get('renderCounts') == expected_counts, prefix + ': held-item/body render path changed')
        require(row.get('clientSelected') == 0 and row.get('serverSelected') == 0, prefix + ': hotbar preparation mismatch')
        if active:
            flags = row.get('cachedNativeAttackVisibility') or {}
            require(flags.get('rightArm') == expected_show and flags.get('leftArm') == expected_show,
                    prefix + ': native two-handed cached arm flags differ')
            require(flags.get('rightItem') is True and flags.get('leftItem') is True,
                    prefix + ': native two-handed item flags differ')
            swings[(row.get('caseNumber'), row.get('camera'), row.get('nativeAttackStartedAt'))].append(row)
    valid_swings = []
    for key, group in swings.items():
        complete = all(row.get('nativeSequenceComplete') is True for row in group)
        if not complete:
            pending.append({'swing': list(key), 'capturedSamples': len(group)})
            if not final: continue
        require(complete and len(group) == 5, f'swing {key}: no complete five-sample native sequence')
        durations = {row.get('nativeAttackDuration') for row in group}
        require(len(durations) == 1, f'swing {key}: duration changed within a swing')
        if len(durations) != 1: continue
        duration = next(iter(durations))
        targets = expected_targets(duration)
        actual = [row.get('nativeAttackAge') for row in group]
        require([row.get('nativeSampleTarget') for row in group] == targets, f'swing {key}: wrong scaled targets')
        require(all(row.get('nativeSampleTargets') == targets for row in group), f'swing {key}: per-capture target list mismatch')
        require(all(a < b for a, b in zip(actual, actual[1:])), f'swing {key}: actual ages repeat or regress')
        require(all(target <= age <= target + 1 and age < duration for target, age in zip(targets, actual)),
                f'swing {key}: native sample outside target/active-duration window')
        valid_swings.append({'caseNumber': key[0], 'camera': key[1], 'startedAt': key[2],
                             'duration': duration, 'targets': targets, 'actualAges': actual,
                             'nativeKeyframes': [row.get('nativeKeyframe') for row in group]})
    completed_attempts = [attempt for attempt in data.get('attackAttempts', [])
                          if attempt.get('allRequestedAgesCapturedInOneSwing') is True]
    missed_attempts = [attempt for attempt in data.get('attackAttempts', [])
                       if 'missedRequestedAge' in attempt]
    if final:
        require(data.get('completedCases') == data.get('caseCount') == 40, 'Not all40 native cases completed')
        require(len(rows) == data.get('plannedCaptures') == 480, 'Expected480 accepted captures')
        require(len(cases) == 40 and all(len(group) == 12 for group in cases.values()), 'Expected12 captures per case')
        require(len(valid_swings) == 80, 'Expected80 complete native swings')
        require(len(completed_attempts) == 80, 'Expected80 accepted native attempt records')
        require(len(completed_attempts) + len(missed_attempts) == len(data.get('attackAttempts', [])),
                'A native attempt has neither complete samples nor retained miss evidence')
        for key in ['inventoryAndAccessoriesRestored', 'arenaAndTimeRestored', 'clientSettingsRestored',
                    'firstPersonBowPoseRestored', 'firstPersonPoseCacheRestored', 'nativeCombatSettingsRestored',
                    'bowConfigurationRestored', 'serverSelectedRestored', 'selectionReconciledToOriginalServer']:
            require(data.get(key) is True, 'Restoration assertion absent/false: ' + key)
        selection = data.get('selectionAfterNativePacket', {})
        require(selection.get('client') == selection.get('server') == data.get('originalServerSelected'), 'Post-packet authoritative selection differs')
        require(selection.get('observedServerTicksAfterPacket', 0) >= 3, 'Selection not observed after native packet')
        require(data.get('clientSelectedRestored') == (data.get('originalClientSelected') == data.get('originalServerSelected')),
                'Original client selection mismatch is not reported truthfully')
    return {'kind': 'native_arm_matrix_numeric', 'source': str(path), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
            'status': 'FAIL' if failures else 'NUMERIC_PASS_VISUAL_REVIEW_SEPARATE' if final else 'INCOMPLETE_SOURCE_FAILURE_RETAINED' if data.get('status') in ['FAIL','RESTORE_FAILURE'] else 'IN_PROGRESS_NO_NUMERIC_VIOLATIONS_OBSERVED',
            'sourceStatus': data.get('status'), 'sourceDetail': data.get('detail'), 'completedCases': data.get('completedCases'),
            'captureRows': len(rows), 'completeNativeSwings': len(valid_swings), 'pending': pending,
            'skinCaptureCounts': dict(Counter(row.get('actualSkinModel') for row in rows)),
            'profileCaptureCounts': dict(Counter(row.get('wearable') or 'diamond_armor_only' for row in rows)),
            'acceptedSwings': valid_swings, 'restoration': restored,
            'reportIo': data.get('reportIo'), 'capturedFrameTimings': timing_summary(rows),
            'activeCapturedFrameTimings': timing_summary([row for row in rows if row.get('nativeAttackActive') is True]),
            'missedAttackCaptureCount': len(data.get('missedAttackCaptures', [])),
            'attackAttemptCount': len(data.get('attackAttempts', [])),
            'acceptedAttemptCount': len(completed_attempts), 'missedAttemptCount': len(missed_attempts),
            'missedAttempts': missed_attempts,
            'originalClientSelected': data.get('originalClientSelected'), 'originalServerSelected': data.get('originalServerSelected'),
            'selectionAfterNativePacket': data.get('selectionAfterNativePacket'), 'failures': failures,
            'limitation': 'Read-only numerical audit; compile-entry counters are not final emitted vertices or a visual screenshot grade.'}


def hook(path):
    data = json.loads(path.read_text())
    failures = []
    if data.get('status') != 'PASS': failures.append('Controlled native hook did not report PASS')
    if data.get('configurationRestored') is not True: failures.append('Controlled hook settings restoration absent')
    rows = data.get('nativeAsymmetricHands', [])
    if {row.get('animatedHand') for row in rows} != {'MAIN_HAND', 'OFF_HAND'} or len(rows) != 2:
        failures.append('Expected both actual native asymmetric hands')
    if len(rows) == 2 and rows[0].get('actualMirrorEnabled') == rows[1].get('actualMirrorEnabled'):
        failures.append('Native mirror did not reverse between hands')
    for row in rows:
        flags = row.get('nativeModifierFlags', {})
        if flags.get('rightArm') == flags.get('leftArm'): failures.append('Native flags are not asymmetric')
        for side, flag in [('RIGHT', 'rightArm'), ('LEFT', 'leftArm')]:
            geometry = row.get('geometry', {}).get(side, {})
            for key in ['skin', 'sleeve', 'armor', 'glove', 'emf', 'defaultWrist']:
                value = geometry.get(key, -1)
                if not (value > 0 if flags.get(flag) else value == 0): failures.append(f'{row.get("animatedHand")}/{side}/{key}: native flag violated')
        if row.get('bothAttachmentMatricesUnchanged') is not True or row.get('nativeFadePreserved') is not True:
            failures.append('Native asymmetric transform/fade assertion absent')
    return {'kind':'controlled_native_arm_hook_numeric','source':str(path),'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),
            'status':'FAIL' if failures else 'PASS','sourceReport':data,'failures':failures,
            'limitation':'Controlled native animation and direct renderer hook fixture; not a gameplay input/camera sequence.'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('report', type=Path)
    parser.add_argument('--hook', action='store_true')
    parser.add_argument('--out', type=Path)
    args = parser.parse_args()
    result = hook(args.report) if args.hook else matrix(args.report)
    encoded = json.dumps(result, indent=2)
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(encoded)
    print(json.dumps({key:value for key,value in result.items() if key in ['status','sourceStatus','completedCases','captureRows','completeNativeSwings','failures']},indent=2))
