package de.yourshika.betterpets.quickslots.ui;

/** The colours of the interface (ARGB), matched to the warm wood and copper of the textures. */
public final class Palette {

    public static final int WHITE = 0xFFFFFFFF;
    public static final int TEXT = 0xFFFFF4E6;
    public static final int TEXT_DIM = 0xFFCDB8A0;
    public static final int TEXT_FAINT = 0xFF94806C;
    public static final int TEXT_DARK = 0xFF2A1A10;
    public static final int GOLD = 0xFFFFC83C;
    public static final int COPPER = 0xFFF8A166;
    public static final int TEAL = 0xFF7FE6CC;
    public static final int GREEN = 0xFF5BE36A;
    public static final int RED = 0xFFFF6B6B;
    public static final int SHADE = 0xFF000000;

    private Palette() {
    }

    /** The colour with its alpha multiplied by {@code alpha} (0..1). */
    public static int fade(final int argb, final float alpha) {
        final int a = Math.round((argb >>> 24) * Math.max(0.0F, Math.min(1.0F, alpha)));
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    /** An opaque colour from 0xRRGGBB, faded to {@code alpha}. */
    public static int rgb(final int rgb, final float alpha) {
        return fade(0xFF000000 | rgb, alpha);
    }

    /** Blends two colours channel by channel; {@code t} = 0 gives {@code from}, 1 gives {@code to}. */
    public static int mix(final int from, final int to, final float t) {
        final float c = Math.max(0.0F, Math.min(1.0F, t));
        final int a = Math.round(((from >>> 24) & 0xFF) + (((to >>> 24) & 0xFF) - ((from >>> 24) & 0xFF)) * c);
        final int r = Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * c);
        final int g = Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * c);
        final int b = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * c);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
