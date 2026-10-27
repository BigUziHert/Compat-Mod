"""Prepare an isolated no-FirstPerson 1.2.5 / 1.2.4 / removed comparison.

Initialization freezes a fresh current-user config/options baseline, retaining the
old immutable test world and standalone harness. It synchronizes changed shared
mod/assets into the stopped disposable runtime, archiving every replaced file.
The prior performance-equipped evidence and the user's pack are never modified.
"""
from pathlib import Path
import argparse
import re
import shutil
import subprocess
import zipfile

import prepare_equipped_benchmark as common

ROOT = common.ROOT
BASE = ROOT / '.local/sable-culling-benchmark-1.2.5'
OUT = ROOT / 'verification/culling-1.2.5/performance'
HISTORICAL_BASE = ROOT / '.local/equipped-benchmark-v2'
ORDER = ['REMOVED', 'OLD124', 'CANDIDATE125', 'CANDIDATE125', 'OLD124', 'REMOVED']
MODES = {'REMOVED': None, 'OLD124': 'old124', 'CANDIDATE125': 'candidate125'}
PRIMARY_SCENES = ['deck-walk-8', 'deck-walk-24', 'deck-occluded-walk-8']
OPTIONAL_PREFIXES = ('emf-attachments-compat-', 'firstperson-', 'emfattachments-perf-', 'fpm_x_compat-')
load, save, sha, utc = common.load, common.save, common.sha, common.utc
inside, runtime_path, require_stopped, running = common.inside, common.runtime_path, common.require_stopped, common.running


def source_hashes():
    files = [ROOT / 'scripts' / name for name in (
        'prepare_sable_culling_benchmark.py', 'run_sable_culling_matrix.py',
        'analyze_sable_culling_benchmark.py', 'prepare_equipped_benchmark.py',
        'analyze_equipped_benchmark.py', 'test_runtime.py', 'build_equipped_perf.py')]
    files.append(ROOT / 'tools/equipped-perf.init.gradle')
    files += [p for directory in ('src/main', 'src/perfmod')
              for p in (ROOT / directory).rglob('*') if p.is_file()]
    return {str(p.relative_to(ROOT)).replace('\\', '/'): sha(p) for p in sorted(files)}


def plan_path(base):
    base = inside(base)
    pointer = base / 'active-plan.json'
    if pointer.exists():
        active = load(pointer)
        path = inside(active['planPath'], base)
        assert sha(path) == active['sha256'], 'Active-plan pointer hash mismatch'
        return path
    return base / 'plan.json'


def load_plan(base):
    return load(plan_path(base))


def pass_label(plan, number, mode, attempt):
    return f'{plan.get("labelPrefix", "sable125")}-pass{number}-{mode.lower()}-r{attempt}'


def differences(a, b):
    return [dict(path=key, benchmarkSha256=a.get(key), currentUserSha256=b.get(key))
            for key in sorted(set(a) | set(b)) if a.get(key) != b.get(key)]


def sync_files(source, target, backup, expected, selector):
    """Archive changed disposable files and copy exact current source bytes."""
    target, backup = inside(target), inside(backup)
    observed = {p.name: sha(p) for p in target.iterdir() if p.is_file() and selector(p.name)}
    changed = []
    for name in sorted(set(expected) | set(observed)):
        if Path(name).name != name or name in ('.', '..'):
            raise ValueError('Only literal file names may be synchronized')
        if observed.get(name) == expected.get(name):
            continue
        destination = inside(target / name, target)
        if destination.exists():
            archived = inside(backup / name)
            archived.parent.mkdir(parents=True, exist_ok=True)
            destination.rename(archived)
        if name in expected:
            origin = Path(source) / name
            assert sha(origin) == expected[name], 'Source asset changed while synchronizing'
            shutil.copy2(origin, destination)
        changed.append(dict(name=name, priorSha256=observed.get(name), currentSha256=expected.get(name)))
    assert {p.name: sha(p) for p in target.iterdir() if p.is_file() and selector(p.name)} == expected
    return changed


