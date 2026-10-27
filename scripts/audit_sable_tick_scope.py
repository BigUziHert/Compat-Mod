#!/usr/bin/env python3
"""Read class files directly; audit one fresh Sable/Entity Culling runtime export.

No game/process interaction, compilation, or runtime edits. Prints a bounded JSON
report. --output optionally saves that same report where the caller requests.
"""
import argparse
import datetime as dt
import hashlib
import json
import pathlib
import struct
import sys
import zipfile


RESOLVER = "dev/caleb/emfattachments/client/SablePlayerOcclusion"
HELPER = "dev/tr7zw/entityculling/NMSCullingHelper"
TICK_OWNER = "dev.tr7zw.entityculling.mixin.ClientWorldMixin"
RENDER_OWNER = "dev.tr7zw.entityculling.mixin.WorldRendererMixin"
CLIENT = "net/minecraft/client/multiplayer/ClientLevel"
RENDERER = "net/minecraft/client/renderer/LevelRenderer"
ENTITY_BOOL = "(Lnet/minecraft/world/entity/Entity;)Z"
POLICY_DESC = "(ZLnet/minecraft/world/entity/Entity;)Z"


class Reader:
    def __init__(self, data):
        self.data, self.pos = data, 0

    def take(self, n):
        value = self.data[self.pos:self.pos + n]
        if len(value) != n:
            raise ValueError("Truncated class file")
        self.pos += n
        return value

    def u1(self): return self.take(1)[0]
    def u2(self): return struct.unpack(">H", self.take(2))[0]
    def u4(self): return struct.unpack(">I", self.take(4))[0]


class ClassFile:
    def __init__(self, data):
        r = Reader(data)
        if r.u4() != 0xCAFEBABE:
            raise ValueError("Not a Java class")
        self.minor, self.major = r.u2(), r.u2()
        self.cp = [None] * r.u2()
        i = 1
        while i < len(self.cp):
            tag = r.u1()
            if tag == 1:
                val = r.take(r.u2()).decode("utf-8", errors="replace")
            elif tag in (3, 4): val = r.take(4).hex()
            elif tag in (5, 6): val = r.take(8).hex()
            elif tag in (7, 8, 16, 19, 20): val = r.u2()
            elif tag in (9, 10, 11, 12, 17, 18): val = (r.u2(), r.u2())
            elif tag == 15: val = (r.u1(), r.u2())
            else: raise ValueError(f"Unknown constant-pool tag {tag}")
            self.cp[i] = (tag, val)
            i += 2 if tag in (5, 6) else 1
        self.access, this_class, self.super_class = r.u2(), r.u2(), r.u2()
        self.name = self.class_name(this_class)
        for _ in range(r.u2()): r.u2()
        for _ in range(r.u2()): self.member(r)
        self.methods = [self.member(r) for _ in range(r.u2())]
        self.annotations = self.annotations_from(self.attributes(r))
        for method in self.methods:
            method["calls"] = self.calls(method.pop("attributes"))

    def utf(self, i): return self.cp[i][1]
    def class_name(self, i): return self.utf(self.cp[i][1])

    def attributes(self, r):
        return [(self.utf(r.u2()), r.take(r.u4())) for _ in range(r.u2())]

    def member(self, r):
        access, name, desc = r.u2(), self.utf(r.u2()), self.utf(r.u2())
        attrs = self.attributes(r)
        return {"access": access, "name": name, "descriptor": desc,
                "annotations": self.annotations_from(attrs), "attributes": attrs}

    def annotation(self, r):
        kind = self.utf(r.u2())
        return kind, {self.utf(r.u2()): self.element(r) for _ in range(r.u2())}

    def element(self, r):
        tag = chr(r.u1())
        if tag in "BCDFIJSZs": return self.utf(r.u2())
        if tag == "c": return self.utf(r.u2())
        if tag == "e": return [self.utf(r.u2()), self.utf(r.u2())]
        if tag == "@":
            kind, values = self.annotation(r)
            return {kind: values}
        if tag == "[": return [self.element(r) for _ in range(r.u2())]
        raise ValueError(f"Unknown annotation element {tag}")

    def annotations_from(self, attrs):
        result = {}
        for name, raw in attrs:
            if name in ("RuntimeVisibleAnnotations", "RuntimeInvisibleAnnotations"):
                r = Reader(raw)
                for _ in range(r.u2()):
                    kind, values = self.annotation(r)
                    result[kind] = values
        return result

    def calls(self, attrs):
        results = []
        for name, raw in attrs:
            if name != "Code": continue
            r = Reader(raw)
            r.u2(); r.u2()
            code = r.take(r.u4())
            pc = 0
            while pc < len(code):
                op = code[pc]
                if op in (182, 183, 184, 185):
                    idx = struct.unpack_from(">H", code, pc + 1)[0]
                    owner_idx, nt_idx = self.cp[idx][1]
                    name_idx, desc_idx = self.cp[nt_idx][1]
                    results.append({"offset": pc, "opcode": op,
                                    "owner": self.class_name(owner_idx),
                                    "name": self.utf(name_idx),
                                    "descriptor": self.utf(desc_idx)})
                if op == 170:  # tableswitch
                    start = (pc + 4) & ~3
                    low, high = struct.unpack_from(">ii", code, start + 4)
                    size = start - pc + 12 + (high - low + 1) * 4
                elif op == 171:  # lookupswitch
                    start = (pc + 4) & ~3
                    pairs = struct.unpack_from(">i", code, start + 4)[0]
                    size = start - pc + 8 + pairs * 8
                elif op == 196: size = 6 if code[pc + 1] == 132 else 4
                elif op in (185, 186, 200, 201): size = 5
                elif op == 197: size = 4
                elif op in (17, 19, 20, 132, 167, 168, 187, 189, 192, 193, 198, 199) or 153 <= op <= 166 or 178 <= op <= 184: size = 3
                elif op in (16, 18, 169, 188) or 21 <= op <= 25 or 54 <= op <= 58: size = 2
                else: size = 1
                pc += size
            if pc != len(code): raise ValueError("Instruction boundary mismatch")
        return results


