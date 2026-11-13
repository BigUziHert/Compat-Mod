"""Fresh-process production versus physical removal in an owned offline client.

Only the disposable runtime is changed. Native foreground focus is handled by
the operator. Reports and every replaced disposable world/config are retained.
"""
from pathlib import Path
from types import SimpleNamespace
import argparse
import json
import re
import shutil
import subprocess
import time
import zipfile

import prepare_equipped_benchmark as common
import prepare_sable_culling_benchmark as sable
import test_runtime
import analyze_perspective_benchmark as perspective_analysis

ROOT = common.ROOT
BASE = ROOT / '.local/perspective-benchmark-1.2.7'
OUT = ROOT / 'verification/performance-1.2.7'
RUNTIME = ROOT / '.local/without-firstperson/.local/client'
USER = test_runtime.INSTANCE
ORDER = [('REMOVED', 'SHADERS'), ('INSTALLED', 'SHADERS'),
         ('INSTALLED', 'SHADERS'), ('REMOVED', 'SHADERS'),
         ('REMOVED', 'NO_SHADERS'), ('INSTALLED', 'NO_SHADERS')]
OPTIONAL = sable.OPTIONAL_PREFIXES
VERSION = '1.2.7'


def emit(**values):
    print(json.dumps(values), flush=True)


def user_snapshot():
    return dict(instance=str(USER), mods={p.name: common.sha(p) for p in (USER / 'mods').iterdir() if p.is_file()},
                config=common.tree_hashes(USER / 'config'), options=common.sha(USER / 'options.txt'),
                resourcepacks=common.tree_hashes(USER / 'resourcepacks'), shaderpacks=common.tree_hashes(USER / 'shaderpacks'))