def init(args):
    historical = inside(args.historical_base)
    previous_path = historical / 'plan.json'
    previous = load(previous_path)
    runtime = runtime_path(args.runtime or previous['runtime'])
    require_stopped(runtime)
    base = inside(args.base)
    if base.exists():
        raise FileExistsError(f'Refusing to overwrite a benchmark baseline: {base}')
    candidate = Path(args.candidate).resolve()
    if not re.fullmatch('[0-9a-f]{64}', args.candidate_sha):
        raise ValueError('Explicit lowercase candidate SHA256 required')
    assert sha(candidate) == args.candidate_sha, 'Candidate differs from explicitly pinned hash'
    with zipfile.ZipFile(candidate) as archive:
        metadata = archive.read('META-INF/neoforge.mods.toml').decode('utf-8')
        assert re.search(r'(?m)^version\s*=\s*"1\.2\.5"\s*$', metadata), 'Expected actual 1.2.5 jar metadata'
    old = previous['artifacts']['main']
    harness = previous['artifacts']['harness']
    assert old['sha256'] == common.MAIN_SHA and sha(old['path']) == common.MAIN_SHA
    assert sha(harness['path']) == harness['sha256'], 'Frozen historical harness changed'
    with zipfile.ZipFile(harness['path']) as archive:
        assert not any(n.startswith(('dev/caleb/emfattachments/client/', 'dev/caleb/emfattachments/mixin/',
                                     'dev/caleb/emfattachments/test/')) or n.endswith('.jar')
                       for n in archive.namelist()), 'Only the standalone minimal harness is permitted'
    world_hashes = common.tree_hashes(historical / 'baseline-world')
    configs = common.tree_hashes(historical / 'baseline-config')
    assert world_hashes == previous['baselineWorldSha256']
    assert configs == previous['baselineConfigSha256']
    assert sha(historical / 'baseline-options.txt') == previous['optionsSha256']
    # Read and verify the latest user snapshot BEFORE any disposable runtime
    # synchronization. No files under the user instance are ever written.
    user_path = Path(args.user_snapshot).resolve()
    user = load(user_path)
    user_instance = Path(user['instance']).resolve()
    assert not user_instance.is_relative_to((ROOT / '.local').resolve()), 'User snapshot must identify the real source instance'
    user_configs = {key.removeprefix('config/'): value for key, value in user['configHashes'].items()}
    assert common.tree_hashes(user_instance / 'config') == user_configs, 'Current user configs differ from approved before-change snapshot'
    assert sha(user_instance / 'options.txt') == user['optionsSha256'], 'User options changed after snapshot'
    user_shared = {name: digest for name, digest in user['mods'].items()
                   if name.endswith('.jar') and not name.startswith(OPTIONAL_PREFIXES)}
    actual_user_mods = {p.name: sha(p) for p in (user_instance / 'mods').iterdir() if p.is_file()}
    assert actual_user_mods == user['mods'], 'User mod files changed after snapshot'
    user_packs = user.get('resourcepacks', {})
    assert {p.name: sha(p) for p in (user_instance / 'resourcepacks').iterdir() if p.is_file()} == user_packs
    user_shaders = {p.name: sha(p) for p in (user_instance / 'shaderpacks').iterdir() if p.is_file()}
    source_world, source_config = historical / 'baseline-world', user_instance / 'config'
    copy_bytes = (common.tree_bytes(source_world) + common.tree_bytes(source_config)) * 7
    copy_bytes += sum(Path(p).stat().st_size for p in (old['path'], harness['path'], candidate)) * 3
    copy_bytes += sum((user_instance / 'mods' / name).stat().st_size for name in user_shared
                      if not (runtime / 'mods' / name).is_file() or sha(runtime / 'mods' / name) != user_shared[name])
    copy_bytes += sum((user_instance / 'shaderpacks' / name).stat().st_size for name in user_shaders)
    storage = common.require_space(runtime, copy_bytes)
    base.mkdir(parents=True)
    for source, name in ((source_world, 'baseline-world'), (source_config, 'baseline-config')):
        shutil.copytree(source, base / name)
    shutil.copytree(user_instance / 'shaderpacks', base / 'baseline-shaderpacks')
    # The baseline is current user configuration with ONLY documented benchmark
    # controls changed; original user settings remain in this independent record.
    original = base / 'source-user-settings'
    original.mkdir()
    shutil.copy2(user_instance / 'options.txt', original / 'options.txt')
    options_text = (user_instance / 'options.txt').read_text(encoding='utf-8-sig')
    lines = [line for line in options_text.splitlines() if line.split(':', 1)[0] not in common.SETTINGS]
    (base / 'baseline-options.txt').write_text('\n'.join(lines + [f'{key}:{value}' for key, value in common.SETTINGS.items()]) + '\n', encoding='utf-8')
    watut = base / 'baseline-config/watut-common.toml'
    shutil.copy2(watut, original / watut.name)
    text, count = re.subn(r'(?m)^(\s*ticksToMarkPlayerIdle\s*=\s*)\d+\s*$', r'\g<1>120000', watut.read_text(encoding='utf-8-sig'))
    assert count == 1, 'Expected exactly one native WATUT idle threshold'
    watut.write_text(text, encoding='utf-8')
    dynamic = base / 'baseline-config/dynamic_fps.json'
    shutil.copy2(dynamic, original / dynamic.name)
    dynamic_data = load(dynamic)
    dynamic_data['enabled'] = False
    save(dynamic, dynamic_data)
    emf = load(base / 'baseline-config/entity_model_features.json')
    assert emf.get('preventFirstPersonHandAnimating') is False, 'Match the current enabled EMF hand animation'
    configs = common.tree_hashes(base / 'baseline-config')
    sync_backup = inside(base / 'initial-runtime-sync')
    sync_backup.mkdir()
    synchronization = dict(
        sharedMods=sync_files(user_instance / 'mods', runtime / 'mods', sync_backup / 'mods', user_shared,
                              lambda name: name.endswith('.jar') and not name.startswith(OPTIONAL_PREFIXES)),
        resourcepacks=sync_files(user_instance / 'resourcepacks', runtime / 'resourcepacks', sync_backup / 'resourcepacks', user_packs, lambda name: True),
        shaderpacks=sync_files(user_instance / 'shaderpacks', runtime / 'shaderpacks', sync_backup / 'shaderpacks', user_shaders, lambda name: True))
    artifacts = {}
    for key, source in (('old124', Path(old['path'])), ('candidate125', candidate), ('harness', Path(harness['path']))):
        target = base / 'artifacts' / key / source.name
        target.parent.mkdir(parents=True)
        shutil.copy2(source, target)
        artifacts[key] = common.artifact(target)
    plan = dict(status='INITIALIZED_NOT_EXECUTED', createdUtc=utc(), base=str(base), runtime=str(runtime),
                order=ORDER, modes={key: dict(compatInstalled=value is not None, firstPersonInstalled=False,
                                           artifactKey=value) for key, value in MODES.items()},
                artifacts=artifacts, sharedModSha256=user_shared, baselineConfigSha256=configs,
                baselineWorldSha256=world_hashes, optionsSha256=sha(base / 'baseline-options.txt'), settings=common.SETTINGS,
                expectedSceneCount=8, primaryScenes=PRIMARY_SCENES, warmupSeconds=10,
                firstSceneWarmupSeconds=30, measureSeconds=20,
                historicalBaseline=dict(path=str(historical), planSha256=sha(previous_path),
                                        priorResultsReusedAsMeasurements=False),
                currentUserSnapshot=dict(path=str(user_path), sha256=sha(user_path),
                    recordedUtc=user['recordedUtc'], sharedModsEqual=True, resourcepacksEqual=True,
                    configDifferencesFromCurrentUser=differences(configs, user_configs),
                    configDifferencesFromHistoricalBaseline=differences(configs, previous['baselineConfigSha256']),
                    sharedModDifferencesFromHistoricalBaseline=differences(user_shared, previous['sharedModSha256']),
                    optionsDifferFromCurrentUser=user['optionsSha256'] != sha(base / 'baseline-options.txt'),
                    benchmarkOnlyChanges=previous['benchmarkOnlyChanges']),
                sourceSha256=source_hashes(), resourcepackSha256=user_packs,
                shaderpackSha256=common.tree_hashes(base / 'baseline-shaderpacks'),
                baselineShaderpackPath=str(base / 'baseline-shaderpacks'), revisionId='revision-001',
                labelPrefix='sable125', storagePreflight=storage,
                disposableRuntimeSynchronization=synchronization,
                constraints=['FirstPerson is physically absent in every run; no FirstPerson tests.',
                             'Six fresh JVM passes, two repeats per state, reversed order in second half.',
                             'Primary endpoints: clear moving Sable deck8/deck24 and real-wall-occluded deck8.',
                             'Remaining five frozen-harness scenes are secondary control measurements.',
                             'Only earliest valid non-excluded attempt per pass; reject focus/camera failures.',
                             'Wall visibility/tick/render differences are behavior costs, not pure hook overhead.',
                             'Old performance-equipped evidence and source user pack are immutable.'])
    save(base / 'plan.json', plan)
    save(OUT / 'execution-plan.json', plan)
    print(__import__('json').dumps(dict(initialized=str(base / 'plan.json'), userInstanceMutated=False,
                                     synchronization=synchronization,
                                     configDifferences=len(plan['currentUserSnapshot']['configDifferencesFromCurrentUser']))))


