"""Analyze new OURS / NOWHEEL / NOWHEEL / OURS native frame captures.

This script never launches the game or generates timing measurements. Capture
validity is separate from observed visibility/animation behavior: a valid run
with missing players is retained, labelled, and cannot win a matched-work FPS
comparison. No expected wall-culling policy is selected by the mod name.
"""
from pathlib import Path
import argparse
import copy
import datetime
import json
import math

import analyze_equipped_benchmark as common
import prepare_nowheel_comparison as preparation

ROOT, OUT = common.ROOT, preparation.OUT
ORDER, MODE_ARTIFACTS, SCENES = preparation.ORDER, preparation.MODE_ARTIFACTS, common.SCENES
PRIMARY = ['deck-walk-8', 'deck-walk-24', 'deck-occluded-walk-8']
WALL = 'deck-occluded-walk-8'
load, sha = common.load, common.sha
NATIVE_FIELDS = ('nativeFullTicks', 'nativeRendererReturns', 'accessoryDispatches',
                 'controllerMovementTicks', 'cumulativePath')
PROBE_PHASES = ['hidden-moving', 'emerge-past-wall', 'exposed-hold', 'camera-away',
                'camera-reentry', 'return-behind-wall', 'rehidden-hold']
PROBE_TICKS = [80, 120, 80, 60, 80, 120, 80]
SOURCE_COMMIT = '1896ddf2f6ee46bd5ab7aaff39e15ea834f6768d'
SOURCE_ROOT = f'https://github.com/lap2ka/nowheel/blob/{SOURCE_COMMIT}/'
SOURCE_NOTES = [
    dict(kind='source-only', claim='Nowheel registers a dynamic Entity Culling whitelist using SableCompat::onSubLevel when Sable Companion is loaded.',
         url=SOURCE_ROOT + 'versions/1.21.1-neoforge/src/main/java/dev/lapt/nowheel/NowheelMod.java'),
    dict(kind='source-only', claim='The Sable predicate accepts any Entity with non-null Sable getContaining(entity); it does not explicitly check player tracking, vehicle, or pending states.',
         url=SOURCE_ROOT + 'versions/1.21.1-neoforge/src/main/java/dev/lapt/nowheel/compat/sable/SableCompat.java'),
    dict(kind='scope', claim='Nowheel also changes Create/Flywheel Entity Culling integration. This whole-mod actor-scene comparison is not a Create factory benchmark or an isolated Sable-hook cost test.',
         url=SOURCE_ROOT + 'src/main/java/dev/lapt/nowheel/mixin/entity_culling/EntityCullingModBaseMixin.java'),
]


def finite_nonnegative(value):
    return type(value) in (int, float) and math.isfinite(value) and value >= 0


def integer_count(value):
    return type(value) is int and value >= 0


def finding(scene, code, detail, **extra):
    return dict(scene=scene, code=code, detail=detail, **extra)


def predicate_coverage(entities):
    rows = []
    for entity in entities:
        samples, active = entity.get('nowheelPredicateSamples'), entity.get('nowheelPredicateTrueSamples')
        if not integer_count(samples) or not integer_count(active) or active > samples:
            raise ValueError('Nowheel native predicate sample counts missing/inconsistent')
        rows.append(dict(index=entity['index'], samples=samples, activeSamples=active,
                         activeFraction=active / samples if samples else None))
    samples = sum(row['samples'] for row in rows)
    active = sum(row['activeSamples'] for row in rows)
    return dict(samples=samples, activeSamples=active,
                activeFraction=active / samples if samples else None,
                actorsEverMatching=sum(row['activeSamples'] > 0 for row in rows),
                actorsSampled=sum(row['samples'] > 0 for row in rows), perClone=rows,
                interpretation=('The native Nowheel predicate was sampled on these actors; only matching samples exercise its Sable whitelist.'
                                if active else 'No sampled actor matched the native Nowheel predicate. This state does not establish whether its fix works for plot-contained entities.'
                                if samples else 'No native Nowheel predicate samples in this state; this is expected when Nowheel is absent.'))


