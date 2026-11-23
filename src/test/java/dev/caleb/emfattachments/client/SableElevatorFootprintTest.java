package dev.caleb.emfattachments.client;

import java.util.function.UnaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SableElevatorFootprintTest {
    private static double height(AABB sweep, VoxelShape shape, UnaryOperator<Vec3> transform) {
        return SableElevatorFootprint.topHeight(sweep, BlockPos.ZERO, shape, Shapes.empty(), transform);
    }

    @Test void realFootOverlapSupportsTheRiderPastTheCenterOfFullBlocksAndSlabs() {
        for (var shape : new VoxelShape[]{Shapes.block(), Shapes.box(0, 0, 0, 1, .5, 1)}) {
            double y = shape.max(Direction.Axis.Y);
            for (double center : new double[]{1.20, 1.299, 1.30, 1.301}) {
                var sweep = new AABB(center - .3, y - .2, .2, center + .3, y + .03, .8);
                double nativeDown = shape.collide(Direction.Axis.Y,
                        new AABB(sweep.minX, y, sweep.minZ, sweep.maxX, y + 1.8, sweep.maxZ), -.1);
                double found = height(sweep, shape, UnaryOperator.identity());
                if (center < 1.30) {
                    assertEquals(0, nativeDown, 1e-10);
                    assertEquals(y, found);
                } else {
                    assertEquals(-.1, nativeDown, 1e-10);
                    assertTrue(Double.isNaN(found));
                }
            }
        }
    }

    @Test void emptyShapesAndTopFacesOutsideTheVerticalSweepProvideNoFloor() {
        var sweep = new AABB(.2, .4, .2, .8, .8, .8);
        assertTrue(Double.isNaN(height(sweep, Shapes.empty(), UnaryOperator.identity())));
        assertTrue(Double.isNaN(height(sweep, Shapes.block(), UnaryOperator.identity())));
        assertTrue(Double.isNaN(height(sweep, Shapes.box(0, 0, 0, 1, .25, 1), UnaryOperator.identity())));
    }

    @Test void aRiserAndABlockAboveCannotSupplyBuriedFaces() {
        VoxelShape step = Shapes.or(Shapes.box(0, 0, 0, 1, .5, 1), Shapes.box(.5, .5, 0, 1, 1, 1));
        assertTrue(Double.isNaN(height(new AABB(.6,.4,.2,.9,.6,.8), step, UnaryOperator.identity())));
        assertEquals(.5, height(new AABB(.1,.4,.2,.75,.6,.8), step, UnaryOperator.identity()));
        var sweep = new AABB(.2,.9,.2,.8,1.1,.8);
        assertTrue(Double.isNaN(SableElevatorFootprint.topHeight(sweep, BlockPos.ZERO, Shapes.block(),
                Shapes.block(), UnaryOperator.identity())));
    }

    @Test void fortyFiveDegreeTurnRejectsEmptyCornersOfTheWorldAlignedBounds() {
        double c = Math.sqrt(.5);
        UnaryOperator<Vec3> yaw = p -> new Vec3(c * (p.x-p.z), p.y, c * (p.x+p.z));
        assertTrue(Double.isNaN(height(new AABB(.58,.9,.05, .68,1.1,.14), Shapes.block(), yaw)));
        assertEquals(1, height(new AABB(.55,.9,.70, .65,1.1,.80), Shapes.block(), yaw));
    }

    @Test void tiltedSlabReturnsTheHighestPointUnderTheFootprintAndRejectsAnOverturnedFace() {
        var slab = Shapes.box(0, 0, 0, 1, .5, 1);
        double c = Math.sqrt(.75), s = .5;
        UnaryOperator<Vec3> tilt = p -> new Vec3(p.x, c * p.y - s * p.z, s * p.y + c * p.z);
        var foot = new AABB(.9, .1, .55, 1.2, .4, .7);
        assertEquals(.5 * c - s * (.55 - .5 * s) / c, height(foot, slab, tilt), 1e-10);
        assertTrue(Double.isNaN(height(new AABB(.1,-1.1,-.8,.9,.1,-.2), slab, p -> new Vec3(p.x,-p.y,-p.z))));
    }
}
