"""Validate and summarize physical removal and perspective benchmarks.

Read immutable reports only after measurement has finished.  No timing values
are synthesized: aggregate FPS is frames / summed native frame interval time.
"""
from pathlib import Path
import argparse
import datetime
import hashlib
import json
import math
import re


ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'verification/performance-1.2.7'
THRESHOLDS = {'16.67': 1e9 / 60, '33.33': 1e9 / 30,
              '50': 50_000_000, '100': 100_000_000}


def load(path):
    return json.loads(Path(path).read_text(encoding='utf-8-sig'))


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def quantile(sorted_values, fraction):
    return sorted_values[max(0, math.ceil(len(sorted_values) * fraction) - 1)]


def frame_stats(intervals):
    """Nearest-rank quantiles; 1% low uses the slowest ceil(N/100) intervals."""
    require(bool(intervals) and all(type(v) is int and v > 0 for v in intervals),
            'Positive integer nanosecond frame intervals required')
    values = sorted(intervals)
    frames, duration = len(values), sum(values)
    tail_count = max(1, math.ceil(frames * .01))
    counts = {key: sum(v > limit for v in values) for key, limit in THRESHOLDS.items()}
    return dict(frames=frames, intervalSeconds=duration / 1e9,
                meanFps=frames * 1e9 / duration, meanFrameMs=duration / frames / 1e6,
                medianFrameMs=quantile(values, .5) / 1e6,
                p95FrameMs=quantile(values, .95) / 1e6,
                p99FrameMs=quantile(values, .99) / 1e6,
                p99_9FrameMs=quantile(values, .999) / 1e6,
                maxFrameMs=values[-1] / 1e6,
                onePercentLowFps=tail_count * 1e9 / sum(values[-tail_count:]),
                onePercentTailFrames=tail_count,
                slowFrames=counts,
                slowFramesPerMinute={key: value * 60e9 / duration for key, value in counts.items()})


def distribution(values):
    """Event-level descriptive distribution; does not pretend these are frame FPS."""
    if not values:
        return None
    ordered = sorted(values)
    return dict(count=len(values), mean=sum(values) / len(values),
                median=quantile(ordered, .5), p95=quantile(ordered, .95),
                maximum=ordered[-1], minimum=ordered[0])


def report_path(manifest):
    if manifest.get('reportPath'):
        return Path(manifest['reportPath'])
    return Path(manifest['runtime']) / 'emfattachments-tests' / manifest['label'] / 'equipped-crowd-frames.json'


def repeat_summary(rows):
    result = {}
    for key in ('meanFps', 'meanFrameMs', 'p99FrameMs', 'p99_9FrameMs', 'onePercentLowFps'):
        values = [row[key] for row in rows]
        result[key] = dict(minimum=min(values), maximum=max(values),
                           meanOfRepeatValues=sum(values) / len(values))
    return result


def no_false_flags(value, context):
    require(isinstance(value, dict) and value, f'{context}: explicit checks missing')
    require(all(flag is True for flag in value.values()), f'{context}: not every restoration check passed')

SCENES = ['world-first', 'world-switches', 'world-third', 'deck-first',
          'deck-switches', 'deck-third', 'deck-crowd-first-24',
          'deck-crowd-third-24', 'near-deck-first', 'near-deck-third']
GROUND_CROWD_SCENE = 'world-crowd-third-24'


def select_scenes(pattern=None, include_ground_crowd=False):
    available = list(SCENES)
    if include_ground_crowd:
        available.insert(3, GROUND_CROWD_SCENE)
    try:
        selected = [scene for scene in available if re.fullmatch(pattern or '.*', scene)]
    except re.error as error:
        raise ValueError(f'Invalid scene filter: {error}') from error
    require(selected, 'Scene filter selected no supported scenes')
    return selected


def scene_pattern(scenes):
    # Send exact names to Java, avoiding Python/Java regex-dialect differences.
    return '(?:' + '|'.join(scenes) + ')'


def expected_scenes(plan):
    selected = select_scenes(plan.get('sceneFilter'), plan.get('includeGroundCrowd', False))
    require(plan.get('scenes', SCENES) == selected, 'Frozen scene list differs from filter/native order')
    return selected


def switch_events(stage, raw, first_third_scene='world-switches'):
    ordinals = stage['frameCameraTypeOrdinals']
    boundaries = [0]
    for value in raw:
        boundaries.append(boundaries[-1] + value)
    events = []
    prior_index = -1
    names = {'FIRST_PERSON': 0, 'THIRD_PERSON_BACK': 1}
    switches = stage.get('cameraSwitches', [])
    actual_changes = [i for i in range(1, len(ordinals)) if ordinals[i] != ordinals[i-1]]
    require(actual_changes == [e['frameIndex'] for e in switches],
            'Camera changes differ from per-frame ordinals')
    for sequence, event in enumerate(switches):
        index, offset = event['frameIndex'], event['offsetNanos']
        require(type(index) is int and prior_index < index < len(raw), 'Invalid switch frame order')
        require(type(offset) is int and boundaries[index] <= offset <= boundaries[index+1],
                'Switch time outside associated frame interval')
        require(event['from'] in names and event['to'] in names and event['from'] != event['to'],
                'Unsupported/unchanged camera switch')
        require(ordinals[index] == names[event['to']] and (index == 0 or ordinals[index-1] == names[event['from']]),
                'Switch types disagree with native camera ordinals')
        end = index + 1
        while end < len(raw) and boundaries[end] < offset + 500_000_000:
            end += 1
        events.append(dict(sequence=sequence, frameIndex=index, offsetNanos=offset,
            direction=f"{event['from']}->{event['to']}", firstInScene=sequence == 0,
            firstThirdPersonActivationInRun=stage['sceneId'] == first_third_scene and sequence == 0,
            firstFrameMs=raw[index] / 1e6, first500ms=frame_stats(raw[index:end]),
            windowEndExclusive=end, windowComplete=boundaries[end] >= offset + 500_000_000,
            windowSpanAfterEventMs=(boundaries[end]-offset) / 1e6,
            firstFramePreEventPortionMs=(offset-boundaries[index]) / 1e6,
            activationToFrameEndMs=(boundaries[index+1]-offset) / 1e6))
        prior_index = index
    if stage['sceneId'].endswith('-switches'):
        require(len(events) >= 8, 'Fewer than eight camera changes in full switch scene')
    else:
        require(not events, 'Unexpected camera changes in steady view')
    return events