def normalized_stage(stage):
    """Validate capture/fixture structure; retain real missing-tick/render outcomes."""
    name = common.scene_id(stage)
    if name not in SCENES:
        raise ValueError(f'Unexpected scene {name!r}')
    intervals = common.pick(stage, 'frameIntervalsNanos', 'intervalsNanos', 'rawFrameIntervalsNanos')
    stats = common.frame_stats(intervals)
    frames, duration = len(intervals), sum(intervals)
    if stage.get('sampleCount') != frames or stage.get('durationNanos') != duration:
        raise ValueError(f'{name}: raw intervals require exact sample/duration accounting')
    if stats['intervalSeconds'] < 19:
        raise ValueError(f'{name}: less than 19 seconds of accepted native intervals')
    if stage.get('actualViewSamples') != frames or stage.get('cameraLocked') is not True or stage.get('actualViewLockedEveryMeasuredFrame') is not True:
        raise ValueError(f'{name}: actual main camera was not verified every measured frame')
    errors = [stage.get(key) for key in ('maximumActualViewYawError', 'maximumActualViewPitchError')]
    if any(not finite_nonnegative(error) or error > .15 for error in errors):
        raise ValueError(f'{name}: actual main camera exceeded the frozen .15 degree guard')
    clones = common.scene_clone_count(stage)
    expected_clones = 0 if name == 'local-lookdown-80' else int(name.rsplit('-', 1)[-1])
    if clones != expected_clones:
        raise ValueError(f'{name}: clone count differs from frozen scene')
    missing, returned = stage.get('framesMissingSomeClone'), stage.get('framesAllClonesReturned')
    if not integer_count(missing) or not integer_count(returned) or missing + returned != frames:
        raise ValueError(f'{name}: native render-frame coverage is missing/inconsistent')
    ticks = stage.get('measuredClientTicks')
    if not integer_count(ticks) or ticks == 0:
        raise ValueError(f'{name}: measured native client ticks missing')
    walking = '-walk-' in name
    if stage.get('walking') is not walking:
        raise ValueError(f'{name}: fixture walking declaration differs from frozen scene')
    entities = stage.get('entities')
    if not isinstance(entities, list) or len(entities) != clones:
        raise ValueError(f'{name}: expected one actual equipment row per requested clone')
    failures, native = [], []
    if name != WALL and missing:
        failures.append(finding(name, 'VISIBLE_PLAYER_FRAMES_MISSING',
                                f'{missing}/{frames} measured frames omitted at least one expected visible player.',
                                missingFrames=missing, measuredFrames=frames))
    profiles, models, indices = set(), set(), set()
    for entity in entities:
        index = entity.get('index')
        if not integer_count(index):
            raise ValueError(f'{name}: invalid clone index')
        indices.add(index)
        loadout = entity.get('loadout', {})
        profiles.add(loadout.get('profile'))
        accessories, equipment = loadout.get('accessories', []), loadout.get('equipment', [])
        if not 5 <= len(accessories) <= 6 or loadout.get('accessoryCount') != len(accessories):
            raise ValueError(f'{name}: clone {index} lacks 5–6 actual accessories')
        if any(not row.get('item') or not row.get('renderer') or row.get('visible') is not True for row in accessories):
            raise ValueError(f'{name}: actual visible accessory/renderer metadata incomplete')
        if len(equipment) != 6 or len({row.get('slot') for row in equipment}) != 6 or sum(row.get('item') != 'minecraft:air' for row in equipment) < 5:
            raise ValueError(f'{name}: actual equipment workload incomplete')
        if entity.get('resident') is not True:
            raise ValueError(f'{name}: requested actor {index} is absent from the fixture')
        skin = entity.get('skin', {})
        if skin.get('nativeGetSkinPath') is not True:
            raise ValueError(f'{name}: native skin/cape hooks bypassed')
        cape = common.EXPECTED_CAPES.get(loadout.get('profile'))
        if cape is not None and skin.get('cape') != cape:
            raise ValueError(f'{name}: actual native cape texture differs from its equipped profile')
        models.add(skin.get('model'))
        if any(not finite_nonnegative(entity.get(key)) for key in NATIVE_FIELDS):
            raise ValueError(f'{name}: actor {index} lacks finite nonnegative native work counters')
        row = dict(index=index, **{key: entity[key] for key in NATIVE_FIELDS})
        row['trackingMatchesOwnedDeck'] = entity.get('trackingMatchesOwnedDeck')
        native.append(row)
        if name != WALL:
            if entity['nativeRendererReturns'] <= 0 or entity['accessoryDispatches'] <= 0:
                failures.append(finding(name, 'VISIBLE_ACTOR_NATIVE_PATH_MISSING',
                                        f'Actor {index} did not run its native player/accessory render path.', actor=index))
            if entity['nativeFullTicks'] <= 0:
                failures.append(finding(name, 'VISIBLE_ACTOR_FULL_TICKS_MISSING',
                                        f'Actor {index} received no full native ticks.', actor=index))
            if walking and (entity['controllerMovementTicks'] <= 0 or entity['cumulativePath'] <= .5):
                failures.append(finding(name, 'VISIBLE_ACTOR_WALKING_MISSING',
                                        f'Actor {index} did not produce the requested native walking path.', actor=index))
        if name.startswith('deck-') and entity.get('trackingMatchesOwnedDeck') is not True:
            failures.append(finding(name, 'ACTOR_DECK_TRACKING_LOST',
                                    f'Actor {index} lost its native owned-deck tracking relation.', actor=index))
    if clones and (indices != set(range(clones)) or len(profiles) != 6 or None in profiles or len(models) != 2 or None in models):
        raise ValueError(f'{name}: unique actors, six loadouts, and both native skin models required')
    if name.startswith('deck-'):
        if stage.get('movingSublevel') is not True or stage.get('localTrackedDeckTicks') != ticks:
            raise ValueError(f'{name}: observer was not natively carried by the moving deck on every measured tick')
        if stage.get('platformDriveTickAtSceneEnd', 0) <= stage.get('platformDriveTickAtSceneStart', 0):
            raise ValueError(f'{name}: native platform did not move')
        drift = stage.get('maximumLocalPlotHorizontalDrift')
        if not finite_nonnegative(drift) or drift > .01:
            raise ValueError(f'{name}: observer drifted in plot space')
    if name == WALL and (stage.get('realWallOcclusion') is not True or stage.get('nativeWallBlocks', 0) < 1):
        raise ValueError('Wall scene requires the actual native opaque wall')
    coverage = predicate_coverage(entities)
    rendered = all(entity['nativeRendererReturns'] > 0 and entity['accessoryDispatches'] > 0 for entity in entities)
    matched = name != WALL and missing == 0 and rendered and not failures
    native_summary = dict(measuredClientTicks=ticks,
                          actorsWithFullTicks=sum(e['nativeFullTicks'] > 0 for e in entities),
                          actorsWithPlayerRenders=sum(e['nativeRendererReturns'] > 0 for e in entities),
                          actorsWithAccessoryDispatches=sum(e['accessoryDispatches'] > 0 for e in entities),
                          actorsWithWalking=sum(e['controllerMovementTicks'] > 0 and e['cumulativePath'] > .5 for e in entities),
                          totalFullTicks=sum(e['nativeFullTicks'] for e in entities),
                          totalPlayerRendererReturns=sum(e['nativeRendererReturns'] for e in entities),
                          totalAccessoryDispatches=sum(e['accessoryDispatches'] for e in entities),
                          perClone=native)
    observations = []
    if name == WALL:
        suppressed = [e['index'] for e in entities if e['nativeFullTicks'] == 0]
        observations.append(finding(name, 'WALL_NATIVE_TICK_AND_RENDER_POLICY_OBSERVED',
            f"{len(suppressed)}/{clones} hidden actors received no full native ticks; "
            f"{native_summary['actorsWithPlayerRenders']}/{clones} submitted player renders. "
            'Suppression alone is an observed policy, not a fixture failure or proof of visible recovery failure.',
            actorsWithNoFullTicks=suppressed, nativeBehavior=native_summary))
    return dict(scene=name, clones=clones, **stats, gear=entities, measuredClientTicks=ticks,
                actualNativeClientTicksPerSecond=ticks * 1e9 / duration,
                cameraLock=dict(actualViewSamples=frames, actualViewLockedEveryMeasuredFrame=True,
                                maximumActualViewYawError=errors[0], maximumActualViewPitchError=errors[1]),
                nativeCoverage=dict(framesAllClonesReturned=returned, framesMissingSomeClone=missing,
                                    allCloneFrameFraction=returned / frames),
                nativeBehavior=native_summary, nowheelPredicateCoverage=coverage,
                matchedRenderWorkload=matched, behaviorFailures=failures, behaviorObservations=observations,
                behaviorCostIncludesUnequalCulling=name == WALL,
                afterMeasurementScreenshot=stage.get('afterMeasurementScreenshot'),
                nativeGeometryEvidence={key: stage.get(key) for key in (
                    'localPosition', 'cameraPosition', 'cameraYaw', 'cameraPitch', 'platformLogicalPosition',
                    'nativeWallBlocks', 'localTrackedDeckTicks', 'maximumLocalPlotHorizontalDrift')},
                gc=common.pick(stage, 'gc', 'gcDeltas', 'gcDelta')), intervals


