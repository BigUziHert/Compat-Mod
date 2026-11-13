"""Matched native-parallel versus optimized cape lookup FPS, in an offline copy.

Both conditions load identical jars; only the focused cape-lookup JVM property
changes. Existing perspective runner/analyzer sources are imported unchanged.
Foreground focus is supplied by the operator, never synthesized by the runner.
"""
from pathlib import Path
from types import SimpleNamespace
import argparse
import json
import re
import shutil
import subprocess
import time

import run_perspective_benchmark as perspective
import analyze_perspective_benchmark as analysis
import prepare_equipped_benchmark as common
import prepare_sable_culling_benchmark as sable
import test_runtime

ROOT = common.ROOT
BASE = ROOT / '.local/accessory-fps-benchmark-1.2.14-r3'
OUT = ROOT / 'verification/accessory-fps-1.2.14/fps-r3'
RUNTIME = ROOT / '.local/without-firstperson/.local/client'
ORDER = [('NATIVE_PARALLEL', 'SHADERS'), ('OPTIMIZED', 'SHADERS'),
         ('OPTIMIZED', 'SHADERS'), ('NATIVE_PARALLEL', 'SHADERS')]
SCENE_FILTER = '(world|deck)-(first|third)'
CONTROL_PROPERTY = 'emfattachments.aetherParallelCapeLookup'
LOADOUT_PROPERTY = 'emfattachments.perfLocalLoadout'
JCMD = Path('C:/Program Files/Java/jdk-21/bin/jcmd.exe')


def emit(**values):
    print(json.dumps(values), flush=True)


def configure():
    perspective.BASE, perspective.OUT, perspective.RUNTIME = BASE, OUT, RUNTIME
    perspective.VERSION, perspective.ORDER = '1.2.14', ORDER
    sable.OUT = OUT


def source_hashes():
    return {str(path.relative_to(ROOT)).replace('\\', '/'): common.sha(path) for path in
            (Path(__file__).resolve(), Path(perspective.__file__).resolve(),
             Path(analysis.__file__).resolve(), Path(common.__file__).resolve(),
             Path(sable.__file__).resolve(), Path(test_runtime.__file__).resolve())}


def init(args):
    args.jvm_arg = args.jvm_arg or ['-Xms256m', '-Xmx16160m', '-XX:+UseZGC']
    args.primary_only = True
    args.include_ground_crowd = False
    args.scene_filter = args.scene_filter or SCENE_FILTER
    perspective.initialize(args)
    plan = common.load(BASE / 'plan.json')
    java = args.java.resolve()
    assert java.is_file() and java.name.lower() in ('java.exe', 'javaw.exe')
    version = subprocess.run([str(java.with_name('java.exe')), '-version'], capture_output=True,
                             text=True, check=True).stderr.strip()
    assert '25.0.3' in version, 'Review Java version instead of silently changing the intended runtime'
    assert not any(CONTROL_PROPERTY in flag or LOADOUT_PROPERTY in flag or
                   flag.startswith('-Demfattachments.disable=') for flag in args.jvm_arg)
    backpack = next((flag.split('=', 1)[1] for flag in args.jvm_arg
                     if flag.startswith('-Demfattachments.perfBackpack=')), 'sophisticatedbackpacks:netherite_backpack')
    accessories = ['aeronautics:aviators_goggles',
                   'artifacts:lucky_scarf', backpack, 'artifacts:onion_ring', 'artifacts:golden_hook',
                   'artifacts:helium_flamingo', 'artifacts:bunny_hoppers', 'aether:diamond_gloves',
                   'artifacts:whoopee_cushion']
    label_prefix = 'accessoryfps1214-r3-' + __import__('hashlib').sha256(str(BASE).encode()).hexdigest()[:6]
    plan.update(labelPrefix=label_prefix, experiment='cape-parallel-opt-out-only',
                localLoadout='reported-accessories', expectedAccessoryCount=9, expectedEquippedItemCount=10,
                expectedAccessoryItems=accessories,
                expectedEquipment=dict(head='minecraft:netherite_helmet', chest='minecraft:netherite_chestplate',
                                       legs='minecraft:netherite_leggings', feet='minecraft:netherite_boots',
                                       mainhand='minecraft:air', offhand='minecraft:air'),
                expectedArmorAppearance=dict(trimPattern='minecraft:silence', trimMaterial='minecraft:gold', foil=False),
                expectedBackpackAppearance=dict(backpackMainColor='ff1e5aa8', backpackAccentColor='ffffc940'),
                appearanceAssumptions='Representative blue/gold backpack dye and gold Silence armor trim; actual user colors/pattern/material were unspecified. Fresh backpack has no contents/upgrades/display items; no enchantments or held items.',
                javaExecutable=str(java), javaExecutableSha256=common.sha(java), javaVersion=version,
                extraJvmArguments=args.jvm_arg, sourceSha256=source_hashes(),
                expectedJvm=dict(javaVersion='25.0.3', heapMaximumBytes=16944988160,
                                 collectors=['ZGC Minor Cycles', 'ZGC Minor Pauses',
                                             'ZGC Major Cycles', 'ZGC Major Pauses']),
                limitations=['Copied offline flat-world/deck fixture, not the exact user multiplayer scene.',
                             'Backpack contents/upgrades and equipment are recorded from fixture metadata.',
                             'Two fresh processes per condition; descriptive repeatability, not a statistical guarantee.',
                             'Native maxFps 260 denotes unlimited in this setup, not a measured ceiling.'])
    plan['controls'] += ['Same 1.2.14 production jar in both states; native-parallel property is the only condition change',
                        'Java executable/version and additional JVM flags are frozen in this plan',
                        'Nine native visible accessory selections; functional night-vision goggles share their hat slot with cosmetic aviator goggles, for ten equipped stacks']
    common.save(BASE / 'plan.json', plan)
    common.save(OUT / 'execution-plan.json', plan)
    emit(initialized=str(BASE), javaVersion=version, order=ORDER, scenes=plan['scenes'])


