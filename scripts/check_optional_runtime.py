"""Smoke-test the production jar in isolated copies with EMF/ETF present or absent.

Copies only an existing disposable AttachmentTest world, never user saves. A tiny
separate probe equips two native Accessories items, renders four camera views,
writes screenshots/results, and cleanly stops the client. This is a loading and
rendering smoke test, not a full gameplay or visual-alignment certification.
"""
import argparse
import ctypes
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

import test_runtime

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / '.local/optional-emf'
JAVA = Path('C:/Program Files/Java/jdk-21/bin')
PROBE_SOURCE = r'''
package dev.caleb.optionalsmoke;
import com.google.gson.GsonBuilder;
import io.wispforest.accessories.api.AccessoriesAPI;
import io.wispforest.accessories.api.AccessoriesCapability;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value="optional_smoke", dist=Dist.CLIENT)
public final class OptionalSmoke {
  private static final List<Map<String,Object>> views = new ArrayList<>();
  private static final CameraType[] CAMERAS = {CameraType.FIRST_PERSON, CameraType.THIRD_PERSON_BACK,
      CameraType.THIRD_PERSON_FRONT, CameraType.FIRST_PERSON};
  private static int readyTicks, stage=-1, ticks, frames, totalWorldFrames;
  private static CompletableFuture<Void> equipment;
  private static boolean finished;
  public OptionalSmoke() {
    NeoForge.EVENT_BUS.addListener(OptionalSmoke::tick);
    NeoForge.EVENT_BUS.addListener(OptionalSmoke::frame);
  }
  private static boolean isolated(Minecraft mc) {
    return mc.gameDirectory.toPath().toAbsolutePath().normalize().toString().replace('\\','/').contains("/.local/optional-emf/")
        && mc.getUser().getType()==User.Type.LEGACY && "AttachmentTest".equals(mc.getUser().getName())
        && mc.hasSingleplayerServer() && !mc.getSingleplayerServer().isPublished();
  }
  private static ItemStack item(String id) {
    var value = BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
    if (value == Items.AIR) throw new IllegalStateException("Missing fixture item "+id);
    return new ItemStack(value);
  }
  private static void equip(net.minecraft.world.entity.LivingEntity p, String id) {
    var stack=item(id); var cap=AccessoriesCapability.get(p);
    String slot=AccessoriesAPI.getStackSlotTypes(p,stack).stream().map(s->s.name())
        .filter(s->!Set.of("head","chest","legs","feet").contains(s))
        .filter(s->cap.getContainers().containsKey(s)&&cap.getContainers().get(s).getSize()>0)
        .sorted().findFirst().orElseThrow(()->new IllegalStateException("No slot for "+id));
    var target=cap.getContainers().get(slot);
    target.getAccessories().setItem(0,stack); target.getCosmeticAccessories().setItem(0,ItemStack.EMPTY);
    target.renderOptions().set(0,true); target.markChanged(false);
  }
  private static void tick(ClientTickEvent.Post ignored) {
    var mc=Minecraft.getInstance(); if(finished)return;
    if(mc.player==null||mc.level==null||mc.screen!=null)return;
    try {
      if(!isolated(mc))throw new IllegalStateException("Probe requires disposable offline world");
      if(++readyTicks<100)return;
      if(equipment==null) {
        mc.getWindow().setTitle("Optional EMF smoke | "+mc.gameDirectory);
        var server=mc.getSingleplayerServer(); var id=mc.player.getUUID();
        equipment=CompletableFuture.runAsync(()->{
          var p=server.getPlayerList().getPlayer(id);
          p.stopSleeping(); p.stopRiding(); p.stopUsingItem();
          p.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.DIAMOND_SWORD));
          p.setItemSlot(EquipmentSlot.HEAD,item("deeperdarker:warden_helmet"));
          equip(p,"artifacts:night_vision_goggles"); equip(p,"artifacts:cross_necklace");
          p.inventoryMenu.broadcastChanges();
        },server);
        return;
      }
      if(!equipment.isDone())return;
      equipment.join();
      if(stage<0)begin(mc,0);
      else if(++ticks>=80&&frames>=60) {
        try(var image=Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
          String name="view-"+stage+"-"+CAMERAS[stage].name().toLowerCase()+".png";
          image.writeToFile(mc.gameDirectory.toPath().resolve(name));
          var row=new LinkedHashMap<String,Object>();
          row.put("camera",CAMERAS[stage].name()); row.put("pitch",mc.player.getXRot());
          row.put("worldFrames",frames); row.put("ticks",ticks); row.put("image",name);
          row.put("position",List.of(mc.player.getX(),mc.player.getY(),mc.player.getZ()));
          row.put("helmet",BuiltInRegistries.ITEM.getKey(mc.player.getItemBySlot(EquipmentSlot.HEAD).getItem()).toString());
          var worn=new TreeMap<String,String>();
          AccessoriesCapability.get(mc.player).getContainers().forEach((name2,c)->{
            for(int i=0;i<c.getSize();i++)if(!c.getAccessories().getItem(i).isEmpty())
              worn.put(name2+":"+i,BuiltInRegistries.ITEM.getKey(c.getAccessories().getItem(i).getItem()).toString());
          });
          if(!worn.containsValue("artifacts:night_vision_goggles")||!worn.containsValue("artifacts:cross_necklace"))
            throw new IllegalStateException("Native accessory sync failed: "+worn);
          row.put("accessories",worn); views.add(row);
        }
        if(stage+1==CAMERAS.length)finish(mc,"PASS",null);else begin(mc,stage+1);
      }
    }catch(Throwable t){finish(mc,"FAIL",t.toString());}
  }
  private static void begin(Minecraft mc,int next) {
    stage=next;ticks=0;frames=0;mc.options.setCameraType(CAMERAS[next]);
    mc.player.setXRot(next==3?65:10); mc.player.xRotO=mc.player.getXRot();
    mc.options.hideGui=false;
  }
  private static void frame(RenderLevelStageEvent event) {
    var mc=Minecraft.getInstance();
    if(!finished&&mc.level!=null&&mc.player!=null&&mc.screen==null&&event.getStage()==RenderLevelStageEvent.Stage.AFTER_LEVEL) {
      totalWorldFrames++;if(stage>=0)frames++;
    }
  }
  private static void finish(Minecraft mc,String status,String error) {
    finished=true;
    try {
      var result=new LinkedHashMap<String,Object>();result.put("status",status);result.put("error",error);
      result.put("totalWorldFrames",totalWorldFrames);result.put("views",views);
      var mods=new TreeMap<String,String>();ModList.get().getMods().forEach(m->mods.put(m.getModId(),m.getVersion().toString()));
      result.put("loadedMods",mods);
      Files.writeString(mc.gameDirectory.toPath().resolve("optional-smoke.json"),new GsonBuilder().setPrettyPrinting().create().toJson(result));
    }catch(Exception t){t.printStackTrace();}
    mc.stop();
  }
}
'''


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def build_probe():
    build = BASE / 'probe'
    build.mkdir(parents=True, exist_ok=True)
    source = build / 'OptionalSmoke.java'
    source.write_text(PROBE_SOURCE, encoding='utf-8')
    classpath_file = build / 'compile-classpath.txt'
    init = build / 'classpath.init.gradle'
    init.write_text('''gradle.projectsEvaluated {
  def project = gradle.rootProject
  project.tasks.register('writeOptionalSmokeClasspath') {
    dependsOn project.tasks.named('createMinecraftArtifacts')
    dependsOn project.tasks.named('extractSableCompileApi')
    doLast { new File(project.rootDir, '.local/optional-emf/probe/compile-classpath.txt').text = project.sourceSets.main.compileClasspath.asPath }
  }
}
''')
    # Export external APIs only, without modifying the release jar.
    subprocess.run([str(ROOT / 'gradlew.bat'), '--init-script', str(init),
                    'writeOptionalSmokeClasspath', '--console=plain'], cwd=ROOT,
                   env=dict(os.environ, JAVA_HOME=str(JAVA.parent)), check=True)
    classpath = classpath_file.read_text().strip()
    classes = build / 'classes'
    classes.mkdir(exist_ok=True)
    args = ['-proc:none', '--release', '21', '-encoding', 'UTF-8', '-classpath', classpath, '-d', str(classes), str(source)]
    argfile = build / 'javac.args'
    argfile.write_text('\n'.join('"' + p.replace('\\', '/').replace('"', '\\"') + '"' for p in args))
    subprocess.run([str(JAVA / 'javac.exe'), '@' + str(argfile)], cwd=ROOT, check=True)
    jar = build / 'optional-smoke.jar'
    with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as z:
        for p in classes.rglob('*.class'):
            z.write(p, p.relative_to(classes).as_posix())
        z.writestr('META-INF/neoforge.mods.toml', '''modLoader="javafml"
loaderVersion="[4,)"
license="MIT"
[[mods]]
modId="optional_smoke"
version="1.0.0"
displayName="Disposable Optional Dependency Smoke Probe"
''')
    return jar


