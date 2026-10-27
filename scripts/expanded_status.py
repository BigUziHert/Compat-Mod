"""Print compact recent isolated-test status without exposing launcher arguments."""
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--count', type=int, default=3)
args = parser.parse_args()
for runtime in (ROOT / '.local/client', ROOT / '.local/without-firstperson/.local/client'):
    print(runtime.relative_to(ROOT))
    reports = sorted((runtime / 'emfattachments-tests').glob('expanded-*/*report.json'),
                     key=lambda p: p.stat().st_mtime, reverse=True)
    for path in reports[:args.count]:
        try:
            data = json.loads(path.read_text(encoding='utf-8'))
        except (json.JSONDecodeError, OSError):
            continue
        status = {key: data[key] for key in ('status', 'detail', 'completedCases', 'plannedCases',
                                             'totalCases', 'stateRestored') if key in data}
        for key in ('captures', 'failures', 'violations', 'cases', 'results'):
            value = data.get(key)
            if isinstance(value, list):
                status[key] = len(value)
                if key in ('failures', 'violations') and value:
                    status[key + 'Sample'] = value[:2]
            elif isinstance(value, (int, float)):
                status[key] = value
        print(path.parent.name, json.dumps(status))
