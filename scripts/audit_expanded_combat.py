"""Incremental native combat outcomes and full-frame review sheets; never grades images.

Example: python scripts/audit_expanded_combat.py --run .local/client/emfattachments-tests/expanded-fpm-combat-full-r4 --sheets
Without --run, reads full-combat attempts from both isolated clients. Re-running is safe
while the client is writing: malformed/incomplete JSON is skipped, never treated as PASS.
"""
import argparse
import csv
import hashlib
import json
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASES = [ROOT / '.local/client/emfattachments-tests', ROOT / '.local/without-firstperson/.local/client/emfattachments-tests']
FAILURES = {'CASE_FAILURE', 'RENDER_FAILURE', 'ACTION_FAILURE', 'ACTION_UNCONFIRMED', 'FAIL', 'UNMET_PRECONDITION'}
KEYS = ('item', 'kind', 'attackIndex', 'pitch', 'context', 'awakening', 'crouch')
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--run', action='append', help='Workspace-relative or absolute run directory; repeat for both clients')
parser.add_argument('--output', default='verification/expanded/combat-outcomes')
parser.add_argument('--sheets', action='store_true', help='Build incremental active/recovery full-frame pairs')
parser.add_argument('--sheet-output', default='.local/review/expanded-combat')
parser.add_argument('--from-case', type=int, default=1, help='One-based local run row to begin sheet generation')
parser.add_argument('--through-case', type=int, default=1000000)
args = parser.parse_args()
output = ROOT / args.output
output.mkdir(parents=True, exist_ok=True)
runs = [ROOT / p for p in args.run] if args.run else sorted(p for base in BASES for p in base.glob('*combat-full*') if p.is_dir())


def relative(path):
    try:
        return str(path.relative_to(ROOT))
    except ValueError:
        return str(path)


def classify(row):
    status = row.get('status')
    if not status:
        return 'PENDING', []
    if status in FAILURES or row.get('finalizationError') or row.get('rendererViolations', 0):
        return 'FAILED_OR_UNMET', [row.get('detail', ''), row.get('finalizationError', '')]
    kind = row.get('kind', '')
    signals = []
    if row.get('targetDamage', 0) > 0:
        signals.append('target_health_loss')
    if row.get('abilitySpawnedEntityTypes'):
        signals.append('ability_entity_spawn')
    if row.get('targetEffects'):
        signals.append('target_effect')
    if row.get('playerEffects'):
        signals.append('player_effect')
    if row.get('serverCooldownObserved'):
        signals.append('native_cooldown')
    if kind in ('melee', 'vanilla_hit'):
        if kind == 'melee' and not row.get('activeLayerObserved'):
            return 'ACTION_LAYER_UNCONFIRMED', signals
        return ('TARGET_HEALTH_LOSS_DURING_CASE' if row.get('targetDamage', 0) > 0 else 'NATIVE_ACTION_NO_TARGET_HEALTH_LOSS'), signals
    if kind.startswith('use'):
        # Azure Sea Shield and Black Steel Targe are vanilla ShieldItem subclasses.
        # Bulwark's separate charge activates only on crouched release. This
        # fixture does not attack the player, so ordinary guards cannot prove
        # defensive damage reduction even when their BLOCK pose is observed.
        ordinary_shield = row.get('item') in ('cataclysm:azure_sea_shield', 'cataclysm:black_steel_targe') or (
            row.get('item') == 'cataclysm:bulwark_of_the_flame' and not row.get('crouch'))
        if (ordinary_shield
                and row.get('serverUseObserved') and row.get('serverUseElapsed', 0) > 0
                and row.get('active', {}).get('neaProjectilePose', {}).get('useAnimation') == 'BLOCK'):
            return 'NATIVE_BLOCK_POSE_OBSERVED_DEFENSE_UNTESTED', signals
        if row.get('awakening') == 'locked' and row.get('abilityUnlocked') is False:
            return ('LOCKED_CONTROL_EFFECT_REQUIRES_REVIEW' if signals else 'LOCKED_CONTROL_NO_EFFECT_OBSERVED'), signals
        if signals:
            return ('COOLDOWN_ONLY_ABILITY_UNCONFIRMED' if signals == ['native_cooldown'] else 'CASE_EFFECT_SIGNAL_OBSERVED'), signals
        if row.get('serverUseObserved') and row.get('serverUseElapsed', 0) > 0:
            return 'NATIVE_USE_OBSERVED_EFFECT_UNCONFIRMED', signals
        return 'ABILITY_UNCONFIRMED', signals
    if kind == 'roll':
        return ('NATIVE_ROLL_OBSERVED' if row.get('rollObserved') else 'ROLL_UNCONFIRMED'), signals
    return 'CAPTURED_ONLY', signals


