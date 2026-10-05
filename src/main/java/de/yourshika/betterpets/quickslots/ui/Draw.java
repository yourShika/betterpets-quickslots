package de.yourshika.betterpets.quickslots.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;
import java.util.Optional;

/** Small drawing helpers the screens share: text that fades, wraps and clips, and the key caps. */
public final class Draw {

    /** Height of a {@link #keyCap}. */
    public static final int KEY_CAP_HEIGHT = 14;

    private Draw() {
    }

    public static boolean inside(final double pointX, final double pointY, final int x, final int y, final int width, final int height) {
        return pointX >= x && pointX < x + width && pointY >= y && pointY < y + height;
    }

    /**
     * Text that can fade: the game draws text with (almost) no alpha fully opaque, so near the end of a
     * fade it is simply left out.
     */
    public static void text(final GuiGraphicsExtractor graphics, final Font font, final Component text, final int x, final int y, final int argb) {
        if ((argb >>> 24) > 24) {
            graphics.text(font, text, x, y, argb);
        }
    }

    public static void centred(final GuiGraphicsExtractor graphics, final Font font, final Component text, final int centreX, final int y, final int argb) {
        if ((argb >>> 24) > 24) {
            graphics.centeredText(font, text, centreX, y, argb);
        }
    }

    /**
     * Text centred on a point that must not get wider than {@code maxWidth}: a label a little too long
     * is drawn a little smaller, and only one that is far too long gets cut.
     */
    public static void fitted(final GuiGraphicsExtractor graphics, final Font font, final Component text, final float centreX, final float y,
                              final int maxWidth, final int argb) {
        if ((argb >>> 24) <= 24) {
            return;
        }
        final int width = font.width(text);
        if (width <= maxWidth) {
            graphics.centeredText(font, text, Math.round(centreX), Math.round(y), argb);
            return;
        }
        final float scale = Math.max(0.75F, maxWidth / (float) width);
        graphics.pose().pushMatrix();
        graphics.pose().translate(centreX, y + 4.0F * (1.0F - scale));
        graphics.pose().scale(scale, scale);
        graphics.centeredText(font, clip(font, text, Math.round(maxWidth / scale)), 0, 0, argb);
        graphics.pose().popMatrix();
    }

    /** A tooltip for a button, in the mod's own look. */
    public static Tooltip tooltip(final Component text) {
        return Tooltip.create(text, Optional.empty(), Sprites.TOOLTIP_STYLE);
    }

    /** Text ending at {@code right}. */
    public static void rightAligned(final GuiGraphicsExtractor graphics, final Font font, final Component text, final int right, final int y, final int argb) {
        text(graphics, font, text, right - font.width(text), y, argb);
    }

    /**
     * Text wrapped to a width, every line centred on {@code centreX}.
     *
     * @return the height it took
     */
    public static int wrappedCentred(final GuiGraphicsExtractor graphics, final Font font, final Component text, final int centreX, final int y,
                                     final int maxWidth, final int argb) {
        final List<FormattedCharSequence> lines = font.split(text, Math.max(20, maxWidth));
        if ((argb >>> 24) > 24) {
            for (int line = 0; line < lines.size(); line++) {
                graphics.centeredText(font, lines.get(line), centreX, y + line * 10, argb);
            }
        }
        return lines.size() * 10;
    }

    /** Shortens a line to a pixel width with an ellipsis. */
    public static Component clip(final Font font, final Component text, final int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        final String cut = font.plainSubstrByWidth(text.getString(), Math.max(0, maxWidth - font.width("…")));
        return Component.literal(cut + "…").withStyle(text.getStyle());
    }

    // ------------------------------------------------------------------------------------------------
    // What the mod's screens have in common
    // ------------------------------------------------------------------------------------------------

    /**
     * The darkening behind a screen. A veil rather than the game's blur: it can fade in and out, and what
     * is behind it stays sharp where a screen wants to show it.
     */
    public static void veil(final GuiGraphicsExtractor graphics, final int width, final int height, final float strength) {
        graphics.fillGradient(0, 0, width, height, Palette.rgb(0x0A0604, strength * 0.8F), Palette.rgb(0x0A0604, strength));
    }