def inspect_stage(stage, settings, measure_seconds, first_third_scene='world-switches'):
    name = stage['sceneId']
    require(name in SCENES or name == GROUND_CROWD_SCENE, f'Unexpected scene {name}')
    raw = stage['frameIntervalsNanos']
    stats = frame_stats(raw)
    require(stage['sampleCount'] == len(raw) and stage['durationNanos'] == sum(raw), f'{name}: raw count/duration mismatch')
    require(sum(raw) >= measure_seconds * 1e9, f'{name}: duration too short')
    require(stage.get('renderSettings') == settings and stage.get('firstPersonEnabled') is False, f'{name}: settings drift')
    require(stage.get('cameraLocked') is True and stage.get('actualViewLockedEveryMeasuredFrame') is True,
            f'{name}: camera guard failed')
    require(stage.get('actualViewSamples') == len(raw), f'{name}: native camera sample missing')
    for key in ('maximumActualViewYawError', 'maximumActualViewPitchError'):
        value = stage.get(key)
        require(isinstance(value, (int, float)) and math.isfinite(value) and 0 <= value <= .15,
                f'{name}: {key} exceeds guard')
    ordinals = stage['frameCameraTypeOrdinals']
    require(len(ordinals) == len(raw) and all(type(v) is int and v in (0, 1) for v in ordinals), f'{name}: camera ordinals invalid')
    first, third = ordinals.count(0), ordinals.count(1)
    require(stage['firstPersonFrameCount'] == first and stage['thirdPersonFrameCount'] == third, f'{name}: camera counts disagree')
    require(stage['thirdPersonFramesLocalBodyReturned'] == third, f'{name}: third-person local body missing')
    if not name.endswith('-switches'):
        require(first == len(raw) if '-first' in name else third == len(raw), f'{name}: wrong steady camera')
    on_deck, near_deck = name.startswith('deck-'), name.startswith('near-deck-')
    require(stage.get('observerOnDeck') is on_deck and stage.get('nearbyMovingDeck') is near_deck,
            f'{name}: wrong scene support classification')
    ticks = stage['measuredClientTicks']
    require(type(ticks) is int and ticks > 0, f'{name}: no native ticks')
    require(stage['localTrackedDeckTicks'] == (ticks if on_deck else 0)
            and stage['localUntrackedTicks'] == (0 if on_deck else ticks), f'{name}: native observer support mismatch')
    if on_deck or near_deck:
        require(stage['platformDriveTickAtSceneEnd'] > stage['platformDriveTickAtSceneStart'], f'{name}: deck did not move')
    if on_deck:
        require(stage.get('maximumLocalPlotHorizontalDrift', float('inf')) <= math.sqrt(2) * .3, f'{name}: observer drift')
    count = 24 if name.startswith('deck-crowd-') or name == GROUND_CROWD_SCENE else 0
    require(stage['players'] == count and len(stage['entities']) == count, f'{name}: wrong crowd count')
    require(stage['rendererWorkloadComparable'] is True and stage['framesAllClonesReturned'] == len(raw)
            and stage['framesMissingSomeClone'] == 0, f'{name}: visible crowd actor omitted')
    for entity in stage['entities']:
        require(entity['resident'] is True and entity['trackingMatchesOwnedDeck'] is on_deck, f'{name}: actor absent/wrong support')
        if not on_deck:
            require(entity.get('trackingSublevel') == '' and entity.get('plotPosition') == [], f'{name}: ground actor is tracked')
        require(entity['nativeRendererReturns'] >= len(raw) and entity['accessoryDispatches'] > 0
                and entity['nativeFullTicks'] > 0, f'{name}: actor workload absent')
        loadout = entity['loadout']
        require(5 <= loadout['accessoryCount'] <= 6 and len(loadout['accessories']) == loadout['accessoryCount']
                and len(loadout['equipment']) == 6, f'{name}: incomplete equipment workload')
        require(all(item.get('visible') is True and item.get('renderer') for item in loadout['accessories']),
                f'{name}: accessory visibility/renderer metadata missing')
    if count:
        require(len({e['loadout']['profile'] for e in stage['entities']}) == 6
                and len({e['skin']['model'] for e in stage['entities']}) == 2, f'{name}: incomplete loadout/body-type diversity')
    events = switch_events(stage, raw, first_third_scene)
    result = dict(scene=name, **stats, cameraSwitches=events,
        nativeCoverage={k: stage.get(k) for k in ('actualViewSamples', 'firstPersonFrameCount', 'thirdPersonFrameCount',
            'thirdPersonFramesLocalBodyReturned', 'localBodyRendererReturns', 'localAccessoryDispatches',
            'measuredClientTicks', 'localTrackedDeckTicks', 'localUntrackedTicks', 'framesAllClonesReturned', 'framesMissingSomeClone',
            'maximumLocalPlotHorizontalDrift')},
        cameraTypeStats={camera: frame_stats([v for v, o in zip(raw, ordinals) if o == code])
            for camera, code in (('FIRST_PERSON', 0), ('THIRD_PERSON_BACK', 1)) if code in ordinals},
        view={key: stage.get(key) for key in ('localPosition', 'cameraPosition', 'cameraYaw', 'cameraPitch',
            'platformLogicalPosition', 'minimumCameraEyeDistance', 'maximumCameraEyeDistance', 'unlockedCameraActiveAtEnd',
            'maximumLocalPlotHorizontalDrift')},
        gc=stage.get('gc'), entities=stage['entities'], screenshot=stage.get('afterMeasurementScreenshot'),
        actorWorkloadRates=[dict(index=entity['index'], nativeFullTicksPerClientTick=entity['nativeFullTicks']/ticks,
            accessoryDispatchesPerRendererReturn=entity['accessoryDispatches']/entity['nativeRendererReturns'])
            for entity in stage['entities']])
    return result, raw


