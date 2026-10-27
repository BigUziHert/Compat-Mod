"""Prepare OURS1.2.5-alone versus Nowheel2.1.1-alone in a disposable client.

No user-pack writes, historical-evidence changes, client launches or builds.
Fresh current-user settings and one immutable cold world/shader snapshot are
reset for each of four fresh JVM runs. There is no neither/both/FirstPerson mode.
"""
from pathlib import Path
import argparse
import json
import re
import shutil
import subprocess
import zipfile

import prepare_equipped_benchmark as common
import prepare_sable_culling_benchmark as guarded

ROOT = common.ROOT
BASE = ROOT / '.local/nowheel-comparison'
OUT = ROOT / 'verification/nowheel-comparison'
SMOKE_BASE = ROOT / '.local/nowheel-comparison-smoke'
SMOKE_OUT = OUT / 'probe-smoke'
WORLD_PLAN = ROOT / '.local/sable-culling-benchmark-1.2.5/revisions/revision-002/plan.json'
ORDER = ['OURS', 'NOWHEEL', 'NOWHEEL', 'OURS']
MODE_ARTIFACTS = {'OURS': 'ours', 'NOWHEEL': 'nowheel'}
MODES = {'OURS': dict(compatInstalled=True, nowheelInstalled=False, firstPersonInstalled=False),
         'NOWHEEL': dict(compatInstalled=False, nowheelInstalled=True, firstPersonInstalled=False)}
OURS_SHA = 'dca5afa425392b3c2095e9e53de94c15b4df581d747425aa024f6a73b24e6058'
NOWHEEL_SHA = '82e4671af59e84c8d22992ff1af7e8fc6c2cde431475c4a82198b9678885975d'
NOWHEEL_DEFAULTS = dict(overrideEntityCulling=True, tickCulling=False, distanceCulling=False, forceRemoving=False)
OPTIONAL_PREFIXES = ('emf-attachments-compat-', 'firstperson-', 'emfattachments-perf-', 'fpm_x_compat-', 'nowheel')
load, save, sha, utc = common.load, common.save, common.sha, common.utc
inside, runtime_path, require_stopped, running = common.inside, common.runtime_path, common.require_stopped, common.running


def optional_file(name):
    return name.lower().startswith(OPTIONAL_PREFIXES)


def source_hashes(probe_only=False):
    names = ('prepare_nowheel_comparison.py', 'run_nowheel_comparison.py', 'analyze_nowheel_comparison.py',
             'prepare_equipped_benchmark.py', 'prepare_sable_culling_benchmark.py',
             'analyze_equipped_benchmark.py', 'test_runtime.py', 'build_equipped_perf.py')
    files = [ROOT / 'scripts' / name for name in names] + [ROOT / 'tools/equipped-perf.init.gradle']
    if probe_only:
        files = [path for path in files if path.name != 'analyze_nowheel_comparison.py']
    files += [p for directory in ('src/main', 'src/perfmod') for p in (ROOT / directory).rglob('*') if p.is_file()]
    return {str(p.relative_to(ROOT)).replace('\\', '/'): sha(p) for p in sorted(files)}


def plan_path(base):
    return inside(base) / 'plan.json'


def pass_label(plan, number, mode, attempt):
    return f'{plan.get("labelPrefix", "nowheel")}-pass{number}-{mode.lower()}-r{attempt}'


def evidence_directory(plan):
    path = Path(plan.get('evidenceDirectory', str(OUT))).resolve()
    assert path == OUT.resolve() or path.is_relative_to(OUT.resolve())
    return path


def verify_artifact(path, expected_sha, mod_id, version):
    path = Path(path).resolve()
    assert re.fullmatch('[0-9a-f]{64}', expected_sha) and sha(path) == expected_sha, f'Pinned {mod_id} artifact mismatch'
    with zipfile.ZipFile(path) as archive:
        metadata = archive.read('META-INF/neoforge.mods.toml').decode('utf-8')
        assert re.search(r'(?m)^modId\s*=\s*"' + re.escape(mod_id) + r'"\s*$', metadata)
        assert re.search(r'(?m)^version\s*=\s*"' + re.escape(version) + r'"\s*$', metadata)
    return path


