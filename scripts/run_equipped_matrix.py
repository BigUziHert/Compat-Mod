"""Run the finite eight-pass offline matrix; native window focus is handled separately."""
from pathlib import Path
from types import SimpleNamespace
import argparse
import contextlib
import datetime
import json
import re
import shutil
import subprocess
import time

import prepare_equipped_benchmark as preparation
import analyze_equipped_benchmark as analysis
import test_runtime

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'verification/performance-equipped'


def emit(value):
    print(json.dumps(value), flush=True)


def save(path, value):
    Path(path).write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')


def gpu_snapshot():
    result = subprocess.run(['C:/Windows/System32/nvidia-smi.exe',
        '--query-gpu=name,driver_version,pstate,temperature.gpu,utilization.gpu,memory.used,power.draw,clocks.current.graphics',
        '--format=csv,noheader'], capture_output=True, text=True, timeout=15)
    return dict(observedUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                exitCode=result.returncode, csv=result.stdout.strip())


def progress_tail(path):
    """Read status text only; never parse/sort the raw timing arrays during sampling."""
    if not path.exists():
        return {}
    try:
        with path.open('rb') as stream:
            stream.seek(0, 2)
            stream.seek(max(0, stream.tell() - 262144))
            tail = stream.read()
        result = {'finished': bool(re.search(rb'"finished"\s*:\s*true', tail))}
        for key in ('activeScene', 'phase', 'status'):
            matches = re.findall(('"' + key + '"\\s*:\\s*"([^"\\r\\n]*)"').encode(), tail)
            if matches:
                result[key] = matches[-1].decode('utf-8')
        return result
    except OSError:
        return {}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', type=Path, default=preparation.BASE)
    parser.add_argument('--start-pass', type=int, default=1)
    parser.add_argument('--end-pass', type=int, default=8)
    parser.add_argument('--max-attempts', type=int, default=2)
    args = parser.parse_args()
    base = preparation.inside(args.base)
    plan = preparation.load(base / 'plan.json')
    runtime = preparation.runtime_path(plan['runtime'])
    assert 1 <= args.start_pass <= args.end_pass <= 8
    assert 1 <= args.max_attempts <= 3
    preparation.require_stopped(runtime)
    execution = dict(startedUtc=preparation.utc(), status='RUNNING', passes=[],
                     monitoring='Only lightweight tail/status reads while measuring; full parsing/statistics and GPU snapshots after completion.')
    state_path = OUT / 'matrix-progress.json'
    save(state_path, execution)
    for number in range(args.start_pass, args.end_pass + 1):
        accepted = False
        for attempt in range(1, args.max_attempts + 1):
            mode = preparation.ORDER[number - 1]
            label = f'equipped-pass{number}-{mode.lower()}-r{attempt}'
            manifest_path = OUT / f'{label}-manifest.json'
            if manifest_path.exists():
                old = analysis.inspect_run(manifest_path)
                if old['accepted']:
                    emit(dict(event='ALREADY_ACCEPTED', label=label))
                    accepted = True
                    break
                continue
            gpu_before = gpu_snapshot()
            preparation.prepare_pass(SimpleNamespace(base=base, pass_number=number, attempt=attempt))
            test_runtime.RUNTIME = runtime
            test_runtime.launch(False, False)
            pid = int(preparation.load(runtime / 'process.json')['pid'])
            item = dict(passNumber=number, attempt=attempt, mode=mode, label=label, pid=pid,
                        launchedUtc=preparation.utc(), gpuBefore=gpu_before, status='LAUNCHED')
            execution['passes'].append(item)
            save(state_path, execution)
            emit(dict(event='LAUNCHED_NEEDS_FOREGROUND', label=label, pid=pid))
            report = runtime / 'emfattachments-tests' / label / 'equipped-crowd-frames.json'
            began = time.monotonic()
            audited = False
            observed = None
            captured = None
            while time.monotonic() - began < 900:
                if not preparation.running(pid):
                    raise RuntimeError(f'Owned JVM exited before final capture: {label}')
                if not audited and time.monotonic() - began >= 20:
                    preparation.process_audit(SimpleNamespace(base=base, label=label,
                        jcmd=Path('C:/Program Files/Java/jdk-21/bin/jcmd.exe')))
                    audited = True
                progress = progress_tail(report)
                if progress != observed:
                    observed = progress
                    item['nativeProgress'] = progress
                    item['elapsedSeconds'] = round(time.monotonic() - began, 1)
                    save(state_path, execution)
                    emit(dict(event='NATIVE_PROGRESS', label=label, **progress))
                if progress.get('finished'):
                    try:
                        captured = preparation.load(report)
                        if captured.get('finished') is True:
                            break
                    except (OSError, json.JSONDecodeError):
                        pass
                time.sleep(5)
            if captured is None:
                raise RuntimeError(f'Bounded wait expired; inspect owned client without modifying it: {label}')
            # Native sampling and restoration have finished. No GPU queries or
            # full report analysis occur until this point.
            item['gpuAfterSampling'] = gpu_snapshot()
            (runtime / 'emfattachments-perf-stop.txt').write_text('stop', encoding='utf-8')
            stop_at = time.monotonic()
            while preparation.running(pid) and time.monotonic() - stop_at < 90:
                time.sleep(1)
            if preparation.running(pid):
                raise RuntimeError(f'Owned JVM did not stop gracefully: {label}, PID {pid}')
            for source, suffix in ((runtime / 'logs/latest.log', 'game.log'),
                                   (runtime / 'console-error.log', 'stderr.log')):
                if source.exists():
                    shutil.copy2(source, OUT / f'{label}-{suffix}')
            verdict = analysis.inspect_run(manifest_path)
            item['status'] = 'ACCEPTED_PENDING_VISUAL_REVIEW' if verdict['accepted'] else 'REJECTED'
            item['finishedUtc'] = preparation.utc()
            item['reportSha256'] = verdict.get('reportSha256')
            item['rejection'] = verdict.get('rejection')
            save(state_path, execution)
            save(OUT / f'{label}-execution.json', item)
            emit(dict(event=item['status'], label=label, rejection=item['rejection']))
            if verdict['accepted']:
                accepted = True
                break
        if not accepted:
            execution['status'] = 'STOPPED_FOR_INVALID_PASS'
            save(state_path, execution)
            raise RuntimeError(f'No valid attempt for pass {number}; inspect evidence before retrying')
    execution['status'] = 'TIMINGS_COMPLETE_PENDING_VISUAL_REVIEW'
    execution['finishedUtc'] = preparation.utc()
    save(state_path, execution)
    emit(dict(event=execution['status']))


if __name__ == '__main__':
    main()
