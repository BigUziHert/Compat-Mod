"""Run OURS/NOWHEEL/NOWHEEL/OURS, with no neither/both/FirstPerson mode.

The root operator supplies native foreground focus. The native fixture enforces
camera/settings/focus; valid observed behavior failures are retained, not retried.
"""
from pathlib import Path
from types import SimpleNamespace
import argparse
import json
import re
import shutil
import subprocess
import time

import prepare_nowheel_comparison as preparation
import test_runtime

_PROGRESS_CACHE = {}

def emit(value):
    print(json.dumps(value), flush=True)


def gpu_snapshot():
    result = subprocess.run(['C:/Windows/System32/nvidia-smi.exe',
        '--query-gpu=name,driver_version,pstate,temperature.gpu,utilization.gpu,memory.used,power.draw,clocks.current.graphics',
        '--format=csv,noheader'], capture_output=True, text=True, timeout=15)
    return dict(observedUtc=preparation.utc(), exitCode=result.returncode, csv=result.stdout.strip())


def progress_tail(path):
    """Bounded metadata reads:64KiB prefix +2MiB tail, cached by mtime/size.

    Gson's two-space top-level anchors distinguish session completion from the
    nested probe's finished flag. Timed stage arrays can precede session status;
    the fixed-size untimed probe subtree currently fits within the2MiB tail.
    Missing metadata stays incomplete until the runner's bounded timeout.
    """
    if not path.exists():
        return {}
    try:
        stamp = path.stat()
        key, signature = str(path.resolve()), (stamp.st_mtime_ns, stamp.st_size)
        if key in _PROGRESS_CACHE and _PROGRESS_CACHE[key][0] == signature:
            return dict(_PROGRESS_CACHE[key][1])
        with path.open('rb') as stream:
            prefix = stream.read(65536)
            stream.seek(0, 2)
            stream.seek(max(0, stream.tell() - 2097152))
            tail = stream.read(2097152)
        result = dict(finished=False)
        found_finished = False
        for block in (prefix, tail):
            if not found_finished:
                match = re.search(rb'(?m)^  "finished"\s*:\s*(true|false)', block)
                if match:
                    found_finished = True
                    result['finished'] = match[1] == b'true'
            for field in ('activeScene', 'phase', 'status'):
                if field not in result:
                    match = re.search(('(?m)^  "' + field + '"\\s*:\\s*"([^"\\r\\n]*)"').encode(), block)
                    if match:
                        result[field] = match[1].decode('utf-8')
        if not found_finished or 'status' not in result:
            result['metadataIncomplete'] = True
            # Never promote a nested/partial completion to session completion.
            result['finished'] = False
        _PROGRESS_CACHE[key] = (signature, dict(result))
        return result
    except OSError:
        return {}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', type=Path, default=preparation.BASE)
    parser.add_argument('--probe-only', action='store_true', help='Run the separate OURS-only untimed probe smoke')
    parser.add_argument('--start-pass', type=int, default=1)
    parser.add_argument('--end-pass', type=int)
    parser.add_argument('--max-attempts', type=int, default=2)
    args = parser.parse_args()
    if args.probe_only and args.base == preparation.BASE:
        args.base = preparation.SMOKE_BASE
    base = preparation.inside(args.base)
    active_plan = preparation.plan_path(base)
    plan = preparation.load(active_plan)
    OUT = preparation.evidence_directory(plan)
    probe_only = plan.get('probeOnly') is True
    if args.probe_only:
        assert probe_only, 'Requested smoke needs its independent smoke plan'
    if args.end_pass is None:
        args.end_pass = len(plan['order'])
    if probe_only:
        inspect = preparation.inspect_probe
    else:
        import analyze_nowheel_comparison as analysis
        inspect = analysis.inspect_run
    runtime = preparation.runtime_path(plan['runtime'])
    assert 1 <= args.start_pass <= args.end_pass <= len(plan['order'])
    assert 1 <= args.max_attempts <= 3
    preparation.require_stopped(runtime)
    execution = dict(status='RUNNING', startedUtc=preparation.utc(), passes=[],
                     planSha256=preparation.sha(active_plan), testedModes=plan['order'], probeOnly=probe_only,
                     requestedPassRange=[args.start_pass, args.end_pass],
                     firstPersonTests=False, neitherOrBothTests=False,
                     monitoring='JVM identity before trigger; lightweight progress only during native timing/probes; shader/statistics inspection after JVM exit.')
    progress_path = OUT / 'matrix-progress.json'
    if progress_path.exists():
        prior = preparation.load(progress_path)
        execution['priorProgressSha256'] = preparation.sha(progress_path)
        if prior.get('planSha256') == execution['planSha256']:
            execution['passes'] = prior.get('passes', [])
        else:
            preserved = OUT / 'revisions' / plan['labelPrefix'] / 'matrix-progress-before-new-revision.json'
            preserved.parent.mkdir(parents=True, exist_ok=True)
            if preserved.exists():
                assert preparation.sha(preserved) == preparation.sha(progress_path), 'Do not replace different prior progress evidence'
            else:
                shutil.copy2(progress_path, preserved)
            execution['preservedPriorProgressPath'] = str(preserved)
    preparation.save(progress_path, execution)
    for number in range(args.start_pass, args.end_pass + 1):
        accepted = False
        for attempt in range(1, args.max_attempts + 1):
            mode = plan['order'][number - 1]
            label = preparation.pass_label(plan, number, mode, attempt)
            manifest_path = OUT / f'{label}-manifest.json'
            archive = base / 'pass-history' / label
            if manifest_path.exists():
                previous = inspect(manifest_path)
                exclusions_path = OUT / 'exclusions.json'
                exclusions = preparation.load(exclusions_path) if exclusions_path.exists() else {}
                if previous['accepted'] and label not in exclusions:
                    emit(dict(event='ALREADY_ACCEPTED', label=label))
                    accepted = True
                    break
                continue
            if archive.exists() or (runtime / 'emfattachments-tests' / label).exists():
                emit(dict(event='CONSUMED_PREPARATION_ATTEMPT_SKIPPED', label=label))
                continue
            gpu_before = gpu_snapshot()
            preparation.prepare_pass(SimpleNamespace(base=base, pass_number=number, attempt=attempt))
            # Never call test_runtime.prepare; it would install other harnesses
            # and overwrite controlled config/options.
            test_runtime.RUNTIME = runtime
            test_runtime.launch(False, False)
            pid = int(preparation.load(runtime / 'process.json')['pid'])
            item = dict(label=label, passNumber=number, attempt=attempt, mode=mode, pid=pid,
                        launchedUtc=preparation.utc(), status='LAUNCHED_UNARMED', gpuBefore=gpu_before)
            execution['passes'].append(item)
            preparation.save(progress_path, execution)
            emit(dict(event='LAUNCHED_NEEDS_FOREGROUND', label=label, pid=pid, triggerArmed=False))
            time.sleep(5)
            preparation.process_audit(SimpleNamespace(base=base, label=label,
                jcmd=Path('C:/Program Files/Java/jdk-21/bin/jcmd.exe')))
            preparation.arm_trigger(base, label)
            item.update(status='NATIVE_TRIGGER_ARMED', triggerArmedUtc=preparation.utc())
            preparation.save(progress_path, execution)
            emit(dict(event='NATIVE_TRIGGER_ARMED', label=label))
            source = runtime / 'emfattachments-tests' / label / 'equipped-crowd-frames.json'
            began, observed, captured = time.monotonic(), None, None
            while time.monotonic() - began < 900:
                if not preparation.running(pid):
                    raise RuntimeError(f'Owned comparison JVM exited before final capture: {label}')
                progress = progress_tail(source)
                if progress != observed:
                    observed = progress
                    item.update(nativeProgress=progress, elapsedSeconds=round(time.monotonic() - began, 1))
                    preparation.save(progress_path, execution)
                    emit(dict(event='NATIVE_PROGRESS', label=label, **progress))
                if progress.get('finished'):
                    try:
                        captured = preparation.load(source)
                        if captured.get('finished') is True:
                            break
                    except (OSError, json.JSONDecodeError):
                        pass
                time.sleep(5)
            if captured is None:
                raise RuntimeError(f'Bounded native comparison wait expired: {label}')
            item['gpuAfterSamplingAndUntimedProbes'] = gpu_snapshot()
            (runtime / 'emfattachments-perf-stop.txt').write_text('stop', encoding='utf-8')
            stop_at = time.monotonic()
            while preparation.running(pid) and time.monotonic() - stop_at < 90:
                time.sleep(1)
            if preparation.running(pid):
                raise RuntimeError(f'Owned comparison JVM did not stop gracefully: {label}, PID{pid}')
            item['shaderPostRunAuditValid'] = preparation.post_run_shader_audit(plan, label)['valid']
            for path, suffix in ((runtime / 'logs/latest.log', 'game.log'), (runtime / 'console-error.log', 'stderr.log')):
                if path.exists():
                    shutil.copy2(path, OUT / f'{label}-{suffix}')
            verdict = inspect(manifest_path)
            findings = verdict.get('behaviorFailures', [])
            behavior_pass = verdict.get('behaviorPass', not bool(findings))
            if verdict['accepted']:
                status = ('PROBE_CAPTURED_WITH_BEHAVIOR_FINDINGS' if findings or behavior_pass is False else 'PROBE_PREFLIGHT_ACCEPTED') if probe_only else ('ACCEPTED_WITH_BEHAVIOR_FINDINGS' if findings or behavior_pass is False else 'ACCEPTED_PENDING_VISUAL_REVIEW')
            else:
                status = 'REJECTED_TECHNICAL_FAILURE'
            item.update(status=status, finishedUtc=preparation.utc(), reportSha256=verdict.get('reportSha256'),
                        rejection=verdict.get('rejection'), behaviorPass=behavior_pass, behaviorFailures=findings,
                        matchedRenderWorkload=verdict.get('matchedRenderWorkload'))
            preparation.save(progress_path, execution)
            preparation.save(OUT / f'{label}-execution.json', item)
            emit(dict(event=status, label=label, rejection=item['rejection'], behaviorFailures=findings))
            if verdict['accepted']:
                accepted = True
                break
        if not accepted:
            execution['status'] = 'STOPPED_FOR_TECHNICAL_FAILURE'
            preparation.save(progress_path, execution)
            raise RuntimeError(f'No valid technical capture for comparison pass{number}; inspect evidence before retrying')
    complete = args.start_pass == 1 and args.end_pass == len(plan['order'])
    execution.update(status='UNTIMED_PROBE_PREFLIGHT_COMPLETE' if probe_only else 'CAPTURES_COMPLETE_PENDING_VISUAL_AND_BEHAVIOR_REVIEW' if complete else 'REQUESTED_PASS_RANGE_CAPTURED_PENDING_REVIEW', finishedUtc=preparation.utc())
    preparation.save(progress_path, execution)
    emit(dict(event=execution['status']))


if __name__ == '__main__':
    main()
