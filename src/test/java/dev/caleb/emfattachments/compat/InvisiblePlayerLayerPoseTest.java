package dev.caleb.emfattachments.compat;

import com.google.gson.JsonParser;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.caleb.emfattachments.mixin.InvisiblePlayerLayerPoseMixin;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.LivingEntity;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import static org.junit.jupiter.api.Assertions.*;

class InvisiblePlayerLayerPoseTest {
    private static final String RENDERER = "net/minecraft/client/renderer/entity/LivingEntityRenderer";
    private static final String MIXIN = "dev/caleb/emfattachments/mixin/InvisiblePlayerLayerPoseMixin";
    private static final String PARTS = "traben/entity_model_features/models/parts/";
    private static final String CALLBACK = "emfattachments$poseInvisiblePlayerLayers";
    private static final String RENDER = "(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;"
            + "Lnet/minecraft/client/renderer/MultiBufferSource;I)V";

    @Test void fullyHiddenBodyAloneSkipsGeometryWhileVisibleTranslucentAndOutlineHaveRenderTypes() throws Exception {
        MethodNode type = method(RENDERER,"getRenderType");
        // Renderer's local flags: 2=body visible, 3=visible to viewer through invisibility, 4=outline.
        var translucent = guardedCall(type,3,"itemEntityTranslucentCull");
        var visible = guardedCall(type,2,"renderType");
        var outlined = guardedCall(type,4,"outline");
        assertTrue(offset(type,translucent) < offset(type,visible) && offset(type,visible) < offset(type,outlined));
        JumpInsnNode outline = guard(type,4);
        assertEquals(Opcodes.ACONST_NULL, executable(outline.label).getOpcode());
        assertEquals(Opcodes.ARETURN, executable(executable(outline.label).getNext()).getOpcode());

        var render = method(RENDERER,"render", RENDER);
        var typeCall = call(render,"getRenderType");
        var skip = (JumpInsnNode) Arrays.stream(render.instructions.toArray())
                .filter(i -> offset(render,i) > offset(render,typeCall) && i.getOpcode()==Opcodes.IFNULL).findFirst().orElseThrow();
        assertTrue(offset(render,call(render,"setupAnim")) < offset(render,typeCall));
        assertTrue(offset(render,skip) < offset(render,call(render,"renderToBuffer")));
        assertTrue(offset(render,call(render,"renderToBuffer")) < offset(render,skip.label));
        assertTrue(offset(render,skip.label) < offset(render,call(render,"iterator")));
    }

    @Test void poseOnlyHookUsesTheMainRootBeforeFeaturesAndHonorsTheNativePauseGate() throws Exception {
        var compiled=method(MIXIN,CALLBACK);
        var normal=method(PARTS+"EMFModelPartWithState","render");
        for (MethodNode m : new MethodNode[]{compiled,normal}) {
            assertTrue(offset(m,call(m,"oneTimeRunnable")) < offset(m,call(m,"animate")));
        }
        var vanillaPose=method(PARTS+"EMFModelPartRoot","animate");
        var pause=call(vanillaPose,"shouldAnimationsPause");
        var paused=(JumpInsnNode)executable(pause.getNext());
        assertEquals(Opcodes.IFNE,paused.getOpcode());
        assertTrue(offset(vanillaPose,call(vanillaPose,"run")) < offset(vanillaPose,paused.label));

        var nodes=Arrays.asList(compiled.instructions.toArray());
        assertFalse(nodes.stream().anyMatch(i -> i instanceof MethodInsnNode m
                && (m.name.equals("render") || m.name.equals("renderToBuffer") || m.name.equals("animateNoPause"))));
        for(var i : nodes) {
            if(i.getOpcode()==Opcodes.ARETURN) {
                var original=(VarInsnNode)executablePrevious(i.getPrevious());
                assertEquals(Opcodes.ALOAD,original.getOpcode());
                assertEquals(1,original.var);
            }
        }
        var firstBranch=(JumpInsnNode)nodes.stream().filter(i -> i instanceof JumpInsnNode).findFirst().orElseThrow();
        assertEquals(Opcodes.IFNONNULL,firstBranch.getOpcode());
        assertEquals(Opcodes.ARETURN,executable(executable(firstBranch.label).getNext()).getOpcode());
        for(String guard : new String[]{"isInvisible","isSpectator","emf$isEMFModel","emfEntity","isFirstPersonHand"}) {
            assertTrue(offset(compiled,call(compiled,guard)) < offset(compiled,call(compiled,"oneTimeRunnable")),guard);
        }
        var hook=InvisiblePlayerLayerPoseMixin.class.getDeclaredMethod(CALLBACK,RenderType.class,LivingEntity.class)
                .getAnnotation(ModifyExpressionValue.class);
        assertArrayEquals(new String[]{"render"+RENDER},hook.method());
        assertEquals("L"+RENDERER+";getRenderType(Lnet/minecraft/world/entity/LivingEntity;ZZZ)Lnet/minecraft/client/renderer/RenderType;",
                hook.at()[0].target());
        assertEquals(1,hook.require());
        assertEquals(1,hook.allow());
    }