def init(args):
    base = inside(args.base)
    if base.exists():
        raise FileExistsError('Refusing to overwrite a Nowheel comparison baseline')
    probe_only = getattr(args, 'probe_only', False)
    label_prefix = getattr(args, 'label_prefix', None) or ('nowheel-smoke' if probe_only else 'nowheel')
    if not re.fullmatch('[A-Za-z0-9][A-Za-z0-9._-]{0,63}', label_prefix):
        raise ValueError('Label prefix must be a simple1..64-character identifier')
    output = SMOKE_OUT if probe_only else OUT
    execution_path = output / 'execution-plan.json'
    if execution_path.exists():
        execution_path = output / f'execution-plan-{label_prefix}.json'
        if execution_path.exists():
            raise FileExistsError('Refusing to replace an existing execution-plan revision')
    world_plan_path = inside(args.world_plan)
    previous = load(world_plan_path)
    runtime = runtime_path(args.runtime or previous['runtime'])
    require_stopped(runtime)
    source_world = inside(Path(previous['base']) / 'baseline-world')
    assert common.tree_hashes(source_world) == previous['baselineWorldSha256'], 'Immutable cold world changed'
    ours = verify_artifact(args.ours, args.ours_sha, 'emfattachments', '1.2.5')
    nowheel = verify_artifact(args.nowheel, args.nowheel_sha, 'nowheel', '2.1.1')
    harness = Path(args.harness).resolve()
    assert re.fullmatch('[0-9a-f]{64}', args.harness_sha) and sha(harness) == args.harness_sha
    with zipfile.ZipFile(harness) as archive:
        assert not any(name.startswith(('dev/caleb/emfattachments/client/', 'dev/caleb/emfattachments/mixin/',
                    'dev/caleb/emfattachments/test/', 'dev/tr7zw/firstperson/')) or name.endswith('.jar')
                    for name in archive.namelist()), 'Minimal standalone harness required; no production/API classes or nested jars'
    official_path = Path(args.nowheel_record).resolve()
    official = load(official_path)
    assert official['sha256'] == args.nowheel_sha and official['officialSha512Verified'] is True and official['officialSha1Verified'] is True
    build_record = None
    if args.harness_build_record:
        record_path = Path(args.harness_build_record).resolve()
        record = load(record_path)
        assert record['sha256'] == args.harness_sha
        build_record = dict(path=str(record_path), sha256=sha(record_path))
    user_path = Path(args.user_snapshot).resolve()
    user = load(user_path)
    instance = Path(user['instance']).resolve()
    assert not instance.is_relative_to((ROOT / '.local').resolve())
    actual_mods = {p.name: sha(p) for p in (instance / 'mods').iterdir() if p.is_file()}
    user_config = {name.removeprefix('config/'): digest for name, digest in user['configHashes'].items()}
    assert actual_mods == user['mods'], 'Current user mod files differ from fresh snapshot'
    assert common.tree_hashes(instance / 'config') == user_config, 'Current user config files differ from snapshot'
    assert sha(instance / 'options.txt') == user['optionsSha256'], 'Current user options differ from snapshot'
    assert {p.name: sha(p) for p in (instance / 'resourcepacks').iterdir() if p.is_file()} == user['resourcepacks']
    assert common.tree_hashes(instance / 'shaderpacks') == user['shaderpacks']
    assert actual_mods.get(ours.name) == args.ours_sha, 'Actual user pack must retain the installed1.2.5 artifact'
    assert not any(optional_file(name) and name.lower().startswith(('nowheel', 'firstperson-')) and name.endswith('.jar')
                   for name in actual_mods), 'Actual user baseline must have neither Nowheel nor FirstPerson installed'
    shared = {name: digest for name, digest in actual_mods.items() if name.endswith('.jar') and not optional_file(name)}
    estimate = (common.tree_bytes(source_world) + common.tree_bytes(instance / 'config')) * 5
    estimate += common.tree_bytes(instance / 'shaderpacks') * 2 + sum(p.stat().st_size for p in (ours, nowheel, harness)) * 3
    estimate += sum((instance / 'mods' / name).stat().st_size for name in shared
                    if not (runtime / 'mods' / name).is_file() or sha(runtime / 'mods' / name) != shared[name])
    space = common.require_space(runtime, estimate)
    base.mkdir(parents=True)
    shutil.copytree(source_world, base / 'baseline-world')
    shutil.copytree(instance / 'config', base / 'baseline-config')
    shutil.copytree(instance / 'shaderpacks', base / 'baseline-shaderpacks')
    original = base / 'source-user-settings'
    original.mkdir()
    shutil.copy2(instance / 'options.txt', original / 'options.txt')
    lines = (instance / 'options.txt').read_text(encoding='utf-8-sig').splitlines()
    lines = [line for line in lines if line.split(':', 1)[0] not in common.SETTINGS]
    (base / 'baseline-options.txt').write_text('\n'.join(lines + [f'{key}:{value}' for key, value in common.SETTINGS.items()]) + '\n', encoding='utf-8')
    watut = base / 'baseline-config/watut-common.toml'
    shutil.copy2(watut, original / watut.name)
    text, count = re.subn(r'(?m)^(\s*ticksToMarkPlayerIdle\s*=\s*)\d+\s*$', r'\g<1>120000', watut.read_text(encoding='utf-8-sig'))
    assert count == 1
    watut.write_text(text, encoding='utf-8')
    dynamic = base / 'baseline-config/dynamic_fps.json'
    shutil.copy2(dynamic, original / dynamic.name)
    data = load(dynamic)
    data['enabled'] = False
    save(dynamic, data)
    nowheel_config = base / 'baseline-config/nowheel.json'
    if nowheel_config.exists():
        shutil.copy2(nowheel_config, original / nowheel_config.name)
    save(nowheel_config, NOWHEEL_DEFAULTS)
    sync = base / 'initial-runtime-sync'
    sync.mkdir()
    top_user_shaders = {p.name: sha(p) for p in (instance / 'shaderpacks').iterdir() if p.is_file()}
    synchronization = dict(
        sharedMods=guarded.sync_files(instance / 'mods', runtime / 'mods', sync / 'mods', shared,
                                     lambda name: name.endswith('.jar') and not optional_file(name)),
        resourcepacks=guarded.sync_files(instance / 'resourcepacks', runtime / 'resourcepacks', sync / 'resourcepacks', user['resourcepacks'], lambda name: True),
        shaderpacks=guarded.sync_files(instance / 'shaderpacks', runtime / 'shaderpacks', sync / 'shaderpacks', top_user_shaders, lambda name: True))
    artifacts = {}
    for key, source in (('ours', ours), ('nowheel', nowheel), ('harness', harness)):
        destination = base / 'artifacts' / key / source.name
        destination.parent.mkdir(parents=True)
        shutil.copy2(source, destination)
        artifacts[key] = common.artifact(destination)
    config_hashes = common.tree_hashes(base / 'baseline-config')
    plan = dict(status='INITIALIZED_NOT_EXECUTED', createdUtc=utc(), base=str(base), runtime=str(runtime),
                order=['OURS'] if probe_only else ORDER, modes={'OURS': MODES['OURS']} if probe_only else MODES,
                modeArtifacts=MODE_ARTIFACTS, labelPrefix=label_prefix,
                probeOnly=probe_only, evidenceDirectory=str(output), artifacts=artifacts,
                sharedModSha256=shared, baselineConfigSha256=config_hashes,
                baselineWorldSha256=previous['baselineWorldSha256'], optionsSha256=sha(base / 'baseline-options.txt'),
                shaderpackSha256=common.tree_hashes(base / 'baseline-shaderpacks'),
                baselineShaderpackPath=str(base / 'baseline-shaderpacks'), resourcepackSha256=user['resourcepacks'],
                settings=common.SETTINGS, warmupSeconds=10, firstSceneWarmupSeconds=30, measureSeconds=20,
                expectedSceneCount=0 if probe_only else 8, comparisonMode='nowheel', visibilityProbe=not args.no_visibility_probe,
                nowheelConfig=NOWHEEL_DEFAULTS, nowheelConfigSha256=sha(nowheel_config),
                nowheelOfficialArtifactRecord=dict(path=str(official_path), sha256=sha(official_path)),
                harnessBuildRecord=build_record,
                userSnapshot=dict(path=str(user_path), sha256=sha(user_path), recordedUtc=user['recordedUtc'],
                    installedOurJarSha256=args.ours_sha, sharedModsEqual=True,
                    configDifferences=guarded.differences(config_hashes, user_config),
                    actualUserPackModified=False),
                coldWorldSource=dict(planPath=str(world_plan_path), planSha256=sha(world_plan_path),
                                     priorTimingDataReused=False),
                sourceSha256=source_hashes(probe_only), storagePreflight=space,
                disposableRuntimeSynchronization=synchronization,
                constraints=['Only OURS alone versus Nowheel alone; no neither/both/FirstPerson state.',
                    'Two fresh JVM repeats per state in ABBA order; exact cold world/config/options/shader reset.',
                    'The actual user pack remains installed1.2.5 and is never written.',
                    'Explicit default Nowheel configuration is present identically in both disposable states.',
                    'Focus/camera/settings/provenance/restoration failures reject an attempt.',
                    'A valid full capture with observed visibility/animation behavior failures is retained and flagged; never retry away a legitimate behavioral result.',
                    'No profiler, raw-array analysis, or GPU snapshot queries during timing.',
                    'Cold native client actors omit real multiplayer networking/server player work.'])
    save(base / 'plan.json', plan)
    save(execution_path, plan)
    print(json.dumps(dict(initialized=str(base / 'plan.json'), userPackModified=False, clientsLaunched=False,
                          executionPlan=str(execution_path), synchronization=synchronization)))


