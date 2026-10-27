"""Export explicitly recorded human/model image inspections; never grade pixels."""
from pathlib import Path
import argparse, hashlib, json
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('notes'); p.add_argument('report'); p.add_argument('output')
a = p.parse_args()
note_path, report_path, output = (ROOT / value for value in (a.notes, a.report, a.output))
notes = json.loads(note_path.read_text(encoding='utf-8-sig'))
data = json.loads(report_path.read_text(encoding='utf-8-sig'))
sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
assert data['label'] == notes['run']
assert data['firstPersonInstalled'] == notes['physicalFirstPersonPresent']
rows, seen = [], set()
for page in notes['pages']:
    for index in page['rows']:
        assert index not in seen, f'Duplicate manual claim {index}'
        seen.add(index)
        raw = data['cases'][index - 1]
        assert raw.get('status'), f'Incomplete row {index}'
        images = []
        for phase in ['active', 'recovery']:
            shot = raw[phase]; image = report_path.parent / shot['screenshot']
            assert image.is_file(), f'Missing original {image}'
            images.append(dict(phase=phase, file=image.relative_to(ROOT).as_posix(), sha256=sha(image),
                counts={key: shot.get(key) for key in ['bodyPasses','bodyItems','vanillaItems','otherItems']},
                attackActive=shot.get('attackActive'), usingItem=shot.get('usingItem'), nativeTick=shot.get('tick'),
                cameraType=shot.get('cameraType'), vehicle=shot.get('vehicle'), invisible=shot.get('invisible')))
        limited = index in page.get('limitedRows', [])
        detail = [page['notes']]
        if str(index) in page.get('special', {}): detail.append(page['special'][str(index)])
        if raw['kind'] == 'melee' and raw['active'].get('attackActive') is not True:
            limited = True
            detail.append('Nominal active image did not capture an active Better Combat layer; active-pose screenshot remains unassessed pending separately recorded replay.')
        rows.append(dict(case=index, canonicalRow=index+data['selection']['start'],
            **{key:raw.get(key) for key in ['item','kind','attackIndex','pitch','context','awakening','crouch']},
            status='REVIEWED_WITH_VISIBILITY_LIMITS' if limited else 'REVIEWED_NO_ADDITIONAL_VISIBLE_RENDER_FAULT',
            notes=detail, images=images, rawStatus=raw['status'], rendererViolations=raw.get('rendererViolations'),
            page=page['page']))
start, end = notes['assignedRange']
expected = set(range(start, end+1))
assert seen <= expected
result = dict(reviewer=notes['reviewer'], run=notes['run'], range=notes['assignedRange'],
    status='COMPLETE' if seen == expected else 'PARTIAL', manualContactPages=[page['page'] for page in notes['pages']],
    recordedAtUtc=datetime.now(timezone.utc).isoformat(), physicalFirstPersonPresent=notes['physicalFirstPersonPresent'],
    sourceReport=report_path.relative_to(ROOT).as_posix(), sourceReportSha256=sha(report_path),
    sourceReportStatus=data['status'], manualNotes=note_path.relative_to(ROOT).as_posix(),
    reviewedImageCount=len(rows)*2, reviewedCaseCount=len(rows), unreviewedAssignedRows=sorted(expected-seen),
    limits=['Every recorded active/recovery panel was manually inspected; this exporter only packages those explicit notes and hashes.',
        ('FirstPerson is physically present in this run despite the historical without-firstperson directory name.' if notes['physicalFirstPersonPresent'] else 'FirstPerson is physically absent in this run; the run manifest records the exact production and harness artifacts.'),
        'Two sampled images do not certify every animation frame. Lifecycle counters and native hit/use outcomes are separate evidence.',
        'Offscreen, effect-obscured, deliberately hidden and edge-clipped geometry is unassessed where noted, never an unconditional alignment pass.',
        'Native two-weapon contexts and world afterimages/projectiles are distinct from an accidental duplicate held-item draw.',
        'Generic health/effect signals can include preparation and prior persistent effects; these notes do not attribute them to the current ability.'],
    cases=sorted(rows,key=lambda row:row['case']))
output.parent.mkdir(parents=True,exist_ok=True); output.write_text(json.dumps(result,indent=2),encoding='utf-8')
print(json.dumps({key:result[key] for key in ['run','status','reviewedCaseCount','reviewedImageCount']},indent=2))
