package dev.caleb.emfattachments.test;

import artifacts.client.item.model.ArmsModel;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.caleb.emfattachments.client.RagdollAccessoryArmScope;
import dev.caleb.emfattachments.client.RagdollAccessoryDrawScope;
import dev.leo.sableplayerragdoll.block.entity.RagdollPartBlockEntity.BodyPart;
import dev.leo.sableplayerragdoll.entity.RagdollDollEntity;
import dev.leo.sableplayerragdoll.neoforge.client.RagdollPartBlockEntityRenderer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/** Calls installed Sable helpers and actual Artifacts models with both claws present. */
public final class RagdollClawProbe {
    private RagdollClawProbe() {}

    public static Map<String,Object> run() {
        var report = new LinkedHashMap<String,Object>();
        var rows = new ArrayList<Map<String,Object>>();
        report.put("scope", "Actual transformed Sable stored Accessories/Curios helpers and installed Artifacts claw models, both arm indices together. Controlled physical-part invocation; real native ragdoll screenshots remain separate.");
        report.put("cases", rows);
        var mc = Minecraft.getInstance();
        Field activeModel = null, activePart = null; Object priorModel = null, priorPart = null;
        ListTag inventory = mc.player.getInventory().save(new ListTag());
        try {
            activeModel = RagdollPartBlockEntityRenderer.class.getDeclaredField("activeModel"); activeModel.setAccessible(true);
            activePart = RagdollPartBlockEntityRenderer.class.getDeclaredField("activeBodyPart"); activePart.setAccessible(true);
            priorModel = activeModel.get(null); priorPart = activePart.get(null);
            Method accessories = helper("AccessoriesRenderHelper"), curios = helper("CuriosRenderHelper");
            ItemStack blue = item("artifacts:digging_claws"), green = item("artifacts:feral_claws");
            for (boolean slim : new boolean[]{false,true}) {
                ArmsModel nativeClaws = ArmsModel.createClawsModel(slim);
                int expectedVertices = vertices(nativeClaws, HumanoidArm.RIGHT);
                require(expectedVertices > 0 && vertices(nativeClaws, HumanoidArm.LEFT) == expectedVertices, "Native claw control geometry differs by arm");
                for (HumanoidArm mainArm : HumanoidArm.values()) {
                    PlayerSkin skin = mc.player.getSkin();
                    PlayerSkin selectedSkin = new PlayerSkin(skin.texture(),skin.textureUrl(),skin.capeTexture(),skin.elytraTexture(),slim?PlayerSkin.Model.SLIM:PlayerSkin.Model.WIDE,skin.secure());
                    RemotePlayer entity = new RemotePlayer(mc.level,new GameProfile(UUID.randomUUID(),"ClawControl")) {
                        @Override public HumanoidArm getMainArm() { return mainArm; }
                        @Override public PlayerSkin getSkin() { return selectedSkin; }
                    };
                    PlayerModel<RagdollDollEntity> model = new PlayerModel<>(mc.getEntityModels().bakeLayer(slim?ModelLayers.PLAYER_SLIM:ModelLayers.PLAYER),slim);
                    RenderLayerParent<RagdollDollEntity,PlayerModel<RagdollDollEntity>> parent = new RenderLayerParent<>() {
                        @Override public PlayerModel<RagdollDollEntity> getModel() { return model; }
                        @Override public ResourceLocation getTextureLocation(RagdollDollEntity ignored) { return selectedSkin.texture(); }
                    };
                    activeModel.set(null, model);
                    for (BodyPart part : List.of(BodyPart.RIGHT_ARM,BodyPart.LEFT_ARM)) {
                        HumanoidArm physical = part==BodyPart.RIGHT_ARM?HumanoidArm.RIGHT:HumanoidArm.LEFT;
                        model.setAllVisible(false);
                        (physical==HumanoidArm.RIGHT?model.rightArm:model.leftArm).visible=true;
                        activePart.set(null,part);
                        for (boolean withAccessories : new boolean[]{false,true}) {
                            Counts counts = new Counts();
                            var before = Arrays.asList(model.rightArm.y,model.leftArm.y);
                            try (var ledger = RagdollAccessoryDrawScope.open()) {
                                if(withAccessories) accessories.invoke(null,part,Map.of("hand",List.of(blue,green)),entity,parent,new PoseStack(),counts,15728880,.5f);
                                curios.invoke(null,part,Map.of("hands",List.of(blue,green)),entity,parent,new PoseStack(),counts,15728880,.5f);
                            }
                            String expected = physical==mainArm?"digging_claws":"feral_claws";
                            String opposite = physical==mainArm?"feral_claws":"digging_claws";
                            var row = new LinkedHashMap<String,Object>(); rows.add(row);
                            row.put("skin",slim?"slim":"default");row.put("mainArm",mainArm.name());row.put("physicalPart",part.name());
                            row.put("path",withAccessories?"accessories_then_curios":"curios_fallback_only");row.put("expectedTexture",expected);
                            row.put("expectedVertices",expectedVertices);row.put("textureVertices",counts.byType);
                            row.put("selectedVertices",counts.forTexture(expected));row.put("oppositeVertices",counts.forTexture(opposite));
                            require(counts.forTexture(expected)==expectedVertices,"Chosen claw missing or drawn more than once: "+row);
                            require(counts.forTexture(opposite)==0,"Opposite claw emitted on physical arm: "+row);
                            require(before.equals(Arrays.asList(model.rightArm.y,model.leftArm.y)),"Sable parent limb offset not restored");
                            require(!RagdollAccessoryArmScope.hides(HumanoidArm.LEFT)&&!RagdollAccessoryArmScope.hides(HumanoidArm.RIGHT),"Arm scope leaked");
                            row.put("status","PASS");
                        }
                    }
                }
                // No Sable accessory invocation: ordinary third-person/hand models remain intact.
                require(vertices(nativeClaws,HumanoidArm.RIGHT)==expectedVertices && vertices(nativeClaws,HumanoidArm.LEFT)==expectedVertices,"Ordinary claw geometry changed outside scope");
            }
            require(inventory.equals(mc.player.getInventory().save(new ListTag())),"Player inventory changed");
            report.put("status","PASS");report.put("inventoryUnchanged",true);
        } catch(Throwable failure) {
            report.put("status","FAIL");report.put("detail",failure.toString());
            if(failure.getCause()!=null)report.put("cause",failure.getCause().toString());
        } finally {
            try { if(activeModel!=null)activeModel.set(null,priorModel);if(activePart!=null)activePart.set(null,priorPart);report.put("nativeRenderGlobalsRestored",true); }
            catch(Throwable failure){report.put("status","FAIL");report.put("restoreError",failure.toString());}
        }
        return report;
    }
    private static Method helper(String name) throws Exception {
        Method method=Class.forName("dev.leo.sableplayerragdoll.neoforge.client."+name).getDeclaredMethod("renderFromStored",BodyPart.class,Map.class,LivingEntity.class,RenderLayerParent.class,PoseStack.class,MultiBufferSource.class,int.class,float.class);
        method.setAccessible(true);return method;
    }
    private static ItemStack item(String id){ItemStack s=BuiltInRegistries.ITEM.get(ResourceLocation.parse(id)).getDefaultInstance();require(!s.isEmpty(),"Missing "+id);return s;}
    private static int vertices(ArmsModel model,HumanoidArm arm){Counter c=new Counter(null,null);model.renderArm(arm,new PoseStack(),c,15728880,0,-1);return c.vertices;}
    private static void require(boolean condition,String detail){if(!condition)throw new AssertionError(detail);}
    private static final class Counts implements MultiBufferSource {
        final Map<String,Integer> byType=new LinkedHashMap<>();
        @Override public VertexConsumer getBuffer(net.minecraft.client.renderer.RenderType type){return new Counter(this,type.toString());}
        int forTexture(String name){return byType.entrySet().stream().filter(e->e.getKey().contains(name)).mapToInt(Map.Entry::getValue).sum();}
    }
    private static final class Counter implements VertexConsumer {
        final Counts counts;final String type;int vertices;Counter(Counts counts,String type){this.counts=counts;this.type=type;}
        @Override public VertexConsumer addVertex(float x,float y,float z){require(Float.isFinite(x)&&Float.isFinite(y)&&Float.isFinite(z),"Nonfinite vertex");vertices++;if(counts!=null)counts.byType.merge(type,1,Integer::sum);return this;}
        @Override public VertexConsumer setColor(int r,int g,int b,int a){return this;}
        @Override public VertexConsumer setUv(float u,float v){return this;}
        @Override public VertexConsumer setUv1(int u,int v){return this;}
        @Override public VertexConsumer setUv2(int u,int v){return this;}
        @Override public VertexConsumer setNormal(float x,float y,float z){return this;}
    }
}
