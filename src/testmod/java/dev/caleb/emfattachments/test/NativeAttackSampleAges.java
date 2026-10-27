package dev.caleb.emfattachments.test;

/** Test-fixture scheduling only. Never changes native animation speed or clocks. */
public final class NativeAttackSampleAges {
    public static final double REFERENCE_DURATION = 100.0 / 7.0;
    private static final int[] REFERENCE_AGES = {3,5,7,9,11};
    private NativeAttackSampleAges() {}

    public static int[] matrix(float actualDuration) {
        if (!Float.isFinite(actualDuration) || actualDuration <= 0 || actualDuration >= Integer.MAX_VALUE - 2)
            throw new IllegalArgumentException("INVALID_NATIVE_DURATION: " + actualDuration);
        // Tick zero is action start; every accepted sample must be positive and
        // strictly earlier than the actual native duration, with five distinct ticks.
        int lastAvailable = (int)Math.ceil(actualDuration) - 1;
        if (lastAvailable < REFERENCE_AGES.length)
            throw new IllegalArgumentException("TOO_SHORT_NATIVE_WINDOW: duration=" + actualDuration
                    + ", distinctPositiveTicksBeforeEnd=" + lastAvailable + ", required=5");
        int[] targets = new int[REFERENCE_AGES.length];
        int previous = 0;
        for (int i=0;i<targets.length;i++) {
            int rounded = (int)Math.round(REFERENCE_AGES[i] * actualDuration / REFERENCE_DURATION);
            int latest = lastAvailable - (targets.length - 1 - i);
            targets[i] = Math.max(previous + 1, Math.min(rounded, latest));
            previous = targets[i];
        }
        return targets;
    }
}
