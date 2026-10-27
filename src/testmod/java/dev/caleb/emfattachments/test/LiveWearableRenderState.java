package dev.caleb.emfattachments.test;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import com.mojang.blaze3d.vertex.VertexConsumer;

/** Actual world-render observations; held-bread counts exclude accessory/head item rendering. */
public final class LiveWearableRenderState {
    public static boolean active;
    public static int playerPasses, bodyPasses, bodyBread, vanillaBread, thirdPersonBread;
    public static int modelDrawCalls;
    public static AttachmentTestMod.TestResult modelGeometry=new AttachmentTestMod.TestResult();
    public static Map<String,Object> lastPose=Map.of();
    private LiveWearableRenderState() {}
    private static boolean eligible(LivingEntity entity) {
        return active && entity==Minecraft.getInstance().player && !(net.neoforged.fml.ModList.get().isLoaded("iris")
                && net.irisshaders.iris.api.v0.IrisApi.getInstance().isRenderingShadowPass());
    }
    private static boolean body(LivingEntity entity) {
        return net.neoforged.fml.ModList.get().isLoaded("firstperson") && dev.caleb.emfattachments.client.FirstPersonRenderPolicy.isLocalBody(entity);
    }
    public static void reset() { playerPasses=bodyPasses=bodyBread=vanillaBread=thirdPersonBread=modelDrawCalls=0; modelGeometry=new AttachmentTestMod.TestResult(); }
    /** Instrument the actual base player model call, distinct from renderer entry or accessory draws. */
    public static VertexConsumer model(LivingEntity entity,VertexConsumer original) {
        if(!eligible(entity))return original;
        modelDrawCalls++;
        return new CountingVertexConsumer(original,modelGeometry);
    }
    public static void player(LivingEntity entity) { if(eligible(entity)){playerPasses++;if(body(entity))bodyPasses++;} }
    public static void pose(LivingEntity entity,PlayerModel<?> model) {
        if(!eligible(entity))return;
        var m=new LinkedHashMap<String,Object>();m.put("head",ModelState.state(model.head));m.put("rightArm",ModelState.state(model.rightArm));m.put("leftArm",ModelState.state(model.leftArm));m.put("rightArmDescendants",model.rightArm.getAllParts().map(ModelState::state).toList());m.put("leftArmDescendants",model.leftArm.getAllParts().map(ModelState::state).toList());m.put("model",model.getClass().getName());m.put("headClass",model.head.getClass().getName());lastPose=m;
    }
    public static void item(LivingEntity entity,ItemStack stack,ItemDisplayContext context) {
        if(!eligible(entity)||!stack.is(Items.BREAD))return;
        if(body(entity))bodyBread++;else if(context.firstPerson())vanillaBread++;else thirdPersonBread++;
    }
    public static Map<String,Object> snapshot() { return Map.of("playerPasses",playerPasses,"bodyPasses",bodyPasses,"bodyBread",bodyBread,"vanillaBread",vanillaBread,"thirdPersonBread",thirdPersonBread,"modelDrawCalls",modelDrawCalls,"modelVertices",modelGeometry.vertices,"modelInvalidVertices",modelGeometry.invalidVertices); }
}
