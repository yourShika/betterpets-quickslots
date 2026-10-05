package de.yourshika.betterpets.quickslots;

import de.yourshika.betterpets.quickslots.ui.Anim;
import de.yourshika.betterpets.quickslots.ui.Draw;
import de.yourshika.betterpets.quickslots.ui.Palette;
import de.yourshika.betterpets.quickslots.ui.Sprites;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the quickslot display in each of its styles. The real HUD and the previews in the settings
 * screen both go through here, so what the preview shows is exactly what the game will.
 *
 * <p>Everything is laid out in the display's own unscaled coordinates and then placed, scaled and slid
 * into the rectangle it is given - the whole screen for the HUD, the little viewport for a preview.</p>
 */
final class HudRenderer {

    private static final int TILE = 22;
    private static final int GAP = 3;
    private static final int PADDING = 5;
    private static final int NAME_HEIGHT = 12;
    private static final int NAME_WIDTH = 150;
    private static final int KEYS_HEIGHT = 9;
    private static final int EDGE = 6;
    private static final long POP_MILLIS = 340L;
    private static final long SHAKE_MILLIS = 320L;

    /** The moving parts of one display: the HUD has one, every preview its own. */
    static final class Motion {
        /** 0 = hidden, 1 = fully shown; eased towards whichever applies. */
        float presence;
        /** When the pet that is out last changed (the new slot pops). */
        long changedAt;
        /** When a switch was last refused (the display shakes its head). */
        long refusedAt;
    }

    private HudRenderer() {
    }

    /** What is going to be drawn, and how much room it takes at scale 1. */
    private record Layout(List<SlotView> slots, SlotView active, int width, int height) {
    }

    /** Works out what the display holds with these settings, or {@code null} if that is nothing. */
    private static Layout measure(final List<SlotView> allSlots, final ModConfig.Values config, final Font font) {
        if (config.hudStyle == ModConfig.HudStyle.OFF || allSlots.isEmpty()) {
            return null;
        }
        final List<SlotView> slots = new ArrayList<>(allSlots.size());
        SlotView active = null;
        for (final SlotView slot : allSlots) {
            if (config.hudEmptySlots || !slot.empty()) {
                slots.add(slot);
            }
            if (slot.active()) {
                active = slot;
            }
        }
        return switch (config.hudStyle) {
            case WHEEL -> new Layout(slots, active, 108, 108 + (config.hudNames ? NAME_HEIGHT : 0));
            case COMPACT -> new Layout(slots, active, TILE + 12 + Math.max(36, font.width(compactTitle(active))), TILE + 4);
            default -> slots.isEmpty() ? null : new Layout(slots, active,
                slots.size() * TILE + (slots.size() - 1) * GAP + 2 * PADDING,
                (config.hudNames ? NAME_HEIGHT : 0) + TILE + 2 * PADDING + (config.hudKeys ? KEYS_HEIGHT : 0));
        };
    }

    /**
     * Draws the display where the settings put it inside a screen-sized area.
     *
     * @param cooldown   how much of the switch cooldown is left, 1 down to 0
     * @param lockMillis how long switching is still locked, 0 if it is not
     */
    static void draw(final GuiGraphicsExtractor graphics, final List<SlotView> allSlots, final ModConfig.Values config, final Motion motion,
                     final int areaX, final int areaY, final int areaWidth, final int areaHeight, final float cooldown, final long lockMillis) {
        if (motion.presence <= 0.01F) {
            return;
        }
        final Font font = Minecraft.getInstance().font;
        final Layout layout = measure(allSlots, config, font);
        if (layout == null) {
            return;
        }
        final float eased = Anim.outCubic(motion.presence);
        final float scale = config.hudScale;
        final float scaledWidth = layout.width() * scale;
        final float scaledHeight = layout.height() * scale;
        // Where it rests, and which way it slides in from: always from the nearest edge.
        float x;
        float y;
        float slideX = 0.0F;
        float slideY = 0.0F;
        final float slide = (1.0F - eased) * 10.0F;
        switch (config.hudAnchor) {
            case TOP -> {
                x = (areaWidth - scaledWidth) / 2.0F;
                y = EDGE;
                slideY = -slide;
            }
            case TOP_LEFT -> {
                x = EDGE;
                y = EDGE;
                slideX = -slide;
            }
            case TOP_RIGHT -> {
                x = areaWidth - scaledWidth - EDGE;
                y = EDGE;
                slideX = slide;
            }
            case LEFT -> {
                x = EDGE;
                y = (areaHeight - scaledHeight) / 2.0F;
                slideX = -slide;
            }
            case RIGHT -> {
                x = areaWidth - scaledWidth - EDGE;
                y = (areaHeight - scaledHeight) / 2.0F;
                slideX = slide;
            }
            case BOTTOM_LEFT -> {
                x = EDGE;
                y = areaHeight - scaledHeight - EDGE;
                slideX = -slide;
            }
            case BOTTOM_RIGHT -> {
                x = areaWidth - scaledWidth - EDGE;
                y = areaHeight - scaledHeight - EDGE;
                slideX = slide;
            }
            default -> {
                // Clear of the hotbar, the health and food rows and the item name above them.
                x = (areaWidth - scaledWidth) / 2.0F;
                y = areaHeight - 22 - 44 - scaledHeight;
                slideY = slide;
            }
        }
        // A refused switch: a quick, fading shake of the head.
        final float shakeProgress = Anim.progress(motion.refusedAt, SHAKE_MILLIS);
        final float shake = shakeProgress >= 1.0F ? 0.0F
            : (float) Math.sin(shakeProgress * Math.PI * 5.0) * (1.0F - shakeProgress) * 4.0F;
        body(graphics, font, layout, config, motion, areaX + x + config.hudOffsetX + slideX + shake, areaY + y + config.hudOffsetY + slideY,
            scale, eased * config.hudOpacity, cooldown, lockMillis);
    }

