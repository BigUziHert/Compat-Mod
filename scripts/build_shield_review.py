"""Show each native shield's before-hit, post-hit and recovered views; never grades them."""
import argparse
import json
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('run', type=Path)
args = parser.parse_args()
run = (ROOT / args.run).resolve()
data = json.loads((run / 'shield-defense-report.json').read_text())
output = ROOT / '.local/review/shields' / run.name
output.mkdir(parents=True, exist_ok=True)
font = ImageFont.truetype('C:/Windows/Fonts/consola.ttf', 15)
pages = []
for index, case in enumerate(data['cases'], 1):
    samples = case.get('presentationSamples', [])
    if not samples:
        continue
    image = Image.new('RGB', (1500, 425), (20, 30, 40))
    draw = ImageDraw.Draw(image)
    title = f"{index}: {case['item']} | guard={case['blocking']} pitch={case['pitch']} crouch={case['crouch']}"
    draw.text((8, 4), title, font=font, fill='white')
    images = []
    for column, sample in enumerate(samples):
        assert column < 3, 'Unexpected extra phase: inspect before replacing the sheet layout'
        hand = sample['hand']
        draw.text((column * 500 + 5, 28), sample['phase'], font=font, fill='white')
        draw.text((column * 500 + 5, 49), f"height={hand['mainHandHeight']:.3f} damage={hand['heldDamage']}", font=font, fill='white')
        path = run / sample['screenshot']
        with Image.open(path) as source:
            thumbnail = source.convert('RGB')
            thumbnail.thumbnail((496, 350))
            image.paste(thumbnail, (column * 500 + (500 - thumbnail.width)//2, 73))
        images.append(str(path.relative_to(ROOT)))
    path = output / f'case-{index:02}.png'
    image.save(path)
    pages.append(dict(case=index, sheet=str(path.relative_to(ROOT)), images=images))
(output / 'index.json').write_text(json.dumps(dict(run=run.name, status=data['status'], pages=pages), indent=2))
print(f'{run.name}: {len(pages)} comparison sheets')
