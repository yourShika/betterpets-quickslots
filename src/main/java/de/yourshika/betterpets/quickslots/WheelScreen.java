package de.yourshika.betterpets.quickslots;

import de.yourshika.betterpets.quickslots.ui.Anim;
import de.yourshika.betterpets.quickslots.ui.Palette;
import de.yourshika.betterpets.quickslots.ui.Sprites;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The pet wheel: hold its key, point at a pet, let go.
 *
 * <p>The quickslots sit on a ring around the cursor, which starts in the middle. Moving the mouse towards
 * a pet picks it - only the direction counts, not hitting the tile - and releasing the key summons it.
 * Letting go in the middle changes nothing. A short tap instead of a hold leaves the wheel open, to be
 * used with a click; so does turning "hold" off in the settings.</p>
 */
class WheelScreen extends Screen {

    /** A press shorter than this is a tap: the wheel then stays open. */
    private static final long TAP_MILLIS = 220L;

    private final WheelRenderer.Motion motion = new WheelRenderer.Motion();
    private final Anim.FrameTimer timer = new Anim.FrameTimer();
    private List<SlotView> slots = List.of();
    private int pointed = -1;
    private boolean clickMode;
    private boolean closing;

    WheelScreen() {
        super(Component.translatable(BetterPetsQuickslots.MOD_ID + ".wheel.title"));
        clickMode = !ModConfig.get().wheelHold;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float partialTick) {
        // No blur and only a light veil: the world stays in view, this is a quick choice mid-game.
        graphics.fill(0, 0, width, height, Palette.rgb(0x0A0604, 0.42F * Anim.outCubic(Anim.progress(motion.openedAt, 180L))));
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float partialTick) {
        final float delta = timer.tick();
        slots = SlotView.live();
        final float scale = Math.min(ModConfig.get().wheelScale, (Math.min(width, height) - 8) / WheelRenderer.EXTENT);
        final float centreX = width / 2.0F;
        final float centreY = height / 2.0F;
        pointed = WheelRenderer.pointedSlot(mouseX - centreX, mouseY - centreY, slots.size(), scale);

        if (ModConfig.get().decorations && width > WheelRenderer.EXTENT * scale + 190) {
            // Two onlookers, swaying gently and half a beat apart, their feet on one line.
            final float fade = Anim.outCubic(Anim.progress(motion.openedAt, 220L));
            final float sway = (Anim.wave(2600L) - 0.5F) * 3.0F;
            final float half = WheelRenderer.EXTENT * scale / 2.0F;
            Sprites.draw(graphics, Sprites.CHARACTER_MOON_FOX, Math.round(centreX - half - 74 - (1.0F - fade) * 20), Math.round(centreY - 20 + sway), 68, 62,
                Palette.fade(Palette.WHITE, fade));
            Sprites.draw(graphics, Sprites.CHARACTER_STAR_DRAGON, Math.round(centreX + half + 6 + (1.0F - fade) * 20), Math.round(centreY - 17 - sway), 68, 59,
                Palette.fade(Palette.WHITE, fade));
        }

        WheelRenderer.draw(graphics, slots, motion, centreX, centreY, scale, pointed, clickMode, QuickslotClient.lockRemainingMillis(), delta);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        // Letting go of the key chooses. Asked every frame instead of waiting for the key event, which a
        // screen does not always get to see.
        if (!clickMode && !closing && !Keybinds.isHeld(Keybinds.wheel)) {
            if (pointed < 0 && Anim.now() - motion.openedAt < TAP_MILLIS) {
                clickMode = true;
            } else {
                choose();
            }
        }
    }

    private void choose() {
        closing = true;
        if (pointed >= 0 && pointed < slots.size() && slots.get(pointed).usable()) {
            QuickslotClient.switchTo(pointed);
        }
        onClose();
    }

    @Override
    public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
        if (event.button() == 0) {
            choose();
        } else {
            onClose();
        }
        return true;
    }

    @Override
    public boolean keyPressed(final KeyEvent event) {
        final int digit = event.getDigit();
        if (digit >= 1 && digit <= slots.size()) {
            closing = true;
            QuickslotClient.switchTo(digit - 1);
            onClose();
            return true;
        }
        // In click mode the wheel's own key closes it again.
        if (clickMode && Keybinds.wheel.matches(event) && Anim.now() - motion.openedAt > TAP_MILLIS) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }
}
