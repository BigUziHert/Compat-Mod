"""Summarize recorded armor matrices. This is evidence extraction, not visual grading."""
from pathlib import Path
import argparse
import json

p = argparse.ArgumentParser()
p.add_argument("report", type=Path)
p.add_argument("--output", type=Path)
a = p.parse_args()
data = json.loads(a.report.read_text())
rows = []

def difference(left, right):
    if left is None or right is None or len(left) != len(right):
        return None
    return max(abs(x-y) for x,y in zip(left,right))

for capture in data.get("captures", []):
    probe = capture.get("nativePose", {}).get("armorMesh", {})
    events = [e for e in probe.get("events", [])
              if not e.get("shadow") and not e.get("isInHandItemLayerTransform")]
    if not events:
        continue
    row = {"screenshot": capture.get("screenshot"), "camera": capture.get("camera"),
           "phase": capture.get("phase"), "omittedEvents": probe.get("omittedEvents"),
           "arms": []}
    for side in ("leftArm", "rightArm"):
        skin = [e for e in events if e["path"] == "skin/"+side
                and e["stage"] == "after_translate_and_rotate"]
        armor = [e for e in events if e["path"] == "armor/chest/"+side
                 and e["stage"] == "after_translate_and_rotate"]
        s = skin[-1] if skin else None
        t = armor[-1] if armor else None
        arm = {"side": side, "skinRootTransformObserved": bool(s),
               "armorRootTransformObserved": bool(t)}
        if s and t:
            arm.update(rootFieldMaxDifference=difference(s["fields"],t["fields"]),
                       rootMatrixMaxDifference=difference(s["matrixColumnMajor"],t["matrixColumnMajor"]),
                       skinPartClass=s["partClass"],armorPartClass=t["partClass"],
                       skinRootFields=s["fields"],armorRootFields=t["fields"])
        arm["actualCompileEntries"] = [e for e in events if e["stage"] == "compile_entry"
            and (e["path"] == "skin/"+side or e["path"].startswith("skin/"+side+"/")
                 or e["path"] == "armor/chest/"+side or e["path"].startswith("armor/chest/"+side+"/"))]
        row["arms"].append(arm)
    rows.append(row)

result = {"source": str(a.report.resolve()), "capturesWithMeshEvidence": len(rows),
          "scope": "Main non-hand-layer actual transform-return and compile-entry witnesses. Matrix inequality alone is not a bug assertion; child rest geometry and outer matrices must be interpreted.",
          "captures": rows}
if a.output:
    a.output.parent.mkdir(parents=True, exist_ok=True)
    a.output.write_text(json.dumps(result,indent=2))
else:
    print(json.dumps(result,indent=2))
