"""Bind the 1.2.4 installation to its final sources, tests and reviewed game captures."""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import zipfile

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'verification/sable'


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def read(path):
    return json.loads(Path(path).read_text(encoding='utf-8-sig'))


def checked(path, digest):
    path = Path(path).resolve()
    assert path.is_relative_to(ROOT), path
    assert sha(path) == digest.lower(), path
    return path


build = read(ROOT / 'verification/build-1.2.4.json')
before = read(OUT / 'before-change.json')
instance = Path(before['instance'])
jar = checked(ROOT / 'build/libs' / build['productionJar'], build['sha256'])
assert build['tests'] == 80 and build['failures'] == 0
assert all(int(s['errors']) == int(s['skipped']) == 0 for s in build['suites'])
for name, digest in build['sourceHashes'].items():
    checked(ROOT / name, digest)
checked(ROOT / 'build.gradle', build['buildGradleSha256'])
with zipfile.ZipFile(jar) as archive:
    assert [n for n in archive.namelist() if n.endswith('.jar')] == build['bundledDependencies']
    assert not any(n.startswith('dev/caleb/emfattachments/test/') for n in archive.namelist())

reviews, images, frames, cull_checks = {}, {}, 0, 0
for mode in ('no-fpm', 'fpm'):
    animation_path = OUT / f'final-{mode}-animation-review.json'
    animation = read(animation_path)
    report_path = checked(animation['reportPath'], animation['reportSha256'])
    checked(animation['manifestPath'], animation['manifestSha256'])
    report = read(report_path)
    assert animation['animationReview']['result'] == 'PASS'
    assert report['status'] == 'CAPTURED'
    for key in ('platformRemovedFromClient', 'remoteRemoved', 'inputsRestored', 'cameraRestored', 'serverStateRestored'):
        assert report[key], (mode, key)
    assert report['firstPersonInstalled'] == (mode == 'fpm')
    for stage in report['stages']:
        assert stage['frames'] == stage['localRenderedFrames'] == stage['remoteRenderedFrames']
        frames += stage['frames']
    for stage in ('translate-descend-no-input', 'rock-no-input'):
        for kind in ('local', 'remote-client-clone'):
            rows = [r for r in report['renderSamples'] if r['stage'] == stage and r['kind'] == kind and r['stageTick'] > 5]
            assert len(rows) > 150
            assert all(r['onGround'] and not r['crouching'] and not r['shift'] and r['pose'] == 'STANDING' for r in rows)
            assert all(r['emfVariables']['var.land'] == 0 for r in rows)
    assert any(r['kind'] == 'local' and r['stage'] == 'intentional-crouch-release' and r['crouching'] for r in report['renderSamples'])
    assert any(r['kind'] == 'local' and r['stage'] == 'intentional-jump-land' and not r['onGround'] for r in report['renderSamples'])
    visual_path = OUT / f'final-{mode}-visual-review.json'
    visual = read(visual_path)
    checked(visual['report'], visual['reportSha256'])
    checked(visual['manifest'], visual['manifestSha256'])
    assert len(visual['images']) == visual['reviewedOriginalImageCount'] == 18
    for row in visual['images']:
        image = checked(row['file'], row['sha256'])
        assert row['reviewedOriginal'] and row['localVisible'] and row['remoteVisible']
        images[str(image)] = row['sha256']
    occlusion_path = OUT / f'final-{mode}-occlusion-review.json'
    occlusion = read(occlusion_path)
    source = checked(occlusion['sourceReport'], occlusion['sourceSha256'])
    assert occlusion['status'] == 'PASS_CONTROLLED_PATH_ASSERTIONS'
    assert len(occlusion['checks']) == 106 and all(r['passed'] for r in occlusion['checks'])
    assert len(occlusion['images']) == 8
    cull_checks += len(occlusion['checks'])
    for row in occlusion['images']:
        image = checked(source.parent / row['file'], row['sha256'])
        images[str(image)] = row['sha256']
    for path in (animation_path, visual_path, occlusion_path):
        reviews[str(path.relative_to(ROOT)).replace('\\', '/')] = sha(path)

assert len(images) == 52
assert sha(instance / 'mods' / jar.name) == sha(jar)
assert [p.name for p in (instance / 'mods').glob('emf-attachments-compat-*.jar*')] == [jar.name]
assert not list((instance / 'mods').glob('fpm_x_compat-*'))
assert not list((instance / 'mods').glob('firstperson-*.jar'))
for name, digest in before['configHashes'].items():
    assert sha(instance / name) == digest, name
assert sha(instance / 'options.txt') == before['optionsSha256']
for name, digest in before['resourcepacks'].items():
    assert sha(instance / 'resourcepacks' / name) == digest, name
for name, digest in before['mods'].items():
    if not name.startswith('emf-attachments-compat-'):
        assert sha(instance / 'mods' / name) == digest, name
for runtime in (ROOT / '.local/client', ROOT / '.local/without-firstperson/.local/client'):
    assert sha(runtime / 'mods' / jar.name) == sha(jar)

environment = dict(recordedAtUtc=datetime.now(timezone.utc).isoformat(), minecraft='1.21.1', neoforge='21.1.264',
                   mainJar=jar.name, mainSha256=sha(jar), installedEqualsTested=True,
                   replacedCompanionAbsent=True, developerHarnessAbsent=True,
                   firstPersonEnabled=False, firstPersonActivationPreserved=True,
                   userConfigFilesUnchanged=len(before['configHashes']), userOptionsUnchanged=True,
                   allOtherModFilesUnchanged=True, resourcePacksUnchanged=True)
(ROOT / 'verification/environment.json').write_text(json.dumps(environment, indent=2) + '\n')
result = dict(status='PASS', productionJar=jar.name, sha256=sha(jar), unitTests=build['tests'],
              sourceFilesMatched=len(build['sourceHashes']), nativeAnimationFrames=frames,
              controlledOcclusionAssertions=cull_checks, finalReviewedOriginalImages=len(images),
              reviewHashes=reviews, imageHashes=images, installation=environment,
              limitations=['Remote players are client clones, not a second networked client.',
                           'Occlusion failure is deliberately injected, not naturally reproduced on the user server.',
                           'Animation camera is third-person front in both FirstPerson installation modes.',
                           'No FPS improvement or zero-overhead claim.'])
(OUT / 'final-integrity.json').write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps({k: v for k, v in result.items() if k not in ('reviewHashes', 'imageHashes', 'installation')}))