def audit_disabled_shader_metadata(plan, manifest, shader_audit, report, output_directory=OUT):
    """Narrow supplemental attestation; never changes the original false shader audit."""
    output_directory = Path(output_directory).resolve()
    label = manifest['label']
    record = dict(label=label, valid=False,
                  rule='Disabled shaders only: exact Euphoria 1.10.5 version-marker addition; all other shader bytes/properties unchanged.')
    try:
        require(manifest.get('graphics') == 'NO_SHADERS', 'Supplement applies only to NO_SHADERS')
        require(report.get('label') == label and report.get('status') == 'CAPTURED' and report.get('finished') is True,
                'Completed matching native report required')
        require(report.get('renderSettings', {}).get('shadersEnabled') is False
                and [stage['sceneId'] for stage in report['stages']] == expected_scenes(plan)
                and all(stage.get('renderSettings', {}).get('shadersEnabled') is False for stage in report['stages']),
                'Every native scene must confirm shaders disabled')
        original_path = output_directory / (label + '-shader-audit.json')
        require(load(original_path) == shader_audit and shader_audit.get('label') == label
                and shader_audit.get('valid') is False and shader_audit.get('jvmExited') is True,
                'Original false, post-exit shader audit must be retained unchanged')
        baseline, observed = shader_audit['baselineSha256'], shader_audit['observedSha256']
        require(baseline == plan['shaderpackSha256'] and set(observed) == set(baseline), 'Shader inventory/baseline differs')
        filename = 'ComplementaryUnbound_r5.9.3 + EuphoriaPatches_1.10.5.txt'
        changed = sorted(key for key in baseline if baseline[key] != observed[key])
        require(changed == [filename], 'Any shader-file change beyond the selected settings file is disallowed')
        rows = shader_audit['differingFiles']
        require(len(rows) == 1 and rows[0]['path'] == filename, 'Unexpected original audit differences')
        row = rows[0]
        require(row['beforeSha256'] == baseline[filename] and row['afterSha256'] == observed[filename], 'Audit hash mismatch')
        before_path = Path(plan['baselineShaderpackPath']) / filename
        after_path = Path(row['savedPath'])
        expected_after = output_directory / (label + '-shader-after') / filename
        require(after_path.resolve() == expected_after.resolve(), 'Observed shader evidence outside its immutable run archive')
        require(sha(before_path) == baseline[filename] and sha(after_path) == observed[filename], 'Archived/baseline shader bytes changed')
        before_lines, after_lines = before_path.read_text(encoding='utf-8').splitlines(), after_path.read_text(encoding='utf-8').splitlines()
        import re
        timestamp = re.compile(r'^#[A-Za-z]{3} [A-Za-z]{3} +\d{1,2} \d{2}:\d{2}:\d{2} [A-Za-z0-9+:-]+ \d{4}$')
        require(before_lines and after_lines and timestamp.fullmatch(before_lines[0]) and timestamp.fullmatch(after_lines[0]),
                'Expected only Java-properties timestamp comments at the first line')
        before_body, after_body = before_lines[1:], after_lines[1:]
        key, flag = 'AAB_FOR_EUPHORIA_PATCHES_VERSION_1_10_5', 'AAB_FOR_EUPHORIA_PATCHES_VERSION_1_10_5=true'
        require(not any(line.split('=', 1)[0].strip() == key for line in before_body), 'Marker already exists in baseline')
        require(after_body.count(flag) == 1
                and [line for line in after_body if line != flag] == before_body,
                'Property delta must be exactly one true version-marker line, with every other line preserved')
        record.update(valid=True, recordedUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
            graphics='NO_SHADERS', allNativeScenesConfirmShadersDisabled=True, nativeSceneCount=len(report['stages']),
            originalShaderAuditPath=str(original_path), originalShaderAuditSha256=sha(original_path), originalAuditValid=False,
            reportPath=str(report_path(manifest)), reportSha256=sha(report_path(manifest)), filename=filename,
            baselineFileSha256=baseline[filename], observedFileSha256=observed[filename],
            addedProperty={key: 'true'}, removedProperties={}, changedProperties={},
            everyOtherShaderFileHashUnchanged=True, everyOtherSettingsLineUnchanged=True,
            timestampCommentChanged=before_lines[0] != after_lines[0], productionOrRuntimeSettingsModified=False)
        target = output_directory / (label + '-disabled-shader-metadata-audit.json')
        if target.exists():
            existing = load(target)
            require({k:v for k,v in existing.items() if k != 'recordedUtc'} == {k:v for k,v in record.items() if k != 'recordedUtc'},
                    'Existing supplemental attestation differs; refusing replacement')
            return existing
        target.write_text(json.dumps(record, indent=2)+'\n', encoding='utf-8')
    except (OSError, ValueError, KeyError, TypeError) as error:
        record.update(valid=False, rejection=str(error))
    return record