def inspect_row(row, index, data, directory):
    outcome, signals = classify(row)
    result = {k: row.get(k) for k in KEYS}
    result.update(run=directory.name, runRow=index + 1, catalogueRangeStart=data.get('selection', {}).get('start', 0),
                  firstPersonInstalled=data.get('firstPersonInstalled'), shrink=data.get('shrinkingArtifact', False),
                  rawStatus=row.get('status', 'PENDING'), outcome=outcome, effectSignals=signals,
                  targetDamage=row.get('targetDamage', 0), serverUseElapsed=row.get('serverUseElapsed', 0),
                  clientUseElapsed=row.get('clientUseElapsed', 0), activeUsePoseObserved=row.get('activeUsePoseObserved'),
                  nativeUseAnimation=row.get('active', {}).get('neaProjectilePose', {}).get('useAnimation'),
                  healthLossAttribution='Accumulated target health decrease during preparation plus action/recovery; no per-damage-source attribution in this report',
                  effectSignalScope='Preparation plus action/recovery; signals can include persistent native managers or world effects from earlier cases',
                  effectSignalAttribution='Unproven for the current weapon/action; not a confirmed specific ability outcome',
                  nativeAttackHitEvents=row.get('nativeAttackHitEvents'),
                  nativeActionDamageEvents=row.get('nativeActionDamageEvents'),
                  nativeActionDamageEventScope=row.get('nativeActionDamageEventScope'),
                  abilityOutcome=row.get('abilityOutcome'), hitOutcome=row.get('hitOutcome'),
                  manaAffordable=row.get('manaAffordable'), abilityUnlocked=row.get('abilityUnlocked'),
                  rendererViolations=row.get('rendererViolations', 0), frames=row.get('frames', 0),
                  zeroItemFrames=row.get('zeroItemFrames', 0), renderMaxima=row.get('maxRenderCounts', {}),
                  visualStatus='NOT_REVIEWED_BY_THIS_SCRIPT', images=[], missingImages=[], capturedMultiplicityFlags=[],
                  sourceReport=relative(directory / 'expanded-combat-report.json'),
                  reportModifiedNs=(directory / 'expanded-combat-report.json').stat().st_mtime_ns)
    result['betweenCaseCleanup'] = {k: v for k, v in row.items() if k.startswith('betweenCase')}
    required_cleanup = ('betweenCasePendingPoseDrained', 'betweenCaseServerSessionReleased', 'betweenCaseClientSeatReleased', 'betweenCaseClientVisibilityRestored')
    result['betweenCaseCleanupStatus'] = ('VERIFIED' if all(row.get(k) is True for k in required_cleanup)
                                        else 'FAILED' if any(row.get(k) is False for k in required_cleanup)
                                        else 'UNREPORTED_OR_PENDING')
    for phase in ('active', 'recovery'):
        shot = row.get(phase, {})
        filename = shot.get('screenshot')
        if filename:
            path = directory / filename
            image = dict(phase=phase, original=relative(path), exists=path.is_file(),
                         counts={k: shot.get(k) for k in ('bodyPasses', 'bodyItems', 'vanillaItems', 'otherItems')},
                         usingItem=shot.get('usingItem'), useElapsed=shot.get('useElapsed'),
                         attackActive=shot.get('attackActive'), vehicle=shot.get('vehicle'))
            if path.is_file():
                image['modifiedNs'] = path.stat().st_mtime_ns
            else:
                result['missingImages'].append(filename)
            result['images'].append(image)
            count = sum(shot.get(k, 0) for k in ('bodyItems', 'vanillaItems', 'otherItems'))
            held_upper_bound = 2 if row.get('context') in ('dual', 'shield') else 1
            if count > held_upper_bound:
                result['capturedMultiplicityFlags'].append(dict(phase=phase, drawnItems=count, contextHeldUpperBound=held_upper_bound))
        elif row.get('status') not in FAILURES and row.get('status'):
            result['missingImages'].append(phase + ' screenshot absent')
    result['useItemMultiplicityAutomaticallyAsserted'] = False if row.get('kind', '').startswith('use') else None
    return result