def revise(args):
    """Freeze a new revision without rewriting any previous plan or evidence."""
    base = inside(args.base)
    previous_path = plan_path(base)
    previous = load(previous_path)
    runtime = runtime_path(previous['runtime'])
    require_stopped(runtime)
    if not re.fullmatch('revision-[0-9]{3}', args.revision):
        raise ValueError('Revision must have form revision-002')
    revision_dir = inside(base / 'revisions' / args.revision)
    if revision_dir.exists():
        raise FileExistsError('Revision already exists; never replace frozen evidence')
    harness = Path(args.harness).resolve()
    assert re.fullmatch('[0-9a-f]{64}', args.harness_sha) and sha(harness) == args.harness_sha
    with zipfile.ZipFile(harness) as archive:
        assert not any(n.startswith(('dev/caleb/emfattachments/client/', 'dev/caleb/emfattachments/mixin/',
                                     'dev/caleb/emfattachments/test/')) or n.endswith('.jar') for n in archive.namelist())
    for key in ('old124', 'candidate125'):
        item = previous['artifacts'][key]
        assert sha(item['path']) == item['sha256'], 'Production artifact changed; fixture-only revision required'
    source = Path(args.shader_source).resolve() if args.shader_source else Path(load(previous['currentUserSnapshot']['path'])['instance']) / 'shaderpacks'
    shader_hashes = common.tree_hashes(source)
    assert shader_hashes == previous['shaderpackSha256'], 'Shader source must exactly reconstruct the originally frozen full tree'
    common.require_space(runtime, common.tree_bytes(source) + harness.stat().st_size * 2)
    revision_dir.mkdir(parents=True)
    shutil.copytree(source, revision_dir / 'baseline-shaderpacks')
    target = revision_dir / 'artifacts/harness' / harness.name
    target.parent.mkdir(parents=True)
    shutil.copy2(harness, target)
    # Deep copy: prior artifact records retain their original paths/hashes.
    import copy
    plan = copy.deepcopy(previous)
    plan.update(status='REVISED_NOT_EXECUTED', revisedUtc=utc(), revisionId=args.revision,
                labelPrefix='sable125-' + args.revision,
                revisionReason=args.reason,
                supersedes=dict(planPath=str(previous_path), sha256=sha(previous_path),
                                measurementsRetainedButExcluded=True),
                sourceSha256=source_hashes(),
                baselineShaderpackPath=str(revision_dir / 'baseline-shaderpacks'),
                shaderpackSha256=shader_hashes,
                shaderSnapshotPolicy='Compare full tree hashes before/after every pass; archive only differing file bytes and restore from one immutable snapshot. Iris may rewrite a nonsemantic timestamp comment afterward.')
    plan['artifacts']['harness'] = common.artifact(target)
    destination = revision_dir / 'plan.json'
    save(destination, plan)
    evidence = OUT / 'revisions' / args.revision
    evidence.mkdir(parents=True)
    for name in ('execution-plan.json', 'matrix-progress.json'):
        old = OUT / name
        if old.exists():
            shutil.copy2(old, evidence / ('superseded-' + name))
    save(evidence / 'execution-plan.json', plan)
    save(base / 'active-plan.json', dict(revisionId=args.revision, planPath=str(destination), sha256=sha(destination)))
    save(OUT / 'execution-plan.json', plan)
    print(__import__('json').dumps(dict(revised=args.revision, plan=str(destination),
        oldPlanUnchangedSha256=sha(previous_path), runtimeMutated=False, userInstanceMutated=False)))


