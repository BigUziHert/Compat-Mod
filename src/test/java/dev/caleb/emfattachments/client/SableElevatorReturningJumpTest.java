package dev.caleb.emfattachments.client;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SableElevatorReturningJumpTest {
    private static SableElevatorRiderCarry.FloorProbe floor(VoxelShape shape) {
        return (feet, rise, gap) -> SableElevatorFootprint.topHeight(
                new AABB(feet.x - .3, feet.y - gap, feet.z - .3,
                        feet.x + .3, feet.y + rise, feet.z + .3),
                BlockPos.ZERO, shape, Shapes.empty(), UnaryOperator.identity());
    }

    @Test void descendingTrailingEdgeReturnsOnlyExistingVerticalPenetration() {
        for (var shape : new VoxelShape[]{Shapes.block(), Shapes.box(0, 0, 0, 1, .5, 1)}) {
            double top = shape.bounds().maxY;
            for (boolean alongX : new boolean[]{true, false}) {
                Vec3 start = alongX ? new Vec3(1.168, top + .121, .5) : new Vec3(.5, top + .121, 1.168);
                Vec3 end = alongX ? new Vec3(1.254, top - .323, .5) : new Vec3(.5, top - .323, 1.254);
                Vec3 lift = SableElevatorRiderCarry.landingCorrection(start, end, floor(shape));
                assertEquals(0, lift.x);
                assertEquals(.323, lift.y, 1e-9);
                assertEquals(0, lift.z);
            }
        }
    }

    @Test void feetMustOverlapAnExposedShapeAtBothCrossingAndEndpoint() {
        var probe = floor(Shapes.block());
        assertSame(Vec3.ZERO, SableElevatorRiderCarry.landingCorrection(
                new Vec3(.5, 1.121, 1.168), new Vec3(.5, .677, 1.301), probe));
        assertSame(Vec3.ZERO, SableElevatorRiderCarry.landingCorrection(
                new Vec3(.5, 1.05, 1.6), new Vec3(.5, .67, 1.2), probe));
        assertSame(Vec3.ZERO, SableElevatorRiderCarry.landingCorrection(
                new Vec3(.5, 1.121, 1.168), new Vec3(.5, .677, 1.254), floor(Shapes.empty())));
    }

    @Test void rejectsApexUpwardMotionUndersideAndDiscontinuousFalls() {
        var probe = floor(Shapes.block());
        for (double[] motion : new double[][]{{1.22,1.15}, {.8,1.1}, {.97,.65}, {2,.9}, {1.1,Double.NaN}}) {
            assertSame(Vec3.ZERO, SableElevatorRiderCarry.landingCorrection(
                    new Vec3(.5,motion[0],.5), new Vec3(.5,motion[1],.5), probe));
        }
    }

    @Test void exactContactTriggersARealCollisionWithZeroMovement() {
        var samples = new AtomicInteger();
        var shape = floor(Shapes.block());
        Vec3 contact = SableElevatorRiderCarry.landingCorrection(
                new Vec3(.5,1.3,1.19), new Vec3(.5,1,1.27), (point, up, down) -> {
                    samples.incrementAndGet();
                    return shape.height(point, up, down);
                });
        assertEquals(Vec3.ZERO, contact);
        assertNotSame(Vec3.ZERO, contact);
        assertEquals(2, samples.get());
    }
}
