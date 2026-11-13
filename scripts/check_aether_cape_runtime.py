"""Check the native Aether cape helper in disposable, matched client processes.

Reuses the optional-runtime copier: user saves/settings are never changed. The
probe exercises the actual woven helper and measures per-thread allocations,
not FPS. Candidate/control differ only by the cape lookup startup opt-out.
"""
import argparse
import ctypes
import json
from pathlib import Path
import re
import shutil
import statistics
import subprocess

import check_optional_runtime as runtime
import test_runtime
from audit_sable_tick_scope import ClassFile

ROOT = Path(__file__).resolve().parents[1]
EVIDENCE = ROOT / '.local/accessory-fps-20261112'
TARGET = 'com/aetherteam/aether/mixin/AetherMixinHooks'
PROBE_SOURCE = r'''
package dev.caleb.optionalsmoke;
import com.aetherteam.aether.mixin.AetherMixinHooks;
import com.aetherteam.aether.item.accessories.cape.CapeItem;
import com.google.gson.GsonBuilder;
import io.wispforest.accessories.Accessories;
import io.wispforest.accessories.api.AccessoriesCapability;
import io.wispforest.accessories.api.AccessoriesContainer;
import io.wispforest.accessories.impl.AccessoriesContainerImpl;
import io.wispforest.accessories.impl.ExpandedSimpleContainer;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value="optional_smoke", dist=Dist.CLIENT)
public final class OptionalSmoke {
  private static int ticks;
  private static boolean finished;
  private static volatile ItemStack sink;
  private static final List<Map<String,Object>> checks = new ArrayList<>();
  private static final List<Map<String,Object>> allocation = new ArrayList<>();
  public OptionalSmoke() { NeoForge.EVENT_BUS.addListener(OptionalSmoke::tick); }
  private static ItemStack item(String id) {
    var value=BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
    if(value==Items.AIR)throw new IllegalStateException("Missing fixture item "+id);
    return new ItemStack(value);
  }
  private static String id(ItemStack s) { return BuiltInRegistries.ITEM.getKey(s.getItem()).toString(); }
  private static void require(boolean value,String message) {if(!value)throw new IllegalStateException(message);}
  private static void tick(ClientTickEvent.Post ignored) {
    var mc=Minecraft.getInstance();
    if(finished||mc.player==null||mc.level==null||mc.screen!=null||++ticks<100)return;
    try {
      require(mc.gameDirectory.toPath().toAbsolutePath().normalize().toString().replace('\\','/').contains("/.local/optional-emf/")
          && mc.getUser().getType()==User.Type.LEGACY && "AttachmentTest".equals(mc.getUser().getName())
          && mc.hasSingleplayerServer() && !mc.getSingleplayerServer().isPublished(),"Requires disposable offline world");
      mc.getWindow().setTitle("Aether cape native helper check | "+mc.gameDirectory);
      check(mc);
      finish(mc,"PASS",null);
    }catch(Throwable error){error.printStackTrace();finish(mc,"FAIL",error.toString());}
  }
  private static void configure(AccessoriesContainer c, ItemStack actual, ItemStack cosmetic, boolean visible, boolean showCosmetics) {
    c.getAccessories().setItem(0,actual);
    c.getCosmeticAccessories().setItem(0,cosmetic);
    c.renderOptions().set(0,visible);
    Accessories.config().clientOptions.showCosmeticAccessories(showCosmetics);
  }
  private static void verify(Minecraft mc, AccessoriesContainer c,String label,ItemStack actual,ItemStack cosmetic,
                             boolean visible,boolean cosmetics,String expected) {
    configure(c,actual,cosmetic,visible,cosmetics);
    ItemStack found=AetherMixinHooks.isCapeVisible(mc.player);
    require(id(found).equals(expected),label+": expected "+expected+" got "+id(found));
    checks.add(Map.of("label",label,"size",c.getSize(),"actual",id(actual),"cosmetic",id(cosmetic),
        "visible",visible,"showCosmetics",cosmetics,"returned",id(found)));
  }
  private static void semantics(Minecraft mc,AccessoriesContainer c,String prefix) {
    verify(mc,c,prefix+"-empty",ItemStack.EMPTY,ItemStack.EMPTY,true,true,"minecraft:air");
    verify(mc,c,prefix+"-actual",item("aether:blue_cape"),ItemStack.EMPTY,true,true,"aether:blue_cape");
    verify(mc,c,prefix+"-actual-hidden",item("aether:blue_cape"),ItemStack.EMPTY,false,true,"minecraft:air");
    verify(mc,c,prefix+"-cosmetic",item("aether:blue_cape"),item("aether:red_cape"),true,true,"aether:red_cape");
    verify(mc,c,prefix+"-cosmetic-disabled",item("aether:blue_cape"),item("aether:red_cape"),true,false,"aether:blue_cape");
    verify(mc,c,prefix+"-cosmetic-only",ItemStack.EMPTY,item("aether:red_cape"),true,true,"aether:red_cape");
    verify(mc,c,prefix+"-cosmetic-hidden",item("aether:blue_cape"),item("aether:red_cape"),false,true,"minecraft:air");
    verify(mc,c,prefix+"-restored",item("aether:blue_cape"),ItemStack.EMPTY,true,true,"aether:blue_cape");
  }
  private static void field(AccessoriesContainer c,String name,Object value) throws Exception {
    var field=AccessoriesContainerImpl.class.getDeclaredField(name);field.setAccessible(true);field.set(c,value);
  }
  private static void resizeFixture(AccessoriesContainer c,int requested) throws Exception {
    // Client update deliberately ignores slot-size modifiers. Synthetic sizes
    // use native inventory objects on this disposable owner, restored finally.
    field(c,"accessories",new ExpandedSimpleContainer((AccessoriesContainerImpl)c,requested,"accessories"));
    field(c,"cosmeticAccessories",new ExpandedSimpleContainer((AccessoriesContainerImpl)c,requested,"cosmetic_accessories"));
    field(c,"renderOptions",new ArrayList<>(Collections.nCopies(requested,true)));
    require(c.getSize()==requested,"Fixture size expected "+requested+" got "+c.getSize());
  }
  private static void allocations(Minecraft mc,AccessoriesContainer c) {
    configure(c,ItemStack.EMPTY,ItemStack.EMPTY,true,true);
    var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
    require(bean.isThreadAllocatedMemorySupported(),"Allocation counter unavailable");
    if(!bean.isThreadAllocatedMemoryEnabled())bean.setThreadAllocatedMemoryEnabled(true);
    long thread=Thread.currentThread().threadId();
    for(int i=0;i<20000;i++)sink=AetherMixinHooks.isCapeVisible(mc.player);
    for(int pass=0;pass<3;pass++) {
      long before=bean.getThreadAllocatedBytes(thread), start=System.nanoTime();
      for(int i=0;i<20000;i++)sink=AetherMixinHooks.isCapeVisible(mc.player);
      long nanos=System.nanoTime()-start, bytes=bean.getThreadAllocatedBytes(thread)-before;
      allocation.add(Map.of("pass",pass,"calls",20000,"allocatedBytes",bytes,"bytesPerCall",bytes/20000.0,
          "elapsedNanos",nanos,"nanosPerCall",nanos/20000.0,"size",c.getSize(),"returned",id(sink)));
    }
  }
  private static void check(Minecraft mc) throws Exception {
    var capability=AccessoriesCapability.get(mc.player);
    require(capability!=null,"Missing player capability");
    var c=capability.getContainer(CapeItem.getStaticIdentifier());
    require(c!=null,"Missing native cape container");
    int originalSize=c.getSize();
    require(originalSize==1,"Expected ordinary one-slot cape container; got "+originalSize);
    var actual=c.getAccessories().getItem(0).copy();var cosmetic=c.getCosmeticAccessories().getItem(0).copy();
    var originalActual=c.getAccessories();var originalCosmetic=c.getCosmeticAccessories();var originalOptions=c.renderOptions();
    boolean visible=c.shouldRender(0), showCosmetics=Accessories.config().clientOptions.showCosmeticAccessories();
    try {
      semantics(mc,c,"single");allocations(mc,c);
      resizeFixture(c,0);
      require(AetherMixinHooks.isCapeVisible(mc.player).isEmpty(),"Empty cape container returned a stack");
      checks.add(Map.of("label","zero","size",0,"returned","minecraft:air","syntheticSize",true));
      resizeFixture(c,2);
      // Both slots deliberately contain equal stacks. The upstream unordered
      // parallel findFirst is allowed to choose either; do not assert slot 0.
      for(int i=0;i<2;i++) {
        c.getAccessories().setItem(i,item("aether:blue_cape"));
        c.getCosmeticAccessories().setItem(i,item("aether:red_cape"));
        c.renderOptions().set(i,true);
      }
      Accessories.config().clientOptions.showCosmeticAccessories(true);
      require(id(AetherMixinHooks.isCapeVisible(mc.player)).equals("aether:red_cape"),"Multi-slot cosmetic mismatch");
      checks.add(Map.of("label","multi-cosmetic","size",2,"returned","aether:red_cape","syntheticSize",true));
      Accessories.config().clientOptions.showCosmeticAccessories(false);
      require(id(AetherMixinHooks.isCapeVisible(mc.player)).equals("aether:blue_cape"),"Multi-slot actual mismatch");
      checks.add(Map.of("label","multi-actual","size",2,"returned","aether:blue_cape","syntheticSize",true));
      c.renderOptions().set(0,false);c.renderOptions().set(1,false);
      require(AetherMixinHooks.isCapeVisible(mc.player).isEmpty(),"Multi-slot hidden mismatch");
      checks.add(Map.of("label","multi-hidden","size",2,"returned","minecraft:air","syntheticSize",true));
    }finally {
      field(c,"accessories",originalActual);field(c,"cosmeticAccessories",originalCosmetic);field(c,"renderOptions",originalOptions);
      configure(c,actual,cosmetic,visible,showCosmetics);
      require(c.getAccessories()==originalActual&&c.getCosmeticAccessories()==originalCosmetic&&c.renderOptions()==originalOptions
          &&ItemStack.matches(c.getAccessories().getItem(0),actual)
          &&ItemStack.matches(c.getCosmeticAccessories().getItem(0),cosmetic)
          &&c.shouldRender(0)==visible
          &&Accessories.config().clientOptions.showCosmeticAccessories()==showCosmetics,"Restoration failed");
    }
  }
  private static void finish(Minecraft mc,String status,String error) {
    finished=true;
    try {
      var result=new LinkedHashMap<String,Object>();result.put("status",status);result.put("error",error);
      result.put("nativeParallelControl",Boolean.getBoolean("emfattachments.aetherParallelCapeLookup"));
      result.put("capeHandlers",Arrays.stream(AetherMixinHooks.class.getDeclaredMethods())
          .map(m->m.getName()).filter(n->n.contains("emfattachments$")).sorted().toList());
      result.put("checks",checks);result.put("allocation",allocation);result.put("thread",Thread.currentThread().getName());
      var mods=new TreeMap<String,String>();ModList.get().getMods().forEach(m->mods.put(m.getModId(),m.getVersion().toString()));
      result.put("loadedMods",mods);result.put("restored",status.equals("PASS"));
      Files.writeString(mc.gameDirectory.toPath().resolve("cape-smoke.json"),new GsonBuilder().setPrettyPrinting().create().toJson(result));
    }catch(Exception error2){error2.printStackTrace();}
    mc.stop();
  }
}
'''


