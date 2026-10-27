"""Read-only, source-bounded interpretation of native combat outcome evidence.

This supplements audit_final_combat_union.py; it neither changes its verdict nor
edits reports. Expected rejection is not a successful active-ability claim.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
SS = ROOT / "research/expanded/simplyswords"
CATALOGUE = ROOT / "verification/expanded/canonical1431-native-catalogue.json"


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def native_gate_sources(catalogue):
    """Only exact extracted methods visibly delegating to the common gate."""
    found = {}
    for entry in catalogue:
        cls = entry.get("preparedClass", "")
        if not entry.get("activeUniqueAbility") or cls in found:
            continue
        path = SS / (cls.replace(".", "/") + ".java")
        if not path.is_file():
            continue
        source = path.read_text(encoding="utf-8")
        method = re.search(r"public InteractionResultHolder<ItemStack> use\([^\n]+\) \{([^}]+)\}", source)
        if method and method.group(1).strip() == "return this.useFromDefaultInput(world, user, hand);":
            found[cls] = dict(path=str(path.relative_to(ROOT)), sha256=digest(path),
                              line=source[:method.start()].count("\n") + 1)
    return found


def annotate(row, catalogue, gates):
    item, kind = row.get("item"), row.get("kind", "")
    entry = catalogue.get(item, {})
    cls = entry.get("preparedClass")
    state = row.get("serverNativeWeaponState", {})
    verified = row.get("serverNativeWeaponStateVerified") is True and row.get("clientNativeWeaponStateVerified") is True
    no_signal = row.get("serverEffectObserved") is False
    result = dict(case=row.get("case"), item=item, kind=kind, awakening=row.get("awakening"),
                  pitch=row.get("pitch"), crouch=row.get("crouch"), rawStatus=row.get("status"),
                  rawAbilityOutcome=row.get("abilityOutcome"), rawHitOutcome=row.get("hitOutcome"),
                  clientUseResult=row.get("clientUseResult"), abilityUnlocked=row.get("abilityUnlocked"),
                  serverNativeWeaponState=state, nativeStateVerifiedBothSides=verified,
                  serverEffectObserved=row.get("serverEffectObserved"),
                  abilitySpawnedEntityTypes=row.get("abilitySpawnedEntityTypes"),
                  targetDamage=row.get("targetDamage"),
                  disposition="UNCLASSIFIED_NATIVE_OUTCOME", activeAbilityCertification=False,
                  visualCertification=False)
    if row.get("status") != "CAPTURED" or row.get("rendererViolations") != 0:
        result.update(disposition="ACTION_OR_RENDER_FAILURE_RETAINED", reason="Expected rejection cannot override failed action/render checks.")
    elif kind in {"melee", "vanilla_hit"}:
        result.update(disposition="NATIVE_HIT_UNCONFIRMED" if row.get("hitOutcome") else "MELEE_EVIDENCE_REQUIRES_EVENT_ATTRIBUTION",
                      reason="Native animation, target selection, and damage events are separate evidence; generic health loss includes preparation and lingering effects.")
    elif kind.startswith("use"):
        if verified and state.get("awakeningLevel") == 0 and row.get("abilityUnlocked") is False and cls in gates:
            result.update(disposition="EXPECTED_NATIVE_LOCKED_REJECTION" if no_signal else "LOCKED_CONTROL_SIGNAL_REQUIRES_ATTRIBUTION",
                          sourceRule="unique_default_gate", exactGateSource=gates[cls],
                          reason="This exact class delegates native use to the locked-state gate. Signals cannot establish that a locked ability activated.")
        elif verified and item in {"simplyswords:righteous_relic", "simplyswords:tainted_relic"} and state.get("backingItem") == "simplyswords:dormant_relic" and state.get("awakeningLevel") == 4:
            result.update(disposition="EXPECTED_NATIVE_LEVEL_PREREQUISITE_REJECTION" if no_signal else "BELOW_LEVEL_GATE_SIGNAL_REQUIRES_ATTRIBUTION",
                          sourceRule="relic_level_and_route_gate",
                          reason="Native alias migration preserves level 4; DormantRelicSwordItem.canActivate requires level >= 8. Client SUCCESS/generic abilityUnlocked does not bypass that server predicate.")
        elif verified and item in {"simplyswords:dormant_relic", "simplyswords:decaying_relic"} and state.get("backingItem") == item and state.get("awakeningLevel") == 8 and state.get("route") in {"none", ""}:
            result.update(disposition="EXPECTED_NATIVE_ROUTE_PREREQUISITE_REJECTION" if no_signal else "UNATTUNED_CONTROL_SIGNAL_REQUIRES_ATTRIBUTION",
                          sourceRule="relic_level_and_route_gate",
                          reason="A fresh level-8 stack without SUN/HARBINGER route cannot activate this native standard. It is not an attuned-ability test.")
        elif verified and item == "simplyswords:toxic_longsword" and state.get("backingItem") == item and cls == "net.sweenus.simplyswords.item.custom.PlagueSwordItem" and row.get("clientUseResult") == "PASS":
            result.update(disposition="EXPECTED_NATIVE_NO_USE" if no_signal else "NO_USE_CONTROL_SIGNAL_REQUIRES_ATTRIBUTION",
                          sourceRule="plague_inherited_no_use",
                          reason="Exact PlagueSwordItem has a hurtEnemy passive and inherits UniqueWeaponItem.use -> useUniqueWeapon -> native Item.use; ordinary non-food use returns PASS.")
        elif cls == "net.sweenus.simplyswords.item.RunicSwordItem":
            result.update(disposition="GEM_DEPENDENT_USE_REQUIRES_COMPONENT_EVIDENCE",
                          sourceRule="runic_gem_dispatch",
                          reason="Use dispatches to actual gem components; their IDs are not recorded by the matrix. Empty gem power rejects natively, while runic_spear also throws. BLOCK is only a declared animation, not an active ability assertion.")
        elif verified and item in {"simplyswords:sunfire", "simplyswords:harbinger"} and state.get("backingItem") == "simplyswords:dormant_relic" and state.get("awakeningLevel") == 8:
            route, entity = ("simplyswords:sun", "simplyswords:battlestandard") if item.endswith(":sunfire") else ("simplyswords:harbinger", "simplyswords:battlestandarddark")
            observed = state.get("route") == route and row.get("abilitySpawnedEntityTypes", {}).get(entity, 0) > 0
            result.update(disposition="SOURCE_CONSISTENT_NATIVE_STANDARD_SPAWN_OBSERVED" if observed else "ATTUNED_STANDARD_OUTCOME_UNCONFIRMED",
                          sourceRule="relic_level_and_route_gate",
                          reason="Matched level, route, and native standard type are evidence of that branch; this does not attribute every damage event, certify every upgrade, or grade pixels.")
        else:
            result.update(disposition="NATIVE_ACTION_OUTCOME_UNCONFIRMED" if no_signal else "NATIVE_SIGNAL_OBSERVED_ATTRIBUTION_LIMITED",
                          reason="No-effect can reflect prerequisites or a no-use path; a generic cooldown/effect/health signal alone does not identify the requested ability. Requires item-specific evidence.")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reports", nargs="+", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    catalogue_rows = read(CATALOGUE)["catalogue"]
    catalogue = {entry["item"]: entry for entry in catalogue_rows}
    gates = native_gate_sources(catalogue_rows)
    runs = []
    for path in args.reports:
        data = read(path)
        rows = [annotate(row, catalogue, gates) for row in data.get("cases", [])]
        runs.append(dict(sourceReport=str(path), sourceReportSha256=digest(path), label=data.get("label"),
                         status=data.get("status"), firstPersonInstalled=data.get("firstPersonInstalled"),
                         rowCount=len(rows), dispositionCounts=dict(Counter(row["disposition"] for row in rows)), rows=rows))
    output = dict(status="SOURCE_BOUNDED_INTERPRETATION_NOT_CERTIFICATION", sourceCatalogue=str(CATALOGUE.relative_to(ROOT)),
                  sourceCatalogueSha256=digest(CATALOGUE), sourceAudit="verification/expanded/native-action-outcome-semantics.json",
                  runs=runs, limits=[
                      "Preserves raw outcomes; never changes final union technical/visual checks or grants active-ability certification.",
                      "Exact native source rules apply only to the verified recorded backing item, level, route, and class.",
                      "No use on one stack is not a pass for unrelated enchantments, gems, passive procs, upgrade branches, or camera/scale cross-products.",
                      "Generic signals may originate in an earlier ability manager. Locked signal cases remain attribution review.",
                      "Physically absent and FPM-present runs remain separate; the directory name does not determine mode."])
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(output, indent=2) + "\n", encoding="utf-8")
    print(json.dumps([{k:v for k,v in run.items() if k != "rows"} for run in runs], indent=2))


if __name__ == "__main__":
    main()