    /** A twinkle on each of the four corner gems of a panel, every one on its own beat. */
    public static void sparkles(final GuiGraphicsExtractor graphics, final int x, final int y, final int width, final int height) {
        for (int corner = 0; corner < 4; corner++) {
            final float beat = (float) ((Anim.now() + corner * 900L) % 3600L) / 3600.0F;
            final float shine = beat < 0.25F ? (float) Math.sin(beat / 0.25F * Math.PI) : 0.0F;
            if (shine > 0.02F) {
                Sprites.drawCentred(graphics, Sprites.SPARK, corner % 2 == 0 ? x + 7 : x + width - 8, corner < 2 ? y + 7 : y + height - 8,
                    9, 9, 0.6F + 0.7F * shine, beat * 3.0F, Palette.fade(Palette.WHITE, shine));
            }
        }
    }

    /** Width of a {@link #scrollbar}. */
    public static final int SCROLLBAR_WIDTH = 4;

    /** How tall the thumb of a scrollbar is for a view of {@code viewHeight} that can scroll by {@code maxScroll}. */
    public static int scrollbarThumb(final int viewHeight, final float maxScroll) {
        return Math.max(16, Math.round(viewHeight * viewHeight / (viewHeight + maxScroll)));
    }

    /** A scrollbar: a dark groove with a copper thumb that brightens while it is {@code hot}. */
    public static void scrollbar(final GuiGraphicsExtractor graphics, final int x, final int y, final int viewHeight,
                                 final float scroll, final float maxScroll, final boolean hot) {
        if (maxScroll <= 0.0F) {
            return;
        }
        final int thumbHeight = scrollbarThumb(viewHeight, maxScroll);
        final int thumbY = y + Math.round((viewHeight - thumbHeight) * Math.max(0.0F, Math.min(1.0F, scroll / maxScroll)));
        graphics.fill(x, y, x + SCROLLBAR_WIDTH, y + viewHeight, Palette.rgb(0x161113, 0.7F));
        graphics.fill(x, thumbY, x + SCROLLBAR_WIDTH, thumbY + thumbHeight, Palette.rgb(hot ? 0xD47242 : 0xB65C36, 1.0F));
        graphics.fill(x, thumbY, x + 1, thumbY + thumbHeight, Palette.rgb(hot ? 0xF8A166 : 0xD47242, 1.0F));
        graphics.fill(x + SCROLLBAR_WIDTH - 1, thumbY, x + SCROLLBAR_WIDTH, thumbY + thumbHeight, Palette.rgb(0x7D381D, 1.0F));
    }

    /** Where a scrollbar should scroll to when its thumb is dragged to {@code mouseY}. */
    public static float scrollbarTarget(final int y, final int viewHeight, final float maxScroll, final double mouseY) {
        final int thumbHeight = scrollbarThumb(viewHeight, maxScroll);
        final float share = (float) ((mouseY - y - thumbHeight / 2.0) / Math.max(1, viewHeight - thumbHeight));
        return Math.max(0.0F, Math.min(1.0F, share)) * maxScroll;
    }

    /** How wide a key cap has to be for a label. */
    public static int keyCapWidth(final Font font, final Component label) {
        return Math.max(20, font.width(label) + 10);
    }

    /** A key of the keyboard with its label on it, {@link #KEY_CAP_HEIGHT} tall. */
    public static void keyCap(final GuiGraphicsExtractor graphics, final Font font, final Component label, final int x, final int y, final int width,
                              final int textArgb, final float alpha) {
        Sprites.draw(graphics, Sprites.KEY_CAP, x, y, width, KEY_CAP_HEIGHT, Palette.fade(Palette.WHITE, alpha));
        centred(graphics, font, label, x + width / 2, y + 2, Palette.fade(textArgb, alpha));
    }
}
