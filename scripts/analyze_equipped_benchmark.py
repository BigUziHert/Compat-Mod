"""Analyze physical mod-removal / FirstPerson factorial frame-time comparisons.

No measurements are generated here. The script consumes immutable manifests and
raw native frame intervals after all game processes have exited.
"""
from pathlib import Path
import argparse
import datetime
import hashlib
import json
import math
import statistics

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'verification/performance-equipped'
MODES = {'A': (True, False), 'B': (False, False), 'C': (True, True), 'D': (False, True)}
ORDER = ['B', 'A', 'D', 'C', 'C', 'D', 'A', 'B']
SCENES = ['local-lookdown-80', 'world-idle-8', 'world-idle-24', 'world-walk-8',
          'world-walk-24', 'deck-walk-8', 'deck-walk-24', 'deck-occluded-walk-8']
EXPECTED_CAPES = {
    'aether-diamond-gloves': 'aether:textures/models/accessory/capes/blue_cape_accessory.png',
    'deep-aether-skyjade': 'deep_aether:textures/models/accessory/capes/cloud_cape_accessory.png',
}


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def load(path):
    return json.loads(Path(path).read_text(encoding='utf-8-sig'))


def percentile(sorted_values, fraction):
    return sorted_values[max(0, math.ceil(len(sorted_values) * fraction) - 1)]


def frame_stats(intervals):
    if not intervals or any(type(v) is not int or v <= 0 for v in intervals):
        raise ValueError('Positive integer raw nanosecond intervals required')
    values = sorted(intervals)
    count, total = len(values), sum(values)
    tail_count = max(1, math.ceil(count * .01))
    slowest_mean = sum(values[-tail_count:]) / tail_count
    slow = {str(ms): sum(v > ms * 1e6 for v in values) for ms in (16.6666667, 33.3333333, 50, 100)}
    return dict(frames=count, intervalSeconds=total / 1e9, meanFps=count * 1e9 / total,
                meanFrameMs=total / count / 1e6, medianFrameMs=percentile(values, .5) / 1e6,
                p95FrameMs=percentile(values, .95) / 1e6, p99FrameMs=percentile(values, .99) / 1e6,
                maxFrameMs=values[-1] / 1e6, onePercentLowFps=1e9 / slowest_mean,
                onePercentTailFrames=tail_count,
                slowFrames=slow, slowFramesPerMinute={key: value * 60e9 / total for key, value in slow.items()})


def pick(mapping, *keys, default=None):
    for key in keys:
        if key in mapping:
            return mapping[key]
    return default


def scene_id(stage):
    return pick(stage, 'scene', 'id', 'stage', 'sceneId')


def scene_clone_count(stage):
    value = pick(stage, 'cloneCount', 'requestedClones', 'players', 'clones', 'count')
    if isinstance(value, list):
        return len(value)
    if isinstance(value, int):
        return value
    name = scene_id(stage)
    return 0 if name == 'local-lookdown-80' else int(name.rsplit('-', 1)[-1])