def probe():
    runtime.PROBE_SOURCE = PROBE_SOURCE
    jar = runtime.build_probe()
    destination = EVIDENCE / 'cape-probe' / runtime.sha(jar)[:12]
    destination.mkdir(parents=True, exist_ok=True)
    for name in ('OptionalSmoke.java', 'optional-smoke.jar', 'javac.args', 'compile-classpath.txt'):
        shutil.copy2(runtime.BASE / 'probe' / name, destination / name)
    return jar


def collect(args, directory):
    process = json.loads((directory / 'process.json').read_text())
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel.OpenProcess.restype = ctypes.c_void_p
    kernel.GetExitCodeProcess.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_ulong)]
    kernel.CloseHandle.argtypes = [ctypes.c_void_p]
    handle = kernel.OpenProcess(0x1000, False, process['pid'])
    if handle:
        try:
            code = ctypes.c_ulong()
            assert kernel.GetExitCodeProcess(handle, ctypes.byref(code))
            assert code.value == 0, f'Client running or unsuccessful exit: {code.value}'
        finally:
            kernel.CloseHandle(handle)
    elif ctypes.get_last_error() != 87:
        raise ctypes.WinError(ctypes.get_last_error())
    result = json.loads((directory / 'cape-smoke.json').read_text())
    prepared = json.loads((directory / 'preparation.json').read_text())
    log = (directory / 'logs/latest.log').read_text(encoding='utf-8', errors='replace')
    jvm = re.search(r'JVM identified as ([^\r\n]+)', log)
    assert jvm, 'Missing runtime JVM identity'
    assert result['status'] == 'PASS', result
    assert result['restored'] and len(result['checks']) == 12 and len(result['allocation']) == 3
    assert result['loadedMods']['emfattachments'] == args.version
    assert 'Stopping!' in log and 'FATAL' not in log and 'Exception caught during firing event' not in log
    assert not list((directory / 'crash-reports').glob('*.txt'))
    assert runtime.sha(directory / 'mods' / Path(prepared['release']).name) == prepared['releaseSha256']
    exported = directory / '.mixin.out/class' / (TARGET + '.class')
    cape_handler = bool(result['capeHandlers'])
    assert cape_handler != result['nativeParallelControl'], 'Runtime class handlers do not match intended mode'
    calls = []
    if exported.is_file():
        actual = ClassFile(exported.read_bytes())
        method = next(m for m in actual.methods if m['name'] == 'isCapeVisible')
        calls = [c for c in method['calls'] if 'avoidSingleSlotParallelLookup' in c['name']]
        assert len(calls) == (0 if result['nativeParallelControl'] else 2), 'Unexpected actual injection-site count'
        text = subprocess.check_output([str(runtime.JAVA / 'javap.exe'), '-p', '-c', str(exported)], text=True)
    else:
        assert result['nativeParallelControl'], 'Missing actual candidate mixin export'
        # With this only mixin disabled, Mixin may leave the native class
        # untransformed, hence unexported. Reflection proves no handler exists.
        text = 'Native control class has no added cape handlers; Mixin did not export an untransformed class.\n'
    output = EVIDENCE / args.label
    output.mkdir(parents=True, exist_ok=True)
    for name in ('cape-smoke.json', 'preparation.json', 'process.json', 'console.log', 'console-error.log'):
        shutil.copy2(directory / name, output / name)
    if exported.is_file():
        shutil.copy2(exported, output / 'AetherMixinHooks.class')
    (output / 'AetherMixinHooks.javap.txt').write_text(text, encoding='utf-8')
    (output / 'collection.json').write_text(json.dumps(dict(status='PASS', runtime=str(directory),
        resultSha256=runtime.sha(directory / 'cape-smoke.json'), exportSha256=runtime.sha(exported) if exported.is_file() else None,
        releaseSha256=prepared['releaseSha256'], expectedCapeHandler=not result['nativeParallelControl'],
        actualCapeHandler=cape_handler, actualInjectionSites=len(calls), jvm=jvm.group(1),
        processExited=True, nativeShutdown=True), indent=2)+'\n')
    print(json.dumps(dict(status='PASS', output=str(output), allocation=result['allocation'])))


