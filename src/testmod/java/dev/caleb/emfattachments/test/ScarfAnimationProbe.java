package dev.caleb.emfattachments.test;

import artifacts.component.ToggleIdentifier;
import artifacts.network.NetworkHandler;
import artifacts.network.ToggleKeyPressedPacket;
import com.google.gson.GsonBuilder;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** An opt-in real world render observer for local and networked players. */
public final class ScarfAnimationProbe {
    private static ScarfAnimationProbe active;
    private final Minecraft mc=Minecraft.getInstance();
    private final Path dir=mc.gameDirectory.toPath().toAbsolutePath().normalize();
    private final String role, actorName;
    private final Path bridge;
    private String stage="";
    private int index,elapsed,readyTicks;
    private boolean toggled,done;
    private BlockPos start;
    private final CameraType originalView=mc.options.getCameraType();
    private final List<Map<String,Object>> stages=new ArrayList<>();
    private Map<String,Object> current;
    private CompletableFuture<Map<String,Object>> configuration;

    private ScarfAnimationProbe(String input) {
        var parts=input.strip().split("\\s+");
        role=parts[0];actorName=parts.length>1?parts[1]:mc.player.getGameProfile().getName();
        if(!List.of("local","actor","viewer").contains(role))throw new IllegalArgumentException(input);
        bridge=role.equals("local")?null:Path.of(System.getProperty("emfattachments.scarfBridge"));
        if(role.equals("local") && (!mc.hasSingleplayerServer()||!dir.endsWith(Path.of(".local","client"))))throw new IllegalStateException("Expected disposable integrated world");
        if(bridge!=null && !Files.isDirectory(bridge))throw new IllegalStateException("Expected dedicated fixture bridge");
        mc.options.setCameraType(role.equals("viewer") ? CameraType.FIRST_PERSON:CameraType.THIRD_PERSON_BACK);
    }

    static void register(){NeoForge.EVENT_BUS.addListener(ScarfAnimationProbe::clientTick);}
    private static void clientTick(ClientTickEvent.Post event) {
        var mc=Minecraft.getInstance();
        if(mc.player==null||mc.level==null||mc.screen!=null)return;
        try {
            if(active==null) {
                var trigger=mc.gameDirectory.toPath().resolve("emfattachments-scarf-live.txt");
                if(!Files.isRegularFile(trigger))return;
                String request=Files.readString(trigger).strip();Files.delete(trigger);active=new ScarfAnimationProbe(request);
            }
            active.tick();
        } catch(Throwable e) {
            if(active!=null)active.finish(e.toString());
            com.mojang.logging.LogUtils.getLogger().error("Scarf world observer failure",e);
        }
    }