def prepare(number, attempt, smoke=False):
    common.require_stopped(RUNTIME)
    plan = perspective.freeze_harness()
    assert plan['sourceSha256'] == source_hashes(), 'Runner/dependency sources changed after plan freeze'
    assert common.sha(plan['javaExecutable']) == plan['javaExecutableSha256']
    mode, graphics = plan['order'][number - 1]
    label = f'{plan["labelPrefix"]}-{"smoke-" if smoke else ""}pass{number}-{mode.lower()}-r{attempt}'
    archive = common.inside(BASE / 'pass-history' / label)
    assert not archive.exists() and not (OUT / f'{label}-manifest.json').exists(), 'Use a fresh attempt label'
    assert not (RUNTIME / 'emfattachments-tests' / label).exists(), 'Native runtime output already exists; use a fresh attempt label before launch'
    assert not list(RUNTIME.glob('emfattachments-*.txt')), 'Inspect unconsumed runtime triggers'
    common.require_space(RUNTIME, 75 * 1024 ** 2, reserve_bytes=512 * 1024 ** 2)
    archive.mkdir(parents=True)
    for folder, source in (('world', RUNTIME / 'saves/AttachmentTest'), ('config', RUNTIME / 'config')):
        common.inside(source).rename(common.inside(archive / folder))
    shutil.copy2(RUNTIME / 'options.txt', archive / 'options.txt')
    shutil.copytree(BASE / 'baseline-world', RUNTIME / 'saves/AttachmentTest')
    shutil.copytree(BASE / 'baseline-config', RUNTIME / 'config')
    shutil.copy2(BASE / 'baseline-options.txt', RUNTIME / 'options.txt')
    sable.restore_shader_snapshot(plan, RUNTIME, archive)
    for path in (RUNTIME / 'mods').iterdir():
        if path.is_file() and path.name.startswith(perspective.OPTIONAL):
            path.rename(common.inside(archive / path.name))
    expected = dict(plan['sharedModSha256'])
    for key in ('harness', 'production'):
        artifact = plan[key]
        assert common.sha(artifact['path']) == artifact['sha256']
        shutil.copy2(artifact['path'], RUNTIME / 'mods' / artifact['name'])
        expected[artifact['name']] = artifact['sha256']
    assert {p.name: common.sha(p) for p in (RUNTIME / 'mods').glob('*.jar')} == expected
    assert common.tree_hashes(RUNTIME / 'saves/AttachmentTest') == plan['baselineWorldSha256']
    assert common.tree_hashes(RUNTIME / 'config') == plan['baselineConfigSha256']
    assert common.tree_hashes(RUNTIME / 'resourcepacks') == plan['resourcepackSha256']
    assert common.tree_hashes(RUNTIME / 'shaderpacks') == plan['shaderpackSha256']
    extra = [*plan['extraJvmArguments'], f'-D{LOADOUT_PROPERTY}={plan["localLoadout"]}']
    if mode == 'NATIVE_PARALLEL':
        extra.append(f'-D{CONTROL_PROPERTY}=true')
    manifest = dict(label=label, mode=mode, graphics=graphics, passNumber=number, attempt=attempt,
                    smoke=smoke, runtime=str(RUNTIME), valid=True, preparedUtc=common.utc(),
                    reportPath=str(RUNTIME / 'emfattachments-tests' / label / 'equipped-crowd-frames.json'),
                    planPath=str(BASE / 'plan.json'), planSha256=common.sha(BASE / 'plan.json'),
                    activeModSha256=expected, configSha256=common.tree_hashes(RUNTIME / 'config'),
                    optionsSha256=common.sha(RUNTIME / 'options.txt'), harnessArtifact=plan['harness'],
                    compatArtifact=plan['production'], productionPhysicallyInstalled=True,
                    firstPersonPhysicallyInstalled=False, localLoadout=plan['localLoadout'],
                    javaExecutable=plan['javaExecutable'], javaExecutableSha256=plan['javaExecutableSha256'],
                    jvmArguments=extra, capeParallelLookupControl=mode == 'NATIVE_PARALLEL',
                    warmupSeconds=5 if smoke else plan['warmupSeconds'],
                    measureSeconds=5 if smoke else plan['measureSeconds'],
                    scenes=plan['scenes'], sceneFilter=plan['sceneFilter'], includeGroundCrowd=False)
    common.save(OUT / f'{label}-manifest.json', manifest)
    return manifest


