"""Build inspectable paired camera sheets from completed live wearable rows.

This does not grade pictures. Full originals remain indexed; first-person panels
show the entire frame at half size, third-person panels show a labelled native-
pixel center crop. Oversized/occluded geometry must be checked in the original.
"""
import argparse
import json
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--fpm', default='expanded-fpm-live-wearables-final')
parser.add_argument('--without', default='expanded-without-fpm-live-wearables-final')
parser.add_argument('--output', default='.local/review/live-wearables-final')
parser.add_argument('--single', choices=['fpm', 'without'], help='Review one client while the other is still running an earlier suite')
args = parser.parse_args()
folders = [ROOT / '.local/client/emfattachments-tests' / args.fpm,
           ROOT / '.local/without-firstperson/.local/client/emfattachments-tests' / args.without]
labels = ['FPM', 'without FPM']
if args.single:
    mode_index = 0 if args.single == 'fpm' else 1
    folders, labels = [folders[mode_index]], [labels[mode_index]]
out = ROOT / args.output
out.mkdir(parents=True, exist_ok=True)
font = ImageFont.truetype('C:/Windows/Fonts/consola.ttf', 15)
small = ImageFont.truetype('C:/Windows/Fonts/consola.ttf', 12)

def key(row):
    return (row['item'], row['slot'], row.get('slotIndex', 0), row.get('variant', 'default'))

def load(folder):
    path = folder / 'expanded-live-wearables-report.json'
    if not path.exists():
        return {}, {}
    try:
        data = json.loads(path.read_text())
    except json.JSONDecodeError:
        return {}, {}
    rows = {key(r): r for r in data.get('cases', []) if len(r.get('captures', [])) == 2 and 'status' in r}
    return data, rows

loaded = [load(f) for f in folders]
keys = sorted(set.intersection(*(set(rows) for _, rows in loaded)))
index = []
W, H, BAR, ROWS = 550, 380, 58, 3
previous_path = out / 'index.json'
previous = json.loads(previous_path.read_text()).get('cases', []) if previous_path.exists() else []
for page_start in range(0, len(keys), ROWS):
    page = page_start // ROWS + 1
    page_keys = keys[page_start:page_start + ROWS]
    cached = [r for r in previous if r.get('sheet') == page]
    if [key(r) for r in cached] == page_keys and (out / f'paired-{page:03d}.png').exists() and all(
        (ROOT / image['original']).exists()
        and (ROOT / image['original']).stat().st_mtime_ns == image.get('sourceModifiedNs')
        for r in cached for image in r['images']
    ):
        index.extend(cached)
        continue
    sheet = Image.new('RGB', (W * 2 * len(folders), (H + BAR) * ROWS + 24), '#16202b')
    draw = ImageDraw.Draw(sheet)
    draw.text((6, 3), ' | '.join(label+' whole first-person frame | '+label+' native-pixel third-person crop' for label in labels), font=small, fill='white')
    records = []
    for row_idx, k in enumerate(keys[page_start:page_start + ROWS]):
        y = 24 + row_idx * (H + BAR)
        catalog_index = loaded[0][1][k].get('catalogIndex', page_start + row_idx)
        record = dict(item=k[0], slot=k[1], slotIndex=k[2], variant=k[3], catalogIndex=catalog_index, sheet=page, row=row_idx + 1, images=[])
        draw.text((7, y + 2), f'{catalog_index + 1:03d}  {k[0]}  {k[1]}[{k[2]}]  {k[3]}', font=font, fill='#aee8ff')
        for mode, folder in enumerate(folders):
            r = loaded[mode][1][k]
            draw.text((mode * W * 2 + 7, y + 23), f'{r["status"]}  armsChanged={r.get("actualEatingArmPoseChanged")}  bodyVertices={r.get("eatingModelVertices", "unmeasured")}', font=small, fill='white')
            for shot_idx, shot in enumerate(r['captures']):
                path = folder / shot['file']
                with Image.open(path) as source:
                    source = source.convert('RGB')
                    if shot_idx == 0:
                        image = source.resize((W, H), Image.Resampling.LANCZOS)
                        bounds = [0, 0, source.width, source.height]
                        mode_desc = 'entire frame resized'
                    else:
                        cx, cy = source.width // 2, int(source.height * .51)
                        bounds = [cx - W // 2, cy - H // 2, cx + W // 2, cy + H // 2]
                        image = source.crop(bounds)
                        mode_desc = 'native-pixel center crop; oversized extents require original'
                    x = (mode * 2 + shot_idx) * W
                    sheet.paste(image, (x, y + BAR))
                draw.text((x + 4, y + 42), f'{shot["view"]}: use={shot.get("elapsedNativeUseTicks")} ticks; model={shot.get("renderCounts", {}).get("modelVertices", "unmeasured")}', font=small, fill='#d7f6bb')
                record['images'].append(dict(mode=labels[mode], view=shot['view'], original=str(path.relative_to(ROOT)), sourceModifiedNs=path.stat().st_mtime_ns, sourceBounds=bounds, display=mode_desc, renderCounts=shot.get('renderCounts'), visualStatus=shot.get('visualStatus')))
        records.append(record)
    sheet.save(out / f'paired-{page:03d}.png')
    index.extend(records)
(out / 'index.json').write_text(json.dumps(dict(scope='Review aids only, not automated visual passes. First-person full frames and native-pixel third-person crops; original file and crop bounds indexed for every panel.', pairedCompletedCases=len(keys), expected=[d.get('caseCount') for d, _ in loaded], sourceStatus=[d.get('status') for d, _ in loaded], cases=index), indent=2))
print(f'Built {(len(keys) + ROWS - 1) // ROWS} sheets for {len(keys)} paired completed cases in {out}')
