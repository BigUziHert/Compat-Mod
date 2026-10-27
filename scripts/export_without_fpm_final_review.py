"""Package explicitly inspected final no-FirstPerson prefix, roll and gap replay evidence."""
import hashlib
import json
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / '.local/without-firstperson/.local/client/emfattachments-tests'
OUT = ROOT / 'verification/expanded'
MAIN = '524b58479fdc925ed42544df61f9e3cea334f059c3bf6615685a81486b7d6ecd'
HARNESS = '23d51aa137befeaf5c3a07939a5c4cf8952ec6f4b980cb4bcc79488069d868f7'
KEYS = ['item', 'kind', 'attackIndex', 'pitch', 'context', 'awakening', 'crouch']
RESTORE = ['serverInventoryRestored', 'nativeServerSessionReleased', 'originalAccessoriesAndToggleComponentsRestored', 'selectionReconciledToOriginalServer', 'serverSelectedRestored', 'nativeClientSeatReleased', 'nativeClientVisibilityRestored', 'cleanupReactionGraceExpired', 'nativeRagdollCameraConfigRestored', 'clientSettingsRestored']
CASE_CLEANUP = ['betweenCasePendingPoseDrained', 'betweenCaseServerSessionReleased', 'betweenCaseClientSeatReleased', 'betweenCaseClientVisibilityRestored']

def h(p):
    return {'path': p.relative_to(ROOT).as_posix(), 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()}

def read(label):
    p = BASE / label / 'expanded-combat-report.json'
    d = json.loads(p.read_text())
    mp = OUT.parent / 'expanded-run-manifests' / f'{label}.json'
    m = json.loads(mp.read_text())
    assert d['status'] == 'CAPTURED' and d['firstPersonInstalled'] is False
    assert m['firstpersonInstalled'] is False and m['rejectedCompanionAbsent'] is True
    assert m['artifacts']['emf-attachments-compat-1.21.1-1.2.0.jar'] == MAIN
    assert m['artifacts']['emf-attachments-compat-1.21.1-1.2.0-test-harness.jar'] == HARNESS
    assert all(d[k] is True for k in RESTORE)
    assert all(c[k] is True for c in d['cases'] for k in CASE_CLEANUP)
    return d, {'report': h(p), 'manifest': h(mp), 'firstPersonPhysicallyPresent': False,
        'productionSha256': MAIN, 'harnessSha256': HARNESS,
        'restoration': {k:d[k] for k in RESTORE}, 'restoredValues': {k:d[k] for k in ['difficultyRestored','difficultyLockRestored','originalClientSelected','originalServerSelected','arenaBlocksRestored']},
        'completedCases': len(d['cases']), 'observedFrames': sum(c['frames'] for c in d['cases']),
        'zeroItemFrames': sum(c['zeroItemFrames'] for c in d['cases']), 'rendererViolations': sum(c['rendererViolations'] for c in d['cases']),
        'allBetweenCaseCleanupVerified': True}

def shots(label, row):
    return [{**h(BASE / label / row[phase]['screenshot']), 'phase':phase,
        **{k:row[phase].get(k) for k in ['tick','attackActive','cameraType','vehicle','invisible','bodyPasses','bodyItems','vanillaItems','otherItems']}} for phase in ['active','recovery']]

prefix_label = 'expanded-without-fpm-storm-prefix-release-r1'
roll_label = 'expanded-without-fpm-roll-release-r1'
replay_label = 'expanded-without-fpm-active-replay-1200-release-r1'
prefix, pe = read(prefix_label)
roll, re = read(roll_label)
replay, ge = read(replay_label)
assert len(prefix['cases']) == 72 and len(roll['cases']) == len(replay['cases']) == 1
assert pe['rendererViolations'] == re['rendererViolations'] == ge['rendererViolations'] == 0
assert pe['zeroItemFrames'] == re['zeroItemFrames'] == ge['zeroItemFrames'] == 0
late = [(i+1,c) for i,c in enumerate(prefix['cases']) if c['kind']=='melee' and c['active']['attackActive'] is not True]
assert [i for i,c in late] == [18]
original = late[0][1]; companion = replay['cases'][0]
assert all(original[k] == companion[k] for k in KEYS)
assert companion['active']['attackActive'] is True and companion['active']['tick'] == 4
assert companion['recovery']['attackActive'] is False and companion['activeLayerObserved'] is True
assert companion['active']['otherItems'] == 1 and companion['active']['vanillaItems'] == 0
assert companion['recovery']['vanillaItems'] == 1 and companion['recovery']['otherItems'] == 0

