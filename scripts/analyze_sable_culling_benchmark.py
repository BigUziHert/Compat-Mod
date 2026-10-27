"""Analyze six no-FirstPerson native runs of candidate125 / old124 / removed.

Timing evidence from earlier experiments is never reused as a new repeat.
Imports the unchanged equipped-frame validator/statistics, with stricter explicit
wall-behavior checks for the three culling policies under comparison.
"""
from pathlib import Path
import argparse
import datetime
import json

import analyze_equipped_benchmark as common
import prepare_sable_culling_benchmark as preparation

OUT = preparation.OUT
ORDER, MODES, SCENES = preparation.ORDER, preparation.MODES, common.SCENES
PRIMARY = preparation.PRIMARY_SCENES
load, sha = common.load, common.sha


def wall_behavior(stage, mode):
    entities = stage['entities']
    ticks = stage['measuredClientTicks']
    if stage.get('realWallOcclusion') is not True or stage.get('nativeWallBlocks', 0) < 1:
        raise ValueError('The occluded scene requires the actual native opaque wall')
    if mode == 'OLD124':
        if stage['framesMissingSomeClone'] != 0 or any(
            e['nativeFullTicks'] <= 0 or e['nativeRendererReturns'] <= 0 or e['accessoryDispatches'] <= 0 for e in entities):
            raise ValueError('Old124 wall behavior must preserve full ticks and every-frame remote render coverage')
    else:
        if any(e['nativeRendererReturns'] != 0 or e['accessoryDispatches'] != 0 for e in entities):
            raise ValueError(f'{mode} wall actors must be naturally occlusion-culled, including native accessory dispatches')
        if stage['framesAllClonesReturned'] != 0:
            raise ValueError(f'{mode} wall native render coverage should be zero')
        if mode == 'CANDIDATE125':
            if any(e['nativeFullTicks'] <= 0 or e['controllerMovementTicks'] <= 0 or e['cumulativePath'] <= .5
                   for e in entities):
                raise ValueError('Candidate125 must preserve native deck ticks/movement behind the wall')
        elif any(e['nativeFullTicks'] != 0 for e in entities):
            raise ValueError('Removed baseline must demonstrate native Entity Culling full-tick suppression behind the wall')
    return dict(mode=mode, measuredClientTicks=ticks, frames=stage['sampleCount'],
                framesAllClonesReturned=stage['framesAllClonesReturned'],
                framesMissingSomeClone=stage['framesMissingSomeClone'], nativeWallBlocks=stage['nativeWallBlocks'],
                perClone=[{key: entity[key] for key in ('index', 'nativeFullTicks', 'nativeRendererReturns',
                           'accessoryDispatches', 'controllerMovementTicks', 'cumulativePath')} for entity in entities],
                interpretation=('Candidate retains player animation/movement ticks while restoring normal wall occlusion.' if mode == 'CANDIDATE125'
                                else 'Broad old policy retains wall-hidden ticks and render submissions.' if mode == 'OLD124'
                                else 'Natural Entity Culling suppresses full ticks, motion and normal render submissions.'))


