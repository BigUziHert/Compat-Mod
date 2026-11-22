package dev.caleb.emfattachments.test;

import com.caleb.unlockedcamera.client.ClientConfig;
import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import com.mojang.blaze3d.platform.InputConstants;
import dev.ryanhcode.sable.Sable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.Screenshot;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Selects the requested native camera and records its rendered position during a disposable elevator ride. */
final class SableElevatorCameraProbe {
    enum Mode {
        FIRST, REAR, FRONT, LEFT, RIGHT
    }

    private final Mode mode;
    private final Set<String> captured = new HashSet<>();
    private Method turnPlayer;
    private Field accumulatedDX;
    private Field accumulatedDY;
    private CameraType previousType;
    private boolean previousUnlocked;
    private boolean previousSkipRear;
    private boolean previousOffset;
    private int previousSide;
    private boolean started;
    private int lookTick = -1;
    private int lastSelectionTick = Integer.MIN_VALUE;
    private boolean orbitRequested;
    private long previousOrbitFrame;
    private double orbitTime;
    private double frameYawInput;
    private double framePitchInput;

    SableElevatorCameraProbe() {
        this(System.getProperty("emfattachments.elevatorCamera", "first"));
    }

    SableElevatorCameraProbe(String mode) {
        this.mode = Mode.valueOf(mode.toUpperCase(Locale.ROOT));
    }

    void lookAround(int tick) {
        orbitRequested = true;
        lookTick = tick;
    }

    void stopOrbitRequest() {
        orbitRequested = false;
    }

