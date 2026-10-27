"""Write a benchmark PLAN and read-only settings audit. Never modifies a runtime or launches Java."""
import argparse
import datetime
import gzip
import hashlib
import json
import re
import struct
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PERF = ROOT / "verification/performance"
BASELINE = ROOT / ".local/test-artifacts/569cbb680399c56d2a6ead3aadec41084c8830685da5cdb6baf9ecf3dba4b470/emf-attachments-compat-1.21.1-1.2.2.jar"
SETTINGS = dict(fullscreen="false", overrideWidth="1100", overrideHeight="760", maxFps="260",
                renderDistance="8", simulationDistance="8", enableVsync="false",
                pauseOnLostFocus="false", mipmapLevels="0", graphicsMode="1")
CONFIGS = ("iris.properties", "entity_model_features.json", "entity_texture_features.json",
           "emf-attachments-client.toml", "dynamic_fps.json", "sodium-options.json",
           "notenoughanimations.json", "watut-common.toml", "watut-client.toml", "watut-server.toml")


def sha(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for part in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(part)
    return digest.hexdigest()


def artifact(path):
    return dict(path=str(path.resolve()), exists=path.is_file(),
                sha256=sha(path) if path.is_file() else None,
                bytes=path.stat().st_size if path.is_file() else None)


def read_options(path):
    return dict(line.split(":", 1) for line in path.read_text(encoding="utf-8-sig").splitlines() if ":" in line)


class NbtReader:
    """Bounded NBT reader for disk level.dat metadata, not a live-world query."""
    def __init__(self, data):
        if len(data) > 32 * 1024 * 1024:
            raise ValueError("level.dat exceeds metadata inspection bound")
        self.data, self.offset = data, 0

    def read(self, n):
        if n < 0 or self.offset + n > len(self.data):
            raise ValueError("Truncated or invalid NBT")
        result = self.data[self.offset:self.offset + n]
        self.offset += n
        return result

    def number(self, fmt):
        return struct.unpack(">" + fmt, self.read(struct.calcsize(fmt)))[0]

    def string(self):
        return self.read(self.number("H")).decode("utf-8", errors="replace")

    def payload(self, kind, depth=0):
        if depth > 128:
            raise ValueError("NBT nesting bound exceeded")
        if kind in range(1, 7):
            return self.number({1: "b", 2: "h", 3: "i", 4: "q", 5: "f", 6: "d"}[kind])
        if kind == 8:
            return self.string()
        if kind in (7, 11, 12):
            size = self.number("i")
            self.read(size * {7: 1, 11: 4, 12: 8}[kind])
            return None
        if kind == 9:
            child, count = self.number("B"), self.number("i")
            if count < 0 or count > 1_000_000:
                raise ValueError("Invalid NBT list count")
            return [self.payload(child, depth + 1) for _ in range(count)]
        if kind == 10:
            result = {}
            while (child := self.number("B")) != 0:
                name = self.string()
                result[name] = self.payload(child, depth + 1)
            return result
        raise ValueError(f"Unsupported NBT type {kind}")


def world_metadata(path):
    if not path.is_file():
        return dict(path=str(path), available=False)
    try:
        raw = path.read_bytes()
        reader = NbtReader(gzip.decompress(raw))
        kind = reader.number("B")
        reader.string()
        data = reader.payload(kind).get("Data", {})
        player = data.get("Player", {})
        return dict(path=str(path), available=True, sha256=hashlib.sha256(raw).hexdigest(),
                    modifiedUtc=datetime.datetime.fromtimestamp(path.stat().st_mtime, datetime.timezone.utc).isoformat(),
                    savedWorld={key: data.get(key) for key in ("Time", "DayTime", "raining", "rainTime", "thundering", "thunderTime", "clearWeatherTime", "Difficulty", "GameType")},
                    relevantGameRules={key: data.get("GameRules", {}).get(key) for key in ("doDaylightCycle", "doWeatherCycle", "doMobSpawning")},
                    savedPlayer={key: player.get(key) for key in ("Pos", "Rotation", "Health", "Dimension")},
                    limitation="Disk save snapshot may lag the live phase. This is not a live weather/time/AFK observation.")
    except Exception as error:
        return dict(path=str(path), available=False, observationError=str(error))


def inspect(runtime, expected_artifact=None, expected_harness=None, expected_hooks=None):
    options = read_options(runtime / "options.txt")
    config_dir = runtime / "config"
    config_hashes = {name: sha(config_dir / name) for name in CONFIGS if (config_dir / name).is_file()}
    emf = json.loads((config_dir / "entity_model_features.json").read_text(encoding="utf-8-sig"))
    dynamic = json.loads((config_dir / "dynamic_fps.json").read_text(encoding="utf-8-sig"))
    mods = sorted((runtime / "mods").glob("*.jar"))
    production = [p for p in mods if p.name.startswith("emf-attachments-compat-") and "test-harness" not in p.name]
    harness = [p for p in mods if p.name.startswith("emf-attachments-compat-") and "test-harness" in p.name]
    violations = [f"options.{key}: found {options.get(key)!r}, expected {value!r}" for key, value in SETTINGS.items() if options.get(key) != value]
    firstperson = [p.name for p in mods if p.name.lower().startswith("firstperson-")]
    if firstperson:
        violations.append("FirstPerson jar is present")
    if emf.get("preventFirstPersonHandAnimating") is not False:
        violations.append("EMF preventFirstPersonHandAnimating must remain false, matching user's actual hand-animation setting")
    if dynamic.get("enabled") is not False:
        violations.append("DynamicFPS enabled must be explicitly false in the disposable instance")
    if len(production) != 1 or len(harness) != 1:
        violations.append("Exactly one production jar and one pinned test harness required")
    if expected_artifact and (len(production) != 1 or sha(production[0]) != expected_artifact):
        violations.append("Production artifact does not match this pass's pinned hash")
    if expected_harness and (len(harness) != 1 or sha(harness[0]) != expected_harness):
        violations.append("Test harness differs from pinned baseline/candidate harness")
    launch_args = runtime / "launch.args"
    disabled = "-Demfattachments.disable=true" in launch_args.read_text(encoding="utf-8") if launch_args.is_file() else None
    if expected_hooks and disabled != (expected_hooks == "disabled"):
        violations.append("Saved launch.args does not match expected production mixin mode; verify actual owned PID uses this exact argfile")
    watut = (config_dir / "watut-common.toml").read_text(encoding="utf-8")
    threshold = re.search(r"ticksToMarkPlayerIdle\s*=\s*(\d+)", watut)
    return dict(observedUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(), runtime=str(runtime),
                settingsReady=not violations, violations=violations,
                expectedSettings=SETTINGS, observedSettings={key: options.get(key) for key in SETTINGS},
                emfPreventFirstPersonHandAnimating=emf.get("preventFirstPersonHandAnimating"),
                dynamicFpsEnabled=dynamic.get("enabled"), firstPersonJarNames=firstperson,
                renderConfigHashes=config_hashes, resourcePackOrder=options.get("resourcePacks"),
                activeModInventory=[dict(name=p.name, bytes=p.stat().st_size, modifiedNs=p.stat().st_mtime_ns) for p in mods],
                productionArtifacts=[artifact(p) for p in production], harnessArtifacts=[artifact(p) for p in harness],
                disableProductionMixinsInLaunchArgs=disabled, launchArgsFileSha256=sha(launch_args) if launch_args.is_file() else None,
                watutIdleThresholdTicks=int(threshold.group(1)) if threshold else None,
                watutIdleRuntimeState="Not observed; do not infer current status from config or disk save",
                savedWorld=world_metadata(runtime / "saves/AttachmentTest/level.dat"),
                limits=["Inventory names/sizes/mtimes are a change detector, not a cryptographic proof for every third-party jar.",
                        "This does not assert running-window focus, actual framebuffer size or offline identity; native fixture validates those."])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime", type=Path, default=ROOT / ".local/without-firstperson/.local/client")
    parser.add_argument("--output", type=Path, default=PERF / "crowd-controlled-plan")
    parser.add_argument("--candidate", type=Path, default=ROOT / "build/libs/emf-attachments-compat-1.21.1-1.2.3.jar")
    parser.add_argument("--harness", type=Path, default=ROOT / "build/libs/emf-attachments-compat-1.21.1-1.2.3-test-harness.jar")
    parser.add_argument("--audit-name", default="settings-audit-preparation")
    parser.add_argument("--expected-production-sha256")
    parser.add_argument("--expected-harness-sha256")
    parser.add_argument("--expected-hooks", choices=("enabled", "disabled"))
    parser.add_argument("--comparison", choices=("hooks", "versions"), default="hooks")
    args = parser.parse_args()
    runtime, output = args.runtime.resolve(), args.output.resolve()
    runtime.relative_to((ROOT / ".local").resolve())
    output.relative_to(PERF.resolve())
    if runtime.parts[-2:] != (".local", "client") or not re.fullmatch(r"[a-zA-Z0-9._-]+", args.audit_name):
        raise ValueError("Disposable runtime and simple audit name required")
    output.mkdir(parents=True, exist_ok=True)
    audit = inspect(runtime, args.expected_production_sha256, args.expected_harness_sha256, args.expected_hooks)
    (output / f"{args.audit_name}.json").write_text(json.dumps(audit, indent=2) + "\n", encoding="utf-8")
    artifacts = {"A": artifact(args.candidate if args.comparison == "hooks" else BASELINE), "B": artifact(args.candidate), "harness": artifact(args.harness)}
    if args.harness.is_file():
        with zipfile.ZipFile(args.harness) as jar:
            names = set(jar.namelist())
            artifacts["harness"]["crowdHelperPresent"] = "dev/caleb/emfattachments/test/CrowdFrameSequence.class" in names
            artifacts["harness"]["crowdMixinPresent"] = "dev/caleb/emfattachments/test/mixin/CrowdFrameRenderMixin.class" in names
            artifacts["harness"]["crowdMixinRegistered"] = "CrowdFrameRenderMixin" in jar.read("emfattachments-test.mixins.json").decode()
            artifacts["harness"]["crowdBootstrapReferencePresent"] = b"CrowdFrameSequence" in jar.read("dev/caleb/emfattachments/test/AttachmentTestMod.class")
    passes = []
    for i, mode in enumerate("ABBA", 1):
        version = ("123-hooks-off" if mode == "A" else "123-hooks-on") if args.comparison == "hooks" else ("122" if mode == "A" else "123")
        label = f"crowd-no-fpm-{version}-pass{i}-r1"
        queue_path = output / f"pass-{i}-{mode.lower()}.queue.json"
        queue = [dict(label=label, trigger="emfattachments-crowd-frames.txt", report="crowd-frames.json",
                      options="warmupSeconds=15\nmeasureSeconds=20", startupTimeoutSeconds=180, timeoutSeconds=360,
                      waitFor=["ownedClonesRemoved", "inputRestored", "cameraRestored", "positionUnchanged", "yawPitchRestored"])]
        queue_path.write_text(json.dumps(queue, indent=2) + "\n", encoding="utf-8")
        passes.append(dict(order=i, mode=mode, production=artifacts[mode], harness=artifacts["harness"],
                           queuePath=str(queue_path), expectedFirstPersonInstalled=False,
                           disableProductionMixins=args.comparison == "hooks" and mode == "A",
                           launchFunction=dict(function="scripts.test_runtime.launch", baseline=args.comparison == "hooks" and mode == "A", auto_sweep=False),
                           coldRestartRequired=True, startingWorldSnapshotMustMatch=True,
                           dispatchCommand=["python", str(ROOT / "scripts/run_test_queue.py"), str(queue_path), "--runtime", str(runtime)]))
    plan = dict(status="PREPARED_NOT_EXECUTED", createdUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                runtime=str(runtime), comparison=args.comparison, expectedSettings=SETTINGS,
                settingSemantics="Installed1.21.1 skips native limitDisplayFPS at260 or higher; maxFps260 means native uncapped, not a hard260FPS limit.",
                configPatchProposalOnly={"dynamic_fps.json": {"enabled": False}, "entity_model_features.json": {"preventFirstPersonHandAnimating": False}},
                noRuntimeMutations=True, artifacts=artifacts, passes=passes,
                constraints=["Run only one game process; root focuses the owned test window before dispatch.",
                             "Keep identical harness, heap, resourcepacks/shaderpack/options and other mods. In hooks mode keep the exact1.2.3jar and change only -Demfattachments.disable=true forA; property omitted forB. Versions mode instead swaps only1.2.2/1.2.3 between exits.",
                             "scripts.test_runtime CLI calls prepare and resets options to60FPS/distance4. Root must prepare first, apply the proposed disposable settings after that, then call launch(baseline=...,auto_sweep=False) without preparing again. This plan performs neither action.",
                             "Before each dispatch rerun this read-only helper with --audit-name passN-before and pinned expected jar/harness hashes plus --expected-hooks enabled/disabled. Confirm the actual owned PID uses the hashed launch.args; artifact-only manifests cannot distinguish hooks mode.",
                             "Restore the same previously saved disposable world snapshot before each pass; never use or modify user save files.",
                             "Wait60seconds after world rendering begins, then dispatch immediately. With105seconds of native phases the run fits below the configured300second WATUT idle threshold on a fresh client session; this is a scheduling bound, not an observed runtime AFK flag.",
                             "Record disk world metadata before launch and after clean exit. If weather/time or rendered AFK pose differs, flag or repeat; current harness does not record live worldtime/weather/WATUT per phase.",
                             "No other harness triggers, GL stack trace, screenshots, or config writes during sampling. Each stage's own post-measurement PNG is excluded before next warmup.",
                             "Do not compare small differences without repeatability. ABBA requires fresh process/world and identical native settings audits for allfourpasses."],
                jfr=dict(optional=True, notStarted=True,
                         preferred="First unprofiledABBA for frame timing; additional pairedA/B with identical JFR default settings for attribution. Do not pool profiled and unprofiled samples.",
                         commandTemplate=["C:/Program Files/Java/jdk-21/bin/jcmd.exe", "<verified-owned-test-pid>", "JFR.start", "name=crowd", "settings=default", "duration=140s", "maxsize=128m", "filename=<absolute-workspace-output>.jfr"],
                         attachTiming="Only after process identity verified and60second warmup, immediately before dispatch. No user-process attach.",
                         localJdkSettings={"executionSamplePeriod": "20ms", "nativeMethodSamplePeriod": "20ms", "allocationSampleThrottle": "150/s", "gcEvents": "enabled"},
                         focus=["Render thread stack share", "allocation samples and GC pause durations", "EMF/FA/player-layer cost by crowd phase", "No GPU-cause claim from CPU sampling alone"]),
                residualWithoutMixins={"gate": "OptionalMixinPlugin.shouldApplyMixin returns false immediately for every production mixin when Boolean.getBoolean(emfattachments.disable) istrue.",
                                      "remains": ["Same mod/config and config-screen registration", "Bundled mixin support library", "FirstPersonPoseStates ClientTickEvent listener still registers; with FirstPerson absent it keeps the empty state disabled", "Same test harness callbacks and native pack"],
                                      "meaning": "All production mixins disabled, not full mod uninstall. No render correction executes from those mixins."},
                analysis=dict(compare="Same-crowdAversusB median/p95/p99 frameintervals, GC deltas, rendered clone counts and screenshot visibility; report both repeats and spread. Fourruns are descriptive, not a statistical guarantee.",
                              frameMeans="Use milliseconds as primary; averageFPS is secondary. Never average reciprocal per-frameFPS values.",
                              caveats=["Crowd stages increase0→8→24, so count effect is confounded with within-run time/cache progression; compare versions at the same stage.",
                                       "Native clone rendering lacks real network/player skin/download/update work. This cannot establish absolute multiplayerFPS.",
                                       "The allocation-optimized arm scopes are FirstPerson-gated and absent in this requested test. A null version difference does not negate their separately measured helper-allocation reduction.",
                                       "Render returns exclude Iris shadows but not every other offscreen pass. Twelve post-sample screenshots cover128synthetic instances acrossfourpasses and require visual validation."]))
    (output / "execution-plan.json").write_text(json.dumps(plan, indent=2) + "\n", encoding="utf-8")
    (output / "options-overrides-proposed.json").write_text(json.dumps(SETTINGS, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(dict(plan=str(output / "execution-plan.json"), settingsReady=audit["settingsReady"],
                         mismatches=audit["violations"], runtimeModified=False, launched=False), indent=2))


if __name__ == "__main__":
    main()
