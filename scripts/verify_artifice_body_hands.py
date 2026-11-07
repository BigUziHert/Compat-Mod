"""Check the scoped FirstPerson body-hand mask; original images still need review.

Use --mask expected only for builds containing the explicit zero-geometry body
mask. Use --mask ownership for pistol free-arm coverage with physical native
hand identities and measured skin/sleeve/accessory body vertices. Older versions
of FirstPerson can move body gloves offscreen while still
emitting vertices; --mask observe records those vertices without calling them
a visible duplicate. This script never infers visibility from vertex counts.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re


HAND_SLOTS = ('hand', 'aether:gloves_slot')
BODY_PREFIXES = tuple('normal/' + slot + '/' for slot in HAND_SLOTS)
STABLE_GUN_PHASES = frozenset(('HOLD', 'WALK', 'SPRINT', 'CROUCH', 'RELOAD',
                               'RELOAD_RETURN', 'SPRINT_RELOAD', 'FIRE', 'RETURN'))
ACCESSORY_NAMESPACES = ('artifacts:', 'aether:', 'deep_aether:')


def texture_name(render_type):
    match = re.search(r'texture\[Optional\[([^\]]+)\]', render_type)
    return match.group(1) if match else render_type


def verify(path, mask):
    raw_bytes = path.read_bytes()
    raw = json.loads(raw_bytes)
    failures, frames = [], []
    coverage = dict(maskedGunFrames=0, visibleNativeAccessoryFrames=0,
                    hiddenNativeAccessoryFrames=0, emptyHandRecoveryFrames=0,
                    thirdPersonControlFrames=0, skippedFrames=0,
                    stableFirstPersonFramesWithoutNativeHands=0,
                    freeBodyArmFrames=0, bothNativeArmFrames=0, hiddenFreeArmFrames=0, cosmeticFreeArmFrames=0,
                    rightNativeOnlyFrames=0, leftNativeOnlyFrames=0,
                    visibleNativeAccessoryArmChecks=0, hiddenNativeAccessoryArmChecks=0)

    def check(condition, message):
        if not condition:
            failures.append(message)

    check(raw.get('finished') is True and raw.get('status') == 'CAPTURED',
          'Native lifecycle did not finish')
    check(raw.get('completedContexts') == raw.get('plannedContexts'),
          'Incomplete context matrix')
    if mask in ('expected', 'ownership'):
        check(raw.get('firstPersonInstalled') is True, 'FirstPerson was not installed')

    for capture in raw.get('captures', []):
        label = capture.get('screenshot', '<missing screenshot>')
        camera = capture.get('actualCameraType')
        stable = (capture.get('rolling') is False and capture.get('passenger') is False)
        phase = capture.get('phase')
        probe = capture.get('renderProbe', {})
        hands = probe.get('hands', [])
        stable_gun = stable and phase in STABLE_GUN_PHASES
        gun_frame = camera == 'FIRST_PERSON' and stable_gun and len(hands) > 0
        recovery = (camera == 'FIRST_PERSON' and stable
                    and capture.get('name') == 'switch_empty_hand')
        third_person = (camera in ('THIRD_PERSON_FRONT', 'THIRD_PERSON_BACK')
                        and stable and (stable_gun or capture.get('name') == 'switch_empty_hand'))
        if camera == 'FIRST_PERSON' and stable_gun and not hands:
            coverage['stableFirstPersonFramesWithoutNativeHands'] += 1
        if not (gun_frame or recovery or third_person):
            coverage['skippedFrames'] += 1
            continue

        physical = capture.get('physicalAccessoryProbe')
        check(isinstance(physical, dict) and isinstance(physical.get('vertices'), dict),
              'Missing physical geometry probe: ' + label)
        physical = physical or {}
        body = {key: count for key, count in physical.get('vertices', {}).items()
                if key.startswith(BODY_PREFIXES)}
        body_vertices = sum(body.values())
        native_textures = {}
        invalid_native = 0
        for hand in hands:
            for render_type, counts in hand.get('textures', {}).items():
                invalid_native += counts.get('invalidVertices', 0)
                texture = texture_name(render_type)
                if texture.startswith(ACCESSORY_NAMESPACES):
                    native_textures[texture] = native_textures.get(texture, 0) + counts['vertices']
        native_vertices = sum(native_textures.values())

        inventory = capture.get('actualAccessoryInventory', [])
        slots = [slot for slot in inventory if slot.get('slot') in HAND_SLOTS]
        check(bool(slots), 'Missing hand-slot inventory witness: ' + label)
        visible_items = []
        items_by_side = {'RIGHT': [], 'LEFT': []}
        cosmetics_by_side = {'RIGHT': False, 'LEFT': False}
        for slot in slots:
            if slot.get('visible') is not True:
                continue
            item = (slot.get('cosmetic') if slot.get('cosmeticCount', 0) > 0
                    else slot.get('normal') if slot.get('normalCount', 0) > 0 else None)
            if item and item != 'minecraft:air':
                visible_items.append(item)
                # Artifacts GloveArtifactRenderer.render selects MAIN_HAND for
                # even slots and OFF_HAND for odd slots, relative to dominance.
                main_side = capture.get('physicalMainArm', capture['view']['arm'])
                opposite_side = 'LEFT' if main_side == 'RIGHT' else 'RIGHT'
                sides = ('RIGHT', 'LEFT') if slot['slot'] == 'aether:gloves_slot' else (
                    main_side if slot['index'] % 2 == 0 else opposite_side,)
                for side in sides:
                    items_by_side[side].append(item)
                    cosmetics_by_side[side] |= slot.get('cosmeticCount', 0) > 0

        def selected_items_present(textures, kind, items=None):
            for item in visible_items if items is None else items:
                namespace, item_path = item.split(':', 1)
                check(any(count > 0 and texture.startswith(namespace + ':')
                          and item_path in texture for texture, count in textures.items()),
                      kind + ' missing selected ' + item + ': ' + label)

        check(invalid_native == 0, 'Nonfinite native hand geometry: ' + label)
        check(physical.get('invalidVertices') == 0, 'Nonfinite/missing body geometry witness: ' + label)
        owned = set()
        body_geometry = physical.get('bodyArmGeometry', {}).get('draws', {})
        if gun_frame and mask == 'ownership':
            coverage['maskedGunFrames'] += 1
            owned = {hand.get('physicalArm') for hand in hands}
            check(None not in owned and owned <= {'LEFT', 'RIGHT'}, 'Missing actual native hand identity: ' + label)
            occupancy = capture.get('nativeGunOccupancy')
            single_arm_override = (raw.get('oneHandedSixShooter') is True
                                   and capture['view']['gun'] == 'irons_artifice:six_shooter')
            expected_owned = ({capture.get('physicalMainArm')} if occupancy == 'MAINHAND' or single_arm_override
                              else {'LEFT', 'RIGHT'})
            check(occupancy in ('MAINHAND', 'BOTH'), 'Missing native occupancy witness: ' + label)
            check(owned == expected_owned, 'Gun drew the wrong physical arms: ' + label)
            free = {'LEFT', 'RIGHT'} - owned
            coverage['visibleNativeAccessoryFrames' if any(items_by_side.get(side) for side in owned)
                     else 'hiddenNativeAccessoryFrames'] += 1
            if free:
                coverage['freeBodyArmFrames'] += 1
                coverage['rightNativeOnlyFrames' if owned == {'RIGHT'} else 'leftNativeOnlyFrames'] += 1
            else:
                coverage['bothNativeArmFrames'] += 1
            check(bool(body_geometry), 'Missing per-part body vertex witness: ' + label)
            decisions = [e for e in physical.get('bodyArmDecisions', []) if e.get('event') == 'open_after']
            check(bool(decisions), 'Missing body scope decision: ' + label)
            for decision in decisions:
                check(decision.get('rightMasked') == ('RIGHT' in owned)
                      and decision.get('leftMasked') == ('LEFT' in owned), 'Body mask differs from actual native sides: ' + label)
            for side in ('RIGHT', 'LEFT'):
                skin = [d for d in body_geometry.values() if d.get('side') == side and d.get('category') == 'skin']
                sleeves = [d for d in body_geometry.values() if d.get('side') == side and d.get('category') == 'sleeve']
                worn = [d for d in body_geometry.values() if d.get('side') == side
                        and d.get('category', '').startswith(('accessory/normal/hand/', 'accessory/normal/aether:gloves_slot/'))]
                skin_count = sum(d['vertices'] for d in skin)
                sleeve_count = sum(d['vertices'] for d in sleeves)
                worn_count = sum(d['vertices'] for d in worn)
                side_textures = {}
                for hand in hands:
                    if hand.get('physicalArm') != side:
                        continue
                    for render_type, counts in hand['textures'].items():
                        texture = texture_name(render_type)
                        if texture.startswith(ACCESSORY_NAMESPACES):
                            side_textures[texture] = side_textures.get(texture, 0) + counts['vertices']
                if side in owned:
                    check(skin_count == 0 and sleeve_count == 0 and worn_count == 0,
                          'Native-owned body arm-root/sleeve/accessory leaked on ' + side + ': ' + label)
                    if items_by_side[side]:
                        coverage['visibleNativeAccessoryArmChecks'] += 1
                        selected_items_present(side_textures, 'Native ' + side, items_by_side[side])
                        check(all(any(texture.startswith(item.split(':', 1)[0] + ':')
                                      and item.split(':', 1)[1] in texture for item in items_by_side[side])
                                  for texture, count in side_textures.items() if count > 0),
                              'Unselected/functional native accessory leaked through selected cosmetic: ' + label)
                    else:
                        coverage['hiddenNativeAccessoryArmChecks'] += 1
                        check(sum(side_textures.values()) == 0, 'Hidden native accessory leaked: ' + label)
                else:
                    check(skin_count > 0, 'Free body arm-root geometry missing on ' + side + ': ' + label)
                    check(all(max(abs(n) for n in d.get('bounds', [9999])) < 32 for d in skin if d['vertices'] > 0),
                          'Free body arm-root remains displaced offscreen: ' + label)
                    if items_by_side[side]:
                        if cosmetics_by_side[side]:
                            coverage['cosmeticFreeArmFrames'] += 1
                        check(worn_count > 0, 'Free body accessory missing on ' + side + ': ' + label)
                        for item in items_by_side[side]:
                            check(any(item in d['category'] and d['vertices'] > 0 for d in worn),
                                  'Free body selected accessory missing ' + item + ': ' + label)
                        check(all(any(item in d['category'] for item in items_by_side[side]) for d in worn if d['vertices'] > 0),
                              'Unselected/functional free accessory leaked through selected cosmetic: ' + label)
                    else:
                        coverage['hiddenFreeArmFrames'] += 1
                        check(worn_count == 0, 'Hidden free-arm accessory leaked: ' + label)
                check(all(d.get('invalidVertices') == 0 for d in skin + sleeves + worn), 'Nonfinite body arm vertices: ' + label)
            for hand in hands:
                check(sum(counts['vertices'] for texture, counts in hand['textures'].items()
                          if 'minecraft:textures/entity/player/' in texture) > 0,
                      'Native gun hand skin disappeared: ' + label)
        elif gun_frame:
            coverage['maskedGunFrames'] += 1
            if mask == 'expected':
                check(body_vertices == 0,
                      'Masked body hand emitted ' + str(body_vertices) + ' vertices: ' + label)
            if visible_items:
                coverage['visibleNativeAccessoryFrames'] += 1
                check(native_vertices > 0, 'Native gun accessory missing: ' + label)
                selected_items_present(native_textures, 'Native gun hand')
            else:
                coverage['hiddenNativeAccessoryFrames'] += 1
                check(native_vertices == 0, 'Hidden native hand accessory emitted geometry: ' + label)
        else:
            key = 'emptyHandRecoveryFrames' if recovery else 'thirdPersonControlFrames'
            coverage[key] += 1
            if recovery:
                check(len(hands) == 0, 'Native gun hands persisted after switching away: ' + label)
                if mask == 'ownership':
                    for side in ('LEFT', 'RIGHT'):
                        check(sum(d['vertices'] for d in body_geometry.values() if d.get('side') == side and d.get('category') == 'skin') > 0,
                              'Empty-hand body arm-root did not recover on ' + side + ': ' + label)
            if third_person and mask == 'ownership':
                check(all(not d.get('currentOwned') for d in physical.get('bodyArmDecisions', []) if d.get('event') == 'open_after'),
                      'First-person body mask leaked into third person: ' + label)
            if visible_items:
                check(body_vertices > 0, 'Body hand accessory missing in control: ' + label)
                body_textures = {}
                for render_type, count in body.items():
                    texture = texture_name(render_type)
                    body_textures[texture] = body_textures.get(texture, 0) + count
                selected_items_present(body_textures, 'Body control')
            else:
                check(body_vertices == 0, 'Hidden body hand accessory emitted geometry: ' + label)

        screenshot = path.parent / label
        check(screenshot.is_file(), 'Missing original screenshot: ' + label)
        frames.append(dict(case=capture['case'], name=capture['name'], screenshot=label,
                           screenshotSha256=hashlib.sha256(screenshot.read_bytes()).hexdigest()
                           if screenshot.is_file() else None,
                           actualCameraType=camera, cameraPitch=capture.get('cameraPitch'),
                           scope='gun-mask' if gun_frame else 'empty-hand' if recovery else 'third-person',
                           visibleHandItems=visible_items, nativeHands=len(hands),
                           nativePhysicalArms=sorted(s for s in owned if s), nativeOccupancy=capture.get('nativeGunOccupancy'),
                           bodyArmGeometry=body_geometry,
                           nativeAccessoryVertices=native_vertices, nativeAccessoryTextures=native_textures,
                           bodyHandVertices=body_vertices,
                           bodyHandRoutes={key.split('/RenderType')[0] + '/' + texture_name(key): count
                                           for key, count in body.items()},
                           bodyHandBounds={key.split('/RenderType')[0] + '/' + texture_name(key): bounds
                                           for key, bounds in physical.get('bounds', {}).items()
                                           if key.startswith(BODY_PREFIXES)}))

    check(coverage['maskedGunFrames'] > 0, 'No stable native first-person gun-hand frames checked')
    check(coverage['visibleNativeAccessoryFrames'] > 0, 'No visible native hand accessory coverage')
    # Both fixture modes switch away after firing. Controls must be present so a
    # global glove suppression cannot satisfy the zero-body-geometry assertion.
    check(coverage['emptyHandRecoveryFrames'] > 0, 'No empty-hand recovery control checked')
    if mask == 'ownership':
        check(coverage['freeBodyArmFrames'] > 0, 'No free body-arm evidence')
        check(coverage['bothNativeArmFrames'] > 0, 'No two-native-arm control')
        check(coverage['stableFirstPersonFramesWithoutNativeHands'] == 0, 'Native gun hands absent from stable gun frames')
        if raw.get('freeControlsMode'):
            for key in ('hiddenFreeArmFrames', 'cosmeticFreeArmFrames', 'rightNativeOnlyFrames', 'leftNativeOnlyFrames'):
                check(coverage[key] > 0, 'Missing supplemental free-hand coverage: ' + key)
        elif raw.get('freeArmMode'):
            for key in ('rightNativeOnlyFrames', 'leftNativeOnlyFrames', 'thirdPersonControlFrames'):
                check(coverage[key] > 0, 'Missing ownership coverage: ' + key)
    return dict(source=str(path.resolve()), reportSha256=hashlib.sha256(raw_bytes).hexdigest(),
                label=raw.get('label'), freeControlsMode=raw.get('freeControlsMode', False), coverage=coverage, frames=frames,
                checks=dict(passed=not failures, failures=failures, mask=mask,
                            scope='Stable actual FIRST_PERSON native gun hands; ownership mode checks body arm-root geometry (including nested sleeve children) and selected hand accessories by physical arm. Bare free-arm visibility also requires original-image review.',
                            additionalFreeHandControlsRequired=mask == 'ownership' and raw.get('freeArmMode', False) and not raw.get('freeControlsMode', False),
                            visualAlignment='Requires original-image review. Observe mode does not assert a body mask or visible duplication.'))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('report', type=Path)
    parser.add_argument('--mask', choices=('expected', 'ownership', 'observe'), default='observe',
                        help='ownership follows actual native sides; expected asserts historical zero-body mask; observe permits offscreen geometry')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--supplement', type=Path, help='Combine the 13-context run with the separate freecontrols report and require both free-hand visibility controls')
    args = parser.parse_args()
    result = verify(args.report, args.mask)
    if args.supplement:
        supplemental = verify(args.supplement, args.mask)
        result['supplement'] = supplemental
        combined = {key: result['coverage'][key] + supplemental['coverage'][key] for key in result['coverage']}
        result['combinedCoverage'] = combined
        result['checks']['failures'] += ['Supplement: ' + message for message in supplemental['checks']['failures']]
        if not supplemental['freeControlsMode']:
            result['checks']['failures'].append('Supplement was not the actual freecontrols fixture')
        for key in ('hiddenFreeArmFrames', 'cosmeticFreeArmFrames', 'thirdPersonControlFrames'):
            if combined[key] == 0:
                result['checks']['failures'].append('Combined ownership coverage missing: ' + key)
        result['checks']['additionalFreeHandControlsRequired'] = False
        result['checks']['passed'] = not result['checks']['failures']
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(dict(checks=result['checks'], coverage=result['coverage'])))
    raise SystemExit(0 if result['checks']['passed'] else 1)
