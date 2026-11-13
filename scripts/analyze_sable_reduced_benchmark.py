"""Validate and compare raw frame reports from the opt-in same-jar Sable tick suite.

Only read reports; do not launch clients or alter instances. Artifact hashes and
isolated-runtime provenance are recorded by the runner, not inferred here.
"""
from pathlib import Path
import argparse
import hashlib
import json
import math
import re
import statistics


SCENES = ("deck-open-8", "deck-open-24", "deck-occluded-8", "deck-occluded-24")


def require(value, message):
    if not value:
        raise ValueError(message)


def percentile(values, fraction):
    return sorted(values)[max(0, math.ceil(len(values) * fraction) - 1)] / 1e6


def normalized_workload(value):
    # Curios generates a new UUID-bearing bridge class name in each JVM.
    # Preserve all actual item, slot, component, visibility and renderer-family data.
    if isinstance(value, dict):
        return {key: ('top.theillusivec4.curios.api.client.CuriosRendererRegistry$Anonymous$<generated>'
                      if key == 'renderer' and isinstance(item, str) and re.fullmatch(
                          r'top\.theillusivec4\.curios\.api\.client\.CuriosRendererRegistry\$Anonymous\$[0-9a-f]{32}', item)
                      else normalized_workload(item)) for key, item in value.items()}
    if isinstance(value, list):
        return [normalized_workload(item) for item in value]
    return value