def check_restoration(report):
    if report.get('cleanupOnlyPreflight') is not False or report.get('cleanup', {}).get('clientStateRestored') is not True:
        raise ValueError('Full capture requires converged cleanup, not cleanup-only preflight')
    evidence = report.get('restorationEvidence')
    if not isinstance(evidence, dict):
        raise ValueError('Authoritative restoration evidence missing')
    for phase in ('immediateServer', 'settledServer'):
        state = evidence.get(phase, {})
        for key in ('matches', 'positionMatches', 'inheritedVelocityMatches', 'supportMatches', 'flagsMatch', 'rotationMatches'):
            if state.get(key) is not True:
                raise ValueError(f'Cleanup {phase}.{key} failed/missing')
        if phase == 'immediateServer' and any(state.get(key) is not True for key in ('movementMatches', 'fallDistanceMatches')):
            raise ValueError('Immediate authoritative movement/fall restoration failed')
    for key in ('clientPositionMatches', 'clientPhysicsMatches', 'clientSupportMatches'):
        if evidence.get(key) is not True:
            raise ValueError(f'Settled client restoration {key} failed')
    return evidence


def check_probes(probes, mode, requested):
    if not requested:
        return []
    if not isinstance(probes, dict):
        raise ValueError('Requested untimed visibility probes missing')
    for key in ('requested', 'finished', 'excludedFromFrameTimings', 'wallRetainedThroughout'):
        if probes.get(key) is not True:
            raise ValueError(f'Untimed visibility probe {key} missing/false')
    if probes.get('actorPositionRepairWrites') != 0:
        raise ValueError('Natural visibility probe used actor position repairs')
    phases = probes.get('phases')
    if not isinstance(phases, list) or [p.get('phaseId') for p in phases] != PROBE_PHASES:
        raise ValueError('Untimed visibility probe phases missing/reordered')
    for phase, expected_ticks in zip(phases, PROBE_TICKS):
        name = phase['phaseId']
        if phase.get('actualViewLocked') is not True:
            raise ValueError(f'{name}: untimed actual camera was not locked')
        for key in ('ticks', 'frameCount'):
            if not integer_count(phase.get(key)) or phase[key] == 0:
                raise ValueError(f'{name}: untimed probe {key} missing')
        if phase['ticks'] != expected_ticks:
            raise ValueError(f'{name}: untimed native phase tick count differs from frozen probe')
        if not integer_count(phase.get('allClonesReturnedFrames')) or phase['allClonesReturnedFrames'] > phase['frameCount']:
            raise ValueError(f'{name}: untimed player coverage counters invalid')
        entities = phase.get('entities')
        if not isinstance(entities, list) or len(entities) != 8 or {e.get('index') for e in entities} != set(range(8)):
            raise ValueError(f'{name}: eight native probe actor rows required')
        for entity in entities:
            for key in ('nativeFullTicks', 'nativeRendererReturns', 'accessoryDispatches'):
                if not integer_count(entity.get(key)):
                    raise ValueError(f'{name}: actual native probe counter {key} missing')
        coverage = predicate_coverage(entities)
        if mode == 'NOWHEEL' and coverage['actorsSampled'] != 8:
            raise ValueError(f'{name}: native Nowheel predicate not sampled on all probe actors')
        samples = phase.get('samples')
        if not isinstance(samples, list) or not samples or {s.get('index') for s in samples} != set(range(8)):
            raise ValueError(f'{name}: actual/expected native geometry samples missing')
        for sample in samples:
            for key in ('expectedWallBlocksSight', 'actualWallBlocksSight', 'expectedCenterInCameraCone',
                        'culled', 'outOfCamera', 'forcedVisible', 'nativeDynamicWhitelisted', 'trackingMatchesOwnedDeck'):
                if type(sample.get(key)) is not bool:
                    raise ValueError(f'{name}: native/expected geometry field {key} missing')
            if not finite_nonnegative(sample.get('nativePlotWorldPositionError')):
                raise ValueError(f'{name}: actual native plot/world position error missing')
            if name in ('exposed-hold', 'camera-reentry') and (sample['expectedWallBlocksSight'] or not sample['expectedCenterInCameraCone']):
                raise ValueError(f'{name}: intended exposed geometry does not have a clear ray and camera cone')
            if name in ('hidden-moving', 'rehidden-hold') and not sample['expectedWallBlocksSight']:
                raise ValueError(f'{name}: intended hidden geometry lacks the persistent opaque wall')
    failures = probes.get('behaviorFailures')
    if not isinstance(failures, list):
        raise ValueError('Probe behavior findings missing')
    return failures