def sheets(directory, records):
    from PIL import Image, ImageDraw, ImageFont
    dest = ROOT / args.sheet_output / directory.name
    dest.mkdir(parents=True, exist_ok=True)
    font = ImageFont.truetype('C:/Windows/Fonts/consola.ttf', 14)
    small = ImageFont.truetype('C:/Windows/Fonts/consola.ttf', 12)
    old_path = dest / 'index.json'
    old = json.loads(old_path.read_text()) if old_path.exists() else {}
    old_pages = {p['page']: p for p in old.get('pages', [])}
    pages = []
    for start in range(0, len(records), 3):
        group = records[start:start + 3]
        if group[-1]['runRow'] < args.from_case or group[0]['runRow'] > args.through_case:
            continue
        if all(r['rawStatus'] == 'PENDING' for r in group):
            continue
        page = start // 3 + 1
        digest = hashlib.sha256(('layout2' + json.dumps(group, sort_keys=True)).encode()).hexdigest()
        filename = f'page-{page:04d}.png'
        page_entry = dict(page=page, file=relative(dest / filename), fingerprint=digest, cases=group)
        if old_pages.get(page, {}).get('fingerprint') != digest or not (dest / filename).exists():
            panel_w, panel_h, bar_h = 550, 380, 70
            sheet = Image.new('RGB', (panel_w * 2, (panel_h + bar_h) * len(group) + 26), '#17222d')
            draw = ImageDraw.Draw(sheet)
            draw.text((8, 5), directory.name + ' | entire active and recovery frames; originals indexed', font=small, fill='white')
            for j, row in enumerate(group):
                y = 26 + j * (panel_h + bar_h)
                draw.text((8, y + 2), f"{row['runRow']:04d} {row['item']}  {row['kind']}[{row['attackIndex']}] pitch={row['pitch']} {row['context']} {row['awakening']} crouch={row['crouch']}", font=font, fill='#aee8ff')
                draw.text((8, y + 23), f"{row['outcome']} | damage={row['targetDamage']} | effects={','.join(row['effectSignals'])}", font=small, fill='#ffe0aa')
                for col, phase in enumerate(('active', 'recovery')):
                    shot = next((s for s in row['images'] if s['phase'] == phase), None)
                    x = col * panel_w
                    counts = '/'.join(str(shot['counts'].get(k)) for k in ('bodyPasses','bodyItems','vanillaItems','otherItems')) if shot else ''
                    draw.text((x + 8, y + 44), phase + (f" using={shot['usingItem']} tick={shot['useElapsed']} B/I/V/O={counts}" if shot else ' absent'), font=small, fill='white')
                    if shot and shot['exists']:
                        with Image.open(ROOT / shot['original']) as im:
                            sheet.paste(im.convert('RGB').resize((panel_w, panel_h), Image.Resampling.LANCZOS), (x, y + bar_h))
            sheet.save(dest / filename)
        pages.append(page_entry)
    # Preserve disjoint prior page ranges when incrementally reviewing one chunk.
    merged = {p['page']: p for p in old.get('pages', [])}
    merged.update({p['page']: p for p in pages})
    old_path.write_text(json.dumps(dict(scope='Review aid only. No automatic visual grades; both panels show the entire frame resized. Inspect indexed originals for fine or clipped geometry.', run=directory.name, pages=sorted(merged.values(), key=lambda p: p['page'])), indent=2))
    return relative(dest)


