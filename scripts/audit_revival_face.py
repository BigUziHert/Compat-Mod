"""Verify immutable 1.2.2 Revival face evidence; never launch or modify Minecraft.

Exit 0 means integrity/recorded assertions pass, 1 means mismatch, and 2 means
required independent reviews are still pending. Visual limitations are retained.
"""
import argparse
import hashlib
import json
import re
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = '569cbb680399c56d2a6ead3aadec41084c8830685da5cdb6baf9ecf3dba4b470'
HARNESS = '93a64a9992e469627ff1eabf3b4c02866a726baf59e6973836ca57890a3529c3'
FINAL = [
    ('fpm-fixed-r3', 'revival-face-fpm-fixed-r3', True, 'face'),
    ('without-fpm-fixed-r3', 'revival-face-without-fpm-fixed-r3', False, 'face'),
    ('fpm-claws-r3', 'revival-face-fpm-claws-r3', True, 'claws'),
    ('without-fpm-claws-r3', 'revival-face-without-fpm-claws-r3', False, 'claws'),
]
FACE_RESTORE = ['serverInventoryRestored', 'serverAccessoriesRestored', 'serverDownedCleared',
                'serverSessionReleased', 'clientRevivalStateRestored', 'cameraAndInputRestored', 'finished']
CLAW_RESTORE = ['inputRestored', 'nativeClientSeatReleased', 'nativeClientVisibilityRestored',
                'nativeCameraConfigRestored', 'nativeServerSessionReleased', 'serverInventoryRestored',
                'cleanupReactionGraceExpired']


def path(value):
    p = Path(value)
    return p if p.is_absolute() else ROOT / p


def digest(p):
    h = hashlib.sha256()
    with p.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def load(p):
    return json.loads(p.read_text(encoding='utf-8-sig'))


class Audit:
    def __init__(self):
        self.errors = []
        self.checked = {}

    def check(self, condition, message):
        if not condition:
            self.errors.append(message)

    def bind(self, p, expected=None):
        p = path(p)
        self.check(p.is_file(), f'Missing file: {p}')
        if not p.is_file():
            return {'path': str(p), 'missing': True}
        actual = digest(p)
        if expected is not None:
            self.check(actual == expected.lower(), f'Hash mismatch: {p}')
        item = {'path': p.relative_to(ROOT).as_posix(), 'sha256': actual}
        self.checked[str(p)] = item
        return item

    def references(self, node):
        """Verify every explicit report/manifest/image/source hash in a review."""
        if isinstance(node, list):
            for value in node:
                self.references(value)
        elif isinstance(node, dict):
            for key, value in node.items():
                if key.endswith('Sha256') and isinstance(value, str):
                    source_key = key[:-6]
                    if source_key in node and isinstance(node[source_key], str):
                        self.bind(node[source_key], value)
                self.references(value)
            if 'sha256' in node:
                found = next((node[k] for k in ('image', 'file', 'path') if isinstance(node.get(k), str)), None)
                if found:
                    self.bind(found, node['sha256'])

    def manifest(self, p, expected_mode=None, final=False):
        self.bind(p)
        data = load(p)
        if expected_mode is not None:
            self.check(data['firstpersonInstalled'] is expected_mode, f'{p.name}: physical FirstPerson mode')
        self.check(data['rejectedCompanionAbsent'] is True, f'{p.name}: rejected companion present')
        for name, expected in data['artifacts'].items():
            archive = data.get('archivedArtifacts', {}).get(name)
            self.check(bool(archive), f'{p.name}: missing immutable archive {name}')
            if archive:
                self.bind(archive, expected)
        if final:
            self.check(data['artifacts'] == {
                'emf-attachments-compat-1.21.1-1.2.2.jar': MAIN,
                'emf-attachments-compat-1.21.1-1.2.2-test-harness.jar': HARNESS,
            }, f'{p.name}: final artifacts differ')
        return data


def texture_counts(group):
    counts = Counter()
    for key, count in group['geometry']['vertices'].items():
        texture = re.search(r'texture\[Optional\[([^]]+)\]', key)
        # Ignore JVM lambda identity in RenderType descriptions, retain exact
        # helper/source/physical-part and texture identity plus vertex counts.
        normalized = key.split('/RenderType', 1)[0] + '/' + (texture.group(1) if texture else key)
        counts[normalized] += count
    return dict(counts)


