package dev.caleb.emfattachments.test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;

/** Bounded, read-only authoritative projectile evidence; never moves a projectile or forces a hit. */
public final class ProjectileTrajectoryProbe {
    private final Projectile projectile;
    private final Map<String,Object> spawn=new LinkedHashMap<>();
    private final List<Map<String,Object>> samples=new ArrayList<>();
    private Vec3 previous;
    private double minimumDistance=Double.POSITIVE_INFINITY;
    private int observations;
    private boolean crossedTarget;
    public ProjectileTrajectoryProbe(Projectile projectile, ServerPlayer player) {
        this.projectile=projectile; previous=projectile.position();
        spawn.put("type",BuiltInRegistries.ENTITY_TYPE.getKey(projectile.getType()).toString());
        spawn.put("uuid",projectile.getUUID().toString()); spawn.put("gameTime",projectile.level().getGameTime());
        spawn.put("position",vector(previous)); spawn.put("velocity",vector(projectile.getDeltaMovement()));
        spawn.put("owner",projectile.getOwner()==null?"":projectile.getOwner().getUUID().toString());
        spawn.put("authoritativeAim",aim(player));
    }
    public static List<Double> vector(Vec3 vector) { return List.of(vector.x,vector.y,vector.z); }
    public static Map<String,Object> aim(ServerPlayer player) {
        return Map.of("yaw",player.getYRot(),"pitch",player.getXRot(),"position",vector(player.position()),
                "eyePosition",vector(player.getEyePosition()),"scale",player.getScale(),"usingItem",player.isUsingItem(),
                "useElapsed",player.getTicksUsingItem(),"gameTime",player.level().getGameTime());
    }
    public void observe(Mob target) {
        Vec3 at=projectile.position(); observations++;
        if(target!=null) {
            var box=target.getBoundingBox();
            double dx=Math.max(Math.max(box.minX-at.x,0),at.x-box.maxX);
            double dy=Math.max(Math.max(box.minY-at.y,0),at.y-box.maxY);
            double dz=Math.max(Math.max(box.minZ-at.z,0),at.z-box.maxZ);
            minimumDistance=Math.min(minimumDistance,Math.sqrt(dx*dx+dy*dy+dz*dz));
            crossedTarget|=box.contains(at)||box.clip(previous,at).isPresent();
        }
        if(samples.size()<16 && (observations<=8 || observations%10==0 || projectile.isRemoved())) {
            var sample=new LinkedHashMap<String,Object>(); sample.put("observation",observations);
            sample.put("position",vector(at)); sample.put("velocity",vector(projectile.getDeltaMovement()));
            sample.put("removed",projectile.isRemoved()); sample.put("onGround",projectile.onGround());
            if(target!=null) { sample.put("targetPosition",vector(target.position())); sample.put("targetAlive",target.isAlive()); }
            samples.add(sample);
        }
        previous=at;
    }
    public Map<String,Object> snapshot() {
        var result=new LinkedHashMap<String,Object>(spawn);
        result.put("samples",new ArrayList<>(samples)); result.put("totalObservations",observations);
        result.put("minimumSampledTargetDistance",Double.isFinite(minimumDistance)?minimumDistance:-1);
        result.put("sampledSegmentIntersectsExactTargetBox",crossedTarget);
        result.put("finalPosition",vector(projectile.position())); result.put("finalVelocity",vector(projectile.getDeltaMovement()));
        result.put("removed",projectile.isRemoved()); result.put("samplingLimit",16);
        return result;
    }
}