def prepare(args):
    runtime = BASE / args.label / '.local/client'
    if runtime.exists():
        raise FileExistsError(f'Use a new label; preserve prior runtime evidence: {runtime}')
    release = args.jar.resolve()
    if not release.is_file():
        raise FileNotFoundError(release)
    probe = build_probe()
    runtime.mkdir(parents=True)
    mods = runtime / 'mods'
    mods.mkdir()
    excluded = ('emf-attachments-compat-', 'fpm_x_compat-', 'entity_model_features-',
                'entity_texture_features-', 'emf_compat_', 'firstperson-')
    for jar in (test_runtime.INSTANCE / 'mods').glob('*.jar'):
        if not jar.name.startswith(excluded):
            shutil.copy2(jar, mods / jar.name)
    if args.emf:
        for prefix in ('entity_model_features-', 'entity_texture_features-', 'emf_compat_'):
            for jar in (test_runtime.INSTANCE / 'mods').glob(prefix + '*.jar*'):
                shutil.copy2(jar, mods / jar.name.removesuffix('.disabled'))
    if args.firstperson:
        shutil.copy2(args.firstperson, mods / args.firstperson.name.removesuffix('.disabled'))
    shutil.copy2(release, mods / release.name)
    shutil.copy2(probe, mods / probe.name)
    for folder in ('config', 'resourcepacks', 'defaultconfigs', 'kubejs', 'shaderpacks', 'vista_ffmpeg_bin'):
        source = test_runtime.INSTANCE / folder
        if source.exists():
            shutil.copytree(source, runtime / folder)
    source_world = args.world.resolve()
    source_world.relative_to((ROOT / '.local').resolve())
    if source_world.name != 'AttachmentTest' or not (source_world / 'level.dat').is_file():
        raise ValueError('Expected an existing disposable AttachmentTest world')
    shutil.copytree(source_world, runtime / 'saves/AttachmentTest')
    options = (test_runtime.INSTANCE / 'options.txt').read_text(encoding='utf-8')
    overrides = dict(fullscreen='false', overrideWidth='1100', overrideHeight='760', renderDistance='4',
                     simulationDistance='5', maxFps='60', guiScale='1', pauseOnLostFocus='false',
                     onboardAccessibility='false', soundCategory_master='0.0')
    lines = [line for line in options.splitlines() if line.split(':', 1)[0] not in overrides]
    lines.extend(f'{k}:{v}' for k,v in overrides.items())
    (runtime / 'options.txt').write_text('\n'.join(lines)+'\n', encoding='utf-8')
    fps = runtime / 'config/dynamic_fps.json'
    if fps.exists():
        data = json.loads(fps.read_text(encoding='utf-8-sig'));data['enabled'] = False
        fps.write_text(json.dumps(data, indent=2), encoding='utf-8')
    (runtime / 'natives').mkdir()
    record = dict(label=args.label, emfExpected=args.emf, firstPersonExpected=bool(args.firstperson),
                  release=str(release), releaseSha256=sha(release), probeSha256=sha(probe),
                  sourceWorld=str(source_world), sourceWorldLevelSha256=sha(source_world / 'level.dat'),
                  runtime=str(runtime), mods={p.name:sha(p) for p in sorted(mods.glob('*.jar'))})
    (runtime / 'preparation.json').write_text(json.dumps(record, indent=2)+'\n')
    print(json.dumps({'prepared':str(runtime),'releaseSha256':record['releaseSha256']}))
    return runtime