def inspect_run(manifest_path):
    """Runner interface: accepted captures may carry behaviorFailures; never retry them."""
    record = dict(manifestPath=str(manifest_path), accepted=False, behaviorFailures=[], behaviorPass=False,
                  matchedRenderWorkload=False)
    try:
        manifest = load(manifest_path)
        source = common.report_path(manifest)
        record.update(label=manifest['label'], mode=manifest['mode'], passNumber=manifest['passNumber'],
                      attempt=manifest['attempt'], manifestSha256=sha(manifest_path), reportPath=str(source))
        if manifest.get('valid') is not True:
            raise ValueError('Pre-launch audit invalid')
        mode, number = manifest['mode'], manifest['passNumber']
        if mode not in MODE_ARTIFACTS or type(number) is not int or not 1 <= number <= len(ORDER) or ORDER[number - 1] != mode:
            raise ValueError('Frozen ABBA order/state mismatch')
        plan_path = Path(manifest['planPath'])
        if sha(plan_path) != manifest['planSha256']:
            raise ValueError('Frozen plan bytes changed')
        plan = load(plan_path)
        if plan.get('order') != ORDER:
            raise ValueError('Plan order differs from the two-mode ABBA design')
        for key, expected in (('ours', preparation.OURS_SHA), ('nowheel', preparation.NOWHEEL_SHA)):
            if plan['artifacts'][key]['sha256'] != expected:
                raise ValueError('Plan release artifact differs from the approved pinned comparison')
        for key in ('nowheelOfficialArtifactRecord', 'userSnapshot', 'harnessBuildRecord'):
            audit = plan.get(key)
            if not isinstance(audit, dict) or sha(audit['path']) != audit['sha256']:
                raise ValueError(f'Pinned provenance record {key} missing/changed')
        official = load(plan['nowheelOfficialArtifactRecord']['path'])
        if official.get('sha256') != preparation.NOWHEEL_SHA or official.get('officialSha512Verified') is not True or official.get('officialSha1Verified') is not True:
            raise ValueError('Official Nowheel digest verification missing')
        if load(plan['harnessBuildRecord']['path']).get('sha256') != plan['artifacts']['harness']['sha256']:
            raise ValueError('Harness build record does not identify the pinned fixture jar')
        expected_mods = dict(plan['sharedModSha256'])
        for key in ('harness', MODE_ARTIFACTS[mode]):
            item = plan['artifacts'][key]
            if sha(item['path']) != item['sha256']:
                raise ValueError('Frozen artifact bytes changed')
            expected_mods[item['name']] = item['sha256']
        if manifest.get('activeModSha256') != expected_mods:
            raise ValueError('Physical jars differ from pinned mutually exclusive state')
        if manifest.get('compatPhysicallyInstalled') is not (mode == 'OURS') or manifest.get('nowheelPhysicallyInstalled') is not (mode == 'NOWHEEL') or manifest.get('firstPersonPhysicallyInstalled') is not False:
            raise ValueError('Physical installed flags differ from the two-mode/no-FirstPerson design')
        if manifest.get('configSha256') != plan['baselineConfigSha256'] or manifest.get('optionsSha256') != plan['optionsSha256']:
            raise ValueError('Pre-launch config/options bytes differ from frozen baseline')
        if manifest.get('sourceSha256') != plan['sourceSha256']:
            raise ValueError('Source provenance differs from frozen plan')
        if manifest.get('nowheelConfigSha256') != plan['nowheelConfigSha256'] or plan.get('nowheelConfig') != preparation.NOWHEEL_DEFAULTS:
            raise ValueError('Explicit Nowheel default config not pinned')
        if manifest.get('shaderpackSha256') != plan['shaderpackSha256']:
            raise ValueError('Pre-launch shader snapshot audit differs')
        shader_path = Path(manifest_path).with_name(manifest['label'] + '-shader-audit.json')
        shader = load(shader_path)
        if shader.get('valid') is not True or shader.get('jvmExited') is not True or shader.get('baselineSha256') != plan['shaderpackSha256']:
            raise ValueError('Post-exit shader audit missing/invalid')
        identity_path = Path(manifest_path).with_name(manifest['label'] + '-process.json')
        identity = load(identity_path)
        if any(identity.get(key) is not True for key in ('offlineIdentityConfirmed', 'productionDisableFlagAbsent', 'processAuditBeforeTrigger')):
            raise ValueError('Owned offline identity/production flag audit missing')
        if Path(identity['userDir']).resolve() != Path(manifest['runtime']).resolve():
            raise ValueError('Process user.dir differs from the owned disposable runtime')
        report = load(source)
        record['reportSha256'] = sha(source)
        if report.get('status') != 'CAPTURED' or report.get('finished') is not True:
            raise ValueError(f'Native report did not finish CAPTURED: {report.get("status")!r} {report.get("detail", "")}')
        if report.get('label') != manifest['label'] or Path(report['runtime']).resolve() != Path(manifest['runtime']).resolve():
            raise ValueError('Native report label/runtime mismatch')
        if report.get('comparisonMode') != 'nowheel':
            raise ValueError('Native dedicated Nowheel-comparison mode missing')
        if report.get('probeOnlyPreflight') is not False:
            raise ValueError('Full matrix cannot accept a probe-only preflight')
        if report.get('compatInstalled') is not (mode == 'OURS') or report.get('nowheelInstalled') is not (mode == 'NOWHEEL'):
            raise ValueError('Native ModList differs from selected physical comparison jars')
        if report.get('firstPersonInstalled') is not False or report.get('firstPersonEnabled') is not False:
            raise ValueError('Native FirstPerson state must be absent/disabled')
        if report.get('fullDeveloperHarnessInstalled') is not False or report.get('productionMixinsDisabledProperty') is not False:
            raise ValueError('Old developer harness or production-mixin substitute present/unknown')
        versions = report.get('modVersions', {})
        expected_id = 'emfattachments' if mode == 'OURS' else 'nowheel'
        if versions.get(expected_id) != ('1.2.5' if mode == 'OURS' else '2.1.1'):
            raise ValueError(f'Native {expected_id} version differs from pinned release')
        if not versions.get('sable') or not versions.get('entityculling'):
            raise ValueError('Native Sable/Entity Culling version evidence missing')
        if report.get('cameraLocked') is not True:
            raise ValueError('Native camera-lock mode missing')
        if report.get('warmupSeconds') != 10 or report.get('measureSeconds') != 20 or report.get('firstSceneExtraWarmupSeconds') != 20:
            raise ValueError('Frozen timing durations changed')
        settings = report['renderSettings']
        for key, value in (('width', 2560), ('height', 1440), ('fov', 90), ('fullscreen', True),
                           ('vsync', False), ('maxFps', 260), ('renderDistance', 8), ('simulationDistance', 8), ('focused', True)):
            if settings.get(key) != value:
                raise ValueError(f'Controlled native setting {key} differs: {settings.get(key)!r}')
        stages, raw, failures = [], {}, []
        for stage in report['stages']:
            if stage.get('renderSettings') != settings or stage.get('firstPersonEnabled') is not False:
                raise ValueError('Native settings/FirstPerson state changed between scenes')
            value, intervals = normalized_stage(stage)
            scene = value['scene']
            if scene in raw:
                raise ValueError('Duplicate native scene')
            if mode == 'NOWHEEL' and value['clones'] and value['nowheelPredicateCoverage']['actorsSampled'] != value['clones']:
                raise ValueError(f'{scene}: actual Nowheel predicate coverage missing for requested actors')
            stages.append(value)
            raw[scene] = intervals
            failures.extend(value['behaviorFailures'])
        if [stage['scene'] for stage in stages] != SCENES:
            raise ValueError('Missing/reordered native timed scenes')
        telemetry = report.get('nowheelTelemetry')
        if not isinstance(telemetry, dict):
            raise ValueError('Native Nowheel telemetry missing')
        if telemetry.get('errors') != [] or telemetry.get('configMutatedByFixture') is not False:
            raise ValueError('Native telemetry failed or changed config')
        if mode == 'NOWHEEL' and telemetry.get('config') != preparation.NOWHEEL_DEFAULTS:
            raise ValueError('Native Nowheel config differs from pinned defaults')
        if mode == 'NOWHEEL' and (not isinstance(telemetry.get('registeredDynamicPredicates'), list) or type(telemetry.get('sablePredicateRegistered')) is not bool):
            raise ValueError('Native dynamic whitelist registration telemetry missing')
        if mode == 'NOWHEEL' and telemetry.get('sablePredicateRegistered') is False:
            failures.append(finding('registration', 'NOWHEEL_SABLE_PREDICATE_NOT_REGISTERED',
                                    'Native telemetry reports the Sable predicate was not registered after warmup.'))
        native_failures = report.get('behaviorFailures', [])
        if not isinstance(native_failures, list):
            raise ValueError('Native behaviorFailures must be an array')
        probes = report.get('comparisonProbes')
        probe_failures = check_probes(probes, mode, plan.get('visibilityProbe'))
        distinct = {json.dumps(value, sort_keys=True): value for value in native_failures + probe_failures}
        for value in distinct.values():
            failures.append(finding('native-fixture', 'NATIVE_BEHAVIOR_FINDING',
                                    value if isinstance(value, str) else json.dumps(value, sort_keys=True), native=value))
        # Probe observations are kept verbatim and excluded from every timing
        # metric. Their physics/state assertions belong to the native fixture.
        record.update(accepted=True, stages=stages, raw=raw, settings=settings,
                      behaviorFailures=failures, behaviorPass=not failures,
                      behaviorObservations=[value for stage in stages for value in stage['behaviorObservations']],
                      matchedRenderWorkload=all(s['matchedRenderWorkload'] for s in stages if s['scene'] != WALL),
                      compatInstalled=mode == 'OURS', nowheelInstalled=mode == 'NOWHEEL',
                      firstPersonInstalled=False, firstPersonEnabled=False,
                      comparisonArtifact=plan['artifacts'][MODE_ARTIFACTS[mode]],
                      compatArtifact=manifest.get('compatArtifact'), nowheelArtifact=manifest.get('nowheelArtifact'),
                      harnessArtifact=manifest['harnessArtifact'], modVersions=versions,
                      cleanup=common.check_cleanup(report), restorationEvidence=check_restoration(report),
                      nowheelTelemetry=telemetry, comparisonProbes=probes,
                      telemetrySamplingPolicy=report.get('telemetrySamplingPolicy'),
                      shaderAuditSha256=sha(shader_path), processAuditSha256=sha(identity_path),
                      sourceSha256=plan['sourceSha256'], limitations=report.get('limitations', []))
    except Exception as error:
        record.update(accepted=False, rejection=str(error))
    return record