def normalized_stage(stage):
    name = scene_id(stage)
    if name not in SCENES:
        raise ValueError(f'Unexpected scene {name!r}')
    intervals = pick(stage, 'frameIntervalsNanos', 'intervalsNanos', 'rawFrameIntervalsNanos')
    stats = frame_stats(intervals)
    expected_samples = pick(stage, 'sampleCount', 'samples', 'frames', default=len(intervals))
    if expected_samples != len(intervals):
        raise ValueError(f'{name}: sample count differs from raw frame intervals')
    duration = pick(stage, 'durationNanos', 'measuredNanos')
    if duration is None or duration <= 0 or abs(duration - sum(intervals)) > max(intervals) * 2:
        raise ValueError(f'{name}: actual measured duration missing or inconsistent')
    if stats['intervalSeconds'] < 19.0:
        raise ValueError(f'{name}: less than 19 seconds of accepted intervals')
    actual_view_samples = stage.get('actualViewSamples')
    if stage.get('cameraLocked') is not True or stage.get('actualViewLockedEveryMeasuredFrame') is not True:
        raise ValueError(f'{name}: actual native main-camera view was not locked in every measured frame')
    if type(actual_view_samples) is not int or actual_view_samples <= 0:
        raise ValueError(f'{name}: native main-camera samples missing')
    yaw_error, pitch_error = stage.get('maximumActualViewYawError'), stage.get('maximumActualViewPitchError')
    if any(not isinstance(value, (int, float)) or not math.isfinite(value) or not 0 <= value <= .15
           for value in (yaw_error, pitch_error)):
        raise ValueError(f'{name}: actual native view error exceeded the frozen .15-degree guard')
    clones = scene_clone_count(stage)
    missing = pick(stage, 'framesMissingSomeClone', 'missingCloneFrames')
    all_returned = pick(stage, 'framesAllClonesReturned', 'fullyRenderedFrames')
    if missing is None or all_returned is None:
        raise ValueError(f'{name}: native render coverage missing')
    if missing + all_returned != len(intervals):
        raise ValueError(f'{name}: render coverage frame count mismatch')
    occluded = name == 'deck-occluded-walk-8'
    if not occluded and missing != 0:
        raise ValueError(f'{name}: {missing} frames omitted at least one expected visible clone')
    gear = pick(stage, 'entities', 'gear', 'gearMetadata', 'loadouts', 'equipment')
    if clones and not gear:
        raise ValueError(f'{name}: equipped workload metadata missing')
    if clones:
        if not isinstance(gear, list) or len(gear) != clones:
            raise ValueError(f'{name}: expected one actual equipment row per clone')
        profiles, skin_models, indices = set(), set(), set()
        occlusion_suppressed = []
        for entity in gear:
            indices.add(entity['index'])
            loadout = entity.get('loadout', {})
            profiles.add(loadout.get('profile'))
            accessories, equipment = loadout.get('accessories', []), loadout.get('equipment', [])
            if not 5 <= len(accessories) <= 6 or loadout.get('accessoryCount') != len(accessories):
                raise ValueError(f'{name}: clone {entity["index"]} lacks the expected 5–6 actual accessories')
            if len(equipment) != 6 or len({r.get('slot') for r in equipment}) != 6:
                raise ValueError(f'{name}: clone {entity["index"]} lacks six distinct equipment slots')
            if any(not row.get('item') or not row.get('renderer') or row.get('visible') is not True for row in accessories):
                raise ValueError(f'{name}: native visible accessory/renderer metadata incomplete')
            if sum(row.get('item') != 'minecraft:air' for row in equipment) < 5:
                raise ValueError(f'{name}: clone equipment workload is incomplete')
            if entity.get('resident') is not True:
                raise ValueError(f'{name}: a requested clone was no longer resident')
            if not occluded and (entity.get('accessoryDispatches', 0) <= 0 or entity.get('nativeRendererReturns', 0) <= 0):
                raise ValueError(f'{name}: clone {entity["index"]} never executed its native player/accessory path')
            if not occluded and entity.get('nativeFullTicks', 0) <= 0:
                raise ValueError(f'{name}: clone never executed full native ticks')
            native_tick_and_render_suppressed = (occluded and entity.get('nativeFullTicks') == 0
                                                and entity.get('nativeRendererReturns') == 0
                                                and entity.get('accessoryDispatches') == 0)
            if native_tick_and_render_suppressed:
                occlusion_suppressed.append(entity['index'])
            if stage.get('walking') and (entity.get('controllerMovementTicks', 0) <= 0 or entity.get('cumulativePath', 0) <= .5):
                if not native_tick_and_render_suppressed:
                    raise ValueError(f'{name}: requested walking did not produce a native path')
            skin = entity.get('skin', {})
            if skin.get('nativeGetSkinPath') is not True:
                raise ValueError(f'{name}: native skin/cape hooks were bypassed')
            expected_cape = EXPECTED_CAPES.get(loadout.get('profile'))
            if expected_cape is not None and skin.get('cape') != expected_cape:
                raise ValueError(f'{name}: {loadout.get("profile")} did not expose its actual native cape texture')
            skin_models.add(skin.get('model'))
        if indices != set(range(clones)) or len(profiles) != 6 or None in profiles:
            raise ValueError(f'{name}: expected six distinct equipped profiles and all unique clone indices')
        if len(skin_models) != 2 or None in skin_models:
            raise ValueError(f'{name}: both native wide/slim model workloads required')
    return dict(scene=name, clones=clones, **stats,
                cameraLock=dict(actualViewSamples=actual_view_samples, measuredFrames=len(intervals),
                                actualViewLockedEveryMeasuredFrame=True,
                                maximumActualViewYawError=yaw_error, maximumActualViewPitchError=pitch_error,
                                cameraAngleLockEvents=stage.get('cameraAngleLockEvents')),
                nativeCoverage=dict(framesAllClonesReturned=all_returned, framesMissingSomeClone=missing,
                                    allCloneFrameFraction=all_returned / len(intervals)),
                gear=gear, nativeFullTicks=stage.get('nativeFullTicks'),
                measuredClientTicks=stage.get('measuredClientTicks'),
                actualNativeClientTicksPerSecond=stage.get('measuredClientTicks', 0) * 1e9 / duration,
                gc=pick(stage, 'gc', 'gcDeltas', 'gcDelta'),
                occlusionSuppressedCloneIndices=occlusion_suppressed if clones else [],
                occludedMotionInterpretation=('A declared wall-occluded clone may have no measured native walking when Entity Culling suppressed all full ticks, player renders and accessory dispatches. This is the measured behavior trade-off, not matched-work hook overhead.'
                                             if occluded else None),
                behaviorCostIncludesUnequalCulling=occluded), intervals


