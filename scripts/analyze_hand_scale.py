"""Summarize native glove/skin evidence without treating rendering as visual proof."""
import argparse
import json
from pathlib import Path


def summarize(path):
    report = json.loads(path.read_text())
    captures = []
    for capture in report.get('captures', []):
        rows = capture['nativeHandProbe']
        differences = []
        for row in rows:
            identities = {row.get('sourcePartIdentity'), row.get('leftSourceIdentity'),
                          row.get('rightSourceIdentity')}
            roots = [p for p in row['skinDraws'] if p['partIdentity'] in identities]
            for glove in row['gloveDraws']:
                if not roots or glove['cubeCount'] == 0:
                    continue
                closest = min(roots, key=lambda p: max(abs(a-b) for a, b in zip(p['pose'], glove['pose'])))
                differences.append({
                    'poseMaxError': max(abs(a-b) for a, b in zip(closest['pose'], glove['pose'])),
                    'matrixMaxError': max(abs(a-b) for a, b in zip(closest['matrix'], glove['matrix'])),
                    'path': row['renderPath'],
                })
        captures.append({
            'screenshot': capture['screenshot'], 'weapon': capture['heldItem'],
            'scale': capture['scale'], 'attacking': capture['betterCombatActive'],
            'nativeAccessoryRows': len(rows), 'gloveComparisons': differences,
            'skinScopes': len(capture.get('nativeSkinProbe', [])),
        })
    differences = [p for c in captures for p in c['gloveComparisons']]
    return {
        'source': str(path.resolve()), 'label': report['label'], 'status': report['status'],
        'finished': report.get('finished'), 'cleanupVerified': report.get('cleanupVerified'),
        'perspective': report.get('cameraPerspective'),
        'captureCount': len(captures), 'comparisons': len(differences),
        'maxPoseError': max((p['poseMaxError'] for p in differences), default=None),
        'maxMatrixError': max((p['matrixMaxError'] for p in differences), default=None),
        'capturesWithoutAccessory': sum(c['nativeAccessoryRows'] == 0 for c in captures),
        'failures': report.get('failures'), 'poseMismatches': report.get('poseMismatches'),
        'captures': captures,
        'limitation': 'Compares native compiled root matrices; image review and geometric interpretation are still required. Missing held-item idle hands may be native behavior.',
    }


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('reports', type=Path, nargs='+')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    output = [summarize(p) for p in args.reports]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(output, indent=2) + '\n')
    for result in output:
        print({k: v for k, v in result.items() if k not in ('source', 'failures', 'poseMismatches', 'captures', 'limitation')})