def combine(runs):
    groups = {}
    for mode in MODE_ARTIFACTS:
        selected = [run for run in runs if run['mode'] == mode]
        if not selected:
            continue
        scenes = {}
        for scene in SCENES:
            intervals = [value for run in selected for value in run['raw'][scene]]
            stats = common.frame_stats(intervals)
            individual = [next(s for s in run['stages'] if s['scene'] == scene) for run in selected]
            stats['individualRuns'] = [dict(label=run['label'], passNumber=run['passNumber'],
                **{key: stage[key] for key in ('meanFps', 'meanFrameMs', 'p99FrameMs', 'onePercentLowFps',
                    'slowFrames', 'nativeCoverage', 'measuredClientTicks', 'nativeBehavior',
                    'nowheelPredicateCoverage', 'matchedRenderWorkload', 'behaviorFailures')})
                for run, stage in zip(selected, individual)]
            stats['repeatMeanFpsRange'] = [min(s['meanFps'] for s in individual), max(s['meanFps'] for s in individual)]
            stats['repeatMeanFpsSpreadPercent'] = (stats['repeatMeanFpsRange'][1] - stats['repeatMeanFpsRange'][0]) / stats['meanFps'] * 100
            stats['matchedRenderWorkload'] = all(s['matchedRenderWorkload'] for s in individual)
            stats['behaviorFailures'] = [f for s in individual for f in s['behaviorFailures']]
            scenes[scene] = stats
        groups[mode] = dict(comparisonArtifact=selected[0]['comparisonArtifact'], repeatCount=len(selected),
                            firstPersonInstalled=False, scenes=scenes,
                            nowheelTelemetry=[r['nowheelTelemetry'] for r in selected],
                            comparisonProbes=[r['comparisonProbes'] for r in selected])
    return groups


