package dev.caleb.emfattachments.test;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import io.wispforest.accessories.api.AccessoriesAPI;
import io.wispforest.accessories.api.AccessoriesCapability;
import io.wispforest.accessories.menu.ArmorSlotTypes;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import net.minecraft.client.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Real world ticks and native eating for every discovered wearable, in an isolated world. */
public final class ExpandedLiveWearableSequence {
    private static Session active;
    private ExpandedLiveWearableSequence() {}
    public static boolean isActive(){return active!=null;}
    public static void register(){
        NeoForge.EVENT_BUS.addListener(ExpandedLiveWearableSequence::tick);
        NeoForge.EVENT_BUS.addListener(ExpandedLiveWearableSequence::beforeFrame);
        NeoForge.EVENT_BUS.addListener(ExpandedLiveWearableSequence::afterFrame);
    }
    private static void tick(ClientTickEvent.Post event){
        var mc=Minecraft.getInstance();
        if(active!=null){try{active.tick();}catch(Throwable e){active.finish("FAIL",e.toString());}return;}
        if(mc.player==null||mc.screen!=null||LiveFirstPersonSequence.isActive()||CombatRenderCounters.active)return;
        var trigger=mc.gameDirectory.toPath().resolve("emfattachments-expanded-live-wearables.txt");
        if(!Files.isRegularFile(trigger))return;
        try{
            if(!mc.hasSingleplayerServer()||!mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of(".local","client")))throw new IllegalStateException("Isolated integrated world only");
            String[] request=Files.readString(trigger).trim().split("\\R");Files.delete(trigger);String filter=".*";int start=0;
            for(int i=1;i<request.length;i++){String option=request[i].trim();if(option.startsWith("start="))start=Integer.parseInt(option.substring(6));else if(!option.isEmpty())filter=option;}
            active=new Session(mc,request[0].replaceAll("[^a-zA-Z0-9._-]","_"),filter,start);active.prepare();
        }catch(Throwable e){if(active!=null)active.finish("FAIL",e.toString());LogUtils.getLogger().error("Live wearable startup failed",e);}
    }
    private static void beforeFrame(RenderFrameEvent.Pre event){if(active!=null&&!active.restoring){LiveWearableRenderState.reset();var c=active.cases.get(active.index);var r=new AttachmentTestMod.TestResult();r.item=c.id();r.slot=c.slot;r.slotIndex=c.index;r.variant=c.variant();r.pose=active.firstCaptured?"live-eat-thirdperson":"live-eat-firstperson";AttachmentTestMod.current=r;}}
    private static void afterFrame(RenderFrameEvent.Post event){if(active!=null)try{active.frame();}catch(Throwable e){active.finish("FAIL",e.toString());}finally{AttachmentTestMod.current=null;}}
    private record Case(Item item,String slot,int index,EquipmentSlot armor,int dye){
        String id(){return BuiltInRegistries.ITEM.getKey(item).toString();}
        ItemStack stack(){var s=item.getDefaultInstance();if(dye>=0)s.set(DataComponents.DYED_COLOR,new DyedItemColor(dye,true));return s;}
        String variant(){return dye<0?"default":"dyed-blue";}
        boolean rear(){return slot.equals("back")||id().matches(".*(cape|cloak|backpack|backtank|jetpack|elytra|wings|quiver|portable_battery).*");}
    }
    private static List<Case> discover(net.minecraft.client.player.LocalPlayer p,String filter){
        var result=new ArrayList<Case>();var cap=AccessoriesCapability.get(p);
        for(var item:BuiltInRegistries.ITEM){if(!BuiltInRegistries.ITEM.getKey(item).toString().matches(filter))continue;var s=item.getDefaultInstance();if(s.isEmpty())continue;
            var slot=p.getEquipmentSlotForItem(s);if(slot==EquipmentSlot.HEAD||slot==EquipmentSlot.CHEST||slot==EquipmentSlot.LEGS||slot==EquipmentSlot.FEET)result.add(new Case(item,"equipment:"+slot.getName(),0,slot,-1));
            for(var type:AccessoriesAPI.getStackSlotTypes(p,s)){if(ArmorSlotTypes.isArmorType(type.name()))continue;var c=cap.getContainer(type);if(c==null)throw new IllegalStateException("Missing native container "+type.name());for(int i=0;i<c.getSize();i++){result.add(new Case(item,type.name(),i,null,-1));if(BuiltInRegistries.ITEM.getKey(item).toString().equals("the_bumblezone:flower_headwear")&&type.name().equals("hat"))result.add(new Case(item,type.name(),i,null,0x33AAFF));}}
        }
        result.sort(Comparator.comparing(Case::id).thenComparing(Case::slot).thenComparingInt(Case::index).thenComparingInt(Case::dye));if(result.isEmpty())throw new IllegalStateException("No matched wearable cases");return result;
    }
    private static final class Session {
        final Minecraft mc;final UUID uuid;final Path output;final List<Case> cases;final Map<String,Object> report=new LinkedHashMap<>();final List<Map<String,Object>> rows=new ArrayList<>();final List<String> failures=new ArrayList<>();
        final CameraType camera;final boolean gui,nea,scarfClient,fpmEnabled;final float yaw,pitch;final Vec3 initialPosition;final int startIndex;final Map<KeyMapping,Boolean> keys=new LinkedHashMap<>();final Map<String,List<Boolean>> clientRenderFlags=new LinkedHashMap<>();
        CompletableFuture<?> pending;Snapshot original;volatile Vec3 fixture;int index,wait,stableTicks,actionTicks,initialDuration,baseDuration,initialNativeUseTicks,firstCaptureAt,secondCaptureAt,captures;boolean started,firstCaptured,restoring;ItemStack useStack;Map<String,Object> baselinePose=Map.of(),row;
        Session(Minecraft mc,String label,String filter,int start)throws Exception{
            this.mc=mc;uuid=mc.player.getUUID();if(mc.player.isPassenger()||mc.player.isUsingItem())throw new IllegalStateException("Start live wearable pass unmounted and without using an item");
            cases=discover(mc.player,filter);if(start<0||start>=cases.size())throw new IllegalArgumentException("Invalid start index "+start);startIndex=index=start;output=mc.gameDirectory.toPath().resolve("emfattachments-tests").resolve(label);Files.createDirectories(output);camera=mc.options.getCameraType();gui=mc.options.hideGui;nea=dev.tr7zw.notenoughanimations.versionless.NEABaseMod.config.enableEatDrinkAnimation;scarfClient=scarf(mc.player);fpmEnabled=hasFpm()&&Fpm.enabled();yaw=mc.player.getYRot();pitch=mc.player.getXRot();initialPosition=mc.player.position();
            for(var key:List.of(mc.options.keyUse,mc.options.keyAttack,mc.options.keyUp,mc.options.keyDown,mc.options.keyLeft,mc.options.keyRight,mc.options.keyJump,mc.options.keyShift,mc.options.keySprint))keys.put(key,key.isDown());
            AccessoriesCapability.get(mc.player).getContainers().forEach((name,c)->clientRenderFlags.put(name,new ArrayList<>(c.renderOptions())));
            report.put("scope","Actual wearable item/slot/index, default stack plus blue flower. Authoritative equipment, at least eight synchronized client ticks, real native bread use. Capture at 17/24 of32 use ticks, scaled to the actual event-modified duration for fast-eating gear. Native timer delta and actual client ticks are recorded separately. No synthetic player rendering. Optional start index resumes a new report without overwriting prior evidence.");
            report.put("label",label);report.put("firstPersonInstalled",hasFpm());report.put("playerSkinModel",mc.player.getSkin().model().id());report.put("caseCount",cases.size());report.put("uniqueItems",cases.stream().map(Case::id).distinct().count());report.put("cases",rows);report.put("failures",failures);report.put("resourcePacks",mc.getResourcePackRepository().getSelectedIds());
            report.put("catalog",cases.stream().map(c->Map.of("item",c.id(),"slot",c.slot,"index",c.index,"variant",c.variant())).toList());
            report.put("startIndex",startIndex);report.put("scheduledCases",cases.size()-startIndex);
            report.put("registeredArtifactsItemIds",BuiltInRegistries.ITEM.keySet().stream().filter(id->id.getNamespace().equals("artifacts")).map(Object::toString).sorted().toList());
            report.put("registeredClimbingNameCandidates",BuiltInRegistries.ITEM.keySet().stream().map(Object::toString).filter(id->id.matches(".*(climb|claw|glove|grip).*" )).sorted().toList());
            dev.tr7zw.notenoughanimations.versionless.NEABaseMod.config.enableEatDrinkAnimation=true;if(hasFpm())Fpm.enabled(true);LiveWearableRenderState.active=true;write("RUNNING","");
        }
        <T> CompletableFuture<T> server(Function<ServerPlayer,T> fn){var future=new CompletableFuture<T>();mc.getSingleplayerServer().execute(()->{try{var p=mc.getSingleplayerServer().getPlayerList().getPlayer(uuid);if(p==null)throw new IllegalStateException("Server player absent");future.complete(fn.apply(p));}catch(Throwable e){future.completeExceptionally(e);}});return future;}
        void releaseUse(){mc.options.keyUse.setDown(false);if(mc.player!=null&&mc.player.isUsingItem())mc.gameMode.releaseUsingItem(mc.player);}
        void prepare(){
            releaseUse();keys.keySet().forEach(k->k.setDown(false));started=firstCaptured=false;wait=stableTicks=actionTicks=0;mc.options.hideGui=false;mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);mc.player.setYRot(yaw);mc.player.setXRot(8);scarf(mc.player,true);
            AccessoriesCapability.get(mc.player).getContainers().values().forEach(c->{for(int i=0;i<c.renderOptions().size();i++)c.renderOptions().set(i,true);});
            Case c=cases.get(index);row=new LinkedHashMap<>();row.put("catalogIndex",index);row.put("item",c.id());row.put("slot",c.slot);row.put("slotIndex",c.index);row.put("variant",c.variant());row.put("captures",new ArrayList<Map<String,Object>>());rows.add(row);
            pending=server(p->{if(original==null)original=new Snapshot(p);if(fixture==null)fixture=findGround(p,initialPosition);ExpandedRagdollRecovery.release(p);clear(p);p.stopUsingItem();p.stopRiding();p.removeAllEffects();p.setHealth(p.getMaxHealth());p.getFoodData().setFoodLevel(10);p.getFoodData().setSaturation(0);p.getAbilities().flying=false;p.onUpdateAbilities();p.teleportTo(p.serverLevel(),fixture.x,fixture.y,fixture.z,yaw,8);p.setDeltaMovement(Vec3.ZERO);p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(p));scarf(p,true);
                p.getInventory().selected=original.selected;p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.BREAD,64));if(c.armor!=null)p.setItemSlot(c.armor,c.stack());else{var container=AccessoriesCapability.get(p).getContainers().get(c.slot);if(container==null||container.getSize()<=c.index)throw new IllegalStateException("Server slot unavailable "+c.slot);container.getAccessories().setItem(c.index,c.stack());}p.inventoryMenu.broadcastChanges();return null;});
            write("RUNNING","");
        }
        ItemStack actual(){var c=cases.get(index);return c.armor!=null?mc.player.getItemBySlot(c.armor):AccessoriesCapability.get(mc.player).getContainers().get(c.slot).getAccessories().getItem(c.index);}
        boolean correctGear(){var c=cases.get(index);var actual=actual();return actual.is(c.item)&&(c.dye<0||actual.getOrDefault(DataComponents.DYED_COLOR,new DyedItemColor(-1,false)).rgb()==c.dye);}
        void tick(){
            if(restoring){if(pending.isDone()){pending.join();active=null;}return;}
            if(mc.player==null||mc.screen!=null)throw new IllegalStateException("World or screen changed during live wearable pass");
            if(!started){wait++;if(pending.isCompletedExceptionally())pending.join();boolean ready=pending.isDone()&&correctGear()&&mc.player.getMainHandItem().is(Items.BREAD)&&mc.player.getFoodData().getFoodLevel()<20&&!mc.player.isPassenger()&&mc.player.position().distanceTo(fixture)<2;
                stableTicks=ready?stableTicks+1:0;if(wait>180)throw new IllegalStateException("Equipment sync timeout: "+cases.get(index)+" actual="+actual()+" food="+mc.player.getFoodData().getFoodLevel()+" passenger="+mc.player.isPassenger());if(stableTicks<8)return;
                baselinePose=LiveWearableRenderState.lastPose;mc.options.setCameraType(CameraType.FIRST_PERSON);mc.player.setXRot(45);mc.options.keyUse.setDown(true);mc.gameMode.useItem(mc.player,InteractionHand.MAIN_HAND);useStack=mc.player.getUseItem();baseDuration=useStack.getUseDuration(mc.player);initialDuration=mc.player.getUseItemRemainingTicks();initialNativeUseTicks=mc.player.getTicksUsingItem();if(!mc.player.isUsingItem()||!useStack.is(Items.BREAD)||initialDuration<3)throw new IllegalStateException("Native bread use failed: base="+baseDuration+" actual="+initialDuration+" using="+mc.player.isUsingItem());firstCaptureAt=Math.max(1,Math.min(initialDuration-2,(int)Math.ceil(initialDuration*17.0/32.0)));secondCaptureAt=Math.max(firstCaptureAt+1,Math.min(initialDuration-1,(int)Math.ceil(initialDuration*24.0/32.0)));row.put("synchronizedTicks",stableTicks);row.put("baseUseDuration",baseDuration);row.put("initialUseDuration",initialDuration);row.put("initialNativeTicksUsingItem",initialNativeUseTicks);row.put("firstCaptureTarget",firstCaptureAt);row.put("secondCaptureTarget",secondCaptureAt);row.put("baselinePose",baselinePose);started=true;return;
            }
            actionTicks++;mc.options.keyUse.setDown(true);mc.player.setYRot(yaw);mc.player.setXRot(firstCaptured?8:45);
            if(!correctGear()||!mc.player.isUsingItem()||mc.player.getUseItem()!=useStack)throw new IllegalStateException("Equipment or active bread stack changed for "+cases.get(index).id()+" elapsed="+(initialDuration-mc.player.getUseItemRemainingTicks()));
            if(actionTicks>=initialDuration)throw new IllegalStateException("No capture completed before bread consumption");
        }
        void frame()throws Exception{
            if(restoring||!started)return;int elapsed=initialDuration-mc.player.getUseItemRemainingTicks();
            if(!firstCaptured&&elapsed>=firstCaptureAt){capture("firstperson",elapsed);firstCaptured=true;mc.options.setCameraType(cases.get(index).rear()?CameraType.THIRD_PERSON_BACK:CameraType.THIRD_PERSON_FRONT);mc.player.setXRot(8);return;}
            if(firstCaptured&&elapsed>=secondCaptureAt){
                capture(cases.get(index).rear()?"thirdperson-back":"thirdperson-front",elapsed);
                boolean poseChanged=armChanged(baselinePose,LiveWearableRenderState.lastPose);
                boolean invisibleWithoutGeometry=mc.player.isInvisible()&&LiveWearableRenderState.modelGeometry.vertices==0;
                row.put("actualEatingArmPoseChanged",poseChanged);row.put("eatingPose",LiveWearableRenderState.lastPose);
                row.put("nativeUseStatus","PASS");
                row.put("eatingModelVertices",LiveWearableRenderState.modelGeometry.vertices);
                row.put("eatingModelDrawCalls",LiveWearableRenderState.modelDrawCalls);
                if(invisibleWithoutGeometry){
                    row.put("visualStatus","NOT_VISUALLY_ASSESSABLE_NATIVE_INVISIBILITY");
                    row.put("status","NATIVE_USE_PASS_VISUAL_NOT_ASSESSABLE");
                }else{
                    boolean modelObserved=LiveWearableRenderState.modelGeometry.vertices>0;
                    row.put("visualStatus","REQUIRES_VISUAL_REVIEW");
                    if(!poseChanged||!modelObserved)failures.add(cases.get(index).id()+" "+cases.get(index).slot+": eating model not observed or arms did not differ from idle; vertices="+LiveWearableRenderState.modelGeometry.vertices+" changed="+poseChanged);
                    row.put("status",poseChanged&&modelObserved?"PASS":"FAIL");
                }
                releaseUse();if(++index<cases.size())prepare();else finish(failures.isEmpty()?"PASS":"FAIL","All native use assertions complete; visual assessability is recorded separately per case");
            }
        }
        @SuppressWarnings("unchecked") void capture(String view,int elapsed)throws Exception{
            if(elapsed<firstCaptureAt||elapsed>=initialDuration)throw new IllegalStateException("Capture outside active native bread-use window: "+elapsed+" of "+initialDuration);var c=cases.get(index);var state=LiveWearableRenderState.snapshot();int total=LiveWearableRenderState.bodyBread+LiveWearableRenderState.vanillaBread+LiveWearableRenderState.thirdPersonBread;
            boolean expectedVisible=!mc.player.isInvisible();boolean valid=total<=1&&(!expectedVisible||total==1);boolean bodyValid=!view.equals("firstperson")||!expectedVisible||LiveWearableRenderState.bodyPasses==(hasFpm()?1:0);if(!bodyValid)failures.add(c.id()+" "+c.slot+" "+view+": unexpected body-pass count "+LiveWearableRenderState.bodyPasses);if(!valid)failures.add(c.id()+" "+c.slot+" "+view+": held bread rendered "+total+" times");
            String file=String.format(Locale.ROOT,"%04d-%s-%s.png",++captures,c.id().replace(':','_'),view);try(NativeImage image=Screenshot.takeScreenshot(mc.getMainRenderTarget())){image.writeToFile(output.resolve(file));}
            var shot=new LinkedHashMap<String,Object>();shot.put("file",file);shot.put("view",view);shot.put("elapsedNativeUseTicks",elapsed);shot.put("elapsedClientTicks",actionTicks);shot.put("nativeTicksUsingItem",mc.player.getTicksUsingItem());shot.put("nativeTicksUsingItemDelta",mc.player.getTicksUsingItem()-initialNativeUseTicks);shot.put("baseUseDuration",baseDuration);shot.put("actualUseDuration",initialDuration);shot.put("remainingUseTicks",mc.player.getUseItemRemainingTicks());shot.put("sameUseStack",mc.player.getUseItem()==useStack);shot.put("correctGear",correctGear());shot.put("renderCounts",state);shot.put("invisible",mc.player.isInvisible());shot.put("modelGeometryObserved",LiveWearableRenderState.modelGeometry.vertices>0);shot.put("visualStatus",mc.player.isInvisible()&&LiveWearableRenderState.modelGeometry.vertices==0?"NOT_VISUALLY_ASSESSABLE_NATIVE_INVISIBILITY":"REQUIRES_VISUAL_REVIEW");shot.put("heldBreadDrawValid",valid);shot.put("bodyDrawValid",bodyValid);var anchors=AttachmentTestMod.current;if(anchors!=null){shot.put("attachmentRenderProbes",anchors);if(anchors.gogglesAnchorFailures+anchors.torsoAnchorFailures+anchors.flowerAnchorFailures+anchors.flowerColorFailures+anchors.invalidVertices+anchors.quiverInvalidVertices+LiveWearableRenderState.modelGeometry.invalidVertices>0)failures.add(c.id()+" "+c.slot+" "+view+": attachment anchor/geometry assertion failed");}shot.put("actualRenderedPose",LiveWearableRenderState.lastPose);((List<Map<String,Object>>)row.get("captures")).add(shot);write("RUNNING","");
        }
        void finish(String status,String detail){
            if(restoring)return;restoring=true;LiveWearableRenderState.active=false;releaseUse();keys.forEach(KeyMapping::setDown);mc.options.setCameraType(camera);mc.options.hideGui=gui;dev.tr7zw.notenoughanimations.versionless.NEABaseMod.config.enableEatDrinkAnimation=nea;if(hasFpm())Fpm.enabled(fpmEnabled);
            if(mc.player!=null){mc.player.setYRot(yaw);mc.player.setXRot(pitch);scarf(mc.player,scarfClient);clientRenderFlags.forEach((name,flags)->{var c=AccessoriesCapability.get(mc.player).getContainers().get(name);for(int i=0;i<Math.min(flags.size(),c.renderOptions().size());i++)c.renderOptions().set(i,flags.get(i));});}
            write("RESTORING",detail);pending=server(p->{if(original!=null)original.restore(p);return null;});pending.whenComplete((v,e)->mc.execute(()->{report.put("stateRestored",e==null);report.put("neaRestored",dev.tr7zw.notenoughanimations.versionless.NEABaseMod.config.enableEatDrinkAnimation==nea);report.put("inputRestored",keys.entrySet().stream().allMatch(x->x.getKey().isDown()==x.getValue()));write(e==null?status:"FAIL",e==null?detail:e.toString());active=null;}));
        }
        void write(String status,String detail){try{if(fixture!=null)report.put("fixturePosition",fixture.toString());report.put("status",status);report.put("detail",detail);report.put("completedCases",index-startIndex);report.put("nextCatalogIndex",index);report.put("captures",captures);Files.writeString(output.resolve("expanded-live-wearables-report.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));}catch(Exception e){throw new IllegalStateException(e);}}
    }
    private static boolean armChanged(Map<String,Object> before,Map<String,Object> after){for(String name:List.of("rightArmDescendants","leftArmDescendants")){if(before.get(name) instanceof List<?> a&&after.get(name) instanceof List<?> b)for(int part=0;part<Math.min(a.size(),b.size());part++)if(a.get(part) instanceof float[] aa&&b.get(part) instanceof float[] bb)for(int i=0;i<6;i++)if(Float.isFinite(aa[i])&&Float.isFinite(bb[i])&&Math.abs(aa[i]-bb[i])>0.001)return true;}return false;}
    private static boolean hasFpm(){return net.neoforged.fml.ModList.get().isLoaded("firstperson");}
    private static final class Fpm {static boolean enabled(){return dev.tr7zw.firstperson.api.FirstPersonAPI.isEnabled();}static void enabled(boolean enabled){dev.tr7zw.firstperson.api.FirstPersonAPI.setEnabled(enabled);}}
    private static Object scarfData(LivingEntity p){try{var supplier=(java.util.function.Supplier<?>)Class.forName("io.github.razordevs.deep_aether.networking.attachment.DAAttachments").getField("PLAYER").get(null);return p.getData((net.neoforged.neoforge.attachment.AttachmentType<?>)supplier.get());}catch(Exception e){throw new IllegalStateException(e);}}
    private static boolean scarf(LivingEntity p){try{var data=scarfData(p);return(boolean)data.getClass().getMethod("isFloatyScarfWrappedAroundNeck").invoke(data);}catch(Exception e){throw new IllegalStateException(e);}}
    private static void scarf(LivingEntity p,boolean wrapped){try{var data=scarfData(p);data.getClass().getMethod("setFloatyScarfWrappedAroundNeck",boolean.class).invoke(data,wrapped);}catch(Exception e){throw new IllegalStateException(e);}}
    private static void clear(ServerPlayer p){p.getInventory().clearContent();AccessoriesCapability.get(p).getContainers().values().forEach(c->{c.getAccessories().clearContent();c.getCosmeticAccessories().clearContent();for(int i=0;i<c.renderOptions().size();i++)c.renderOptions().set(i,true);});}
    private static Vec3 findGround(ServerPlayer p,Vec3 origin){for(int distance:new int[]{48,80,112})for(int dx:new int[]{distance,-distance})for(int dz:new int[]{distance,-distance}){int x=(int)Math.floor(origin.x)+dx,z=(int)Math.floor(origin.z)+dz,y=p.serverLevel().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z);boolean clear=true;for(int ox=-6;ox<=6&&clear;ox++)for(int oz=-6;oz<=6&&clear;oz++){if(p.serverLevel().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x+ox,z+oz)!=y){clear=false;break;}for(int oy=0;oy<5;oy++)if(!p.serverLevel().getBlockState(new BlockPos(x+ox,y+oy,z+oz)).isAir()){clear=false;break;}}if(clear)return new Vec3(x+.5,y,z+.5);}throw new IllegalStateException("No existing flat unobstructed wearable capture ground");}
    private record ContainerState(List<ItemStack> normal,List<ItemStack> cosmetic,List<Boolean> render){}
    private static final class Snapshot {
        final net.minecraft.nbt.CompoundTag foodState=new net.minecraft.nbt.CompoundTag();final List<ItemStack> inventory,armor,offhand;final int selected,food;final float saturation,health,yaw,pitch,fall;final Vec3 position,motion;final boolean flying,scarf;final List<MobEffectInstance> effects;final Map<String,ContainerState> containers=new LinkedHashMap<>();
        Snapshot(ServerPlayer p){p.getFoodData().addAdditionalSaveData(foodState);inventory=p.getInventory().items.stream().map(ItemStack::copy).toList();armor=p.getInventory().armor.stream().map(ItemStack::copy).toList();offhand=p.getInventory().offhand.stream().map(ItemStack::copy).toList();selected=p.getInventory().selected;food=p.getFoodData().getFoodLevel();saturation=p.getFoodData().getSaturationLevel();health=p.getHealth();position=p.position();motion=p.getDeltaMovement();yaw=p.getYRot();pitch=p.getXRot();fall=p.fallDistance;flying=p.getAbilities().flying;scarf=scarf(p);effects=p.getActiveEffects().stream().map(MobEffectInstance::new).toList();AccessoriesCapability.get(p).getContainers().forEach((name,c)->{var normal=new ArrayList<ItemStack>();var cosmetic=new ArrayList<ItemStack>();for(int i=0;i<c.getSize();i++){normal.add(c.getAccessories().getItem(i).copy());cosmetic.add(c.getCosmeticAccessories().getItem(i).copy());}containers.put(name,new ContainerState(normal,cosmetic,new ArrayList<>(c.renderOptions())));});}
        void restore(ServerPlayer p){ExpandedRagdollRecovery.release(p);p.stopUsingItem();p.stopRiding();clear(p);for(int i=0;i<inventory.size();i++)p.getInventory().items.set(i,inventory.get(i).copy());for(int i=0;i<armor.size();i++)p.getInventory().armor.set(i,armor.get(i).copy());for(int i=0;i<offhand.size();i++)p.getInventory().offhand.set(i,offhand.get(i).copy());p.getInventory().selected=selected;containers.forEach((name,state)->{var c=AccessoriesCapability.get(p).getContainers().get(name);for(int i=0;i<state.normal.size();i++){c.getAccessories().setItem(i,state.normal.get(i).copy());c.getCosmeticAccessories().setItem(i,state.cosmetic.get(i).copy());}for(int i=0;i<state.render.size();i++)c.renderOptions().set(i,state.render.get(i));});p.removeAllEffects();effects.forEach(p::addEffect);p.setHealth(health);p.getFoodData().setFoodLevel(food);p.getFoodData().setSaturation(saturation);p.getFoodData().readAdditionalSaveData(foodState);p.getAbilities().flying=flying;p.onUpdateAbilities();p.teleportTo(p.serverLevel(),position.x,position.y,position.z,yaw,pitch);p.setDeltaMovement(motion);p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(p));p.fallDistance=fall;scarf(p,scarf);p.inventoryMenu.broadcastChanges();for(int i=0;i<inventory.size();i++)if(!ItemStack.matches(inventory.get(i),p.getInventory().items.get(i)))throw new IllegalStateException("Inventory restoration mismatch");}
    }
}
