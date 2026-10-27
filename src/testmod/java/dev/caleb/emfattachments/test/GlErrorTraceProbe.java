package dev.caleb.emfattachments.test;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.GlDebug;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;

/** Explicit, bounded observation of the existing native GL callback. Never changes GL state. */
public final class GlErrorTraceProbe {
    private static final UUID OFFLINE_ID = UUID.nameUUIDFromBytes("OfflinePlayer:AttachmentTest".getBytes(StandardCharsets.UTF_8));
    private static final StackWalker WALKER = StackWalker.getInstance();
    private static volatile Session active;
    private static int pollTicks;

    private GlErrorTraceProbe() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(GlErrorTraceProbe::tick);
    }

    private static boolean disposable(Minecraft mc) {
        return mc.player != null && mc.level != null && mc.hasSingleplayerServer()
                && !mc.getSingleplayerServer().isPublished()
                && mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of(".local", "client"))
                && mc.getUser().getType() == User.Type.LEGACY
                && "AttachmentTest".equals(mc.getUser().getName()) && OFFLINE_ID.equals(mc.getUser().getProfileId())
                && "AttachmentTest".equals(mc.player.getGameProfile().getName()) && OFFLINE_ID.equals(mc.player.getUUID());
    }

    private static void tick(ClientTickEvent.Post ignored) {
        Minecraft mc = Minecraft.getInstance();
        Session session = active;
        if (session != null) {
            if (session.observerFailure != null) {
                active = null;
                session.finish("FAIL");
            } else if (!session.sameWorld(mc) || !disposable(mc)) {
                active = null;
                session.finish("SCOPE_ENDED");
            } else if (System.nanoTime() >= session.deadline) {
                active = null;
                session.finish("CAPTURED");
            } else if (++pollTicks % 20 == 0) {
                session.write("RECORDING", false);
            }
            return;
        }
        // No filesystem polling on each rendered frame and no callback installation.
        if (++pollTicks % 20 != 0 || !disposable(mc) || mc.screen != null) return;
        Path trigger = mc.gameDirectory.toPath().resolve("emfattachments-gl-trace.txt");
        if (!Files.isRegularFile(trigger)) return;
        try {
            List<String> lines = Files.readAllLines(trigger);
            String label = (lines.isEmpty() ? "gl-trace" : lines.getFirst().trim()).replaceAll("[^a-zA-Z0-9._-]", "_");
            if (label.isBlank() || label.equals(".") || label.equals("..")) label = "gl-trace";
            int seconds = 60;
            for (String line : lines) if (line.trim().startsWith("seconds=")) seconds = Integer.parseInt(line.trim().substring(8));
            seconds = Math.max(5, Math.min(120, seconds));
            Session next = new Session(mc, label, seconds);
            Files.delete(trigger);
            active = next;
            next.write("RECORDING", false);
        } catch (Exception failure) {
            // Leave the trigger available for inspection. One bounded failure report, no native log flood.
            try {
                Path out = mc.gameDirectory.toPath().resolve("emfattachments-tests/gl-trace-startup-error.json");
                Files.createDirectories(out.getParent());
                Files.writeString(out, new GsonBuilder().setPrettyPrinting().create().toJson(Map.of(
                        "status", "FAIL", "finished", true, "detail", failure.toString(), "observedAt", Instant.now().toString())));
            } catch (Exception ignoredWriteFailure) {}
        }
    }

    /** Called after Minecraft has already decoded the message; returns no replacement state. */
    public static void observe(int source, int type, int id, int severity, String message) {
        Session session = active;
        if (session == null || type != 33356 || id != 1282 || System.nanoTime() >= session.deadline) return;
        if (!session.sameWorld(Minecraft.getInstance())) return;
        boolean array = message.contains("Array object is not active");
        boolean depth = message.contains("Depth formats do not match");
        if (!array && !depth) return;
        try { session.observe(source, type, id, severity, message, array); }
        catch (Throwable failure) { session.observerFailure = failure.toString(); }
    }

    private static final class Session {
        final Minecraft mc;
        final ClientLevel level;
        final LocalPlayer player;
        final IntegratedServer server;
        final Path output;
        final long started = System.nanoTime();
        final long deadline;
        final Map<String, Object> metadata = new LinkedHashMap<>();
        final List<Sample> samples = new ArrayList<>(4);
        long arrayCount, depthCount, unrecordedDistinctCallbacks;
        boolean closed;
        String writeFailure;
        volatile String observerFailure;

        Session(Minecraft mc, String label, int seconds) throws Exception {
            this.mc = mc;
            level = mc.level;
            player = mc.player;
            server = mc.getSingleplayerServer();
            deadline = started + seconds * 1_000_000_000L;
            output = mc.gameDirectory.toPath().resolve("emfattachments-tests").resolve(label).resolve("gl-errors.json");
            Files.createDirectories(output.getParent());
            boolean debugSupported = GL.getCapabilities().GL_KHR_debug || GL.getCapabilities().GL_ARB_debug_output;
            // Query only: the native Minecraft constructor requests asynchronous output.
            boolean synchronous = debugSupported && GL11.glIsEnabled(33346);
            metadata.put("label", label);
            metadata.put("startedAt", Instant.now().toString());
            metadata.put("requestedSeconds", seconds);
            metadata.put("runtime", mc.gameDirectory.toPath().toAbsolutePath().normalize().toString());
            metadata.put("offlineProfileId", player.getUUID().toString());
            metadata.put("firstPersonInstalled", ModList.get().isLoaded("firstperson"));
            metadata.put("compatInstalled", ModList.get().isLoaded("emfattachments"));
            metadata.put("nativeDebugEnabled", GlDebug.isDebugEnabled());
            metadata.put("nativeDebugVerbosity", mc.options.glDebugVerbosity);
            metadata.put("debugExtensionSupported", debugSupported);
            metadata.put("synchronousCallbackEnabledAtStart", synchronous);
            metadata.put("armingThread", Thread.currentThread().getName());
            metadata.put("maxDistinctStacks", 4);
            metadata.put("limitations", List.of(
                    "Observation only: no GL state, callback, error filtering, rendering, gameplay, or inventory changes.",
                    "A non-synchronous callback can be delivered at a later GL call; its stack is not by itself the originating operation.",
                    "At most four distinct matching messages receive a stack; subsequent identical messages only increment counters.",
                    "No error observed is not proof of absence. This is a reproduction trace, not an FPS benchmark."));
        }

        boolean sameWorld(Minecraft current) {
            return current == mc && current.level == level && current.player == player && current.getSingleplayerServer() == server;
        }

        synchronized void observe(int source, int type, int id, int severity, String message, boolean array) {
            if (closed) return;
            if (array) arrayCount++; else depthCount++;
            for (Sample sample : samples) {
                if (sample.source == source && sample.type == type && sample.id == id && sample.severity == severity && sample.message.equals(message)) {
                    sample.count++;
                    sample.lastElapsedNanos = System.nanoTime() - started;
                    return;
                }
            }
            if (samples.size() == 4) { unrecordedDistinctCallbacks++; return; }
            // Runs only for the first matching distinct message, never once per frame/error.
            samples.add(new Sample(source, type, id, severity, message, System.nanoTime() - started,
                    Thread.currentThread().getName(), WALKER.walk(frames -> frames.limit(96).map(StackWalker.StackFrame::toString).toList())));
        }

        synchronized void finish(String status) {
            closed = true;
            write(status, true);
        }

        synchronized void write(String status, boolean finished) {
            Map<String, Object> result = new LinkedHashMap<>(metadata);
            result.put("status", status);
            result.put("finished", finished);
            result.put("elapsedSeconds", (System.nanoTime() - started) / 1_000_000_000.0);
            result.put("inactiveArrayObjectCount", arrayCount);
            result.put("depthFormatMismatchCount", depthCount);
            result.put("callbacksBeyondDistinctStackLimit", unrecordedDistinctCallbacks);
            result.put("samples", samples);
            result.put("outcome", arrayCount + depthCount > 0 ? "MATCHING_ERROR_OBSERVED" : "NO_MATCHING_ERROR_OBSERVED");
            if (writeFailure != null) result.put("previousWriteFailure", writeFailure);
            if (observerFailure != null) result.put("observerFailure", observerFailure);
            try { Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(result)); }
            catch (Exception failure) { writeFailure = failure.toString(); }
        }
    }

    private static final class Sample {
        final int source, type, id, severity;
        final String message, callbackThread;
        final long firstElapsedNanos;
        final List<String> stack;
        long count = 1, lastElapsedNanos;
        Sample(int source, int type, int id, int severity, String message, long elapsed, String thread, List<String> stack) {
            this.source = source; this.type = type; this.id = id; this.severity = severity;
            this.message = message; this.callbackThread = thread; this.firstElapsedNanos = elapsed; this.lastElapsedNanos = elapsed; this.stack = stack;
        }
    }
}