def comparisons(groups):
    if any(mode not in groups for mode in MODE_ARTIFACTS):
        return []
    rows = []
    for scene in SCENES:
        ours, nowheel = (groups[mode]['scenes'][scene] for mode in ('OURS', 'NOWHEEL'))
        change = (ours['meanFps'] / nowheel['meanFps'] - 1) * 100
        matched = ours['matchedRenderWorkload'] and nowheel['matchedRenderWorkload']
        pairs = [dict(oursLabel=a['label'], nowheelLabel=b['label'], oursPass=a['passNumber'], nowheelPass=b['passNumber'],
                      meanFpsPercentChange=(a['meanFps'] / b['meanFps'] - 1) * 100)
                 for a, b in zip(ours['individualRuns'], nowheel['individualRuns'])]
        if scene == WALL:
            interpretation = ('Native wall ticks, motion, player renders, and accessory dispatches are reported independently for each state. '
                              'Different work is a behavior cost, not pure hook overhead; no expected policy is imposed by mod name.')
        elif not matched:
            interpretation = ('At least one valid capture omitted expected visible actors/accessory submissions. FPS is diagnostic only; '
                              'this scene cannot establish a matched-rendering performance winner.')
        else:
            interpretation = ('Native visible player/accessory paths and every-frame player coverage are matched. '
                              'Separate screenshots still must confirm pixels; compare both repeats before attributing small FPS differences.')
        rows.append(dict(scene=scene, primaryEndpoint=scene in PRIMARY, ours=ours, nowheel=nowheel,
                         matchedRenderWorkload=matched, oursMeanFpsPercentChange=change,
                         oursMeanFrameMsDifference=ours['meanFrameMs'] - nowheel['meanFrameMs'],
                         oursP99FrameMsDifference=ours['p99FrameMs'] - nowheel['p99FrameMs'],
                         oursOnePercentLowFpsPercentChange=(ours['onePercentLowFps'] / nowheel['onePercentLowFps'] - 1) * 100,
                         pairedOrderComparisons=pairs,
                         repeatMeanFpsRangesOverlap=not (ours['repeatMeanFpsRange'][1] < nowheel['repeatMeanFpsRange'][0]
                                                       or nowheel['repeatMeanFpsRange'][1] < ours['repeatMeanFpsRange'][0]),
                         changeWithinLargestRepeatSpread=abs(change) <= max(ours['repeatMeanFpsSpreadPercent'], nowheel['repeatMeanFpsSpreadPercent']),
                         interpretation=interpretation))
    return rows


