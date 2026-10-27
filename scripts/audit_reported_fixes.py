"""Verify immutable sources for the bounded 1.2.1 reviews; never infer visual success."""
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def resolve(value):
    path = Path(value)
    return path if path.is_absolute() else ROOT / path


def walk(value):
    if isinstance(value, dict):
        yield value
        for child in value.values():
            yield from walk(child)
    elif isinstance(value, list):
        for child in value:
            yield from walk(child)


build = read(ROOT / "verification/build-1.2.1.json")
assert (build["tests"], build["failures"], build["errors"], build["skipped"]) == (40, 0, 0, 0)
for filename, expected in build["jars"].items():
    assert digest(ROOT / "build/libs" / filename) == expected.lower(), filename
for filename, expected in build["sourceSha256"].items():
    assert digest(resolve(filename)) == expected.lower(), filename

images = {}
reviews = []
for folder in ("verification/reported-bugs", "verification/necklace-bug"):
    for path in sorted((ROOT / folder).glob("*review.json")):
        review = read(path)
        report_record = review.get("report")
        report_hash = review.get("reportSha256")
        if isinstance(report_record, dict):
            report_hash = report_record.get("sha256")
        if not report_hash:
            continue
        if isinstance(report_record, dict):
            report = resolve(report_record["path"])
        elif "report" in review:
            report = resolve(review["report"])
        else:
            run = resolve(review["run"])
            if not run.is_dir():
                options = [ROOT / prefix / "emfattachments-tests" / review["run"]
                           for prefix in (".local/client", ".local/without-firstperson/.local/client")]
                matches = [option for option in options if option.is_dir()]
                assert len(matches) == 1, (path, matches)
                run = matches[0]
            candidates = list(run.glob("*-report.json"))
            assert len(candidates) == 1, (path, candidates)
            report = candidates[0]
        assert digest(report) == report_hash.lower(), (path, report)
        manifest_path = ROOT / "verification/expanded-run-manifests" / (report.parent.name + ".json")
        manifest = read(manifest_path)
        for filename, expected in manifest["artifacts"].items():
            archived = resolve(manifest["archivedArtifacts"][filename])
            assert digest(archived) == expected.lower(), archived
        for key in ("productionSha256", "harnessSha256", "testHarnessSha256"):
            if key in review:
                assert review[key].lower() in {v.lower() for v in manifest["artifacts"].values()}, (path, key)
        for artifact in review.get("buildArtifacts", []):
            filename = Path(artifact["path"]).name
            if filename.startswith("emf-attachments-compat-"):
                assert artifact["sha256"].lower() == manifest["artifacts"][filename].lower(), (path, filename)
        if "runManifestSha256" in review:
            assert digest(manifest_path) == review["runManifestSha256"].lower(), manifest_path
        count = 0
        for obj in walk(review):
            expected = obj.get("sha256")
            if not isinstance(expected, str):
                continue
            filenames = [obj.get(key) for key in ("file", "source", "path", "screenshot", "image")]
            filenames = [name for name in filenames if isinstance(name, str) and name.lower().endswith(".png")]
            for name in filenames:
                image = resolve(name)
                assert digest(image) == expected.lower(), (path, image)
                images[str(image.resolve()).lower()] = expected.lower()
                count += 1
        reviews.append({"path": str(path.relative_to(ROOT)), "sha256": digest(path),
                        "report": str(report.relative_to(ROOT)), "reportSha256": digest(report),
                        "manifest": str(manifest_path.relative_to(ROOT)),
                        "manifestSha256": digest(manifest_path), "verifiedImageReferences": count,
                        "productionArtifacts": manifest["artifacts"]})

labels = {
    "reported-fpm-crossbow-charge-smoke-r3": 33,
    "reported-fpm-crossbow-charge-final-r3": 132,
    "reported-fpm-crossbow-clamped-final-r3": 33,
    "reported-without-fpm-crossbow-fixed-r3": 33,
    "reported-fpm-necklaces-fixed-r3": 5,
    "reported-without-fpm-necklaces-fixed-r3": 5,
    "reported-fpm-claws-fixed-r3": 4,
    "reported-without-fpm-claws-fixed-r3": 4,
    "reported-fpm-down-movement-fixed-r3": 19,
}
final_runs = []
for label, expected_count in labels.items():
    prefix = ".local/without-firstperson/.local/client" if "without-fpm" in label else ".local/client"
    run = ROOT / prefix / "emfattachments-tests" / label
    sources = list(run.glob("*.png"))
    assert len(sources) == expected_count, (label, len(sources))
    assert all(str(source.resolve()).lower() in images for source in sources), (label, "unreviewed source image")
    matching_reviews = [row["path"] for row in reviews if Path(row["report"]).parent.name == label]
    assert matching_reviews, (label, "no report review")
    manifest = read(ROOT / "verification/expanded-run-manifests" / (label + ".json"))
    assert manifest["artifacts"] == build["jars"], (label, "not final artifacts")
    assert manifest["firstpersonInstalled"] == ("without-fpm" not in label), label
    assert manifest["rejectedCompanionAbsent"], label
    final_runs.append({"label": label, "sourceImages": len(sources), "reviews": matching_reviews,
                       "firstpersonInstalled": manifest["firstpersonInstalled"]})

result = {
    "recordedAtUtc": datetime.now(timezone.utc).isoformat(),
    "status": "PASS_SOURCE_AND_ARTIFACT_INTEGRITY",
    "meaning": "Hashes bind manual reviews to original pixels, reports and archived jars. This is not an automatic visual pass; retain each review's failures and unassessable regions.",
    "build": "verification/build-1.2.1.json", "buildSha256": digest(ROOT / "verification/build-1.2.1.json"),
    "currentSourceFilesVerified": len(build["sourceSha256"]),
    "uniqueReviewedSourceImagesVerified": len(images),
    "finalJarReviewedSourceImages": sum(labels.values()),
    "finalRuns": final_runs, "reviews": reviews,
}
target = ROOT / "verification/reported-bugs/final-evidence-integrity.json"
target.write_text(json.dumps(result, indent=2), encoding="utf-8")
print(json.dumps({key: value for key, value in result.items() if key not in ("reviews", "finalRuns")}, indent=2))