storm = []
for i,c in enumerate(prefix['cases'],1):
    if c['item']=='simplyswords:stormscale' and c['kind']=='melee':
        a = c['active']; w = a['worldPlayerRenderDiagnostics']
        checks = {'nativeAttackActive': a['attackActive'] is True,
            'oneNativeHeldItem': a['otherItems']==1 and a['vanillaItems']==a['bodyItems']==a['bodyPasses']==0,
            'nativePlayerEntryReturn': w['levelPlayerEntries']==w['levelPlayerReturns']==1,
            'playerRenderUncancelled': w['renderPlayerPre']==w['renderPlayerPost']==1 and w['renderPlayerPreCanceled']==0,
            'nativePlayerAnimatorMode': w['playerAnimatorMode']=='THIRD_PERSON_MODEL',
            'noNativeCameraSuppression': w['nativeCameraState']['suppressLocalPlayerRender'] is False,
            'noRecordedMissingItemIntervals': c['worldPlayerRenderHistory']['activeMissingItemFrames']==0}
        assert all(checks.values()), (i,checks)
        storm.append({'row':i, **{k:c[k] for k in KEYS}, 'checks':checks, 'activeRenderWitness':w})
assert len(storm)==8
roll_case=roll['cases'][0]
assert roll_case['rollObserved'] and roll_case['rollAvailableAtStart'] and roll_case['movementDistance']>0

pe.update({'kindCounts':dict(Counter(c['kind'] for c in prefix['cases'])), 'all144ImagesManuallyInspected':True,
    'visualReview':h(OUT/'combat-visual/without-fpm-storm-prefix-release-r1.json'), 'stormscaleEightNativeMeleeControls':storm,
    'lateActiveRowsRetained':[{'row':18,'key':{k:original[k] for k in KEYS},'tick':original['active']['tick'],'firstInactiveTick':original['firstInactiveTick'],'images':shots(prefix_label,original)}],
    'nativeOutcomeCaution':'CAPTURED proves lifecycle execution, not every offensive use branch. Fresh Soulstealer ability and Stormbringer earned-parry prerequisites remain unconfirmed by this generic matrix. Locked StarsEdge effects can be earlier native carryover; case-wide health/effect signals are not current-ability attribution.'})
re.update({'all2ImagesManuallyInspected':True,'images':shots(roll_label,roll_case),'nativeRollObserved':True,
    'movementDistance':roll_case['movementDistance'],'emptyHands':roll_case['item']=='minecraft:air',
    'visualNotes':'Both full frames inspected. Native external ragdoll camera shows the small moving player with no new visible detachments. Detailed attachment alignment is unassessable at this distance. Empty-hand zero item draws are expected. The nominal recovery snapshot still has the native seat; final cleanup independently verifies seat/visibility restoration.',
    'nativeCameraConfigurationPreserved':roll['nativeRagdollCameraConfigRestored']})
ge.update({'all2ImagesManuallyInspected':True,'key':{k:companion[k] for k in KEYS},'images':shots(replay_label,companion),
    'originalPrefixRow':18,'originalGapRetained':True,'actualNativeActiveAtCapture':True,'captureTick':4,
    'nativeAttackHitEvents':companion['nativeAttackHitEvents'],'nativeActionDamageEvents':companion['nativeActionDamageEvents'],
    'visualNotes':'Both full frames inspected. Native active snapshot shows one coherent dark/blue Soulstealer blade segment near the hurt target; recovery shows one right-edge blade. Native soul/ring particles are separate world effects. Grip and cropped extents remain visually unassessed.',
    'gapResolution':'Same canonical key, same production and harness, physical FirstPerson absence, and true native active layer at the companion capture close the active-pose timing gap. Original late image is retained and never relabeled active.'})
result={'reviewer':'equipment_inventory','status':'REVIEWED_WITH_VISIBILITY_AND_NATIVE_BRANCH_LIMITS','manuallyInspectedImages':148,
    'prefix':pe,'roll':re,'activeGapCompanion':ge,
    'historicalStormscaleFailure':{'status':'UNRESOLVED_NOT_REPRODUCED_IN_THIS_BOUNDED_RUN','reference':'verification/expanded/stormscale-world-render-audit.json',
        'detail':'The original r10 eight native Stormscale omissions remain recorded. This final72-case native prefix, including native ragdoll transitions, has zero missing-item intervals and eight actual active Stormscale snapshots; non-reproduction does not prove the original cause fixed.'},
    'limits':['Every listed PNG was manually inspected; reports and manifests are hashed here.',
        'Sampled screenshots and observed frame counters do not exhaust every possible animation frame, configuration or multiplayer environment.',
        'Geometry hidden by native particles/mounts, offscreen grips and small external-ragdoll views remain unassessable, not an unconditional alignment pass.']}
(OUT/'without-fpm-final-storm-roll-review.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps({'images':148,'prefixCases':72,'prefixFrames':pe['observedFrames'],'stormscaleControls':8,'rollFrames':re['observedFrames'],'gapClosed':True},indent=2))