def restore_shader_snapshot(plan, runtime, archive):
    """Restore exact bytes, preserving only changed prior files and hash maps."""
    base = inside(plan['base'])
    source = inside(plan['baselineShaderpackPath'], base)
    target = inside(runtime / 'shaderpacks')
    before, expected = common.tree_hashes(target), plan['shaderpackSha256']
    assert common.tree_hashes(source) == expected, 'Immutable shader snapshot changed'
    changed = []
    for relative in sorted(set(before) | set(expected)):
        if before.get(relative) == expected.get(relative):
            continue
        path = inside(target / relative, target)
        saved_path = None
        if path.exists():
            saved_path = inside(archive / 'shaderpacks-mutated' / relative, archive)
            saved_path.parent.mkdir(parents=True, exist_ok=True)
            path.rename(saved_path)
        if relative in expected:
            path.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(inside(source / relative, source), path)
        changed.append(dict(path=relative, beforeSha256=before.get(relative),
                            afterSha256=expected.get(relative), archivedPath=str(saved_path) if saved_path else None))
    # Added files can leave empty directories. Remove only empty paths that the
    # immutable tree does not contain; every resolved target remains below it.
    expected_dirs = {str(p.relative_to(source)).replace('\\', '/') for p in source.rglob('*') if p.is_dir()}
    for path in sorted((p for p in target.rglob('*') if p.is_dir()), key=lambda p: len(p.parts), reverse=True):
        if str(path.relative_to(target)).replace('\\', '/') not in expected_dirs:
            inside(path, target).rmdir()
    after = common.tree_hashes(target)
    assert after == expected, 'Shader tree did not reset exactly'
    record = dict(beforeSha256=before, afterSha256=after, changedFiles=changed,
                  archivedBytes=sum(Path(row['archivedPath']).stat().st_size for row in changed if row['archivedPath']),
                  copiedBytes=sum((source / row['path']).stat().st_size for row in changed if row['afterSha256']))
    save(archive / 'shaderpack-reset.json', record)
    return dict(changedFiles=len(changed), archivedBytes=record['archivedBytes'], copiedBytes=record['copiedBytes'],
                manifestPath=str(archive / 'shaderpack-reset.json'), manifestSha256=sha(archive / 'shaderpack-reset.json'))