def validate_provenance(manifest, plan):
    require(manifest.get('valid') is True and manifest.get('smoke') is False, 'Invalid preflight or smoke-only run')
    mode, graphics = manifest['mode'], manifest['graphics']
    require(mode in ('INSTALLED', 'REMOVED') and graphics in ('SHADERS', 'NO_SHADERS'), 'Unexpected mode')
    number = manifest['passNumber']
    require(type(number) is int and 1 <= number <= len(plan['order']), 'Invalid pass number')
    require(list(plan['order'][number-1]) == [mode, graphics], 'Run differs from frozen pass order')
    require(manifest.get('sceneFilter') == plan.get('sceneFilter'), 'Run scene filter differs from frozen plan')
    require(manifest.get('scenes', SCENES) == expected_scenes(plan), 'Run scene list differs from frozen plan')
    require(manifest.get('includeGroundCrowd', False) == plan.get('includeGroundCrowd', False), 'Ground crowd opt-in differs from plan')
    for key in ('warmupSeconds', 'measureSeconds'):
        require(manifest[key] == plan[key], f'Manifest {key} differs from plan')
    require(manifest['harnessArtifact'] == plan['harness'], 'Harness differs from latest plan')
    require(manifest['compatArtifact'] == (plan['production'] if mode == 'INSTALLED' else None), 'Production differs from plan')
    expected = dict(plan['sharedModSha256'])
    for key in ('harness',) + (('production',) if mode == 'INSTALLED' else ()):
        artifact = plan[key]
        require(sha(artifact['path']) == artifact['sha256'], 'Frozen artifact changed')
        expected[artifact['name']] = artifact['sha256']
    require(manifest['activeModSha256'] == expected, 'Physical jar selection differs from plan')
    require(manifest.get('firstPersonPhysicallyInstalled') is False
            and manifest.get('productionPhysicallyInstalled') is (mode == 'INSTALLED'), 'Physical mod-state confirmation missing')
    require(manifest['optionsSha256'] == plan['optionsSha256'], 'Options differ from baseline')
    before, after = plan['baselineConfigSha256'], manifest['configSha256']
    changed = sorted(key for key in set(before) | set(after) if before.get(key) != after.get(key))
    require(changed == ([] if graphics == 'SHADERS' else ['iris.properties']), 'Unexpected configuration drift')
    return mode, graphics


def inspect_run(manifest_path, plan_path=None):
    manifest_path = Path(manifest_path)
    manifest = load(manifest_path)
    plan_path = Path(plan_path or manifest_path.parent / 'execution-plan.json')
    record = {k: manifest.get(k) for k in ('label', 'mode', 'graphics', 'passNumber', 'attempt', 'smoke')}
    record.update(manifestPath=str(manifest_path), manifestSha256=sha(manifest_path), accepted=False)
    try:
        plan = load(plan_path)
        require(manifest['planSha256'] == sha(plan_path), 'Superseded or changed plan')
        mode, graphics = validate_provenance(manifest, plan)
        source = report_path(manifest)
        record.update(reportPath=str(source), reportSha256=sha(source))
        report = load(source)
        require(report.get('status') == 'CAPTURED' and report.get('finished') is True,
                f'Native run not CAPTURED: {report.get("status")}: {report.get("detail", "")}')
        require(report.get('label') == manifest['label'] and Path(report['runtime']).resolve() == Path(manifest['runtime']).resolve(),
                'Native label/runtime mismatch')
        scenes = expected_scenes(plan)
        require(report.get('perspectiveComparison') is True and report.get('plannedScenes') == scenes, 'Wrong native scene plan')
        require(report.get('perspectiveSceneFilter') == (plan.get('sceneFilter') or '.*'), 'Wrong native scene filter')
        require(report.get('perspectiveIncludeGroundCrowd', False) == plan.get('includeGroundCrowd', False), 'Wrong native ground crowd opt-in')
        require(report.get('compatInstalled') is (mode == 'INSTALLED') and report.get('firstPersonInstalled') is False, 'Native mod-state mismatch')
        require(report.get('firstPersonEnabled') is False and report.get('fullDeveloperHarnessInstalled') is False
                and report.get('productionMixinsDisabledProperty') is False, 'Unexpected harness/disable flag')
        require(isinstance(report.get('resourcePacks'), list) and report['resourcePacks']
                and all(isinstance(pack, str) for pack in report['resourcePacks']), 'Native selected resource packs missing')
        require(not report.get('behaviorFailures'), 'Native behavior validation failed')
        require(report.get('cleanupOnlyPreflight') is False, 'Cleanup-only run')
        for key in ('warmupSeconds', 'measureSeconds', 'firstSceneExtraWarmupSeconds'):
            require(report.get(key) == plan[key], f'{key} differs from plan')
        settings = report['renderSettings']
        for key, expected in (('width',2560), ('height',1440), ('fov',90), ('fullscreen',True), ('vsync',False),
                ('maxFps',260), ('renderDistance',8), ('simulationDistance',8), ('focused',True), ('shadersEnabled',graphics == 'SHADERS')):
            require(settings.get(key) == expected, f'Native setting differs: {key}={settings.get(key)!r}')
        no_false_flags(report.get('cleanup'), 'Cleanup')
        restoration = report['restorationEvidence']
        for phase in ('immediateServer', 'settledServer'):
            for key in ('matches','positionMatches','inheritedVelocityMatches','supportMatches','flagsMatch','rotationMatches'):
                require(restoration[phase].get(key) is True, f'Cleanup failed: {phase}.{key}')
        for key in ('movementMatches','fallDistanceMatches'):
            require(restoration['immediateServer'].get(key) is True, f'Immediate cleanup failed: {key}')
        for key in ('clientPositionMatches','clientPhysicsMatches','clientSupportMatches'):
            require(restoration.get(key) is True, f'Client cleanup failed: {key}')
        identity_path = manifest_path.with_name(manifest['label']+'-process.json')
        identity = load(identity_path)
        require(identity.get('offlineIdentityConfirmed') is True and identity.get('productionDisableFlagAbsent') is True
                and Path(identity['userDir']).resolve() == Path(manifest['runtime']).resolve(), 'Offline process audit failed')
        execution_path = manifest_path.with_name(manifest['label']+'-execution.json')
        execution = load(execution_path)
        require(execution.get('nativeStatus') == 'CAPTURED' and execution.get('finishedUtc')
                and execution.get('reportSha256') == record['reportSha256'], 'Finished execution/report provenance missing')
        audit = execution['shaderAudit']
        supplemental_shader_audit = None
        if audit.get('valid') is not True:
            supplemental_shader_audit = audit_disabled_shader_metadata(plan, manifest, audit, report, manifest_path.parent)
            require(supplemental_shader_audit['valid'],
                    'Shader byte audit failed: ' + supplemental_shader_audit.get('rejection', 'unapproved change'))
        require(audit.get('jvmExited') is True and audit.get('baselineSha256') == plan['shaderpackSha256'],
                'Shader baseline/post-exit confirmation failed')
        require(identity['observedUtc'] <= execution['triggerArmedUtc'], 'Identity audit after capture trigger')
        stages, raw = [], {}
        require([s['sceneId'] for s in report['stages']] == scenes, 'Missing/reordered scenes')
        first_third_scene = next((name for name in scenes if '-third' in name or name.endswith('-switches')), None)
        for stage in report['stages']:
            result, intervals = inspect_stage(stage, settings, plan['measureSeconds'], first_third_scene)
            stages.append(result)
            raw[result['scene']] = intervals
        record.update(accepted=True, stages=stages, raw=raw, settings=settings, configSha256=manifest['configSha256'],
            cleanup=report['cleanup'], processAuditSha256=sha(identity_path), executionSha256=sha(execution_path),
            harnessArtifact=manifest['harnessArtifact'], compatArtifact=manifest['compatArtifact'],
            supplementalDisabledShaderMetadataAudit=supplemental_shader_audit,
            localLoadout=report['clientLocalLoadout'],
            resourcePacks=report.get('resourcePacks'),
            crowdWorkload={stage['sceneId']: [dict(index=e['index'], loadout=e['loadout'], skin=e['skin'])
                for e in stage['entities']] for stage in report['stages']}, limitations=report.get('limitations', []))
    except (OSError, ValueError, KeyError, TypeError, IndexError) as error:
        record['rejection'] = str(error)
    return record

