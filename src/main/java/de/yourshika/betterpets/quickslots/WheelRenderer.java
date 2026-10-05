package de.yourshika.betterpets.quickslots;

import de.yourshika.betterpets.quickslots.ui.Anim;
import de.yourshika.betterpets.quickslots.ui.Palette;
import de.yourshika.betterpets.quickslots.ui.Sprites;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Draws the pet wheel. {@link WheelScreen} uses it for the real thing and the settings screen for its
 * preview, so the two cannot look different.
 */
final class WheelRenderer {

    private static final String KEY = BetterPetsQuickslots.MOD_ID + ".wheel.";
    /** Radius the pets sit at: the middle of the ring's band. */
    static final float NODE_RADIUS = 89.0F;
    /** Mouse movement within this distance of the centre picks nothing. */
    static final float DEAD_ZONE = 30.0F;
    /** Everything the wheel draws fits in a square of this size (at scale 1). */
    static final float EXTENT = 228.0F;

    /** The moving parts of one wheel. */
    static final class Motion {
        long openedAt = Anim.now();
        float[] hover = new float[0];
        float pointerAngle;
        float pointerPresence;
    }

    private WheelRenderer() {
    }

    /**
     * Which slot a point is in the direction of, seen from the centre of a wheel with {@code count}
     * slots - or -1 inside the dead zone. Only the direction matters, not hitting a tile.
     */
    static int pointedSlot(final float offsetX, final float offsetY, final int count, final float scale) {
        if (count <= 0 || Math.hypot(offsetX, offsetY) <= DEAD_ZONE * scale) {
            return -1;
        }
        final double step = Math.PI * 2.0 / count;
        return Math.floorMod((int) Math.round((Math.atan2(offsetY, offsetX) + Math.PI / 2.0) / step), count);
    }

    /**
     * @param pointed    the slot being pointed at, or -1
     * @param clickMode  the wheel stays open until a click (changes the hint in the middle)
     * @param lockMillis how long switching is still locked, 0 if it is not
     */
    static void draw(final GuiGraphicsExtractor graphics, final List<SlotView> slots, final Motion motion, final float centreX, final float centreY,
                     final float scale, final int pointed, final boolean clickMode, final long lockMillis, final float delta) {
        final Font font = Minecraft.getInstance().font;
        final int count = slots.size();
        if (motion.hover.length != count) {
            motion.hover = new float[count];
        }
        final double step = count == 0 ? 0.0 : Math.PI * 2.0 / count;
        final boolean locked = lockMillis > 0L;
        final float open = Anim.outBack(Anim.progress(motion.openedAt, 260L));
        final float fade = Anim.outCubic(Anim.progress(motion.openedAt, 140L));

        Sprites.drawCentred(graphics, Sprites.WHEEL_RING, centreX, centreY, 200, 200, scale * (0.82F + 0.18F * open), 0.0F, Palette.fade(Palette.WHITE, fade));
        Sprites.drawCentred(graphics, Sprites.WHEEL_HUB, centreX, centreY, 72, 72, scale * open, 0.0F, Palette.fade(Palette.WHITE, fade));

        // The pointer swings round to the pet being pointed at.
        motion.pointerPresence = Anim.approach(motion.pointerPresence, pointed >= 0 ? 1.0F : 0.0F, 14.0F, delta);
        if (pointed >= 0) {
            final float goal = (float) (-Math.PI / 2.0 + pointed * step);
            final float turn = (float) Math.IEEEremainder(goal - motion.pointerAngle, Math.PI * 2.0);
            motion.pointerAngle += Anim.enabled() ? turn * (1.0F - (float) Math.exp(-20.0F * delta)) : turn;
        }

        for (int i = 0; i < count; i++) {
            final SlotView slot = slots.get(i);
            final double angle = -Math.PI / 2.0 + i * step;
            // The pets fly out from the middle one after another.
            final float arrive = Anim.outBack(Anim.staggered(motion.openedAt, 240L, i, 22L));
            final float nodeX = centreX + (float) Math.cos(angle) * NODE_RADIUS * scale * arrive;
            final float nodeY = centreY + (float) Math.sin(angle) * NODE_RADIUS * scale * arrive;
            motion.hover[i] = Anim.approach(motion.hover[i], i == pointed ? 1.0F : 0.0F, 16.0F, delta);
            final float lift = motion.hover[i] * (slot.usable() && !locked ? 1.0F : 0.35F);
            final float nodeScale = scale * arrive * (1.0F + 0.2F * lift);

            if (lift > 0.02F && slot.usable()) {
                Sprites.drawCentred(graphics, Sprites.GLOW, nodeX, nodeY, 64, 64, nodeScale * 1.25F, 0.0F, Palette.rgb(slot.colour(), 0.85F * lift));
            } else if (slot.active()) {
                Sprites.drawCentred(graphics, Sprites.GLOW, nodeX, nodeY, 64, 64, nodeScale * 1.05F, 0.0F,
                    Palette.rgb(slot.colour(), 0.35F + 0.2F * Anim.wave(1800L)));
            }
            Sprites.drawCentred(graphics, Sprites.WHEEL_NODE, nodeX, nodeY, 44, 44, nodeScale, 0.0F, Palette.fade(Palette.WHITE, fade));
            if (slot.empty()) {
                Sprites.drawCentred(graphics, Sprites.ICON_EMPTY, nodeX, nodeY, 16, 16, nodeScale * 1.4F, 0.0F, Palette.fade(Palette.WHITE, 0.45F * fade));
            } else if (fade > 0.5F) {
                final float iconScale = 2.0F * nodeScale;
                slot.drawIcon(graphics, nodeX - 8.0F * iconScale, nodeY - 8.0F * iconScale, iconScale);
                if (!slot.usable()) {
                    Sprites.drawCentred(graphics, Sprites.WHEEL_NODE, nodeX, nodeY, 44, 44, nodeScale, 0.0F, Palette.rgb(0x16110F, 0.6F));
                }
            }
            if (lift > 0.02F && slot.usable()) {
                Sprites.drawCentred(graphics, Sprites.WHEEL_NODE_GOLD, nodeX, nodeY, 48, 48, nodeScale, 0.0F, Palette.fade(Palette.WHITE, lift));
            }
            if (slot.active()) {
                Sprites.drawCentred(graphics, Sprites.GEM_GREEN, nodeX + 14.0F * nodeScale, nodeY - 14.0F * nodeScale, 6, 6, nodeScale * 1.3F, 0.0F, Palette.WHITE);
            }
            // The slot number rides on the inside of the ring.
            if (scale >= 0.6F) {
                final float labelX = centreX + (float) Math.cos(angle) * 58.0F * scale * arrive;
                final float labelY = centreY + (float) Math.sin(angle) * 58.0F * scale * arrive;
                graphics.centeredText(font, Integer.toString(i + 1), Math.round(labelX), Math.round(labelY) - 4,
                    Palette.fade(i == pointed ? Palette.GOLD : Palette.TEXT_FAINT, fade));
            }
        }
        drawCentre(graphics, font, pointed >= 0 && pointed < count ? slots.get(pointed) : null, centreX, centreY, scale, fade, clickMode, lockMillis);
        // Last, so that the name plate in the middle never hides where the pointer is pointing.
        if (motion.pointerPresence > 0.02F) {
            final float reach = (42.0F + 3.0F * Anim.wave(900L)) * scale;
            Sprites.drawCentred(graphics, Sprites.WHEEL_POINTER,
                centreX + (float) Math.cos(motion.pointerAngle) * reach, centreY + (float) Math.sin(motion.pointerAngle) * reach,
                15, 12, scale * 1.2F, motion.pointerAngle + (float) (Math.PI / 2.0), Palette.fade(Palette.WHITE, motion.pointerPresence));
        }
    }

