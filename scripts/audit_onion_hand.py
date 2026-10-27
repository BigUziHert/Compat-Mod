"""Bind the 1.2.3 hand/arm regression evidence to the built artifacts and sources."""
import json
from datetime import datetime, timezone
from pathlib import Path
from audit_revival_face import Audit, ROOT, digest, load, path


def main():
    audit = Audit()
    build = load(ROOT / 'verification/build-1.2.3.json')
    audit.check((build['tests'], build['failures'], build['errors'], build['skipped']) == (62, 0, 0, 0), 'Build tests differ')
    for source, expected in build['sourceSha256'].items():
        audit.bind(source, expected)
    for name, expected in build['jars'].items():
        audit.bind(ROOT / 'build/libs' / name, expected)

    reviews = [
        ('baseline-r2-review.json', 12),
        ('fixed-r1-review.json', 84),
        ('allocation-fpm-arm-policy-r1-review.json', 96),
        ('allocation-fpm-claws-r1-review.json', 4),
    ]
    image_paths = set()
    review_records = []
    for name, expected_count in reviews:
        file = ROOT / 'verification/onion-hand' / name
        audit.bind(file)
        review = load(file)
        audit.references(review)
        images = review.get('images', [])
        audit.check(len(images) == expected_count, f'{name}: expected {expected_count} reviewed images')
        for row in images:
            source = next((row[k] for k in ('source', 'path', 'file', 'image') if isinstance(row.get(k), str)), None)
            audit.check(source is not None and 'sha256' in row, f'{name}: image lacks hash/reference')
            if source:
                audit.bind(source, row['sha256'])
                image_paths.add(str(path(source).resolve()))
        review_records.append({'path': str(file.relative_to(ROOT)), 'sha256': digest(file),
                               'status': review.get('status', review.get('result')), 'images': len(images)})
    audit.check(len(image_paths) == 196, 'Expected 196 unique reviewed baseline/final images')
    result = {'recordedUtc': datetime.now(timezone.utc).isoformat(),
              'status': 'PASS' if not audit.errors else 'FAIL', 'errors': audit.errors,
              'build': audit.bind(ROOT / 'verification/build-1.2.3.json'),
              'reviews': review_records, 'reviewedImages': len(image_paths),
              'files': list(audit.checked.values()),
              'limits': 'Integrity and recorded assertions only. Each independent review retains its visual limits; no multiplayer FPS or exhaustive scenario claim.'}
    (ROOT / 'verification/onion-hand/final-evidence-integrity.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps({k: v for k, v in result.items() if k not in ('files', 'reviews')}, indent=2))
    raise SystemExit(bool(audit.errors))


if __name__ == '__main__':
    main()