    private void tick() throws Exception {
        String next;
        if(bridge==null) next=index<ScarfAnimationEquipment.STAGES.size()?ScarfAnimationEquipment.STAGES.get(index):"END";
        else {
            var file=bridge.resolve("stage.txt");
            if(!Files.isRegularFile(file))return;
            next=Files.readString(file).strip();
            if(next.isEmpty()||next.equals("IDLE"))return;
        }
        if(next.equals("END")){finish(""); return;}
        if(!next.equals(stage))begin(next);
        if(done)return;
        elapsed++;
        if(configuration!=null){
            if(!configuration.isDone())return;
            current.put("serverAtConfigure",configuration.join());configuration=null;
        }
        AbstractClientPlayer actor=mc.level.players().stream().filter(p->p.getGameProfile().getName().equals(actorName)).findFirst().orElse(null);
        if(actor!=null && !role.equals("viewer") && stage.equals("scarf-active") && !toggled
                && ScarfAnimationEquipment.state(actor).get("disabledByToggle").equals(true)) {
            NetworkHandler.sendToServer(new ToggleKeyPressedPacket(ToggleIdentifier.SCARF_OF_INVISIBILITY));
            toggled=true;current.put("nativeToggleTick",actor.tickCount);
        }
        if(actor==null || !ScarfAnimationEquipment.ready(actor,stage,role.equals("viewer"))){
            if(elapsed>540)throw new IllegalStateException("Timed out awaiting "+stage+" on "+role+" "+(actor==null?"absent":ScarfAnimationEquipment.state(actor)));
            return;
        }
        if(readyTicks==0) {
            current.put("syncedEquipment",ScarfAnimationEquipment.state(actor));current.put("uuid",actor.getUUID());
            current.put("entityClass",actor.getClass().getName());current.put("readyTick",actor.tickCount);current.put("waitedTicks",elapsed);
            ScarfRenderTrace.target=actor;ScarfRenderTrace.rows=new ArrayList<>();current.put("frames",ScarfRenderTrace.rows);
        }
        readyTicks++;
        if(!role.equals("viewer")) {
            mc.options.keyUp.setDown(readyTicks>=12 && readyTicks<44);
            mc.options.keyShift.setDown(readyTicks>=32 && readyTicks<42);
        }
        if(readyTicks==28 && List.of("base","scarf-active","scarf-removed","vanilla-invisible").contains(stage)) {
            String name="emfattachments-scarf-"+role+"-"+stage+".png";
            try(var img=Screenshot.takeScreenshot(mc.getMainRenderTarget())){img.writeToFile(dir.resolve(name));}
            current.put("screenshot",name);
        }
        if(readyTicks>=61){
            current.put("endEquipment",ScarfAnimationEquipment.state(actor));current.put("actorEndTick",actor.tickCount);
            current.put("view",mc.options.getCameraType().name());ScarfRenderTrace.rows=null;ScarfRenderTrace.target=null;
            done=true;
            if(bridge==null)index++;else Files.writeString(bridge.resolve(role+"-"+stage+"-done.txt"),Integer.toString(actor.tickCount));
        }
    }

    private void begin(String name) {
        if(!ScarfAnimationEquipment.STAGES.contains(name))throw new IllegalArgumentException(name);
        stage=name;done=toggled=false;readyTicks=elapsed=0;
        current=new LinkedHashMap<>();current.put("stage",stage);stages.add(current);
        if(bridge==null){
            var server=Objects.requireNonNull(mc.getSingleplayerServer());
            UUID uuid=mc.player.getUUID();
            configuration=server.submit(()->{
                ServerPlayer p=Objects.requireNonNull(server.getPlayerList().getPlayer(uuid));
                if(start==null){
                    int x=p.getBlockX()+16,z=p.getBlockZ()+16;
                    int y=p.serverLevel().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z)+4;
                    start=new BlockPos(x,y,z);
                    for(int dx=-5;dx<=5;dx++)for(int dz=-7;dz<=22;dz++)
                        p.serverLevel().setBlockAndUpdate(start.offset(dx,-1,dz),Blocks.SMOOTH_STONE.defaultBlockState());
                }
                p.getAbilities().flying=false;p.onUpdateAbilities();p.setNoGravity(false);p.setSprinting(false);
                p.teleportTo(p.serverLevel(),start.getX()+.5,start.getY(),start.getZ()+.5,0,5);
                p.setDeltaMovement(Vec3.ZERO);p.fallDistance=0;
                ScarfAnimationEquipment.configure(p,name);
                return ScarfAnimationEquipment.state(p);
            });
        }
    }

    private void finish(String failure) {
        ScarfRenderTrace.target=null;ScarfRenderTrace.rows=null;
        mc.options.keyUp.setDown(false);mc.options.keyShift.setDown(false);mc.options.setCameraType(originalView);
        try {
            var out=Map.of("role",role,"actor",actorName,"world",mc.hasSingleplayerServer()?"integrated":"dedicated",
                    "failure",failure,"stages",stages,"compatLoaded",net.neoforged.fml.ModList.get().isLoaded("emfattachments"));
            Path file=dir.resolve("emfattachments-scarf-live-report.json");
            var temporary=file.resolveSibling(file.getFileName()+".tmp");
            Files.writeString(temporary,new GsonBuilder().serializeNulls().create().toJson(out));
            Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING);
        } catch(Exception e){throw new IllegalStateException(e);}
        finally{active=null;}
    }
}
