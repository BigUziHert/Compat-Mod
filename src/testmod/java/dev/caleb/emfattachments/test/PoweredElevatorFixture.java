package dev.caleb.emfattachments.test;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.elevator.ElevatorColumn;
import com.simibubi.create.content.contraptions.elevator.ElevatorContraption;
import com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlockEntity;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/** Server-owned Create elevator whose vertical travel is driven by its Creative Motor and floor contacts. */
final class PoweredElevatorFixture implements AutoCloseable {
    static final Vec3 CABIN_FEET = new Vec3(-3.5, 1, -0.5);
    static final double SPEED_PER_SECOND = 1000.0 / 60;
    static final int TOP_FLOOR = 18;
    static final int BOTTOM_FLOOR = 2;
    private static final int MOTOR_RPM = 128;
    private static final int PULLEY_Y = 22;
    private static final int CABIN_MIN_X = -8;
    private static final int CABIN_RADIUS_Z = 4;

    final ServerLevel level;
    final ServerSubLevel platform;
    final BlockPos origin;
    private final BlockPos worldBase;
    private final Map<BlockPos, BlockState> replacedBlocks = new LinkedHashMap<>();
    private final PhysicsPipeline pipeline;
    private final Vector3d linear = new Vector3d();
    private final Vector3d angular = new Vector3d();
    private SuperGlueEntity glue;
    private ElevatorPulleyBlockEntity pulley;
    private boolean assembleRequested;
    private double holdY;