    void applyOrbit(Minecraft client) {
        frameYawInput = 0;
        framePitchInput = 0;
        if (!orbitRequested || !started || client.screen != null || client.player == null) {
            previousOrbitFrame = 0;
            return;
        }
        long now = System.nanoTime();
        long previous = previousOrbitFrame;
        previousOrbitFrame = now;
        if (previous == 0) return;
        double elapsedSeconds = (now - previous) / 1e9;
        double lastTime = orbitTime;
        orbitTime += elapsedSeconds * 20.0;
        try {
            if (turnPlayer == null) {
                accumulatedDX = MouseHandler.class.getDeclaredField("accumulatedDX");
                accumulatedDY = MouseHandler.class.getDeclaredField("accumulatedDY");
                turnPlayer = MouseHandler.class.getDeclaredMethod("turnPlayer", double.class);
                accumulatedDX.setAccessible(true);
                accumulatedDY.setAccessible(true);
                turnPlayer.setAccessible(true);
            }
            double sensitivity = client.options.sensitivity().get() * 0.6 + 0.2;
            double degreesPerPixel = sensitivity * sensitivity * sensitivity * 8 * 0.15;
            frameYawInput = 100 * (sweep(orbitTime, 52) - sweep(lastTime, 52));
            framePitchInput = 28 * (sweep(orbitTime, 34) - sweep(lastTime, 34));
            MouseHandler mouse = client.mouseHandler;
            double oldDX = accumulatedDX.getDouble(mouse);
            double oldDY = accumulatedDY.getDouble(mouse);
            try {
                accumulatedDX.setDouble(mouse, frameYawInput / degreesPerPixel);
                accumulatedDY.setDouble(mouse, framePitchInput / degreesPerPixel);
                turnPlayer.invoke(mouse, elapsedSeconds);
            } finally {
                accumulatedDX.setDouble(mouse, oldDX);
                accumulatedDY.setDouble(mouse, oldDY);
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Could not feed the native mouse look path", failure);
        }
    }

    private static double sweep(double time, int period) {
        return Math.sin(time * Math.PI * 2 / period);
    }

    void begin(Minecraft client) {
        begin(client, false);
    }

    void begin(Minecraft client, boolean waitForBoarding) {
        if (started) throw new IllegalStateException("Camera witness already started");
        try {
            previousType = client.options.getCameraType();
            previousUnlocked = activeField().getBoolean(null);
            previousSkipRear = ClientConfig.DISABLE_VANILLA_THIRD_PERSON.get();
            previousOffset = ClientConfig.ENABLE_SHOULDER_OFFSET.get();
            previousSide = sideField().getInt(null);
            started = true;
            if (waitForBoarding) {
                activeField().setBoolean(null, false);
                setNativeMode(client, CameraType.FIRST_PERSON);
            } else {
                select(client);
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Could not select the archive camera", failure);
        }
    }

    /** Do not reselect during travel because that would hide a real unexpected camera transition. */
    void ensureAtStart(Minecraft client) {
        if (client.player == null || client.player.isPassenger() || matches(client)
                || client.player.tickCount - (long) lastSelectionTick < 10) return;
        try {
            select(client);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Could not select the archive camera after boarding", failure);
        }
    }

    private void select(Minecraft client) throws ReflectiveOperationException {
        if (client.player != null) lastSelectionTick = client.player.tickCount;
        activeField().setBoolean(null, false);
        if (mode == Mode.REAR) ClientConfig.DISABLE_VANILLA_THIRD_PERSON.set(false);
        setNativeMode(client, switch (mode) {
            case FIRST, LEFT, RIGHT -> CameraType.FIRST_PERSON;
            case REAR -> CameraType.THIRD_PERSON_BACK;
            case FRONT -> CameraType.THIRD_PERSON_FRONT;
        });
        if (shoulderRequested()) {
            ClientConfig.DISABLE_VANILLA_THIRD_PERSON.set(true);
            ClientConfig.ENABLE_SHOULDER_OFFSET.set(true);
            sideField().setInt(null, mode == Mode.LEFT ? -1 : 1);
            // Unlocked Camera consumes the native perspective mapping on its next tick.
            press(client.options.keyTogglePerspective);
        }
    }

    boolean matches(Minecraft client) {
        if (client.player == null || client.player.isPassenger()
                || UnlockedCameraClient.isUnlockedCameraActive() != shoulderRequested()) return false;
        CameraType expected = switch (mode) {
            case FIRST -> CameraType.FIRST_PERSON;
            case REAR, LEFT, RIGHT -> CameraType.THIRD_PERSON_BACK;
            case FRONT -> CameraType.THIRD_PERSON_FRONT;
        };
        return client.options.getCameraType() == expected;
    }

    private boolean shoulderRequested() {
        return mode == Mode.LEFT || mode == Mode.RIGHT;
    }

    void resetLook() {
        lookTick = -1;
        orbitTime = 0;
        previousOrbitFrame = 0;
    }

    Map<String, Object> sample(Minecraft client, UUID platformId, Vec3 cabinFloor, String stage,
                               boolean travelingVertically) {
        var camera = client.gameRenderer.getMainCamera();
        var player = client.player;
        if (player == null || camera.getEntity() != player || !camera.isInitialized()) return Map.of();
        var result = new LinkedHashMap<String, Object>();
        Vec3 position = camera.getPosition();
        float partialTick = client.getTimer().getGameTimeDeltaPartialTick(true);
        Vec3 vanillaEye = player.getEyePosition(partialTick);
        Vec3 eye = Sable.HELPER.getEyePositionInterpolated(player, partialTick);
        Vec3 delta = position.subtract(eye);
        double yaw = Math.toRadians(camera.getYRot());
        double lateral = delta.x * Math.cos(yaw) + delta.z * Math.sin(yaw);
        result.put("stage", stage);
        result.put("cameraRequested", mode.name());
        result.put("cameraMode", client.options.getCameraType().name());
        result.put("unlockedCameraActive", UnlockedCameraClient.isUnlockedCameraActive());
        result.put("cameraDetached", camera.isDetached());
        result.put("cameraMatchesRequest", matches(client) && camera.isDetached() == (mode != Mode.FIRST));
        result.put("travelingVertically", travelingVertically);
        result.put("cameraPosition", coordinates(position));
        result.put("riderEye", coordinates(eye));
        result.put("vanillaInterpolatedEye", coordinates(vanillaEye));
        result.put("cameraToEye", delta.length());
        result.put("cameraLateralOffset", lateral);
        result.put("cameraConfiguredShoulderOffset", shoulderOffset());
        result.put("cameraYawPitch", List.of(camera.getYRot(), camera.getXRot()));
        result.put("riderYawPitch", List.of(player.getYRot(), player.getXRot()));
        result.put("cameraPartialTick", partialTick);
        result.put("lookTick", lookTick);
        result.put("frameNanos", System.nanoTime());
        result.put("playerTick", player.tickCount);
        result.put("orbitRequested", orbitRequested);
        result.put("mouseYawInputDegrees", frameYawInput);
        result.put("mousePitchInputDegrees", framePitchInput);
        var vehicle = Sable.HELPER.getVehicleSubLevel(player);
        result.put("riderIsPassenger", player.isPassenger());
        result.put("vehicleOnTestPlatform", vehicle != null && vehicle.getUniqueId().equals(platformId));
        if (cabinFloor != null) result.put("cameraRelativeToCabin", coordinates(position.subtract(cabinFloor)));
        result.put("cameraHit", client.hitResult == null ? "" : client.hitResult.getType().name());
        if (client.hitResult instanceof BlockHitResult hit) {
            result.put("cameraHitBlock", hit.getBlockPos().toShortString());
        }
        return result;
    }

    Map<String, Object> travelEvidence(List<Map<String, Object>> rows, String phase) {
        var traveling = rows.stream().filter(row -> row.get("stage") instanceof String stage
                && stage.endsWith(":" + phase) && Boolean.TRUE.equals(row.get("travelingVertically"))).toList();
        int matching = 0;
        int offsetFrames = 0;
        int lookTicks = 0;
        double minYaw = 0;
        double maxYaw = 0;
        double previousYaw = Double.NaN;
        double unwrappedYaw = 0;
        double minPitch = Double.POSITIVE_INFINITY;
        double maxPitch = Double.NEGATIVE_INFINITY;
        double previousOrbitYaw = Double.NaN;
        double previousOrbitPitch = Double.NaN;
        double maximumOrbitError = 0;
        int orbitPairs = 0;
        int unexpectedOrbitSteps = 0;
        for (var row : traveling) {
            if (!Boolean.TRUE.equals(row.get("cameraMatchesRequest"))) continue;
            matching++;
            double lateral = ((Number) row.get("cameraLateralOffset")).doubleValue();
            if (mode == Mode.LEFT && lateral > 0.25 || mode == Mode.RIGHT && lateral < -0.25) offsetFrames++;
            lookTicks = Math.max(lookTicks, ((Number) row.get("lookTick")).intValue());
            var angles = (List<?>) row.get("cameraYawPitch");
            double yaw = ((Number) angles.getFirst()).doubleValue();
            double pitch = ((Number) angles.getLast()).doubleValue();
            if (Boolean.TRUE.equals(row.get("orbitRequested"))) {
                if (Double.isFinite(previousOrbitYaw)) {
                    double yawInput = ((Number) row.get("mouseYawInputDegrees")).doubleValue();
                    double pitchInput = ((Number) row.get("mousePitchInputDegrees")).doubleValue();
                    double yawError = Math.abs(Math.IEEEremainder(yaw - previousOrbitYaw - yawInput, 360));
                    double pitchError = Math.abs(pitch - previousOrbitPitch - pitchInput);
                    double error = Math.max(yawError, pitchError);
                    maximumOrbitError = Math.max(maximumOrbitError, error);
                    orbitPairs++;
                    if (error > 2) unexpectedOrbitSteps++;
                }
                previousOrbitYaw = yaw;
                previousOrbitPitch = pitch;
            } else {
                previousOrbitYaw = Double.NaN;
            }
            if (Double.isFinite(previousYaw)) unwrappedYaw += Math.IEEEremainder(yaw - previousYaw, 360);
            previousYaw = yaw;
            minYaw = Math.min(minYaw, unwrappedYaw);
            maxYaw = Math.max(maxYaw, unwrappedYaw);
            minPitch = Math.min(minPitch, pitch);
            maxPitch = Math.max(maxPitch, pitch);
        }
        double yawSpan = maxYaw - minYaw;
        double pitchSpan = matching == 0 ? 0 : maxPitch - minPitch;
        boolean passed = traveling.size() >= 16 && matching == traveling.size() && lookTicks >= 10
                && yawSpan >= 30 && pitchSpan >= 8 && (!shoulderRequested() || offsetFrames >= 10)
                && orbitPairs >= 10 && unexpectedOrbitSteps == 0;
        return Map.of("renderedMovingFrames", traveling.size(), "expectedCameraFrames", matching,
                "visibleShoulderOffsetFrames", offsetFrames, "lookTicks", lookTicks,
                "yawSpanDegrees", yawSpan, "pitchSpanDegrees", pitchSpan, "orbitPairs", orbitPairs,
                "largestOrbitErrorDegrees", maximumOrbitError, "unexpectedOrbitSteps", unexpectedOrbitSteps,
                "passed", passed);
    }

    void captureLook(Minecraft client, String scenario, String stage) {
        if (!Boolean.getBoolean("emfattachments.sableElevatorScreenshots") || lookTick < 10
                || !captured.add(stage) || !client.gameRenderer.getMainCamera().isInitialized()) return;
        String filename = (mode.name() + "-" + scenario + "-" + stage).toLowerCase(Locale.ROOT);
        Path output = client.gameDirectory.toPath().resolve("emfattachments-elevator-look");
        try {
            Files.createDirectories(output);
            try (var image = Screenshot.takeScreenshot(client.getMainRenderTarget())) {
                image.writeToFile(output.resolve(filename + ".png"));
            }
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Could not capture the rendered elevator camera", failure);
        }
    }

    void finish(Minecraft client) {
        if (!started) return;
        started = false;
        try {
            ClientConfig.DISABLE_VANILLA_THIRD_PERSON.set(previousSkipRear);
            ClientConfig.ENABLE_SHOULDER_OFFSET.set(previousOffset);
            sideField().setInt(null, previousSide);
            activeField().setBoolean(null, previousUnlocked);
            setNativeMode(client, previousType);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Could not restore the archive camera", failure);
        }
    }

    static void press(KeyMapping mapping) {
        KeyMapping.click(InputConstants.getKey(mapping.saveString()));
    }

    static float targetDistance() {
        try {
            return cameraField("targetDistance").getFloat(null);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Could not read the archive camera zoom", failure);
        }
    }

    private static float shoulderOffset() {
        try {
            return cameraField("shoulderOffset").getFloat(null);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Could not read the archive camera offset", failure);
        }
    }

    private static void setNativeMode(Minecraft client, CameraType cameraType) throws ReflectiveOperationException {
        Method method = UnlockedCameraClient.class.getDeclaredMethod("setCameraType", Minecraft.class, CameraType.class);
        method.setAccessible(true);
        method.invoke(null, client, cameraType);
    }

    private static Field activeField() throws ReflectiveOperationException {
        return cameraField("active");
    }

    private static Field sideField() throws ReflectiveOperationException {
        return cameraField("shoulderSide");
    }

    private static Field cameraField(String name) throws ReflectiveOperationException {
        Field field = UnlockedCameraClient.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static List<Double> coordinates(Vec3 vector) {
        return List.of(vector.x, vector.y, vector.z);
    }
}
