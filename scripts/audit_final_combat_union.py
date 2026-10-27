"""Read-only final FPM shard audit. Numeric coverage is never a visual/ability pass.

python scripts/audit_final_combat_union.py REPORT_A REPORT_B \
  --expected-production SHA256 --expected-harness SHA256 --output AUDIT.json

Exit 0: structural/action checks pass, no unresolved outcome strings. Exit 1:
technical failure. Exit 2: technical checks pass but gameplay outcomes need review.
The script never starts a client, changes a trigger, or modifies input evidence.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KEY_FIELDS = ("item", "kind", "attackIndex", "pitch", "context", "awakening", "crouch")
RESTORE = ("serverInventoryRestored", "clientSettingsRestored", "nativeClientSeatReleased",
           "nativeClientVisibilityRestored", "cleanupReactionGraceExpired",
           "selectionReconciledToOriginalServer", "serverSelectedRestored")
CASE_RESTORE = ("betweenCasePendingPoseDrained", "betweenCaseServerSessionReleased",
                "betweenCaseClientSeatReleased", "betweenCaseClientVisibilityRestored")


def read(path: Path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def digest(path: Path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def key(row):
    # Missing keys are a schema failure, never replaced with a guessed default.
    return tuple(row[field] for field in KEY_FIELDS)


def describe(value):
    return dict(zip(KEY_FIELDS, value))


def canonical_digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def audit(args):
    issues, unresolved, warnings, runs, all_keys = [], [], [], [], []

    def issue(scope, reason, **details):
        issues.append(dict(scope=scope, reason=reason, **details))

    plan_doc = read(args.plan)
    plan = plan_doc["plan"]
    expected_keys = [key(row) for row in plan]
    expected_ids = {row["item"] for row in plan if row["kind"] != "roll"}
    if len(plan) != 1431 or len(set(expected_keys)) != 1431 or len(expected_ids) != 184:
        raise ValueError("The canonical reference itself must have exactly1431 unique keys/184 combat IDs")
    catalogue_path = Path(plan_doc["sourceCatalogue"])
    if not catalogue_path.is_absolute():
        catalogue_path = ROOT / catalogue_path
    expected_catalogue = read(catalogue_path)["catalogue"]
    catalogue_hash = canonical_digest(expected_catalogue)
    if {row["item"] for row in expected_catalogue if row.get("combatItem")} != expected_ids:
        raise ValueError("Reference catalogue item set does not match reference case plan")

    for ordinal, (report_path, manifest_path, start, count) in enumerate((
            (args.report_a, args.manifest_a, 0, 720), (args.report_b, args.manifest_b, 720, 711))):
        data = read(report_path)
        label = data.get("label", "")
        scope = "A" if ordinal == 0 else "B"
        manifest_path = manifest_path or ROOT / "verification/expanded-run-manifests" / (label + ".json")
        manifest = read(manifest_path)
        rows = data.get("cases", [])
        actual_keys = []
        for index, row in enumerate(rows):
            try:
                actual_keys.append(key(row))
            except KeyError as error:
                issue(scope, "Case schema omits canonical key", row=index + 1, field=str(error))
        if data.get("status") != "CAPTURED":
            issue(scope, "Run is not terminal CAPTURED", status=data.get("status"))
        selection = data.get("selection", {})
        if selection != {"item": ".*", "kind": ".*", "start": start, "limit": count}:
            issue(scope, "Wrong shard selection", observed=selection, expectedStart=start, expectedLimit=count)
        for field, expected in (("unfilteredPlannedCases", 1431), ("matchingCasesBeforeRange", 1431),
                                ("plannedCases", count)):
            if data.get(field) != expected:
                issue(scope, "Wrong planned count", field=field, observed=data.get(field), expected=expected)
        if len(rows) != count:
            issue(scope, "Wrong completed row count", observed=len(rows), expected=count)
        if actual_keys != expected_keys[start:start + count]:
            issue(scope, "Ordered case keys differ from exact canonical shard")
        all_keys.extend(actual_keys)
        if canonical_digest(data.get("catalogue")) != catalogue_hash:
            issue(scope, "Native prepared catalogue differs from canonical reference")
        if data.get("firstPersonInstalled") is not True or manifest.get("firstpersonInstalled") is not True:
            issue(scope, "Final FPM union requires actual FirstPerson in both shards")
        if data.get("shrinkingArtifact") is not False:
            issue(scope, "Unexpected scale cross-product; canonical full matrix is normal scale")
        if manifest.get("label") != label:
            issue(scope, "Report and manifest labels differ")
        if Path(manifest.get("runtime", "")).resolve() != report_path.resolve().parents[2]:
            issue(scope, "Report path does not belong to the manifested runtime")
        if manifest.get("rejectedCompanionAbsent") is not True:
            issue(scope, "Rejected vendor companion is not proven absent")
        artifacts = manifest.get("artifacts", {})
        production = {name: sha for name, sha in artifacts.items() if "test-harness" not in name}
        harness = {name: sha for name, sha in artifacts.items() if "test-harness" in name}
        if len(production) != 1 or set(production.values()) != {args.expected_production.lower()}:
            issue(scope, "Production artifact is not the explicitly requested final hash", artifacts=production)
        if len(harness) != 1 or set(harness.values()) != {args.expected_harness.lower()}:
            issue(scope, "Harness artifact is not the explicitly requested final hash", artifacts=harness)
        for name, sha in artifacts.items():
            archived = manifest.get("archivedArtifacts", {}).get(name)
            archive_path = ROOT / archived if archived else None
            if archive_path is None or not archive_path.is_file() or digest(archive_path) != sha:
                issue(scope, "Archived run artifact missing or hash differs", artifact=name)
        for field in RESTORE:
            if data.get(field) is not True:
                issue(scope, "Restoration not verified", field=field, observed=data.get(field))
        if data.get("originalServerSelected") not in range(9):
            issue(scope, "Original authoritative selected slot absent/invalid")
        configs = manifest.get("renderConfigHashes", {})
        runs.append(dict(shard=scope, label=label, report=str(report_path), reportSha256=digest(report_path),
                         manifest=str(manifest_path), manifestSha256=digest(manifest_path), rows=len(rows),
                         selection=selection, catalogueSha256=canonical_digest(data.get("catalogue")),
                         renderConfigHashes=configs, statusCounts=dict(Counter(row.get("status") for row in rows))))
        capture_names = []
        for index, row in enumerate(rows):
            local = dict(shard=scope, row=index + 1, case=row.get("case"), item=row.get("item"))
            if row.get("status") != "CAPTURED":
                issue(scope, "Case action/render status failed or incomplete", **local, status=row.get("status"))
            if row.get("rendererViolations") != 0 or row.get("finalizationError"):
                issue(scope, "Renderer/finalization failure", **local,
                      rendererViolations=row.get("rendererViolations"), finalizationError=row.get("finalizationError"))
            if not isinstance(row.get("frames"), (int, float)) or row["frames"] <= 0:
                issue(scope, "No actual measured render frames", **local)
            if row.get("kind") == "melee" and row.get("activeLayerObserved") is not True:
                issue(scope, "Native attack layer not observed", **local)
            if row.get("kind") == "melee" and row.get("firstInactiveTick", -1) < 0:
                issue(scope, "Complete native attack/fade ending not observed", **local)
            if row.get("kind") == "roll" and row.get("rollObserved") is not True:
                issue(scope, "Native roll not observed", **local)
            if row.get("activeUsePoseObserved") is False:
                issue(scope, "Claimed active use capture missed native use pose", **local)
            for field in ("serverNativeWeaponStateVerified", "clientNativeWeaponStateVerified"):
                if row.get(field) is not True:
                    issue(scope, "Native prepared weapon state not verified", **local, field=field)
            selection_at_action = row.get("selectionVerifiedAtAction", {})
            if selection_at_action != {"client": 0, "server": 0, "nativeSelectionPacketSent": True}:
                issue(scope, "Canonical native slot not verified before action", **local, observed=selection_at_action)
            for field in CASE_RESTORE:
                if row.get(field) is not True:
                    issue(scope, "Between-case cleanup not verified", **local, field=field)
            for field in ("abilityOutcome", "hitOutcome"):
                if row.get(field):
                    unresolved.append(dict(**local, field=field, outcome=row[field],
                                           awakening=row.get("awakening"), abilityUnlocked=row.get("abilityUnlocked")))
            # Existence is evidence completeness only. Never infer visual or gameplay success from pixels.
            for phase in ("active", "recovery"):
                shot = row.get(phase, {})
                filename = shot.get("screenshot")
                if isinstance(filename, str):
                    capture_names.append(filename)
                if not isinstance(filename, str) or Path(filename).name != filename or not (report_path.parent / filename).is_file():
                    issue(scope, "Required original capture missing/invalid", **local, phase=phase)
        if len(capture_names) != len(set(capture_names)):
            issue(scope, "Original capture filenames are reused across case phases")

    actual_counter = Counter(all_keys)
    missing = [describe(value) for value in expected_keys if actual_counter[value] == 0]
    extra = [describe(value) for value in actual_counter if value not in set(expected_keys)]
    duplicates = [dict(key=describe(value), count=count) for value, count in actual_counter.items() if count > 1]
    if missing or extra or duplicates or len(all_keys) != 1431:
        issue("union", "Final case union is not exactly the1431 canonical keys")
    actual_ids = {value[0] for value in all_keys if value[1] != "roll"}
    if actual_ids != expected_ids:
        issue("union", "Combat item ID union differs", missing=sorted(expected_ids - actual_ids), extra=sorted(actual_ids - expected_ids))
    if len(runs) == 2:
        for name in ("emf-attachments-client.toml", "firstperson.json"):
            values = [run["renderConfigHashes"].get(name) for run in runs]
            if not values[0] or values[0] != values[1]:
                issue("manifest", "Required rendering configuration hash missing/different", file=name, hashes=values)
        if runs[0]["renderConfigHashes"].get("iris.properties") != runs[1]["renderConfigHashes"].get("iris.properties"):
            warnings.append("Iris raw hashes differ; compare archived effective values because autogenerated timestamp comments also change the hash.")
    queue_checks = []
    for queue_path in args.queues:
        for entry in read(queue_path):
            if entry.get("trigger") not in {"emfattachments-expanded-combat.txt", "emfattachments-sequential-abilities.txt"}:
                continue
            missing_waits = sorted({"selectionReconciledToOriginalServer", "serverSelectedRestored"} - set(entry.get("waitFor", [])))
            queue_checks.append(dict(queue=str(queue_path), label=entry.get("label"), missingSelectionWaitFor=missing_waits))
            if missing_waits:
                issue("queue", "Selected-slot reconciliation not required", queue=str(queue_path), label=entry.get("label"))
    technical_pass = not issues
    return dict(status="TECHNICAL_FAIL" if issues else "TECHNICAL_PASS_OUTCOMES_REVIEW_REQUIRED" if unresolved else "TECHNICAL_PASS",
                technicalCoverageAndActionChecksPass=technical_pass, gameplayCertification="NOT_GRANTED_BY_THIS_SCRIPT",
                visualCertification="NOT_GRANTED_BY_THIS_SCRIPT", sourcePlan=str(args.plan), sourcePlanSha256=digest(args.plan),
                expectedCases=1431, expectedCombatItemIds=184, actualRows=len(all_keys), uniqueCaseKeys=len(actual_counter),
                actualCombatItemIds=len(actual_ids), missingKeys=missing, extraKeys=extra, duplicateKeys=duplicates,
                runs=runs, issueCount=len(issues), issues=issues, unresolvedOutcomeCount=len(unresolved),
                unresolvedOutcomes=unresolved, warnings=warnings, queueChecks=queue_checks,
                limits=["Generic serverEffectObserved/targetDamage can include prior lingering managers; they are not attributed ability proof.",
                        "Focused earned-charge/native defense reports and human review must resolve applicable outcome gaps separately.",
                        "This union is FPM-present only, not a second full physically-absent matrix or every scale/camera/state cross-product.",
                        "Recorder may omit native mod/config/resourcepack hashes; exact catalogue and artifact checks do not replace missing provenance."])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report_a", type=Path)
    parser.add_argument("report_b", type=Path)
    parser.add_argument("--manifest-a", type=Path)
    parser.add_argument("--manifest-b", type=Path)
    parser.add_argument("--plan", type=Path, default=ROOT / "verification/expanded/canonical1431-reference-plan.json")
    parser.add_argument("--expected-production", required=True)
    parser.add_argument("--expected-harness", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--queues", type=Path, nargs="*", default=[])
    args = parser.parse_args()
    for value in (args.expected_production, args.expected_harness):
        if len(value) != 64 or any(c not in "0123456789abcdefABCDEF" for c in value):
            parser.error("Expected artifact hashes must be complete SHA256 values")
    try:
        result = audit(args)
    except (OSError, ValueError, KeyError, TypeError) as error:
        result = dict(status="INPUT_OR_SCHEMA_FAILURE", technicalCoverageAndActionChecksPass=False,
                      gameplayCertification="NOT_GRANTED_BY_THIS_SCRIPT", visualCertification="NOT_GRANTED_BY_THIS_SCRIPT", error=str(error))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps({key: result.get(key) for key in ("status", "actualRows", "uniqueCaseKeys", "actualCombatItemIds", "issueCount", "unresolvedOutcomeCount")}))
    return 1 if not result["technicalCoverageAndActionChecksPass"] else 2 if result["unresolvedOutcomeCount"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
