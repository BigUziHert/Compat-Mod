"""Export canonical renderer sweeps and live-camera evidence without overstating it."""
import csv
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RUNS = ROOT / '.local/client/emfattachments-tests'
OUT = ROOT / 'verification'
OUT.mkdir(exist_ok=True)
ARMOR_UI_PROXIES = {'accessories:head', 'accessories:chest', 'accessories:legs', 'accessories:feet'}
BASE_RUNS = {'baseline-firstperson', 'fixed-without-firstperson'}


def canonical(label):
    return label in BASE_RUNS or label.startswith(('final-', 'replacement-'))


def write_json(path, data):
    path.write_text(json.dumps(data, indent=2), encoding='utf-8')


def total(rows, field):
    return sum(r.get(field, 0) for r in rows)


summaries = []
live_summaries = []
combat_summaries = []
for path in sorted(RUNS.glob('*/report.json')):
    if not canonical(path.parent.name):
        continue
    data = json.loads(path.read_text(encoding='utf-8'))
    if 'results' not in data:
        continue
    # Early baseline included armor GUI aliases; the true equipment slots were
    # already tested separately. Remove only those synthetic duplicate cases.
    rows = [dict(r) for r in data['results'] if r['slot'] not in ARMOR_UI_PROXIES]
    for row in rows:
        row.setdefault('variant', 'default')
        row.setdefault('playerSkinModel', data.get('playerSkinModel', 'not recorded'))
        if row.get('flowerAnchorChecks', 0) > 0:
            # Installed Bumblezone native flower head is six cubes (24 vertices each).
            # A generic humanoid fallback has only one cube; pose/tint alone miss it.
            row['expectedFlowerVertices'] = 144
            row['flowerGeometryPass'] = row.get('vertices', 0) == 144
        if row.get('item') == 'supplementaries:quiver' and 'quiverLayerCalls' in row:
            # Native quiver_in_hand has 16 quads. Its own render layer is separate
            # from Accessories' deliberately empty renderer for this item.
            row['expectedQuiverVertices'] = 64
            row['quiverDrawPass'] = (row.get('quiverLayerCalls') == 1
                                    and row.get('quiverRenderCalls') == 1
                                    and row.get('quiverVertices') == 64
                                    and row.get('quiverInvalidVertices') == 0
                                    and row.get('quiverBodyAnchorCalls') == 1
                                    and (row.get('quiverMode') != 'THIGH'
                                         or row.get('quiverLegAnchorCalls') == 1))
    summary = dict(
        kind='renderer-sweep', label=data['label'], finished=data['finished'],
        view=data.get('view', 'front'), uniqueItems=len({r['item'] for r in rows}),
        itemSlotCombinations=len({(r['item'], r['slot'], r['slotIndex']) for r in rows}),
        # A dyed variant is a separate test case even though its registry ID is unchanged.
        itemSlotCases=len({(r['item'], r['slot'], r['slotIndex'], r['variant']) for r in rows}),
        poseRenders=len(rows), incompletePoseRenders=sum(not r.get('completed', False) for r in rows),
        exceptions=sum(bool(r.get('exception')) for r in rows),
        nonFiniteGeometryCases=sum(r.get('invalidVertices', 0) > 0 for r in rows),
        parentPoseMutationCases=sum(r.get('mutatedParts', 0) > 0 for r in rows),
        accessoryRendererCalls=total(rows, 'rendererCalls'),
        calledAccessoryWithoutGeometry=sum(r.get('rendererCalls', 0) > 0 and r.get('vertices', 0) == 0 for r in rows),
        gogglesAnchorChecks=total(rows, 'gogglesAnchorChecks'),
        gogglesAnchorFailures=total(rows, 'gogglesAnchorFailures'),
        torsoAnchorChecks=total(rows, 'torsoAnchorChecks'),
        torsoAnchorFailures=total(rows, 'torsoAnchorFailures'),
        flowerAnchorChecks=total(rows, 'flowerAnchorChecks'),
        flowerAnchorFailures=total(rows, 'flowerAnchorFailures'),
        flowerColorChecks=total(rows, 'flowerColorChecks'),
        flowerColorFailures=total(rows, 'flowerColorFailures'),
        flowerGeometryChecks=sum(r.get('flowerAnchorChecks', 0) > 0 for r in rows),
        flowerGeometryFailures=sum(r.get('flowerAnchorChecks', 0) > 0 and not r.get('flowerGeometryPass', False) for r in rows),
        dyeTintChecks=sum(r.get('expectedTint') is not None for r in rows),
        dyeTintFailures=sum(r.get('expectedTint') is not None and not r.get('tintObserved', False) for r in rows),
        quiverDrawChecks=sum('quiverDrawPass' in r for r in rows),
        quiverDrawFailures=sum('quiverDrawPass' in r and not r['quiverDrawPass'] for r in rows),
        quiverVertices=total(rows, 'quiverVertices'),
        firstPersonProbe=data.get('firstPersonProbe'),
        accessoryScopeStatus=(data.get('firstPersonProbe') or {}).get('accessoryScope', {}).get('status', 'not run'),
        accessoryScopeCases=(data.get('firstPersonProbe') or {}).get('accessoryScope', {}).get('caseCount', 0),
        accessoryScopeFailures=len((data.get('firstPersonProbe') or {}).get('accessoryScope', {}).get('failures', [])),
        nativeCuriosHookCases=(data.get('firstPersonProbe') or {}).get('accessoryScope', {}).get('nativeCuriosHookCases', 0),
        combatProbeStatus=(data.get('firstPersonProbe') or {}).get('combat', {}).get('status', 'not run'),
        skinModels=sorted({r['playerSkinModel'] for r in rows}),
        headImplementations=sorted({r.get('headPartClass', '') for r in rows}),
        discoveryErrors=data.get('discoveryErrors', []), resourcePacks=data.get('resourcePacks', []),
        screenshotCount=data.get('screenshots', 0),
        scope=data.get('scope', ''),
        evidence=str(path.relative_to(ROOT)).replace('\\', '/'),
    )
    summaries.append(summary)
    keys = ['item', 'variant', 'slot', 'slotIndex', 'pose', 'renderer', 'rendererCalls', 'vertices',
            'completed', 'headPartClass', 'playerSkinModel', 'capeTexture',
            'gogglesAnchorChecks', 'gogglesAnchorFailures',
            'torsoAnchorChecks', 'torsoAnchorFailures', 'torsoAnchorMaxError',
            'flowerAnchorChecks', 'flowerAnchorFailures', 'flowerColorChecks', 'flowerColorFailures',
            'expectedFlowerColor', 'actualFlowerColor', 'expectedFlowerVertices', 'flowerGeometryPass', 'expectedTint', 'tintObserved', 'exception']
    keys += ['quiverMode', 'quiverLayerCalls', 'quiverRenderCalls', 'quiverVertices',
             'quiverInvalidVertices', 'quiverBodyAnchorCalls', 'quiverLegAnchorCalls',
             'expectedQuiverVertices', 'quiverDrawPass']
    with (OUT / (path.parent.name + '.csv')).open('w', encoding='utf-8', newline='') as file:
        writer = csv.DictWriter(file, fieldnames=keys, extrasaction='ignore')
        writer.writeheader()
        writer.writerows(rows)
    scope_cases = (data.get('firstPersonProbe') or {}).get('accessoryScope', {}).get('cases', [])
    if scope_cases:
        scope_keys = ['item', 'slot', 'cosmetic', 'mode', 'expectedHidden', 'vertices',
                      'nativeCuriosHookSlot', 'nativeCuriosContextVisible', 'nativeCuriosHookVertices',
                      'nativeCuriosHookStatus', 'inventoryUnchanged', 'modelUnchanged', 'status', 'error']
        with (OUT / (path.parent.name + '-scope.csv')).open('w', encoding='utf-8', newline='') as file:
            writer = csv.DictWriter(file, fieldnames=scope_keys, extrasaction='ignore')
            writer.writeheader()
            writer.writerows(scope_cases)