def audit_runtime(plan, mode):
    assert mode in MODES
    runtime = runtime_path(plan['runtime'])
    selected = MODE_ARTIFACTS[mode]
    expected = dict(plan['sharedModSha256'])
    for key in ('harness', selected):
        item = plan['artifacts'][key]
        expected[item['name']] = item['sha256']
    jars = {p.name: sha(p) for p in sorted((runtime / 'mods').glob('*.jar'))}
    configs = common.tree_hashes(runtime / 'config')
    violations = []
    for valid, reason in ((jars == expected, 'Active jars are not exactly one selected comparison mod plus shared/harness'),
        (configs == plan['baselineConfigSha256'], 'Config bytes changed'),
        (sha(runtime / 'options.txt') == plan['optionsSha256'], 'Options bytes changed'),
        (common.tree_hashes(runtime / 'saves/AttachmentTest') == plan['baselineWorldSha256'], 'Cold world differs'),
        (common.tree_hashes(runtime / 'shaderpacks') == plan['shaderpackSha256'], 'Shader bytes differ'),
        ({p.name: sha(p) for p in (runtime / 'resourcepacks').iterdir() if p.is_file()} == plan['resourcepackSha256'], 'Resource pack bytes differ'),
        (load(runtime / 'config/nowheel.json') == NOWHEEL_DEFAULTS, 'Explicit Nowheel defaults changed')):
        if not valid:
            violations.append(reason)
    options = common.properties(runtime / 'options.txt')
    return dict(valid=not violations, violations=violations, runtime=str(runtime), activeModSha256=jars,
                configSha256=configs, optionsSha256=sha(runtime / 'options.txt'),
                observedSettings={key: options.get(key) for key in plan['settings']},
                compatPhysicallyInstalled=mode == 'OURS', nowheelPhysicallyInstalled=mode == 'NOWHEEL',
                firstPersonPhysicallyInstalled=False,
                compatArtifact=plan['artifacts']['ours'] if mode == 'OURS' else None,
                nowheelArtifact=plan['artifacts']['nowheel'] if mode == 'NOWHEEL' else None,
                comparisonArtifact=plan['artifacts'][selected], harnessArtifact=plan['artifacts']['harness'],
                shaderpackSha256=plan['shaderpackSha256'], nowheelConfigSha256=sha(runtime / 'config/nowheel.json'))