def markdown(result):
    lines = ['# Our 1.2.5 versus Nowheel 2.1.1', '',
             f"Status: {result['status']}. FirstPerson is physically absent; all selected runs are fresh JVM captures.", '',
             '| Scene | Our FPS | Nowheel FPS | Our FPS change | Our / Nowheel 1% low | Our / Nowheel p99 ms | Native render work |',
             '| --- | ---: | ---: | ---: | ---: | ---: | --- |']
    for row in sorted(result['comparisons'], key=lambda row: (row['scene'] not in PRIMARY, SCENES.index(row['scene']))):
        a, b = row['ours'], row['nowheel']
        work = 'Matched coverage; pixels need review' if row['matchedRenderWorkload'] else 'Wall behavior cost' if row['scene'] == WALL else 'NOT MATCHED; diagnostic FPS'
        lines.append(f"| {row['scene']} | {a['meanFps']:.1f} | {b['meanFps']:.1f} | {row['oursMeanFpsPercentChange']:+.2f}% | {a['onePercentLowFps']:.1f} / {b['onePercentLowFps']:.1f} | {a['p99FrameMs']:.2f} / {b['p99FrameMs']:.2f} | {work} |")
    lines += ['', '| Run / scene | Full-tick actors | Rendered actors | Accessory actors | Walking actors | Nowheel predicate active / sampled |',
              '| --- | ---: | ---: | ---: | ---: | ---: |']
    for run in result['acceptedRuns']:
        for stage in run['stages']:
            if stage['scene'] not in PRIMARY:
                continue
            b, p = stage['nativeBehavior'], stage['nowheelPredicateCoverage']
            lines.append(f"| {run['label']} / {stage['scene']} | {b['actorsWithFullTicks']}/{stage['clones']} | {b['actorsWithPlayerRenders']}/{stage['clones']} | {b['actorsWithAccessoryDispatches']}/{stage['clones']} | {b['actorsWithWalking']}/{stage['clones']} | {p['activeSamples']}/{p['samples']} |")
    lines += ['', 'Zero predicate-active samples mean the tested actor state did not exercise Nowheel’s Sable whitelist. '
              'That finding must not be generalized to plot-contained entities or other untested Sable states.', '',
              'Mean FPS pools raw intervals. 1% low is 1000 / mean milliseconds of the slowest ceil(1% N) intervals; quantiles use nearest rank. '
              'Individual repeats, ranges, frame spikes, native telemetry, and untimed probes are retained in the JSON.', '',
              'These are whole-mod comparisons with equipped client actors, not isolated hook CPU measurements or live multiplayer networking tests. '
              'The scene is not a Create factory benchmark. Two repeats are descriptive. Native counters require separate visual review.', '',
              f"Behavior findings retained: {sum(len(r['behaviorFailures']) for r in result['acceptedRuns'])}. "
              'A complete timing capture does not mean every visibility/animation check passed.', '']
    lines += ['Source context below is independent of the measurements:', '']
    for note in SOURCE_NOTES:
        lines.append(f"- {note['claim']} [Pinned 2.1.1 source]({note['url']})")
    lines += ['', 'Nowheel uses its default four boolean settings: overrideEntityCulling=true; tickCulling=false; '
              'distanceCulling=false; forceRemoving=false. These results do not evaluate a tuned configuration.', '']
    return '\n'.join(lines)