    /**
     * Draws the display in the middle of a rectangle, shrunk to fit if it has to be and regardless of
     * where and how large the settings put it on the screen - a close look at it for the previews.
     */
    static void drawFitted(final GuiGraphicsExtractor graphics, final List<SlotView> allSlots, final ModConfig.Values config, final Motion motion,
                           final int x, final int y, final int width, final int height, final float maxScale) {
        final Font font = Minecraft.getInstance().font;
        final Layout layout = measure(allSlots, config, font);
        if (layout == null) {
            return;
        }
        final float scale = Math.min(maxScale, Math.min(width / (float) layout.width(), height / (float) layout.height()));
        body(graphics, font, layout, config, motion, x + (width - layout.width() * scale) / 2.0F, y + (height - layout.height() * scale) / 2.0F,
            scale, config.hudOpacity, 0.0F, 0L);
    }

    private static void body(final GuiGraphicsExtractor graphics, final Font font, final Layout layout, final ModConfig.Values config, final Motion motion,
                             final float x, final float y, final float scale, final float alpha, final float cooldown, final long lockMillis) {
        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y);
        graphics.pose().scale(scale, scale);
        final float pop = 1.0F - Anim.outCubic(Anim.progress(motion.changedAt, POP_MILLIS));
        switch (config.hudStyle) {
            case WHEEL -> drawWheel(graphics, font, layout.slots(), layout.active(), config, alpha, pop);
            case COMPACT -> drawCompact(graphics, font, layout.active(), layout.width(), alpha, pop);
            default -> drawBar(graphics, font, layout.slots(), layout.active(), config, layout.width(), layout.height(), alpha, pop, cooldown);
        }
        if (lockMillis > 0L) {
            // Over the slots themselves, not over the name written above or below them.
            final boolean named = config.hudNames && config.hudStyle != ModConfig.HudStyle.COMPACT;
            final int top = named && config.hudStyle == ModConfig.HudStyle.BAR ? NAME_HEIGHT : 0;
            final int bottom = layout.height() - (named && config.hudStyle == ModConfig.HudStyle.WHEEL ? NAME_HEIGHT : 0);
            drawLock(graphics, font, layout.width(), top, bottom, alpha, lockMillis);
        }
        graphics.pose().popMatrix();
    }

    // ------------------------------------------------------------------------------------------------
    // The slots in a row
    // ------------------------------------------------------------------------------------------------

    private static void drawBar(final GuiGraphicsExtractor graphics, final Font font, final List<SlotView> slots, final SlotView active,
                                final ModConfig.Values config, final int width, final int height, final float alpha, final float pop, final float cooldown) {
        final int plateY = config.hudNames ? NAME_HEIGHT : 0;
        Sprites.draw(graphics, Sprites.CARD, 0, plateY, width, height - plateY, Palette.fade(Palette.WHITE, alpha * 0.92F));
        if (config.hudNames) {
            drawName(graphics, font, active, width / 2, 1, alpha);
        }
        final int tileY = plateY + PADDING;
        for (int i = 0; i < slots.size(); i++) {
            final SlotView slot = slots.get(i);
            final int tileX = PADDING + i * (TILE + GAP);
            drawTile(graphics, slot, tileX, tileY, alpha, slot.active() ? pop : 0.0F);
            if (slot.active() && cooldown > 0.0F && alpha > 0.5F) {
                // The cooldown drains away from the top, like the game's own item cooldowns.
                final int top = tileY + 1 + Math.round((TILE - 2) * (1.0F - cooldown));
                graphics.fill(tileX + 1, top, tileX + TILE - 1, tileY + TILE - 1, Palette.rgb(0x000000, 0.5F * alpha));
            }
            if (config.hudKeys && alpha > 0.1F) {
                drawSmallText(graphics, font, Keybinds.slotHint(slot.index()), tileX + TILE / 2.0F, tileY + TILE + 3, TILE + GAP - 1,
                    Palette.fade(slot.empty() ? Palette.TEXT_FAINT : Palette.TEXT_DIM, alpha));
            }
        }
    }

    /** One slot: the tile, a glow and gold frame if its pet is out, the pet itself. */
    private static void drawTile(final GuiGraphicsExtractor graphics, final SlotView slot, final int x, final int y, final float alpha, final float pop) {
        final float centreX = x + TILE / 2.0F;
        final float centreY = y + TILE / 2.0F;
        if (slot.active()) {
            final float breathe = 0.55F + 0.25F * Anim.wave(1800L);
            Sprites.drawCentred(graphics, Sprites.GLOW, centreX, centreY, 64, 64, 0.62F + pop * 0.3F, 0.0F,
                Palette.rgb(slot.colour(), alpha * breathe));
        }
        Sprites.draw(graphics, Sprites.SLOT, x, y, TILE, TILE, Palette.fade(Palette.WHITE, alpha));
        if (alpha > 0.5F) {
            final float iconScale = 1.0F + pop * 0.35F;
            slot.drawIcon(graphics, centreX - 8.0F * iconScale, centreY - 8.0F * iconScale, iconScale);
            if (slot.disabled() || slot.missing()) {
                graphics.fill(x + 1, y + 1, x + TILE - 1, y + TILE - 1, Palette.rgb(0x16110F, 0.6F));
            }
        }
        if (slot.active()) {
            final int grow = Math.round(pop * 4.0F);
            Sprites.draw(graphics, Sprites.FRAME_GOLD, x - 2 - grow, y - 2 - grow, TILE + 4 + 2 * grow, TILE + 4 + 2 * grow,
                Palette.fade(Palette.WHITE, alpha));
        }
    }

    // ------------------------------------------------------------------------------------------------
    // The slots around a ring
    // ------------------------------------------------------------------------------------------------

    private static void drawWheel(final GuiGraphicsExtractor graphics, final Font font, final List<SlotView> slots, final SlotView active,
                                  final ModConfig.Values config, final float alpha, final float pop) {
        final float centre = 54.0F;
        Sprites.drawCentred(graphics, Sprites.WHEEL_RING_SMALL, centre, centre, 88, 88, 1.0F, 0.0F, Palette.fade(Palette.WHITE, alpha));
        // The pet that is out sits in the middle, the slots around it.
        if (active != null) {
            Sprites.drawCentred(graphics, Sprites.GLOW, centre, centre, 64, 64, 0.9F + pop * 0.3F, 0.0F,
                Palette.rgb(active.colour(), alpha * (0.5F + 0.25F * Anim.wave(1800L))));
            if (alpha > 0.5F) {
                final float iconScale = 2.0F + pop * 0.6F;
                active.drawIcon(graphics, centre - 8.0F * iconScale, centre - 8.0F * iconScale, iconScale);
            }
        }
        final int count = slots.size();
        for (int i = 0; i < count; i++) {
            final SlotView slot = slots.get(i);
            final double angle = -Math.PI / 2.0 + i * (Math.PI * 2.0 / count);
            final float nodeX = centre + (float) Math.cos(angle) * 38.0F;
            final float nodeY = centre + (float) Math.sin(angle) * 38.0F;
            final float nodeScale = slot.active() ? 1.15F + pop * 0.3F : 1.0F;
            Sprites.drawCentred(graphics, Sprites.SLOT, nodeX, nodeY, 20, 20, nodeScale, 0.0F, Palette.fade(Palette.WHITE, alpha));
            if (alpha > 0.5F) {
                slot.drawIcon(graphics, nodeX - 8.0F * nodeScale, nodeY - 8.0F * nodeScale, nodeScale);
            }
            if (slot.active()) {
                Sprites.drawCentred(graphics, Sprites.FRAME_GOLD, nodeX, nodeY, 24, 24, nodeScale, 0.0F, Palette.fade(Palette.WHITE, alpha));
            }
        }
        if (config.hudNames) {
            drawName(graphics, font, active, 54, 108 + 1, alpha);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Just the pet that is out
    // ------------------------------------------------------------------------------------------------

    private static void drawCompact(final GuiGraphicsExtractor graphics, final Font font, final SlotView active, final int width,
                                    final float alpha, final float pop) {
        Sprites.draw(graphics, Sprites.CARD, 0, 0, width, TILE + 4, Palette.fade(Palette.WHITE, alpha * 0.92F));
        if (active == null) {
            Sprites.draw(graphics, Sprites.SLOT, 2, 2, TILE, TILE, Palette.fade(Palette.WHITE, alpha));
            Sprites.draw(graphics, Sprites.ICON_PUT_AWAY, 5, 5, 16, 16, Palette.fade(Palette.WHITE, alpha));
        } else {
            drawTile(graphics, active, 2, 2, alpha, pop);
        }
        if (alpha > 0.1F) {
            final boolean twoLines = active != null && active.level() > 0;
            graphics.text(font, compactTitle(active), TILE + 7, twoLines ? 4 : 9,
                Palette.rgb(active == null ? 0xCDB8A0 : active.colour(), alpha));
            if (twoLines) {
                graphics.text(font, Component.translatable("betterpets-quickslots.screen.level", active.level()), TILE + 7, 14,
                    Palette.fade(Palette.TEXT_DIM, alpha));
            }
        }
    }

    private static Component compactTitle(final SlotView active) {
        return active == null ? Component.translatable("betterpets-quickslots.hud.none") : Component.literal(active.name());
    }

    // ------------------------------------------------------------------------------------------------

    private static void drawName(final GuiGraphicsExtractor graphics, final Font font, final SlotView active, final int centreX, final int y, final float alpha) {
        // Text drawn with almost no alpha would come out fully opaque; leave it out instead.
        if (alpha <= 0.1F) {
            return;
        }
        if (active == null) {
            graphics.centeredText(font, Component.translatable("betterpets-quickslots.hud.none"), centreX, y, Palette.fade(Palette.TEXT_DIM, alpha));
        } else {
            // The longest nickname the plugin allows is wider than the display it stands over.
            graphics.centeredText(font, Draw.clip(font, Component.literal(active.name()), NAME_WIDTH), centreX, y, Palette.rgb(active.colour(), alpha));
        }
    }

    /**
     * A line at three-quarter size, centred on {@code centreX} - for the key under a slot. A label too
     * long for its slot is shrunk further rather than allowed to run into its neighbours.
     */
    private static void drawSmallText(final GuiGraphicsExtractor graphics, final Font font, final Component text, final float centreX, final float y,
                                      final int maxWidth, final int colour) {
        final float scale = Math.min(0.75F, maxWidth / (float) Math.max(1, font.width(text)));
        graphics.pose().pushMatrix();
        graphics.pose().translate(centreX, y);
        graphics.pose().scale(scale, scale);
        graphics.centeredText(font, text, 0, 0, colour);
        graphics.pose().popMatrix();
    }

    /** Switching is locked after too many switches: a padlock over the display, counting down. */
    private static void drawLock(final GuiGraphicsExtractor graphics, final Font font, final int width, final int top, final int bottom,
                                 final float alpha, final long lockMillis) {
        if (alpha <= 0.1F) {
            return;
        }
        final float pulse = 0.75F + 0.25F * Anim.wave(700L);
        graphics.fill(1, top + 1, width - 1, bottom - 1, Palette.rgb(0x2A0808, 0.5F * alpha));
        final String seconds = (lockMillis + 999L) / 1000L + "s";
        final int total = 11 + 3 + font.width(seconds);
        final int left = (width - total) / 2;
        final int y = (top + bottom - 12) / 2;
        // On a plate of their own: straight on top of a pet's face neither lock nor number could be read.
        Sprites.draw(graphics, Sprites.CARD, left - 5, y - 3, total + 10, 18, Palette.fade(Palette.WHITE, alpha));
        Sprites.draw(graphics, Sprites.LOCK, left, y, 11, 12, Palette.fade(Palette.WHITE, alpha * pulse));
        graphics.text(font, seconds, left + 14, y + 2, Palette.fade(Palette.RED, alpha));
    }
}
