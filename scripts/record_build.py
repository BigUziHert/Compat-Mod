"""Record the built production artifact and the actual Gradle JUnit results."""
import hashlib
import json
import re
from pathlib import Path
import xml.etree.ElementTree as ET
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
VERSION = re.search(r"^version = '([^']+)'", (ROOT / 'build.gradle').read_text(), re.MULTILINE).group(1)
jar = ROOT / 'build/libs' / f'emf-attachments-compat-1.21.1-{VERSION}.jar'
digest = hashlib.sha256(jar.read_bytes()).hexdigest()
suites = []
for path in sorted((ROOT / 'build/test-results/test').glob('TEST-*.xml')):
    suite = ET.parse(path).getroot()
    suites.append({**suite.attrib, 'cases': [case.attrib['name'] for case in suite.findall('testcase')]})
assert suites, 'No JUnit results found'
tests = sum(int(suite['tests']) for suite in suites)
failures = sum(int(suite['failures']) + int(suite['errors']) for suite in suites)
assert failures == 0, f'{failures} test failures/errors'
with ZipFile(jar) as archive:
    dependencies = [name for name in archive.namelist() if name.startswith('META-INF/jarjar/') and name.endswith('.jar')]
record = dict(productionJar=jar.name, sha256=digest, suites=suites,
              tests=tests, failures=failures, bundledDependencies=dependencies)
record['sourceHashes'] = {
    str(path.relative_to(ROOT)).replace('\\', '/'): hashlib.sha256(path.read_bytes()).hexdigest()
    for path in sorted((ROOT / 'src').rglob('*')) if path.is_file()
}
record['buildGradleSha256'] = hashlib.sha256((ROOT / 'build.gradle').read_bytes()).hexdigest()
destination = ROOT / 'verification' / f'build-{VERSION}.json'
if destination.exists():
    previous = json.loads(destination.read_text())
    previous_archive = destination.with_name(f'build-{VERSION}-{previous["sha256"][:12]}.json')
    if previous['sha256'] != digest and not previous_archive.exists():
        previous_archive.write_text(json.dumps(previous, indent=2), encoding='utf-8')
destination.write_text(json.dumps(record, indent=2), encoding='utf-8')
print(json.dumps({key: value for key, value in record.items() if key not in ('suites', 'sourceHashes')}))