def inspect_run(manifest_path):
    manifest = load(manifest_path)
    source = common.report_path(manifest)
    record = dict(label=manifest['label'], mode=manifest['mode'], passNumber=manifest['passNumber'],
                  attempt=manifest['attempt'], manifestPath=str(manifest_path), manifestSha256=sha(manifest_path),
                  reportPath=str(source), accepted=False)
    try:
        if manifest.get('valid') is not True:
            raise ValueError('Pre-launch audit invalid')
        mode = manifest['mode']
        if ORDER[manifest['passNumber'] - 1] != mode:
            raise ValueError('Frozen pass order/state mismatch')
        plan_path = Path(manifest['planPath'])
        if sha(plan_path) != manifest['planSha256']:
            raise ValueError('Frozen plan changed')
        plan = load(plan_path)
        expected_mods = dict(plan['sharedModSha256'])
        keys = ('harness',) + ((MODES[mode],) if MODES[mode] else ())
        for key in keys:
            item = plan['artifacts'][key]
            if sha(item['path']) != item['sha256']:
                raise ValueError('Frozen artifact bytes changed')
            expected_mods[item['name']] = item['sha256']
        if manifest['activeModSha256'] != expected_mods:
            raise ValueError('Physical active jars do not match pinned state')
        if plan.get('baselineShaderpackPath') and manifest.get('shaderpackSha256') != plan['shaderpackSha256']:
            raise ValueError('Exact pre-launch shader snapshot audit missing/different')
        if plan.get('baselineShaderpackPath'):
            shader_audit_path = Path(manifest_path).with_name(manifest['label'] + '-shader-audit.json')
            shader_audit = load(shader_audit_path)
            if shader_audit.get('valid') is not True or shader_audit.get('jvmExited') is not True or shader_audit.get('baselineSha256') != plan['shaderpackSha256']:
                raise ValueError('Post-exit shader audit changed more than the generated timestamp comment')
            record['shaderAuditSha256'] = sha(shader_audit_path)
        if manifest.get('firstPersonPhysicallyInstalled') is not False:
            raise ValueError('FirstPerson was not physically absent')
        if manifest['sourceSha256'] != plan['sourceSha256']:
            raise ValueError('Source provenance differs from frozen plan')
        identity_path = Path(manifest_path).with_name(manifest['label'] + '-process.json')
        identity = load(identity_path)
        if identity.get('offlineIdentityConfirmed') is not True or identity.get('productionDisableFlagAbsent') is not True or identity.get('processAuditBeforeTrigger') is not True:
            raise ValueError('Owned offline identity/production flag audit missing')
        if Path(identity['userDir']).resolve() != Path(manifest['runtime']).resolve():
            raise ValueError('Process user.dir differs from owned runtime')
        report = load(source)
        record['reportSha256'] = sha(source)
        if report.get('status') != 'CAPTURED' or report.get('finished') is not True:
            raise ValueError(f'Native report did not finish CAPTURED: {report.get("status")!r} {report.get("detail", "")}')
        if report.get('label') != manifest['label'] or Path(report['runtime']).resolve() != Path(manifest['runtime']).resolve():
            raise ValueError('Native report label/runtime mismatch')
        compat = MODES[mode] is not None
        if report.get('compatInstalled') is not compat or report.get('firstPersonInstalled') is not False or report.get('firstPersonEnabled') is not False:
            raise ValueError('Native ModList/API differs from no-FirstPerson physical state')
        if report.get('fullDeveloperHarnessInstalled') is not False or report.get('productionMixinsDisabledProperty') is not False:
            raise ValueError('Old developer harness or mixin-disable substitute present/unknown')
        if report.get('cameraLocked') is not True:
            raise ValueError('Native camera-lock mode absent')
        restoration = report.get('restorationEvidence')
        if plan.get('baselineShaderpackPath'):
            if report.get('cleanupOnlyPreflight') is not False or report.get('cleanup', {}).get('clientStateRestored') is not True:
                raise ValueError('Full matrix requires the converged client/server cleanup fixture, not cleanup-only preflight')
            if not isinstance(restoration, dict):
                raise ValueError('Authoritative cleanup restoration evidence missing')
            for phase in ('immediateServer', 'settledServer'):
                state = restoration.get(phase, {})
                for key in ('matches', 'positionMatches', 'inheritedVelocityMatches', 'supportMatches', 'flagsMatch', 'rotationMatches'):
                    if state.get(key) is not True:
                        raise ValueError(f'Cleanup {phase}.{key} failed/missing')
                if phase == 'immediateServer' and any(state.get(key) is not True for key in ('movementMatches', 'fallDistanceMatches')):
                    raise ValueError('Immediate authoritative movement/fall restoration failed')
            if any(restoration.get(key) is not True for key in ('clientPositionMatches', 'clientPhysicsMatches', 'clientSupportMatches')):
                raise ValueError('Settled client restoration failed')
        if report.get('warmupSeconds') != 10 or report.get('measureSeconds') != 20 or report.get('firstSceneExtraWarmupSeconds') != 20:
            raise ValueError('Frozen warmup/measurement durations changed')
        settings = report['renderSettings']
        for key, value in (('width', 2560), ('height', 1440), ('fov', 90), ('fullscreen', True),
                           ('vsync', False), ('maxFps', 260), ('renderDistance', 8), ('simulationDistance', 8), ('focused', True)):
            if settings.get(key) != value:
                raise ValueError(f'Native controlled setting {key} differs: {settings.get(key)!r}')
        stages, raw, behaviors = [], {}, []
        for stage in report['stages']:
            if stage.get('renderSettings') != settings or stage.get('firstPersonEnabled') is not False:
                raise ValueError('Native settings or FirstPerson state changed between scenes')
            value, intervals = common.normalized_stage(stage)
            scene = value['scene']
            if stage.get('actualViewSamples') != len(intervals) or stage.get('durationNanos') != sum(intervals):
                raise ValueError('Every measured interval must have an actual main-camera sample and exact duration accounting')
            if scene in raw:
                raise ValueError('Duplicate scene')
            if scene.startswith('deck-'):
                if stage.get('movingSublevel') is not True or stage.get('localTrackedDeckTicks') != stage.get('measuredClientTicks'):
                    raise ValueError('Native moving-deck carry missing during a primary scene')
                if stage.get('platformDriveTickAtSceneEnd', 0) <= stage.get('platformDriveTickAtSceneStart', 0):
                    raise ValueError('Native platform did not move')
                if any(e.get('trackingMatchesOwnedDeck') is not True for e in stage['entities']):
                    raise ValueError('A crowd actor lost the native deck tracking relation')
                if stage.get('maximumLocalPlotHorizontalDrift', 999) > .01:
                    raise ValueError('Local observer drifted in plot space')
            if scene == 'deck-occluded-walk-8':
                behaviors.append(wall_behavior(stage, mode))
            stages.append(value)
            raw[scene] = intervals
        if [stage['scene'] for stage in stages] != SCENES:
            raise ValueError('Missing/reordered native scenes')
        record.update(accepted=True, stages=stages, raw=raw, settings=settings,
                      compatInstalled=compat, firstPersonInstalled=False, firstPersonEnabled=False,
                      compatArtifact=manifest.get('compatArtifact'), harnessArtifact=manifest['harnessArtifact'],
                      cleanup=common.check_cleanup(report), wallBehavior=behaviors,
                      restorationEvidence=restoration,
                      sourceSha256=plan['sourceSha256'], limitations=report.get('limitations', []))
    except Exception as error:
        record['rejection'] = str(error)
    return record