def report_path(manifest):
    if manifest.get('reportPath'):
        return Path(manifest['reportPath'])
    return Path(manifest['runtime']) / 'emfattachments-tests' / manifest['label'] / 'equipped-crowd-frames.json'


def check_cleanup(report):
    cleanup = report.get('cleanup')
    if not isinstance(cleanup, dict) or len(cleanup) < 4:
        raise ValueError('Explicit cleanup map with multiple native restoration checks required')
    failed = [key for key, value in cleanup.items() if value is not True]
    if failed:
        raise ValueError(f'Cleanup did not pass: {failed}')
    return cleanup


def inspect_run(manifest_path):
    manifest = load(manifest_path)
    source = report_path(manifest)
    record = dict(label=manifest['label'], mode=manifest['mode'], passNumber=manifest['passNumber'],
                  attempt=manifest['attempt'], manifestPath=str(manifest_path), manifestSha256=sha(manifest_path),
                  reportPath=str(source), accepted=False)
    try:
        if not manifest.get('valid'):
            raise ValueError('Pre-launch audit was not valid')
        if ORDER[manifest['passNumber'] - 1] != manifest['mode']:
            raise ValueError('Pass order/mode mismatch')
        report = load(source)
        record['reportSha256'] = sha(source)
        if report.get('status') not in ('CAPTURED', 'PASS', 'COMPLETE'):
            raise ValueError(f'Native report status {report.get("status")!r}: {report.get("detail", "")}')
        if report.get('finished') is not True or report.get('label') != manifest['label']:
            raise ValueError('Native report is unfinished or has a different pass label')
        if Path(report.get('runtime', '')).resolve() != Path(manifest['runtime']).resolve():
            raise ValueError('Native report belongs to a different runtime')
        if report.get('fullDeveloperHarnessInstalled') is not False or report.get('productionMixinsDisabledProperty') is not False:
            raise ValueError('Old diagnostic harness or mixin-disable substitute is active/unknown')
        if report.get('cameraLocked') is not True:
            raise ValueError('Native camera-lock benchmark mode is absent')
        if report.get('warmupSeconds') != 10 or report.get('measureSeconds') != 20 or report.get('firstSceneExtraWarmupSeconds') != 20:
            raise ValueError('Native warmup/measurement durations differ from the frozen full-matrix design')
        compat, fp = MODES[manifest['mode']]
        if report.get('compatInstalled') is not compat or report.get('firstPersonInstalled') is not fp:
            raise ValueError('Actual native ModList differs from physical-jar mode')
        settings = pick(report, 'renderSettings', 'settings')
        if not isinstance(settings, dict):
            raise ValueError('Actual native render settings missing')
        for key, value in (('width', 2560), ('height', 1440), ('fov', 90)):
            if settings.get(key) != value:
                raise ValueError(f'Actual native {key}={settings.get(key)!r}; controlled matrix requires {value}')
        if settings.get('vsync') is not False or settings.get('maxFps', 0) < 260:
            raise ValueError('Native benchmark must be uncapped with VSync disabled')
        if settings.get('fullscreen') is not True:
            raise ValueError('Native controlled benchmark must be fullscreen')
        if settings.get('renderDistance') != 8 or settings.get('simulationDistance') != 8:
            raise ValueError('Native render/simulation distance must both be 8')
        if fp and report.get('firstPersonEnabled') is not True:
            raise ValueError('FirstPerson is installed but native API does not report enabled')
        record['cleanup'] = check_cleanup(report)
        record['compatInstalled'], record['firstPersonInstalled'] = compat, fp
        stages, raw = [], {}
        for stage in report['stages']:
            actual_settings = pick(stage, 'renderSettings', 'settings')
            if actual_settings != settings:
                raise ValueError('Native render settings changed between benchmark stages')
            if fp and stage.get('firstPersonEnabled') is not True:
                raise ValueError('FirstPerson was disabled during a measured stage')
            value, intervals = normalized_stage(stage)
            stages.append(value)
            if value['scene'] in raw:
                raise ValueError('Duplicate scene')
            raw[value['scene']] = intervals
        if [stage['scene'] for stage in stages] != SCENES:
            raise ValueError('Missing/reordered scenes')
        body_frames = pick(report['stages'][0], 'framesLocalBodyReturned', 'localBodyRenderedFrames')
        if fp and body_frames != stages[0]['frames']:
            raise ValueError('FirstPerson look-down scene did not render the native local body in every measured frame')
        record.update(accepted=True, stages=stages, raw=raw,
                      settings=settings, firstPersonEnabled=report.get('firstPersonEnabled'),
                      lookDownLocalBodyFrames=body_frames, limitations=report.get('limitations', []))
    except Exception as error:
        record['rejection'] = str(error)
    return record