    /** The middle of the wheel: the pet being pointed at and what choosing it will do, or a hint. */
    private static void drawCentre(final GuiGraphicsExtractor graphics, final Font font, final SlotView target, final float centreX, final float centreY,
                                   final float scale, final float fade, final boolean clickMode, final long lockMillis) {
        final Component line;
        Component detail = null;
        int colour = Palette.TEXT_DIM;
        float plateOffset = -8.0F;
        if (lockMillis > 0L) {
            Sprites.drawCentred(graphics, Sprites.LOCK, centreX, centreY - 9.0F * scale, 11, 12, scale * 1.6F, 0.0F,
                Palette.fade(Palette.WHITE, 0.75F + 0.25F * Anim.wave(700L)));
            line = Component.translatable(KEY + "locked", (lockMillis + 999L) / 1000L);
            colour = Palette.RED;
            plateOffset = 6.0F * scale;
        } else if (target == null) {
            line = Component.translatable(KEY + (clickMode ? "hint.click" : "hint.hold"));
        } else if (target.empty()) {
            line = Component.translatable(KEY + "empty", target.index() + 1);
            colour = Palette.TEXT_FAINT;
        } else if (!target.usable()) {
            line = Component.literal(target.name());
            detail = Component.translatable(KEY + (target.missing() ? "missing" : "disabled"));
            colour = Palette.TEXT_FAINT;
            plateOffset = -13.0F;
        } else {
            if (fade > 0.5F) {
                final float iconScale = 2.0F * scale;
                target.drawIcon(graphics, centreX - 8.0F * iconScale, centreY - 8.0F * iconScale - 10.0F * scale, iconScale);
            }
            line = Component.literal(target.name());
            detail = target.active() && QuickslotClient.sameSlotPutsAway()
                ? Component.translatable(KEY + "put_away")
                : Component.translatable(BetterPetsQuickslots.MOD_ID + ".screen.level", target.level());
            colour = Palette.rgb(target.colour(), 1.0F);
            plateOffset = 8.0F * scale;
        }
        // On a small preview there is no room for words; the icons carry it.
        if (scale < 0.6F || fade <= 0.1F) {
            return;
        }
        final int plateWidth = Math.max(font.width(line), detail == null ? 0 : font.width(detail)) + 14;
        final int plateHeight = detail == null ? 16 : 26;
        final int plateX = Math.round(centreX - plateWidth / 2.0F);
        final int plateY = Math.round(centreY + plateOffset);
        Sprites.draw(graphics, Sprites.CARD, plateX, plateY, plateWidth, plateHeight, Palette.fade(Palette.WHITE, fade));
        graphics.centeredText(font, line, Math.round(centreX), plateY + 4, Palette.fade(colour, fade));
        if (detail != null) {
            graphics.centeredText(font, detail, Math.round(centreX), plateY + 14, Palette.fade(Palette.TEXT_DIM, fade));
        }
    }
}
