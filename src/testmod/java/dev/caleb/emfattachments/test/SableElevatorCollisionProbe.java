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
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
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
import net.neoforged.fml.ModList;

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
            Path returnFile = Boolean.getBoolean("emfattachments.elevatorReturnProbe")
                    ? directory.resolve("emfattachments-elevator-return-replay.json") : null;
            List<Sample> results = sample(client.level, returnFile);
            Files.writeString(directory.resolve("emfattachments-sable-elevator-report.json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(results));
            LogUtils.getLogger().info("Sable elevator probe: {} of {} native hit assertions passed",
                    results.stream().filter(Sample::passed).count(), results.size());
            if (Boolean.getBoolean("emfattachments.elevatorReview")) {
                Files.writeString(directory.resolve("emfattachments-elevator-review.json"),
                        new GsonBuilder().setPrettyPrinting().create().toJson(reviewContacts(client.level)));
            }
        } catch (Exception error) {
            LogUtils.getLogger().error("Sable elevator probe failed", error);
        }
    }

    private static List<Sample> sample(ClientLevel level, Path returnFile) throws ReflectiveOperationException, java.io.IOException {
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
            if (returnFile != null) {
                Files.writeString(returnFile, new GsonBuilder().setPrettyPrinting().create().toJson(
                        measuredReturnReplays(elevator, subLevel)));
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

    private static List<Map<String, Object>> measuredReturnReplays(TestContraption cabin, ClientSubLevel carrier)
            throws ReflectiveOperationException {
        var product = Class.forName("dev.caleb.emfattachments.client.SableElevatorRiderCarry");
        var probe = Class.forName(product.getName() + "$FloorProbe");
        Method landing = product.getDeclaredMethod("landingCorrection", Vec3.class, Vec3.class, probe);
        landing.setAccessible(true);
        Method shape = Class.forName("dev.caleb.emfattachments.client.SableElevatorFootprint").getDeclaredMethod(
                "height", AbstractContraptionEntity.class, SubLevel.class, LocalPlayer.class,
                Vec3.class, double.class, double.class);
        shape.setAccessible(true);
        LocalPlayer player = Minecraft.getInstance().player;
        double halfWidth = player.getBoundingBox().getZsize() / 2;
        String[] names = {"measured C5 Z", "0.027 to 0.030 toe overlap", "both clear", "only endpoint overlaps"};
        double[][] margins = {{-0.16788605632818587, -0.2544828689476759}, {-0.270, -0.273},
                {-0.34, -0.38}, {-0.45, -0.27}};
        var rows = new ArrayList<Map<String, Object>>();
        Vec3 edge = carrier.logicalPose().transformPosition(cabin.toGlobalVector(new Vec3(0.5, 1, 0), 1));
        for (int i = 0; i < margins.length; i++) {
            double from = margins[i][0], to = margins[i][1];
            Vec3 start = edge.add(0, 0.12139694053919214, from);
            Vec3 end = edge.add(0, -0.3234290238557307, to);
            var queries = new ArrayList<Map<String, Object>>();
            Object nativeShape = java.lang.reflect.Proxy.newProxyInstance(probe.getClassLoader(), new Class<?>[]{probe},
                    (obj, method, args) -> {
                        if (!method.getName().equals("height")) throw new IllegalStateException(method.toString());
                        Vec3 feet = (Vec3) args[0];
                        double actual = (double) shape.invoke(null, cabin, carrier, player, feet, args[1], args[2]);
                        queries.add(Map.of("relativeFeet", feet.subtract(edge), "toeMargin", feet.z-edge.z+halfWidth,
                                "floorFound", Double.isFinite(actual)));
                        return actual;
                    });
            Vec3 correction = (Vec3) landing.invoke(null, start, end, nativeShape);
            boolean expectedContact = i < 2;
            rows.add(Map.of("scenario", names[i], "start", start.subtract(edge), "end", end.subtract(edge),
                    "endToeMargin", to+halfWidth, "expectedContact", expectedContact, "correction", correction,
                    "passed", Math.abs(correction.y - (expectedContact ? edge.y - end.y : 0)) < EPSILON,
                    "shapeQueries", queries, "nativeCarrierLookup", Sable.HELPER.getContaining(cabin)==carrier));
        }
        return rows;
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

    // Uses detached entities and the same native methods that operate on live riders.
    private static Map<String, Object> reviewContacts(ClientLevel level) throws ReflectiveOperationException {
        var elevator = new TestContraption(level, new ElevatorContraption(), new Vec3(32, 32, 32));
        var rider = new RemotePlayer(level, new GameProfile(UUID.randomUUID(), "ContactProbe"));
        var report = new LinkedHashMap<String, Object>();
        Vec3 surface = elevator.toGlobalVector(new Vec3(0.5, 1, 0.5), 1);
        rider.setPos(surface.add(0, 2, 0));
        rider.getAbilities().flying = true;
        Method remote = ContraptionCollider.class.getDeclaredMethod("saveRemotePlayerFromClipping",
                Player.class, AbstractContraptionEntity.class, Vec3.class);
        remote.setAccessible(true);
        double flightY = rider.getY();
        remote.invoke(null, rider, elevator, new Vec3(0, -0.4, 0));
        report.put("unlockedRemoteCreativeFlight", Map.of("originalY", flightY, "correctedY", rider.getY(),
                "stoneTopY", surface.y, "flying", rider.getAbilities().flying,
                "passed", Math.abs(rider.getY() - flightY) < EPSILON));

        BlockPos torchPos = BlockPos.ZERO.above();
        var torch = Blocks.TORCH.defaultBlockState();
        var cabin = elevator.getContraption();
        cabin.getBlocks().put(torchPos, new StructureBlockInfo(torchPos, torch, null));
        rider.getAbilities().flying = false;
        rider.setPos(surface.add(0, 0.1, 0));
        double before = rider.getY();
        Method save = ContraptionCollider.class.getDeclaredMethod("savePlayerFromClipping",
                Player.class, AbstractContraptionEntity.class, Vec3.class, double.class);
        save.setAccessible(true);
        boolean moved = (boolean) save.invoke(null, rider, elevator, UPWARD_MOTION, before - elevator.getY());
        boolean empty = torch.getCollisionShape(cabin.getContraptionWorld(), torchPos).isEmpty();
        report.put("torchAboveStone", Map.of("torchCollisionEmpty", empty, "initialY", before,
                "stoneTopY", surface.y, "correctedY", rider.getY(), "correctionAccepted", moved,
                "passed", empty && moved && Math.abs(rider.getY() - surface.y) < EPSILON));
        String version = ModList.get().getModContainerById("emfattachments")
                .map(container -> container.getModInfo().getVersion().toString()).orElse("absent");
        report.put("compatVersion", version);
        if (!version.equals("1.2.19") && !version.equals("1.2.20-dev")) return report;
        Method hasFloor = Class.forName("dev.caleb.emfattachments.client.SableElevatorRiderCarry")
                .getDeclaredMethod("hasFloor", AbstractContraptionEntity.class, SubLevel.class,
                        Vec3.class, double.class, double.class);
        hasFloor.setAccessible(true);
        boolean carryHit = (boolean) hasFloor.invoke(null, elevator, null, surface.add(0, 0.6, 0), 0.25, 0.625);
        report.put("stoneBelowTorchCarry", Map.of("floorWitness", carryHit, "passed", carryHit));
        cabin.getBlocks().remove(BlockPos.ZERO);
        boolean torchOnly = (boolean) hasFloor.invoke(null, elevator, null, surface.add(0, 0.6, 0), 0.25, 0.625);
        report.put("torchOnlyCarry", Map.of("floorWitness", torchOnly, "passed", !torchOnly));
        cabin.getBlocks().remove(torchPos);
        cabin.getBlocks().put(BlockPos.ZERO, new StructureBlockInfo(BlockPos.ZERO, Blocks.STONE.defaultBlockState(), null));
        var edges = new ArrayList<Map<String, Object>>();
        Method footprint = null;
        if (version.equals("1.2.20-dev")) {
            footprint = Class.forName("dev.caleb.emfattachments.client.SableElevatorFootprint")
                    .getDeclaredMethod("height", AbstractContraptionEntity.class, SubLevel.class,
                            LocalPlayer.class, Vec3.class, double.class, double.class);
            footprint.setAccessible(true);
        }
        for (double localX : new double[] {0.95, 1.05, 1.29, 1.31}) {
            Vec3 feet = elevator.toGlobalVector(new Vec3(localX, 1, 0.5), 1);
            rider.setPos(feet);
            double width = rider.getBoundingBox().getXsize();
            double overlap = Math.max(0, Math.min(1, localX + width / 2) - Math.max(0, localX - width / 2));
            boolean centerRay = (boolean) hasFloor.invoke(null, elevator, null, feet, 0.03125, 0.75);
            boolean witness = centerRay || footprint != null && Double.isFinite((double) footprint.invoke(
                    null, elevator, null, Minecraft.getInstance().player, feet, 0.03125, 0.75));
            edges.add(Map.of("localX", localX, "riderWidth", width, "lateralOverlapBlocks", overlap,
                    "centerRay", centerRay, "releaseFloorWitness", witness, "passed", witness == (overlap > 0)));
        }
        report.put("stoneEdge", edges);
        return report;
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