    @Test void onlyTheClientWithTheVerifiedEmfVersionEnablesTheHook() throws Exception {
        var fixture=new MixinDiscoveryFixture();
        for(String version : new String[]{"","3.3.11","3.3.12","3.3.13"}) {
            var versions=version.isEmpty() ? Map.<String,DefaultArtifactVersion>of()
                    : Map.of("entity_model_features",new DefaultArtifactVersion(version));
            var plugin=fixture.selector(versions::containsKey,(mod,min)->false,versions);
            assertEquals(version.equals("3.3.12"),plugin.shouldApplyMixin(RENDERER.replace('/','.'),MIXIN.replace('/','.')));
        }
        try(var in=getClass().getResourceAsStream("/emfattachments.mixins.json")) {
            assertNotNull(in);
            var cfg=JsonParser.parseString(new String(in.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
            assertTrue(cfg.getAsJsonArray("client").asList().stream().anyMatch(v->v.getAsString().equals("InvisiblePlayerLayerPoseMixin")));
            assertFalse(cfg.has("mixins") && cfg.getAsJsonArray("mixins").asList().stream()
                    .anyMatch(v->v.getAsString().equals("InvisiblePlayerLayerPoseMixin")));
        }
    }

    private static JumpInsnNode guard(MethodNode m,int local) {
        for(var i : m.instructions) {
            if(i instanceof VarInsnNode v && v.getOpcode()==Opcodes.ILOAD && v.var==local)
                return (JumpInsnNode)executable(v.getNext());
        }
        throw new AssertionError("Missing flag " + local);
    }

    private static MethodInsnNode guardedCall(MethodNode m,int local,String name) {
        var test=guard(m,local);
        var result=call(m,name);
        assertEquals(Opcodes.IFEQ,test.getOpcode());
        assertTrue(offset(m,test)<offset(m,result) && offset(m,result)<offset(m,test.label),name);
        return result;
    }

    private static int offset(MethodNode m,AbstractInsnNode i) { return m.instructions.indexOf(i); }
    private static AbstractInsnNode executable(AbstractInsnNode i) {
        while(i.getOpcode()<0) i=i.getNext();
        return i;
    }
    private static AbstractInsnNode executablePrevious(AbstractInsnNode i) {
        while(i.getOpcode()<0) i=i.getPrevious();
        return i;
    }
    private static MethodInsnNode call(MethodNode m,String name) {
        return Arrays.stream(m.instructions.toArray()).filter(i -> i instanceof MethodInsnNode inv && inv.name.equals(name))
                .map(MethodInsnNode.class::cast).findFirst().orElseThrow();
    }
    private static MethodNode method(String owner,String name,String...desc) throws IOException {
        try(var in=InvisiblePlayerLayerPoseTest.class.getResourceAsStream("/"+owner+".class")) {
            assertNotNull(in,owner);
            var node=new ClassNode();
            new ClassReader(in).accept(node,ClassReader.SKIP_DEBUG);
            return node.methods.stream().filter(m->m.name.equals(name) && (desc.length==0 || m.desc.equals(desc[0])))
                    .findFirst().orElseThrow();
        }
    }
}