def initialize(args):
    common.require_stopped(RUNTIME)
    if (BASE / 'plan.json').exists():
        raise FileExistsError(BASE / 'plan.json')
    # This suite needs <500 MiB; do not duplicate the multi-GB mod collection.
    common.require_space(RUNTIME, 500 * 1024**2, reserve_bytes=512 * 1024**2)
    before = user_snapshot()
    assert not any(n.startswith('firstperson-') and n.endswith('.jar') for n in before['mods']), 'Review the user FirstPerson state'
    BASE.mkdir(parents=True, exist_ok=True)
    OUT.mkdir(parents=True, exist_ok=True)
    if (OUT / 'user-before.json').exists():
        assert common.load(OUT / 'user-before.json') == before, 'User inputs changed during initialization'
    common.save(OUT / 'user-before.json', before)
    for origin, folder in ((ROOT / '.local/equipped-benchmark-v2/baseline-world', 'baseline-world'),
                           (USER / 'config', 'baseline-config'), (USER / 'shaderpacks', 'baseline-shaderpacks')):
        if not (BASE / folder).exists():
            shutil.copytree(origin, BASE / folder)
    shutil.copy2(USER / 'options.txt', BASE / 'source-options.txt')
    settings = dict(common.SETTINGS)
    lines = [s for s in (USER / 'options.txt').read_text(encoding='utf-8-sig').splitlines()
             if s.split(':', 1)[0] not in settings]
    (BASE / 'baseline-options.txt').write_text('\n'.join(lines + [f'{k}:{v}' for k,v in settings.items()]) + '\n', encoding='utf-8')
    dynamic = BASE / 'baseline-config/dynamic_fps.json'
    data = common.load(dynamic)
    data['enabled'] = False
    common.save(dynamic, data)
    watut = BASE / 'baseline-config/watut-common.toml'
    value, count = re.subn(r'(?m)^(\s*ticksToMarkPlayerIdle\s*=\s*)\d+\s*$', r'\g<1>120000', watut.read_text(encoding='utf-8-sig'))
    assert count == 1
    watut.write_text(value, encoding='utf-8')
    active = {n:h for n,h in before['mods'].items() if n.endswith('.jar') and not n.startswith(OPTIONAL)}
    sync = sable.sync_files(USER / 'mods', RUNTIME / 'mods', BASE / 'initial-backup/mods', active,
                            lambda n: n.endswith('.jar') and not n.startswith(OPTIONAL))
    # Restore exact resource/shader files without replacing unrelated evidence.
    for folder in ('resourcepacks', 'shaderpacks'):
        source, dest = USER / folder, RUNTIME / folder
        for existing in dest.rglob('*'):
            if existing.is_file() and not (source / existing.relative_to(dest)).is_file():
                backup = common.inside(BASE / 'initial-backup' / folder / existing.relative_to(dest))
                backup.parent.mkdir(parents=True, exist_ok=True)
                common.inside(existing, dest).rename(backup)
        for p in source.rglob('*'):
            if not p.is_file():
                continue
            rel = p.relative_to(source)
            target = common.inside(dest / rel)
            if target.exists() and common.sha(target) == common.sha(p):
                continue
            if target.exists():
                backup = common.inside(BASE / 'initial-backup' / folder / rel)
                backup.parent.mkdir(parents=True, exist_ok=True)
                target.rename(backup)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(p, target)
        assert common.tree_hashes(source) == common.tree_hashes(dest), f'{folder} has unmatched extra files'
    production = args.production_jar or USER / f'mods/emf-attachments-compat-1.21.1-{VERSION}.jar'
    production = production.resolve()
    with zipfile.ZipFile(production) as jar:
        metadata = jar.read('META-INF/neoforge.mods.toml').decode('utf-8')
    mod_blocks = re.split(r'(?m)^\[\[mods\]\]\s*$', metadata)[1:]
    assert any(re.search(r'(?m)^modId\s*=\s*"emfattachments"\s*$', block.split('[[', 1)[0])
               and re.search(r'(?m)^version\s*=\s*"' + re.escape(VERSION) + r'"\s*$', block.split('[[', 1)[0])
               for block in mod_blocks), 'Production version mismatch'
    target = BASE / 'artifacts' / production.name
    target.parent.mkdir()
    shutil.copy2(production, target)
    scenes = perspective_analysis.select_scenes(args.scene_filter, args.include_ground_crowd)
    scene_filter = perspective_analysis.scene_pattern(scenes) if args.scene_filter or args.include_ground_crowd else None
    plan = dict(createdUtc=common.utc(), base=str(BASE), runtime=str(RUNTIME), order=ORDER[:4] if args.primary_only else ORDER,
                version=VERSION, labelPrefix='perspective' + VERSION.replace('.', ''), scenes=scenes,
                sceneFilter=scene_filter, includeGroundCrowd=args.include_ground_crowd,
                production=common.artifact(target), sharedModSha256=active,
                baselineWorldSha256=common.tree_hashes(BASE / 'baseline-world'),
                baselineConfigSha256=common.tree_hashes(BASE / 'baseline-config'),
                baselineShaderpackPath=str(BASE / 'baseline-shaderpacks'), shaderpackSha256=before['shaderpacks'],
                resourcepackSha256=before['resourcepacks'], optionsSha256=common.sha(BASE / 'baseline-options.txt'),
                settings=settings, synchronization=sync,
                controls=['2560x1440 fullscreen, FOV90, render/simulation8, vsync off, maxFps260',
                          'DynamicFPS disabled; WATUT idle threshold raised; audio muted in disposable copy',
                          'Current user mods/config/resource/shaders; FirstPerson physically absent',
                          'Historical clean disposable flat world reset before each pass; no user saves used'],
                warmupSeconds=args.warmup if args.warmup is not None else (10 if VERSION == '1.2.7' else 20),
                measureSeconds=args.measure if args.measure is not None else (20 if VERSION == '1.2.7' else 60),
                firstSceneExtraWarmupSeconds=20)
    common.save(BASE / 'plan.json', plan)
    common.save(OUT / 'execution-plan.json', plan)
    emit(initialized=str(BASE), productionSha256=plan['production']['sha256'], freeBytes=shutil.disk_usage(BASE).free)


def freeze_harness():
    plan = common.load(BASE / 'plan.json')
    build = common.load(OUT / 'perf-build.json')
    source = Path(build['artifact'])
    assert common.sha(source) == build['sha256']
    if 'harness' in plan:
        assert plan['harness']['sha256'] == build['sha256'] and plan['harnessSourceSha256'] == build['sourceSha256'], 'Harness changed after plan freeze; use a new matrix'
        assert common.sha(plan['harness']['path']) == plan['harness']['sha256'], 'Frozen harness bytes changed'
        return plan
    target = BASE / 'artifacts' / ('emfattachments-perf-' + build['sha256'][:12] + '.jar')
    if not target.exists():
        shutil.copy2(source, target)
    plan['harness'] = common.artifact(target)
    plan['harnessSourceSha256'] = build['sourceSha256']
    common.save(BASE / 'plan.json', plan)
    common.save(OUT / 'execution-plan.json', plan)
    return plan


