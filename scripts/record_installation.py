import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path

root = Path(__file__).resolve().parents[1]
instance = Path('C:/Users/caleb/curseforge/minecraft/Instances/Modded Minecraft')
mods = instance / 'mods'
out = root / 'verification'
version = '1.2.3'
name = f'emf-attachments-compat-1.21.1-{version}.jar'

def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(chunk)
    return value.hexdigest().upper()

main_hash = digest(root / 'build/libs' / name)
assert digest(mods / name) == main_hash, 'Installed artifact differs from built artifact'
assert digest(root / '.local/client/mods' / name) == main_hash, 'Tested artifact differs from installed artifact'
assert digest(root / '.local/without-firstperson/.local/client/mods' / name) == main_hash, 'Absent-FirstPerson tested artifact differs'
assert not list(mods.glob('fpm_x_compat-*')), 'Rejected companion remains in the mods directory'
assert [p.name for p in mods.glob('emf-attachments-compat-*.jar*')] == [name], 'Duplicate or test harness found'
assert not list((root / '.local/without-firstperson/.local/client/mods').glob('firstperson-*.jar')), 'Physical-absence runtime still contains active FirstPerson'
assert list((root / '.local/client/mods').glob('firstperson-*.jar')), 'FirstPerson-present runtime lacks FirstPerson'
configuration_snapshot = 'verification/onion-hand/before-change.json'
before = json.loads((root / configuration_snapshot).read_text(encoding='utf-8-sig'))
preserved_configs = before['configs']
firstperson_active = bool(list(mods.glob('firstperson-*.jar')))
assert firstperson_active == before['firstPersonActive'], 'FirstPerson activation changed during this fix'
for config_name, expected_hash in preserved_configs.items():
    assert digest(instance / 'config' / config_name) == expected_hash.upper(), f'User config changed: {config_name}'

previous = json.loads((out / 'environment.json').read_text())
if previous['mainJar'] != name:
    previous_version = previous['mainJar'].removeprefix('emf-attachments-compat-1.21.1-').removesuffix('.jar')
    archive = out / f'environment-{previous_version}.json'
    if not archive.exists():
        archive.write_text(json.dumps(previous, indent=2), encoding='utf-8')
data = {
    'recordedAtUtc': datetime.now(timezone.utc).isoformat(),
    'minecraft': '1.21.1', 'neoforge': '21.1.264', 'mainJar': name, 'mainSha256': main_hash,
    'installedEqualsTested': True,
    'replacedCompanionAbsent': True,
    'firstPersonEnabled': firstperson_active,
    'firstPersonActivationPreserved': True,
    'firstPersonConfigUnchanged': True,
    'firstPersonConfigSha256': preserved_configs['firstperson.json'].upper(),
    'configurationSnapshot': configuration_snapshot,
    'preservedConfigSha256': preserved_configs,
    'harnessInstalledInUserInstance': False,
    'testHarnessSha256': digest(root / 'build/libs' / f'emf-attachments-compat-1.21.1-{version}-test-harness.jar'),
    'mods': [{'name': p.name, 'sha256': digest(p)} for p in sorted(mods.glob('*.jar'))],
    'resourcePacks': [{'name': p.name, 'sha256': digest(p)} for p in sorted((instance / 'resourcepacks').glob('*.zip'))],
}
(out / 'environment.json').write_text(json.dumps(data, indent=2), encoding='utf-8')
print(json.dumps({k:v for k,v in data.items() if k not in ('mods', 'resourcePacks')}, indent=2))
