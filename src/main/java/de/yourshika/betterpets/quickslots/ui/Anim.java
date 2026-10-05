package de.yourshika.betterpets.quickslots.ui;

import net.minecraft.util.Util;

/**
 * The motion of the interface: easing curves, timed progress and frame-rate independent smoothing.
 *
 * <p>Everything runs on wall-clock time ({@link Util#getMillis()}) rather than on game ticks, so the
 * interface moves at the same pace whatever the frame rate, and keeps moving while the game is paused.
 * Animations can be switched off or sped up as a whole (see the settings screen): with them off every
 * timed animation is already finished and every smoothed value sits on its target.</p>
 */
public final class Anim {

    private static boolean enabled = true;
    private static float speed = 1.0F;

    private Anim() {
    }

    /** Applies the player's animation settings. */
    public static void configure(final boolean animationsEnabled, final float animationSpeed) {
        enabled = animationsEnabled;
        speed = Math.max(0.25F, Math.min(4.0F, animationSpeed));
    }

    public static boolean enabled() {
        return enabled;
    }

    public static long now() {
        return Util.getMillis();
    }

    // ------------------------------------------------------------------------------------------------
    // Timed animations
    // ------------------------------------------------------------------------------------------------

    /** How far a timed animation is, 0..1, given when it started and how long it takes at normal speed. */
    public static float progress(final long startMillis, final long durationMillis) {
        if (!enabled || durationMillis <= 0L) {
            return 1.0F;
        }
        return clamp01((now() - startMillis) * speed / durationMillis);
    }

    /** Like {@link #progress}, for the n-th of several items that start one after another. */
    public static float staggered(final long startMillis, final long durationMillis, final int index, final long delayMillis) {
        return progress(startMillis + (long) (index * delayMillis / speed), durationMillis);
    }

    /** A value swinging between 0 and 1 and back once per period; never stops, also with animations off. */
    public static float wave(final long periodMillis) {
        return 0.5F - 0.5F * (float) Math.cos(now() % periodMillis / (double) periodMillis * Math.PI * 2.0);
    }

    // ------------------------------------------------------------------------------------------------
    // Smoothed values
    // ------------------------------------------------------------------------------------------------

    /**
     * Moves {@code current} towards {@code target}, covering the same share of the remaining distance in
     * the same time at any frame rate. {@code sharpness} is roughly "how many times per second the gap
     * shrinks to a third": 12 is snappy, 6 is gentle.
     */
    public static float approach(final float current, final float target, final float sharpness, final float deltaSeconds) {
        if (!enabled) {
            return target;
        }
        final float next = target + (current - target) * (float) Math.exp(-sharpness * speed * deltaSeconds);
        return Math.abs(next - target) < 0.001F ? target : next;
    }

    /** Measures the time between frames for {@link #approach}. One per screen. */
    public static final class FrameTimer {
        private long last;

        /** Seconds since the previous call, capped so a hitch does not make everything jump. */
        public float tick() {
            final long now = now();
            final float delta = last == 0L ? 0.0F : Math.min(0.1F, (now - last) / 1000.0F);
            last = now;
            return delta;
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Easing
    // ------------------------------------------------------------------------------------------------

    public static float clamp01(final float value) {
        return value < 0.0F ? 0.0F : Math.min(value, 1.0F);
    }

    public static float lerp(final float from, final float to, final float t) {
        return from + (to - from) * t;
    }

    /** Fast at first, then settling. */
    public static float outCubic(final float t) {
        final float inverse = 1.0F - clamp01(t);
        return 1.0F - inverse * inverse * inverse;
    }

    /** Overshoots the end a little and comes back - a "pop". */
    public static float outBack(final float t) {
        final float c = 1.70158F;
        final float shifted = clamp01(t) - 1.0F;
        return 1.0F + (c + 1.0F) * shifted * shifted * shifted + c * shifted * shifted;
    }

    /** Slow at both ends. */
    public static float inOutSine(final float t) {
        return 0.5F - 0.5F * (float) Math.cos(clamp01(t) * Math.PI);
    }
}