def combine(runs):
    result = {}
    for mode, artifact_key in MODES.items():
        selected = [run for run in runs if run['mode'] == mode]
        if not selected:
            continue
        scenes = {}
        for scene in SCENES:
            values = [value for run in selected for value in run['raw'][scene]]
            stats = common.frame_stats(values)
            individual = [next(stage for stage in run['stages'] if stage['scene'] == scene) for run in selected]
            stats['individualRuns'] = [dict(label=run['label'], passNumber=run['passNumber'],
                **{key: stage[key] for key in ('meanFps', 'p99FrameMs', 'onePercentLowFps', 'slowFrames',
                                               'nativeCoverage', 'measuredClientTicks', 'gear')})
                for run, stage in zip(selected, individual)]
            stats['repeatMeanFpsRange'] = [min(s['meanFps'] for s in individual), max(s['meanFps'] for s in individual)]
            stats['repeatMeanFpsSpreadPercent'] = (stats['repeatMeanFpsRange'][1] - stats['repeatMeanFpsRange'][0]) / stats['meanFps'] * 100
            scenes[scene] = stats
        result[mode] = dict(compatArtifact=selected[0]['compatArtifact'], firstPersonInstalled=False,
                            repeatCount=len(selected), scenes=scenes,
                            wallBehavior=[r['wallBehavior'][0] for r in selected])
    return result