for path in sorted(RUNS.glob('*/live-report.json')):
    if not canonical(path.parent.name):
        continue
    data = json.loads(path.read_text(encoding='utf-8'))
    # Preserve stage-level camera/equipment and restoration evidence verbatim.
    write_json(OUT / (path.parent.name + '-live-report.json'), data)
    captures = data.get('captures', [])
    live_summaries.append(dict(
        kind='live-camera', label=path.parent.name, status=data.get('status'),
        detail=data.get('detail'), capturedStages=len(captures),
        activeUseCaptures=sum(bool(c.get('usingItem')) for c in captures),
        progressedUseCaptures=sum(bool(c.get('useProgressed')) for c in captures),
        neaSettingsRestored=data.get('neaSettingsRestored'),
        useKeyRestored=data.get('useKeyRestored'),
        screenshots=[c.get('file', c.get('screenshot')) for c in captures],
        evidence=str(path.relative_to(ROOT)).replace('\\', '/'),
        note='Captured is not a visual pass; review the referenced screenshots.'
    ))

for path in sorted(RUNS.glob('*/combat-report.json')):
    if not canonical(path.parent.name):
        continue
    data = json.loads(path.read_text(encoding='utf-8'))
    write_json(OUT / (path.parent.name + '-combat-report.json'), data)
    captures = data.get('captures', [])
    combat_summaries.append(dict(
        kind='live-combat', label=path.parent.name, status=data.get('status'),
        capturedStages=len(captures), ownedFrames=data.get('ownedFrames', 0),
        correctSingleBodyAndWeaponFrames=data.get('correctSingleBodyAndWeaponFrames', 0),
        violations=len(data.get('violations', [])), settingsRestored=data.get('settingsRestored'),
        evidence=str(path.relative_to(ROOT)).replace('\\', '/'),
        note='Renderer counters and screenshot visual review are separate evidence.'
    ))