def audit_process(manifest):
    pid = int(common.load(RUNTIME / 'process.json')['pid'])
    result = subprocess.run([str(JCMD), str(pid), 'VM.system_properties'],
                            capture_output=True, text=True, check=True)
    props = common.parse_java_properties(result.stdout)
    # Only bounded public identity/experiment values are retained. The full
    # command line and environment are never printed or written to evidence.
    command = props.get('sun.java.command', '')
    assert Path(props.get('user.dir', '')).resolve() == RUNTIME
    assert '--username AttachmentTest' in command and '--userType legacy' in command and '--accessToken 0' in command
    assert props.get('emfattachments.disable') != 'true'
    assert (props.get(CONTROL_PROPERTY) == 'true') == manifest['capeParallelLookupControl']
    assert props.get(LOADOUT_PROPERTY) == manifest['localLoadout']
    plan = common.load(BASE / 'plan.json')
    assert Path(props['java.home']).resolve() == Path(plan['javaExecutable']).parent.parent.resolve()
    assert props['java.version'] == '25.0.3'
    record = dict(pid=pid, observedUtc=common.utc(), offlineIdentityConfirmed=True,
                  userDir=str(RUNTIME), productionDisableFlagAbsent=True,
                  capeParallelLookupControl=manifest['capeParallelLookupControl'], localLoadout=props[LOADOUT_PROPERTY],
                  javaVersion=props['java.version'], javaHome=props['java.home'],
                  launchArgsSha256=common.sha(RUNTIME / 'launch.args'), sensitiveLaunchArgumentsStored=False)
    common.save(OUT / f'{manifest["label"]}-process.json', record)
    return record