def comparisons(groups):
    rows = []
    for after, before in (('CANDIDATE125', 'OLD124'), ('CANDIDATE125', 'REMOVED'), ('OLD124', 'REMOVED')):
        if after not in groups or before not in groups:
            continue
        for scene in SCENES:
            a, b = groups[after]['scenes'][scene], groups[before]['scenes'][scene]
            change = (a['meanFps'] / b['meanFps'] - 1) * 100
            # The mirrored six-pass order gives both state orders in its two
            # halves; ascending pass order pairs descriptive repeats explicitly.
            pairs = [dict(afterLabel=x['label'], beforeLabel=y['label'],
                          afterPass=x['passNumber'], beforePass=y['passNumber'],
                          meanFpsPercentChange=(x['meanFps'] / y['meanFps'] - 1) * 100)
                     for x, y in zip(a['individualRuns'], b['individualRuns'])]
            wall = scene == 'deck-occluded-walk-8'
            interpretation = ('Wall scene compares different native work: old124 keeps full ticks and renders; candidate125 keeps full ticks/motion while culling hidden renders; removed naturally suppresses full ticks/motion and renders. This is a practical behavior cost, not pure hook overhead.'
                              if wall else 'Matched requested open-scene player submission coverage and equipment; interpret small differences against both repeats and separate visual review.')
            rows.append(dict(afterState=after, beforeState=before, scene=scene, primaryEndpoint=scene in PRIMARY,
                             after=a, before=b, meanFpsPercentChange=change,
                             meanFrameMsDifference=a['meanFrameMs'] - b['meanFrameMs'],
                             p99FrameMsDifference=a['p99FrameMs'] - b['p99FrameMs'],
                             onePercentLowFpsPercentChange=(a['onePercentLowFps'] / b['onePercentLowFps'] - 1) * 100,
                             pairedOrderComparisons=pairs,
                             repeatMeanFpsRangesOverlap=not (a['repeatMeanFpsRange'][1] < b['repeatMeanFpsRange'][0]
                                                           or b['repeatMeanFpsRange'][1] < a['repeatMeanFpsRange'][0]),
                             changeWithinLargestRepeatSpread=abs(change) <= max(a['repeatMeanFpsSpreadPercent'], b['repeatMeanFpsSpreadPercent']),
                             interpretation=interpretation))
    return rows


