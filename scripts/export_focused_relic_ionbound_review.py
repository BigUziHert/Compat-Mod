"""Save the independently inspected release Relic/Ionbound evidence; no image grading."""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RUNS = ROOT / '.local/without-firstperson/.local/client/emfattachments-tests'
OUT = ROOT / 'verification/expanded/focused-native-release'

def hashed(p):
    return {'path': p.relative_to(ROOT).as_posix(), 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()}

def trimmed(v):
    if isinstance(v, dict):
        return {k: trimmed(x) for k, x in v.items() if k != 'nativeDamageEvents'}
    return v

NOTES = {
    'relic-unattuned': [
        'All four full frames inspected. Level Dormant/Decaying controls retain one narrow held relic, with lower handle outside the viewport. Downward Dormant grip is coherent; the close resting forearm partly occludes Decaying geometry. No visible detached or duplicated assessed segment. These are fresh unattuned-use rejection controls, not an attuned active-ability demonstration.'
    ],
    'ionbound': [
        'Frames 1-4: one coherent blue held weapon throughout zero-cube, first/two earned cubes and corridor start. White cube/spark world effects remain distinct from the held weapon; lower handle is cropped by the viewport.',
        'Frames 5-8: native slam lightning and target hurt pose are distinct world effects. Bright follow-up beam 6 obscures most held geometry, which is visually unassessable there. Expired-beam 7 and next zero-cube control 8 restore a clear single blue weapon.',
        'Frames 9-12: single held weapon remains coherent during natural cube accrual and corridor; native lightning during slam partly overlaps the target. No visible duplicated or detached assessable geometry.',
        'Frames 13-16: bright beam 13 obscures held geometry; expired beam 14 clears it. Downward control and first cube 15-16 show coherent visible grip/weapon with close resting forearm partially covering the lower viewport.',
        'Frames 17-20: downward cube/corridor/slam snapshots retain coherent single visible grip/weapon. Bright beam 20 and close hurt target obscure substantial geometry; no full-shape alignment claim for that snapshot.',
        'Frames 21-24: expired beam and fresh crouched control/cube snapshots show a coherent single held weapon. Native sparks and the close target occlude some upper geometry; lower forearm is visibly close to the camera but is the retained resting pose.',
        'Frames 25-28: downward crouched corridor/slam maintain coherent single weapon/grip. Follow-up beam 27 obscures the lower arm/weapon; expired beam 28 clears the beam while native sparks remain. No visible detached or duplicate assessed segment.'
    ]
}