def run(number, attempt, smoke=False):
    manifest = prepare(number, attempt, smoke)
    label = manifest['label']
    execution = dict(label=label, gpuBefore=perspective.gpu(), startedUtc=common.utc())
    test_runtime.RUNTIME = RUNTIME
    test_runtime.launch(False, False, extra_jvm=manifest['jvmArguments'], java_executable=manifest['javaExecutable'])
    pid = int(common.load(RUNTIME / 'process.json')['pid'])
    execution['pid'] = pid
    emit(event='LAUNCHED_NEEDS_FOREGROUND', label=label, pid=pid)
    time.sleep(5)
    audit_process(manifest)
    trigger = (f'{label}\nwarmupSeconds={manifest["warmupSeconds"]}\nmeasureSeconds={manifest["measureSeconds"]}\n'
               f'perspectiveComparison=true\nperspectiveSceneFilter={manifest["sceneFilter"]}\n')
    (RUNTIME / common.TRIGGER).write_text(trigger, encoding='utf-8')
    execution['triggerArmedUtc'] = common.utc()
    report = Path(manifest['reportPath'])
    start, previous = time.monotonic(), None
    while time.monotonic() - start < 1200:
        if not common.running(pid):
            raise RuntimeError('Owned client exited before report completion; preserve crash evidence')
        progress = perspective.tail(report)
        if progress != previous:
            previous = progress
            emit(event='NATIVE_PROGRESS', label=label, elapsedSeconds=round(time.monotonic() - start), **progress)
        if progress.get('finished'):
            break
        time.sleep(5)
    else:
        raise TimeoutError('Benchmark timeout; inspect the owned JVM')
    captured = common.load(report)
    execution.update(nativeStatus=captured.get('status'), gpuAfter=perspective.gpu(), reportSha256=common.sha(report))
    (RUNTIME / 'emfattachments-perf-stop.txt').write_text('stop', encoding='utf-8')
    limit = time.monotonic() + 90
    while common.running(pid) and time.monotonic() < limit:
        time.sleep(1)
    assert not common.running(pid), 'Owned client did not exit gracefully'
    execution['shaderAudit'] = sable.post_run_shader_audit(common.load(BASE / 'plan.json'), label)
    config_after = common.tree_hashes(RUNTIME / 'config')
    execution['configAfterSha256'] = config_after
    execution['configChanges'] = []
    for relative in sorted(set(manifest['configSha256']) | set(config_after)):
        if manifest['configSha256'].get(relative) == config_after.get(relative):
            continue
        saved = None
        if relative in config_after:
            source = common.inside(RUNTIME / 'config' / relative, RUNTIME / 'config')
            saved = OUT / f'{label}-config-after' / relative
            saved.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, saved)
        execution['configChanges'].append(dict(path=relative, beforeSha256=manifest['configSha256'].get(relative),
                                                afterSha256=config_after.get(relative), savedPath=str(saved) if saved else None))
    target = OUT / label
    assert not target.exists()
    shutil.copytree(report.parent, target)
    execution['reportArchive'] = str(target / report.name)
    for source, suffix in ((RUNTIME / 'logs/latest.log', 'game.log'), (RUNTIME / 'console-error.log', 'stderr.log')):
        if source.exists():
            shutil.copy2(source, OUT / f'{label}-{suffix}')
    execution['finishedUtc'] = common.utc()
    common.save(OUT / f'{label}-execution.json', execution)
    checked = inspect(OUT / f'{label}-manifest.json', allow_smoke=smoke)
    emit(event='PASS_FINISHED', label=label, accepted=checked['accepted'], rejection=checked.get('rejection'))
    assert checked['accepted'], checked.get('rejection')