def prepare_pass(args):
    base = inside(args.base)
    active_plan = plan_path(base)
    plan = load(active_plan)
    output = evidence_directory(plan)
    runtime = runtime_path(plan['runtime'])
    require_stopped(runtime)
    assert 1 <= args.pass_number <= len(plan['order']) and 1 <= args.attempt <= 99
    mode = plan['order'][args.pass_number - 1]
    label = pass_label(plan, args.pass_number, mode, args.attempt)
    archive = inside(base / 'pass-history' / label)
    if archive.exists() or (output / f'{label}-manifest.json').exists() or (runtime / 'emfattachments-tests' / label).exists():
        raise FileExistsError('Pass label exists; select an unconsumed attempt')
    assert source_hashes(plan.get('probeOnly', False)) == plan['sourceSha256'], 'Sources/scripts changed after freeze'
    for item in plan['artifacts'].values():
        assert sha(item['path']) == item['sha256'], 'Frozen comparison artifact changed'
    if list(runtime.glob('emfattachments-*.txt')):
        raise RuntimeError('Another unconsumed harness trigger exists')
    shader_source = inside(plan['baselineShaderpackPath'], base)
    space = common.require_space(runtime, common.tree_bytes(base / 'baseline-world') + common.tree_bytes(base / 'baseline-config') + common.tree_bytes(shader_source) + 8 * 1024 * 1024)
    archive.mkdir(parents=True)
    for name, source in (('world', runtime / 'saves/AttachmentTest'), ('config', runtime / 'config')):
        if source.exists():
            inside(source).rename(inside(archive / name))
    if (runtime / 'options.txt').exists():
        shutil.copy2(runtime / 'options.txt', archive / 'options.txt')
    shutil.copytree(base / 'baseline-world', runtime / 'saves/AttachmentTest')
    shutil.copytree(base / 'baseline-config', runtime / 'config')
    shutil.copy2(base / 'baseline-options.txt', runtime / 'options.txt')
    shader_reset = guarded.restore_shader_snapshot(plan, runtime, archive)
    for source in (runtime / 'mods').iterdir():
        if source.is_file() and optional_file(source.name):
            source.rename(inside(archive / source.name))
    for key in ('harness', MODE_ARTIFACTS[mode]):
        item = plan['artifacts'][key]
        shutil.copy2(item['path'], runtime / 'mods' / item['name'])
    manifest = audit_runtime(plan, mode)
    manifest.update(label=label, passNumber=args.pass_number, attempt=args.attempt, mode=mode,
                    preparedUtc=utc(), launched=False, triggerArmed=False, spacePreflight=space,
                    planPath=str(active_plan), planSha256=sha(active_plan), sourceSha256=plan['sourceSha256'],
                    shaderReset=shader_reset, baselineShaderpackPath=str(shader_source))
    assert manifest['valid'], manifest['violations']
    save(output / f'{label}-manifest.json', manifest)
    print(json.dumps(dict(prepared=label, mode=mode, firstPersonInstalled=False, triggerArmed=False, launched=False)))


