"""Verify source-image hashes and coverage recorded by completed manual reviews.

This verifies evidence integrity, never grades pixels or claims unobstructed views.
"""
from pathlib import Path
import argparse
import hashlib
import json

ROOT = Path(__file__).resolve().parents[1]


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def image_claims(value):
    if isinstance(value, dict):
        source = next((value[field] for field in ('source', 'original', 'file')
                       if isinstance(value.get(field), str) and value[field].lower().endswith('.png')), None)
        if source and value.get('sha256'):
            yield dict(source=source, sha256=value['sha256'])
        for child in value.values():
            yield from image_claims(child)
    elif isinstance(value, list):
        for child in value:
            yield from image_claims(child)


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--run', action='append', required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
index_path = ROOT / 'verification/expanded/combat-visual/index.json'
index = read(index_path)
results = []
errors = []
for run in args.run:
    entry = next(row for row in index['runs'] if row['run'] == run)
    report_path = ROOT / entry['sourceReport']
    report = read(report_path)
    if report['status'] != 'CAPTURED' or len(report['cases']) != report['plannedCases']:
        errors.append(f'{run}: source report is not complete')
    if entry['unreviewedCapturedRows'] or entry['invalidReviewClaims']:
        errors.append(f'{run}: unreviewed or invalid row claims')
    expected = set()
    for row in report['cases']:
        for phase in ('active', 'recovery'):
            shot = row.get(phase, {}).get('screenshot')
            if not shot:
                errors.append(f'{run}: missing {phase} capture')
            else:
                expected.add((report_path.parent / shot).resolve())
    recorded = {}
    documents = []
    for relative in entry['documents']:
        path = ROOT / relative
        review = read(path)
        documents.append(dict(path=relative, sha256=digest(path)))
        for claim in image_claims(review):
            source = (ROOT / claim['source']).resolve()
            source.relative_to(ROOT)
            if source not in expected:
                errors.append(f'{run}: review includes an unexpected source image: {source}')
                continue
            value = claim['sha256'].lower()
            if source in recorded and recorded[source] != value:
                errors.append(f'{run}: conflicting image hashes: {source}')
            recorded[source] = value
    missing = expected - recorded.keys()
    errors.extend(f'{run}: image lacks hashed manual evidence: {p}' for p in sorted(missing))
    for path, value in recorded.items():
        if not path.is_file() or digest(path) != value:
            errors.append(f'{run}: missing/changed reviewed source image: {path}')
    results.append(dict(run=run, sourceReport=entry['sourceReport'], reportSha256=digest(report_path),
                        cases=len(report['cases']), expectedImages=len(expected), hashedReviewedImages=len(recorded),
                        documents=documents))
output = dict(status='PASS_EVIDENCE_INTEGRITY' if not errors else 'FAIL',
              scope='Completed manual-review claims match every actual active/recovery PNG byte for byte. '
                    'No automated visual, alignment, unobstructed-view, or gameplay certification.',
              index=str(index_path.relative_to(ROOT)), indexSha256=digest(index_path), runs=results, errors=errors)
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(output, indent=2)+'\n', encoding='utf-8')
print(json.dumps(dict(status=output['status'], errorCount=len(errors), firstErrors=errors[:5],
                      verifiedImages=sum(row['hashedReviewedImages'] for row in results)), indent=2))
raise SystemExit(1 if errors else 0)