def combine(runs):
    result = {}
    for mode in MODES:
        selected = [r for r in runs if r['mode'] == mode]
        if not selected:
            continue
        scenes = {}
        for scene in SCENES:
            intervals = [v for run in selected for v in run['raw'][scene]]
            stats = frame_stats(intervals)
            individual = [next(stage for stage in run['stages'] if stage['scene'] == scene) for run in selected]
            stats['individualRuns'] = [dict(label=r['label'], meanFps=s['meanFps'], p99FrameMs=s['p99FrameMs'],
                                            onePercentLowFps=s['onePercentLowFps']) for r, s in zip(selected, individual)]
            stats['repeatMeanFpsRange'] = [min(s['meanFps'] for s in individual), max(s['meanFps'] for s in individual)]
            stats['repeatMeanFpsSpreadPercent'] = (stats['repeatMeanFpsRange'][1] - stats['repeatMeanFpsRange'][0]) / stats['meanFps'] * 100
            stats['nativeCoverage'] = [s['nativeCoverage'] for s in individual]
            scenes[scene] = stats
        result[mode] = dict(compatInstalled=MODES[mode][0], firstPersonInstalled=MODES[mode][1],
                            repeatCount=len(selected), scenes=scenes)
    return result


def comparisons(groups):
    rows = []
    for fp, on, off in ((False, 'A', 'B'), (True, 'C', 'D')):
        if on not in groups or off not in groups:
            continue
        for scene in SCENES:
            a, b = groups[on]['scenes'][scene], groups[off]['scenes'][scene]
            fps_change = (a['meanFps'] / b['meanFps'] - 1) * 100
            paired = [dict(compatOnLabel=x['label'], compatOffLabel=y['label'],
                           meanFpsPercentChange=(x['meanFps'] / y['meanFps'] - 1) * 100)
                      for x, y in zip(a['individualRuns'], b['individualRuns'])]
            if scene == 'deck-occluded-walk-8':
                interpretation = 'The compatibility fix keeps hidden deck players ticking and submitting renders while natural Entity Culling suppresses them without the jar. This is unequal native work and a material practical behavior cost, not pure hook overhead.'
                visibility = 'Unequal culling/tick/render work behind a real wall.'
            elif fp:
                interpretation = 'Observed total-behavior comparison with FirstPerson enabled. Without compatibility, native head/goggle geometry obstructs the view; compatibility restores a clear view. Pixel visibility/body placement are not matched, so this FPS difference must not be called pure compatibility-code overhead.'
                visibility = 'NOT_MATCHED: head/goggle clipping without compatibility; corrected visibility with compatibility.'
            else:
                interpretation = 'Matched native submission coverage and equipped scene; compare the reviewed images and both repeat ranges before attributing a small difference.'
                visibility = 'Native submission coverage matched; separate screenshot review checks visible gear.'
            rows.append(dict(firstPersonInstalled=fp, scene=scene, compatOn=a, compatOff=b,
                             compatMeanFpsPercentChange=fps_change,
                             compatMeanFrameMsDifference=a['meanFrameMs'] - b['meanFrameMs'],
                             compatP99FrameMsDifference=a['p99FrameMs'] - b['p99FrameMs'],
                             compatOnePercentLowFpsPercentChange=(a['onePercentLowFps'] / b['onePercentLowFps'] - 1) * 100,
                             pairedOrderComparisons=paired,
                             repeatMeanFpsRangesOverlap=not (a['repeatMeanFpsRange'][1] < b['repeatMeanFpsRange'][0]
                                                           or b['repeatMeanFpsRange'][1] < a['repeatMeanFpsRange'][0]),
                             smallDifferenceVersusRepeatSpread=abs(fps_change) <= max(a['repeatMeanFpsSpreadPercent'], b['repeatMeanFpsSpreadPercent']),
                             pixelVisibilityComparability=visibility, interpretation=interpretation))
    fp_effect = []
    for compat, fp_on, fp_off in ((True, 'C', 'A'), (False, 'D', 'B')):
        if fp_on in groups and fp_off in groups:
            for scene in SCENES:
                a, b = groups[fp_on]['scenes'][scene], groups[fp_off]['scenes'][scene]
                fp_effect.append(dict(compatInstalled=compat, scene=scene,
                                      firstPersonOn=a, firstPersonOff=b,
                                      firstPersonMeanFpsPercentChange=(a['meanFps'] / b['meanFps'] - 1) * 100,
                                      firstPersonMeanFrameMsDifference=a['meanFrameMs'] - b['meanFrameMs'],
                                      firstPersonP99FrameMsDifference=a['p99FrameMs'] - b['p99FrameMs'],
                                      firstPersonOnePercentLowFpsPercentChange=(a['onePercentLowFps'] / b['onePercentLowFps'] - 1) * 100,
                                      interpretation=('Practical cost of adding FirstPerson plus its local body/accessories. With compatibility absent, head/goggle clipping changes visibility; apparent crowd-scene FPS gains are not evidence that FirstPerson is free or accelerates equivalent rendering.'
                                                      if not compat else 'Practical addition of a visible first-person body/accessories; total feature rendering cost, not pure API/hook overhead.')))
    return rows, fp_effect