def post_run_shader_audit(plan, label):
    """After JVM exit only: retain hashes/changed bytes, permit timestamp only."""
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
            before = inside(source / relative, source).read_bytes().splitlines()
            after = inside(target / relative, target).read_bytes().splitlines()
            timestamp_only = (bool(before) and bool(after) and before[0].startswith(b'#') and after[0].startswith(b'#')
                              and before[1:] == after[1:])
        saved_path = None
        if relative in observed:
            saved_path = OUT / f'{label}-shader-after' / relative
            saved_path.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(inside(target / relative, target), saved_path)
        rows.append(dict(path=relative, beforeSha256=expected.get(relative), afterSha256=observed.get(relative),
                         timestampCommentOnly=timestamp_only, savedPath=str(saved_path) if saved_path else None))
    record = dict(label=label, observedUtc=utc(), jvmExited=True,
                  valid=all(row['timestampCommentOnly'] for row in rows),
                  baselineSha256=expected, observedSha256=observed, differingFiles=rows)
    save(OUT / f'{label}-shader-audit.json', record)
    return record


def audit_runtime(plan, mode):
    runtime = runtime_path(plan['runtime'])
    artifact_key = MODES[mode]
    expected = dict(plan['sharedModSha256'])
    for key in ('harness',) + ((artifact_key,) if artifact_key else ()):
        item = plan['artifacts'][key]
        expected[item['name']] = item['sha256']
    jars = {p.name: sha(p) for p in sorted((runtime / 'mods').glob('*.jar'))}
    configs = common.tree_hashes(runtime / 'config')
    violations = []
    for condition, message in (
        (jars == expected, 'Active jar inventory/hash differs from frozen state'),
        (configs == plan['baselineConfigSha256'], 'Config bytes differ from pinned baseline'),
        (sha(runtime / 'options.txt') == plan['optionsSha256'], 'Options bytes differ from pinned baseline'),
        (common.tree_hashes(runtime / 'saves/AttachmentTest') == plan['baselineWorldSha256'], 'World differs from cold snapshot'),
        ({p.name: sha(p) for p in (runtime / 'resourcepacks').iterdir() if p.is_file()} == plan['resourcepackSha256'], 'Resource packs changed'),
        (common.tree_hashes(runtime / 'shaderpacks') == plan['shaderpackSha256'], 'Shader packs changed')):
        if not condition:
            violations.append(message)
    options = common.properties(runtime / 'options.txt')
    return dict(valid=not violations, violations=violations, runtime=str(runtime), activeModSha256=jars,
                configSha256=configs, optionsSha256=sha(runtime / 'options.txt'),
                observedSettings={k: options.get(k) for k in plan['settings']},
                compatPhysicallyInstalled=artifact_key is not None, firstPersonPhysicallyInstalled=False,
                compatArtifact=plan['artifacts'].get(artifact_key), harnessArtifact=plan['artifacts']['harness'],
                actualNativeModList='Must be independently confirmed by native report')


