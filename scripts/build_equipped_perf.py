"""Build the minimal standalone benchmark without linking/bundling our production classes."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
JAVA = Path('C:/Program Files/Java/jdk-21')
OUTPUT = ROOT / 'build/perfmod'
CLASSES = OUTPUT / 'classes'
JAR = ROOT / 'build/perf-libs/emfattachments-perf-1.21.1.jar'
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--release', type=Path, default=ROOT / 'build/libs/emf-attachments-compat-1.21.1-1.2.4.jar')
parser.add_argument('--release-sha', default='6aeec849c303a20a8cc100a44451963eca1fb46d47cd130a794a83c331dacff8')
parser.add_argument('--output', type=Path, default=ROOT / 'verification/performance-equipped/perf-build.json')
parser.add_argument('--jar-output', type=Path, default=JAR)
parser.add_argument('--first-person-api', type=Path,
                    help='Verified compile-only FirstPerson API jar; never copied into a game runtime')
parser.add_argument('--first-person-api-sha', default='03b8b32edb053156ebdf844311273dc95968238e345150ff63a44a65c5c73741')
options = parser.parse_args()
RELEASE = options.release.resolve()
JAR = options.jar_output.resolve()


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


release_hash = sha(RELEASE)
assert len(options.release_sha) == 64 and release_hash == options.release_sha
environment = dict(os.environ, JAVA_HOME=str(JAVA))
compile_only_api = None
gradle_arguments = [str(ROOT / 'gradlew.bat'), '--init-script', str(ROOT / 'tools/equipped-perf.init.gradle'),
                    'writeEquippedPerfClasspath', '--console=plain']
if options.first_person_api:
    api = options.first_person_api.resolve()
    assert sha(api) == options.first_person_api_sha, 'Compile-only FirstPerson API hash mismatch'
    with zipfile.ZipFile(api) as archive:
        assert 'dev/tr7zw/firstperson/api/FirstPersonAPI.class' in archive.namelist()
    compile_only_api = dict(path=str(api), sha256=sha(api), bytes=api.stat().st_size,
                            gameRuntimeInstallation=False, bundledInPerfJar=False)
    gradle_arguments.append('-PfirstPersonJar=' + str(api))
subprocess.run(gradle_arguments, cwd=ROOT, env=environment, check=True)
classpath = (OUTPUT / 'compile-classpath.txt').read_text().strip()
# The exporter predates explicit inputs. A cached export must never omit the
# supplied API: append its verified path independently of Gradle task caching.
if compile_only_api and str(api) not in classpath.split(os.pathsep):
    classpath += os.pathsep + str(api)
assert not any('emf-attachments-compat-' in Path(p).name for p in classpath.split(os.pathsep))
sources = sorted((ROOT / 'src/perfmod/java').rglob('*.java'))
assert sources, 'No benchmark sources'
if CLASSES.exists():
    assert CLASSES.resolve().is_relative_to((ROOT / 'build').resolve()) and CLASSES.resolve() != (ROOT / 'build').resolve()
    shutil.rmtree(CLASSES)
CLASSES.mkdir(parents=True)
arguments = ['-proc:none', '--release', '21', '-encoding', 'UTF-8', '-classpath', classpath, '-d', str(CLASSES)]
arguments += [str(p) for p in sources]
argfile = OUTPUT / 'javac.args'
argfile.write_text('\n'.join('"' + p.replace('\\', '/').replace('"', '\\"') + '"' for p in arguments), encoding='utf-8')
subprocess.run([str(JAVA / 'bin/javac.exe'), '@' + str(argfile)], cwd=ROOT, check=True)
resources = ROOT / 'src/perfmod/resources'
entries = {str(p.relative_to(CLASSES)).replace('\\', '/'): p.read_bytes() for p in CLASSES.rglob('*') if p.is_file()}
for p in resources.rglob('*'):
    if p.is_file():
        name = str(p.relative_to(resources)).replace('\\', '/')
        assert name not in entries
        entries[name] = p.read_bytes()
assert 'META-INF/neoforge.mods.toml' in entries
assert not any(n.startswith(('dev/caleb/emfattachments/client/', 'dev/caleb/emfattachments/mixin/',
                            'dev/caleb/emfattachments/test/', 'dev/tr7zw/firstperson/')) or n.endswith('.jar') for n in entries)
entries['META-INF/MANIFEST.MF'] = b'Manifest-Version: 1.0\r\n\r\n'
JAR.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(JAR, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
    for name, data in sorted(entries.items()):
        info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = 0o100644 << 16
        archive.writestr(info, data)
assert sha(RELEASE) == release_hash, 'Release artifact changed during benchmark build'
record = dict(artifact=str(JAR), sha256=sha(JAR), bytes=JAR.stat().st_size,
              releaseUnchangedSha256=release_hash, productionClassesOnCompileClasspath=False,
              firstPersonCompileOnlyApi=compile_only_api,
              compileClasspathEntries=len(classpath.split(os.pathsep)),
              sourceSha256={str(p.relative_to(ROOT)).replace('\\', '/'): sha(p)
                            for p in sources + [p for p in resources.rglob('*') if p.is_file()]},
              builderSha256=sha(Path(__file__)), initScriptSha256=sha(ROOT / 'tools/equipped-perf.init.gradle'))
destination = options.output.resolve()
destination.parent.mkdir(parents=True, exist_ok=True)
if destination.exists():
    previous = json.loads(destination.read_text())
    archive = destination.with_name('perf-build-' + previous['sha256'][:12] + '.json')
    if not archive.exists():
        shutil.copy2(destination, archive)
destination.write_text(json.dumps(record, indent=2) + '\n')
print(json.dumps({k: v for k, v in record.items() if k != 'sourceSha256'}))
