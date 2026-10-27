package dev.caleb.emfattachments.test;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.SectionPos;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Test-only readiness witness. Never changes rendering, suppresses assertions, or advances time. */
public final class NativeWorldReadiness {
    private static final int REQUIRED_FRAMES = 5;
    private static boolean registered, frameOpen;
    private static long frameSerial, completedAtFrame = -1;
    private static Context completedContext;
    private static int consecutive;
    private static Invocation current, returned;
    private static String reason = "No completed native world frames";

    private NativeWorldReadiness() {}

    public static void register() {
        if (registered) return;
        registered = true;
        NeoForge.EVENT_BUS.addListener(NativeWorldReadiness::pre);
        NeoForge.EVENT_BUS.addListener(NativeWorldReadiness::stage);
        NeoForge.EVENT_BUS.addListener(NativeWorldReadiness::post);
    }

    private static void pre(RenderFrameEvent.Pre event) {
        frameSerial++;
        frameOpen = true;
        current = null;
        returned = null;
    }

    /** Called exactly around GameRenderer's invocation of LevelRenderer.renderLevel. */
    public static Invocation enter(Camera camera) {
        Invocation token = new Invocation(current, frameOpen ? context(camera) : null, frameSerial);
        current = token;
        return token;
    }

    private static void stage(RenderLevelStageEvent event) {
        if (!frameOpen) return;
        if (current != null && current.context != null && event.getCamera() == current.context.camera
                && event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            current.nativeEntityStage = true;
        }
        if (returned != null && returned.context != null && event.getCamera() == returned.context.camera
                && event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            returned.nativeAfterLevel = true;
        }
    }

    private static void post(RenderFrameEvent.Post event) {
        Invocation token = returned;
        frameOpen = false;
        current = null;
        if (token == null || !token.normalReturn || !token.nativeEntityStage || !token.nativeAfterLevel
                || token.frame != frameSerial || !matchesCurrent(token.context)) {
            reset("No complete native world invocation/entity stage/after-level stage in this frame");
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!mc.level.isOutsideBuildHeight(mc.player.blockPosition().getY())
                && !mc.levelRenderer.isSectionCompiled(mc.player.blockPosition())) {
            reset("Native player section is not compiled");
            return;
        }
        consecutive = token.context.equals(completedContext) ? consecutive + 1 : 1;
        completedContext = token.context;
        completedAtFrame = frameSerial;
        reason = consecutive >= REQUIRED_FRAMES ? "Ready" : "Awaiting consecutive completed native frames";
    }

    /** Use only before consuming an idle trigger or starting a prepared action, never to hide active failures. */
    public static boolean ready() {
        // Client ticks can run after RenderFrame.Pre and before this frame's world draw.
        // The immediately preceding completed frame remains valid; a missing draw resets at Post.
        return consecutive >= REQUIRED_FRAMES
                && (completedAtFrame == frameSerial || (frameOpen && completedAtFrame == frameSerial - 1))
                && matchesCurrent(completedContext);
    }

    public static Map<String,Object> evidence() {
        var value = new LinkedHashMap<String,Object>();
        value.put("ready", ready());
        value.put("requiredCompletedWorldFrames", REQUIRED_FRAMES);
        value.put("consecutiveCompletedWorldFrames", consecutive);
        value.put("frameSerial", frameSerial);
        value.put("lastCompletedFrame", completedAtFrame);
        value.put("reason", reason);
        value.put("entryReturnAndNativeStagesRequired", true);
        value.put("nativePlayerSectionCompiledRequired", true);
        if (completedContext != null) {
            value.put("player", completedContext.player.getUUID().toString());
            value.put("dimension", completedContext.level.dimension().location().toString());
            value.put("cameraType", completedContext.cameraType.name());
            value.put("section", completedContext.section);
        }
        return value;
    }

    private static Context context(Camera camera) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.screen != null || mc.getOverlay() != null
                || camera != mc.gameRenderer.getMainCamera() || camera.getEntity() != mc.player
                || mc.getCameraEntity() != mc.player || !mc.player.isAlive()) return null;
        return new Context(mc.level, mc.player, camera, mc.options.getCameraType(),
                SectionPos.asLong(mc.player.blockPosition()));
    }

    private static boolean matchesCurrent(Context value) {
        return value != null && value.equals(context(value.camera));
    }

    private static void reset(String why) {
        consecutive = 0;
        completedContext = null;
        completedAtFrame = -1;
        reason = why;
    }

    private record Context(ClientLevel level, LocalPlayer player, Camera camera, CameraType cameraType, long section) {}

    public static final class Invocation {
        private final Invocation parent;
        private final Context context;
        private final long frame;
        private boolean nativeEntityStage, nativeAfterLevel, normalReturn;

        private Invocation(Invocation parent, Context context, long frame) {
            this.parent = parent; this.context = context; this.frame = frame;
        }

        public void returnedNormally() { normalReturn = true; }

        public void close() {
            if (current == this) current = parent;
            if (parent == null && normalReturn) returned = this;
            else if (!normalReturn) reset("Native world renderer did not return normally");
        }
    }
}
