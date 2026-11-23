package dev.caleb.emfattachments.client;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import dev.ryanhcode.sable.sublevel.SubLevel;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Checks a rider's actual foot rectangle when a ray through its center misses the cabin floor. */
final class SableElevatorFootprint {
    private SableElevatorFootprint() {}

    static double height(AbstractContraptionEntity cabin, SubLevel carrier, LocalPlayer rider, Vec3 projectedFeet,
                         double maxRise, double maxGap) {
        var body = rider.getBoundingBox().move(projectedFeet.subtract(rider.position()));
        var sweep = new AABB(body.minX, projectedFeet.y - maxGap, body.minZ,
                body.maxX, projectedFeet.y + maxRise, body.maxZ);
        UnaryOperator<Vec3> toLocal = p -> cabin.toLocalVector(carrier == null ? p : carrier.logicalPose().transformPositionInverse(p), 1);
        UnaryOperator<Vec3> toWorld = p -> {
            Vec3 plot = cabin.toGlobalVector(p, 1);
            return carrier == null ? plot : carrier.logicalPose().transformPosition(plot);
        };
        var center = toLocal.apply(projectedFeet.add(0, maxRise, 0));
        var search = new AABB(center, center);
        for (double x : new double[]{sweep.minX, sweep.maxX}) {
            for (double y : new double[]{sweep.minY, sweep.maxY}) {
                for (double z : new double[]{sweep.minZ, sweep.maxZ}) {
                    var point = toLocal.apply(new Vec3(x, y, z));
                    search = search.minmax(new AABB(point, point));
                }
            }
        }
        // A block's collision can extend outside its owning cell (for example, a fence).
        var low = BlockPos.containing(search.minX - 1, search.minY - 1, search.minZ - 1);
        var high = BlockPos.containing(search.maxX + 1, search.maxY + 1, search.maxZ + 1);
        var blocks = cabin.getContraption().getBlocks();
        double cells = (1d + high.getX() - low.getX()) * (1d + high.getY() - low.getY()) * (1d + high.getZ() - low.getZ());
        Iterable<BlockPos> positions = cells <= blocks.size() ? BlockPos.betweenClosed(low, high) : blocks.keySet();
        var world = cabin.getContraption().getContraptionWorld();
        var context = context(rider, center.y);
        var paddedSearch = search.inflate(1e-7);
        double highest = Double.NEGATIVE_INFINITY;
        for (var pos : positions) {
            if (pos.getX() < low.getX() || pos.getX() > high.getX() || pos.getY() < low.getY() || pos.getY() > high.getY()
                    || pos.getZ() < low.getZ() || pos.getZ() > high.getZ()) continue;
            var block = blocks.get(pos);
            if (block == null) continue;
            var shape = block.state().getCollisionShape(world, pos, context);
            if (shape.isEmpty() || !shape.bounds().move(pos).intersects(paddedSearch)) continue;
            var above = pos.above();
            var cover = blocks.get(above);
            var aboveShape = cover == null ? Shapes.empty() : cover.state().getCollisionShape(world, above, context);
            double candidate = topHeight(sweep, pos, shape, aboveShape, toWorld);
            if (candidate > highest) highest = candidate;
        }
        return highest == Double.NEGATIVE_INFINITY ? Double.NaN : highest;
    }

    static CollisionContext context(LocalPlayer rider, double localStartY) {
        return new EntityCollisionContext(rider.isDescending(), localStartY,
                rider.getMainHandItem(), rider::canStandOnFluid, rider) {};
    }

    static double topHeight(AABB sweep, BlockPos pos, VoxelShape shape, VoxelShape above, UnaryOperator<Vec3> toWorld) {
        if (shape.isEmpty()) return Double.NaN;
        List<AABB> boxes = shape.toAabbs();
        if (boxes.size() > 1 || !above.isEmpty()) {
            // The thin upper layer excludes buried horizontal faces within a shape and across block boundaries.
            var top = Shapes.joinUnoptimized(shape, shape.move(0, -1e-5, 0), BooleanOp.ONLY_FIRST);
            if (!above.isEmpty()) top = Shapes.joinUnoptimized(top, above.move(0, 1 - 1e-5, 0), BooleanOp.ONLY_FIRST);
            boxes = top.toAabbs();
        }
        double highest = Double.NEGATIVE_INFINITY;
        for (AABB box : boxes) {
            var b = box.move(pos);
            List<Vec3> face = List.of(
                    toWorld.apply(new Vec3(b.minX, b.maxY, b.minZ)), toWorld.apply(new Vec3(b.minX, b.maxY, b.maxZ)),
                    toWorld.apply(new Vec3(b.maxX, b.maxY, b.maxZ)), toWorld.apply(new Vec3(b.maxX, b.maxY, b.minZ)));
            face = clip(face, Axis.X, sweep.minX, true);
            face = clip(face, Axis.X, sweep.maxX, false);
            face = clip(face, Axis.Z, sweep.minZ, true);
            face = clip(face, Axis.Z, sweep.maxZ, false);
            if (face.size() < 3) continue;
            // Signed projected area rejects vertical/overturned faces and zero-area edge touches.
            Vec3 start = face.getFirst();
            double area = 0;
            double y = start.y;
            for (int i = 1; i < face.size(); i++) {
                Vec3 a = face.get(i - 1), bPoint = face.get(i);
                area += (a.z - start.z) * (bPoint.x - start.x) - (a.x - start.x) * (bPoint.z - start.z);
                y = Math.max(y, bPoint.y);
            }
            if (area > 1e-12 && y >= sweep.minY && y <= sweep.maxY) highest = Math.max(highest, y);
        }
        return highest == Double.NEGATIVE_INFINITY ? Double.NaN : highest;
    }

    // Clip the world-space face polygon. Its interpolated vertices retain the actual Y on a tilted cabin.
    private static List<Vec3> clip(List<Vec3> polygon, Axis axis, double boundary, boolean minimum) {
        if (polygon.isEmpty()) return polygon;
        var clipped = new ArrayList<Vec3>(polygon.size() + 1);
        Vec3 previous = polygon.getLast();
        double previousDistance = (previous.get(axis) - boundary) * (minimum ? 1 : -1);
        for (Vec3 current : polygon) {
            double distance = (current.get(axis) - boundary) * (minimum ? 1 : -1);
            if ((distance >= 0) != (previousDistance >= 0)) {
                clipped.add(previous.lerp(current, previousDistance / (previousDistance - distance)));
            }
            if (distance >= 0) clipped.add(current);
            previous = current;
            previousDistance = distance;
        }
        return clipped;
    }
}