def prepare(number, attempt, smoke=False, scene_filter=None):
    common.require_stopped(RUNTIME)
    plan = freeze_harness()
    assert Path(plan['runtime']).resolve() == RUNTIME.resolve(), 'Runtime differs from frozen plan'
    mode, graphics = plan['order'][number-1]
    planned_scenes = perspective_analysis.expected_scenes(plan)
    if scene_filter is not None:
        selected = perspective_analysis.select_scenes(scene_filter, plan.get('includeGroundCrowd', False))
        assert smoke or selected == planned_scenes, 'Non-smoke scene selection must match frozen plan'
        scene_filter = perspective_analysis.scene_pattern(selected)
    else:
        selected, scene_filter = planned_scenes, plan.get('sceneFilter')
    label = f'{plan.get("labelPrefix", "perspective127")}-{"smoke-" if smoke else ""}pass{number}-{mode.lower()}-{graphics.lower()}-r{attempt}'
    archive = common.inside(BASE / 'pass-history' / label)
    assert not archive.exists() and not (OUT / f'{label}-manifest.json').exists()
    assert not list(RUNTIME.glob('emfattachments-*.txt')), 'Inspect unconsumed triggers before running'
    common.require_space(RUNTIME, 75 * 1024**2, reserve_bytes=512 * 1024**2)
    archive.mkdir(parents=True)
    for folder, source in (('world', RUNTIME / 'saves/AttachmentTest'), ('config', RUNTIME / 'config')):
        common.inside(source).rename(common.inside(archive / folder))
    shutil.copy2(RUNTIME / 'options.txt', archive / 'options.txt')
    shutil.copytree(BASE / 'baseline-world', RUNTIME / 'saves/AttachmentTest')
    shutil.copytree(BASE / 'baseline-config', RUNTIME / 'config')
    shutil.copy2(BASE / 'baseline-options.txt', RUNTIME / 'options.txt')
    sable.restore_shader_snapshot(plan, RUNTIME, archive)
    if graphics == 'NO_SHADERS':
        path = RUNTIME / 'config/iris.properties'
        value,count = re.subn(r'(?m)^enableShaders=true$', 'enableShaders=false', path.read_text())
        assert count == 1
        path.write_text(value, encoding='utf-8')
    for p in (RUNTIME / 'mods').iterdir():
        if p.is_file() and p.name.startswith(OPTIONAL):
            p.rename(common.inside(archive / p.name))
    expected = dict(plan['sharedModSha256'])
    for key in ('harness',) + (('production',) if mode == 'INSTALLED' else ()):
        item = plan[key]
        assert common.sha(item['path']) == item['sha256']
        shutil.copy2(item['path'], RUNTIME / 'mods' / item['name'])
        expected[item['name']] = item['sha256']
    actual = {p.name:common.sha(p) for p in (RUNTIME / 'mods').glob('*.jar')}
    assert actual == expected
    assert common.tree_hashes(RUNTIME / 'saves/AttachmentTest') == plan['baselineWorldSha256']
    assert common.tree_hashes(RUNTIME / 'resourcepacks') == plan['resourcepackSha256']
    assert common.tree_hashes(RUNTIME / 'shaderpacks') == plan['shaderpackSha256']
    manifest = dict(label=label, mode=mode, graphics=graphics, passNumber=number, attempt=attempt, smoke=smoke,
                    runtime=str(RUNTIME), reportPath=str(RUNTIME / 'emfattachments-tests' / label / 'equipped-crowd-frames.json'),
                    preparedUtc=common.utc(), valid=True, planPath=str(BASE / 'plan.json'), planSha256=common.sha(BASE / 'plan.json'),
                    activeModSha256=actual, configSha256=common.tree_hashes(RUNTIME / 'config'),
                    optionsSha256=common.sha(RUNTIME / 'options.txt'), harnessArtifact=plan['harness'],
                    compatArtifact=plan['production'] if mode == 'INSTALLED' else None,
                    firstPersonPhysicallyInstalled=False, productionPhysicallyInstalled=mode == 'INSTALLED',
                    warmupSeconds=5 if smoke else plan['warmupSeconds'], measureSeconds=5 if smoke else plan['measureSeconds'],
                    scenes=selected, sceneFilter=scene_filter, includeGroundCrowd=plan.get('includeGroundCrowd', False))
    common.save(OUT / f'{label}-manifest.json', manifest)
    return manifest