def launch(runtime):
    if (runtime / 'process.json').exists() or (runtime / 'optional-smoke.json').exists():
        raise FileExistsError('Use a fresh prepared label; do not overwrite prior runtime evidence')
    test_runtime.RUNTIME = runtime
    test_runtime.launch(auto_sweep=False)


def collect(args, runtime):
    pid = json.loads((runtime / 'process.json').read_text())['pid']
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel.OpenProcess.restype = ctypes.c_void_p
    kernel.GetExitCodeProcess.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_ulong)]
    kernel.CloseHandle.argtypes = [ctypes.c_void_p]
    handle = kernel.OpenProcess(0x1000, False, pid)
    if handle:
        try:
            code = ctypes.c_ulong()
            if not kernel.GetExitCodeProcess(handle, ctypes.byref(code)):
                raise ctypes.WinError(ctypes.get_last_error())
            assert code.value == 0, f'Client is running or did not exit cleanly: {code.value}'
        finally:
            kernel.CloseHandle(handle)
    elif ctypes.get_last_error() != 87:  # PID no longer exists.
        raise ctypes.WinError(ctypes.get_last_error())
    assert not list((runtime / 'crash-reports').glob('*.txt')), 'Client produced a crash report'
    final_log = (runtime / 'logs/latest.log').read_text(encoding='utf-8', errors='replace')
    assert 'Stopping!' in final_log, 'No native client shutdown marker'
    assert 'Exception caught during firing event' not in final_log, 'Event handler failed'
    assert not any('FATAL' in line for line in final_log.splitlines()), 'Fatal log entry requires review'
    record = json.loads((runtime / 'preparation.json').read_text())
    result = json.loads((runtime / 'optional-smoke.json').read_text())
    assert result['status'] == 'PASS', result
    assert result['totalWorldFrames'] > 100 and len(result['views']) == 4
    assert all(view['helmet'] == 'deeperdarker:warden_helmet' for view in result['views'])
    assert ('entity_model_features' in result['loadedMods']) == record['emfExpected']
    assert ('entity_texture_features' in result['loadedMods']) == record['emfExpected']
    assert ('firstperson' in result['loadedMods']) == record['firstPersonExpected']
    assert result['loadedMods']['emfattachments'] == args.version
    assert sha(runtime / 'mods' / Path(record['release']).name) == record['releaseSha256']
    output = ROOT / 'verification' / ('optional-emf-' + args.version) / args.label
    output.mkdir(parents=True, exist_ok=True)
    for file in ('preparation.json','optional-smoke.json','logs/latest.log','console.log','console-error.log'):
        if (runtime / file).is_file():
            shutil.copy2(runtime / file, output / Path(file).name)
    for view in result['views']:
        shutil.copy2(runtime / view['image'], output / view['image'])
    (output / 'collection.json').write_text(json.dumps(dict(processExited=True, nativeShutdownLogged=True,
        crashReports=[], eventHandlerFailures=False, fatalLogEntries=False,
        originalImages={v['image']:sha(output/v['image']) for v in result['views']}), indent=2)+'\n')
    print(json.dumps({'status':'PASS','evidence':str(output),'frames':result['totalWorldFrames'],
                      'releaseSha256':record['releaseSha256']}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('prepare','launch','collect'))
    parser.add_argument('--label', required=True)
    parser.add_argument('--version', default='1.2.8')
    parser.add_argument('--jar', type=Path, default=ROOT / 'build/libs/emf-attachments-compat-1.21.1-1.2.8.jar')
    parser.add_argument('--emf', action='store_true')
    parser.add_argument('--firstperson', type=Path)
    parser.add_argument('--world', type=Path, default=ROOT / '.local/without-firstperson/.local/client/saves/AttachmentTest')
    args = parser.parse_args()
    if not args.label or any(c not in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_' for c in args.label):
        raise ValueError('Invalid label')
    runtime = BASE / args.label / '.local/client'
    if args.action == 'prepare': prepare(args)
    elif args.action == 'launch': launch(runtime)
    else: collect(args, runtime)