def process_audit(args):
    plan = load(plan_path(args.base))
    output = evidence_directory(plan)
    runtime = runtime_path(plan['runtime'])
    pid = int(load(runtime / 'process.json')['pid'])
    assert running(pid), 'Owned JVM is not running'
    result = subprocess.run([str(args.jcmd), str(pid), 'VM.system_properties'], capture_output=True, text=True, check=True)
    properties = common.parse_java_properties(result.stdout)
    command = properties.get('sun.java.command', '')
    assert Path(properties.get('user.dir', '')).resolve() == runtime
    assert '--username AttachmentTest' in command and '--userType legacy' in command and '--accessToken 0' in command
    assert properties.get('emfattachments.disable') != 'true'
    record = dict(pid=pid, observedUtc=utc(), offlineIdentityConfirmed=True, userDir=str(runtime),
                  productionDisableFlagAbsent=True, launchArgsSha256=sha(runtime / 'launch.args'), sensitiveLaunchArgumentsStored=False)
    save(output / f'{args.label}-process.json', record)
    return record


def arm_trigger(base, label):
    plan = load(plan_path(base))
    output = evidence_directory(plan)
    runtime = runtime_path(plan['runtime'])
    manifest = load(output / f'{label}-manifest.json')
    identity = load(output / f'{label}-process.json')
    assert manifest['valid'] and identity['offlineIdentityConfirmed'] is True
    assert int(load(runtime / 'process.json')['pid']) == identity['pid'] and running(identity['pid'])
    assert not list(runtime.glob('emfattachments-*.txt'))
    identity.update(triggerArmedUtc=utc(), processAuditBeforeTrigger=True)
    save(output / f'{label}-process.json', identity)
    probe = 'true' if plan['visibilityProbe'] else 'false'
    only = '\nprobeOnly=true' if plan.get('probeOnly') else ''
    (runtime / common.TRIGGER).write_text(f'{label}\nwarmupSeconds=10\nmeasureSeconds=20\ncomparison=nowheel\nvisibilityProbe={probe}{only}\n', encoding='utf-8')