def inspect(path):
    data = path.read_bytes()
    report = json.loads(data)
    require(report.get("finished") is True and report.get("status") == "CAPTURED",
            f"Incomplete/failed run: {report.get('status')} {report.get('detail', '')}")
    require(report.get("sableReducedBenchmark") is True, "Wrong benchmark suite")
    require(report.get("compatInstalled") is True and report.get("productionMixinsDisabledProperty") is False,
            "Production compatibility mod must be active in both runs")
    require(report.get("fullDeveloperHarnessInstalled") is False, "Diagnostic harness contaminated timing")
    require(not report.get("behaviorFailures"), f"Native behavior failures: {report.get('behaviorFailures')}")
    require(report.get("cleanup", {}).get("clientStateRestored") is True, "Client restoration did not pass")
    full = report.get("sableFullTicksProperty")
    require(isinstance(full, bool), "Missing full-tick property")
    settings = report["renderSettings"]
    expected = dict(width=2560, height=1440, fullscreen=True, vsync=False, maxFps=260,
                    fov=90, renderDistance=8, simulationDistance=8, focused=True)
    for key, value in expected.items():
        require(settings.get(key) == value, f"Unexpected setting {key}: {settings.get(key)}")
    require(report.get("plannedScenes") == list(SCENES), "Unexpected planned scene list")
    require([s.get("sceneId") for s in report["stages"]] == list(SCENES), "Unexpected completed scene list")
    stages = []
    for stage in report["stages"]:
        name = stage["sceneId"]
        require(stage.get("sableFullTicksProperty") is full, f"Tick mode changed: {name}")
        require(stage.get("renderSettings") == settings, f"Settings changed: {name}")
        require(stage.get("nativeMotionAndTickModeValidated") is True, f"Native motion/tick validation failed: {name}")
        require(stage.get("actualViewLockedEveryMeasuredFrame") is True, f"Actual view was not locked: {name}")
        frames = stage["frameIntervalsNanos"]
        require(frames and all(isinstance(v, int) and v > 0 for v in frames), f"Invalid raw intervals: {name}")
        require(len(frames) == stage["sampleCount"] and sum(frames) == stage["durationNanos"],
                f"Raw frame accounting differs: {name}")
        ticks = stage["measuredClientTicks"]
        require(ticks > 0 and stage.get("localTrackedDeckTicks") == ticks, f"Observer lost deck: {name}")
        hidden = stage["realWallOcclusion"]
        require(hidden == ("occluded" in name), f"Wrong scene visibility: {name}")
        require(len(stage["entities"]) == stage["players"] == int(name.rsplit("-", 1)[1]), f"Wrong actor count: {name}")
        require(not hidden or stage.get("nativeWallBlocks", 0) > 0, f"Missing native wall: {name}")
        for entity in stage["entities"]:
            label = f"{name} actor {entity['index']}"
            for flag in ("resident", "trackingMatchesOwnedDeck", "nativeMotionValidated", "hiddenCullingValidated", "hiddenTickModeValidated"):
                require(entity.get(flag) is True, f"{flag} failed: {label}")
            require(entity["nativeMotionSamples"] == ticks and entity["independentPlotTargetUpdates"] > 0,
                    f"Missing independent input/sampling: {label}")
            require(entity["nativeAiSteps"] >= ticks * .9 and entity["nativeMeasuredTravelBlocks"] > .5,
                    f"Native motion stalled: {label}")
            require(entity["maximumNativePlotWorldError"] <= .25 and entity["maximumPlotTargetLag"] <= .5
                    and entity["lostSupportSamples"] == 0, f"Native position/plot drift: {label}")
            if hidden:
                require(entity["nativeCulledSamples"] >= ticks * .9, f"Actor not naturally occluded: {label}")
                require(entity["nativeRendererReturns"] == 0 and entity["accessoryDispatches"] == 0,
                        f"Hidden actor rendered: {label}")
                require(entity["nativeFullTicks"] >= ticks * .9 if full else entity["nativeFullTicks"] < ticks * .1,
                        f"Wrong full/reduced tick workload: {label}")
            else:
                require(entity["nativeRendererReturns"] > 0 and entity["accessoryDispatches"] > 0,
                        f"Visible actor did not render: {label}")
        require(hidden or stage["framesMissingSomeClone"] == 0, f"Open workload lost rendered actors: {name}")
        stages.append(dict(scene=name, samples=len(frames), meanFrameMs=statistics.mean(frames) / 1e6,
                           meanFps=len(frames) * 1e9 / sum(frames), p50FrameMs=percentile(frames, .5),
                           p95FrameMs=percentile(frames, .95), p99FrameMs=percentile(frames, .99),
                           maxFrameMs=max(frames) / 1e6, over16_7ms=sum(v > 16_666_667 for v in frames),
                           nativeFullTicks=sum(e["nativeFullTicks"] for e in stage["entities"]),
                           nativeAiSteps=sum(e["nativeAiSteps"] for e in stage["entities"]),
                           maximumNativePlotWorldError=max(e["maximumNativePlotWorldError"] for e in stage["entities"]),
                           maximumPlotTargetLag=max(e["maximumPlotTargetLag"] for e in stage["entities"])))
    identity = dict(modVersions=report["modVersions"], settings=settings, resourcePacks=report["resourcePacks"],
                    warmupSeconds=report["warmupSeconds"], measureSeconds=report["measureSeconds"],
                    firstPersonInstalled=report["firstPersonInstalled"], firstPersonEnabled=report["firstPersonEnabled"],
                    loadouts=normalized_workload([[e["loadout"] for e in s["entities"]] for s in report["stages"]]))
    return dict(path=str(path.resolve()), sha256=hashlib.sha256(data).hexdigest(), label=report["label"],
                mode="full" if full else "reduced", stages=stages), identity


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reports", type=Path, nargs="+")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    runs, identity = [], None
    for path in args.reports:
        run, actual = inspect(path)
        require(identity is None or identity == actual, "A/B mod versions, settings, equipment or duration differ")
        identity = actual
        runs.append(run)
    require(len({r["sha256"] for r in runs}) == len(runs), "Duplicate report bytes cannot count as repeats")
    groups = {mode: [r for r in runs if r["mode"] == mode] for mode in ("full", "reduced")}
    require(all(groups.values()), "Provide at least one full and one reduced report")
    comparisons = []
    for index, scene in enumerate(SCENES):
        stats = {mode: {key: statistics.median(r["stages"][index][key] for r in values)
                        for key in ("meanFrameMs", "meanFps", "p50FrameMs", "p95FrameMs", "p99FrameMs")}
                 for mode, values in groups.items()}
        comparisons.append(dict(scene=scene, medianRunStatistics=stats,
                                reducedMeanFrameChangePercent=100 * (stats["reduced"]["meanFrameMs"] / stats["full"]["meanFrameMs"] - 1),
                                reducedP95ChangePercent=100 * (stats["reduced"]["p95FrameMs"] / stats["full"]["p95FrameMs"] - 1)))
    result = dict(valid=True, runs=runs, repeats={m: len(v) for m, v in groups.items()}, comparisons=comparisons,
                  equipmentComparison="Exact metadata except the generated CuriosRendererRegistry$Anonymous$32hex suffix; original class names remain in raw reports.",
                  repeatEvidence="At least two independent reports per mode" if min(map(len, groups.values())) >= 2 else "Exploratory single-pair evidence; repeat before a performance conclusion",
                  limits="Frame timings measure this fixed offline scene. Verify runner jar hashes and run order separately; native renderer counters do not certify pixels.")
    content = json.dumps(result, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(content, encoding="utf-8")
    print(content)


if __name__ == "__main__":
    main()
