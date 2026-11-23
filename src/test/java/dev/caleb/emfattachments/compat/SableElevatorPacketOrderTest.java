package dev.caleb.emfattachments.compat;

import com.google.gson.JsonParser;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.caleb.emfattachments.mixin.SableElevatorPositionMixin;
import dev.caleb.emfattachments.mixin.SableElevatorPostTickMixin;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static org.junit.jupiter.api.Assertions.*;

class SableElevatorPacketOrderTest {
    @Test void vanillaSendsTheLocalPlayerPositionOnceBeforeCreatesClientPostTickCollisions() throws Exception {
        var playerTick = calls("net/minecraft/client/player/LocalPlayer", "tick");
        assertTrue(only(playerTick, "net/minecraft/client/player/AbstractClientPlayer.tick")
                < only(playerTick, "net/minecraft/client/player/LocalPlayer.sendPosition"));
        var gameTick = calls("net/minecraft/client/Minecraft", "tick");
        assertTrue(only(gameTick, "net/minecraft/client/multiplayer/ClientLevel.tickEntities")
                < only(gameTick, "net/neoforged/neoforge/client/ClientHooks.fireClientTickPost"));
        String clientEvents = "com/simibubi/create/foundation/events/ClientEvents";
        String contraptions = "com/simibubi/create/content/contraptions/";
        only(calls(clientEvents, "onTickPost"), clientEvents + ".onTick");
        only(calls(clientEvents, "onTick"), contraptions + "ContraptionHandler.tick");
        only(calls(contraptions + "ContraptionHandler", "tick"), contraptions + "ContraptionCollider.collideEntities");
        only(calls("net/neoforged/neoforge/client/ClientHooks", "fireClientTickPost"), "net/neoforged/bus/api/IEventBus.post");
        var wrap = SableElevatorPositionMixin.class.getDeclaredMethod("emfattachments$sendCabinPosition", LocalPlayer.class,
                Operation.class).getAnnotation(WrapOperation.class);
        assertArrayEquals(new String[] {"tick"}, wrap.method());
        assertEquals(1, wrap.require());
        assertEquals(1, wrap.allow());
        assertEquals("Lnet/minecraft/client/player/LocalPlayer;sendPosition()V", wrap.at()[0].target());
        var flush = SableElevatorPostTickMixin.class.getDeclaredMethod("emfattachments$finishCabinPosition",
                CallbackInfo.class).getAnnotation(Inject.class);
        assertArrayEquals(new String[] {"fireClientTickPost"}, flush.method());
        assertEquals("RETURN", flush.at()[0].value());
    }

    @Test void sableOwnsTheNativePositionPacketCoordinateFrameAtBothEnds() throws Exception {
        String sable = "dev/ryanhcode/sable/";
        String playerMixin = sable + "mixin/entity/entities_stick_sublevels/player/";
        String setPosition = sable + "mixinterface/entity/entity_sublevel_collision/EntityMovementExtension.sable$setPosField";
        var before = calls(playerMixin + "LocalPlayerMixin", "sable$preSendPosition");
        assertTrue(only(before, sable + "companion/math/Pose3d.transformPositionInverse") < only(before, setPosition));
        only(calls(playerMixin + "LocalPlayerMixin", "sable$postSendPosition"), setPosition);
        var server = calls(playerMixin + "ServerboundMovePlayerPacketMixin", "sable$handle");
        assertTrue(only(server, sable + "ActiveSableCompanion.getContaining")
                < only(server, sable + "companion/math/Pose3d.transformPosition"));
    }

    @Test void positionHooksRequireTheTestedPairAndDeclineMissingOrDifferentVersions() throws Exception {
        Class<?> isolated = MixinDiscoveryFixture.pluginWithFixtureMetadata();
        var plugin = (IMixinConfigPlugin) isolated.getConstructor().newInstance();
        var versions = isolated.getField("fixtureVersions");
        var hooks = List.of("SableElevatorPositionMixin", "SableElevatorPostTickMixin");
        try (var stream = getClass().getResourceAsStream("/emfattachments.mixins.json")) {
            assertNotNull(stream);
            var config = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            for (String hook : hooks) {
                assertTrue(config.getAsJsonArray("client").asList().stream().anyMatch(entry -> entry.getAsString().equals(hook)));
                assertFalse(config.has("mixins") && config.getAsJsonArray("mixins").asList().stream()
                        .anyMatch(entry -> entry.getAsString().equals(hook)));
            }
        }
        for (String sable : List.of("", "2.0.5", "2.0.6", "2.0.7")) {
            for (String create : List.of("", "6.0.9", "6.0.10", "6.0.11")) {
                var installed = new HashMap<String, DefaultArtifactVersion>();
                if (!sable.isEmpty()) installed.put("sable", new DefaultArtifactVersion(sable));
                if (!create.isEmpty()) installed.put("create", new DefaultArtifactVersion(create));
                versions.set(null, installed);
                for (String mixin : hooks) {
                    boolean expected = "2.0.6".equals(sable) && "6.0.10".equals(create);
                    assertEquals(expected, plugin.shouldApplyMixin("unused", "dev.caleb.emfattachments.mixin." + mixin),
                            mixin + " with Sable " + sable + " and Create " + create);
                }
            }
        }
    }

    private static int only(List<String> calls, String target) {
        int first = calls.indexOf(target);
        assertTrue(first >= 0, target);
        assertEquals(first, calls.lastIndexOf(target), target);
        return first;
    }

    private static List<String> calls(String owner, String method) throws Exception {
        var node = readClass(owner);
        var found = node.methods.stream().filter(m -> m.name.equals(method)).findFirst().orElseThrow();
        List<String> result = new ArrayList<>();
        for (var instruction : found.instructions) {
            if (instruction instanceof MethodInsnNode call) result.add(call.owner + "." + call.name);
        }
        return result;
    }

    private static ClassNode readClass(String owner) throws Exception {
        try (InputStream bytes = SableElevatorPacketOrderTest.class.getResourceAsStream("/" + owner + ".class")) {
            assertNotNull(bytes, owner);
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG);
            return node;
        }
    }
}