write_json(OUT / 'summary.json', summaries)
write_json(OUT / 'live-summary.json', live_summaries)
write_json(OUT / 'combat-summary.json', combat_summaries)
csv_keys = ['label', 'finished', 'view', 'skinModels', 'uniqueItems', 'itemSlotCombinations',
            'itemSlotCases', 'poseRenders', 'incompletePoseRenders', 'exceptions', 'nonFiniteGeometryCases',
            'parentPoseMutationCases', 'gogglesAnchorChecks', 'gogglesAnchorFailures',
            'torsoAnchorChecks', 'torsoAnchorFailures', 'flowerAnchorChecks', 'flowerAnchorFailures',
            'flowerColorChecks', 'flowerColorFailures', 'flowerGeometryChecks', 'flowerGeometryFailures', 'dyeTintChecks', 'dyeTintFailures',
            'quiverDrawChecks', 'quiverDrawFailures', 'quiverVertices',
            'accessoryScopeStatus', 'accessoryScopeCases', 'accessoryScopeFailures',
            'nativeCuriosHookCases', 'combatProbeStatus',
            'firstPersonProbeStatus', 'screenshotCount']
with (OUT / 'summary.csv').open('w', encoding='utf-8', newline='') as file:
    writer = csv.DictWriter(file, fieldnames=csv_keys, extrasaction='ignore')
    writer.writeheader()
    for summary in summaries:
        row = dict(summary)
        row['skinModels'] = ';'.join(row['skinModels'])
        row['firstPersonProbeStatus'] = (row.get('firstPersonProbe') or {}).get('status', 'not installed/tested')
        writer.writerow(row)
print(json.dumps({'sweeps': summaries, 'live': live_summaries, 'combat': combat_summaries}, indent=2))
