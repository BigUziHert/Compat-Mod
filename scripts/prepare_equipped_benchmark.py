"""Prepare/audit disposable equipped-player benchmarks. Never launches Minecraft.

Run init after preparing the disposable runtime once. prepare-pass restores the
same world/config/options and physically selects the two optional mod jars.
"""
from pathlib import Path
import argparse
import ctypes
import datetime
import hashlib
import json
import os
import re
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'verification/performance-equipped'
BASE = ROOT / '.local/equipped-benchmark'
MAIN_SHA = '6aeec849c303a20a8cc100a44451963eca1fb46d47cd130a794a83c331dacff8'
MODES = {'A': (True, False), 'B': (False, False), 'C': (True, True), 'D': (False, True)}
ORDER = ['B', 'A', 'D', 'C', 'C', 'D', 'A', 'B']
SETTINGS = dict(fullscreen='true', overrideWidth='2560', overrideHeight='1440',
                maxFps='260', enableVsync='false', renderDistance='8', simulationDistance='8',
                pauseOnLostFocus='false', fov='0.5', guiScale='1', soundCategory_master='0.0')
TRIGGER = 'emfattachments-equipped-benchmark.txt'


def utc():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def sha(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for part in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(part)
    return h.hexdigest()


def load(path):
    return json.loads(Path(path).read_text(encoding='utf-8-sig'))


def save(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')


def inside(path, parent=ROOT / '.local'):
    path, parent = Path(path).resolve(), Path(parent).resolve()
    if not path.is_relative_to(parent) or path == parent:
        raise ValueError(f'Path must stay below {parent}: {path}')
    return path


def runtime_path(path):
    path = inside(path)
    if path.parts[-2:] != ('.local', 'client'):
        raise ValueError('Disposable runtime must end in .local/client')
    return path


def running(pid):
    if os.name != 'nt':
        try:
            os.kill(pid, 0)
            return True
        except ProcessLookupError:
            return False
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel.OpenProcess.restype = ctypes.c_void_p
    handle = kernel.OpenProcess(0x1000, False, int(pid))
    if not handle:
        if ctypes.get_last_error() == 5:
            raise RuntimeError('Cannot safely establish prior process exit')
        return False
    try:
        code = ctypes.c_ulong()
        if not kernel.GetExitCodeProcess(ctypes.c_void_p(handle), ctypes.byref(code)):
            raise RuntimeError('Cannot inspect prior owned process')
        return code.value == 259
    finally:
        kernel.CloseHandle(ctypes.c_void_p(handle))


def require_stopped(runtime):
    record = runtime / 'process.json'
    if record.exists() and running(int(load(record)['pid'])):
        raise RuntimeError('Previous owned test client is still running; stop it before preparation')
    if (runtime / 'emfattachments-queue.lock').exists():
        raise RuntimeError('A queue still owns this runtime')


def tree_hashes(path):
    path = Path(path)
    return {str(p.relative_to(path)).replace('\\', '/'): sha(p)
            for p in sorted(path.rglob('*')) if p.is_file()}


def tree_bytes(path):
    return sum(p.stat().st_size for p in Path(path).rglob('*') if p.is_file())


def require_space(path, copy_bytes, reserve_bytes=2 * 1024 * 1024 * 1024):
    free = shutil.disk_usage(path).free
    if free < copy_bytes + reserve_bytes:
        raise RuntimeError(f'Insufficient free space: {free} bytes free, {copy_bytes} bytes copy estimate plus {reserve_bytes} reserve')
    return dict(freeBytes=free, copyEstimateBytes=copy_bytes, reservedBytes=reserve_bytes)


def artifact(path):
    path = Path(path).resolve()
    return dict(path=str(path), name=path.name, sha256=sha(path), bytes=path.stat().st_size)


def properties(path, separator=':'):
    return dict(line.split(separator, 1) for line in Path(path).read_text(encoding='utf-8-sig').splitlines()
                if separator in line and not line.lstrip().startswith('#'))


def unescape_java_property(value):
    """Decode java.util.Properties escaping without logging the source value."""
    decoded, offset = [], 0
    controls = {'t': '\t', 'r': '\r', 'n': '\n', 'f': '\f'}
    while offset < len(value):
        char = value[offset]
        offset += 1
        if char != '\\':
            decoded.append(char)
            continue
        if offset == len(value):
            raise ValueError('Incomplete escape in JVM property')
        escaped = value[offset]
        offset += 1
        if escaped == 'u':
            digits = value[offset:offset + 4]
            if len(digits) != 4 or not all(c in '0123456789abcdefABCDEF' for c in digits):
                raise ValueError('Malformed Unicode escape in JVM property')
            decoded.append(chr(int(digits, 16)))
            offset += 4
        else:
            # Java drops the escape marker for :, =, space, backslash and other
            # escaped literals; Python's unicode_escape has different semantics.
            decoded.append(controls.get(escaped, escaped))
    # Java strings use UTF-16. Combine a pair of escaped surrogate code units
    # while retaining an unpaired code unit if the original property had one.
    return ''.join(decoded).encode('utf-16-le', errors='surrogatepass').decode('utf-16-le', errors='surrogatepass')


def parse_java_properties(text):
    """Read jcmd's Properties output in memory; callers store only safe fields."""
    result, continued = {}, ''
    for physical in text.splitlines():
        line = continued + (physical.lstrip(' \t\f') if continued else physical)
        trailing = len(line) - len(line.rstrip('\\'))
        if trailing % 2:
            continued = line[:-1]
            continue
        continued = ''
        line = line.lstrip(' \t\f')
        if not line or line.startswith(('#', '!')):
            continue
        escaped, split = False, len(line)
        for index, char in enumerate(line):
            if not escaped and (char in '=:' or char in ' \t\f'):
                split = index
                break
            if char == '\\':
                escaped = not escaped
            else:
                escaped = False
        start = split
        if start < len(line) and line[start] in ' \t\f':
            while start < len(line) and line[start] in ' \t\f':
                start += 1
        if start < len(line) and line[start] in '=:':
            start += 1
        while start < len(line) and line[start] in ' \t\f':
            start += 1
        result[unescape_java_property(line[:split])] = unescape_java_property(line[start:])
    if continued:
        raise ValueError('Incomplete continued JVM property')
    return result


def init(args):
    runtime = runtime_path(args.runtime)
    require_stopped(runtime)
    base = inside(args.base)
    if base.exists():
        raise FileExistsError(f'Refusing to overwrite benchmark baseline: {base}')
    main, harness, fp = [Path(p).resolve() for p in (args.main, args.harness, args.firstperson)]
    assert sha(main) == MAIN_SHA, 'Production jar differs from installed 1.2.4'
    with zipfile.ZipFile(harness) as z:
        assert not any(n.startswith('dev/caleb/emfattachments/client/') or n.startswith('dev/caleb/emfattachments/mixin/') for n in z.namelist()), 'Perf jar must not bundle production helpers/mixins'
        assert not any(n.startswith('dev/caleb/emfattachments/test/') for n in z.namelist()), 'Full old developer harness is prohibited'
        assert not any(n.endswith('.jar') for n in z.namelist()), 'Perf jar must not add nested dependencies'
    world = inside(runtime / 'saves/AttachmentTest')
    assert (world / 'level.dat').is_file(), 'Prepared disposable AttachmentTest world required'
    storage = dict(worldBytes=tree_bytes(world), configBytes=tree_bytes(runtime / 'config'),
                   artifactBytes=sum(p.stat().st_size for p in (main, harness, fp)))
    storage.update(require_space(runtime, (storage['worldBytes'] + storage['configBytes']) * 9 + storage['artifactBytes'] * 3))
    storage['meaning'] = 'Conservative initial estimate for one baseline plus eight preserved pass worlds/configs; later cache growth is checked again before each pass.'
    base.mkdir(parents=True)
    for key, source in (('main', main), ('harness', harness), ('firstperson', fp)):
        directory = base / 'artifacts' / key
        directory.mkdir(parents=True)
        name = source.name.removesuffix('.disabled')
        shutil.copy2(source, directory / name)
    retired = base / 'retired-initial-mods'
    retired.mkdir()
    for p in (runtime / 'mods').iterdir():
        if p.is_file() and (p.name.startswith(('emf-attachments-compat-', 'firstperson-', 'emfattachments-perf-')) or p.name.startswith('fpm_x_compat-')):
            target = inside(retired / p.name)
            p.rename(target)
    original = base / 'original-settings'
    original.mkdir()
    shutil.copy2(runtime / 'options.txt', original / 'options.txt')
    watut = runtime / 'config/watut-common.toml'
    shutil.copy2(watut, original / 'watut-common.toml')
    text = watut.read_text(encoding='utf-8-sig')
    text, count = re.subn(r'(?m)^(\s*ticksToMarkPlayerIdle\s*=\s*)\d+\s*$', r'\g<1>120000', text)
    assert count == 1, 'Expected exactly one WATUT idle threshold'
    watut.write_text(text, encoding='utf-8')
    dynamic = runtime / 'config/dynamic_fps.json'
    shutil.copy2(dynamic, original / dynamic.name)
    data = load(dynamic)
    data['enabled'] = False
    save(dynamic, data)
    emf = load(runtime / 'config/entity_model_features.json')
    assert emf.get('preventFirstPersonHandAnimating') is False, 'Match the enabled user EMF hand animation'
    source_options = (runtime / 'options.txt').read_text(encoding='utf-8-sig')
    lines = [line for line in source_options.splitlines() if line.split(':', 1)[0] not in SETTINGS]
    (runtime / 'options.txt').write_text('\n'.join(lines + [f'{k}:{v}' for k, v in SETTINGS.items()]) + '\n', encoding='utf-8')
    shutil.copy2(runtime / 'options.txt', base / 'baseline-options.txt')
    shutil.copytree(runtime / 'config', base / 'baseline-config')
    shutil.copytree(world, base / 'baseline-world')
    frozen_artifacts = {key: artifact(next((base / 'artifacts' / key).iterdir())) for key in ('main', 'harness', 'firstperson')}
    shared_mods = {p.name: sha(p) for p in sorted((runtime / 'mods').glob('*.jar'))}
    config_hashes = tree_hashes(base / 'baseline-config')
    plan = dict(status='INITIALIZED_NOT_EXECUTED', createdUtc=utc(), runtime=str(runtime), base=str(base),
                order=ORDER, modes={k: dict(compatInstalled=c, firstPersonInstalled=f) for k, (c, f) in MODES.items()},
                passes=[dict(passNumber=i, mode=mode, label=f'equipped-pass{i}-{mode.lower()}-r1') for i, mode in enumerate(ORDER, 1)],
                artifacts=frozen_artifacts, sharedModSha256=shared_mods, baselineConfigSha256=config_hashes,
                baselineWorldSha256=tree_hashes(base / 'baseline-world'), optionsSha256=sha(base / 'baseline-options.txt'),
                settings=SETTINGS, warmupSeconds=10, firstSceneWarmupSeconds=30, measureSeconds=20, storageEstimate=storage,
                fovFileSemantics='Minecraft normalized fov:0.5 corresponds to the requested native 90 degrees; verify actual renderSettings.fov in every report.',
                benchmarkOnlyChanges={'watut-common.toml': {'ticksToMarkPlayerIdle': 120000}, 'dynamic_fps.json': {'enabled': False}},
                constraints=['One owned game JVM at a time; fresh JVM and exact world/config/options snapshot per pass.',
                             'Our production jar is physically absent in B/D; no disable flag substitute.',
                             'Same minimal standalone perf harness in every mode; old developer harness absent.',
                             'No profiler or concurrent computational analysis during frame sampling.',
                             'All scenes use native cached skin/accessory/render paths; client clones omit multiplayer network and skin-download work.',
                             'Occluded-deck scene may intentionally have unequal rendering work; report its cost as practical behavior, not pure hook overhead.'])
    save(base / 'plan.json', plan)
    save(OUT / 'execution-plan.json', plan)
    print(json.dumps({'initialized': str(base / 'plan.json'), 'runtime': str(runtime), 'launched': False}))


def prepare_pass(args):
    base = inside(args.base)
    plan = load(base / 'plan.json')
    runtime = runtime_path(plan['runtime'])
    require_stopped(runtime)
    if not 1 <= args.pass_number <= len(ORDER) or not 1 <= args.attempt <= 99:
        raise ValueError('Pass must be 1..8; attempt 1..99')
    number, mode = args.pass_number, ORDER[args.pass_number - 1]
    label = f'equipped-pass{number}-{mode.lower()}-r{args.attempt}'
    archive = inside(base / 'pass-history' / label)
    if archive.exists() or (runtime / 'emfattachments-tests' / label).exists():
        raise FileExistsError('Pass label/archive already exists; use a new attempt')
    space = require_space(runtime, tree_bytes(base / 'baseline-world') + tree_bytes(base / 'baseline-config') + 8 * 1024 * 1024)
    archive.mkdir(parents=True)
    for name, source in (('world', runtime / 'saves/AttachmentTest'), ('config', runtime / 'config')):
        source, target = inside(source), inside(archive / name)
        if source.exists():
            source.rename(target)
    shutil.copytree(base / 'baseline-world', runtime / 'saves/AttachmentTest')
    shutil.copytree(base / 'baseline-config', runtime / 'config')
    shutil.copy2(base / 'baseline-options.txt', runtime / 'options.txt')
    for p in (runtime / 'mods').iterdir():
        if p.is_file() and p.name.startswith(('emf-attachments-compat-', 'firstperson-', 'emfattachments-perf-', 'fpm_x_compat-')):
            p.rename(inside(archive / p.name))
    compat, firstperson = MODES[mode]
    for key in ('harness',) + (('main',) if compat else ()) + (('firstperson',) if firstperson else ()):
        item = plan['artifacts'][key]
        assert sha(item['path']) == item['sha256'], f'Frozen {key} changed'
        shutil.copy2(item['path'], runtime / 'mods' / item['name'])
    triggers = [p for p in runtime.glob('emfattachments-*.txt') if p.name != TRIGGER]
    if triggers:
        raise RuntimeError('Unconsumed other harness trigger(s): ' + ', '.join(p.name for p in triggers))
    (runtime / TRIGGER).write_text(f'{label}\nwarmupSeconds=10\nmeasureSeconds=20\n', encoding='utf-8')
    manifest = audit_runtime(plan, mode)
    manifest.update(passNumber=number, attempt=args.attempt, mode=mode, label=label, preparedUtc=utc(), launched=False, spacePreflight=space)
    assert manifest['valid'], manifest['violations']
    save(OUT / f'{label}-manifest.json', manifest)
    print(json.dumps({'prepared': label, 'mode': mode, 'compatInstalled': compat, 'firstPersonInstalled': firstperson, 'launched': False}))


def audit_runtime(plan, mode):
    runtime = runtime_path(plan['runtime'])
    compat, firstperson = MODES[mode]
    jars = {p.name: sha(p) for p in sorted((runtime / 'mods').glob('*.jar'))}
    expected = dict(plan['sharedModSha256'])
    for key in ('harness',) + (('main',) if compat else ()) + (('firstperson',) if firstperson else ()):
        item = plan['artifacts'][key]
        expected[item['name']] = item['sha256']
    violations = []
    if jars != expected:
        violations.append('Active jar inventory/hash differs from frozen pass mode')
    configs = tree_hashes(runtime / 'config')
    if configs != plan['baselineConfigSha256']:
        violations.append('Config bytes differ from frozen pre-launch baseline')
    options = properties(runtime / 'options.txt')
    if sha(runtime / 'options.txt') != plan['optionsSha256']:
        violations.append('Options bytes differ from frozen pre-launch baseline')
    if tree_hashes(runtime / 'saves/AttachmentTest') != plan['baselineWorldSha256']:
        violations.append('World differs from exact cold-start snapshot')
    return dict(valid=not violations, violations=violations, runtime=str(runtime), activeModSha256=jars,
                configSha256=configs, optionsSha256=sha(runtime / 'options.txt'), observedSettings={k: options.get(k) for k in SETTINGS},
                compatPhysicallyInstalled=compat, firstPersonPhysicallyInstalled=firstperson,
                actualNativeModList='Must be confirmed separately by the running fixture report')


def process_audit(args):
    plan = load(inside(args.base) / 'plan.json')
    runtime = runtime_path(plan['runtime'])
    pid = int(load(runtime / 'process.json')['pid'])
    if not running(pid):
        raise RuntimeError('Owned benchmark JVM is not running')
    result = subprocess.run([str(args.jcmd), str(pid), 'VM.system_properties'], capture_output=True, text=True, check=True)
    props = parse_java_properties(result.stdout)
    command = props.get('sun.java.command', '')
    assert Path(props.get('user.dir', '')).resolve() == runtime
    assert '--username AttachmentTest' in command and '--userType legacy' in command and '--accessToken 0' in command, 'Not the owned offline benchmark JVM'
    assert props.get('emfattachments.disable') != 'true', 'A mixin-disable substitute is prohibited'
    record = dict(pid=pid, observedUtc=utc(), offlineIdentityConfirmed=True, userDir=str(runtime),
                  productionDisableFlagAbsent=True, launchArgsSha256=sha(runtime / 'launch.args'),
                  sensitiveLaunchArgumentsStored=False)
    save(OUT / f'{args.label}-process.json', record)
    print(json.dumps(record))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', type=Path, default=BASE)
    sub = parser.add_subparsers(dest='command', required=True)
    p = sub.add_parser('init')
    p.add_argument('--runtime', type=Path, default=ROOT / '.local/without-firstperson/.local/client')
    p.add_argument('--main', type=Path, default=ROOT / 'build/libs/emf-attachments-compat-1.21.1-1.2.4.jar')
    p.add_argument('--harness', type=Path, default=ROOT / 'build/perf-libs/emfattachments-perf-1.21.1.jar')
    p.add_argument('--firstperson', type=Path, default=ROOT / '.local/client/mods/firstperson-neoforge-2.7.3-mc1.21.1.jar')
    p = sub.add_parser('prepare-pass')
    p.add_argument('pass_number', type=int)
    p.add_argument('--attempt', type=int, default=1)
    p = sub.add_parser('audit')
    p.add_argument('--mode', choices=MODES, required=True)
    p = sub.add_parser('process-audit')
    p.add_argument('--label', required=True)
    p.add_argument('--jcmd', type=Path, default=Path('C:/Program Files/Java/jdk-21/bin/jcmd.exe'))
    args = parser.parse_args()
    if args.command == 'init':
        init(args)
    elif args.command == 'prepare-pass':
        prepare_pass(args)
    elif args.command == 'audit':
        print(json.dumps(audit_runtime(load(inside(args.base) / 'plan.json'), args.mode), indent=2))
    else:
        if not re.fullmatch('[A-Za-z0-9._-]+', args.label):
            raise ValueError('Simple label required')
        process_audit(args)


if __name__ == '__main__':
    main()
