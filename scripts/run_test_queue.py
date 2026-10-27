"""Run explicit developer-harness triggers sequentially in one disposable client."""
import argparse
import json
import re
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('queue', type=Path)
parser.add_argument('--runtime', type=Path, default=ROOT / '.local/client')
args = parser.parse_args()
runtime = args.runtime.resolve()
runtime.relative_to((ROOT / '.local').resolve())
assert runtime.parts[-2:] == ('.local', 'client')
queue = json.loads(args.queue.read_text(encoding='utf-8'))
lock = runtime / 'emfattachments-queue.lock'
results = []

def read_report(path):
    try:
        return json.loads(path.read_text(encoding='utf-8'))
    except (OSError, json.JSONDecodeError):
        return None

with lock.open('x', encoding='utf-8') as handle:
    handle.write(str(args.queue.resolve()))
try:
    for case in queue:
        label = case['label']
        assert re.fullmatch(r'[a-zA-Z0-9._-]+', label)
        assert re.fullmatch(r'emfattachments-[a-z-]+\.txt', case['trigger'])
        assert '/' not in case['report'] and '\\' not in case['report']
        report = runtime / 'emfattachments-tests' / label / case['report']
        if report.exists():
            raise RuntimeError(f'Refusing to overwrite existing evidence: {label}')
        pending = list(runtime.glob('emfattachments-*.txt'))
        if pending:
            raise RuntimeError(f'Other trigger pending: {[p.name for p in pending]}')
        subprocess.run([sys.executable, str(ROOT / 'scripts/record_runtime_evidence.py'),
                        label, '--runtime', str(runtime)], check=True, stdout=subprocess.DEVNULL)
        payload = label + '\n' + case.get('options', '')
        trigger = runtime / case['trigger']
        temporary = trigger.with_suffix('.queue-pending')
        temporary.write_text(payload, encoding='utf-8')
        temporary.replace(trigger)
        print(f'START {label}', flush=True)
        start = last_update = time.monotonic()
        saw_report = False
        while True:
            time.sleep(3)
            data = read_report(report)
            if time.monotonic() - start > case.get('timeoutSeconds', 10800):
                raise TimeoutError(label)
            if data is None:
                if not saw_report and time.monotonic() - start > case.get('startupTimeoutSeconds', 300):
                    raise TimeoutError(f'No report started for {label}; inspect the isolated client and pending trigger')
                continue
            saw_report = True
            status = data.get('status', '')
            terminal = data.get('finished') is True or status in {
                'PASS', 'FAIL', 'CAPTURED', 'ABORTED', 'CATALOGUED'}
            if terminal and status not in {'FAIL', 'ABORTED'} and any(data.get(key) is not True for key in case.get('waitFor', [])):
                terminal = False
            if terminal:
                failed = status in {'FAIL', 'ABORTED'}
                for key in ('failures', 'violations', 'renderExceptions', 'poseMutationCases',
                            'nonFiniteGeometryCases', 'gogglesAnchorFailures', 'torsoAnchorFailures',
                            'flowerAnchorFailures', 'flowerColorFailures', 'dyeTintFailures'):
                    failed |= bool(data.get(key))
                failed |= data.get('stateRestored') is False
                failed |= data.get('plannedCases') == 0 or data.get('caseCount') == 0
                for row in data.get('cases', []):
                    failed |= row.get('status', '') in {'CASE_FAILURE', 'RENDER_FAILURE', 'ACTION_FAILURE', 'ACTION_UNCONFIRMED', 'FAIL', 'UNMET_PRECONDITION'}
                    failed |= bool(row.get('finalizationError'))
                result = dict(label=label, status=status or 'FINISHED', accepted=not failed,
                              elapsedSeconds=round(time.monotonic()-start, 1))
                results.append(result)
                print(json.dumps(result), flush=True)
                args.queue.with_suffix('.results.json').write_text(json.dumps(results, indent=2))
                if failed:
                    raise RuntimeError(f'Inspect failed test before continuing: {label}')
                break
            if time.monotonic() - last_update >= 30:
                progress = data.get('completedCases', len(data.get('results', data.get('captures', [])))
                                    if isinstance(data.get('results', data.get('captures', [])), list) else data.get('captures'))
                print(f'RUNNING {label}: {progress}', flush=True)
                last_update = time.monotonic()
finally:
    lock.unlink(missing_ok=True)