def audit_face(audit, raw, review, baseline, label):
    audit.check(raw['status'] == 'CAPTURED' and raw['baseline'] is False, f'{label}: final lifecycle incomplete')
    audit.check(raw['behaviorFailures'] == [], f'{label}: native behavior failures')
    audit.check(len(raw['captures']) == raw['plannedCaptureCount'] == 11, f'{label}: expected11 captures')
    audit.check(all(raw[k] is True for k in FACE_RESTORE), f'{label}: cleanup incomplete')
    helper = raw['nativeHelperProbe']
    groups = helper['cases']
    audit.check(helper['status'] == 'PASS' and helper['failures'] == [] and helper['observedRegressionCount'] == 0,
                f'{label}: helper failures')
    audit.check(helper['clientEquipmentAndRenderGlobalsRestored'] is True, f'{label}: helper cleanup')
    audit.check(len(groups) == 32, f'{label}: expected32 helper groups')
    scenarios = {'face_visible', 'face_hidden', 'cosmetic_whoopee_visible', 'cosmetic_whoopee_hidden',
                 'distinct_cosmetic_visible', 'hat_visible', 'hand_control', 'feet_control'}
    expected_keys = {(s, source, route) for s in scenarios for source in ('stored', 'live')
                     for route in ('accessories_then_curios', 'curios_only')}
    key = lambda r: (r['scenario'], r['source'], r['path'])
    audit.check({key(r) for r in groups} == expected_keys, f'{label}: helper matrix gaps')
    originals = {key(r): r for r in baseline['nativeHelperProbe']['cases']}
    classes = Counter()
    for row in groups:
        audit.check(row['expectedBehaviorPassed'] is True and row['geometry']['invalidVertices'] == 0,
                    f'{label}: helper assertion {key(row)}')
        if row['scenario'] in ('hand_control', 'feet_control'):
            classes['unchangedNativeLimbControls'] += 1
            old = originals[key(row)]
            audit.check(row['effectiveTextureVerticesByPart'] == old['effectiveTextureVerticesByPart']
                        and texture_counts(row) == texture_counts(old), f'{label}: limb control changed {key(row)}')
        elif row['path'] == 'curios_only' and 'cosmetic' in row['scenario']:
            classes['nativeCosmeticFallbackDiagnosticsNotNewFeaturePasses'] += 1
        else:
            classes['assertedHeadGeometry'] += 1
            expected_head = 0 if not row['visible'] else 72 if row['scenario'] == 'distinct_cosmetic_visible' else 48
            vertices = row['effectiveTextureVerticesByPart']
            audit.check(vertices['HEAD'] == expected_head and all(v == 0 for p, v in vertices.items() if p != 'HEAD'),
                        f'{label}: expected head-only geometry {key(row)}')
    audit.check(dict(classes) == {'assertedHeadGeometry': 18, 'nativeCosmeticFallbackDiagnosticsNotNewFeaturePasses': 6,
                                 'unchangedNativeLimbControls': 8}, f'{label}: helper classification totals')
    downed = revived = 0
    for row in raw['captures']:
        server = row['server']
        audit.check(row['nativeGeometry']['invalidVertices'] == 0, f'{label}: nonfinite native geometry')
        if row['stage'].endswith('_revived'):
            revived += 1
            audit.check(not server['downed'] and not row['clientDowned'] and not server['nativeSession']
                        and server['seat'] == '', f'{label}: failed native revive {row["stage"]}')
        else:
            downed += 1
            audit.check(server['downed'] and row['clientDowned'] and server['nativeSession']
                        and server['seat'] == 'sable_player_ragdoll:ragdoll_seat'
                        and row['expectedBehaviorPassed'] is True, f'{label}: native down assertion {row["stage"]}')
            vertices = row['nativeGeometry']['vertices']
            if not server['renderFlag']:
                audit.check(row['whoopeeVertices'] == row['snorkelVertices'] == 0, f'{label}: hidden native geometry')
            else:
                texture = 'snorkel.png' if row['stage'] == 'distinct_cosmetic_down' else 'whoopee_cushion.png'
                expected = 72 if texture == 'snorkel.png' else 48
                relevant = {k: v for k, v in vertices.items() if texture in k}
                audit.check(bool(relevant) and all(k.startswith('accessories/stored/HEAD/') for k in relevant),
                            f'{label}: native duplicate/wrong-part {row["stage"]}')
                calls = row['nativeGeometry']['calls'].get('accessories/stored/HEAD', 0)
                audit.check(calls > 0 and sum(relevant.values()) == expected * calls,
                            f'{label}: native vertices per helper {row["stage"]}')
                if texture == 'snorkel.png':
                    audit.check(row['whoopeeVertices'] == 0, f'{label}: wrong functional item under cosmetic')
    audit.check(downed == 7 and revived == 4, f'{label}: down/revive coverage')
    audit.check(review['capturesReviewed'] == 11 and review['helperGroups'] == 32, f'{label}: manual review coverage')
    return {'captures': 11, 'nativeDownedViews': downed, 'nativeRevivedViews': revived, 'helperGroups': 32,
            'helperClassifications': dict(classes), 'cleanup': {k: raw[k] for k in FACE_RESTORE},
            'limits': review.get('limits', [])}