def post_run_shader_audit(plan, label):
    output = evidence_directory(plan)
    runtime = runtime_path(plan['runtime'])
    require_stopped(runtime)
    source = inside(plan['baselineShaderpackPath'], inside(plan['base']))
    target = inside(runtime / 'shaderpacks')
    observed, expected = common.tree_hashes(target), plan['shaderpackSha256']
    rows = []
    for relative in sorted(set(observed) | set(expected)):
        if observed.get(relative) == expected.get(relative):
            continue
        timestamp_only = False
        if relative in observed and relative in expected and '/' not in relative and relative.endswith('.txt'):
            before, after = inside(source / relative, source).read_bytes().splitlines(), inside(target / relative, target).read_bytes().splitlines()
            timestamp_only = bool(before) and bool(after) and before[0].startswith(b'#') and after[0].startswith(b'#') and before[1:] == after[1:]
        saved_path = None
        if relative in observed:
            saved_path = output / f'{label}-shader-after' / relative
            saved_path.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(inside(target / relative, target), saved_path)
        rows.append(dict(path=relative, beforeSha256=expected.get(relative), afterSha256=observed.get(relative),
                         timestampCommentOnly=timestamp_only, savedPath=str(saved_path) if saved_path else None))
    result = dict(label=label, observedUtc=utc(), jvmExited=True, valid=all(row['timestampCommentOnly'] for row in rows),
                  baselineSha256=expected, observedSha256=observed, differingFiles=rows)
    save(output / f'{label}-shader-audit.json', result)
    return result


def inspect_probe(manifest_path):
    """Validate an untimed OURS-only smoke without calling the FPS analyzer."""
    manifest = load(manifest_path)
    report_path = Path(manifest['runtime']) / 'emfattachments-tests' / manifest['label'] / 'equipped-crowd-frames.json'
    result = dict(accepted=False, label=manifest['label'], reportPath=str(report_path), timingMeasurement=False)
    try:
        plan = load(manifest['planPath'])
        output = evidence_directory(plan)
        assert sha(manifest['planPath']) == manifest['planSha256']
        assert plan.get('probeOnly') is True and plan['order'] == ['OURS'] and manifest['mode'] == 'OURS'
        assert manifest.get('valid') is True and manifest['compatPhysicallyInstalled'] is True
        assert manifest['nowheelPhysicallyInstalled'] is False and manifest['firstPersonPhysicallyInstalled'] is False
        expected = dict(plan['sharedModSha256'])
        for key in ('ours', 'harness'):
            item = plan['artifacts'][key]
            assert sha(item['path']) == item['sha256']
            expected[item['name']] = item['sha256']
        assert manifest['activeModSha256'] == expected
        process = load(output / f'{manifest["label"]}-process.json')
        assert process['offlineIdentityConfirmed'] is True and process['processAuditBeforeTrigger'] is True
        assert load(output / f'{manifest["label"]}-shader-audit.json')['valid'] is True
        report = load(report_path)
        result['reportSha256'] = sha(report_path)
        assert report.get('status') == 'VISIBILITY_PREFLIGHT_CAPTURED' and report.get('finished') is True
        assert report['label'] == manifest['label'] and Path(report['runtime']).resolve() == Path(manifest['runtime']).resolve()
        assert report.get('comparisonMode') == 'nowheel' and report.get('compatInstalled') is True
        assert report.get('nowheelInstalled') is False and report.get('firstPersonInstalled') is False
        assert report.get('stages') == [], 'Probe smoke must contain zero timed stages'
        probes = report['comparisonProbes']
        assert probes.get('finished') is True, 'Untimed native visibility probes unfinished'
        cleanup = report['cleanup']
        assert len(cleanup) >= 11 and all(value is True for value in cleanup.values())
        failures = report.get('behaviorFailures', [])
        result.update(accepted=True, cleanup=cleanup, comparisonProbes=probes,
                      behaviorFailures=failures, behaviorPass=not bool(failures), matchedRenderWorkload=None)
    except Exception as error:
        result['rejection'] = str(error) or type(error).__name__
    return result