def export(mode):
    label = f'expanded-fpm-{mode}-native-release-r1'
    run = RUNS / label
    raw = run / 'sequential-ability-report.json'
    d = json.loads(raw.read_text())
    manifest = ROOT / 'verification/expanded-run-manifests' / f'{label}.json'
    cases = []
    for c in d['cases']:
        checks = {
            'nativeCasePassed': c['status'] == 'PASS',
            'worldWasReady': c['nativeWorldReadiness']['ready'],
            'zeroRendererViolations': c['rendererViolations'] == 0,
            'pendingPoseDrained': c['nativePendingPoseDrained'],
            'seatReleased': c['nativeClientSeatReleased'],
            'visibilityRestored': c['nativeClientVisibilityRestored'],
        }
        keep = ['item', 'pitch', 'crouch', 'status', 'detail', 'rendererViolations', 'cleanupServerTicks']
        if mode == 'relic-unattuned':
            before, after = c['beforeNativeUse'], c['afterNativeUse']
            checks.update({
                'nativeBackingItem': before['nativeItem'] == c['item'] == after['nativeItem'],
                'freshUnattunedLevelEight': before['level'] == after['level'] == 8 and before['route'] == after['route'] == '',
                'noStandardCreated': before['standardPositionAir'] and after['standardPositionAir'],
                'noAbilityUseOrCooldown': not before['serverUsing'] and not after['serverUsing'] and not before['nativeCooldown'] and not after['nativeCooldown'],
                'noSpawnOrTargetDamage': not c['spawnedEntityTypes'] and c['targetDamage'] == 0,
            })
            keep += ['beforeNativeUse', 'afterNativeUse', 'spawnedEntityTypes', 'targetDamage']
        else:
            initial, zero, earned = c['initialIonState'], c['zeroCubeUse'], c['earnedCubes']
            slam, follow, beam, final = [c[k] for k in ['nativeSlam', 'beforeNativeFollowup', 'activeNativeBeam', 'ionboundFinal']]
            timeline = c['nativeCubeTimeline']
            checks.update({
                'zeroCubeRejection': initial['cubes'] == zero['cubes'] == 0 and not zero['corridor'] and not zero['beam'],
                'nativeRecharge160And320': any(x['nativeHeldTicks'] == 160 and x['cubes'] == 1 for x in timeline) and earned['nativeHeldTicks'] == 320 and earned['cubes'] == 2,
                'nativeSlam22TickDelay': slam['slammed'] and slam['cubes'] == 1 and slam['slamAt'] - slam['corridorStartedAt'] == 22,
                'actualFollowupWindow': follow['pendingFollowup'] and follow['slamAt'] <= follow['worldTick'] <= follow['followupEnds'],
                'nativeBeam60Ticks': beam['beam'] and beam['cubes'] == 0 and beam['beamEndsAt'] - beam['beamStartedAt'] == 60,
                'nativeStatusAndSlow': beam['movementSlow'] and final['movementSlowSeen'] and final['beamTargetParalysisSeen'],
                'sameStackAndAttributedDamage': final['sameServerStack'] and final['slamTargetDamage'] > 0 and final['beamTargetDamage'] > 0 and c['ownerAttributedSlamDamage'] and c['ownerAttributedBeamDamage'],
                'naturallyExpired': c['nativeAbilityStateExpired'] and not final['corridor'] and not final['beam'] and not final['pendingFollowup'] and not final['movementSlow'],
                'requestedPoseDuringAction': all(s['pitch'] == c['pitch'] and s['crouch'] == c['crouch'] for s in [zero, earned, slam, follow, beam]),
            })
            keep += ['nativeRechargeInterval', 'initialIonState', 'zeroCubeUse', 'earnedCubes', 'nativeCubeTimeline', 'nativeSlam', 'beforeNativeFollowup', 'activeNativeBeam', 'nativeBeamExpired', 'ionboundFinal', 'nativeAbilityStateExpired', 'ownerAttributedSlamDamage', 'ownerAttributedBeamDamage']
        cases.append({**{k: trimmed(c[k]) for k in keep}, 'checks': checks})
    restored = {k: d[k] for k in ['clientSettingsRestored', 'selectionReconciledToOriginalServer', 'clientOriginalSelectionRestored', 'serverSelectedRestored', 'serverInventoryRestored']}
    captures = []
    for i, c in enumerate(d['captures']):
        p = run / c['screenshot']
        captures.append({**hashed(p), 'number': i + 1, 'phase': c['phase'], 'pitch': c['pitch'],
            'drawCounts': {k: c[k] for k in ['bodyPasses', 'bodyItems', 'vanillaItems', 'otherItems']},
            'manualNote': NOTES[mode][i // 4]})
    checks = {
        'finalCaptured': d['status'] == 'CAPTURED',
        'physicalFirstPersonPresent': d['firstPersonInstalled'],
        'firstPersonEnabled': d['originalFirstPersonEnabled'] and not d['temporarilyDisabledFirstPerson'],
        'fourCases': len(cases) == 4,
        'allNativeAssertions': all(all(c['checks'].values()) for c in cases),
        'restoration': all(restored.values()),
        'singleLocalBodyAndHeldItem': all(tuple(c[k] for k in ['bodyPasses', 'bodyItems', 'vanillaItems', 'otherItems']) == (1, 1, 0, 0) for c in d['captures']),
    }
    assert all(checks.values()), checks
    out = {'reviewer': '/root/equipment_inventory', 'run': label, 'status': 'REVIEWED_WITH_VISIBILITY_LIMITS',
        'allImagesManuallyInspected': True, 'reviewedImages': len(captures), 'reviewedContactPages': list(range(1, len(NOTES[mode]) + 1)),
        'sourceReport': hashed(raw), 'manifest': hashed(manifest), 'checks': checks, 'cases': cases, 'captures': captures,
        'restoration': restored, 'restoredValues': {k: d[k] for k in ['originalClientSelected', 'originalServerSelected', 'gameModeRestored', 'arenaBlocksRestored', 'difficultyRestored', 'difficultyLockRestored']},
        'modeNote': 'FirstPerson physically present and enabled. The historical without-firstperson directory name does not describe this final run.',
        'limits': ['Manual visual review is limited to visible geometry in the saved frames; cropped, close-forearm-covered, bright-beam and particle-obscured parts are unassessed, not an alignment pass.',
            'Draw counts are renderer-call evidence and are kept distinct from emitted-geometry proof. Native world effects are separate from the held-item draw.',
            'Relic controls certify fresh unattuned rejection only; they do not certify SUN/HARBINGER attuned active routes.' if mode == 'relic-unattuned' else 'Ionbound proves two naturally earned cubes, ordinary corridor/slam and follow-up beam with native expiry at these four views; it does not exhaust every configuration, awakening route or possible environment.']}
    OUT.mkdir(parents=True, exist_ok=True)
    p = OUT / f'{mode}-review.json'
    p.write_text(json.dumps(out, indent=2) + '\n')
    print(p.relative_to(ROOT), checks, len(captures))

if __name__ == '__main__':
    export('relic-unattuned')
    export('ionbound')
