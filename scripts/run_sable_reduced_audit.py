"""Owned offline Sable audit. The supplied CurseForge pack is read-only.

Copies use independent files, never hard links. Each pass restores a disposable
world and configuration; replaced runtime state is retained for inspection.
"""
from pathlib import Path
import argparse
import datetime
import hashlib
import json
import re
import shutil
import sys

import test_runtime
import prepare_equipped_benchmark as common

ROOT = Path(__file__).resolve().parents[1]
SOURCE = Path('C:/Users/caleb/curseforge/minecraft/Instances/Modded Minecraft').resolve()
BASE = ROOT / '.local/sable-reduced-tick-20261112'
RUNTIME = BASE / '.local/client'
WORLD = ROOT / '.local/equipped-benchmark-v2/baseline-world'
FOLDERS = ('mods', 'config', 'resourcepacks', 'shaderpacks', 'defaultconfigs', 'kubejs')


def inside(path):
    path = Path(path).resolve()
    if not path.is_relative_to(BASE.resolve()) or path == BASE.resolve() or path.is_relative_to(SOURCE):
        raise ValueError(f'Write outside the disposable audit: {path}')
    return path


def save(path, data):
    path = inside(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')


def source_snapshot():
    result = {}
    for folder in FOLDERS:
        for path in sorted((SOURCE / folder).rglob('*')):
            if path.is_file():
                result[str(path.relative_to(SOURCE)).replace('\\', '/')] = common.sha(path)
    result['options.txt'] = common.sha(SOURCE / 'options.txt')
    return result


def init():
    if (BASE / 'source-before.json').exists():
        assert common.load(BASE / 'source-before.json') == source_snapshot(), 'Source pack changed externally'
        return
    assert (WORLD / 'level.dat').is_file(), 'Use only the existing disposable world'
    BASE.mkdir(parents=True, exist_ok=True)
    save(BASE / 'source-before.json', source_snapshot())
    RUNTIME.mkdir(parents=True)
    for folder in FOLDERS:
        origin = SOURCE / folder
        if not origin.exists():
            continue
        destination = inside(RUNTIME / folder)
        shutil.copytree(origin, destination)
    shutil.copytree(WORLD, inside(BASE / 'baseline-world'))
    shutil.copytree(SOURCE / 'config', inside(BASE / 'baseline-config'))
    shutil.copy2(SOURCE / 'options.txt', inside(BASE / 'baseline-options.txt'))
    (RUNTIME / 'natives').mkdir()
    print(json.dumps({'initialized': str(RUNTIME), 'sourceFilesHashed': len(source_snapshot())}))


def prepare(args):
    init()
    common.require_stopped(RUNTIME)
    assert re.fullmatch(r'[A-Za-z0-9_.-]+', args.label) and args.label not in ('.', '..')
    archive = inside(BASE / 'passes' / args.label)
    if archive.exists():
        raise FileExistsError(archive)
    archive.mkdir(parents=True)
    for folder in ('config', 'shaderpacks', 'saves/AttachmentTest', 'logs', 'crash-reports', '.mixin.out'):
        path = inside(RUNTIME / folder)
        if path.exists():
            target = inside(archive / 'before' / folder)
            target.parent.mkdir(parents=True, exist_ok=True)
            path.rename(target)
    for name in ('options.txt', 'console.log', 'console-error.log', 'launch.args', 'process.json'):
        path = inside(RUNTIME / name)
        if path.exists():
            target = inside(archive / 'before' / name)
            target.parent.mkdir(parents=True, exist_ok=True)
            path.rename(target)
    for path in list(RUNTIME.glob('emfattachments-*.txt')):
        target = inside(archive / 'before' / path.name)
        target.parent.mkdir(parents=True, exist_ok=True)
        inside(path).rename(target)
    shutil.copytree(BASE / 'baseline-config', inside(RUNTIME / 'config'))
    shutil.copytree(SOURCE / 'shaderpacks', inside(RUNTIME / 'shaderpacks'))
    shutil.copytree(BASE / 'baseline-world', inside(RUNTIME / 'saves/AttachmentTest'))
    # Reconcile active mods from the immutable read-only manifest, then select
    # the production artifact and optional developer-only harness explicitly.
    expected = {Path(n).name: digest for n, digest in common.load(BASE / 'source-before.json').items()
                if n.startswith('mods/') and n.endswith('.jar')
                and not Path(n).name.startswith(('emf-attachments-compat-', 'fpm_x_compat-'))
                and not (args.without_ragrevival and Path(n).name.lower().startswith('ragrevival'))}
    for path in list((RUNTIME / 'mods').iterdir()):
        if path.is_file() and (path.name not in expected or common.sha(path) != expected[path.name]):
            target = inside(archive / 'before/mods' / path.name)
            target.parent.mkdir(parents=True, exist_ok=True)
            inside(path).rename(target)
    for name, digest in expected.items():
        path = inside(RUNTIME / 'mods' / name)
        if not path.exists():
            shutil.copy2(SOURCE / 'mods' / name, path)
        assert common.sha(path) == digest
    for artifact in (args.production, args.harness):
        if artifact:
            shutil.copy2(artifact, inside(RUNTIME / 'mods' / artifact.name))
    settings = dict(common.SETTINGS)
    settings.update(onboardAccessibility='false')
    if args.kind != 'perf':
        settings.update(fullscreen='false', overrideWidth='1100', overrideHeight='760', maxFps='120',
                        renderDistance='6', simulationDistance='6', guiScale='2')
    lines = [line for line in (BASE / 'baseline-options.txt').read_text(encoding='utf-8-sig').splitlines()
             if line.split(':', 1)[0] not in settings]
    inside(RUNTIME / 'options.txt').write_text('\n'.join(lines + [f'{k}:{v}' for k, v in settings.items()]) + '\n', encoding='utf-8')
    dynamic = inside(RUNTIME / 'config/dynamic_fps.json')
    if dynamic.exists():
        data = common.load(dynamic); data['enabled'] = False; save(dynamic, data)
    watut = inside(RUNTIME / 'config/watut-common.toml')
    if watut.exists():
        watut.write_text(re.sub(r'(?m)^(\s*ticksToMarkPlayerIdle\s*=\s*)\d+\s*$',
                               r'\g<1>120000', watut.read_text(encoding='utf-8-sig')), encoding='utf-8')
    record = {'label': args.label, 'kind': args.kind, 'runtime': str(RUNTIME),
              'preparedUtc': common.utc(), 'withoutRagRevival': args.without_ragrevival,
              'mods': {p.name: common.sha(p) for p in (RUNTIME / 'mods').iterdir() if p.is_file()},
              'settings': settings, 'jvm': args.jvm, 'sourceUnchanged': source_snapshot() == common.load(BASE / 'source-before.json')}
    assert record['sourceUnchanged']
    save(archive / 'manifest.json', record)
    save(BASE / 'active.json', record)
    print(json.dumps({'prepared': args.label, 'mods': len(record['mods']), 'sourceUnchanged': True}))


def launch():
    common.require_stopped(RUNTIME)
    record = common.load(BASE / 'active.json')
    assert source_snapshot() == common.load(BASE / 'source-before.json')
    test_runtime.RUNTIME = RUNTIME
    test_runtime.launch(False, False, record['jvm'])
    record.update(common.load(RUNTIME / 'process.json'))
    save(BASE / 'active.json', record)
    save(BASE / 'passes' / record['label'] / 'manifest.json', record)


def collect():
    common.require_stopped(RUNTIME)
    record = common.load(BASE / 'active.json')
    target = inside(BASE / 'passes' / record['label'] / 'result')
    if target.exists():
        raise FileExistsError(target)
    target.mkdir()
    for folder in ('logs', 'crash-reports', '.mixin.out', 'emfattachments-tests'):
        if (RUNTIME / folder).exists():
            shutil.copytree(RUNTIME / folder, inside(target / folder))
    for name in ('console.log', 'console-error.log', 'options.txt'):
        if (RUNTIME / name).exists():
            shutil.copy2(RUNTIME / name, inside(target / name))
    record['sourceUnchanged'] = source_snapshot() == common.load(BASE / 'source-before.json')
    assert record['sourceUnchanged'], 'Source pack changed externally during this pass'
    record['collectedUtc'] = common.utc()
    save(BASE / 'passes' / record['label'] / 'manifest.json', record)
    print(json.dumps({'collected': str(target), 'sourceUnchanged': True}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    sub.add_parser('init'); sub.add_parser('launch'); sub.add_parser('collect')
    p = sub.add_parser('prepare')
    p.add_argument('--label', required=True)
    p.add_argument('--kind', choices=('startup', 'functional', 'perf'), default='functional')
    p.add_argument('--production', type=Path, required=True)
    p.add_argument('--harness', type=Path)
    p.add_argument('--without-ragrevival', action='store_true')
    p.add_argument('--jvm', action='append', default=[])
    args = parser.parse_args()
    if args.command == 'prepare': prepare(args)
    elif args.command == 'init': init()
    elif args.command == 'launch': launch()
    elif args.command == 'collect': collect()
