package dev.caleb.emfattachments.test;

import com.google.gson.GsonBuilder;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.ContraptionCollider;
import com.simibubi.create.content.contraptions.ContraptionHandlerClient;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.elevator.ElevatorContraption;
import com.simibubi.create.content.contraptions.pulley.PulleyContraption;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Runs Create's native rider correction with a real Sable pose and a detached test rider. */
final class SableElevatorCollisionProbe {
    private static final Vec3 UPWARD_MOTION = new Vec3(0, 0.4, 0);
    private static final double EPSILON = 1e-6;

    private SableElevatorCollisionProbe() {}

    static void register() {
        NeoForge.EVENT_BUS.addListener(SableElevatorCollisionProbe::tick);
    }

    private static void tick(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        Path directory = client.gameDirectory.toPath().toAbsolutePath().normalize();
        Path trigger = directory.resolve("emfattachments-sable-elevator.txt");
        if (!directory.endsWith(Path.of(".local", "client")) || client.getSingleplayerServer() == null
                || client.getSingleplayerServer().isPublished() || client.player == null || client.level == null
                || client.screen != null || !Files.isRegularFile(trigger)) return;
        try {
            Files.delete(trigger);
            List<Sample> results = sample(client.level);
            Files.writeString(directory.resolve("emfattachments-sable-elevator-report.json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(results));
            LogUtils.getLogger().info("Sable elevator probe: {} of {} native hit assertions passed",
                    results.stream().filter(Sample::passed).count(), results.size());
        } catch (Exception error) {
            LogUtils.getLogger().error("Sable elevator probe failed", error);
        }
    }

    private static List<Sample> sample(ClientLevel level) throws ReflectiveOperationException {
        Method save = ContraptionCollider.class.getDeclaredMethod("savePlayerFromClipping",
                Player.class, AbstractContraptionEntity.class, Vec3.class, double.class);
        save.setAccessible(true);
        var container = SubLevelContainer.getContainer(level);
        if (container == null) throw new IllegalStateException("Missing client Sable container");
        int loadedBefore = container.getLoadedCount();
        var subLevel = (ClientSubLevel) container.allocateNewSubLevel(new Pose3d());
        List<Sample> results;
        try {
            subLevel.updateRenderData();
            BlockPos center = subLevel.getPlot().getCenterBlock();
            Vec3 plotOrigin = Vec3.atLowerCornerOf(center);
            subLevel.logicalPose().rotationPoint().set(plotOrigin.x, plotOrigin.y, plotOrigin.z);
            subLevel.logicalPose().position().set(32, 32, 32);
            var elevator = new TestContraption(level, new ElevatorContraption(), plotOrigin);
            if (Sable.HELPER.getContaining(elevator) != subLevel) {
                throw new IllegalStateException("Native Sable lookup did not find the test elevator's plot");
            }
            Sample subLevelBelow = measure(save, "sable-elevator-world-below-plot", elevator, subLevel, true);
            Sample otherPulley = measure(save, "sable-rope-pulley-control",
                    new TestContraption(level, new PulleyContraption(0), plotOrigin), subLevel, false);
            subLevel.logicalPose().orientation().rotationZ(0.17);
            Sample tilted = measure(save, "sable-elevator-tilted", elevator, subLevel, true);
            subLevel.logicalPose().orientation().identity();
            subLevel.logicalPose().position().set(32, 240, 32);
            Sample subLevelAbove = measure(save, "sable-elevator-world-above-plot", elevator, subLevel, true);
            Sample ordinary = measure(save, "ordinary-elevator-control", new TestContraption(level,
                    new ElevatorContraption(), new Vec3(32, 32, 32)), null, false);
            results = List.of(subLevelBelow, tilted, subLevelAbove, ordinary, otherPulley);
        } finally {
            container.removeSubLevel(subLevel, SubLevelRemovalReason.UNLOADED);
        }
        if (container.getLoadedCount() != loadedBefore) throw new IllegalStateException("Probe plot was not removed");
        return results;
    }

    private static Sample measure(Method save, String name, TestContraption elevator, ClientSubLevel subLevel,
                                  boolean projectHit) throws ReflectiveOperationException {
        Vec3 surface = elevator.toGlobalVector(new Vec3(0.5, 1, 0.5), 1);
        if (subLevel != null) surface = subLevel.logicalPose().transformPosition(surface);
        RemotePlayer rider = new RemotePlayer((ClientLevel) elevator.level(),
                new GameProfile(UUID.randomUUID(), "ElevatorProbe"));
        rider.setPos(surface.x, surface.y + 0.1, surface.z);
        double originalY = rider.getY();
        double rayY = originalY + rider.maxUpStep();
        AABB feet = rider.getBoundingBox().deflate(0.25, 0, 0.25);
        double expectedWorldY = Double.NEGATIVE_INFINITY;
        double rawPlotY = Double.NEGATIVE_INFINITY;
        for (double x : new double[]{feet.minX, feet.maxX}) {
            for (double z : new double[]{feet.minZ, feet.maxZ}) {
                var hit = ContraptionHandlerClient.rayTraceContraption(new Vec3(x, rayY, z),
                        new Vec3(x, rayY - 5, z), elevator);
                if (hit == null) throw new IllegalStateException("Native ray missed the " + name + " floor");
                Vec3 plotHit = elevator.toGlobalVector(hit.getLocation(), 1);
                rawPlotY = Math.max(rawPlotY, plotHit.y);
                Vec3 worldHit = subLevel == null ? plotHit : subLevel.logicalPose().transformPosition(plotHit);
                expectedWorldY = Math.max(expectedWorldY, worldHit.y);
            }
        }
        boolean moved = (boolean) save.invoke(null, rider, elevator, UPWARD_MOTION, originalY - elevator.getY());
        double expectedY = projectHit ? expectedWorldY : rawPlotY;
        boolean passed = moved && Math.abs(rider.getY() - expectedY) < EPSILON;
        return new Sample(name, originalY, rawPlotY, expectedWorldY, rider.getY(), moved, passed);
    }

    private record Sample(String scenario, double originalY, double plotHitY, double worldHitY, double riderY,
                          boolean moved, boolean passed) {}

    private static final class TestContraption extends ControlledContraptionEntity {
        TestContraption(ClientLevel level, Contraption contraption, Vec3 origin) {
            super(BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.fromNamespaceAndPath("create", "stationary_contraption")),
                    level);
            contraption.entity = this;
            contraption.anchor = BlockPos.ZERO;
            contraption.bounds = new AABB(BlockPos.ZERO);
            contraption.getBlocks().put(BlockPos.ZERO,
                    new StructureBlockInfo(BlockPos.ZERO, Blocks.STONE.defaultBlockState(), null));
            setContraption(contraption);
            setPos(origin.x, origin.y, origin.z);
        }
    }
}