run_results, all_rows, errors = [], [], []
for directory in runs:
    path = directory / 'expanded-combat-report.json'
    try:
        data = json.loads(path.read_text())
    except (OSError, json.JSONDecodeError) as error:
        errors.append(dict(report=relative(path), error=str(error)))
        continue
    records = [inspect_row(row, i, data, directory) for i, row in enumerate(data.get('cases', []))]
    all_rows.extend(records)
    summary = dict(run=directory.name, status=data.get('status'), expectedCases=data.get('plannedCases'), rawRows=len(records),
                   completedRows=sum(r['rawStatus'] != 'PENDING' for r in records), outcomes=dict(Counter(r['outcome'] for r in records)),
                   rendererViolations=sum(r['rendererViolations'] for r in records), missingImageRows=sum(bool(r['missingImages']) for r in records),
                   capturedMultiplicityReviewRows=sum(bool(r['capturedMultiplicityFlags']) for r in records),
                   betweenCaseCleanup=dict(Counter(r['betweenCaseCleanupStatus'] for r in records)),
                   restoration={k: v for k, v in data.items() if k.endswith('Restored') or k in ('cleanupReactionGraceExpired', 'nativeClientSeatReleased', 'nativeClientVisibilityRestored')},
                   sourceReport=relative(path))
    if args.sheets:
        summary['reviewSheets'] = sheets(directory, records)
    run_results.append(summary)
    (output / (directory.name + '.json')).write_text(json.dumps(dict(summary=summary, cases=records), indent=2))

key = lambda r: (r['firstPersonInstalled'], r['shrink'], *(r.get(k) for k in KEYS))
unique = {}
for row in all_rows:
    if row['rawStatus'] != 'PENDING':
        prior = unique.get(key(row))
        if prior is None or row['reportModifiedNs'] >= prior['reportModifiedNs']:
            unique[key(row)] = row
audit = dict(scope='Numerical outcome audit, not visual approval. CAPTURED means timeline evidence; a cooldown alone is not proof of a specific ability. Target health loss is accumulated from preparation through action/recovery and is not damage-source attribution. Locked controls, native use observations, uncertain outcomes and renderer errors remain separate.',
             runs=run_results, readErrors=errors, completedUniqueCaseKeys=len(unique), uniqueOutcomes=dict(Counter(r['outcome'] for r in unique.values())),
             caveats=['Per-ID special secondary, charge, kill, biome/weather, ally and random branches require separate native evidence.',
                      'Current Java checks strict weapon multiplicity for active melee. Use-case counts are captured but do not have equivalent all-frame multiplicity assertions.',
                      'The full-combat fixture neither snapshots/restores weather nor records its state per case. Rain/thunder and incidental effects can contribute to target health loss; weapon-specific causation is unproven without separate projectile-hit or native damage-source evidence.',
                      'Native ability managers and world hazards can outlive a case and affect its successor. Observed Brimstone firestorm carryover reaches the locked Brimstone and next Caelestis cases. A locked-control effect signal is not evidence of an unlock bypass; it requires an isolated replay after native manager expiration and fresh target creation.',
                      'This script never assigns an alignment or screenshot PASS.'], cases=list(unique.values()))
(output / 'audit.json').write_text(json.dumps(audit, indent=2))
fields = ['run', 'runRow', *KEYS, 'firstPersonInstalled', 'shrink', 'rawStatus', 'outcome', 'targetDamage', 'serverUseElapsed', 'activeUsePoseObserved', 'abilityOutcome', 'hitOutcome', 'rendererViolations', 'zeroItemFrames', 'sourceReport']
with (output / 'cases.csv').open('w', newline='', encoding='utf-8') as handle:
    writer = csv.DictWriter(handle, fieldnames=fields, extrasaction='ignore'); writer.writeheader(); writer.writerows(all_rows)
print(json.dumps(dict(runs=run_results, readErrors=errors, completedUniqueCaseKeys=len(unique), uniqueOutcomes=audit['uniqueOutcomes']), indent=2))
