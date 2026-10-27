package dev.caleb.emfattachments.client;

import dev.kosmx.playerAnim.api.firstPerson.FirstPersonConfiguration;
import dev.kosmx.playerAnim.api.firstPerson.FirstPersonMode;
import dev.kosmx.playerAnim.api.layered.AnimationStack;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.caleb.emfattachments.mixin.AnimationFadeAccessor;
import java.util.AbstractList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeAttackArmVisibilityTest {
    @Test void activeNativeAnimationNeverReadsTheFadeChain() {
        var unreadable = new AbstractList<Object>() {
            @Override public Object get(int index) { throw new AssertionError("Read a fade during a current animation"); }
            @Override public int size() { throw new AssertionError("Inspected fades during a current animation"); }
        };
        assertEquals(new NativeAttackArmVisibility.Visibility(true, false, true, false),
                NativeAttackArmVisibility.resolveModifiers(animation(true, true, false, true, false), unreadable, false, .25f));
    }

    @Test void directModifierScanSkipsExpiredNullAndInactiveSourcesAndUsesNewestLiveFade() {
        var old = new Fade(0, 10, animation(true, false, false, false, true));
        var newest = new Fade(5, 10, animation(true, true, false, true, false));
        var expired = new Fade(10, 10, animation(true, false, true, false, true));
        var inactive = new Fade(0, 10, animation(false, false, false, false, false));
        var missing = new Fade(0, 10, null);
        assertEquals(new NativeAttackArmVisibility.Visibility(false, true, false, true),
                NativeAttackArmVisibility.resolveModifiers(null,
                        List.of(old, newest, new Object(), expired, inactive, missing), true, .5f));
        assertNull(NativeAttackArmVisibility.resolveModifiers(null, List.of(expired, inactive, missing), false, .5f));
    }

    @Test void directModifierScanStopsBeforeOlderSources() {
        var newest = new Fade(1, 10, animation(true, true, true, true, true));
        var chain = new AbstractList<Object>() {
            @Override public Object get(int index) {
                if (index == 1) return newest;
                throw new AssertionError("Read an older fade after selecting the newest live source");
            }
            @Override public int size() { return 2; }
        };
        assertFalse(NativeAttackArmVisibility.resolveModifiers(null, chain, false, 0).hidesAnyArm());
    }

    @Test void allNativeFlagCombinationsRemainIndependentAndUseImmutableCachedValues() {
        for (int bits = 0; bits < 16; bits++) {
            boolean right = (bits & 1) != 0, left = (bits & 2) != 0;
            boolean rightItem = (bits & 4) != 0, leftItem = (bits & 8) != 0;
            var source = animation(true, right, left, rightItem, leftItem);
            var selected = NativeAttackArmVisibility.resolveModifiers(source, List.of(), false, 0);
            assertEquals(new NativeAttackArmVisibility.Visibility(right, left, rightItem, leftItem), selected);
            assertSame(selected, NativeAttackArmVisibility.resolveModifiers(source, List.of(), false, .9f));
            assertEquals(!right || !left, selected.hidesAnyArm(), "Item flags must not hide arm geometry");
            assertEquals(new NativeAttackArmVisibility.Visibility(left, right, leftItem, rightItem),
                    NativeAttackArmVisibility.resolveModifiers(source, List.of(), true, .9f));
        }
    }

    private record Fade(int time, int length, IAnimation source) implements AnimationFadeAccessor {
        @Override public int emfattachments$time() { return time; }
        @Override public int emfattachments$length() { return length; }
        @Override public IAnimation emfattachments$source() { return source; }
        @Override public float emfattachments$alpha(String part, dev.kosmx.playerAnim.api.TransformType type, float progress) {
            throw new AssertionError("Visibility selection must not evaluate or mutate native fade interpolation");
        }
    }

    private static IAnimation animation(boolean active, boolean right, boolean left, boolean rightItem, boolean leftItem) {
        return new IAnimation() {
            @Override public boolean isActive() { return active; }
            @Override public void setupAnim(float delta) {}
            @Override public dev.kosmx.playerAnim.core.util.Vec3f get3DTransform(String part,
                    dev.kosmx.playerAnim.api.TransformType type, float delta, dev.kosmx.playerAnim.core.util.Vec3f value) {
                return value;
            }
            @Override public FirstPersonMode getFirstPersonMode(float delta) { return FirstPersonMode.NONE; }
            @Override public FirstPersonConfiguration getFirstPersonConfiguration(float delta) {
                return new FirstPersonConfiguration(right, left, rightItem, leftItem);
            }
        };
    }

    @Test void actualInstalledStackModeFilterWouldLoseNativeArmsOnButDirectSourcePreservesIt() {
        IAnimation armsOn = animation(true, true, true, true, true);
        AnimationStack filtered = new AnimationStack(); filtered.addAnimLayer(2000, armsOn);
        assertFalse(filtered.getFirstPersonConfiguration(.5f).isShowRightArm(), "Reproduce AUTO/NONE filter");
        assertEquals(new NativeAttackArmVisibility.Visibility(true, true, true, true),
                NativeAttackArmVisibility.resolve(armsOn, List.of(), false, .5f));
        IAnimation armsOff = animation(true, false, false, true, true);
        assertEquals(new NativeAttackArmVisibility.Visibility(false, false, true, true),
                NativeAttackArmVisibility.resolve(armsOff, List.of(), false, .5f));
    }

    @Test void latestLiveFadeSourceSurvivesNullCurrentWithoutStaleCompletedAttack() {
        IAnimation oldOff = animation(true, false, false, true, false);
        IAnimation recentOn = animation(true, true, true, true, true);
        IAnimation expired = animation(false, false, false, false, false);
        assertEquals(new NativeAttackArmVisibility.Visibility(true, true, true, true),
                NativeAttackArmVisibility.resolve(null, List.of(expired, recentOn, oldOff), false, .5f));
        assertEquals(new NativeAttackArmVisibility.Visibility(false, false, true, false),
                NativeAttackArmVisibility.resolve(oldOff, List.of(recentOn), false, .5f));
        assertNull(NativeAttackArmVisibility.resolve(null, List.of(expired), false, .5f));
    }

    @Test void offhandMirroringKeepsIndependentArmAndItemFlagsWithoutMutatingNativeConfig() {
        IAnimation single = animation(true, true, false, true, false);
        assertEquals(new NativeAttackArmVisibility.Visibility(false, true, false, true),
                NativeAttackArmVisibility.resolve(single, List.of(), true, .5f));
        assertTrue(single.getFirstPersonConfiguration(.5f).isShowRightArm());
        assertFalse(single.getFirstPersonConfiguration(.5f).isShowLeftArm());
        assertEquals(new NativeAttackArmVisibility.Visibility(true, false, true, false),
                NativeAttackArmVisibility.resolve(single, List.of(), false, .5f));
    }
}