def markdown(result):
    lines = ['# Equipped-player performance comparison', '',
             'Actual production-jar presence/absence; all values are measured in the isolated native client fixture.', '',
             '**FirstPerson visibility caveat:** without the compatibility jar, the local head/goggles obstruct the view in the final screenshots. The FirstPerson-installed rows compare that clipped view with the corrected clear view. Their observed FPS differences are total behavior costs, not isolated code overhead.', '',
             '| Scene | FirstPerson | Without compat FPS | With compat FPS | FPS change | Without/with p99 ms |',
             '| --- | --- | ---: | ---: | ---: | ---: |']
    for row in result['compatComparisons']:
        off, on = row['compatOff'], row['compatOn']
        lines.append(f"| {row['scene']} | {'installed' if row['firstPersonInstalled'] else 'removed'} | {off['meanFps']:.1f} | {on['meanFps']:.1f} | {row['compatMeanFpsPercentChange']:+.2f}% | {off['p99FrameMs']:.2f} / {on['p99FrameMs']:.2f} |")
    lines += ['', 'Average FPS is total accepted frames divided by total accepted interval time. Quantiles pool raw frame intervals.',
              'The 1% low is 1000 divided by the mean milliseconds of the slowest ceil(1% of frames); it is not simply 1000/p99.',
              'Both individual repeats, their range, and slow-frame counts remain in the JSON record.', '',
              'Client clones exercise native EMF, accessories, shaders and Sable client paths. They omit real multiplayer networking, server player simulation and distinct skin downloads.',
              'The occluded-deck scene includes the intended culling-policy difference and should not be described as pure overhead.',
              'No GPU-causality or universal multiplayer-FPS conclusion follows from these timings alone.', '']
    lines += ['## FirstPerson installed versus removed', '',
              'These comparisons add/remove the local body and accessories. Without compatibility, the clipped view makes an equivalent-pixels comparison impossible; positive crowded-scene values must not be presented as a FirstPerson speedup.', '',
              '| Scene | Compatibility | Without FirstPerson FPS | With FirstPerson FPS | FPS change | Without/with 1% low FPS |',
              '| --- | --- | ---: | ---: | ---: | ---: |']
    for row in result['firstPersonComparisons']:
        off, on = row['firstPersonOff'], row['firstPersonOn']
        lines.append(f"| {row['scene']} | {'installed' if row['compatInstalled'] else 'removed'} | {off['meanFps']:.1f} | {on['meanFps']:.1f} | {row['firstPersonMeanFpsPercentChange']:+.2f}% | {off['onePercentLowFps']:.1f} / {on['onePercentLowFps']:.1f} |")
    lines.append('')
    return '\n'.join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, default=OUT)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    directory = args.directory.resolve()
    manifests = sorted(directory.glob('equipped-pass*-manifest.json'))
    if not manifests:
        raise FileNotFoundError('No equipped pass manifests found')
    exclusions_path = directory / 'exclusions.json'
    exclusions = load(exclusions_path) if exclusions_path.exists() else {}
    inspected = [inspect_run(path) for path in manifests]
    selected, excluded = [], []
    for number in range(1, 9):
        candidates = sorted([r for r in inspected if r['passNumber'] == number], key=lambda r: r['attempt'])
        choice = None
        for run in candidates:
            if run['label'] in exclusions:
                run['explicitExclusion'] = exclusions[run['label']]
            elif run['accepted'] and choice is None:
                choice = run
                selected.append(run)
                continue
            excluded.append(run)
    groups = combine(selected)
    compat, fp = comparisons(groups)
    complete = len(selected) == 8 and all(group['repeatCount'] == 2 for group in groups.values())
    result = dict(status='COMPLETE_TIMINGS_REQUIRE_VISUAL_REVIEW' if complete else 'INCOMPLETE',
                  analyzedUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                  design=dict(order=ORDER, modes={k: dict(compatInstalled=c, firstPersonInstalled=f) for k, (c, f) in MODES.items()},
                              expectedScenes=SCENES, physicalModRemoval=True,
                              selection='Earliest valid non-excluded attempt per planned pass, never highest-FPS selection.',
                              onePercentLowDefinition='1000 / mean milliseconds of slowest ceil(1%N) frames',
                              percentileDefinition='Nearest-rank quantile of pooled positive native frame intervals'),
                  prominentInterpretation={'firstPersonInstalled': 'The no-compatibility FirstPerson view is obstructed by local head/goggle geometry. Observed 3.5–9.4% open-scene differences include corrected visibility/body placement and cannot be reported as pure code overhead.',
                                           'occludedDeck': 'The large wall-scene cost includes the deliberate difference between eight active/rendered deck actors and eight naturally culled, tick-suppressed actors.'},
                  acceptedRuns=[{k: v for k, v in run.items() if k != 'raw'} for run in selected],
                  rejectedOrAdditionalRuns=[{k: v for k, v in run.items() if k != 'raw'} for run in excluded],
                  combinedByMode=groups, compatComparisons=compat, firstPersonComparisons=fp,
                  acceptedFrames=sum(len(values) for run in selected for values in run['raw'].values()),
                  acceptedIntervalSeconds=sum(sum(values) / 1e9 for run in selected for values in run['raw'].values()),
                  limitations=['Client-only equipped clones omit actual multiplayer packet/server/skin download work.',
                               'Two repeats per state provide descriptive repeatability, not a statistical guarantee.',
                               'Mean FPS, frame quantiles and GC deltas do not identify GPU or scheduler causality.',
                               'The deliberately occluded deck can have different draw/tick work between compatibility modes.',
                               'Body/attachment placement and visibility corrections can change total rendering work; timings measure the resulting behavior, not only isolated hook CPU cost.',
                               'Native render returns need separate post-measurement visual review.'])
    output = args.output or directory / 'comparison.json'
    output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    output.with_suffix('.md').write_text(markdown(result), encoding='utf-8')
    print(json.dumps(dict(status=result['status'], acceptedRuns=len(selected), acceptedFrames=result['acceptedFrames'], output=str(output))))


if __name__ == '__main__':
    main()