    PoweredElevatorFixture(ServerLevel level, BlockPos worldBase, boolean sable) {
        this.level = level;
        this.worldBase = worldBase;
        var container = SubLevelContainer.getContainer(level);
        if (sable && container == null) throw new IllegalStateException("Server Sable container is missing");
        if (!sable) {
            platform = null;
            pipeline = null;
            origin = worldBase;
            return;
        }
        Pose3d pose = new Pose3d();
        pose.position().set(worldBase.getX(), worldBase.getY(), worldBase.getZ());
        platform = (ServerSubLevel) container.allocateNewSubLevel(pose);
        platform.setName("Disposable powered elevator fixture");
        var plot = platform.getPlot();
        origin = plot.getCenterBlock();
        ChunkPos center = plot.getCenterChunk();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) plot.newEmptyChunk(new ChunkPos(center.x + x, center.z + z));
        }
        pipeline = container.physicsSystem().getPipeline();
    }

    void build() {
        if (platform == null) {
            // Create disassembles the cabin at its current floor, which may differ from its starting floor.
            for (int x = CABIN_MIN_X; x <= 1; x++) {
                for (int y = BOTTOM_FLOOR; y <= TOP_FLOOR + 1; y++) {
                    for (int z = -CABIN_RADIUS_Z; z <= CABIN_RADIUS_Z; z++) rememberAir(origin.offset(x, y, z));
                }
            }
        }
        for (int x = -3; x <= 4; x++) {
            for (int z = -2; z <= 2; z++) place(x, 0, z, Blocks.OAK_PLANKS.defaultBlockState());
        }
        for (int y = 1; y <= PULLEY_Y + 1; y++) place(3, y, -1, Blocks.QUARTZ_BLOCK.defaultBlockState());
        for (int x = 0; x <= 2; x++) place(x, PULLEY_Y + 1, 0, Blocks.QUARTZ_BLOCK.defaultBlockState());
        place(3, PULLEY_Y + 1, 0, Blocks.QUARTZ_BLOCK.defaultBlockState());
        for (int x = CABIN_MIN_X; x <= 0; x++) {
            for (int z = -CABIN_RADIUS_Z; z <= CABIN_RADIUS_Z; z++) {
                place(x, BOTTOM_FLOOR, z, Blocks.SMOOTH_STONE.defaultBlockState());
            }
        }
        if (platform != null) {
            // Join the stationary landing to the carrier below the glue volume. Sable can split off
            // this landing when Create removes the adjacent cabin from the plot during assembly.
            for (int x = -12; x <= -4; x++) place(x, 0, -1, Blocks.OAK_PLANKS.defaultBlockState());
            place(-12, 1, -1, Blocks.OAK_PLANKS.defaultBlockState());
            place(-9, 1, -1, Blocks.OAK_PLANKS.defaultBlockState());
            for (int x = -12; x < CABIN_MIN_X; x++) {
                for (int z = -2; z <= 1; z++) place(x, BOTTOM_FLOOR, z, Blocks.OAK_PLANKS.defaultBlockState());
            }
        }
        var contact = createBlock("redstone_contact");
        place(1, BOTTOM_FLOOR, 0, contact.setValue(BlockStateProperties.FACING, Direction.EAST));
        for (int y : new int[]{BOTTOM_FLOOR, 10, TOP_FLOOR}) {
            place(2, y, 0, contact.setValue(BlockStateProperties.FACING, Direction.WEST));
        }
        place(-4, BOTTOM_FLOOR + 1, 1, createBlock("contraption_controls")
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
        place(0, PULLEY_Y, 0, createBlock("elevator_pulley")
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
        place(1, PULLEY_Y, 0, createBlock("creative_motor").setValue(BlockStateProperties.FACING, Direction.WEST));
        glue = new SuperGlueEntity(level, SuperGlueEntity.span(
                origin.offset(CABIN_MIN_X, BOTTOM_FLOOR, -CABIN_RADIUS_Z),
                origin.offset(1, BOTTOM_FLOOR + 1, CABIN_RADIUS_Z)));
        if (!level.addFreshEntity(glue)) throw new IllegalStateException("Cabin glue could not be spawned");
        if (!(level.getBlockEntity(origin.offset(0, PULLEY_Y, 0)) instanceof ElevatorPulleyBlockEntity nativePulley)
                || !(level.getBlockEntity(origin.offset(1, PULLEY_Y, 0)) instanceof CreativeMotorBlockEntity motor)) {
            throw new IllegalStateException("Native elevator pulley or creative motor is missing");
        }
        pulley = nativePulley;
        motor.generatedSpeed.setValue(MOTOR_RPM);
        if (platform != null) {
            if (platform.getMassTracker().getMass() <= 0) throw new IllegalStateException("Physics platform has no mass");
            platform.logicalPose().position().set(worldBase.getX(), worldBase.getY(), worldBase.getZ());
            platform.updateLastPose();
            pipeline.teleport(platform, platform.logicalPose().position(), new Quaterniond());
            holdY = platformReferenceY();
        }
    }

    boolean assembleWhenPowered() {
        if (!assembleRequested && Math.abs(pulley.getSpeed()) == MOTOR_RPM) {
            pulley.clicked();
            assembleRequested = true;
        }
        return cabin() != null && floors().size() == 3;
    }

    AbstractContraptionEntity cabin() {
        return pulley == null ? null : pulley.getAttachedContraption();
    }

    java.util.Collection<BlockPos> floors() {
        var column = column();
        return column == null ? java.util.List.of() : column.getContacts();
    }

    int target() {
        var column = column();
        return column == null ? Integer.MIN_VALUE : column.getTargetedYLevel() - origin.getY();
    }

    private ElevatorColumn column() {
        var cabin = cabin();
        return cabin != null && cabin.getContraption() instanceof ElevatorContraption elevator
                ? ElevatorColumn.get(level, elevator.getGlobalColumn()) : null;
    }

    void callFloor(int y) {
        BlockPos floor = origin.offset(2, y, 0);
        if (!level.getBlockState(floor).is(createBlock("elevator_contact").getBlock())) {
            throw new IllegalStateException("Contact was not converted to a native elevator floor at " + floor);
        }
        int otherFloor = y == BOTTOM_FLOOR ? TOP_FLOOR : BOTTOM_FLOOR;
        place(2, otherFloor, 1, Blocks.AIR.defaultBlockState());
        place(2, y, 1, Blocks.REDSTONE_BLOCK.defaultBlockState());
        if (target() != y) throw new IllegalStateException("Floor " + y + " did not accept the native redstone call");
    }

    float rpm() {
        return pulley.getSpeed();
    }

    void drive(double x, double z) {
        if (platform == null) return;
        if (platform.isRemoved()) throw new IllegalStateException("Moving elevator platform unloaded");
        pipeline.getLinearVelocity(platform, linear).negate();
        // Apply a velocity change to the real physics body. The feedback only holds altitude.
        double y = 0.25 + (holdY - platformReferenceY()) * 3;
        linear.add(x, y, z);
        pipeline.getAngularVelocity(platform, angular).negate();
        pipeline.addLinearAndAngularVelocity(platform, linear, angular);
    }

    Vec3 floor() {
        Vec3 feet = cabin().toGlobalVector(CABIN_FEET, 1);
        return platform == null ? feet : platform.logicalPose().transformPosition(feet);
    }

    Vec3 boardingPosition() {
        BlockPos deck = origin.offset(-10, BOTTOM_FLOOR, -1);
        if (!level.getBlockState(deck).is(Blocks.OAK_PLANKS)) {
            throw new IllegalStateException("The stationary landing split from its carrier at " + deck);
        }
        return platform.logicalPose().transformPosition(Vec3.atLowerCornerOf(origin)
                .add(-9.4, BOTTOM_FLOOR + 1.03, CABIN_FEET.z));
    }

    private double platformReferenceY() {
        return platform.logicalPose().transformPosition(Vec3.atLowerCornerOf(origin)).y;
    }

    Vec3 carrierPosition() {
        if (platform == null) return Vec3.atLowerCornerOf(origin);
        var point = platform.logicalPose().position();
        return new Vec3(point.x(), point.y(), point.z());
    }

    private void place(int x, int y, int z, BlockState state) {
        BlockPos pos = origin.offset(x, y, z);
        if (platform == null) rememberAir(pos);
        if (!level.setBlock(pos, state, Block.UPDATE_ALL) && !level.getBlockState(pos).equals(state)) {
            throw new IllegalStateException("Could not place fixture block at " + pos);
        }
    }

    private void rememberAir(BlockPos pos) {
        if (replacedBlocks.containsKey(pos)) return;
        BlockState original = level.getBlockState(pos);
        if (!original.isAir()) throw new IllegalStateException("World fixture site is occupied at " + pos);
        replacedBlocks.put(pos, original);
    }

    private static BlockState createBlock(String name) {
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath("create", name);
        if (!BuiltInRegistries.BLOCK.containsKey(location)) throw new IllegalStateException("Create block missing: " + location);
        return BuiltInRegistries.BLOCK.get(location).defaultBlockState();
    }

    @Override
    public void close() {
        if (pulley != null && cabin() != null) pulley.disassemble();
        if (glue != null) glue.discard();
        if (platform != null && !platform.isRemoved()) {
            SubLevelContainer.getContainer(level).removeSubLevel(platform, SubLevelRemovalReason.REMOVED);
        }
        replacedBlocks.forEach((pos, original) -> level.setBlock(pos, original, Block.UPDATE_ALL));
    }
}
