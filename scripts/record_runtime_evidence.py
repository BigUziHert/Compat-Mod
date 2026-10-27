"""Record the actual jars in an isolated runtime alongside a uniquely named test run."""
import argparse
import datetime
import hashlib
import json
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('label')
parser.add_argument('--runtime', type=Path, default=ROOT / '.local/client')
args = parser.parse_args()
runtime = args.runtime.resolve()
runtime.relative_to((ROOT / '.local').resolve())
mods = runtime / 'mods'
paths = sorted(mods.glob('emf-attachments-compat-*.jar'))
assert len(paths) == 2, 'Expected exactly production and developer harness'
data = dict(label=args.label, recordedUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
            runtime=str(runtime), firstpersonInstalled=bool(list(mods.glob('firstperson-*.jar'))),
            rejectedCompanionAbsent=not bool(list(mods.glob('fpm_x_compat-*'))),
            artifacts={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in paths})
data['archivedArtifacts'] = {}
for path in paths:
    archive = ROOT / '.local/test-artifacts' / data['artifacts'][path.name] / path.name
    archive.parent.mkdir(parents=True, exist_ok=True)
    if not archive.exists():
        shutil.copy2(path, archive)
    assert hashlib.sha256(archive.read_bytes()).hexdigest() == data['artifacts'][path.name]
    data['archivedArtifacts'][path.name] = str(archive.relative_to(ROOT))
data['renderConfigHashes'] = {
    name: hashlib.sha256((runtime / 'config' / name).read_bytes()).hexdigest()
    for name in ('iris.properties', 'emf-attachments-client.toml', 'firstperson.json')
    if (runtime / 'config' / name).is_file()
}
target = ROOT / 'verification/expanded-run-manifests' / (args.label + '.json')
target.parent.mkdir(parents=True, exist_ok=True)
if target.exists():
    old = json.loads(target.read_text())
    if old.get('artifacts') != data['artifacts']:
        raise RuntimeError('Run label already records different artifacts; use a fresh label')
else:
    target.write_text(json.dumps(data, indent=2), encoding='utf-8')
print(json.dumps(data, indent=2))
