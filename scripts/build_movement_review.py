"""Contact sheets for saved movement evidence; does not assign visual verdicts."""
import argparse
import json
from pathlib import Path
from PIL import Image, ImageDraw

parser = argparse.ArgumentParser()
parser.add_argument("--report", action="append", required=True)
parser.add_argument("--output", required=True)
args = parser.parse_args()
merged = {}
for filename in args.report:
    path = Path(filename)
    report = json.loads(path.read_text())
    for capture in report["captures"]:
        merged[capture["stage"]] = (capture, path.parent / capture["screenshot"], path)
out = Path(args.output)
out.mkdir(parents=True, exist_ok=True)
rows = []
items = list(merged.values())
for start in range(0, len(items), 4):
    canvas = Image.new("RGB", (1100, 820), (23, 31, 40))
    draw = ImageDraw.Draw(canvas)
    for offset, (capture, image_path, report_path) in enumerate(items[start:start + 4]):
        x, y = (offset % 2) * 550, (offset // 2) * 410
        number = start + offset + 1
        draw.text((x + 5, y + 3), f"{number:03} {capture['stage']}", fill="white")
        draw.text((x + 5, y + 16), f"{capture['camera']}; rise={capture['rise']:.3f}; climbing={capture.get('nativeClimbingAtCapture', 'n/a')}", fill="white")
        image = Image.open(image_path)
        image.thumbnail((550, 380))
        canvas.paste(image, (x, y + 30))
        rows.append({"case": number, "stage": capture["stage"], "sheet": start // 4 + 1,
                     "panel": offset + 1, "source": str(image_path), "report": str(report_path),
                     "camera": capture["camera"], "visualStatus": "REQUIRES_REVIEW"})
    canvas.save(out / f"page-{start // 4 + 1:03}.png")
(out / "index.json").write_text(json.dumps({"scope": "Entire native frames resized to550x380; no automated visual verdict.", "caseCount": len(rows), "cases": rows}, indent=2) + "\n")
print(f"Created{len(rows)} panels in{(len(rows)+3)//4} sheets")