def summarize_events(events, windows):
    complete = [event for event in events if event['windowComplete']]
    return dict(eventCount=len(events), complete500msWindowCount=len(complete),
                incomplete500msWindowCount=len(events)-len(complete),
                firstFrameMs=distribution([event['firstFrameMs'] for event in events]),
                firstFramePreEventPortionMs=distribution([event['firstFramePreEventPortionMs'] for event in events]),
                activationToFrameEndMs=distribution([event['activationToFrameEndMs'] for event in events]),
                activationToFrameEndOverMs={key: sum(event['activationToFrameEndMs']*1e6 > limit for event in events)
                                            for key,limit in THRESHOLDS.items()},
                firstFramesOverMs={key: sum(event['firstFrameMs']*1e6 > limit for event in events)
                                   for key, limit in THRESHOLDS.items()},
                complete500msPooledFrames=frame_stats(windows) if windows else None,
                windowP99Ms=distribution([event['first500ms']['p99FrameMs'] for event in complete]),
                windowMeanFrameMs=distribution([event['first500ms']['meanFrameMs'] for event in complete]))


def combine(runs, scenes=SCENES):
    groups = {}
    for graphics in ('SHADERS', 'NO_SHADERS'):
        for mode in ('REMOVED', 'INSTALLED'):
            chosen = [run for run in runs if run['graphics'] == graphics and run['mode'] == mode]
            if not chosen:
                continue
            scene_results = {}
            for scene in scenes:
                individual = [dict(label=run['label'], passNumber=run['passNumber'],
                    **next(stage for stage in run['stages'] if stage['scene'] == scene)) for run in chosen]
                merged = frame_stats([value for run in chosen for value in run['raw'][scene]])
                merged.update(repeatCount=len(chosen), repeatRanges=repeat_summary(individual),
                    individualRuns=individual)
                if scene.endswith('-switches'):
                    categories = {}
                    predicates = {
                        'all': lambda event: True,
                        'firstInScene': lambda event: event['firstInScene'],
                        'firstThirdPersonActivationInRun': lambda event: event['firstThirdPersonActivationInRun'],
                        'laterToThirdPerson': lambda event: event['direction'].endswith('->THIRD_PERSON_BACK')
                            and not event['firstThirdPersonActivationInRun'],
                        'returnToFirstPerson': lambda event: event['direction'].endswith('->FIRST_PERSON')}
                    for key, predicate in predicates.items():
                        selected, windows = [], []
                        for run, stage in zip(chosen, individual):
                            for event in stage['cameraSwitches']:
                                if not predicate(event):
                                    continue
                                selected.append(dict(label=run['label'], **event))
                                if event['windowComplete']:
                                    windows.extend(run['raw'][scene][event['frameIndex']:event['windowEndExclusive']])
                        if selected:
                            categories[key] = dict(**summarize_events(selected, windows), events=selected)
                    merged['switchEventGroups'] = categories
                scene_results[scene] = merged
            groups[f'{graphics}/{mode}'] = dict(graphics=graphics, mode=mode,
                repeatCount=len(chosen), scenes=scene_results, settings=chosen[0]['settings'])
    return groups


