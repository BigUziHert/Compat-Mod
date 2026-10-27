"""Run six no-FirstPerson passes; the root operator handles native foreground focus.

Do not call test_runtime.prepare: it would alter the pinned mod/settings baseline.
The native fixture independently rejects lost focus and camera/settings drift.
"""
from pathlib import Path
from types import SimpleNamespace
import argparse
import json
import re
import shutil
import subprocess
import time

import prepare_sable_culling_benchmark as preparation
import analyze_sable_culling_benchmark as analysis
import test_runtime

OUT = preparation.OUT


def emit(value):
    print(json.dumps(value), flush=True)


def gpu_snapshot():
    result = subprocess.run(['C:/Windows/System32/nvidia-smi.exe',
        '--query-gpu=name,driver_version,pstate,temperature.gpu,utilization.gpu,memory.used,power.draw,clocks.current.graphics',
        '--format=csv,noheader'], capture_output=True, text=True, timeout=15)
    return dict(observedUtc=preparation.utc(), exitCode=result.returncode, csv=result.stdout.strip())


def progress_tail(path):
    """Lightweight status read only: no raw-array parsing/sorting during measurement."""
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
    parser.add_argument('--end-pass', type=int, default=6)
    parser.add_argument('--max-attempts', type=int, default=2)
    args = parser.parse_args()
    base = preparation.inside(args.base)
    active_plan = preparation.plan_path(base)
    plan = preparation.load(active_plan)
    runtime = preparation.runtime_path(plan['runtime'])
    assert 1 <= args.start_pass <= args.end_pass <= len(preparation.ORDER)
    assert 1 <= args.max_attempts <= 3
    preparation.require_stopped(runtime)
    execution = dict(startedUtc=preparation.utc(), status='RUNNING', passes=[], firstPersonTests=False,
                     planSha256=preparation.sha(active_plan), revisionId=plan.get('revisionId', 'revision-001'),
                     monitoring='Owned JVM audit before trigger; lightweight tail reads while measuring; full parsing and GPU snapshot after capture.')
    progress_path = OUT / 'matrix-progress.json'
    if progress_path.exists():
        prior = preparation.load(progress_path)
        execution['priorProgressSha256'] = preparation.sha(progress_path)
        if prior.get('planSha256') == execution['planSha256']:
            execution['passes'] = prior.get('passes', [])
    preparation.save(progress_path, execution)
    for number in range(args.start_pass, args.end_pass + 1):
        accepted = False
        for attempt in range(1, args.max_attempts + 1):
            mode = preparation.ORDER[number - 1]
            label = preparation.pass_label(plan, number, mode, attempt)
            manifest_path = OUT / f'{label}-manifest.json'
            if manifest_path.exists():
                old = analysis.inspect_run(manifest_path)
                exclusion_path = OUT / 'exclusions.json'
                exclusions = preparation.load(exclusion_path) if exclusion_path.exists() else {}
                if old['accepted'] and label not in exclusions:
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
                        launchedUtc=preparation.utc(), gpuBefore=gpu_before, status='LAUNCHED_UNARMED')
            execution['passes'].append(item)
            preparation.save(progress_path, execution)
            emit(dict(event='LAUNCHED_NEEDS_FOREGROUND', label=label, pid=pid, triggerArmed=False))
            # JVM inspection cannot overlap the benchmark: there is no native
            # benchmark trigger until the owned offline identity is established.
            time.sleep(5)
            preparation.process_audit(SimpleNamespace(base=base, label=label,
                jcmd=Path('C:/Program Files/Java/jdk-21/bin/jcmd.exe')))
            preparation.arm_trigger(base, label)
            item['triggerArmedUtc'] = preparation.utc()
            item['status'] = 'NATIVE_TRIGGER_ARMED'
            preparation.save(progress_path, execution)
            emit(dict(event='NATIVE_TRIGGER_ARMED', label=label))
            report = runtime / 'emfattachments-tests' / label / 'equipped-crowd-frames.json'
            began, observed, captured = time.monotonic(), None, None
            while time.monotonic() - began < 900:
                if not preparation.running(pid):
                    raise RuntimeError(f'Owned JVM exited before final capture: {label}')
                progress = progress_tail(report)
                if progress != observed:
                    observed = progress
                    item['nativeProgress'] = progress
                    item['elapsedSeconds'] = round(time.monotonic() - began, 1)
                    preparation.save(progress_path, execution)
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
                raise RuntimeError(f'Bounded wait expired; inspect owned client: {label}')
            item['gpuAfterSampling'] = gpu_snapshot()
            (runtime / 'emfattachments-perf-stop.txt').write_text('stop', encoding='utf-8')
            stop_at = time.monotonic()
            while preparation.running(pid) and time.monotonic() - stop_at < 90:
                time.sleep(1)
            if preparation.running(pid):
                raise RuntimeError(f'Owned JVM did not stop gracefully: {label}, PID{pid}')
            item['shaderPostRunAuditValid'] = preparation.post_run_shader_audit(plan, label)['valid']
            for source, suffix in ((runtime / 'logs/latest.log', 'game.log'),
                                   (runtime / 'console-error.log', 'stderr.log')):
                if source.exists():
                    shutil.copy2(source, OUT / f'{label}-{suffix}')
            verdict = analysis.inspect_run(manifest_path)
            item.update(status='ACCEPTED_PENDING_VISUAL_REVIEW' if verdict['accepted'] else 'REJECTED',
                        finishedUtc=preparation.utc(), reportSha256=verdict.get('reportSha256'),
                        rejection=verdict.get('rejection'))
            preparation.save(progress_path, execution)
            preparation.save(OUT / f'{label}-execution.json', item)
            emit(dict(event=item['status'], label=label, rejection=item['rejection']))
            if verdict['accepted']:
                accepted = True
                break
        if not accepted:
            execution['status'] = 'STOPPED_FOR_INVALID_PASS'
            preparation.save(progress_path, execution)
            raise RuntimeError(f'No valid attempt for pass{number}; inspect evidence before retrying')
    execution.update(status='TIMINGS_COMPLETE_PENDING_VISUAL_REVIEW', finishedUtc=preparation.utc())
    preparation.save(progress_path, execution)
    emit(dict(event=execution['status']))


if __name__ == '__main__':
    main()
