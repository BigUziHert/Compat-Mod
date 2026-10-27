"""Read-only exact-case reconciliation of missed active melee screenshots.

Original gaps are always retained. A nominal active image is evidence only when
active.attackActive is true and the real native attack completed. Vanilla hits
are intentionally excluded. --inspect-partial can report progress, never PASS.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import json
from pathlib import Path
import re

from audit_final_combat_union import ROOT, RESTORE, CASE_RESTORE, read, digest, key, describe


def sha_argument(value):
    if not re.fullmatch(r"[a-fA-F0-9]{64}", value):
        raise argparse.ArgumentTypeError("Expected a complete SHA256")
    return value.lower()


def shot_evidence(path, row):
    shot = row.get("active", {})
    name = shot.get("screenshot")
    image_path = path.parent / name if isinstance(name, str) and Path(name).name == name else None
    exists = bool(image_path and image_path.is_file())
    return dict(activeAttackActive=shot.get("attackActive"), activeTick=shot.get("tick"),
                source=str(image_path) if image_path else None, exists=exists,
                sha256=digest(image_path) if exists else None)


def case_errors(row, require_active=False):
    errors = []
    if row.get("status") != "CAPTURED":
        errors.append("case is not CAPTURED")
    if row.get("rendererViolations") != 0 or row.get("finalizationError"):
        errors.append("renderer/finalization failure")
    if row.get("activeLayerObserved") is not True or row.get("firstInactiveTick", -1) < 0:
        errors.append("complete native attack layer/cycle not proven")
    if not isinstance(row.get("frames"), (int, float)) or row["frames"] <= 0:
        errors.append("no measured render frames")
    for field in CASE_RESTORE:
        if row.get(field) is not True:
            errors.append("case cleanup not verified: " + field)
    for field in ("serverNativeWeaponStateVerified", "clientNativeWeaponStateVerified"):
        if row.get(field) is not True:
            errors.append("native weapon state not verified: " + field)
    if row.get("selectionVerifiedAtAction") != {"client": 0, "server": 0, "nativeSelectionPacketSent": True}:
        errors.append("canonical selected slot not verified")
    if require_active and row.get("active", {}).get("attackActive") is not True:
        errors.append("nominal active capture is not an actual active native attack")
    return errors


def run_evidence(path, args):
    data = read(path)
    label = data.get("label")
    errors = []
    manifest_path = ROOT / "verification/expanded-run-manifests" / (str(label) + ".json")
    try:
        manifest = read(manifest_path)
    except (OSError, ValueError) as error:
        manifest = {}
        errors.append("manifest unavailable: " + str(error))
    if data.get("firstPersonInstalled") is not True or manifest.get("firstpersonInstalled") is not True:
        errors.append("actual FirstPerson-present runtime not proven")
    if manifest.get("label") != label:
        errors.append("manifest/report label mismatch")
    if Path(manifest.get("runtime", "")).resolve() != path.resolve().parents[2]:
        errors.append("manifest does not describe this runtime path")
    if manifest.get("rejectedCompanionAbsent") is not True:
        errors.append("rejected companion absence not proven")
    artifacts = manifest.get("artifacts", {})
    production = {name: sha for name, sha in artifacts.items() if "test-harness" not in name}
    harness = {name: sha for name, sha in artifacts.items() if "test-harness" in name}
    if len(production) != 1 or set(production.values()) != {args.expected_production}:
        errors.append("wrong production hash")
    if len(harness) != 1 or set(harness.values()) != {args.expected_harness}:
        errors.append("wrong harness hash")
    for name, sha in artifacts.items():
        archive = manifest.get("archivedArtifacts", {}).get(name)
        archive_path = ROOT / archive if archive else None
        if not archive_path or not archive_path.is_file() or digest(archive_path) != sha:
            errors.append("archived artifact hash not verified: " + name)
    terminal_errors = []
    if data.get("status") != "CAPTURED":
        terminal_errors.append("full run is not terminal CAPTURED")
    for field in RESTORE:
        if data.get(field) is not True:
            terminal_errors.append("run restoration not verified: " + field)
    rows = data.get("cases", [])
    if len(rows) != data.get("plannedCases"):
        terminal_errors.append("completed row count differs from plannedCases")
    if any(row.get("status") != "CAPTURED" or row.get("rendererViolations") != 0 or row.get("finalizationError") for row in rows):
        terminal_errors.append("run contains incomplete/failed action or renderer rows")
    evidence = dict(label=label, report=str(path), reportSha256=digest(path),
                    manifest=str(manifest_path), manifestSha256=digest(manifest_path) if manifest_path.is_file() else None,
                    firstPersonInstalled=data.get("firstPersonInstalled"), status=data.get("status"), rows=len(rows),
                    provenanceErrors=errors, terminalAndRestorationErrors=terminal_errors)
    return data, evidence


def audit(args):
    full_runs, replay_runs, originals, issues = [], [], [], []
    original_keys = []
    for path in args.full_report:
        data, run = run_evidence(path, args)
        full_runs.append(run)
        issues.extend(dict(report=str(path), reason=reason) for reason in run["provenanceErrors"])
        if not args.inspect_partial:
            issues.extend(dict(report=str(path), reason=reason) for reason in run["terminalAndRestorationErrors"])
        for index, row in enumerate(data.get("cases", []), 1):
            try:
                row_key = key(row)
                original_keys.append(row_key)
            except KeyError as error:
                issues.append(dict(report=str(path), row=index, reason="canonical key absent: " + str(error)))
                continue
            if row.get("kind") != "melee" or row.get("status") is None:
                continue
            if not args.inspect_partial:
                issues.extend(dict(report=str(path), row=index, reason=reason) for reason in case_errors(row))
            shot = shot_evidence(path, row)
            if shot["activeAttackActive"] is True and shot["exists"]:
                continue
            originals.append(dict(key=describe(row_key), sourceReport=str(path), sourceRow=index,
                                  originalShot=shot, originalCaseErrors=case_errors(row),
                                  resolution="UNRESOLVED", acceptedReplay=None, replayCandidates=[]))
    plan = read(args.plan)["plan"]
    expected = Counter(key(row) for row in plan)
    actual = Counter(original_keys)
    if not args.inspect_partial and (len(original_keys) != 1431 or actual != expected):
        issues.append(dict(reason="Finished full reports are not the exact 1431-key canonical union",
                           observedRows=len(original_keys), uniqueKeys=len(actual)))
    lookup = defaultdict(list)
    for path in args.replay_report:
        data, run = run_evidence(path, args)
        replay_runs.append(run)
        run_errors = run["provenanceErrors"] + run["terminalAndRestorationErrors"]
        for index, row in enumerate(data.get("cases", []), 1):
            if row.get("kind") != "melee":
                continue
            try:
                row_key = key(row)
            except KeyError:
                continue
            shot = shot_evidence(path, row)
            errors = list(run_errors) + case_errors(row, require_active=True)
            if not shot["exists"]:
                errors.append("active original replay PNG missing")
            lookup[row_key].append(dict(report=str(path), row=index, label=data.get("label"),
                                       shot=shot, eligible=not errors, rejectionReasons=errors,
                                       firstInactiveTick=row.get("firstInactiveTick")))
    for gap in originals:
        candidates = lookup[key(gap["key"])]
        gap["replayCandidates"] = candidates
        accepted = next((row for row in candidates if row["eligible"]), None)
        if accepted:
            gap["resolution"] = "RESOLVED_BY_EXACT_CASE_REPLAY_ORIGINAL_GAP_RETAINED"
            gap["acceptedReplay"] = accepted
    unresolved = sum(gap["acceptedReplay"] is None for gap in originals)
    status = "INSPECTION_ONLY_PARTIAL_INPUTS" if args.inspect_partial else "FAIL_INVALID_FULL_INPUTS" if issues else "UNRESOLVED_ACTIVE_CAPTURE_GAPS" if unresolved else "ACTIVE_CAPTURE_GAPS_RECONCILED"
    return dict(status=status, originalGapCount=len(originals), resolvedGapCount=len(originals)-unresolved,
                unresolvedGapCount=unresolved, originalGaps=originals, fullRuns=full_runs, replayRuns=replay_runs,
                technicalIssues=issues, canonicalPlan=str(args.plan), canonicalPlanSha256=digest(args.plan),
                expectedProduction=args.expected_production, expectedHarness=args.expected_harness,
                finalAssertionGranted=not args.inspect_partial and not issues and not unresolved,
                limits=["Original late/missing active screenshots remain gaps in the original run even after a successful replay.",
                        "Replays must match item/kind/attackIndex/pitch/context/awakening/crouch exactly; vanilla_hit is excluded.",
                        "Actual FirstPerson presence is verified from report and manifest, never inferred from runtime folder names.",
                        "No visual alignment, unobstructed-view or gameplay-effect certification is made; PNG existence and active native state are separate from manual review.",
                        "Final assertion requires the finished exact 1431-case full union, source provenance, and run restoration. Partial inspection can never pass."])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--full-report", action="append", type=Path, required=True)
    parser.add_argument("--replay-report", action="append", type=Path, default=[])
    parser.add_argument("--expected-production", type=sha_argument, required=True)
    parser.add_argument("--expected-harness", type=sha_argument, required=True)
    parser.add_argument("--plan", type=Path, default=ROOT / "verification/expanded/canonical1431-reference-plan.json")
    parser.add_argument("--inspect-partial", action="store_true")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = audit(args)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({field:result[field] for field in ("status", "originalGapCount", "resolvedGapCount", "unresolvedGapCount", "finalAssertionGranted")}, indent=2))
    raise SystemExit(3 if args.inspect_partial else 1 if result["technicalIssues"] else 2 if result["unresolvedGapCount"] else 0)


if __name__ == "__main__":
    main()