def inspect(path, allow_smoke=False):
    path = Path(path)
    manifest = common.load(path)
    result = {key: manifest.get(key) for key in ('label', 'mode', 'graphics', 'passNumber', 'attempt', 'smoke')}
    result.update(accepted=False, manifestPath=str(path), manifestSha256=common.sha(path))
    require = analysis.require
    try:
        plan = common.load(OUT / 'execution-plan.json')
        require(manifest['planSha256'] == common.sha(OUT / 'execution-plan.json'), 'Plan changed after preparation')
        require(manifest['valid'] is True and (allow_smoke or not manifest['smoke']), 'Invalid/smoke-only run')
        require([manifest['mode'], manifest['graphics']] == plan['order'][manifest['passNumber'] - 1], 'Pass order mismatch')
        expected = dict(plan['sharedModSha256'])
        for key in ('harness', 'production'):
            artifact = plan[key]
            require(common.sha(artifact['path']) == artifact['sha256'], 'Frozen artifact changed')
            expected[artifact['name']] = artifact['sha256']
        require(manifest['activeModSha256'] == expected, 'Mod inventories differ')
        require(manifest['harnessArtifact'] == plan['harness'] and manifest['compatArtifact'] == plan['production'], 'Artifacts differ')
        require(manifest['configSha256'] == plan['baselineConfigSha256'] and manifest['optionsSha256'] == plan['optionsSha256'], 'Settings drift')
        require(manifest['scenes'] == plan['scenes'] and manifest['sceneFilter'] == plan['sceneFilter'], 'Scenes differ')
        control = manifest['mode'] == 'NATIVE_PARALLEL'
        require(manifest['capeParallelLookupControl'] is control, 'Wrong experiment mode')
        expected_jvm = [*plan['extraJvmArguments'], f'-D{LOADOUT_PROPERTY}={plan["localLoadout"]}']
        if control:
            expected_jvm.append(f'-D{CONTROL_PROPERTY}=true')
        require(manifest['jvmArguments'] == expected_jvm and manifest['javaExecutable'] == plan['javaExecutable']
                and manifest['javaExecutableSha256'] == plan['javaExecutableSha256'], 'Frozen JVM selection differs')
        execution = common.load(OUT / (manifest['label'] + '-execution.json'))
        identity = common.load(OUT / (manifest['label'] + '-process.json'))
        source = Path(execution['reportArchive'])
        require(common.sha(source) == execution['reportSha256'], 'Archived report changed')
        report = common.load(source)
        require(report['status'] == 'CAPTURED' and report['finished'] is True, 'Native report failed: ' + str(report.get('detail')))
        require(execution['nativeStatus'] == 'CAPTURED' and execution.get('finishedUtc'), 'Execution unfinished')
        require(identity['offlineIdentityConfirmed'] is True and identity['productionDisableFlagAbsent'] is True
                and identity['capeParallelLookupControl'] is control and identity['javaVersion'] == '25.0.3', 'Process identity failed')
        require(identity['observedUtc'] <= execution['triggerArmedUtc'], 'Identity audit was late')
        require(Path(identity['userDir']).resolve() == Path(manifest['runtime']).resolve(), 'Runtime identity differs')
        require(report['label'] == manifest['label'] and Path(report['runtime']).resolve() == Path(manifest['runtime']).resolve(), 'Report identity differs')
        require(report['capeParallelLookupControl'] is control and report['perfLocalLoadout'] == plan['localLoadout'], 'Native mode/loadout differs')
        require(report['capeLookupHandlerPresent'] is (not control)
                and len(report['capeLookupHandlers']) == (0 if control else 2), 'Native cape handler presence differs')
        jvm = report['jvmDetails']
        for key in ('javaVersion', 'heapMaximumBytes'):
            require(jvm[key] == plan['expectedJvm'][key], 'Native JVM differs: ' + key)
        require(set(jvm['collectors']) == set(plan['expectedJvm']['collectors']), 'Native collectors differ')
        require(report['compatInstalled'] is True and report['firstPersonInstalled'] is False and report['firstPersonEnabled'] is False
                and report['fullDeveloperHarnessInstalled'] is False and report['productionMixinsDisabledProperty'] is False, 'Unexpected production/harness state')
        require(report['perspectiveComparison'] is True and report['plannedScenes'] == plan['scenes']
                and report['perspectiveSceneFilter'] == plan['sceneFilter'], 'Native scenes differ')
        require(not report.get('behaviorFailures') and not report['cleanupOnlyPreflight'], 'Native behavior/preflight failed')
        for key in ('warmupSeconds', 'measureSeconds'):
            require(report[key] == manifest[key] == (5 if manifest['smoke'] else plan[key]), key + ' differs')
        require(report['firstSceneExtraWarmupSeconds'] == plan['firstSceneExtraWarmupSeconds'], 'Extra warmup differs')
        settings = report['renderSettings']
        for key, value in (('width', 2560), ('height', 1440), ('fov', 90), ('fullscreen', True), ('vsync', False),
                           ('maxFps', 260), ('renderDistance', 8), ('simulationDistance', 8), ('focused', True), ('shadersEnabled', True)):
            require(settings.get(key) == value, 'Native setting differs: ' + key)
        analysis.no_false_flags(report['cleanup'], 'Cleanup')
        restoration = report['restorationEvidence']
        for phase in ('immediateServer', 'settledServer'):
            for key in ('matches', 'positionMatches', 'inheritedVelocityMatches', 'supportMatches', 'flagsMatch', 'rotationMatches'):
                require(restoration[phase].get(key) is True, f'Cleanup failed: {phase}.{key}')
        for key in ('movementMatches', 'fallDistanceMatches'):
            require(restoration['immediateServer'].get(key) is True, 'Immediate restoration failed: ' + key)
        for key in ('clientPositionMatches', 'clientPhysicsMatches', 'clientSupportMatches'):
            require(restoration.get(key) is True, 'Client restoration failed: ' + key)
        audit = execution['shaderAudit']
        require(audit['valid'] is True and audit['jvmExited'] is True and audit['baselineSha256'] == plan['shaderpackSha256'], 'Shader audit failed')
        require(isinstance(execution['configAfterSha256'], dict), 'Post-run config audit missing')
        for change in execution['configChanges']:
            if change['savedPath']:
                require(common.sha(change['savedPath']) == change['afterSha256'], 'Archived post-run config changed')
        local = report['clientLocalLoadout']
        require(local['accessoryCount'] == plan['expectedAccessoryCount'] and len(local['accessories']) == plan['expectedAccessoryCount'], 'Incomplete reported loadout')
        require(local['equippedItemCount'] == plan['expectedEquippedItemCount'], 'Functional/cosmetic equipped count differs')
        require(sorted(item['item'] for item in local['accessories']) == sorted(plan['expectedAccessoryItems']), 'Reported accessory identities differ')
        for item in local['accessories']:
            if item['item'] == 'aeronautics:aviators_goggles':
                require(item['functionalItem'] == 'artifacts:night_vision_goggles'
                        and item['selectedCosmetic'] == 'aeronautics:aviators_goggles'
                        and item['slot'] == 'hat' and item['index'] == 0, 'Native goggles cosmetic replacement differs')
            else:
                require(item['functionalItem'] == item['item'] and item['selectedCosmetic'] == 'minecraft:air',
                        'Unexpected accessory cosmetic/functional replacement')
        require(len(local['equipment']) == 6
                and {item['slot']: item['item'] for item in local['equipment']} == plan['expectedEquipment'], 'Armor/held equipment differs')
        for item in local['equipment']:
            if item['slot'] in ('head', 'chest', 'legs', 'feet'):
                require(all(item['appearance'].get(key) == value for key, value in plan['expectedArmorAppearance'].items()), 'Native armor trim/foil differs')
        backpack = next(item for item in local['accessories'] if item['item'].startswith('sophisticatedbackpacks:'))
        require(all(backpack['appearance'].get(key) == value for key, value in plan['expectedBackpackAppearance'].items()), 'Native backpack dye differs')
        require(all(item['visible'] is True and item['renderer'] for item in local['accessories']), 'Hidden/unrendered loadout item')
        require(analysis.normalized_workload(local) == analysis.normalized_workload(report['preparation']['serverLocalLoadout']), 'Local client/server loadouts differ')
        require(report['initialLocalLoadoutWitness']['verified'] is True, 'Initial native loadout witness failed')
        require(report['capeInventorySizes'] == [1, 1], 'Cape lookup is not the intended ordinary single-slot workload')
        stages, raw = [], {}
        require([s['sceneId'] for s in report['stages']] == plan['scenes'], 'Missing/reordered stages')
        first_third = next(name for name in plan['scenes'] if '-third' in name)
        for stage in report['stages']:
            require(stage['localLoadoutBeforeMeasurement']['verified'] is True
                    and stage['localLoadoutAfterMeasurement']['verified'] is True
                    and stage['reportedAccessoryCoverage'] is True, 'Native measured loadout/dispatch coverage failed')
            row, frames = analysis.inspect_stage(stage, settings, manifest['measureSeconds'], first_third)
            stages.append(row)
            raw[row['scene']] = frames
        result.update(accepted=True, stages=stages, raw=raw, settings=settings, localLoadout=local,
                      resourcePacks=report['resourcePacks'], reportPath=str(source), reportSha256=common.sha(source),
                      configAfterSha256=execution['configAfterSha256'], configChanges=execution['configChanges'],
                      processAuditSha256=common.sha(OUT / (manifest['label'] + '-process.json')),
                      executionSha256=common.sha(OUT / (manifest['label'] + '-execution.json')))
    except (OSError, ValueError, KeyError, TypeError, IndexError) as error:
        result['rejection'] = str(error)
    return result