def gpu():
    result = subprocess.run(['C:/Windows/System32/nvidia-smi.exe', '--query-gpu=name,driver_version,pstate,temperature.gpu,utilization.gpu,memory.used,power.draw,clocks.current.graphics', '--format=csv,noheader'], capture_output=True,text=True,timeout=15)
    return dict(observedUtc=common.utc(), csv=result.stdout.strip(), exitCode=result.returncode)


def tail(path):
    if not path.exists():
        return {}
    with path.open('rb') as stream:
        stream.seek(0,2)
        stream.seek(max(0,stream.tell()-262144))
        value=stream.read()
    record={'finished':bool(re.search(rb'"finished"\s*:\s*true',value))}
    for key in ('status','phase','activeScene','detail'):
        values=re.findall(('"'+key+'"\\s*:\\s*"([^"\\r\\n]*)"').encode(),value)
        if values:
            record[key]=values[-1].decode('utf-8')
    return record


def run(number, attempt, smoke=False, scene_filter=None):
    manifest = prepare(number,attempt,smoke,scene_filter)
    label=manifest['label']
    execution=dict(label=label, gpuBefore=gpu(), startedUtc=common.utc())
    test_runtime.RUNTIME=RUNTIME
    test_runtime.launch(False,False)
    pid=int(common.load(RUNTIME/'process.json')['pid'])
    execution['pid']=pid
    emit(event='LAUNCHED_NEEDS_FOREGROUND',label=label,pid=pid)
    time.sleep(5)
    # Use the existing bounded identity audit, never print full JVM properties.
    sable.OUT=OUT
    sable.process_audit(SimpleNamespace(base=BASE,label=label,jcmd=Path('C:/Program Files/Java/jdk-21/bin/jcmd.exe')))
    trigger=f'{label}\nwarmupSeconds={manifest["warmupSeconds"]}\nmeasureSeconds={manifest["measureSeconds"]}\nperspectiveComparison=true\n'
    if manifest['sceneFilter']:
        trigger+=f'perspectiveSceneFilter={manifest["sceneFilter"]}\n'
    if manifest['includeGroundCrowd']:
        trigger+='perspectiveIncludeGroundCrowd=true\n'
    (RUNTIME/common.TRIGGER).write_text(trigger,encoding='utf-8')
    execution['triggerArmedUtc']=common.utc()
    report=Path(manifest['reportPath'])
    start=time.monotonic()
    previous=None
    while time.monotonic()-start<1200:
        if not common.running(pid):
            raise RuntimeError('Owned client exited before report completion')
        progress=tail(report)
        if progress!=previous:
            previous=progress
            emit(event='NATIVE_PROGRESS',label=label,elapsedSeconds=round(time.monotonic()-start),**progress)
        if progress.get('finished'):
            break
        time.sleep(5)
    else:
        raise TimeoutError('Benchmark timeout; inspect owned JVM')
    captured=common.load(report)
    execution.update(nativeStatus=captured.get('status'),gpuAfter=gpu(),reportSha256=common.sha(report))
    (RUNTIME/'emfattachments-perf-stop.txt').write_text('stop',encoding='utf-8')
    limit=time.monotonic()+90
    while common.running(pid) and time.monotonic()<limit:
        time.sleep(1)
    assert not common.running(pid),'Owned client did not exit gracefully'
    execution['shaderAudit']=sable.post_run_shader_audit(common.load(BASE/'plan.json'),label)
    if not execution['shaderAudit']['valid']:
        execution['disabledShaderMetadataAudit'] = perspective_analysis.audit_disabled_shader_metadata(
            common.load(BASE/'plan.json'), manifest, execution['shaderAudit'], captured, OUT)
    for source,suffix in ((RUNTIME/'logs/latest.log','game.log'),(RUNTIME/'console-error.log','stderr.log')):
        if source.exists():
            shutil.copy2(source,OUT/f'{label}-{suffix}')
    execution['finishedUtc']=common.utc()
    common.save(OUT/f'{label}-execution.json',execution)
    emit(event='PASS_FINISHED',label=label,status=captured.get('status'),report=str(report))
    assert captured.get('status')=='CAPTURED',captured.get('detail')
    assert captured.get('plannedScenes') == manifest['scenes'], 'Native scene plan mismatch'
    assert [stage['sceneId'] for stage in captured.get('stages', [])] == manifest['scenes'], 'Missing/reordered native scenes'
    assert not captured.get('behaviorFailures'), captured.get('behaviorFailures')
    assert execution['shaderAudit']['valid'] or execution.get('disabledShaderMetadataAudit', {}).get('valid') is True, 'Shader snapshot changed beyond verified metadata'
    for stage in captured.get('stages', []):
        raw = stage['frameIntervalsNanos']
        assert len(raw) == stage['sampleCount'] and sum(raw) == stage['durationNanos']
        assert stage['actualViewLockedEveryMeasuredFrame'] is True, stage['sceneId']
        assert stage['rendererWorkloadComparable'] is True, stage['sceneId']
        assert stage['thirdPersonFramesLocalBodyReturned'] == stage['thirdPersonFrameCount'], stage['sceneId']
        expected_ticks = stage['localTrackedDeckTicks'] if stage['observerOnDeck'] else stage['localUntrackedTicks']
        assert expected_ticks == stage['measuredClientTicks'], stage['sceneId']
        assert stage['renderSettings']['shadersEnabled'] == (manifest['graphics'] == 'SHADERS'), stage['sceneId']