def distance(left, right):
    return math.sqrt(sum((a-b)**2 for a,b in zip(left,right))) if left is not None and right is not None else None


def paired_view(after, before):
    a, b = after['view'], before['view']
    def relative(view):
        camera, deck = view.get('cameraPosition'), view.get('platformLogicalPosition')
        return [x-y for x,y in zip(camera, deck)] if camera is not None and deck is not None else None
    return dict(cameraWorldPositionDistanceBlocks=distance(a.get('cameraPosition'), b.get('cameraPosition')),
        localWorldPositionDistanceBlocks=distance(a.get('localPosition'), b.get('localPosition')),
        cameraOffsetFromPlatformDistanceBlocks=distance(relative(a),relative(b)),
        installedMaximumLocalPlotHorizontalDrift=a.get('maximumLocalPlotHorizontalDrift'),
        removedMaximumLocalPlotHorizontalDrift=b.get('maximumLocalPlotHorizontalDrift'),
        cameraYawDifferenceDegrees=a['cameraYaw']-b['cameraYaw'], cameraPitchDifferenceDegrees=a['cameraPitch']-b['cameraPitch'],
        interpretation='End-of-measurement samples; moving-platform phase can differ. Native yaw/pitch and per-tick support/axis-drift guards independently constrain every run.')


def comparisons(groups, scenes=SCENES):
    rows = []
    for graphics in ('SHADERS', 'NO_SHADERS'):
        before_group, after_group = groups.get(f'{graphics}/REMOVED'), groups.get(f'{graphics}/INSTALLED')
        if not before_group or not after_group:
            continue
        for scene in scenes:
            before, after = before_group['scenes'][scene], after_group['scenes'][scene]
            pairs = []
            for a, b in zip(after['individualRuns'], before['individualRuns']):
                pairs.append(dict(installedLabel=a['label'], removedLabel=b['label'],
                    installedMeanFps=a['meanFps'], removedMeanFps=b['meanFps'],
                    meanFpsPercentChange=(a['meanFps']/b['meanFps']-1)*100,
                    p99FrameMsDifference=a['p99FrameMs']-b['p99FrameMs'], viewComparison=paired_view(a,b)))
            lower = all(pair['meanFpsPercentChange'] < 0 for pair in pairs)
            higher = all(pair['meanFpsPercentChange'] > 0 for pair in pairs)
            ar, br = after['repeatRanges']['meanFps'], before['repeatRanges']['meanFps']
            overlap = not (ar['maximum'] < br['minimum'] or br['maximum'] < ar['minimum'])
            rows.append(dict(graphics=graphics, scene=scene, removed=before, installed=after,
                meanFpsPercentChange=(after['meanFps']/before['meanFps']-1)*100,
                meanFrameMsDifference=after['meanFrameMs']-before['meanFrameMs'],
                p99FrameMsDifference=after['p99FrameMs']-before['p99FrameMs'],
                p99_9FrameMsDifference=after['p99_9FrameMs']-before['p99_9FrameMs'],
                onePercentLowFpsPercentChange=(after['onePercentLowFps']/before['onePercentLowFps']-1)*100,
                repeatMeanFpsRangesOverlap=overlap, adjacentRunPairs=pairs,
                repeatedSameDirection=len(pairs) > 1 and (lower or higher),
                observedDirection='lower' if lower else 'higher' if higher else 'mixed',
                inferenceScope='Two-repeat descriptive comparison; no statistical-significance claim.' if len(pairs)>1
                    else 'One diagnostic pair; repeatability is unmeasured.'))
    return rows


def scenario_contrasts(groups):
    pairs = [('world-first', 'world-third'), ('near-deck-first', 'near-deck-third'),
             ('deck-first', 'deck-third'), ('deck-crowd-first-24', 'deck-crowd-third-24')]
    return [dict(group=key, firstPersonScene=first, thirdPersonScene=third,
            firstPersonFps=group['scenes'][first]['meanFps'], thirdPersonFps=group['scenes'][third]['meanFps'],
            meanFpsPercentChange=(group['scenes'][third]['meanFps']/group['scenes'][first]['meanFps']-1)*100,
            p99FrameMsDifference=group['scenes'][third]['p99FrameMs']-group['scenes'][first]['p99FrameMs'],
            interpretation='Different camera perspective and local-body workload; this is total scenario cost, not isolated compatibility overhead.')
        for key, group in groups.items() for first, third in pairs if first in group['scenes'] and third in group['scenes']]