def analyze():
    plan = common.load(OUT / 'execution-plan.json')
    paths = sorted(OUT.glob(plan['labelPrefix'] + '-*-manifest.json'))
    inspected = [inspect(path) for path in paths]
    accepted, excluded = [], []
    for number in range(1, len(plan['order']) + 1):
        candidates = sorted((r for r in inspected if r['passNumber'] == number), key=lambda r: r['attempt'])
        chosen = False
        for row in candidates:
            if row['accepted'] and not chosen:
                accepted.append(row)
                chosen = True
            else:
                excluded.append(row)
    if accepted:
        for row in accepted:
            assert row['settings'] == accepted[0]['settings']
            assert row['resourcePacks'] == accepted[0]['resourcePacks']
            assert analysis.normalized_workload(row['localLoadout']) == analysis.normalized_workload(accepted[0]['localLoadout'])
    grouped = {}
    for mode in ('NATIVE_PARALLEL', 'OPTIMIZED'):
        chosen = [r for r in accepted if r['mode'] == mode]
        if not chosen:
            continue
        grouped[mode] = {}
        for scene in plan['scenes']:
            stats = analysis.frame_stats([frame for run in chosen for frame in run['raw'][scene]])
            repeats = [dict(label=r['label'], **next(s for s in r['stages'] if s['scene'] == scene)) for r in chosen]
            stats.update(repeatCount=len(chosen), repeats=repeats, repeatRanges=analysis.repeat_summary(repeats))
            grouped[mode][scene] = stats
    comparisons = []
    if len(grouped) == 2:
        for scene in plan['scenes']:
            before, after = grouped['NATIVE_PARALLEL'][scene], grouped['OPTIMIZED'][scene]
            pairs = [dict(nativeLabel=b['label'], optimizedLabel=a['label'],
                          meanFpsPercentChange=(a['meanFps'] / b['meanFps'] - 1) * 100)
                     for a, b in zip(after['repeats'], before['repeats'])]
            comparisons.append(dict(scene=scene, native=before, optimized=after,
                                    meanFpsPercentChange=(after['meanFps'] / before['meanFps'] - 1) * 100,
                                    p99FrameMsDifference=after['p99FrameMs'] - before['p99FrameMs'], adjacentPairs=pairs))
    contrasts = [dict(mode=mode, scene=prefix, firstPersonFps=stages[prefix + '-first']['meanFps'],
                      thirdPersonFps=stages[prefix + '-third']['meanFps'],
                      changePercent=(stages[prefix + '-third']['meanFps'] / stages[prefix + '-first']['meanFps'] - 1) * 100)
                 for mode, stages in grouped.items() for prefix in ('world', 'deck')
                 if prefix + '-first' in stages and prefix + '-third' in stages]
    result = dict(status='COMPLETE_TIMINGS_REQUIRE_VISUAL_REVIEW' if len(accepted) == len(plan['order']) else 'INCOMPLETE',
                  experiment=plan['experiment'], planSha256=common.sha(OUT / 'execution-plan.json'),
                  production=plan['production'], javaVersion=plan['javaVersion'], extraJvmArguments=plan['extraJvmArguments'],
                  acceptedRuns=[{k: v for k, v in row.items() if k != 'raw'} for row in accepted],
                  excludedRuns=[{k: v for k, v in row.items() if k != 'raw'} for row in excluded],
                  groups=grouped, comparisons=comparisons, perspectiveContrasts=contrasts,
                  acceptedFrames=sum(len(frames) for row in accepted for frames in row['raw'].values()),
                  acceptedIntervalSeconds=sum(sum(frames) / 1e9 for row in accepted for frames in row['raw'].values()),
                  limitations=plan['limitations'])
    common.save(OUT / 'fps-comparison.json', result)
    lines = ['# Accessory FPS: native parallel versus 1.2.14 cape optimization', '',
             f'Status: **{result["status"]}**. {len(accepted)} accepted fresh JVMs; {result["acceptedFrames"]:,} frames across {result["acceptedIntervalSeconds"]:.2f} measured seconds.', '',
             'Both conditions use the same production, benchmark and mod jars. Only the focused cape parallel-lookup opt-out differs. Shader settings, world snapshot, reported loadout, Java and JVM flags are matched.', '',
             '| Scene | Native FPS | Optimized FPS | Change | Native / optimized 1% low | Native / optimized p99 ms |',
             '| --- | ---: | ---: | ---: | ---: | ---: |']
    for row in comparisons:
        b, a = row['native'], row['optimized']
        lines.append(f'| {row["scene"]} | {b["meanFps"]:.2f} | {a["meanFps"]:.2f} | {row["meanFpsPercentChange"]:+.2f}% | '
                     f'{b["onePercentLowFps"]:.2f} / {a["onePercentLowFps"]:.2f} | {b["p99FrameMs"]:.3f} / {a["p99FrameMs"]:.3f} |')
    lines += ['', 'Repeat ranges and adjacent run comparisons:', '',
              '| Scene | Native FPS range | Optimized FPS range | Pair 1 | Pair 2 |',
              '| --- | ---: | ---: | ---: | ---: |']
    for row in comparisons:
        b, a = row['native']['repeatRanges']['meanFps'], row['optimized']['repeatRanges']['meanFps']
        pairs = [f'{p["meanFpsPercentChange"]:+.2f}%' for p in row['adjacentPairs']] + ['n/a', 'n/a']
        lines.append(f'| {row["scene"]} | {b["minimum"]:.2f}–{b["maximum"]:.2f} | {a["minimum"]:.2f}–{a["maximum"]:.2f} | {pairs[0]} | {pairs[1]} |')
    lines += ['', 'FPS is total accepted frame count divided by total native interval time. The 1% low is 1000 divided by the mean milliseconds of the slowest ceil(1% of frames); quantiles use nearest rank.', '',
              'Two processes per condition are descriptive evidence. Screenshot review is separate from numeric workload checks. This copied offline fixture does not recreate the exact multiplayer scene, backpack contents or downloaded skin. It cannot promise the same FPS in ordinary play. No profiler runs contribute timing results.', '']
    for row in excluded:
        lines.append(f'- Excluded {row["label"]}: {row.get("rejection", "Additional valid attempt; earliest valid repeat retained")}')
    (OUT / 'fps-comparison.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')
    emit(status=result['status'], acceptedRuns=len(accepted), acceptedFrames=result['acceptedFrames'], output=str(OUT / 'fps-comparison.md'))


