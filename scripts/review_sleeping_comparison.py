"""Review captured native sleep evidence; does not replace visual inspection."""
import json
import hashlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'verification/sleeping-1.2.7'
RESTORATION = ('inventoryAndAccessoriesRestored', 'platformRemoved', 'remoteRemoved',
               'sleepEnded', 'cameraRestored', 'inputsRestored', 'serverRestorationCompleted')
FINAL_LABELS = {'patched-native-shadows-r3', 'patched-fpm-shadows-r3'}


def review(path):
    data = json.loads(path.read_text(encoding='utf-8-sig'))
    label = data['label']
    patched = label.startswith('patched-')
    failures = []
    def check(condition, description):
        if not condition:
            failures.append(description)
    check(data.get('finished') is True and data['status'] == 'CAPTURED', 'capture finished')
    for key in RESTORATION:
        check(data.get(key) is True, key)
    captures = data['screenshots']
    check(len(captures) == 2 * len(data['cases']), 'two captures per case')
    cases = {c['case']: c for c in data['cases']}
    orientation = []
    name_tags = []
    local_head_hidden = local_head_drawn = necklaces = third_person = shadows = 0
    for capture in captures:
        name = capture['case']
        case = cases[name]
        bodies = capture['orientation']
        draws = capture['artifacts']
        orientation.extend(bodies)
        name_tags.extend(capture.get('nameTags', []))
        check(capture['clientSleeping'], name + ': actual sleeping state')
        check(any(o['entity'] == 'local' and not o['shadow'] for o in bodies), name + ': main local body')
        check(all(a['invalidVertices'] == 0 for a in draws), name + ': finite accessory vertices')
        own = [a for a in draws if a['entity'] == 'local' and not a['shadow'] and a['vertices'] > 0]
        if not case['visible']:
            check(not draws, name + ': hidden slot excluded from all observed passes')
        elif capture['camera'] == 'THIRD_PERSON_BACK':
            for entity in ('local', 'remote-client-clone'):
                check(any(a['entity'] == entity and not a['shadow'] and a['vertices'] > 0 for a in draws),
                      name + ': third-person goggles ' + entity)
            third_person += 1
            if label.endswith(('-r2', '-r3')):
                check(any(n['entity'] == 'remote-client-clone' and not n['shadow']
                          for n in capture.get('nameTags', [])), name + ': actual remote name-tag draw')
        elif case['item'] == 'artifacts:cross_necklace':
            check(any(a['item'] == case['item'] for a in own), name + ': non-head necklace retained')
            necklaces += 1
        else:
            local_head_drawn += bool(own)
            local_head_hidden += not bool(own)
            if patched:
                check(not own, name + ': no local head accessory geometry')
            else:
                check(bool(own), name + ': baseline reproduces local head accessory draw')
        if '-shadows-' in label and case['visible']:
            check(any(o['entity'] == 'local' and o['shadow'] for o in bodies), name + ': local shadow body')
            check(any(a['entity'] == 'local' and a['shadow'] and a['vertices'] > 0 for a in draws),
                  name + ': accessory retained in actual shadow pass')
            shadows += 1
    maximum = max((o['maximumBasisError'] for o in orientation), default=float('inf'))
    if patched:
        check(maximum < 0.0001, 'all main/shadow body bases match single transformed bed')
        if label.endswith(('-r2', '-r3')):
            check(bool(name_tags) and max(n['maximumBasisError'] for n in name_tags) < 0.0001,
                  'name tags retain camera-facing billboard orientation')
    else:
        check(maximum > 0.5, 'baseline reproduces double-rotation error')
        for capture in captures:
            if capture['case'] in ('world-native-goggles', 'cardinal-deck-goggles'):
                check(all(o['maximumBasisError'] < 0.0001 for o in capture['orientation']),
                      capture['case'] + ': ordinary/cardinal control')
    return dict(label=label, patched=patched, passed=not failures, failures=failures,
                cases=len(cases), captures=len(captures), orientationSamples=len(orientation),
                maximumBasisError=maximum, headHiddenCaptures=local_head_hidden,
                headDrawnCaptures=local_head_drawn, nonHeadControlCaptures=necklaces,
                thirdPersonPreservationCaptures=third_person, localShadowPreservationCaptures=shadows,
                nameTagSamples=len(name_tags),
                maximumNameTagBasisError=max((n['maximumBasisError'] for n in name_tags), default=None),
                acceptedReleaseEvidence=label in FINAL_LABELS,
                captureStatus=data['status'],
                initialCandidateRejectedForNameTagRegression=patched and label.endswith('-r1'),
                restoration={k:data.get(k) for k in RESTORATION},
                limits='Numeric renderer evidence; consult separate original-image reviews. Remote witness is a client-only clone.')


if __name__ == '__main__':
    rows = [review(p) for p in sorted(OUT.glob('*-r*-report.json'))]
    final = [r for r in rows if r['acceptedReleaseEvidence']]
    production_hash = hashlib.sha256((ROOT / 'build/libs/emf-attachments-compat-1.21.1-1.2.7.jar').read_bytes()).hexdigest()
    harness_hashes = set()
    for row in final:
        manifest = json.loads((OUT / (row['label'] + '-manifest.json')).read_text())
        if manifest['productionSha256'] != production_hash:
            row['failures'].append('Final run production bytes differ from current built jar')
        if bool(manifest['firstperson']) != ('-fpm-' in row['label']):
            row['failures'].append('FirstPerson installation does not match variant')
        harness_hashes.add(manifest['harnessSha256'])
        row['passed'] = not row['failures']
    result = {'runs': rows, 'numericChecksPassed': bool(rows) and all(r['passed'] for r in rows),
              'finalReleaseRunsPassed': len(final) == 2 and len(harness_hashes) == 1 and all(r['passed'] for r in final),
              'finalProductionSha256': production_hash,
              'note': 'Initial r1 candidate was rejected for a name-tag regression. FPM r2 stopped during native sleep setup after20captures. Only the two completed r3 runs with identical stabilized harness bytes are final paired release evidence; earlier results remain visible.'}
    (OUT / 'numeric-review.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))
    if not result['finalReleaseRunsPassed']:
        raise SystemExit(1)
