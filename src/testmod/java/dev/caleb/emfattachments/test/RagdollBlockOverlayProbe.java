package dev.caleb.emfattachments.test;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.caleb.emfattachments.client.RagdollBlockOverlayOrigin;
import dev.leo.sableplayerragdoll.neoforge.config.RagdollClientConfig;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.tuple.Pair;

/** Deterministic, immediately restored block fixtures around an actual native ragdoll camera. */
public final class RagdollBlockOverlayProbe {
    private RagdollBlockOverlayProbe() {}
    public static Map<String,Object> run(Path output) {
        var result=new LinkedHashMap<String,Object>();var mc=Minecraft.getInstance();var player=mc.player;
        Camera camera=mc.gameRenderer.getMainCamera();Vec3 cameraBefore=camera.getPosition(),positionBefore=player.position();
        var entityBefore=mc.getCameraEntity();var typeBefore=mc.options.getCameraType();boolean config=RagdollClientConfig.USE_FIRST_PERSON_CAMERA.get();
        Map<BlockPos,BlockState> blocks=new LinkedHashMap<>();
        try {
            require(RagdollBlockOverlayOrigin.applies(player),"Actual native first-person seat/camera required");
            var lookup=ScreenEffectRenderer.class.getDeclaredMethod("getOverlayBlock",Player.class);lookup.setAccessible(true);
            var setPosition=Camera.class.getDeclaredMethod("setPosition",Vec3.class);setPosition.setAccessible(true);
            result.put("nativePosition",positionBefore.toString());result.put("nativeEye",player.getEyePosition().toString());result.put("nativeCamera",cameraBefore.toString());
            result.put("nativeNoPhysics",player.noPhysics);result.put("nativeSpectator",player.isSpectator());
            Set<BlockPos> eyes=samples(player,player.getEyePosition()), cameras=samples(player,cameraBefore);
            Set<BlockPos> eyeOnly=new LinkedHashSet<>(eyes);eyeOnly.removeAll(cameras);
            Set<BlockPos> cameraOnly=new LinkedHashSet<>(cameras);cameraOnly.removeAll(eyes);
            if(eyeOnly.isEmpty()||cameraOnly.isEmpty()) {
                // Use the relative separation from the original r5 black frame, at a
                // block boundary, when today's settled physics pose shares one voxel.
                // The native seat, aligned camera mode and rotation stay in place.
                Vec3 recordedCamera=new Vec3(cameraBefore.x,Math.floor(cameraBefore.y)+.192975,cameraBefore.z);
                double eyeOffset=player.getEyeY()-player.getY();
                player.setPos(recordedCamera.x,recordedCamera.y-.461904-eyeOffset,recordedCamera.z);setPosition.invoke(camera,recordedCamera);
                eyes=samples(player,player.getEyePosition());cameras=samples(player,recordedCamera);
                eyeOnly=new LinkedHashSet<>(eyes);eyeOnly.removeAll(cameras);cameraOnly=new LinkedHashSet<>(cameras);cameraOnly.removeAll(eyes);
                result.put("geometry","Recorded r5 camera/eye vertical separation replay, native seat and camera mode retained");
            } else result.put("geometry","Current actual native camera and player-eye sample cells");
            require(!eyeOnly.isEmpty()&&!cameraOnly.isEmpty(),"Distinct control voxels unavailable");
            var all=new LinkedHashSet<>(eyes);all.addAll(cameras);
            for(var pos:all) {blocks.put(pos,mc.level.getBlockState(pos));mc.level.setBlock(pos,Blocks.AIR.defaultBlockState(),3);}
            BlockPos eyeSolid=eyeOnly.iterator().next(),cameraSolid=cameraOnly.iterator().next();
            result.put("fixtureEye",player.getEyePosition().toString());result.put("fixtureCamera",camera.getPosition().toString());
            result.put("eyeOnlySolid",eyeSolid.toString());result.put("cameraOnlySolid",cameraSolid.toString());
            mc.level.setBlock(eyeSolid,Blocks.BEDROCK.defaultBlockState(),3);
            Object eyeNative=lookup.invoke(null,player),eyeCandidate=RagdollBlockOverlayOrigin.atCamera(player);
            require(eyeCandidate==null,"Candidate did not preserve clear real camera");
            boolean mismatch=eyeNative!=null;result.put("baselineWrongOriginReproduced",mismatch);
            result.put("eyeSolidNativeLookup",describe(eyeNative));result.put("eyeSolidCameraLookup",describe(eyeCandidate));
            // Calls the actual native overlay entry point; the bounded event probe
            // records its source block and camera samples. The snapshot is diagnostic
            // because this explicit call runs after the normal frame composition.
            ScreenEffectRenderer.renderScreenEffect(mc,new PoseStack());
            try(var image=Screenshot.takeScreenshot(mc.getMainRenderTarget())) {image.writeToFile(output.resolve("overlay-eye-solid-diagnostic.png"));}
            result.put("nativeOverlayEventHistory",RagdollCameraProbe.history());
            mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            require(!RagdollBlockOverlayOrigin.applies(player)&&lookup.invoke(null,player)!=null,"Third-person native lookup changed");
            result.put("thirdPersonPreserved",true);mc.options.setCameraType(typeBefore);
            RagdollClientConfig.USE_FIRST_PERSON_CAMERA.set(false);
            require(!RagdollBlockOverlayOrigin.applies(player)&&lookup.invoke(null,player)!=null,"Disabled native camera lookup changed");
            result.put("disabledNativeCameraPreserved",true);RagdollClientConfig.USE_FIRST_PERSON_CAMERA.set(config);
            var other=new RemotePlayer(mc.level,new GameProfile(UUID.randomUUID(),"OverlayControl"));
            other.setPos(player.getX(),player.getEyeY()-other.getEyeHeight(),player.getZ());
            require(!RagdollBlockOverlayOrigin.applies(other)&&lookup.invoke(null,other)!=null,"Ordinary other player's native solid overlay changed");result.put("ordinaryOtherPlayerScopePreserved",true);
            mc.setCameraEntity(other);require(!RagdollBlockOverlayOrigin.applies(player)&&lookup.invoke(null,player)!=null,"Other camera entity lookup changed");
            result.put("differentCameraEntityPreserved",true);mc.setCameraEntity(entityBefore);
            mc.level.setBlock(eyeSolid,Blocks.AIR.defaultBlockState(),3);mc.level.setBlock(cameraSolid,Blocks.BEDROCK.defaultBlockState(),3);
            Object cameraNative=lookup.invoke(null,player),cameraCandidate=RagdollBlockOverlayOrigin.atCamera(player);
            require(cameraCandidate instanceof Pair<?,?> pair&&pair.getRight().equals(cameraSolid),"Candidate lost actual camera-in-solid overlay");
            result.put("cameraSolidNativeLookup",describe(cameraNative));result.put("cameraSolidCameraLookup",describe(cameraCandidate));
            require(mismatch?cameraNative==null:cameraNative!=null,"Native result is inconsistent with baseline/fixed lookup");
            result.put("actualCameraOpaquePreservedByCandidate",true);result.put("status",mismatch?"BASELINE_REPRODUCED":"FIXED_PASS");
        } catch(Throwable e) {result.put("status","FAIL");result.put("error",e.toString());}
        finally {
            blocks.forEach((p,s)->mc.level.setBlock(p,s,3));player.setPos(positionBefore);mc.setCameraEntity(entityBefore);mc.options.setCameraType(typeBefore);RagdollClientConfig.USE_FIRST_PERSON_CAMERA.set(config);
            try {var set=Camera.class.getDeclaredMethod("setPosition",Vec3.class);set.setAccessible(true);set.invoke(camera,cameraBefore);}catch(Exception e){result.put("restoreError",e.toString());}
            result.put("stateRestored",player.position().equals(positionBefore)&&camera.getPosition().equals(cameraBefore)&&mc.getCameraEntity()==entityBefore&&mc.options.getCameraType()==typeBefore&&RagdollClientConfig.USE_FIRST_PERSON_CAMERA.get()==config&&blocks.entrySet().stream().allMatch(e->mc.level.getBlockState(e.getKey()).equals(e.getValue())));
        }
        return result;
    }
    private static Object describe(Object value) {return value==null?"clear":value.toString();}
    private static Set<BlockPos> samples(Player p,Vec3 origin) {
        Set<BlockPos> result=new LinkedHashSet<>();for(int i=0;i<8;i++)result.add(BlockPos.containing(origin.x+(((i>>0)%2-.5F)*p.getBbWidth()*.8F),origin.y+(((i>>1)%2-.5F)*.1F*p.getScale()),origin.z+(((i>>2)%2-.5F)*p.getBbWidth()*.8F)));return result;
    }
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
}
