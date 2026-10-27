package dev.caleb.emfattachments.test;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import dev.leo.sableplayerragdoll.neoforge.client.RagdollCameraHelper;

/** Read-only camera coordinates in the actual native torso reference frame. */
public final class RagdollCameraProbe {
    private static boolean registered;
    private static final java.util.List<Map<String,Object>> overlays=new java.util.ArrayList<>();
    private static final java.util.List<Map<String,Object>> history=new java.util.ArrayList<>();
    private static final java.util.List<Map<String,Object>> mismatchCaptures=new java.util.ArrayList<>();
    private static int historyCount;
    private RagdollCameraProbe() {}
    public static void resetHistory() { history.clear(); historyCount=0; mismatchCaptures.clear(); }
    public static Map<String,Object> history() { return Map.of("count",historyCount,"sampleLimit",16,"samples",new java.util.ArrayList<>(history),"mismatchScreenshots",new java.util.ArrayList<>(mismatchCaptures)); }
    /** Called after the complete frame, so the actual opaque overlay is present in the screenshot. */
    public static void captureMismatchFrame(java.nio.file.Path output,String prefix) throws java.io.IOException {
        if(mismatchCaptures.size()>=3) return;
        var mismatch=overlays.stream().filter(row->row.get("type").equals("BLOCK") && !Boolean.TRUE.equals(row.get("canceled"))
                && ((java.util.List<?>)row.get("cameraBlockingSamples")).isEmpty()).findFirst();
        if(mismatch.isEmpty()) return;
        String filename=prefix+"-overlay-mismatch-"+(mismatchCaptures.size()+1)+".png";
        try(var image=net.minecraft.client.Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) { image.writeToFile(output.resolve(filename)); }
        var entry=new LinkedHashMap<String,Object>(mismatch.get()); entry.put("screenshot",filename); mismatchCaptures.add(entry);
    }
    public static void register() {
        if(registered) return;
        registered=true;
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.RenderFrameEvent.Pre event)->overlays.clear());
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST,true,
                net.neoforged.neoforge.client.event.RenderBlockScreenEffectEvent.class,event->{
                    if(!RagdollRenderCounters.active || event.getPlayer()!=Minecraft.getInstance().player || !RagdollRenderCounters.mainPass()) return;
                    var entry=new LinkedHashMap<String,Object>();
                    entry.put("type",event.getOverlayType().name()); entry.put("block",net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(event.getBlockState().getBlock()).toString());
                    entry.put("position",event.getBlockPos().toShortString()); entry.put("canceled",event.isCanceled());
                    var mc=Minecraft.getInstance(); Vec3 actual=mc.gameRenderer.getMainCamera().getPosition();
                    entry.put("actualCamera",actual.toString()); entry.put("playerEye",event.getPlayer().getEyePosition().toString());
                    entry.put("cameraBlockingSamples",blockingSamples(actual)); entry.put("cameraType",mc.options.getCameraType().name());
                    overlays.add(entry); historyCount++; if(history.size()<16) history.add(new LinkedHashMap<>(entry));
                });
    }
    public static Map<String,Object> capture(float partial) {
        Minecraft mc=Minecraft.getInstance(); var row=new LinkedHashMap<String,Object>();
        Vec3 actual=mc.gameRenderer.getMainCamera().getPosition();
        Vec3 expected=RagdollCameraHelper.firstPersonHeadAnchor(actual,partial);
        row.put("actualCamera",actual.toString()); row.put("nativeExpectedAnchor",expected.toString());
        row.put("cameraAnchorDistance",actual.distanceTo(expected)); row.put("partialTick",partial);
        row.put("playerPitch",mc.player.getXRot()); row.put("playerYaw",mc.player.getYRot());
        row.put("cameraPitch",mc.gameRenderer.getMainCamera().getXRot()); row.put("cameraYaw",mc.gameRenderer.getMainCamera().getYRot());
        row.put("nativeFirstPersonAligned",RagdollCameraHelper.isFirstPersonAligned());
        row.put("nativeAnchorApplicable",dev.leo.sableplayerragdoll.neoforge.config.RagdollClientConfig.useFirstPersonCamera()
                &&mc.options.getCameraType().isFirstPerson()&&!ExpandedRagdollRecovery.clientRestored(mc.player));
        row.put("actualScreenOverlayEvents",new java.util.ArrayList<>(overlays));
        row.put("screenOverlayHistory",history());
        row.put("nativeEyeBlockingSamples",blockingSamples(mc.player.getEyePosition()));
        row.put("actualCameraBlockingSamples",blockingSamples(actual));
        try {
            Object helper=Class.forName("dev.ryanhcode.sable.Sable").getField("HELPER").get(null);
            Object torso=helper.getClass().getMethod("getVehicleSubLevel",Entity.class).invoke(helper,mc.player);
            if(torso==null) { row.put("torsoMissing",true); return row; }
            Object plot=torso.getClass().getMethod("getPlot").invoke(torso);
            BlockPos center=(BlockPos)plot.getClass().getMethod("getCenterBlock").invoke(plot);
            Object pose=torso.getClass().getMethod("renderPose",float.class).invoke(torso,partial);
            var transform=pose.getClass().getMethod("transformPosition",Vec3.class);
            Vec3 origin=(Vec3)transform.invoke(pose,Vec3.atCenterOf(center));
            Vec3 up=(Vec3)transform.invoke(pose,Vec3.atCenterOf(center).add(0,1,0));
            Vec3 forward=(Vec3)transform.invoke(pose,Vec3.atCenterOf(center).add(0,0,1));
            Vec3 right=(Vec3)transform.invoke(pose,Vec3.atCenterOf(center).add(1,0,0));
            Vec3 delta=actual.subtract(origin);
            row.put("torsoWorldCenter",origin.toString()); row.put("torsoUp",up.subtract(origin).toString());
            row.put("torsoForward",forward.subtract(origin).toString());
            row.put("cameraTorsoLocal",new Vec3(delta.dot(right.subtract(origin).normalize()),delta.dot(up.subtract(origin).normalize()),delta.dot(forward.subtract(origin).normalize())).toString());
        } catch(ReflectiveOperationException failure) { row.put("torsoReadError",failure.toString()); }
        return row;
    }
    /** Same eight corners and predicates as native ScreenEffectRenderer, with a selectable center. */
    private static java.util.List<Map<String,Object>> blockingSamples(Vec3 center) {
        var mc=Minecraft.getInstance(); var result=new java.util.ArrayList<Map<String,Object>>();
        for(int i=0;i<8;i++) {
            double x=center.x+(((i>>0)%2-.5F)*mc.player.getBbWidth()*.8F);
            double y=center.y+(((i>>1)%2-.5F)*.1F*mc.player.getScale());
            double z=center.z+(((i>>2)%2-.5F)*mc.player.getBbWidth()*.8F);
            BlockPos pos=BlockPos.containing(x,y,z); var state=mc.level.getBlockState(pos);
            if(state.getRenderShape()!=net.minecraft.world.level.block.RenderShape.INVISIBLE && state.isViewBlocking(mc.level,pos))
                result.add(Map.of("position",pos.toShortString(),"block",net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()));
        }
        return result;
    }
}