def self_check(path):
    """Synthetic schema/selection safeguards only; no synthetic data becomes measurements."""
    candidates = sorted((ROOT / '.local/without-firstperson/.local/client/emfattachments-tests').glob(
        'sable125-revision-002-pass3-candidate125-r1/equipped-crowd-frames.json'))
    if not candidates:
        raise FileNotFoundError('Existing immutable report used solely as a schema fixture is unavailable')
    source = candidates[0]
    old = load(source)
    wall = copy.deepcopy(next(s for s in old['stages'] if common.scene_id(s) == WALL))
    visible = copy.deepcopy(next(s for s in old['stages'] if common.scene_id(s) == 'deck-walk-8'))
    for stage in (wall, visible):
        for entity in stage['entities']:
            entity.update(nowheelPredicateSamples=20, nowheelPredicateTrueSamples=0)
    checks = []
    for label, ticks, renders, accessories in (('wall_all_suppressed', 0, 0, 0), ('wall_full_ticks_hidden', 400, 0, 0),
                                               ('wall_zero_ticks_rendered', 0, 100, 100), ('wall_full_ticks_rendered', 400, 100, 100)):
        stage = copy.deepcopy(wall)
        for entity in stage['entities']:
            entity.update(nativeFullTicks=ticks, nativeRendererReturns=renders, accessoryDispatches=accessories,
                          controllerMovementTicks=400 if ticks else 0, cumulativePath=1.0 if ticks else 0)
        value, _ = normalized_stage(stage)
        assert value['nativeBehavior']['actorsWithFullTicks'] == (8 if ticks else 0)
        assert value['nativeBehavior']['actorsWithPlayerRenders'] == (8 if renders else 0)
        assert not value['matchedRenderWorkload']
        checks.append(dict(check=label, passed=True, purpose='Retain native outcomes without state-specific wall-policy assumptions'))
    stage = copy.deepcopy(visible)
    stage['framesAllClonesReturned'], stage['framesMissingSomeClone'] = 0, stage['sampleCount']
    for entity in stage['entities']:
        entity.update(nativeFullTicks=0, nativeRendererReturns=0, accessoryDispatches=0,
                      controllerMovementTicks=0, cumulativePath=0)
    value, _ = normalized_stage(stage)
    assert value['behaviorFailures'] and not value['matchedRenderWorkload']
    checks.append(dict(check='missing_visible_players_retained_as_behavior_failure', passed=True))
    for label, mutate in (
            ('camera_failure_rejected', lambda s: s.update(actualViewLockedEveryMeasuredFrame=False)),
            ('duration_mismatch_rejected', lambda s: s.update(durationNanos=s['durationNanos'] + 1)),
            ('loadout_failure_rejected', lambda s: s['entities'][0]['loadout'].update(accessories=[]))):
        stage = copy.deepcopy(visible)
        mutate(stage)
        try:
            normalized_stage(stage)
        except ValueError:
            checks.append(dict(check=label, passed=True))
        else:
            raise AssertionError(label)
    stage = copy.deepcopy(visible)
    a, _ = normalized_stage(stage)
    stage.update(compatInstalled=False, nowheelInstalled=True)
    b, _ = normalized_stage(stage)
    assert a == b
    checks.append(dict(check='mod_flags_do_not_determine_native_behavior', passed=True))
    probes = dict(requested=True, finished=True, excludedFromFrameTimings=True,
                  wallRetainedThroughout=True, actorPositionRepairWrites=0, phases=[], behaviorFailures=[])
    for name, ticks in zip(PROBE_PHASES, PROBE_TICKS):
        hidden = name in ('hidden-moving', 'rehidden-hold')
        probes['phases'].append(dict(phaseId=name, ticks=ticks, frameCount=1, allClonesReturnedFrames=0,
            actualViewLocked=True,
            entities=[dict(index=i, nativeFullTicks=0, nativeRendererReturns=0, accessoryDispatches=0,
                           nowheelPredicateSamples=3, nowheelPredicateTrueSamples=0) for i in range(8)],
            samples=[dict(index=i, expectedWallBlocksSight=hidden, actualWallBlocksSight=True,
                          expectedCenterInCameraCone=True, culled=True, outOfCamera=True,
                          forcedVisible=False, nativeDynamicWhitelisted=False, trackingMatchesOwnedDeck=True,
                          nativePlotWorldPositionError=26.0) for i in range(8)]))
    probes['behaviorFailures'].append(dict(finding='Synthetic stale-position visibility failure'))
    assert check_probes(probes, 'NOWHEEL', True) == probes['behaviorFailures']
    checks.append(dict(check='probe_actual_stale_position_missing_visibility_retained', passed=True))
    malformed = copy.deepcopy(probes)
    malformed['phases'][2]['samples'][0]['expectedWallBlocksSight'] = True
    try:
        check_probes(malformed, 'NOWHEEL', True)
    except ValueError:
        checks.append(dict(check='probe_invalid_expected_geometry_rejected', passed=True))
    else:
        raise AssertionError('Invalid expected geometry accepted')
    result = dict(status='PASS', syntheticSchemaChecksOnly=True, newPerformanceMeasurements=False,
                  immutableSchemaFixture=str(source), immutableSchemaFixtureSha256=sha(source),
                  analyzerSha256=sha(__file__), checks=checks)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(dict(status='PASS', checks=len(checks), output=str(path))))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, default=OUT)
    parser.add_argument('--base', type=Path, default=preparation.BASE)
    parser.add_argument('--plan', type=Path)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--self-check', action='store_true')
    args = parser.parse_args()
    if args.self_check:
        self_check(args.output or args.directory / 'analyzer-self-check.json')
        return
    directory = args.directory.resolve()
    manifests = sorted(directory.glob('nowheel*-manifest.json'))
    if not manifests:
        raise FileNotFoundError('No new Nowheel-comparison manifests')
    exclusions_path = directory / 'exclusions.json'
    exclusions = load(exclusions_path) if exclusions_path.exists() else {}
    inspected = [inspect_run(path) for path in manifests]
    active_plan = args.plan or preparation.plan_path(args.base)
    plan_hash = sha(active_plan)
    selected, excluded = [], []
    for number in range(1, len(ORDER) + 1):
        chosen = False
        for run in sorted((r for r in inspected if r.get('passNumber') == number), key=lambda r: r['attempt']):
            if load(run['manifestPath']).get('planSha256') != plan_hash:
                run['supersededPlanExcluded'] = True
            elif run['label'] in exclusions:
                run['explicitExclusion'] = exclusions[run['label']]
            elif run['accepted'] and not chosen:
                selected.append(run)
                chosen = True
                continue
            excluded.append(run)
    excluded += [r for r in inspected if r.get('passNumber') not in range(1, len(ORDER) + 1)]
    groups = combine(selected)
    complete = len(selected) == len(ORDER) and len(groups) == 2 and all(g['repeatCount'] == 2 for g in groups.values())
    behavior_findings = any(r['behaviorFailures'] for r in selected)
    status = ('COMPLETE_WITH_BEHAVIOR_FINDINGS_REQUIRE_VISUAL_REVIEW' if behavior_findings else 'COMPLETE_TIMINGS_REQUIRE_VISUAL_REVIEW') if complete else 'INCOMPLETE'
    result = dict(status=status, analyzedUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                  analyzerSha256=sha(__file__), sharedStatisticsHelperSha256=sha(common.__file__),
                  activePlan=dict(path=str(active_plan), sha256=plan_hash),
                  design=dict(order=ORDER, primaryEndpoints=PRIMARY, secondaryControls=[s for s in SCENES if s not in PRIMARY],
                              physicalJarSelection=True, firstPersonInstalledInAnyRun=False, historicalTimingReuse=False,
                              selection='Earliest valid non-excluded attempt per planned pass, including genuine behavior findings; never best-FPS selection.',
                              onePercentLowDefinition='1000 / mean milliseconds of slowest ceil(1%N) frames',
                              percentileDefinition='Nearest-rank quantile of pooled positive native frame intervals'),
                  acceptedRuns=[{k: v for k, v in r.items() if k != 'raw'} for r in selected],
                  rejectedOrAdditionalRuns=[{k: v for k, v in r.items() if k != 'raw'} for r in excluded],
                  combinedByState=groups, comparisons=comparisons(groups),
                  pinnedSourceContext=SOURCE_NOTES, nowheelDefaults=preparation.NOWHEEL_DEFAULTS,
                  acceptedFrames=sum(len(v) for r in selected for v in r['raw'].values()),
                  acceptedIntervalSeconds=sum(sum(v) / 1e9 for r in selected for v in r['raw'].values()),
                  limitations=['Equipped client actors omit real multiplayer packet/server/skin-download work.',
                               'Two fresh repeats per state are descriptive, not a statistical guarantee.',
                               'Native wall outcomes may change tick/render work and do not isolate hook CPU overhead.',
                               'Whole-mod comparison includes our other fixes and Nowheel Create optimizations; this is not a factory scene.',
                               'Zero native Nowheel predicate matches cannot establish behavior for plot-contained Sable entities.',
                               'Native counters require separate screenshot review; untimed probes are excluded from frame metrics.',
                               'FirstPerson was excluded; no FirstPerson conclusion follows.'])
    output = args.output or directory / 'comparison.json'
    output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    output.with_suffix('.md').write_text(markdown(result), encoding='utf-8')
    print(json.dumps(dict(status=status, acceptedRuns=len(selected), acceptedFrames=result['acceptedFrames'], output=str(output))))


if __name__ == '__main__':
    main()