def markdown(result):
    lines = [f"# Perspective and Sable performance: {result['design'].get('version', '1.2.7')} versus physical removal", '',
        f"Status: **{result['status']}**. {len(result['acceptedRuns'])} accepted fresh-process runs; "
        f"{result['acceptedFrames']:,} native frame intervals across {result['acceptedIntervalSeconds']:.2f} measured seconds.", '',
        'FirstPerson is physically absent. Shader runs have two repeats per state in removed/installed/installed/removed order; '
        + ('shader-disabled results are a single diagnostic pair. ' if any(row['graphics'] == 'NO_SHADERS' for row in result['comparisons']) else '')
        + 'Smoke runs and invalid or superseded attempts are excluded.', '',
        'Measured scenes: ' + ', '.join(result['design']['scenes']) + '. '
        + ('This is the historical full perspective matrix.' if result['design']['scenes'] == SCENES else 'This is the frozen selected scene subset, not the full historical matrix.'), '',
        '| Graphics | Scene | Removed FPS | Installed FPS | Change | Removed / installed 1% low | Removed / installed p99 ms |',
        '| --- | --- | ---: | ---: | ---: | ---: | ---: |']
    for row in result['comparisons']:
        b, a = row['removed'], row['installed']
        lines.append(f"| {row['graphics']} | {row['scene']} | {b['meanFps']:.2f} | {a['meanFps']:.2f} | "
            f"{row['meanFpsPercentChange']:+.2f}% | {b['onePercentLowFps']:.2f} / {a['onePercentLowFps']:.2f} | "
            f"{b['p99FrameMs']:.3f} / {a['p99FrameMs']:.3f} |")
    lines += ['', 'Repeat ranges and adjacent comparisons:', '',
        '| Scene | Removed FPS range | Installed FPS range | Pair 1 change | Pair 2 change |',
        '| --- | ---: | ---: | ---: | ---: |']
    for row in result['comparisons']:
        if row['graphics'] != 'SHADERS':
            continue
        br, ar = row['removed']['repeatRanges']['meanFps'], row['installed']['repeatRanges']['meanFps']
        pairs = row['adjacentRunPairs']
        changes = [f"{pair['meanFpsPercentChange']:+.2f}%" for pair in pairs] + ['n/a', 'n/a']
        lines.append(f"| {row['scene']} | {br['minimum']:.2f}-{br['maximum']:.2f} | "
            f"{ar['minimum']:.2f}-{ar['maximum']:.2f} | {changes[0]} | {changes[1]} |")
    switch_section_start = len(lines)
    lines += ['', 'Camera-switch events (frame times include the native activation interval):', '',
        '| Graphics / mod | Scene | Events | First activation frame ms | Later to-third median / p95 ms | Return-to-first median / p95 ms |',
        '| --- | --- | ---: | --- | ---: | ---: |']
    for key, group in result['combinedByState'].items():
        for scene in ('world-switches', 'deck-switches'):
            if scene not in group['scenes']:
                continue
            events = group['scenes'][scene]['switchEventGroups']
            first = events.get('firstThirdPersonActivationInRun', {}).get('events', [])
            first_text = ', '.join(f"{event['firstFrameMs']:.3f}" for event in first) or 'already activated earlier'
            def event_text(category):
                data = events.get(category, {}).get('firstFrameMs')
                return f"{data['median']:.3f} / {data['p95']:.3f}" if data else 'n/a'
            lines.append(f"| {key} | {scene} | {events['all']['eventCount']} | {first_text} | "
                f"{event_text('laterToThirdPerson')} | {event_text('returnToFirstPerson')} |")
    lines += ['', 'The interval above begins at the previous frame end, so a stall can precede camera activation. '
        'The following values begin at the recorded activation marker and end at that same frame end; '
        'they include native switching and rendering work but do not isolate CPU/GPU time.', '',
        '| Graphics / mod | Scene | First activation after-marker ms | Later to-third after-marker median / p95 ms | Return-to-first after-marker median / p95 ms |',
        '| --- | --- | --- | ---: | ---: |']
    for key, group in result['combinedByState'].items():
        for scene in ('world-switches', 'deck-switches'):
            if scene not in group['scenes']:
                continue
            events = group['scenes'][scene]['switchEventGroups']
            first = events.get('firstThirdPersonActivationInRun', {}).get('events', [])
            first_text = ', '.join(f"{event['activationToFrameEndMs']:.3f}" for event in first) or 'already activated earlier'
            def post_event_text(category):
                data = events.get(category, {}).get('activationToFrameEndMs')
                return f"{data['median']:.3f} / {data['p95']:.3f}" if data else 'n/a'
            lines.append(f"| {key} | {scene} | {first_text} | "
                f"{post_event_text('laterToThirdPerson')} | {post_event_text('returnToFirstPerson')} |")
    if not any(scene.endswith('-switches') for scene in result['design']['scenes']):
        lines = lines[:switch_section_start]
        lines += ['', 'No camera-switch scenes were measured; these results do not test switching hitches.']
    lines += ['', 'The JSON retains available switch events and window statistics, '
        'median/p95/p99/p99.9/max frame times, 1% lows, slow-frame counts and rates, native workload checks, '
        'repeat ranges, GC totals and immutable provenance.', '',
        'FPS is total frames divided by total raw interval time. Quantiles use nearest rank over pooled intervals; '
        '1% low is 1000 divided by the mean milliseconds of the slowest ceil(1% of frames). '
        'A switch window includes full boundary frames through the first frame ending at least 500 ms after activation. '
        'Incomplete windows remain recorded but are excluded from pooled 500 ms summaries.', '',
        'These finite repeats describe this isolated world, equipment and shader configuration. A slow maximum frame by itself '
        'does not identify a cause; use repeated deltas and event distributions. Frame timings do not identify CPU/GPU causality. '
        'When a switch scene is selected, a recorded activation is not a guaranteed cold asset or driver cache. '
        'Near-deck and ordinary-world views use different framing. '
        'Live multiplayer timing, a complex ship/factory and the user\'s exact lag scene are outside this fixture. '
        'Screenshots still require independent visual review.', '',
        'The native maxFps option is 260 with VSync off; 260 is the unlimited selection in this setup and must not be '
        'treated as a measured 260 FPS ceiling. No frame-cap conclusion is inferred from that option alone.', '']
    if result['rejectedOrAdditionalRuns']:
        lines += ['Excluded/retained attempts:', '']
        lines += [f"- {run['label']}: {run.get('rejection', run.get('exclusion', 'Additional valid attempt; earliest valid pass selected'))}"
                  for run in result['rejectedOrAdditionalRuns']]
    return '\n'.join(lines) + '\n'


