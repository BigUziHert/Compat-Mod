"""Build source-indexed contact sheets for manual inspection; never grades images."""
import argparse, hashlib, json, textwrap
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

p = argparse.ArgumentParser()
p.add_argument('report', type=Path)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
data = json.loads(a.report.read_text())
a.output.mkdir(parents=True, exist_ok=True)
font = ImageFont.truetype('C:/Windows/Fonts/consola.ttf', 14)
captures = data['captures']
pages = []
for start in range(0, len(captures), 4):
    image = Image.new('RGB', (1200, 920), (22, 28, 34))
    draw = ImageDraw.Draw(image)
    sources = []
    for i, row in enumerate(captures[start:start+4]):
        source = a.report.parent / row['screenshot']
        x, y = (i % 2) * 600, (i // 2) * 460
        label = f"{start+i+1} {source.name}"
        for n, line in enumerate(textwrap.wrap(label, 65)[:3]):
            draw.text((x+5, y+5+n*17), line, font=font, fill='white')
        with Image.open(source) as original:
            frame = original.convert('RGB')
            frame.thumbnail((596, 400))
            image.paste(frame, (x+(600-frame.width)//2, y+57+(400-frame.height)//2))
        sources.append(dict(index=start+i+1, source=str(source.resolve()),
                            sha256=hashlib.sha256(source.read_bytes()).hexdigest()))
    output = a.output / f'page-{len(pages)+1:03}.png'
    image.save(output)
    pages.append(dict(sheet=str(output.resolve()), sources=sources))
(a.output/'index.json').write_text(json.dumps(dict(report=str(a.report.resolve()), status=data['status'],
    captures=len(captures), pages=pages), indent=2))
print(f'{len(captures)} captures in {len(pages)} sheets')