def prepare_pass(args):
    base = inside(args.base)
    active_plan_path = plan_path(base)
    plan = load(active_plan_path)
    runtime = runtime_path(plan['runtime'])
    require_stopped(runtime)
    if not 1 <= args.pass_number <= len(ORDER) or not 1 <= args.attempt <= 99:
        raise ValueError('Pass must be1..6, attempt1..99')
    mode = ORDER[args.pass_number - 1]
    label = pass_label(plan, args.pass_number, mode, args.attempt)
    archive = inside(base / 'pass-history' / label)
    if archive.exists() or (OUT / f'{label}-manifest.json').exists() or (runtime / 'emfattachments-tests' / label).exists():
        raise FileExistsError('Pass label exists; use a new attempt')
    for item in plan['artifacts'].values():
        assert sha(item['path']) == item['sha256'], 'Frozen artifact changed'
    assert source_hashes() == plan['sourceSha256'], 'Benchmark or production sources changed after freeze'
    triggers = list(runtime.glob('emfattachments-*.txt'))
    if triggers:
        raise RuntimeError('Unconsumed harness triggers; review before preparation: ' + ', '.join(p.name for p in triggers))
    shader_snapshot = inside(plan['baselineShaderpackPath'], base)
    assert common.tree_hashes(shader_snapshot) == plan['shaderpackSha256'], 'Immutable shader snapshot changed'
    space = common.require_space(runtime, common.tree_bytes(base / 'baseline-world') + common.tree_bytes(base / 'baseline-config') + common.tree_bytes(shader_snapshot) + 8 * 1024 * 1024)
    archive.mkdir(parents=True)
    for name, source in (('world', runtime / 'saves/AttachmentTest'), ('config', runtime / 'config')):
        source = inside(source)
        if source.exists():
            source.rename(inside(archive / name))
    if (runtime / 'options.txt').exists():
        shutil.copy2(runtime / 'options.txt', archive / 'options.txt')
    shutil.copytree(base / 'baseline-world', runtime / 'saves/AttachmentTest')
    shutil.copytree(base / 'baseline-config', runtime / 'config')
    shader_reset = restore_shader_snapshot(plan, runtime, archive)
    shutil.copy2(base / 'baseline-options.txt', runtime / 'options.txt')
    for path in (runtime / 'mods').iterdir():
        if path.is_file() and path.name.startswith(OPTIONAL_PREFIXES):
            path.rename(inside(archive / path.name))
    selected = MODES[mode]
    for key in ('harness',) + ((selected,) if selected else ()):
        item = plan['artifacts'][key]
        shutil.copy2(item['path'], runtime / 'mods' / item['name'])
    manifest = audit_runtime(plan, mode)
    manifest.update(passNumber=args.pass_number, attempt=args.attempt, mode=mode, label=label,
                    preparedUtc=utc(), launched=False, spacePreflight=space,
                    planPath=str(active_plan_path), planSha256=sha(active_plan_path), revisionId=plan.get('revisionId', 'revision-001'),
                    shaderpackSha256=plan['shaderpackSha256'], baselineShaderpackPath=str(shader_snapshot),
                    shaderReset=shader_reset,
                    sourceSha256=plan['sourceSha256'])
    assert manifest['valid'], manifest['violations']
    save(OUT / f'{label}-manifest.json', manifest)
    print(__import__('json').dumps(dict(prepared=label, mode=mode, firstPersonInstalled=False,
                                     launched=False, triggerArmed=False)))