def normalized_workload(value):
    # Curios emits a new UUID-named adapter class in each fresh JVM. Preserve
    # all item/slot/visibility/renderer-family data; normalize only this suffix.
    if isinstance(value, dict):
        import re
        return {key: ('top.theillusivec4.curios.api.client.CuriosRendererRegistry$Anonymous$<generated>'
                      if key == 'renderer' and isinstance(item, str) and re.fullmatch(
                          r'top\.theillusivec4\.curios\.api\.client\.CuriosRendererRegistry\$Anonymous\$[0-9a-f]{32}', item)
                      else normalized_workload(item)) for key,item in value.items()}
    if isinstance(value, list):
        return [normalized_workload(item) for item in value]
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', '--out', dest='directory', type=Path, default=OUT)
    parser.add_argument('--plan', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    directory = args.directory.resolve()
    plan_path = args.plan or directory / 'execution-plan.json'
    plan = load(plan_path)
    scenes = expected_scenes(plan)
    label_prefix = plan.get('labelPrefix', 'perspective127')
    require(re.fullmatch(r'[A-Za-z0-9_-]+', label_prefix), 'Invalid plan label prefix')
    manifests = sorted(directory.glob(label_prefix + '-*-manifest.json'))
    require(manifests, 'No perspective benchmark manifests found')
    exclusions_path = directory / 'exclusions.json'
    exclusions = load(exclusions_path) if exclusions_path.exists() else {}
    inspected = [inspect_run(path, plan_path) for path in manifests]
    selected, rejected = [], []
    for number in range(1, len(plan['order'])+1):
        chosen = False
        for run in sorted((r for r in inspected if r['passNumber'] == number), key=lambda r: r['attempt']):
            if run['label'] in exclusions:
                run['exclusion'] = exclusions[run['label']]
            elif run['accepted'] and not chosen:
                selected.append(run)
                chosen = True
                continue
            rejected.append(run)
    # Settings/config equality is required within each graphics condition, including across mod states.
    for graphics in ('SHADERS', 'NO_SHADERS'):
        condition = [run for run in selected if run['graphics'] == graphics]
        if condition:
            require(all(run['settings'] == condition[0]['settings'] and run['configSha256'] == condition[0]['configSha256']
                        for run in condition), f'{graphics}: accepted settings/config differ between runs')
    if selected:
        require(all(run['resourcePacks'] == selected[0]['resourcePacks'] for run in selected),
                'Selected native resource packs differ between accepted runs')
        require(all(normalized_workload(run['localLoadout']) == normalized_workload(selected[0]['localLoadout'])
                    and normalized_workload(run['crowdWorkload']) == normalized_workload(selected[0]['crowdWorkload']) for run in selected),
                'Equipped local/crowd workloads differ between accepted runs')
    groups = combine(selected, scenes)
    complete = len(selected) == len(plan['order'])
    result = dict(status='COMPLETE_TIMINGS_REQUIRE_VISUAL_REVIEW' if complete else 'INCOMPLETE',
        analyzedUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(), analyzerSha256=sha(__file__),
        activePlan=dict(path=str(plan_path), sha256=sha(plan_path), harness=plan.get('harness'), production=plan['production']),
        design=dict(version=plan.get('version', '1.2.7'), order=plan['order'], scenes=scenes, historicalTimingReuse=False,
            sceneScope='historical-full-matrix' if scenes == SCENES else 'frozen-selected-subset',
            selection='Earliest valid non-excluded attempt for each planned pass, never selected by favorable timing.',
            meanFps='Total accepted frame count / summed native interval time.',
            onePercentLow='1e9 / mean nanoseconds of slowest ceil(1%N) intervals.',
            percentiles='Nearest rank of pooled raw native frame intervals.',
            equipmentComparison='Exact metadata comparison except CuriosRendererRegistry$Anonymous$32hexUUID generated class suffix; all original names retained.',
            first500ms='Whole boundary frames from marked activation through first frame ending >=500ms later; incomplete windows excluded.'),
        acceptedRuns=[{k:v for k,v in run.items() if k != 'raw'} for run in selected],
        rejectedOrAdditionalRuns=[{k:v for k,v in run.items() if k != 'raw'} for run in rejected],
        combinedByState=groups, comparisons=comparisons(groups, scenes), scenarioContrasts=scenario_contrasts(groups),
        acceptedFrames=sum(len(values) for run in selected for values in run['raw'].values()),
        acceptedIntervalSeconds=sum(sum(values)/1e9 for run in selected for values in run['raw'].values()),
        limitations=['Two shader repeats per state are descriptive, not a statistical guarantee.',
            'Shader-disabled condition, when planned, has only one diagnostic pair.',
            'Actor tick/renderer ratios are retained per run; workload differences are not isolated compatibility overhead.',
            'Native isolated client clones omit network/server/skin download behavior.',
            'Different camera types/framing change visible geometry and rendering workload.',
            'Frame intervals and aggregate GC counts do not attribute a CPU/GPU cause.',
            'Only selected scenes are measured; asset/driver caches are not forcibly purged and absent switch scenes give no switch-hitch evidence.',
            'maxFps260 is native unlimited selection here, not proof of an active 260fps cap.',
            'Native coverage requires separate screenshot review.'])
    output = args.output or directory / 'comparison.json'
    output.write_text(json.dumps(result, indent=2)+'\n', encoding='utf-8')
    output.with_suffix('.md').write_text(markdown(result), encoding='utf-8')
    print(json.dumps(dict(status=result['status'], acceptedRuns=len(selected), acceptedFrames=result['acceptedFrames'],
        excluded=[dict(label=run['label'], reason=run.get('rejection',run.get('exclusion','additional'))) for run in rejected], output=str(output))))


if __name__ == '__main__':
    main()