def markdown(result):
    lines = ['# Sable culling comparison: 1.2.5 / 1.2.4 / removed', '',
             'FirstPerson is physically absent in every run. All repeats are new fresh-JVM measurements.', '',
             '| Scene | Removed FPS | 1.2.4 FPS | 1.2.5 FPS | 1.2.5 vs1.2.4 | 1.2.5 vsremoved |',
             '| --- | ---: | ---: | ---: | ---: | ---: |']
    groups = result['combinedByState']
    if all(mode in groups for mode in MODES):
        for scene in PRIMARY + [s for s in SCENES if s not in PRIMARY]:
            removed, old, candidate = [groups[key]['scenes'][scene] for key in ('REMOVED', 'OLD124', 'CANDIDATE125')]
            lines.append(f"| {scene} | {removed['meanFps']:.1f} | {old['meanFps']:.1f} | {candidate['meanFps']:.1f} | {(candidate['meanFps']/old['meanFps']-1)*100:+.2f}% | {(candidate['meanFps']/removed['meanFps']-1)*100:+.2f}% |")
    lines += ['', 'Primary endpoints are the two open moving-deck scenes and the real-wall-occluded deck scene.',
              'The wall scene is intentionally unequal work: old1.2.4 renders hidden actors;1.2.5 retains their native ticks/motion but restores render culling; removed also suppresses full native ticks/motion.',
              'Mean FPS = pooled accepted frames / pooled interval seconds. Quantiles use nearest rank.',
              '1% low =1000 / mean milliseconds of the slowest ceil(1%N) intervals. Per-repeat ranges and all slow-frame counts remain in comparison.json.',
              'Native client clones omit real multiplayer networking, server-player simulation and skin-download work. Two repeats per state are descriptive, not a statistical guarantee.',
              'Review screenshots separately before accepting visual correctness; native dispatch totals alone cannot prove correct pixels.', '']
    return '\n'.join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, default=OUT)
    parser.add_argument('--base', type=Path, default=preparation.BASE)
    parser.add_argument('--plan', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    directory = args.directory.resolve()
    manifests = sorted(directory.glob('sable125*-manifest.json'))
    if not manifests:
        raise FileNotFoundError('No new Sable-culling pass manifests')
    exclusions_path = directory / 'exclusions.json'
    exclusions = load(exclusions_path) if exclusions_path.exists() else {}
    inspected = [inspect_run(path) for path in manifests]
    active_plan = args.plan or preparation.plan_path(args.base)
    active_plan_sha = sha(active_plan)
    selected, excluded = [], []
    for number in range(1, len(ORDER) + 1):
        chosen = False
        for run in sorted((r for r in inspected if r['passNumber'] == number), key=lambda r: r['attempt']):
            if load(run['manifestPath']).get('planSha256') != active_plan_sha:
                run['supersededPlanExcluded'] = True
            elif run['label'] in exclusions:
                run['explicitExclusion'] = exclusions[run['label']]
            elif run['accepted'] and not chosen:
                selected.append(run)
                chosen = True
                continue
            excluded.append(run)
    groups = combine(selected)
    complete = len(selected) == len(ORDER) and len(groups) == 3 and all(g['repeatCount'] == 2 for g in groups.values())
    result = dict(status='COMPLETE_TIMINGS_REQUIRE_VISUAL_REVIEW' if complete else 'INCOMPLETE',
                  analyzedUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                  analyzerSha256=sha(Path(__file__)), sharedStatisticsValidatorSha256=sha(Path(common.__file__)),
                  activePlan=dict(path=str(active_plan), sha256=active_plan_sha, revisionId=load(active_plan).get('revisionId', 'revision-001')),
                  design=dict(order=ORDER, primaryEndpoints=PRIMARY, secondaryControls=[s for s in SCENES if s not in PRIMARY],
                              physicalJarSelection=True, firstPersonInstalledInAnyRun=False,
                              historicalTimingReuse=False, selection='Earliest valid non-excluded attempt per planned pass; never best-FPS selection.',
                              onePercentLowDefinition='1000 / mean milliseconds of slowest ceil(1%N) frames',
                              percentileDefinition='Nearest-rank quantile of pooled positive native frame intervals'),
                  acceptedRuns=[{k: v for k, v in run.items() if k != 'raw'} for run in selected],
                  rejectedOrAdditionalRuns=[{k: v for k, v in run.items() if k != 'raw'} for run in excluded],
                  combinedByState=groups, comparisons=comparisons(groups),
                  acceptedFrames=sum(len(values) for run in selected for values in run['raw'].values()),
                  acceptedIntervalSeconds=sum(sum(values) / 1e9 for run in selected for values in run['raw'].values()),
                  limitations=['Client-only clones omit real multiplayer packet/server/skin-download work.',
                               'Two repeats per state are descriptive repeatability evidence, not a statistical guarantee.',
                               'The wall scene intentionally changes tick/render workload and does not isolate hook CPU overhead.',
                               'Mean FPS and frame-time quantiles do not establish GPU/CPU/scheduler causality.',
                               'Native coverage and gear counters require separate screenshot review.',
                               'FirstPerson was excluded at the user request; these results do not establish its behavior.'])
    output = args.output or directory / 'comparison.json'
    output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    output.with_suffix('.md').write_text(markdown(result), encoding='utf-8')
    print(json.dumps(dict(status=result['status'], acceptedRuns=len(selected), acceptedFrames=result['acceptedFrames'], output=str(output))))


if __name__ == '__main__':
    main()