def audit_claws(audit, raw, review, label):
    audit.check(raw['status'] == 'CAPTURED' and raw['failures'] == [], f'{label}: claw runtime incomplete')
    audit.check(len(raw['captures']) == 4 and len(review['images']) == 4, f'{label}: claw captures/review')
    probe = raw['nativePairedClawControls']
    cases = probe['cases']
    audit.check(probe['status'] == 'PASS' and probe['inventoryUnchanged'] and probe['nativeRenderGlobalsRestored'],
                f'{label}: claw control status/cleanup')
    expected = {(skin, dominant, part, route) for skin in ('default', 'slim') for dominant in ('LEFT', 'RIGHT')
                for part in ('LEFT_ARM', 'RIGHT_ARM') for route in ('curios_fallback_only', 'accessories_then_curios')}
    audit.check(len(cases) == 16 and {(r['skin'], r['mainArm'], r['physicalPart'], r['path']) for r in cases} == expected,
                f'{label}:16 claw control coverage')
    audit.check(all(r['status'] == 'PASS' and r['expectedVertices'] == r['selectedVertices'] == 96
                    and r['oppositeVertices'] == 0 for r in cases), f'{label}: claw geometry failure')
    ledger = raw['bridgedAccessoryScopeControls']
    audit.check(ledger['status'] == 'PASS' and ledger['checkCount'] == len(ledger['checks']) == 61
                and all(r['passed'] for r in ledger['checks']) and ledger['inventoryUnchanged'], f'{label}: ledger controls')
    audit.check(all(raw[k] is True for k in CLAW_RESTORE), f'{label}: cleanup incomplete')
    audit.check(all(r['serverState']['nativeSession'] and r['nativeRagdollGeometry']['nonFiniteVertices'] == 0
                    for r in raw['captures']), f'{label}: actual native ragdoll or finite geometry')
    return {'captures': 4, 'nativeClawControls': 16, 'exactSelectedVertices': 96, 'oppositeVertices': 0,
            'ledgerChecks': 61, 'cleanup': {k: raw[k] for k in CLAW_RESTORE},
            'visualClassifications': dict(Counter(r['visualStatus'] for r in review['images'])),
            'limits': review.get('limitations', [])}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', default='verification/revival-face/final-evidence-integrity.json')
    args = parser.parse_args()
    out = path(args.output)
    required = [ROOT / 'verification/revival-face' / f'{name}-review.json' for name, *_ in FINAL]
    required.append(ROOT / 'verification/revival-face/baseline-r1-review.json')
    missing = [str(p.relative_to(ROOT)) for p in required if not p.exists()]
    if missing:
        result = {'status': 'PENDING_INDEPENDENT_REVIEWS', 'missing': missing, 'finalPass': False}
        out.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
        print(json.dumps(result, indent=2))
        return 2
    audit = Audit()
    build_path = ROOT / 'verification/build-1.2.2.json'
    build = load(build_path)
    audit.bind(build_path)
    audit.check((build['tests'], build['failures'], build['errors'], build['skipped']) == (46, 0, 0, 0), 'Build46 tests')
    audit.check(set(build['jars'].values()) == {MAIN, HARNESS}, 'Build final artifact identities')
    for file, expected in build['sourceSha256'].items():
        audit.bind(file, expected)
    xml_totals = Counter()
    xml_paths = sorted((ROOT / 'build/test-results/test').glob('TEST-*.xml'))
    for file in xml_paths:
        suite = ET.parse(file).getroot()
        xml_totals.update({k: int(suite.attrib.get(k, 0)) for k in ('tests', 'failures', 'errors', 'skipped')})
        audit.bind(file)
    audit.check(dict(xml_totals) == {'tests': 46, 'failures': 0, 'errors': 0, 'skipped': 0}, 'Current JUnit XML46 tests')
    baseline_review_path = required[-1]
    baseline_review = load(baseline_review_path)
    audit.bind(baseline_review_path)
    audit.references(baseline_review)
    baseline = load(path(baseline_review['rawReport']))
    audit.manifest(path(baseline_review['runManifest']), True)
    audit.check(baseline_review['status'] == 'CONFIRMED_BASELINE_REGRESSIONS_NOT_PASS', 'Baseline must remain failed')
    audit.check(baseline['baseline'] is True and len(baseline['behaviorFailures']) == 6
                and baseline['nativeHelperProbe']['observedRegressionCount'] == 11, 'Original baseline regressions preserved')
    finals = []
    for name, label, mode, kind in FINAL:
        review_path = ROOT / 'verification/revival-face' / f'{name}-review.json'
        review = load(review_path)
        audit.bind(review_path)
        audit.references(review)
        manifest_path = ROOT / 'verification/expanded-run-manifests' / f'{label}.json'
        manifest = audit.manifest(manifest_path, mode, final=True)
        audit.check(review['artifacts'] == manifest['artifacts'], f'{label}: review artifacts')
        report_path = path(manifest['runtime']) / 'emfattachments-tests' / label / ('revival-face-report.json' if kind == 'face' else 'ragdoll-report.json')
        raw = load(report_path)
        audit.bind(report_path, review['rawReportSha256'] if kind == 'face' else review['reportSha256'])
        audit.check(raw['firstPersonInstalled'] is mode and review['firstPersonInstalled'] is mode, f'{label}: physicalmode evidence')
        image_rows = review['cases'] if kind == 'face' else review['images']
        image_paths = {str(path(r['image'] if kind == 'face' else r['file']).resolve()) for r in image_rows}
        original_paths = {str((report_path.parent / r['file']).resolve()) for r in raw['captures']}
        audit.check(image_paths == original_paths, f'{label}: exact reviewed original image set')
        if kind == 'face':
            audit.check(all(r.get('manuallyViewed') is True for r in image_rows), f'{label}: manual image review')
            audit.check(review['status'] == 'ACCEPTED_WITH_BOUNDED_VISUAL_LIMITS', f'{label}: review acceptance')
            findings = audit_face(audit, raw, review, baseline, label)
        else:
            audit.check(review['status'] == 'PASS_NATIVE_GEOMETRY_CONTROLS_WITH_VISUAL_LIMITS', f'{label}: review acceptance')
            findings = audit_claws(audit, raw, review, label)
        finals.append({'label': label, 'firstPersonPhysicallyInstalled': mode, 'review': str(review_path.relative_to(ROOT)), **findings})
    rejected_path = ROOT / 'verification/revival-face/rejected-fixed-r1-access-error.json'
    rejected = load(rejected_path)
    audit.bind(rejected_path)
    audit.references(rejected)
    for row in rejected['runs']:
        audit.manifest(path(row['manifest']))
        audit.check(row['captureCount'] == 0 and row['result'] == 'REJECTED_RUNTIME_ACCESS_ERROR_NO_GAMEPLAY_PASS',
                    'Rejected invoker run must never be included as gameplay pass')
    preflight_path = ROOT / 'verification/revival-face/r2-preflight-note.json'
    preflight = load(preflight_path)
    audit.bind(preflight_path)
    audit.bind(ROOT / 'verification/revival-face/unconsumed-r2-trigger.txt')
    audit.manifest(ROOT / 'verification/expanded-run-manifests/revival-face-fpm-fixed-r2.json')
    audit.check(preflight['status'] == 'NOT_EXECUTED_NATIVE_DEATH_SCREEN', 'r2 must remain unexecuted preflight')
    for file in ('verification/build-1.2.2-rejected-r1.json', 'verification/build-1.2.2-preflight-r2.json'):
        audit.bind(file)
    result = {
        'status': 'PASS_EVIDENCE_INTEGRITY_WITH_PRESERVED_VISUAL_LIMITS' if not audit.errors else 'FAIL_EVIDENCE_INTEGRITY',
        'finalPass': not audit.errors, 'productionSha256': MAIN, 'testHarnessSha256': HARNESS,
        'auditScript': audit.bind(Path(__file__).resolve()),
        'build': {'record': str(build_path.relative_to(ROOT)), 'sourceFilesVerified': len(build['sourceSha256']),
                  'junitXmlSuitesVerified': len(xml_paths), 'junitTotals': dict(xml_totals)},
        'baseline': {'classification': 'CONFIRMED_REGRESSIONS_NOT_PASS', 'nativeBehaviorFailures': 6,
                     'helperRegressions': 11, 'reviewedImages': 11},
        'rejectedCandidate': {'classification': 'RUNTIME_MODULE_ACCESS_ERROR_NOT_PASS', 'runs': 2, 'captures': 0},
        'r2Preflight': {'classification': 'NOT_EXECUTED_NATIVE_DEATH_SCREEN', 'gameplayPass': False},
        'finalRuns': finals, 'errors': audit.errors,
        'verifiedFiles': sorted(audit.checked.values(), key=lambda r: r['path']),
        'limits': ['Integrity and recorded native assertions are checked; this script does not infer visual quality from screenshots.',
                   '32 helper groups per mode consist of18 head geometry assertions,8 unchanged limb controls,6 native Curios-only cosmetic diagnostics.',
                   'Native RagRevival public down/revive APIs are exercised; lethal-event interception and food rescue are not certified.',
                   'Claw first-person views obscured by actual native terrain overlay remain visually unassessable.'],
    }
    out.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({k: result[k] for k in ('status', 'finalPass', 'build', 'errors')}, indent=2))
    return 1 if audit.errors else 0


if __name__ == '__main__':
    sys.exit(main())
