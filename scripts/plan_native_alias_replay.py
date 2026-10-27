"""Reconstruct the six legacy-alias replay cases from the observed native catalogue."""
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1]
source = root / 'verification/expanded/expanded-catalog-expanded-combat-report.json'
catalogue = json.loads(source.read_text())['catalogue']
by_id = {entry['item']: entry for entry in catalogue}
migrations = {
    'awakened_lichblade': ('slumbering_lichblade', 8, 'lichblade', 1.0),
    'harbinger': ('dormant_relic', 8, 'harbinger', 1.0),
    'righteous_relic': ('dormant_relic', 4, 'sun', .25),
    'sunfire': ('dormant_relic', 8, 'sun', .75),
    'tainted_relic': ('dormant_relic', 4, 'harbinger', .5),
    'waking_lichblade': ('slumbering_lichblade', 4, 'lichblade', .5),
}
items, cases = [], []
for entry in catalogue:
    name = entry['item'].removeprefix('simplyswords:')
    if name not in migrations:
        continue
    backing, level, route, model = migrations[name]
    # Exact installed WeaponRegistry resolves a preset component or physical
    # item ID. Migration supplies no preset. Runtime must confirm this estimate
    # on the actual prepared stack; never reuse the old alias combo indices.
    native = by_id['simplyswords:'+backing]
    selected = []
    for attack in native['attacks']:
        for pitch in (0, 55):
            selected.append(dict(item=entry['item'],kind='melee',attackIndex=attack['index'],pitch=pitch,
                context=attack['context'],awakening='full',crouch=False,
                plannedNativeBackingAnimation=attack['animation']))
    assert native['nativeUse'] and not native['nativeUseOn']
    for pitch in (0, 55):
        for crouch in (False, True):
            selected.append(dict(item=entry['item'],kind='use',attackIndex=0,pitch=pitch,
                context='empty',awakening='full',crouch=crouch))
    # Both native backing classes implement active abilities and persistent
    # progression, including level4 relic aliases whose original class did not.
    if native['activeUniqueAbility']:
        selected.append(dict(item=entry['item'],kind='use',attackIndex=0,pitch=0,
            context='empty',awakening='locked',crouch=False))
    items.append(dict(requestedId=entry['item'],nativeBackingItem='simplyswords:'+backing,
        nativeAliasLevel=level,nativeAliasForm=entry['item'],nativeAliasRoute='simplyswords:'+route,
        nativeAliasModelValue=model,meleeCases=native['nativeAttacks']*2,nativeUseCases=4,
        originalRegistryAttackCount=entry['nativeAttacks'],nativeBackingAttackCount=native['nativeAttacks'],
        originalRegistryCaseCount=entry['nativeAttacks']*2+4+int(entry['activeUniqueAbility']),
        lockedControlCases=int(native['activeUniqueAbility']),expectedCasesPerInstallation=len(selected),
        fullAwakeningMeans='Preserve the native alias form/level; do not promote level4 aliases to level8.',
        abilityExpectation='Native level4 use may reject the level8-only active ability; this is an expected prerequisite boundary, not a rendering failure.' if level==4 else 'Ability outcome still requires its native prerequisites and effect-specific evidence.'))
    cases.extend(selected)
assert len(cases)==70
report=dict(status='SOURCE_VERIFIED_PLAN_AWAITING_PREPARED_STACK_RUNTIME_CATALOGUE',catalogueSource=source.relative_to(root).as_posix(),
    sourcePreparation='src/testmod/java/dev/caleb/emfattachments/test/ExpandedNativeWeaponPreparation.java',
    originalLegacyRegistryCasesPerInstallation=82,
    expectedCasesPerInstallation=70,expectedCasesAcrossBothPhysicalModes=140,
    expectedScreenshotsPerInstallation=140,
    countBasis='Native canonical backing attributes from the observed runtime catalogue; source confirms no weapon preset is supplied by native migration. Prepared-stack catalogue must verify before replay.',
    triggerFile='emfattachments-expanded-combat.txt',
    triggerLines=['REPLACE_WITH_DISTINCT_REPLAY_LABEL','all','item=simplyswords:(awakened_lichblade|harbinger|righteous_relic|sunfire|tainted_relic|waking_lichblade)'],
    requiredEvidence=[
        'Exact client and authoritative server backing ID, level, form, route and model component match before action.',
        'Actual native attack index and resolved animation must be recorded after migration; a planned legacy animation is not proof it ran.',
        'Preserve the old 82 alias-input rows as historical fixture scope; do not claim inaccessible old alias combos were executed by the canonical stack.',
        'Native inputs execute use/attack; no direct ability activation or manager reset.',
        'Locked controls use canonical level zero to prevent native alias inventoryTick from reawakening them.',
        'Failed historical alias rows remain fixture failures, not gameplay passes.',
        'Native ATTACK_HIT and action-period damage-source fields from the new harness are separate from old case-wide health loss.',
        'Native persistent effects can still carry over; generic effect signals do not prove current ability attribution.'
    ],items=items,cases=cases)
out=root/'verification/expanded/native-alias-replay-plan.json'
out.write_text(json.dumps(report,indent=2))
print(json.dumps([{k:r[k] for k in ('requestedId','nativeBackingItem','nativeAliasLevel','expectedCasesPerInstallation')} for r in items],indent=2))
