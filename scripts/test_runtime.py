"""Prepare/run an isolated copy of the installed pack, using local launcher libraries.

No account credentials or existing saves are copied. Jars are copied, never edited.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import uuid

ROOT = Path(__file__).resolve().parents[1]
INSTANCE = Path('C:/Users/caleb/curseforge/minecraft/Instances/Modded Minecraft')
INSTALL = Path('C:/Users/caleb/curseforge/minecraft/Install')
RUNTIME = ROOT / '.local/client'
VERSION = 'neoforge-21.1.264'
MOD_VERSION = '1.2.13'


def sync_emf_hand_setting(source, target):
    """Refresh the one user-controlled hand-animation prerequisite in retained configs."""
    source, target = Path(source), Path(target)
    if source.resolve() == target.resolve():
        raise ValueError('EMF test configuration must differ from the user configuration')
    key = 'preventFirstPersonHandAnimating'
    user_options = json.loads(source.read_text(encoding='utf-8-sig'))
    if not isinstance(user_options, dict) or type(user_options.get(key)) is not bool:
        raise ValueError(f'Expected explicit boolean {key} in {source}')
    test_options = json.loads(target.read_text(encoding='utf-8-sig'))
    if not isinstance(test_options, dict):
        raise ValueError(f'Expected an EMF configuration object in {target}')
    if type(test_options.get(key)) is bool and test_options[key] == user_options[key]:
        return False
    # Preserve every other disposable setting, including generated metadata.
    # Do not infer the user's choice from an older copied file or native default.
    test_options[key] = user_options[key]
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', dir=target.parent,
                                         prefix=target.name + '.', suffix='.tmp', delete=False) as output:
            temporary = Path(output.name)
            output.write(json.dumps(test_options, indent=2) + '\n')
        temporary.replace(target)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
    return True


def prepare(firstperson=False):
    artifacts = [ROOT / 'build/libs' / f'emf-attachments-compat-1.21.1-{MOD_VERSION}{suffix}.jar'
                 for suffix in ('', '-test-harness')]
    for artifact in artifacts:
        if not artifact.is_file():
            raise FileNotFoundError(f'Build the current release first: {artifact}')
    RUNTIME.mkdir(parents=True, exist_ok=True)
    for folder in ('mods', 'config', 'resourcepacks', 'defaultconfigs', 'kubejs', 'shaderpacks', 'vista_ffmpeg_bin'):
        source = INSTANCE / folder
        target = RUNTIME / folder
        if source.exists():
            # Preserve the disposable instance's own generated configuration on
            # later runs (some shader metadata is deliberately read-only).
            def copy_missing(src, dst):
                if folder == 'mods' and (Path(src).name.startswith('fpm_x_compat-')
                                        or Path(src).name.startswith('emf-attachments-compat-')):
                    return str(dst)
                if not Path(dst).exists():
                    shutil.copy2(src, dst)
                return str(dst)
            shutil.copytree(source, target, dirs_exist_ok=True, copy_function=copy_missing)
    # Both the default and --runtime clients otherwise retain the very first
    # copied value indefinitely, invalidating later native-hand comparisons.
    sync_emf_hand_setting(INSTANCE / 'config/entity_model_features.json',
                          RUNTIME / 'config/entity_model_features.json')
    mods = (RUNTIME / 'mods').resolve()
    retired = ROOT / '.local/runtime-retired-mods'
    retired.mkdir(parents=True, exist_ok=True)
    for pattern in ('fpm_x_compat-*', 'emf-attachments-compat-*'):
        for jar in mods.glob(pattern):
            if jar.is_file() and jar.resolve().parent == mods:
                backup = retired / jar.name
                if not backup.exists():
                    shutil.copy2(jar, backup)
                jar.unlink()
    for disabled in mods.glob('firstperson-*.jar.disabled'):
        enabled = disabled.with_suffix('')
        if firstperson and not enabled.exists():
            shutil.copy2(disabled, enabled)
    if not firstperson:
        for enabled in mods.glob('firstperson-*.jar'):
            disabled = enabled.with_name(enabled.name + '.disabled')
            if not disabled.exists():
                shutil.copy2(enabled, disabled)
            enabled.unlink()
    if firstperson and not list(mods.glob('firstperson-*.jar')):
        raise FileNotFoundError('Verified FirstPerson jar is missing')
    for jar in artifacts:
        shutil.copy2(jar, mods / jar.name)
    if list(mods.glob('fpm_x_compat-*')):
        raise RuntimeError('The replaced companion must not be installed in the test client')
    options = (INSTANCE / 'options.txt').read_text(encoding='utf-8')
    overrides = dict(fullscreen='false', overrideWidth='1100', overrideHeight='760',
                     renderDistance='4', simulationDistance='5', maxFps='60',
                     guiScale='1', pauseOnLostFocus='false', onboardAccessibility='false',
                     soundCategory_master='0.0')
    lines = [line for line in options.splitlines() if line.split(':', 1)[0] not in overrides]
    lines.extend(f'{k}:{v}' for k, v in overrides.items())
    (RUNTIME / 'options.txt').write_text('\n'.join(lines) + '\n', encoding='utf-8')
    # Parallel test windows must retain foreground rendering cadence. Dynamic
    # FPS otherwise throttles the occluded test to one frame per second.
    fps_config = RUNTIME / 'config/dynamic_fps.json'
    if fps_config.exists():
        fps_options = json.loads(fps_config.read_text(encoding='utf-8-sig'))
        fps_options['enabled'] = False
        fps_config.write_text(json.dumps(fps_options, indent=2), encoding='utf-8')
    (RUNTIME / 'natives').mkdir(exist_ok=True)


def allowed(rules):
    if not rules:
        return True
    allow = False
    for rule in rules:
        osrule = rule.get('os', {})
        match = osrule.get('name', 'windows') == 'windows'
        match &= osrule.get('arch', 'x86_64') in ('x86_64', 'amd64')
        match &= not bool(rule.get('features'))
        if match:
            allow = rule['action'] == 'allow'
    return allow


def launch(baseline=False, auto_sweep=True, extra_jvm=()):
    vanilla = json.loads((INSTALL / 'versions/1.21.1/1.21.1.json').read_text())
    neo = json.loads((INSTALL / f'versions/{VERSION}/{VERSION}.json').read_text())
    libraries = {}
    for lib in vanilla['libraries'] + neo['libraries']:
        if not allowed(lib.get('rules')):
            continue
        if lib['name'].endswith((':natives-windows-arm64', ':natives-windows-x86')):
            continue
        parts = lib['name'].split(':')
        key = ':'.join(parts[:2] + parts[3:])
        path = INSTALL / 'libraries' / lib['downloads']['artifact']['path']
        if not path.exists():
            raise FileNotFoundError(path)
        libraries[key] = str(path)
    classpath = ';'.join(list(libraries.values()) + [str(INSTALL / 'versions/1.21.1/1.21.1.jar')])
    digest = bytearray(hashlib.md5(b'OfflinePlayer:AttachmentTest').digest())
    digest[6] = (digest[6] & 15) | 48
    digest[8] = (digest[8] & 63) | 128
    variables = dict(auth_player_name='AttachmentTest', version_name=VERSION,
                     game_directory=str(RUNTIME), assets_root=str(INSTALL / 'assets'),
                     assets_index_name=vanilla['assetIndex']['id'], auth_uuid=uuid.UUID(bytes=bytes(digest)).hex,
                     auth_access_token='0', clientid='local-development', auth_xuid='0',
                     user_type='legacy', version_type='release', natives_directory=str(RUNTIME / 'natives'),
                     launcher_name='emf-attachments-test', launcher_version='1', classpath=classpath,
                     library_directory=str(INSTALL / 'libraries'), classpath_separator=';')

    def expand(entries):
        result = []
        for entry in entries:
            if isinstance(entry, str):
                values = [entry]
            elif allowed(entry.get('rules')):
                values = entry['value'] if isinstance(entry['value'], list) else [entry['value']]
            else:
                continue
            for value in values:
                for key, replacement in variables.items():
                    value = value.replace('${' + key + '}', replacement)
                if '${' in value:
                    raise ValueError(value)
                if value.startswith('-DignoreList='):
                    value += ',1.21.1.jar'
                result.append(value)
        return result

    args = ['-Xms1G', '-Xmx8G', *extra_jvm]
    if auto_sweep:
        args.append('-Demfattachments.test=true')
    if baseline:
        args.append('-Demfattachments.disable=true')
    args += expand(vanilla['arguments']['jvm'] + neo['arguments']['jvm'])
    args += [neo['mainClass']]
    args += expand(vanilla['arguments']['game'] + neo['arguments']['game'])
    args += ['--width', '1100', '--height', '760']
    if (RUNTIME / 'saves/AttachmentTest/level.dat').exists():
        args += ['--quickPlaySingleplayer', 'AttachmentTest']
    argfile = RUNTIME / 'launch.args'
    argfile.write_text('\n'.join('"' + v.replace('\\', '/').replace('"', '\\"') + '"' for v in args), encoding='utf-8')
    java = INSTALL / 'runtime/java-runtime-delta/windows-x64/java-runtime-delta/bin/javaw.exe'
    with (RUNTIME / 'console.log').open('w') as out, (RUNTIME / 'console-error.log').open('w') as err:
        process = subprocess.Popen([str(java), '@' + str(argfile)], cwd=RUNTIME, stdout=out, stderr=err)
    (RUNTIME / 'process.json').write_text(json.dumps({'pid': process.pid}), encoding='utf-8')
    print(f'Isolated test client started: PID {process.pid}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--firstperson', action='store_true')
    parser.add_argument('--baseline', action='store_true')
    parser.add_argument('--prepare-only', action='store_true')
    parser.add_argument('--no-auto-sweep', action='store_true',
                        help='Start in the world and wait for an explicit test trigger')
    parser.add_argument('--runtime', type=Path,
                        help='Separate disposable directory under .local, ending in .local/client')
    args = parser.parse_args()
    if args.runtime:
        requested = args.runtime.resolve()
        requested.relative_to((ROOT / '.local').resolve())
        if requested.parts[-2:] != ('.local', 'client'):
            raise ValueError('Alternate runtime must end in .local/client for harness isolation checks')
        RUNTIME = requested
    prepare(args.firstperson)
    if not args.prepare_only:
        launch(args.baseline, not args.no_auto_sweep)