def digest(data): return hashlib.sha256(data).hexdigest()
def merged_owner(method):
    return method["annotations"].get("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;", {}).get("mixin")
def compact(method):
    return {"name": method["name"], "descriptor": method["descriptor"], "mergedOwner": merged_owner(method)}
def matches(call, owner, name, desc=None):
    return call["owner"] == owner and call["name"] == name and (desc is None or call["descriptor"] == desc)
def all_calls(cf): return [(m, c) for m in cf.methods for c in m["calls"]]


def audit(args):
    cutoff = dt.datetime.fromisoformat(args.not_before.replace("Z", "+00:00"))
    if cutoff.tzinfo is None: raise ValueError("--not-before must include a timezone")
    root = pathlib.Path(args.export_root)
    if (root / "class").is_dir(): root /= "class"
    evidence, checks = [], {}
    classes = {}
    for name in (CLIENT, RENDERER):
        p = root / (name + ".class")
        raw = p.read_bytes()
        classes[name] = ClassFile(raw)
        stamp = dt.datetime.fromtimestamp(p.stat().st_mtime, dt.timezone.utc)
        evidence.append({"path": str(p.resolve()), "sha256": digest(raw), "modifiedUtc": stamp.isoformat()})
        checks[name.rsplit("/", 1)[-1] + "ExportFresh"] = stamp >= cutoff
    client, renderer = classes[CLIENT], classes[RENDERER]
    policy_calls = [(m, c) for m, c in all_calls(client) if c["owner"] == RESOLVER]
    checks["exactlyOneClientLevelPolicyCall"] = len(policy_calls) == 1 and matches(policy_calls[0][1], RESOLVER, "keepsFullTick", POLICY_DESC)
    chain = {}
    if len(policy_calls) == 1:
        wrapper, policy_call = policy_calls[0]
        checks["policyWrapperOwnedByOurCullingMixin"] = merged_owner(wrapper) == "dev.caleb.emfattachments.mixin.SablePlayerEntityCullingMixin"
        chain["policyWrapper"] = compact(wrapper)
        chain["policyCall"] = policy_call
        callers = [(m, c) for m, c in all_calls(client) if matches(c, CLIENT, wrapper["name"], wrapper["descriptor"])]
        checks["policyWrapperHasOnlyOneCaller"] = len(callers) == 1
        if len(callers) == 1:
            tick, wrapper_call = callers[0]
            checks["callerOwnedByEntityCullingTickMixin"] = merged_owner(tick) == TICK_OWNER
            native = [c for c in tick["calls"] if matches(c, HELPER, "ignoresCulling", ENTITY_BOOL)]
            checks["oneNativeTickIgnoresCullingCall"] = len(native) == 1
            checks["nativeResultImmediatelyFeedsPolicyWrapper"] = len(native) == 1 and tick["calls"].index(wrapper_call) == tick["calls"].index(native[0]) + 1
            entries = [(m, c) for m, c in all_calls(client) if matches(c, CLIENT, tick["name"], tick["descriptor"])]
            # The test harness WrapMethod may move the native method body into
            # tickNonPassenger$... while retaining its descriptor. Do not guess
            # any unrelated generated handler name; unknown shapes fail closed.
            checks["tickCallbackReachedOnlyFromTickNonPassengerBody"] = len(entries) == 1 and entries[0][0]["descriptor"] == "(Lnet/minecraft/world/entity/Entity;)V" and (entries[0][0]["name"] == "tickNonPassenger" or entries[0][0]["name"].startswith("tickNonPassenger$"))
            chain["tickCallback"] = compact(tick)
            chain["nativeTickCall"] = native
            chain["tickCallbackCallers"] = [compact(m) for m, _ in entries]
    checks["noPolicyCallsAnywhereInWovenLevelRenderer"] = not any(c["owner"] == RESOLVER for _, c in all_calls(renderer))
    render_methods = [m for m in renderer.methods if merged_owner(m) == RENDER_OWNER and any(matches(c, HELPER, "ignoresCulling", ENTITY_BOOL) for c in m["calls"])]
    checks["oneNativeEntityCullingRenderCallback"] = len(render_methods) == 1
    render_proof = []
    for method in render_methods:
        native = [c for c in method["calls"] if matches(c, HELPER, "ignoresCulling", ENTITY_BOOL)]
        checks["renderCallbackStillChecksNativeHelperOnce"] = len(native) == 1
        checks["renderCallbackStillChecksCulledState"] = any(c["name"] == "isCulled" and "entityculling" in c["owner"] for c in method["calls"])
        checks["renderCallbackStillCanCancel"] = any(matches(c, "org/spongepowered/asm/mixin/injection/callback/CallbackInfo", "cancel", "()V") for c in method["calls"])
        render_proof.append({**compact(method), "relevantCalls": [c for c in method["calls"] if c in native or c["name"] in ("cancel", "isCulled", "isForcedVisible")]})
    jar = pathlib.Path(args.entityculling_jar)
    with zipfile.ZipFile(jar) as z: raw = z.read(HELPER + ".class")
    helper = ClassFile(raw)
    checks["installedGlobalHelperHasNoPolicyCalls"] = not any(c["owner"] == RESOLVER for _, c in all_calls(helper))
    evidence.append({"path": str(jar.resolve()), "sha256": digest(jar.read_bytes()), "member": HELPER + ".class", "memberSha256": digest(raw)})
    production = pathlib.Path(args.production_jar)
    target_rows = []
    with zipfile.ZipFile(production) as z:
        for name in z.namelist():
            if not name.startswith("dev/caleb/emfattachments/mixin/") or not name.endswith(".class"): continue
            cf = ClassFile(z.read(name))
            ann = cf.annotations.get("Lorg/spongepowered/asm/mixin/Mixin;", {})
            targets = ann.get("value", []) + ann.get("targets", [])
            target_rows.append({"mixin": cf.name, "targets": targets})
    checks["noProductionMixinTargetsGlobalHelper"] = not any(HELPER in t.replace(".", "/") for row in target_rows for t in row["targets"])
    ours = [row for row in target_rows if row["mixin"].endswith("/SablePlayerEntityCullingMixin")]
    checks["productionCullingMixinTargetsOnlyClientLevel"] = len(ours) == 1 and ours[0]["targets"] == ["L" + CLIENT + ";"]
    evidence.append({"path": str(production.resolve()), "sha256": digest(production.read_bytes())})
    exported_helper = root / (HELPER + ".class")
    helper_scope = "Installed helper bytecode plus production mixin targets; not a separately exported live helper class."
    if exported_helper.exists():
        hraw = exported_helper.read_bytes(); live = ClassFile(hraw)
        checks["exportedGlobalHelperFresh"] = exported_helper.stat().st_mtime >= cutoff.timestamp()
        checks["exportedGlobalHelperHasNoPolicyCalls"] = not any(c["owner"] == RESOLVER for _, c in all_calls(live))
        evidence.append({"path": str(exported_helper.resolve()), "sha256": digest(hraw)})
        helper_scope = "Also inspected fresh exported global helper."
    return {"status": "PASS" if all(checks.values()) else "FAIL", "checkedUtc": dt.datetime.now(dt.timezone.utc).isoformat(),
            "freshnessCutoff": cutoff.isoformat(), "checks": checks, "tickCallChain": chain,
            "nativeRenderCancellationEvidence": render_proof, "productionCullingTarget": ours,
            "globalHelperProofScope": helper_scope, "evidence": evidence,
            "limits": ["Structural bytecode scope audit, not proof of natural occlusion, visible pixels, full tick behavior or timing.",
                       "Installed global-helper evidence does not rule out unrelated third-party transformations when that live class is not exported.",
                       "Freshness relies on the supplied launch cutoff and file timestamps; caller must archive/remove stale export folders before launch."]}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--export-root", required=True, help="Fresh .mixin.out or its class directory")
    p.add_argument("--not-before", required=True, help="Launch UTC/offset timestamp; older exports fail")
    p.add_argument("--production-jar", required=True)
    p.add_argument("--entityculling-jar", required=True)
    p.add_argument("--output", help="Optional report path; no output file written unless specified")
    args = p.parse_args()
    try:
        result = audit(args)
    except Exception as exc:
        result = {"status": "ERROR", "error": f"{type(exc).__name__}: {exc}"}
    encoded = json.dumps(result, indent=2)
    if args.output: pathlib.Path(args.output).write_text(encoded + "\n", encoding="utf-8")
    print(encoded)
    return 0 if result["status"] == "PASS" else 1


if __name__ == "__main__": sys.exit(main())