def main():
    global BASE, OUT, RUNTIME
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('init', 'run', 'analyze', 'audit-user'))
    parser.add_argument('--base', type=Path, default=BASE)
    parser.add_argument('--out', type=Path, default=OUT)
    parser.add_argument('--runtime', type=Path)
    parser.add_argument('--production-jar', type=Path, default=ROOT / 'build/libs/emf-attachments-compat-1.21.1-1.2.14.jar')
    parser.add_argument('--java', type=Path, default=test_runtime.INSTALL / 'java/java_25/bin/javaw.exe')
    parser.add_argument('--jvm-arg', action='append', default=[])
    parser.add_argument('--scene-filter')
    parser.add_argument('--warmup', type=int, choices=range(5, 61), default=20)
    parser.add_argument('--measure', type=int, choices=range(5, 61), default=60)
    parser.add_argument('--start', type=int, default=1)
    parser.add_argument('--end', type=int, default=1)
    parser.add_argument('--attempt', type=int, default=1)
    parser.add_argument('--smoke', action='store_true')
    args = parser.parse_args()
    BASE = common.inside(args.base)
    OUT = common.inside(args.out, ROOT / 'verification')
    RUNTIME = common.runtime_path(args.runtime or RUNTIME)
    if args.command != 'init':
        plan = common.load(BASE / 'plan.json')
        RUNTIME = common.runtime_path(args.runtime or plan['runtime'])
        assert RUNTIME == Path(plan['runtime']).resolve()
    assert not BASE.is_relative_to(RUNTIME) and not RUNTIME.is_relative_to(BASE)
    configure()
    if args.command == 'init':
        init(args)
    elif args.command == 'run':
        assert 1 <= args.start <= args.end <= len(plan['order'])
        for number in range(args.start, args.end + 1):
            run(number, args.attempt, args.smoke)
    elif args.command == 'analyze':
        analyze()
    else:
        after = perspective.user_snapshot()
        before = common.load(OUT / 'user-before.json')
        common.save(OUT / 'user-after.json', after)
        emit(userInstanceUnchanged=before == after)
        assert before == after, 'User instance changed; inspect source before claiming an untouched pack'


if __name__ == '__main__':
    main()
