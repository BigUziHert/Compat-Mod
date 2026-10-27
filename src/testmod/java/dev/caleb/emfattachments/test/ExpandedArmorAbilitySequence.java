package dev.caleb.emfattachments.test;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import io.wispforest.accessories.api.AccessoriesAPI;
import io.wispforest.accessories.api.AccessoriesCapability;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import net.minecraft.client.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Native armor keys, passive health trigger, full-set movement and real projectile impact. */
public final class ExpandedArmorAbilitySequence {
    private static Session active;
    private static final String[] CASES={"cursium_helmet","cursium_boots","bloom_stone_pauldrons","monstrous_helm","gravitite_jump","valkyrie_flight","shield_of_repulsion"};
    private ExpandedArmorAbilitySequence() {}
    public static void register(){NeoForge.EVENT_BUS.addListener(ExpandedArmorAbilitySequence::tick);NeoForge.EVENT_BUS.addListener(ExpandedArmorAbilitySequence::frame);}
    private static void tick(ClientTickEvent.Post event){
        var mc=Minecraft.getInstance();
        if(active!=null){try{if(mc.player==null||mc.screen!=null)throw new IllegalStateException("World/screen changed");active.tick();}catch(Throwable e){active.finish("FAIL",e.toString());active=null;}return;}
        if(mc.player==null||mc.screen!=null||LiveFirstPersonSequence.isActive()||CombatRenderCounters.active)return;
        Path trigger=mc.gameDirectory.toPath().resolve("emfattachments-expanded-armor.txt");if(!Files.isRegularFile(trigger))return;
        try{if(!mc.hasSingleplayerServer()||!mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of(".local","client")))throw new IllegalStateException("Isolated integrated world only");
            String label=Files.readString(trigger).trim().replaceAll("[^a-zA-Z0-9._-]","_");Files.delete(trigger);active=new Session(mc,label);active.prepare();
        }catch(Throwable e){if(active!=null)active.finish("FAIL",e.toString());active=null;LogUtils.getLogger().error("Expanded armor startup failed",e);}
    }
    private static void frame(RenderFrameEvent.Post event){if(active==null)return;try{active.frame();}catch(Throwable e){active.finish("FAIL",e.toString());active=null;}}
    private static ItemStack stack(String id){ItemStack s=BuiltInRegistries.ITEM.get(ResourceLocation.parse(id)).getDefaultInstance();if(s.isEmpty())throw new IllegalStateException("Missing "+id);return s;}
    private static Object aether(LivingEntity p){try{var holder=(java.util.function.Supplier<?>)Class.forName("com.aetherteam.aether.attachment.AetherDataAttachments").getField("AETHER_PLAYER").get(null);return p.getData((net.neoforged.neoforge.attachment.AttachmentType<?>)holder.get());}catch(Exception e){throw new IllegalStateException(e);}}
    private static Object get(Object object,String method){try{return object.getClass().getMethod(method).invoke(object);}catch(Exception e){throw new IllegalStateException(e);}}
    private static void set(Object object,String method,Class<?> type,Object value){try{object.getClass().getMethod(method,type).invoke(object,value);}catch(Exception e){throw new IllegalStateException(e);}}
    private static final class Session {
        final Minecraft mc;final UUID id;final Path output;final CameraType camera;final boolean gui,jump,shift,flying;final Vec3 position;Vec3 fixturePosition;final float yaw,pitch;
        final List<Map<String,Object>> rows=new ArrayList<>(),shots=new ArrayList<>();final List<String> failures=new ArrayList<>();final Map<String,Object> report=new LinkedHashMap<>();
        final boolean gravititeKey;final Map<KeyMapping,Boolean> originalKeys=new LinkedHashMap<>();
        int index,waitTicks,actionTicks,captures;boolean started,firstShot,secondShot,restoring;
        CompletableFuture<?> pending;CompletableFuture<Map<String,Object>> sample;
        Map<String,Object> metrics=new LinkedHashMap<>();
        Snapshot original;final List<UUID> spawned=new ArrayList<>();UUID targetId,arrowId;int spawnedClusters;double maxHorizontal,maxUp,maxHeight;int maxFlightTimer;float maxFlightModifier;boolean glowing,monstrous,deflected;String accessorySlot="";
        Session(Minecraft mc,String label)throws Exception{this.mc=mc;id=mc.player.getUUID();output=mc.gameDirectory.toPath().resolve("emfattachments-tests").resolve(label);Files.createDirectories(output);
            if(mc.player.isPassenger()||mc.player.isUsingItem())throw new IllegalStateException("Start armor fixture unmounted and without using an item");camera=mc.options.getCameraType();gui=mc.options.hideGui;jump=mc.options.keyJump.isDown();shift=mc.options.keyShift.isDown();flying=mc.player.getAbilities().flying;position=mc.player.position();yaw=mc.player.getYRot();pitch=mc.player.getXRot();gravititeKey=com.aetherteam.aether.client.AetherKeys.GRAVITITE_JUMP_ABILITY.isDown();for(var key:List.of(mc.options.keyUse,mc.options.keyAttack,mc.options.keyUp,mc.options.keyDown,mc.options.keyLeft,mc.options.keyRight,mc.options.keyJump,mc.options.keyShift,mc.options.keySprint))originalKeys.put(key,key.isDown());
            report.put("scope","Real local-player native armor key paths, passive low-health inventory tick, authoritative full-set jump/flight, and actual server projectile collision. Disposable integrated world; server equipment/effects/input restored.");report.put("cases",rows);report.put("screenshots",shots);report.put("failures",failures);report.put("resourcePacks",mc.getResourcePackRepository().getSelectedIds());write("RUNNING","");}
        <T> CompletableFuture<T> server(Function<ServerPlayer,T> task){var result=new CompletableFuture<T>();mc.getSingleplayerServer().execute(()->{try{var p=mc.getSingleplayerServer().getPlayerList().getPlayer(id);if(p==null)throw new IllegalStateException("Server player absent");result.complete(task.apply(p));}catch(Throwable e){result.completeExceptionally(e);}});return result;}
        void prepare(){sample=null;started=false;waitTicks=actionTicks=0;firstShot=secondShot=false;metrics=new LinkedHashMap<>();maxHorizontal=maxUp=maxHeight=0;maxFlightTimer=0;maxFlightModifier=0;glowing=monstrous=deflected=false;spawnedClusters=0;targetId=arrowId=null;
            releaseInputs();originalKeys.keySet().forEach(key->key.setDown(false));mc.options.hideGui=false;mc.options.setCameraType(CameraType.FIRST_PERSON);mc.player.getAbilities().flying=false;mc.player.setYRot(yaw);mc.player.setXRot(55);
            String name=CASES[index];pending=server(p->{if(original==null)original=new Snapshot(p);ExpandedRagdollRecovery.release(p);cleanup(p);if(fixturePosition==null){fixturePosition=findOpenGround(p,position);report.put("fixturePosition",fixturePosition.toString());}p.stopRiding();p.setGameMode(name.equals("shield_of_repulsion")?net.minecraft.world.level.GameType.SURVIVAL:original.gameMode);clearEquipment(p);p.stopUsingItem();p.removeAllEffects();p.setHealth(p.getMaxHealth());p.getAbilities().flying=false;p.onUpdateAbilities();p.teleportTo(p.serverLevel(),fixturePosition.x,fixturePosition.y,fixturePosition.z,yaw,55);p.setDeltaMovement(Vec3.ZERO);p.fallDistance=0;p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(p));
                switch(name){
                    case "cursium_helmet"->{p.setItemSlot(EquipmentSlot.HEAD,stack("cataclysm:cursium_helmet"));var cow=EntityType.COW.create(p.serverLevel());cow.setNoAi(true);cow.setInvulnerable(true);cow.setPos(fixturePosition.add(2,0,2));p.serverLevel().addFreshEntity(cow);targetId=cow.getUUID();spawned.add(targetId);}
                    case "cursium_boots"->p.setItemSlot(EquipmentSlot.FEET,stack("cataclysm:cursium_boots"));
                    case "bloom_stone_pauldrons"->p.setItemSlot(EquipmentSlot.CHEST,stack("cataclysm:bloom_stone_pauldrons"));
                    case "monstrous_helm"->p.setItemSlot(EquipmentSlot.HEAD,stack("cataclysm:monstrous_helm"));
                    case "gravitite_jump"->armor(p,"gravitite");
                    case "valkyrie_flight"->{armor(p,"valkyrie");p.teleportTo(p.serverLevel(),fixturePosition.x,fixturePosition.y+2,fixturePosition.z,yaw,55);}
                    case "shield_of_repulsion"->{var s=stack("aether:shield_of_repulsion");var cap=AccessoriesCapability.get(p);var allowed=AccessoriesAPI.getStackSlotTypes(p,s).stream().filter(t->!io.wispforest.accessories.menu.ArmorSlotTypes.isArmorType(t.name())).sorted(Comparator.comparing(t->t.name())).findFirst().orElseThrow();var c=cap.getContainer(allowed);c.getAccessories().setItem(0,s);accessorySlot=allowed.name();}
                }
                for(var slot:EquipmentSlot.values()){var item=p.getItemBySlot(slot).getItem();p.getCooldowns().removeCooldown(item);}p.inventoryMenu.broadcastChanges();return null;});}
        void tick(){
            if(restoring){if(pending.isDone()){pending.join();active=null;}return;}
            if(!started){if(pending.isCompletedExceptionally())pending.join();if(!pending.isDone()||++waitTicks<12)return;if(sample!=null&&sample.isDone()){report.put("preparationServer",sample.join());sample=null;}if(sample==null)sample=server(this::preparationState);report.put("preparationClient",preparationState(mc.player));if(!equipped()||mc.player.isPassenger()||mc.player.position().distanceTo(fixturePosition)>3){if(waitTicks>160)throw new IllegalStateException("Armor fixture not ready for "+CASES[index]+": "+report.get("preparationClient")+" server="+report.get("preparationServer"));return;}sample=null;activate();started=true;return;}
            actionTicks++;mc.player.setYRot(yaw);mc.player.setXRot(firstShot?8:55);
            if(actionTicks==24)releaseInputs();
            if(sample!=null&&sample.isDone()){var m=sample.join();metrics.putAll(m);maxHorizontal=Math.max(maxHorizontal,((Number)m.get("horizontalSpeed")).doubleValue());maxUp=Math.max(maxUp,((Number)m.get("verticalSpeed")).doubleValue());maxHeight=Math.max(maxHeight,((Number)m.get("heightAboveStart")).doubleValue());glowing|=(boolean)m.get("targetGlowing");monstrous|=(boolean)m.get("monstrousEffect");deflected|=(boolean)m.get("projectileReversed");spawnedClusters=Math.max(spawnedClusters,((Number)m.get("clusterCount")).intValue());maxFlightTimer=Math.max(maxFlightTimer,((Number)m.get("flightTimer")).intValue());maxFlightModifier=Math.max(maxFlightModifier,((Number)m.get("flightModifier")).floatValue());sample=null;}
            if(sample==null)sample=server(this::observe);
        }
        Map<String,Object> preparationState(LivingEntity player){
            var m=new LinkedHashMap<String,Object>();m.put("position",player.position().toString());if(player==mc.player){var input=new LinkedHashMap<String,Boolean>();originalKeys.keySet().forEach(key->input.put(key.getName(),key.isDown()));m.put("inputKeys",input);}var floor=player.blockPosition().below();m.put("floor",player.level().getBlockState(floor).toString());m.put("onGround",player.onGround());if(player instanceof ServerPlayer serverPlayer)m.put("nativeRagdollActive",ExpandedRagdollRecovery.active(serverPlayer));m.put("vehicle",player.getVehicle()==null?"none":BuiltInRegistries.ENTITY_TYPE.getKey(player.getVehicle().getType()).toString());
            var armorIds=new LinkedHashMap<String,String>();for(var slot:EquipmentSlot.values())armorIds.put(slot.getName(),BuiltInRegistries.ITEM.getKey(player.getItemBySlot(slot).getItem()).toString());m.put("equipment",armorIds);
            var accessories=new LinkedHashMap<String,String>();AccessoriesCapability.get(player).getContainers().forEach((name,c)->{for(int i=0;i<c.getSize();i++)if(!c.getAccessories().getItem(i).isEmpty())accessories.put(name+":"+i,BuiltInRegistries.ITEM.getKey(c.getAccessories().getItem(i).getItem()).toString());});m.put("accessories",accessories);
            m.put("fullGravititeSet",com.aetherteam.aether.item.EquipmentUtil.hasFullGravititeSet(player));m.put("fullValkyrieSet",com.aetherteam.aether.item.EquipmentUtil.hasFullValkyrieSet(player));return m;
        }
        boolean equipped(){var p=mc.player;return switch(CASES[index]){case "cursium_helmet"->p.getItemBySlot(EquipmentSlot.HEAD).is(stack("cataclysm:cursium_helmet").getItem());case "cursium_boots"->p.getItemBySlot(EquipmentSlot.FEET).is(stack("cataclysm:cursium_boots").getItem())&&p.onGround();case "bloom_stone_pauldrons"->p.getItemBySlot(EquipmentSlot.CHEST).is(stack("cataclysm:bloom_stone_pauldrons").getItem());case "monstrous_helm"->p.getItemBySlot(EquipmentSlot.HEAD).is(stack("cataclysm:monstrous_helm").getItem());case "gravitite_jump"->com.aetherteam.aether.item.EquipmentUtil.hasFullGravititeSet(p)&&p.onGround();case "valkyrie_flight"->com.aetherteam.aether.item.EquipmentUtil.hasFullValkyrieSet(p);default->{var c=AccessoriesCapability.get(p).getContainers().get(accessorySlot);yield c!=null&&c.getAccessories().getItem(0).is(stack("aether:shield_of_repulsion").getItem());}};}
        void activate(){switch(CASES[index]){
            case "cursium_helmet"->KeyMapping.click(com.github.L_Ender.cataclysm.init.ModKeybind.HELMET_KEY_ABILITY.getKey());
            case "cursium_boots"->KeyMapping.click(com.github.L_Ender.cataclysm.init.ModKeybind.BOOTS_KEY_ABILITY.getKey());
            case "bloom_stone_pauldrons"->KeyMapping.click(com.github.L_Ender.cataclysm.init.ModKeybind.CHESTPLATE_KEY_ABILITY.getKey());
            case "monstrous_helm"->pending=server(p->{p.setHealth(Math.min(5,p.getMaxHealth()/3));return null;});
            case "gravitite_jump"->{var key=com.aetherteam.aether.client.AetherKeys.GRAVITITE_JUMP_ABILITY;key.setDown(true);com.aetherteam.aether.client.event.hooks.CapabilityClientHooks.AetherPlayerHooks.keyInput(key.getKey().getValue());mc.options.keyJump.setDown(true);}
            case "valkyrie_flight"->mc.options.keyJump.setDown(true);
            case "shield_of_repulsion"->pending=server(p->{Arrow arrow=new Arrow(EntityType.ARROW,p.serverLevel());arrow.setNoGravity(true);arrow.setPos(p.position().add(0,0.9,3));arrow.setDeltaMovement(0,0,-0.45);p.serverLevel().addFreshEntity(arrow);arrowId=arrow.getUUID();spawned.add(arrowId);return null;});
        }}
        Map<String,Object> observe(ServerPlayer p){var m=new LinkedHashMap<String,Object>();m.put("horizontalSpeed",p.getDeltaMovement().horizontalDistance());m.put("verticalSpeed",p.getDeltaMovement().y);m.put("heightAboveStart",p.getY()-fixturePosition.y);var target=targetId==null?null:p.serverLevel().getEntity(targetId);m.put("targetGlowing",target instanceof LivingEntity living&&living.hasEffect(net.minecraft.world.effect.MobEffects.GLOWING));m.put("monstrousEffect",p.getActiveEffects().stream().anyMatch(e->BuiltInRegistries.MOB_EFFECT.getKey(e.getEffect().value()).toString().equals("cataclysm:monstrous")));int clusters=0;for(var e:p.serverLevel().getEntities(p,p.getBoundingBox().inflate(40))){if(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString().equals("cataclysm:amethyst_cluster_projectile")&&e instanceof Projectile pr&&pr.getOwner()==p){clusters++;if(!spawned.contains(e.getUUID()))spawned.add(e.getUUID());}}m.put("clusterCount",clusters);var arrow=arrowId==null?null:p.serverLevel().getEntity(arrowId);m.put("projectileReversed",arrow!=null&&arrow.getDeltaMovement().z>0.025);m.put("flightTimer",get(aether(p),"getFlightTimer"));m.put("flightModifier",get(aether(p),"getFlightModifier"));m.put("gravititeInput",get(aether(p),"isGravititeJumpActive"));m.put("aetherJumping",get(aether(p),"isJumping"));m.put("fullGravititeSet",com.aetherteam.aether.item.EquipmentUtil.hasFullGravititeSet(p));m.put("fullValkyrieSet",com.aetherteam.aether.item.EquipmentUtil.hasFullValkyrieSet(p));m.put("gameMode",p.gameMode.getGameModeForPlayer().getName());m.put("arrowAlive",arrow!=null&&arrow.isAlive());if(arrow!=null){m.put("arrowPosition",arrow.position().toString());m.put("arrowMotion",arrow.getDeltaMovement().toString());m.put("arrowDeflectable",arrow.getType().is(com.aetherteam.aether.AetherTags.Entities.DEFLECTABLE_PROJECTILES));}return m;}
        void frame()throws Exception{if(!started||restoring)return;if(actionTicks>=18&&!firstShot){capture("firstperson");firstShot=true;mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);mc.player.setXRot(8);}if(actionTicks>=36&&!secondShot){capture("thirdperson");secondShot=true;}if(actionTicks<42||!secondShot)return;
            Map<String,Object> row=new LinkedHashMap<>();row.put("ability",CASES[index]);row.put("maximumHorizontalSpeed",maxHorizontal);row.put("maximumUpwardSpeed",maxUp);row.put("maximumHeight",maxHeight);row.put("targetGlowing",glowing);row.put("monstrousEffect",monstrous);row.put("projectiles",spawnedClusters);row.put("projectileReversed",deflected);row.put("flightTimer",maxFlightTimer);row.put("flightModifier",maxFlightModifier);row.put("lastServerState",metrics);
            boolean pass=switch(CASES[index]){case "cursium_helmet"->glowing;case "cursium_boots"->maxHorizontal>0.7&&maxHeight>0.1;case "bloom_stone_pauldrons"->spawnedClusters>=8;case "monstrous_helm"->monstrous;case "gravitite_jump"->maxUp>0.8&&maxHeight>1.5;case "valkyrie_flight"->maxFlightTimer>3&&maxFlightModifier>1;case "shield_of_repulsion"->deflected;default->false;};row.put("status",pass?"PASS":"FAIL");rows.add(row);if(!pass)failures.add(CASES[index]+" native activation assertion failed");write("RUNNING","");
            if(++index<CASES.length)prepare();else finish(failures.isEmpty()?"PASS":"FAIL","Native activation checks complete");}
        void capture(String view)throws Exception{String file=String.format(Locale.ROOT,"%02d-%s-%s.png",++captures,CASES[index],view);try(NativeImage image=Screenshot.takeScreenshot(mc.getMainRenderTarget())){image.writeToFile(output.resolve(file));}var m=new LinkedHashMap<String,Object>();m.put("ability",CASES[index]);m.put("view",view);m.put("tick",actionTicks);m.put("file",file);m.put("position",mc.player.position().toString());shots.add(m);write("RUNNING","");}
        void releaseInputs(){mc.options.keyJump.setDown(false);mc.options.keyShift.setDown(false);var key=com.aetherteam.aether.client.AetherKeys.GRAVITITE_JUMP_ABILITY;key.setDown(false);com.aetherteam.aether.client.event.hooks.CapabilityClientHooks.AetherPlayerHooks.keyInput(key.getKey().getValue());}
        void finish(String status,String detail){releaseInputs();originalKeys.forEach(KeyMapping::setDown);mc.options.keyJump.setDown(jump);mc.options.keyShift.setDown(shift);com.aetherteam.aether.client.AetherKeys.GRAVITITE_JUMP_ABILITY.setDown(gravititeKey);mc.options.setCameraType(camera);mc.options.hideGui=gui;if(mc.player!=null){mc.player.setYRot(yaw);mc.player.setXRot(pitch);mc.player.getAbilities().flying=flying;}report.put("result",status);report.put("detail",detail);restoring=true;started=false;pending=server(p->{cleanup(p);if(original!=null)original.restore(p);return null;});pending.whenComplete((v,e)->{mc.execute(()->{try{report.put("stateRestored",e==null);write(e==null?status:"FAIL",e==null?detail:e.toString());}catch(Exception failure){LogUtils.getLogger().error("Armor report write failed",failure);}});});}
        void cleanup(ServerPlayer p){for(UUID uuid:spawned){var e=p.serverLevel().getEntity(uuid);if(e!=null)e.discard();}spawned.clear();}
        void write(String status,String detail){try{report.put("status",status);report.put("detail",detail);Files.writeString(output.resolve("expanded-armor-report.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));}catch(Exception e){throw new IllegalStateException(e);}}
    }
    private static void armor(ServerPlayer p,String material){p.setItemSlot(EquipmentSlot.HEAD,stack("aether:"+material+"_helmet"));p.setItemSlot(EquipmentSlot.CHEST,stack("aether:"+material+"_chestplate"));p.setItemSlot(EquipmentSlot.LEGS,stack("aether:"+material+"_leggings"));p.setItemSlot(EquipmentSlot.FEET,stack("aether:"+material+"_boots"));equipAccessory(p,stack("aether:"+material+"_gloves"));if(!(material.equals("gravitite")?com.aetherteam.aether.item.EquipmentUtil.hasFullGravititeSet(p):com.aetherteam.aether.item.EquipmentUtil.hasFullValkyrieSet(p)))throw new IllegalStateException("Native full armor set predicate failed: "+material);}
    private static void equipAccessory(ServerPlayer p,ItemStack stack){
        var slot=AccessoriesAPI.getStackSlotTypes(p,stack).stream().filter(t->!io.wispforest.accessories.menu.ArmorSlotTypes.isArmorType(t.name())).sorted(Comparator.comparing(t->t.name())).findFirst().orElseThrow();
        AccessoriesCapability.get(p).getContainer(slot).getAccessories().setItem(0,stack);
    }
    private static Vec3 findOpenGround(ServerPlayer p,Vec3 original){
        var level=p.serverLevel();
        for(int distance:new int[]{64,96,128})for(int dx:new int[]{distance,-distance})for(int dz:new int[]{distance,-distance}){
            int x=(int)Math.floor(original.x)+dx,z=(int)Math.floor(original.z)+dz,y=level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z);
            boolean clear=true;
            for(int ox=-24;ox<=24&&clear;ox++)for(int oz=-24;oz<=24&&clear;oz++){
                if(level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x+ox,z+oz)!=y){clear=false;break;}
                for(int oy=0;oy<8;oy++)if(!level.getBlockState(new net.minecraft.core.BlockPos(x+ox,y+oy,z+oz)).isAir()){clear=false;break;}
            }
            if(clear)return new Vec3(x+0.5,y,z+0.5);
        }
        throw new IllegalStateException("No existing clear flat armor-test surface near saved position");
    }
    private static void clearEquipment(ServerPlayer p){for(EquipmentSlot slot:EquipmentSlot.values())p.setItemSlot(slot,ItemStack.EMPTY);var cap=AccessoriesCapability.get(p);if(cap!=null)cap.getContainers().values().forEach(c->{c.getAccessories().clearContent();c.getCosmeticAccessories().clearContent();});}
    private static final class Snapshot {
        final Map<EquipmentSlot,ItemStack> equipment=new EnumMap<>(EquipmentSlot.class);final Map<String,List<ItemStack>> items=new LinkedHashMap<>(),cosmetics=new LinkedHashMap<>();final List<MobEffectInstance> effects;final float health;final Vec3 pos,motion;final boolean flying;final float fallDistance;final Map<String,Object> aetherState=new LinkedHashMap<>();final Map<Object,Object> cooldowns;final net.minecraft.world.level.GameType gameMode;final int food;final float saturation;
        Snapshot(ServerPlayer p){gameMode=p.gameMode.getGameModeForPlayer();food=p.getFoodData().getFoodLevel();saturation=p.getFoodData().getSaturationLevel();for(EquipmentSlot slot:EquipmentSlot.values())equipment.put(slot,p.getItemBySlot(slot).copy());AccessoriesCapability.get(p).getContainers().forEach((n,c)->{List<ItemStack>a=new ArrayList<>(),b=new ArrayList<>();for(int i=0;i<c.getSize();i++){a.add(c.getAccessories().getItem(i).copy());b.add(c.getCosmeticAccessories().getItem(i).copy());}items.put(n,a);cosmetics.put(n,b);});effects=p.getActiveEffects().stream().map(MobEffectInstance::new).toList();health=p.getHealth();pos=p.position();motion=p.getDeltaMovement();flying=p.getAbilities().flying;fallDistance=p.fallDistance;for(String method:List.of("isJumping","isMoving","isGravititeJumpActive","getFlightTimer","getFlightModifier"))aetherState.put(method,get(aether(p),method));cooldowns=new HashMap<>(cooldowns(p));}
        @SuppressWarnings("unchecked") static Map<Object,Object> cooldowns(ServerPlayer p){try{var f=net.minecraft.world.item.ItemCooldowns.class.getDeclaredField("cooldowns");f.setAccessible(true);return(Map<Object,Object>)f.get(p.getCooldowns());}catch(Exception e){throw new IllegalStateException(e);}}
        void restore(ServerPlayer p){ExpandedRagdollRecovery.release(p);p.stopRiding();p.setGameMode(gameMode);p.getFoodData().setFoodLevel(food);p.getFoodData().setSaturation(saturation);clearEquipment(p);equipment.forEach((slot,s)->p.setItemSlot(slot,s.copy()));items.forEach((n,a)->{var c=AccessoriesCapability.get(p).getContainers().get(n);for(int i=0;i<a.size();i++){c.getAccessories().setItem(i,a.get(i).copy());c.getCosmeticAccessories().setItem(i,cosmetics.get(n).get(i).copy());}});p.removeAllEffects();effects.forEach(p::addEffect);p.setHealth(health);p.getAbilities().flying=flying;p.onUpdateAbilities();p.teleportTo(p.serverLevel(),pos.x,pos.y,pos.z,p.getYRot(),p.getXRot());p.setDeltaMovement(motion);p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(p));for(Object item:new ArrayList<>(cooldowns(p).keySet()))p.getCooldowns().removeCooldown((net.minecraft.world.item.Item)item);cooldowns(p).putAll(cooldowns);
            try{var tick=net.minecraft.world.item.ItemCooldowns.class.getDeclaredField("tickCount");tick.setAccessible(true);int now=tick.getInt(p.getCooldowns());for(var e:cooldowns.entrySet()){var end=e.getValue().getClass().getDeclaredField("endTime");end.setAccessible(true);p.connection.send(new net.minecraft.network.protocol.game.ClientboundCooldownPacket((net.minecraft.world.item.Item)e.getKey(),Math.max(0,end.getInt(e.getValue())-now)));}}catch(Exception e){throw new IllegalStateException(e);}
            p.fallDistance=fallDistance;aetherState.forEach((method,value)->set(aether(p),method.startsWith("is")?"set"+method.substring(2):"set"+method.substring(3),value instanceof Boolean?boolean.class:value instanceof Integer?int.class:float.class,value));p.inventoryMenu.broadcastChanges();
            equipment.forEach((slot,s)->{if(!ItemStack.matches(s,p.getItemBySlot(slot)))throw new IllegalStateException("Armor fixture did not restore "+slot);});}
    }
}