def arm_trigger(base, label):
    """Called only after the owned JVM identity audit, before sampling starts."""
    plan = load_plan(base)
    runtime = runtime_path(plan['runtime'])
    manifest = load(OUT / f'{label}-manifest.json')
    identity = load(OUT / f'{label}-process.json')
    assert manifest['valid'] and identity['offlineIdentityConfirmed'] is True
    assert int(load(runtime / 'process.json')['pid']) == identity['pid'] and running(identity['pid'])
    assert not list(runtime.glob('emfattachments-*.txt')), 'Unconsumed trigger exists'
    identity.update(triggerArmedUtc=utc(), processAuditBeforeTrigger=True)
    save(OUT / f'{label}-process.json', identity)
    (runtime / common.TRIGGER).write_text(f'{label}\nwarmupSeconds=10\nmeasureSeconds=20\n', encoding='utf-8')


def process_audit(args):
    plan = load_plan(args.base)
    runtime = runtime_path(plan['runtime'])
    pid = int(load(runtime / 'process.json')['pid'])
    if not running(pid):
        raise RuntimeError('Owned test JVM is not running')
    result = subprocess.run([str(args.jcmd), str(pid), 'VM.system_properties'], capture_output=True, text=True, check=True)
    props = common.parse_java_properties(result.stdout)
    command = props.get('sun.java.command', '')
    assert Path(props.get('user.dir', '')).resolve() == runtime
    assert '--username AttachmentTest' in command and '--userType legacy' in command and '--accessToken 0' in command
    assert props.get('emfattachments.disable') != 'true'
    record = dict(pid=pid, observedUtc=utc(), offlineIdentityConfirmed=True, userDir=str(runtime),
                  productionDisableFlagAbsent=True, launchArgsSha256=sha(runtime / 'launch.args'),
                  sensitiveLaunchArgumentsStored=False)
    save(OUT / f'{args.label}-process.json', record)
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', type=Path, default=BASE)
    commands = parser.add_subparsers(dest='command', required=True)
    p = commands.add_parser('init')
    p.add_argument('--historical-base', type=Path, default=HISTORICAL_BASE)
    p.add_argument('--runtime', type=Path)
    p.add_argument('--candidate', type=Path, required=True)
    p.add_argument('--candidate-sha', required=True)
    p.add_argument('--user-snapshot', type=Path, default=ROOT / 'verification/culling-1.2.5/before-change.json')
    p = commands.add_parser('revise')
    p.add_argument('--revision', required=True)
    p.add_argument('--harness', type=Path, required=True)
    p.add_argument('--harness-sha', required=True)
    p.add_argument('--shader-source', type=Path)
    p.add_argument('--reason', required=True)
    p = commands.add_parser('prepare-pass')
    p.add_argument('pass_number', type=int)
    p.add_argument('--attempt', type=int, default=1)
    p = commands.add_parser('audit')
    p.add_argument('--mode', choices=MODES, required=True)
    p = commands.add_parser('process-audit')
    p.add_argument('--label', required=True)
    p.add_argument('--jcmd', type=Path, default=Path('C:/Program Files/Java/jdk-21/bin/jcmd.exe'))
    args = parser.parse_args()
    if args.command == 'init':
        init(args)
    elif args.command == 'revise':
        revise(args)
    elif args.command == 'prepare-pass':
        prepare_pass(args)
    elif args.command == 'audit':
        print(__import__('json').dumps(audit_runtime(load_plan(args.base), args.mode)))
    else:
        if not re.fullmatch('[A-Za-z0-9._-]+', args.label):
            raise ValueError('Simple pass label required')
        print(__import__('json').dumps(process_audit(args)))


if __name__ == '__main__':
    main()