def main():
    global BASE, OUT, RUNTIME, VERSION
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command',choices=['init','run','audit-user'])
    parser.add_argument('--start',type=int,default=1)
    parser.add_argument('--end',type=int,default=1)
    parser.add_argument('--attempt',type=int,default=1)
    parser.add_argument('--smoke',action='store_true')
    parser.add_argument('--scene-filter')
    parser.add_argument('--include-ground-crowd', action='store_true', help='Opt in to the world-crowd-third-24 scene at initialization')
    parser.add_argument('--version', default='1.2.7')
    parser.add_argument('--base', type=Path)
    parser.add_argument('--out', type=Path)
    parser.add_argument('--runtime', type=Path)
    parser.add_argument('--production-jar', type=Path)
    parser.add_argument('--warmup', type=int, choices=range(5, 61), metavar='5..60')
    parser.add_argument('--measure', type=int, choices=range(5, 61), metavar='5..60')
    parser.add_argument('--primary-only', action='store_true', help='Freeze only the four shader ABBA passes at initialization')
    args=parser.parse_args()
    if not re.fullmatch(r'\d+\.\d+\.\d+', args.version):
        parser.error('--version must contain three numeric components')
    VERSION = args.version
    BASE = common.inside(args.base or ROOT / f'.local/perspective-benchmark-{VERSION}')
    OUT = common.inside(args.out or ROOT / f'verification/performance-{VERSION}', ROOT / 'verification')
    RUNTIME = common.runtime_path(args.runtime or RUNTIME)
    assert not BASE.is_relative_to(RUNTIME) and not RUNTIME.is_relative_to(BASE), 'Base and runtime must be separate directories'
    if args.command != 'init':
        plan = common.load(BASE / 'plan.json')
        if args.runtime is None:
            RUNTIME = common.runtime_path(plan['runtime'])
        assert RUNTIME == Path(plan['runtime']).resolve(), 'Runtime differs from frozen plan'
    if args.command=='init':
        initialize(args)
    elif args.command=='audit-user':
        after=user_snapshot()
        before=common.load(OUT/'user-before.json')
        common.save(OUT/'user-after.json',after)
        emit(userInstanceUnchanged=before==after)
        assert before==after
    else:
        assert 1<=args.start<=args.end<=len(plan['order'])
        for number in range(args.start,args.end+1):
            run(number,args.attempt,args.smoke,args.scene_filter)


if __name__=='__main__':
    main()