def recover_probe(args):
    """Record an already completed native smoke after orchestration recovery."""
    plan = load(plan_path(args.base))
    assert plan.get('probeOnly') is True
    output = evidence_directory(plan)
    runtime = runtime_path(plan['runtime'])
    require_stopped(runtime)
    assert re.fullmatch('[A-Za-z0-9._-]+', args.label)
    destination = output / f'{args.label}-manual-recovery.json'
    if destination.exists():
        raise FileExistsError('Never replace an existing recovery record')
    progress = output / 'matrix-progress.json'
    recovery_dir = output / 'orchestration-recovery'
    recovery_dir.mkdir(exist_ok=True)
    if progress.exists():
        saved = recovery_dir / 'matrix-progress-before-manual-recovery.json'
        if not saved.exists():
            shutil.copy2(progress, saved)
    post_run_shader_audit(plan, args.label)
    for path, suffix in ((runtime / 'logs/latest.log', 'game.log'), (runtime / 'console-error.log', 'stderr.log')):
        if path.exists():
            saved = output / f'{args.label}-{suffix}'
            if saved.exists():
                assert sha(saved) == sha(path), 'Preserved log differs; do not overwrite evidence'
            else:
                shutil.copy2(path, saved)
    verdict = inspect_probe(output / f'{args.label}-manifest.json')
    record = dict(status='MANUALLY_RECOVERED_UNTIMED_PROBE' if verdict['accepted'] else 'RECOVERY_VALIDATION_FAILED',
                  recoveredUtc=utc(), label=args.label, timingMeasurement=False, newClientLaunched=False,
                  jvmStoppedBeforeValidation=True, originalPlanSha256=sha(plan_path(args.base)),
                  orchestrationFailure='The original262144-byte tail-only monitor omitted top-level session status/finished before the approximately1.2MB comparisonProbes subtree. Root gracefully stopped the already-finished native smoke after monitoring failed.',
                  originalOrchestrationProgressPreserved=True,
                  originalOrchestrationProgressSha256=sha(progress) if progress.exists() else None,
                  recoveryHelperSha256=sha(Path(__file__)), verdict=verdict)
    save(destination, record)
    print(json.dumps(dict(status=record['status'], accepted=verdict['accepted'], timingMeasurement=False,
                          output=str(destination), rejection=verdict.get('rejection'))))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', type=Path, default=BASE)
    parser.add_argument('--probe-only', action='store_true', help='Use an independent OURS-only untimed smoke base/evidence')
    commands = parser.add_subparsers(dest='command', required=True)
    command = commands.add_parser('init')
    command.add_argument('--world-plan', type=Path, default=WORLD_PLAN)
    command.add_argument('--runtime', type=Path)
    command.add_argument('--ours', type=Path, default=ROOT / 'build/libs/emf-attachments-compat-1.21.1-1.2.5.jar')
    command.add_argument('--ours-sha', default=OURS_SHA)
    command.add_argument('--nowheel', type=Path, default=ROOT / '.local/nowheel-comparison-preparation/artifacts/nowheel/nowheel-2.1.1+1.21.1neoforge.jar')
    command.add_argument('--nowheel-sha', default=NOWHEEL_SHA)
    command.add_argument('--harness', type=Path, required=True)
    command.add_argument('--harness-sha', required=True)
    command.add_argument('--harness-build-record', type=Path, required=True)
    command.add_argument('--user-snapshot', type=Path, default=OUT / 'before-change.json')
    command.add_argument('--nowheel-record', type=Path, default=OUT / 'nowheel-artifact.json')
    command.add_argument('--label-prefix', help='Unique run prefix; defaults to nowheel (or nowheel-smoke for an untimed smoke)')
    command.add_argument('--no-visibility-probe', action='store_true', help='Disable the separate untimed probe only when explicitly requested')
    command = commands.add_parser('prepare-pass')
    command.add_argument('pass_number', type=int)
    command.add_argument('--attempt', type=int, default=1)
    command = commands.add_parser('audit')
    command.add_argument('--mode', choices=MODES, required=True)
    command = commands.add_parser('process-audit')
    command.add_argument('--label', required=True)
    command.add_argument('--jcmd', type=Path, default=Path('C:/Program Files/Java/jdk-21/bin/jcmd.exe'))
    command = commands.add_parser('inspect-probe-smoke', help='Validate a finished, stopped smoke after manual orchestration recovery')
    command.add_argument('--label', required=True)
    args = parser.parse_args()
    if args.probe_only and args.base == BASE:
        args.base = SMOKE_BASE
    if args.command == 'init':
        init(args)
    elif args.command == 'prepare-pass':
        prepare_pass(args)
    elif args.command == 'audit':
        print(json.dumps(audit_runtime(load(plan_path(args.base)), args.mode)))
    elif args.command == 'inspect-probe-smoke':
        recover_probe(args)
    else:
        assert re.fullmatch('[A-Za-z0-9._-]+', args.label)
        print(json.dumps(process_audit(args)))


if __name__ == '__main__':
    main()
