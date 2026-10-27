"""Reconcile the final release's raw unresolved outcomes without upgrading them.

Reads immutable reports, native source, manifests and manual reviews. Writes only
the selected audit artifact. A different focused fixture is never an exact-row
replay; expected native rejection is never an active-ability success.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PRODUCTION = "524b58479fdc925ed42544df61f9e3cea334f059c3bf6615685a81486b7d6ecd"
HARNESS = "23d51aa137befeaf5c3a07939a5c4cf8952ec6f4b980cb4bcc79488069d868f7"
SS = "research/expanded/simplyswords/net/sweenus/simplyswords/"
MANAGERS = "research/expanded/outcome-semantics-source/managers/net/sweenus/simplyswords/world/"
CATA = "research/expanded/cataclysm/com/github/L_Ender/cataclysm/items/"


def read(path):
    return json.loads((ROOT / path).read_text(encoding="utf-8-sig"))


def ref(path, **extra):
    path = Path(path)
    absolute = path if path.is_absolute() else ROOT / path
    return dict(path=absolute.relative_to(ROOT).as_posix(),
                sha256=hashlib.sha256(absolute.read_bytes()).hexdigest(), **extra)


def source(path, start, end, supports):
    return ref(path, lines=[start, end], supports=supports)


def compact(row):
    """Keep evidence readable; full event arrays remain in hashed raw reports."""
    if isinstance(row, dict):
        return {k: compact(v) for k, v in row.items()
                if k not in {"preEquipClient", "preparationTimeline", "nativeWorldReadiness",
                             "nativeEarnedHitGeometry", "nativePose", "presentationSamples",
                             "equipHeightTimeline", "active", "recovery"}}
    if isinstance(row, list):
        if len(row) > 6:
            return dict(count=len(row), first=[compact(v) for v in row[:3]],
                        last=[compact(v) for v in row[-2:]], fullArrayInHashedReport=True)
        return [compact(v) for v in row]
    return row


FOCUS_REVIEWS = {
    "shield-defense": "verification/expanded/shield-defense-native-release-review.json",
    "gauntlets": "verification/expanded/gauntlets-native-release-review.json",
    "bloodwake": "verification/expanded/focused-native-release/bloodwake-review.json",
    "relic-unattuned": "verification/expanded/focused-native-release/relic-unattuned-review.json",
    "ionbound": "verification/expanded/focused-native-release/ionbound-review.json",
    "molten-edge": "verification/expanded/molten-edge-fpm-release-r1-review.json",
    "soulrender": "verification/expanded/soulrender-fpm-release-r1-review.json",
    "soulkeeper": "verification/expanded/soulkeeper-fpm-release-r1-review.json",
    "stormbringer": "verification/expanded/stormbringer-native-release-review.json",
    "bulwark-melee": "verification/expanded/bulwark-melee-native-release-review.json",
    "magiblade": "verification/expanded/magiblade-fpm-release-r1-review.json",
}


def focused(name):
    label = f"expanded-fpm-{name}-native-release-r1"
    runtime = ".local/client" if name == "tempest" else ".local/without-firstperson/.local/client"
    filename = "shield-defense-report.json" if name == "shield-defense" else "expanded-combat-report.json" if name == "magiblade" else "sequential-ability-report.json"
    path = f"{runtime}/emfattachments-tests/{label}/{filename}"
    data = read(path)
    manifest_path = f"verification/expanded-run-manifests/{label}.json"
    manifest = read(manifest_path)
    assert manifest["firstpersonInstalled"] is True and manifest["rejectedCompanionAbsent"] is True
    assert data["firstPersonInstalled"] is True
    assert set(manifest["artifacts"].values()) == {PRODUCTION, HARNESS}
    assert data["status"] in {"CAPTURED", "PASS"}, (name, data["status"])
    assert all(row["status"] in {"PASS", "CAPTURED"} for row in data["cases"])
    fields = (["stateRestored", "inputRestored", "nativeClientSeatReleased"] if name == "shield-defense"
              else ["serverInventoryRestored", "clientSettingsRestored", "selectionReconciledToOriginalServer", "serverSelectedRestored"])
    assert all(data.get(field) is True for field in fields), (name, fields)
    if name not in {"shield-defense", "magiblade"}:
        assert data["temporarilyDisabledFirstPerson"] is False and data["originalFirstPersonEnabled"] is True
        assert all(row.get("rendererViolations") == 0 and row.get("nativePendingPoseDrained") is True
                   and row.get("nativeClientSeatReleased") is True and row.get("nativeClientVisibilityRestored") is True
                   for row in data["cases"])
    evidence = dict(report=ref(path), manifest=ref(manifest_path), runtimeFirstPersonPresent=True,
                    exactProductionAndHarness=True, status=data["status"], cases=len(data["cases"]),
                    restoration={field: data[field] for field in fields},
                    caseEvidence=[dict(index=i + 1, evidence=compact(row)) for i, row in enumerate(data["cases"])])
    review_path = FOCUS_REVIEWS.get(name)
    if name == "tempest":
        # Tempest finishes last; require its numeric report, but do not manufacture a visual review.
        candidates = sorted((ROOT / "verification/expanded").glob("*tempest*release*review.json"))
        if candidates:
            review_path = str(candidates[-1].relative_to(ROOT))
    if review_path and (ROOT / review_path).exists():
        review = read(review_path)
        recorded_hash = review.get("reportSha256", review.get("sourceSha256", review.get("sourceReportSha256")))
        if not recorded_hash and isinstance(review.get("sourceReport"), dict):
            recorded_hash = review["sourceReport"].get("sha256")
        assert recorded_hash == evidence["report"]["sha256"], (name, "manual review source changed")
        evidence["manualReview"] = ref(review_path)
        evidence["visualCertification"] = "See review's per-image limits; not inferred from numeric PASS."
    else:
        evidence["visualCertification"] = "NOT_REVIEWED_BY_THIS_RECONCILIATION"
    return data, evidence


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", default="verification/expanded/final-native-outcome-reconciliation.json")
    args = parser.parse_args()
    union_path = "verification/expanded/final-combat-union-audit.json"
    annotation_path = "verification/expanded/final-combat-native-outcome-interpretation.json"
    semantics_path = "verification/expanded/native-action-outcome-semantics.json"
    union, annotation, semantics = read(union_path), read(annotation_path), read(semantics_path)
    assert union["technicalCoverageAndActionChecksPass"] is True and union["actualRows"] == 1431
    annotated = {(run["label"], row["case"]): row for run in annotation["runs"] for row in run["rows"]}
    runs = {}
    for shard, runtime in [("A", ".local/client"), ("B", ".local/without-firstperson/.local/client")]:
        path = f"{runtime}/emfattachments-tests/expanded-fpm-combat-release-{shard.lower()}-r1/expanded-combat-report.json"
        data = read(path)
        assert data["status"] == "CAPTURED" and data["firstPersonInstalled"] is True
        expected = next(r for r in annotation["runs"] if r["label"] == data["label"])
        assert expected["sourceReportSha256"] == ref(path)["sha256"]
        runs[shard] = (data, ref(path))
    names = ["shield-defense", "gauntlets", "bloodwake", "relic-unattuned", "ionbound",
             "molten-edge", "soulrender", "tempest", "soulkeeper", "stormbringer", "bulwark-melee", "magiblade"]
    focus = {name: focused(name) for name in names}
    sources = {
        "shield": [source(CATA + "Azure_sea_Shield.java", 24, 36, "Inherits native ShieldItem use and shield-block ability."),
                   source(CATA + "Black_Steel_Targe.java", 24, 36, "Inherits native ShieldItem use and shield-block ability."),
                   source(CATA + "Bulwark_of_the_flame.java", 57, 98, "Native BLOCK use; crouch release additionally dashes.")],
        "guard": [source(CATA + "Gauntlet_of_Guard.java", 60, 80, "Held use applies inward velocity; damage/spawn is not its required result.")],
        "maelstrom": [source(CATA + "Gauntlet_of_Maelstrom.java", 86, 145, "Release requires a native block ray hit before spawning a vortex.")],
        "bloodwake": [source(SS + "item/custom/BloodwakeSwordItem.java", 86, 132, "Frenzy tier gates normal rite; supported crouch branch can select a rite.")],
        "ionbound": [source("research/expanded/ionbound-source/net/sweenus/simplyswords/world/IonboundStormscaleAbilityManager.java", 190, 198, "Native activation requires an earned cube, including timed followup.")],
        "molten-edge": [source(SS + "item/custom/MoltenEdgeSwordItem.java", 80, 90, "Held unbroken item needs heat >0 and no current vent.")],
        "soulrender": [source(SS + "item/custom/SoulrenderSwordItem.java", 159, 175, "Native reaping requires an owned mark.")],
        "tempest": [source(SS + "item/custom/TempestSwordItem.java", 156, 180, "Activation requires native marks and consumes them into a vortex.")],
        "soulstealer": [source(SS + "item/custom/StealSwordItem.java", 170, 208, "Server-selected backstab target and positive earned Soul Debt required."),
                        source(SS + "item/custom/StealSwordItem.java", 413, 431, "Debt comes from StoredCharge; native default applies when absent.")],
        "twisted_blade": [source(SS + "item/custom/TwistedBladeItem.java", 83, 94, "Actual item delegates canActivate/activate to its manager."),
                          source(MANAGERS + "TwistedBladeAbilityManager.java", 151, 187, "Positive Ferocity and no currently armed crescendo required.")],
        "watcher": [source(SS + "item/custom/WatcherClaymoreItem.java", 15, 26, "Exact class inherits WatcherSwordItem use."),
                    source(SS + "item/custom/WatchingWarglaiveItem.java", 14, 24, "Exact class inherits WatcherSwordItem use."),
                    source(SS + "item/custom/WatcherSwordItem.java", 90, 102, "Inherited use dispatches to the common native locked gate."),
                    source(MANAGERS + "WatcherAbilityManager.java", 265, 277, "Native activation requires resolved marked target."),
                    source(MANAGERS + "WatcherAbilityManager.java", 980, 998, "Resolution requires owned Dread mark of matching weapon type and valid target within range.")],
        "hearthflame": [source(SS + "item/custom/HearthflameSwordItem.java", 82, 93, "Exact item delegates native use/activation."),
                       source(MANAGERS + "HearthflameAbilityManager.java", 99, 131, "Rejects already-active manager or no valid targets; active chains are stored by actor UUID.")],
    }
    map_focus = {"cataclysm:azure_sea_shield": ("shield-defense", "shield"), "cataclysm:black_steel_targe": ("shield-defense", "shield"),
                 "cataclysm:bulwark_of_the_flame": ("shield-defense", "shield"), "cataclysm:gauntlet_of_guard": ("gauntlets", "guard"),
                 "cataclysm:gauntlet_of_maelstrom": ("gauntlets", "maelstrom"), "simplyswords:bloodwake": ("bloodwake", "bloodwake"),
                 "simplyswords:ionbound_stormscale": ("ionbound", "ionbound"), "simplyswords:molten_edge": ("molten-edge", "molten-edge"),
                 "simplyswords:soulrender": ("soulrender", "soulrender"), "simplyswords:tempest": ("tempest", "tempest")}
    rows = []
    for unresolved in union["unresolvedOutcomes"]:
        shard, index = unresolved["shard"], unresolved["row"]
        data, report_ref = runs[shard]
        native = data["cases"][index - 1]
        assert unresolved["case"] == native["case"]
        note = annotated[(data["label"], native["case"])]
        disposition = note["disposition"]
        result = dict(original=unresolved, report=report_ref, nativeInterpretation=note,
                      disposition=disposition, originalOutcomePreserved=True, originalRowAbilityPass=False,
                      visualPass=False, sourceEvidence=[], focusedEvidence=[], remainingCoverageLimit=True)
        rule = note.get("sourceRule")
        if rule:
            result["sourceEvidence"].append(dict(audit=ref(semantics_path), rule=rule))
        if note.get("exactGateSource"):
            result["sourceEvidence"].append(note["exactGateSource"])
        if disposition.startswith("EXPECTED_NATIVE_"):
            result["remainingCoverageLimit"] = False
            result["conclusion"] = "Source-consistent negative/no-use control; does not exercise the positive ability or passive proc."
            if disposition in {"EXPECTED_NATIVE_ROUTE_PREREQUISITE_REJECTION", "EXPECTED_NATIVE_LEVEL_PREREQUISITE_REJECTION"}:
                result["focusedEvidence"].append(dict(fixture="relic-unattuned", relation="Distinct level8 unattuned control; level4 alias and attuned routes remain separate."))
        elif disposition == "GEM_DEPENDENT_USE_REQUIRES_COMPONENT_EVIDENCE":
            result["conclusion"] = "Gem-specific ability remains unconfirmed: original runtime did not record socketed gem IDs. Native empty-gem rejection is source-supported but cannot be asserted for this exact row from missing data."
        elif native["kind"] == "melee":
            result["conclusion"] = "The exact dual/downward native melee row has no target health loss; unrelated focused hits or reaping cannot resolve this exact combo/target result."
        elif native["item"] in {"simplyswords:watcher_claymore", "simplyswords:watching_warglaive"}:
            result["sourceEvidence"] = sources["watcher"]
            if native["awakening"] == "locked" and native["abilityUnlocked"] is False and native["serverNativeWeaponState"]["awakeningLevel"] == 0 and native["serverEffectObserved"] is False:
                result.update(disposition="EXPECTED_NATIVE_LOCKED_REJECTION_INHERITED_USE", remainingCoverageLimit=False,
                              conclusion="Exact subclasses inherit WatcherSwordItem.use -> common locked gate. Native rejection is expected; not an active-ability pass.")
            else:
                result.update(disposition="MARK_PREREQUISITE_NOT_DEMONSTRATED",
                              conclusion="Native activation needs an owned Dread mark of the matching weapon type. This fresh-use fixture did not establish that prerequisite or verify its earned positive branch.")
        elif native["item"] in map_focus:
            name, source_name = map_focus[native["item"]]
            fdata, _ = focus[name]
            matches = [(i + 1, row) for i, row in enumerate(fdata["cases"])
                       if row["item"] == native["item"] and row["pitch"] == native["pitch"]
                       and row["crouch"] == native["crouch"] and (name != "shield-defense" or row["blocking"] is True)]
            assert len(matches) == 1, (native["case"], len(matches))
            result.update(disposition="DIFFERENT_NATIVE_FIXTURE_CONFIRMS_BOUNDED_BRANCH", remainingCoverageLimit=False,
                          conclusion="Original no-effect row remains unconfirmed. A distinct prerequisite-complete defensive/earned-state/physics fixture confirms only its recorded native branch and context; it is not an exact-row replacement.")
            result["sourceEvidence"] = sources[source_name]
            result["focusedEvidence"] = [dict(fixture=name, focusedCase=matches[0][0], sameItemPitchCrouch=True,
                                              exactFixture=False, exactRowResolved=False,
                                              relation="Different setup: incoming damage, untethered/ray target, or earned native resource/mark as described in hashed focused report.")]
        elif native["item"] == "simplyswords:soulstealer":
            result.update(disposition="EARNED_SOUL_DEBT_BRANCH_NOT_DEMONSTRATED", sourceEvidence=sources["soulstealer"],
                          conclusion="Positive Soul Debt and a valid native backstab target are required. No earned-debt followup was performed in this final suite; full-use rows remain unconfirmed.")
        elif native["item"] == "simplyswords:twisted_blade":
            result.update(disposition="EARNED_FEROCITY_BRANCH_NOT_DEMONSTRATED", sourceEvidence=sources["twisted_blade"],
                          conclusion="Positive native Ferocity is required and not established by the fresh-use fixture. Earned Crescendo branch remains unconfirmed.")
        elif native["item"] == "simplyswords:hearthflame":
            result.update(disposition="NATIVE_TARGET_OR_ACTIVE_MANAGER_GATE_UNCONFIRMED", sourceEvidence=sources["hearthflame"],
                          conclusion="This crouched level use produced no native signal. Source rejects an already-active per-actor chain manager or missing valid targets; neither gate state was logged here. Adjacent furnace-chain spawns do not prove this exact row.")
        else:
            result["conclusion"] = "Retained unresolved; no source-matched positive assertion is available."
        rows.append(result)
    standards = []
    for shard, (data, report_ref) in runs.items():
        for i, row in enumerate(data["cases"]):
            if row["item"] in {"simplyswords:harbinger", "simplyswords:sunfire"} and row["kind"] == "use" and row["awakening"] == "full":
                interpretation = annotated[(data["label"], row["case"])]
                assert interpretation["disposition"] == "SOURCE_CONSISTENT_NATIVE_STANDARD_SPAWN_OBSERVED"
                standards.append(dict(shard=shard, row=i + 1, report=report_ref, evidence=interpretation,
                                      relation="Distinct level8 native route; does not upgrade level4 Righteous/Tainted rejection."))
    assert len(standards) == 8
    mag_data, _ = focus["magiblade"]
    mag = next(row for row in mag_data["cases"] if row["awakening"] == "full" and row["pitch"] == 55 and row["crouch"] is True)
    assert mag["serverCooldownObserved"] is True and not mag.get("abilitySpawnedEntityTypes") and not mag.get("nativeActionDamageEvents")
    remaining = [dict(shard=r["original"]["shard"], row=r["original"]["row"], item=r["original"]["item"],
                      case=r["original"]["case"], disposition=r["disposition"], reason=r["conclusion"])
                 for r in rows if r["remainingCoverageLimit"]]
    result = dict(status="SOURCE_AND_FOCUSED_EVIDENCE_RECONCILED_WITH_EXPLICIT_REMAINING_LIMITS",
                  sourceUnion=ref(union_path), sourceInterpretation=ref(annotation_path), sourceSemantics=ref(semantics_path),
                  expectedProduction=PRODUCTION, expectedHarness=HARNESS, actualPhysicalMode="FIRSTPERSON_PRESENT_IN_BOTH_FINAL_SHARDS",
                  rawUnresolvedCount=len(rows), rawOutcomesChanged=0, originalRowsPromotedToAbilityPass=0,
                  dispositionCounts=dict(Counter(r["disposition"] for r in rows)),
                  remainingCoverageLimitRows=len(remaining), remainingCoverageLimits=remaining,
                  originalNoEffectRowsWithDistinctFocusedEvidence=sum(r["disposition"] == "DIFFERENT_NATIVE_FIXTURE_CONFIRMS_BOUNDED_BRANCH" for r in rows),
                  perRow=rows, focusedFixtures={name: evidence for name, (_, evidence) in focus.items()},
                  level8NativeStandardEvidence=standards,
                  additionalUnconfirmedOutsideRaw184=[dict(fixture="magiblade", case=mag["case"],
                      disposition="COOLDOWN_ONLY_ABILITY_UNCONFIRMED", nativeUseAndActiveCaptureObserved=True,
                      evidence={k: mag.get(k) for k in ["serverUseElapsed", "activeUsePoseObserved", "serverCooldownObserved", "abilitySpawnedEntityTypes", "nativeActionDamageEvents", "targetDamage"]},
                      reason="Native short use and its render pose completed, but the downward crouch case has cooldown only; cooldown cannot prove the intended sonic ability outcome.")],
                  limits=["All184 original unresolved strings are retained; no row is converted into an ability pass.",
                          "Expected locked/route/level/no-use results are negative controls, not positive abilities or passive procs.",
                          "Focused fixtures use distinct prerequisite/target/game-mode setups and cannot repair original exact-row attribution.",
                          "Runic gem IDs, earned Soul Debt/Ferocity/Dread branches and the listed exact melee no-hit cases remain unverified.",
                          "Native chance effects, every awakening choice, and every item/camera/scale/world-condition cross-product are not certified.",
                          "Manual review limitations remain in linked artifacts; numerical signals do not establish visibility or attachment when obscured."])
    output = ROOT / args.output
    paired_path = "verification/expanded/final-soul-downward-melee-target-audit.json"
    if (ROOT / paired_path).is_file():
        result["nativeTargetSelectionFollowup"] = ref(paired_path,
            conclusion="Four exact downward cases were replayed in both physical FirstPerson modes. "
                       "All eight native target lists and attributed damage lists remained empty. "
                       "This reproduces the selection miss; it does not certify successful hits. "
                       "The native-cone explanation remains a source-and-geometry inference.")
    output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: result[k] for k in ["status", "rawUnresolvedCount", "rawOutcomesChanged", "dispositionCounts", "remainingCoverageLimitRows", "originalNoEffectRowsWithDistinctFocusedEvidence"]}, indent=2))


if __name__ == "__main__":
    main()