def compare(candidate_label, control_label):
    candidates = []
    for label in (candidate_label, control_label):
        if not label or any(c not in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_' for c in label):
            raise ValueError('Invalid comparison label')
        directory = EVIDENCE / label
        candidates.append((json.loads((directory / 'cape-smoke.json').read_text()),
                           json.loads((directory / 'preparation.json').read_text()),
                           json.loads((directory / 'collection.json').read_text())))
    (candidate, cp, cc), (control, bp, bc) = candidates
    assert candidate['status'] == control['status'] == cc['status'] == bc['status'] == 'PASS'
    assert not candidate['nativeParallelControl'] and control['nativeParallelControl']
    assert cp['releaseSha256'] == bp['releaseSha256'] and cp['mods'] == bp['mods']
    assert cp['probeSha256'] == bp['probeSha256'] and cp['sourceWorldLevelSha256'] == bp['sourceWorldLevelSha256']
    assert candidate['checks'] == control['checks'] and candidate['loadedMods'] == control['loadedMods']
    assert cc['actualInjectionSites'] == 2 and bc['actualInjectionSites'] == 0
    assert cc['jvm'] == bc['jvm']
    after = statistics.median(r['bytesPerCall'] for r in candidate['allocation'])
    before = statistics.median(r['bytesPerCall'] for r in control['allocation'])
    result = dict(status='PASS_NATIVE_HELPER_COMPARISON', candidateLabel=candidate_label, controlLabel=control_label,
        productionSha256=cp['releaseSha256'], probeSha256=cp['probeSha256'], matchedMods=True, jvm=cc['jvm'],
        semanticChecksEqual=True, checksPerRun=len(candidate['checks']), ordinarySingleSlotChecks=8,
        syntheticInventorySizeChecks=4, syntheticSizeMethod='Temporarily substitute native ExpandedSimpleContainer objects; restore original inventory identities finally.',
        candidateInjectionSites=cc['actualInjectionSites'], controlInjectionSites=bc['actualInjectionSites'],
        candidateBytesPerCall=after, controlBytesPerCall=before, bytesSavedPerCall=before-after,
        allocationReductionPercent=100*(1-after/before), candidateAllocations=candidate['allocation'],
        controlAllocations=control['allocation'],
        limits=['One fresh process per mode; three helper allocation windows per process.',
                '20,000 warmup calls then three 20,000-call windows on the render thread.',
                'These are helper allocation measurements, not an FPS benchmark or proof of recovering the reported FPS loss.',
                'Synthetic zero/two-slot controls test native helper behavior but do not simulate gameplay slot resizing.',
                'No pixel/visual alignment certification; returned native item stacks and visibility policy were checked.',
                'These disposable checks use Java21.0.7; the original user profile used Java25.0.3.',
                'Initial candidate-r1 failed Mixin application due to a non-private static helper; it was corrected and excluded.'])
    path = EVIDENCE / 'cape-native-comparison.json'
    path.write_text(json.dumps(result, indent=2)+'\n')
    path.with_suffix('.md').write_text(
        '# Native Aether cape lookup check\n\n'
        f'Both fresh clients passed the same 12 native helper checks. The candidate applied exactly two argument changes; '
        f'the focused native control applied none. Both used production SHA-256 `{cp["releaseSha256"]}` and identical probe/mod jars. '
        f'Runtime: {cc["jvm"]}; the original live profile used Java25.0.3.\n\n'
        f'The ordinary one-slot empty-cape lookup allocated **{before:,.0f} bytes/call** in the native control and '
        f'**{after:,.0f} bytes/call** in the candidate, a **{100*(1-after/before):.2f}% reduction**. '
        'Each process warmed up 20,000 calls and measured three 20,000-call windows on its render thread.\n\n'
        'Eight ordinary one-slot scenarios check empty, actual, hidden, cosmetic replacement, disabled cosmetic selection, '
        'cosmetic-only, hidden cosmetic and subsequent actual selection. Four synthetic native-inventory-size scenarios '
        'check zero slots and two equal-stock slots with cosmetic, actual and hidden selection. Original inventory identities, '
        'items, visibility and cosmetic settings were restored.\n\n'
        'The initial candidate-r1 startup failure is retained separately. Its non-private static helper was corrected; '
        'only the completed candidate-r2 and focused control contribute results.\n\n'
        '**These are helper allocation and native return-value checks, not FPS measurements or visual certification.** '
        'The zero/two-slot fixtures substitute real native inventory objects temporarily; they do not reproduce gameplay resizing.\n', encoding='utf-8')
    print(json.dumps({k:v for k,v in result.items() if k not in ('candidateAllocations','controlAllocations','limits')}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('build-probe', 'prepare', 'launch', 'collect', 'compare'))
    parser.add_argument('--label', default='cape-candidate-r1')
    parser.add_argument('--version', default='1.2.14')
    parser.add_argument('--jar', type=Path, default=ROOT / 'build/libs/emf-attachments-compat-1.21.1-1.2.14.jar')
    parser.add_argument('--world', type=Path, default=ROOT / '.local/without-firstperson/.local/client/saves/AttachmentTest')
    parser.add_argument('--control', action='store_true')
    parser.add_argument('--probe-from', help='Reuse an earlier fresh runtime probe byte for byte')
    parser.add_argument('--candidate-label', default='cape-candidate-r2')
    parser.add_argument('--control-label', default='cape-control-r1')
    parser.add_argument('--emf', action=argparse.BooleanOptionalAction, default=True)
    args = parser.parse_args()
    if not args.label or any(c not in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_' for c in args.label):
        raise ValueError('Invalid evidence label')
    args.firstperson = None
    directory = runtime.BASE / args.label / '.local/client'
    if args.action == 'compare':
        compare(args.candidate_label,args.control_label)
    elif args.action == 'build-probe':
        print(probe())
    elif args.action == 'prepare':
        runtime.PROBE_SOURCE = PROBE_SOURCE
        source = runtime.BASE / 'probe/OptionalSmoke.java'
        if args.probe_from:
            if any(c not in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_' for c in args.probe_from):
                raise ValueError('Invalid probe source label')
            previous = runtime.BASE / args.probe_from / '.local/client'
            jar = previous / 'mods/optional-smoke.jar'
            if not jar.is_file():
                raise FileNotFoundError(jar)
            runtime.build_probe = lambda: jar
            source = previous / 'OptionalSmoke.java'
        directory = runtime.prepare(args)
        prepared = json.loads((directory / 'preparation.json').read_text())
        prepared['capeParallelControl'] = args.control
        prepared['runnerSha256'] = runtime.sha(__file__)
        prepared['purpose'] = 'Native helper behavior/allocation check; not an FPS benchmark'
        (directory / 'preparation.json').write_text(json.dumps(prepared, indent=2)+'\n')
        shutil.copy2(source, directory / 'OptionalSmoke.java')
    elif args.action == 'launch':
        assert not (directory / 'process.json').exists(), 'Use a fresh label to preserve prior evidence'
        prepared = json.loads((directory / 'preparation.json').read_text())
        test_runtime.RUNTIME = directory
        flags = ['-Dmixin.debug.export=true', '-Dmixin.debug.export.filter=' + TARGET.replace('/', '.')]
        if prepared['capeParallelControl']:
            flags += ['-Demfattachments.aetherParallelCapeLookup=true']
        test_runtime.launch(auto_sweep=False, extra_jvm=flags)
    else:
        collect(args, directory)


if __name__ == '__main__':
    main()
