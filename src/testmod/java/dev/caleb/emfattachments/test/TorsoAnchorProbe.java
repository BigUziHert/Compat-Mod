package dev.caleb.emfattachments.test;

import org.joml.Matrix4f;

public final class TorsoAnchorProbe {
    private TorsoAnchorProbe() {}

    public static void compare(Matrix4f expected, Matrix4f actual) {
        var result = AttachmentTestMod.current;
        result.torsoAnchorChecks++;
        float[] a = expected.get(new float[16]);
        float[] b = actual.get(new float[16]);
        double maxError = 0;
        for (int i = 0; i < 16; i++) maxError = Math.max(maxError, Math.abs(a[i] - b[i]));
        if (!Double.isFinite(maxError) || maxError > 1.0e-4) result.torsoAnchorFailures++;
        result.torsoAnchorMaxError = Math.max(result.torsoAnchorMaxError, maxError);
    }
}
