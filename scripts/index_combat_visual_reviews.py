"""Index recorded manual reviews; this script never grades screenshots."""
import json
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
reviews = ROOT / 'verification/expanded/combat-visual'
claims = defaultdict(lambda: defaultdict(list))
documents = defaultdict(list)
for path in sorted(reviews.glob('*.json')):
    if path.name == 'index.json':
        continue
    data = json.loads(path.read_text(encoding='utf-8'))
    # Storage-cleanup manifests live alongside the reviews and make no claims.
    if not isinstance(data, dict):
        continue
    run = data.get('run')
    if not run:
        continue
    relative = path.relative_to(ROOT).as_posix()
    documents[run].append(relative)
    if 'cases' in data:
        rows = [int(next(row[field] for field in ('runRow', 'case', 'row') if field in row))
                for row in data['cases'] if row.get('images')]
    elif 'caseRangeInclusive' in data:
        start, end = data['caseRangeInclusive']
        rows = range(start, end + 1)
    else:
        rows = [row for page in data.get('pages', []) if page.get('reviewed') for row in page['caseRows']]
    for row in rows:
        claims[run][row].append(relative)

results = []
for run in sorted(documents):
    matches = [base / run / 'expanded-combat-report.json' for base in (
        ROOT / '.local/client/emfattachments-tests',
        ROOT / '.local/without-firstperson/.local/client/emfattachments-tests')]
    matches = [path for path in matches if path.exists()]
    if len(matches) != 1:
        raise RuntimeError(f'Expected one source report for {run}: {matches}')
    report = matches[0]
    data = json.loads(report.read_text(encoding='utf-8'))
    captured, reviewed, unavailable = [], [], []
    image_count = reviewed_image_count = 0
    for index, row in enumerate(data.get('cases', []), 1):
        images = [row[phase]['screenshot'] for phase in ('active', 'recovery')
                  if isinstance(row.get(phase), dict) and row[phase].get('screenshot')]
        existing = [name for name in images if (report.parent / name).is_file()]
        if len(existing) != len(images):
            raise RuntimeError(f'Missing recorded image {run}/{index}')
        if existing:
            captured.append(index)
            image_count += len(existing)
            if index in claims[run]:
                reviewed.append(index)
                reviewed_image_count += len(existing)
        elif row.get('status') in {'CASE_FAILURE', 'ACTION_FAILURE', 'UNMET_PRECONDITION', 'FAIL'}:
            unavailable.append(index)
    invalid_claims = sorted(set(claims[run]) - set(captured))
    results.append(dict(run=run, status=data.get('status'), plannedCases=data.get('plannedCases'),
                        sourceReport=report.relative_to(ROOT).as_posix(), documents=documents[run],
                        capturedRows=len(captured), recordedImages=image_count,
                        reviewedRows=len(reviewed), reviewedImages=reviewed_image_count,
                        unreviewedCapturedRows=sorted(set(captured)-set(reviewed)),
                        failedRowsWithoutImages=unavailable, invalidReviewClaims=invalid_claims,
                        reviewedRowSources={str(row): claims[run][row] for row in reviewed}))

output = dict(scope='Index of manual review records; no automatic visual grading or gameplay certification',
              limits=['Occluded and offscreen geometry remains unassessed as recorded in each review.',
                      'Health-loss labels in earlier sheets include preparation and native effect carryover; they do not attribute damage to the current weapon.',
                      'A reviewed image is not an unconditional visual pass. Failed rows without images require separate native replay.'],
              runs=results)
(reviews / 'index.json').write_text(json.dumps(output, indent=2), encoding='utf-8')
for result in results:
    print(json.dumps({key: value for key, value in result.items() if key not in (
        'documents', 'reviewedRowSources', 'unreviewedCapturedRows', 'failedRowsWithoutImages')}, indent=2))
assert not any(result['invalidReviewClaims'] for result in results), 'A review claims a row without captures'
