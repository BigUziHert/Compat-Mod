"""Run Artifice checks in a fresh offline copy; the supplied instance is read-only.

Only mod jars are read from the user's instance. Configuration, packs and the
world come from the already disposable developer fixture. Every run gets its
own directory and immutable input hashes; never install a jar into the instance.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil

import test_runtime

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / '.local' / f'artifice-{test_runtime.MOD_VERSION}'
EVIDENCE = ROOT / 'verification' / f'artifice-{test_runtime.MOD_VERSION}'
FIXTURE = ROOT / '.local/client'
JAR_STEM = f'emf-attachments-compat-1.21.1-{test_runtime.MOD_VERSION}'


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')


def runtime(label):
    if not re.fullmatch(r'[a-zA-Z0-9_-]+', label):
        raise ValueError('Use a simple unique run label')
    result = BASE / label / '.local/client'
    result.resolve().relative_to((ROOT / '.local').resolve())
    return result


def prepare(args):
    target = runtime(args.label)
    if target.exists():
        raise FileExistsError('Use a new label: ' + str(target))
    production = args.jar.resolve()
    harness = ROOT / 'build/libs' / (JAR_STEM + '-test-harness.jar')
    if not production.is_file() or not harness.is_file():
        raise FileNotFoundError('Build production and testModJar before preparing')
    mods = test_runtime.INSTANCE / 'mods'
    before = {p.name: sha(p) for p in mods.iterdir() if p.is_file()}
    target.mkdir(parents=True)
    for folder in ('config', 'resourcepacks', 'shaderpacks', 'defaultconfigs', 'kubejs', 'vista_ffmpeg_bin'):
        if (FIXTURE / folder).is_dir():
            shutil.copytree(FIXTURE / folder, target / folder)
    shutil.copytree(FIXTURE / 'saves/AttachmentTest', target / 'saves/AttachmentTest',
                    ignore=shutil.ignore_patterns('session.lock'))
    (target / 'mods').mkdir()
    for p in mods.glob('*.jar'):
        if p.name.startswith(('emf-attachments-compat-', 'fpm_x_compat-')):
            continue
        if args.no_emf and p.name.startswith(('entity_model_features-', 'entity_texture_features-', 'emf_compat_')):
            continue
        if args.native_reference and p.name.startswith(('bettercombat-', 'notenoughanimations-', 'eatinganimation-', 'watut-', 'firstperson-')):
            continue
        shutil.copy2(p, target / 'mods' / p.name)
    for p in ((harness,) if args.native_reference else (production, harness)):
        shutil.copy2(p, target / 'mods' / p.name)
    if args.firstperson:
        if not args.firstperson.is_file():
            raise FileNotFoundError(args.firstperson)
        shutil.copy2(args.firstperson, target / 'mods' / args.firstperson.name)
    options = (FIXTURE / 'options.txt').read_text(encoding='utf-8-sig')
    overrides = dict(fullscreen='false', overrideWidth='1100', overrideHeight='760',
                     renderDistance='4', simulationDistance='5', maxFps='60', guiScale='1',
                     pauseOnLostFocus='false', soundCategory_master='0.0')
    if args.no_emf:
        overrides['resourcePacks'] = '["vanilla","mod_resources"]'
        overrides['incompatibleResourcePacks'] = '[]'
    lines = [v for v in options.splitlines() if v.split(':', 1)[0] not in overrides]
    lines += [f'{k}:{v}' for k, v in overrides.items()]
    (target / 'options.txt').write_text('\n'.join(lines) + '\n', encoding='utf-8')
    fps = target / 'config/dynamic_fps.json'
    if fps.exists():
        settings = json.loads(fps.read_text(encoding='utf-8-sig'))
        settings['enabled'] = False
        save(fps, settings)
    (target / 'natives').mkdir()
    save(EVIDENCE / args.label / 'inputs.json', dict(runtime=str(target), fixture=str(FIXTURE),
         userMods=before, runtimeMods={p.name: sha(p) for p in (target / 'mods').glob('*.jar')},
         production=str(production), productionSha256=sha(production), harnessSha256=sha(harness),
         emf=not args.no_emf, nativeReference=args.native_reference,
         firstPersonJar=str(args.firstperson) if args.firstperson else None,
         options=(target / 'options.txt').read_text(encoding='utf-8')))
    print(target)


def launch(args):
    test_runtime.RUNTIME = runtime(args.label)
    if not (test_runtime.RUNTIME / 'saves/AttachmentTest/level.dat').exists():
        raise FileNotFoundError('Prepare this run first')
    output = EVIDENCE / args.label
    if (output / 'launch-inputs.json').exists():
        raise FileExistsError('Use a fresh run label for each process')
    inputs = json.loads((output / 'inputs.json').read_text(encoding='utf-8'))
    production = Path(inputs['production'])
    harness = ROOT / 'build/libs' / (JAR_STEM + '-test-harness.jar')
    for p in ((harness,) if inputs.get('nativeReference') else (production, harness)):
        shutil.copy2(p, test_runtime.RUNTIME / 'mods' / p.name)
        archived = output / 'artifacts' / p.name
        archived.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(p, archived)
    save(output / 'launch-inputs.json', dict(productionSha256=None if inputs.get('nativeReference') else sha(production), harnessSha256=sha(harness),
         runtimeMods={p.name: sha(p) for p in (test_runtime.RUNTIME / 'mods').glob('*.jar')}))
    test_runtime.launch(auto_sweep=False, extra_jvm=['-Demfattachments.artificeOnly=true'])


def collect(args):
    target = runtime(args.label)
    output = EVIDENCE / args.label
    inputs = json.loads((output / 'inputs.json').read_text(encoding='utf-8'))
    mods = test_runtime.INSTANCE / 'mods'
    after = {p.name: sha(p) for p in mods.iterdir() if p.is_file()}
    save(output / 'user-mods-check.json', dict(unchanged=inputs['userMods'] == after, after=after))
    for folder in ('emfattachments-tests', 'logs', 'crash-reports'):
        if (target / folder).exists():
            shutil.copytree(target / folder, output / folder, dirs_exist_ok=True)
    for name in ('console.log', 'console-error.log'):
        if (target / name).exists():
            shutil.copy2(target / name, output / name)
    save(output / 'files.json', {str(p.relative_to(output)): sha(p) for p in output.rglob('*')
                               if p.is_file() and p.name != 'files.json'})
    print(output)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('prepare', 'launch', 'collect'))
    parser.add_argument('--label', required=True)
    parser.add_argument('--jar', type=Path,
                        default=ROOT / 'build/libs' / (JAR_STEM + '.jar'))
    parser.add_argument('--no-emf', action='store_true')
    parser.add_argument('--firstperson', type=Path, help='Copy this local FirstPerson jar into the disposable client only')
    parser.add_argument('--native-reference', action='store_true', help='Omit this compatibility jar and optional animation mods/packs; retain gun/roll dependencies')
    args = parser.parse_args()
    if args.native_reference:
        args.no_emf = True
    globals()[args.action](args)
