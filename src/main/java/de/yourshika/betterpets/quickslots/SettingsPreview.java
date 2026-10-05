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
 * The preview in the settings screen. It draws the display and the pet wheel with the very renderers the
 * game uses, so nothing here can look different from the real thing - and it acts the settings out:
 *
 * <ul>
 *   <li><b>the display</b> on a small screen with the hotbar on it (where it sits and how large it is),
 *       and once more up close (what it looks like). A little loop plays what happens in the game - a pet
 *       is switched, a while later the modifier key is held - and the display comes and goes exactly as
 *       the settings say it should;</li>
 *   <li><b>the pet wheel</b>, opening and pointing at one pet after another, or following the mouse;</li>
 *   <li><b>the keys</b>: press any of them in the settings and the preview shows what it would do.</li>
 * </ul>
 *
 * <p>All of it is make-believe: nothing is sent to the server. The slots are the player's own if they have
 * any, a few sample pets otherwise.</p>
 */
final class SettingsPreview {

    /** What the preview shows. */
    enum Mode {
        KEYS, HUD, WHEEL
    }

    private static final String KEY = BetterPetsQuickslots.MOD_ID + ".settings.preview.";

    // The loop the display preview plays: wait, switch a pet, let the display go, hold the modifier, rest.
    private static final long LEAD_IN_MILLIS = 700L;
    private static final long PAUSE_MILLIS = 1300L;
    private static final long HOLD_MILLIS = 1700L;
    private static final long TAIL_MILLIS = 1100L;
    /** How long the note about an event stays up. */
    private static final long TAG_MILLIS = 1300L;
    /** The wheel preview opens anew this often, so its opening is seen too. */
    private static final long WHEEL_LOOP_MILLIS = 8000L;
    private static final long WHEEL_STEP_MILLIS = 850L;
    /** How long the answer to a pressed key stays up. */
    private static final long SAID_MILLIS = 2800L;
    private static final long TESTER_WHEEL_MILLIS = 2200L;
    /** The least height the close-up of the display gets under the small screen. */
    private static final int CLOSE_UP_MIN = 34;

    private final HudRenderer.Motion hud = new HudRenderer.Motion();
    private final WheelRenderer.Motion wheel = new WheelRenderer.Motion();
    /** The look the key tester uses: everything on, so every slot shows its key. */
    private final ModConfig.Values testerLook = new ModConfig.Values();

    private boolean started;
    /** The slot whose pet is "out", or -1. */
    private int activeSlot = -1;
    private long loopStart = Anim.now();
    private boolean switched;
    private long switchedAt;
    private boolean holding;

    // What the key tester last recognised.
    private Component saidKeys;
    private Component saidText;
    private int saidColour;
    private long saidAt;
    private long testerWheelAt;

    SettingsPreview() {
        hud.presence = 1.0F;
        testerLook.hudStyle = ModConfig.HudStyle.BAR;
        testerLook.hudNames = true;
        testerLook.hudKeys = true;
        testerLook.hudEmptySlots = true;
    }

    // ------------------------------------------------------------------------------------------------
    // The pets on show
    // ------------------------------------------------------------------------------------------------

    private List<SlotView> baseSlots() {
        final List<SlotView> base = SlotView.forPreview(-1);
        if (!started) {
            started = true;
            activeSlot = step(base, -1, 1);
        }
        if (activeSlot >= base.size() || activeSlot >= 0 && !base.get(activeSlot).usable()) {
            activeSlot = -1;
        }
        return base;
    }

    private List<SlotView> withActive(final List<SlotView> base) {
        final List<SlotView> views = new ArrayList<>(base.size());
        for (final SlotView view : base) {
            views.add(view.withActive(view.index() == activeSlot));
        }
        return views;
    }

    /** The next slot with a pet that can be summoned, going round; {@code from} itself if there is none. */
    private static int step(final List<SlotView> slots, final int from, final int direction) {
        for (int i = 1; i <= slots.size(); i++) {
            final int candidate = Math.floorMod(from + direction * i, slots.size());
            if (slots.get(candidate).usable()) {
                return candidate;
            }
        }
        return from;
    }

    // ------------------------------------------------------------------------------------------------
    // The loop of the display preview
    // ------------------------------------------------------------------------------------------------

    private void playLoop(final ModConfig.Values config, final List<SlotView> base, final float delta) {
        final long now = Anim.now();
        final long shown = (long) (config.hudSeconds * 1000.0F);
        final long holdFrom = LEAD_IN_MILLIS + shown + PAUSE_MILLIS;
        final long holdUntil = holdFrom + HOLD_MILLIS;
        long time = now - loopStart;
        if (time < 0L || time >= holdUntil + TAIL_MILLIS) {
            loopStart = now;
            time = 0L;
            switched = false;
        }
        if (!switched && time >= LEAD_IN_MILLIS) {
            switched = true;
            switchedAt = now;
            activeSlot = step(base, activeSlot, 1);
            hud.changedAt = now;
        }
        holding = !Keybinds.modifier.isUnbound() && time >= holdFrom && time < holdUntil;
        final boolean wanted = config.hudStyle != ModConfig.HudStyle.OFF
            && (config.hudAlways
                || config.hudOnChange && switched && now - switchedAt < shown
                || config.hudWhileModifier && holding);
        hud.presence = Anim.approach(hud.presence, wanted ? 1.0F : 0.0F, wanted ? 16.0F : 9.0F, delta);
    }

    // ------------------------------------------------------------------------------------------------
    // In the settings panel
    // ------------------------------------------------------------------------------------------------

    /**
     * Draws the preview into its place in the settings panel.
     *
     * @param screenWidth  the size of the real screen, which the small one is a scaled-down copy of
     * @param screenHeight see {@code screenWidth}
     */
    void draw(final GuiGraphicsExtractor graphics, final Mode mode, final int x, final int y, final int width, final int height,
              final int screenWidth, final int screenHeight, final int mouseX, final int mouseY, final float delta) {
        final Font font = Minecraft.getInstance().font;
        Sprites.draw(graphics, Sprites.INSET, x, y, width, height);
        final int innerX = x + 3;
        final int innerY = y + 3;
        final int innerWidth = width - 6;
        final int innerHeight = height - 6;
        if (innerWidth < 40 || innerHeight < 40) {
            return;
        }
        final List<SlotView> base = baseSlots();
        final ModConfig.Values config = ModConfig.get();
        switch (mode) {
            case WHEEL -> drawWheelStage(graphics, base, config, innerX, innerY, innerWidth, innerHeight, mouseX, mouseY, delta);
            case KEYS -> drawTester(graphics, font, base, innerX, innerY, innerWidth, innerHeight, delta);
            default -> {
                playLoop(config, base, delta);
                final List<SlotView> views = withActive(base);
                // The small screen keeps the shape of the real one, and leaves room below for the close-up.
                int miniWidth = innerWidth;
                int miniHeight = Math.round(miniWidth * screenHeight / (float) screenWidth);
                final int tallest = innerHeight - CLOSE_UP_MIN - 3;
                if (miniHeight > tallest) {
                    miniHeight = tallest;
                    miniWidth = Math.round(miniHeight * screenWidth / (float) screenHeight);
                }
                drawSmallScreen(graphics, font, views, config, innerX + (innerWidth - miniWidth) / 2, innerY, miniWidth, miniHeight, screenWidth, screenHeight);
                final int closeY = innerY + miniHeight + 3;
                drawCloseUp(graphics, font, views, config, innerX, closeY, innerWidth, innerY + innerHeight - closeY);
            }
        }
    }

    /** The real screen in small: the scene, the game's HUD, and the display where the settings put it. */
    private void drawSmallScreen(final GuiGraphicsExtractor graphics, final Font font, final List<SlotView> views, final ModConfig.Values config,
                                 final int x, final int y, final int width, final int height, final int screenWidth, final int screenHeight) {
        graphics.enableScissor(x, y, x + width, y + height);
        Sprites.draw(graphics, Sprites.SCENE, x, y, width, height);
        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y);
        graphics.pose().scale(width / (float) screenWidth, height / (float) screenHeight);
        drawGameHud(graphics, screenWidth, screenHeight);
        HudRenderer.draw(graphics, views, config, hud, 0, 0, screenWidth, screenHeight, 0.0F, 0L);
        graphics.pose().popMatrix();
        if (config.hudStyle == ModConfig.HudStyle.OFF) {
            drawPlate(graphics, font, Component.translatable(KEY + "off"), x + width / 2, y + height / 2 - 7, Palette.TEXT_DIM, 1.0F);
        } else {
            // In whichever half of the screen the display is not.
            drawEventTag(graphics, font, x + width / 2, y + Math.round(height * (atTop(config.hudAnchor) ? 0.62F : 0.13F)));
        }
        graphics.disableScissor();
    }

    private static boolean atTop(final ModConfig.HudAnchor anchor) {
        return anchor == ModConfig.HudAnchor.TOP || anchor == ModConfig.HudAnchor.TOP_LEFT || anchor == ModConfig.HudAnchor.TOP_RIGHT;
    }

    /** What is happening in the make-believe game right now - the cause the display reacts to. */
    private void drawEventTag(final GuiGraphicsExtractor graphics, final Font font, final int centreX, final int centreY) {
        final long sinceSwitch = Anim.now() - switchedAt;
        if (switched && sinceSwitch < TAG_MILLIS) {
            final float fade = sinceSwitch > TAG_MILLIS - 300L ? (TAG_MILLIS - sinceSwitch) / 300.0F : 1.0F;
            drawPlate(graphics, font, Component.translatable(KEY + "event.switch"), centreX, centreY - 7, Palette.GREEN, fade);
        } else if (holding) {
            drawPlate(graphics, font, Component.translatable(KEY + "event.modifier", Keybinds.shortLabel(Keybinds.modifier)), centreX, centreY - 7,
                Palette.GOLD, 1.0F);
        }
    }

    /** A line of text on a small card. */
    private static void drawPlate(final GuiGraphicsExtractor graphics, final Font font, final Component text, final int centreX, final int y,
                                  final int colour, final float alpha) {
        final int plateWidth = font.width(text) + 12;
        Sprites.draw(graphics, Sprites.CARD, centreX - plateWidth / 2, y, plateWidth, 14, Palette.fade(Palette.WHITE, alpha * 0.92F));
        Draw.centred(graphics, font, text, centreX, y + 3, Palette.fade(colour, alpha));
    }

    /** The display up close, over a strip of the scene so that its opacity can be judged. */
    private void drawCloseUp(final GuiGraphicsExtractor graphics, final Font font, final List<SlotView> views, final ModConfig.Values config,
                             final int x, final int y, final int width, final int height) {
        if (height < 12) {
            return;
        }
        graphics.enableScissor(x, y, x + width, y + height);
        drawSceneStrip(graphics, x, y, width, height);
        if (config.hudStyle != ModConfig.HudStyle.OFF) {
            HudRenderer.drawFitted(graphics, views, config, hud, x + 4, y + 2, width - 8, height - 4, 1.25F);
        }
        graphics.disableScissor();
    }

    /** Fills a rectangle with the part of the scene around its horizon. */
    private static void drawSceneStrip(final GuiGraphicsExtractor graphics, final int x, final int y, final int width, final int height) {
        final int sceneHeight = width * 144 / 256;
        if (sceneHeight * 0.78F >= height) {
            // Wider than the scene is: show the band from the hills down to the first row of soil.
            Sprites.draw(graphics, Sprites.SCENE, x, y + height - Math.round(sceneHeight * 0.78F), width, sceneHeight);
        } else {
            Sprites.draw(graphics, Sprites.SCENE, x, y, width, height);
        }
    }

    /** The game's own HUD, as a landmark: hotbar, experience bar, hearts, food and the crosshair. */
    static void drawGameHud(final GuiGraphicsExtractor graphics, final int screenWidth, final int screenHeight) {
        final int left = screenWidth / 2 - 91;
        Sprites.draw(graphics, Sprites.VANILLA_HOTBAR, left, screenHeight - 22, 182, 22);
        Sprites.draw(graphics, Sprites.VANILLA_EXPERIENCE, left, screenHeight - 29, 182, 5);
        for (int i = 0; i < 10; i++) {
            Sprites.draw(graphics, Sprites.VANILLA_HEART_CONTAINER, left + i * 8, screenHeight - 39, 9, 9);
            Sprites.draw(graphics, Sprites.VANILLA_HEART, left + i * 8, screenHeight - 39, 9, 9);
            Sprites.draw(graphics, Sprites.VANILLA_FOOD_EMPTY, left + 173 - i * 8, screenHeight - 39, 9, 9);
            Sprites.draw(graphics, Sprites.VANILLA_FOOD, left + 173 - i * 8, screenHeight - 39, 9, 9);
        }
        Sprites.draw(graphics, Sprites.VANILLA_CROSSHAIR, (screenWidth - 15) / 2, (screenHeight - 15) / 2, 15, 15);
    }

    // ------------------------------------------------------------------------------------------------
    // The pet wheel
    // ------------------------------------------------------------------------------------------------

    private void drawWheelStage(final GuiGraphicsExtractor graphics, final List<SlotView> base, final ModConfig.Values config,
                                final int x, final int y, final int width, final int height, final int mouseX, final int mouseY, final float delta) {
        graphics.enableScissor(x, y, x + width, y + height);
        drawSceneStrip(graphics, x, y, width, height);
        graphics.fill(x, y, x + width, y + height, Palette.rgb(0x0A0604, 0.42F));
        // Sized so that the largest setting just fills the stage and the smaller ones visibly do not.
        final float scale = Math.min(width, height) / WheelRenderer.EXTENT * (0.62F + 0.38F * (config.wheelScale - 0.75F) / 0.75F);
        final float centreX = x + width / 2.0F;
        final float centreY = y + height / 2.0F;
        final List<SlotView> views = withActive(base);
        final long now = Anim.now();
        final int pointed;
        if (Draw.inside(mouseX, mouseY, x, y, width, height)) {
            // Under the mouse the wheel can be tried out.
            pointed = WheelRenderer.pointedSlot(mouseX - centreX, mouseY - centreY, views.size(), scale);
        } else {
            if (now - wheel.openedAt > WHEEL_LOOP_MILLIS) {
                wheel.openedAt = now;
            }
            final long turning = now - wheel.openedAt - 700L;
            pointed = turning < 0L || views.isEmpty() ? -1 : (int) (turning / WHEEL_STEP_MILLIS % views.size());
        }
        WheelRenderer.draw(graphics, views, wheel, centreX, centreY, scale, pointed, !config.wheelHold, 0L, delta);
        graphics.disableScissor();
    }

    // ------------------------------------------------------------------------------------------------
    // The key tester
    // ------------------------------------------------------------------------------------------------

    private void drawTester(final GuiGraphicsExtractor graphics, final Font font, final List<SlotView> base,
                            final int x, final int y, final int width, final int height, final float delta) {
        final List<SlotView> views = withActive(base);
        final int stageHeight = Math.max(44, Math.min(height - 40, 84));
        graphics.enableScissor(x, y, x + width, y + stageHeight);
        drawSceneStrip(graphics, x, y, width, stageHeight);
        if (Anim.now() - testerWheelAt < TESTER_WHEEL_MILLIS) {
            // The wheel key was pressed: a wheel opens, as it would in the game.
            graphics.fill(x, y, x + width, y + stageHeight, Palette.rgb(0x0A0604, 0.42F));
            WheelRenderer.draw(graphics, views, wheel, x + width / 2.0F, y + stageHeight / 2.0F, (stageHeight - 4) / WheelRenderer.EXTENT,
                -1, !ModConfig.get().wheelHold, 0L, delta);
        } else {
            testerLook.hudOpacity = 1.0F;
            HudRenderer.drawFitted(graphics, views, testerLook, hud, x + 4, y + 3, width - 8, stageHeight - 6, 1.25F);
        }
        graphics.disableScissor();

        final int textY = y + stageHeight + 5;
        final int textHeight = y + height - textY;
        final int centreX = x + width / 2;
        final long age = Anim.now() - saidAt;
        if (saidText != null && age < SAID_MILLIS) {
            final float fade = age > SAID_MILLIS - 400L ? (SAID_MILLIS - age) / 400.0F : 1.0F;
            final float pop = Anim.outBack(Anim.progress(saidAt, 220L));
            final int capWidth = Draw.keyCapWidth(font, saidKeys);
            final int capY = textY + Math.max(0, (textHeight - 28) / 2) - Math.round((1.0F - pop) * 4.0F);
            Draw.keyCap(graphics, font, saidKeys, centreX - capWidth / 2, capY, capWidth, Palette.TEXT, fade);
            Draw.centred(graphics, font, Draw.clip(font, saidText, width - 6), centreX, capY + Draw.KEY_CAP_HEIGHT + 4, Palette.fade(saidColour, fade));
        } else if (!Keybinds.modifier.isUnbound() && Keybinds.isHeld(Keybinds.modifier)) {
            Draw.wrappedCentred(graphics, font, Component.translatable(KEY + "try.modifier", Keybinds.shortLabel(Keybinds.modifier)),
                centreX, textY + 2, width - 8, Palette.GOLD);
        } else {
            Draw.wrappedCentred(graphics, font, Component.translatable(KEY + "try"), centreX, textY + 2, width - 8,
                Palette.mix(Palette.TEXT_FAINT, Palette.TEXT_DIM, Anim.wave(2400L)));
        }
    }

    /** A key for a quickslot was pressed in the settings. */
    void testSlot(final int slot, final Component keys) {
        final List<SlotView> base = baseSlots();
        if (slot >= base.size()) {
            say(keys, Component.translatable(KEY + "try.no_slot", slot + 1), Palette.TEXT_FAINT);
        } else if (!base.get(slot).usable()) {
            say(keys, Component.translatable(KEY + "try.empty", slot + 1), Palette.TEXT_FAINT);
        } else if (activeSlot == slot && !QuickslotClient.sameSlotPutsAway()) {
            say(keys, Component.translatable(KEY + "try.slot", slot + 1, base.get(slot).name()), Palette.rgb(base.get(slot).colour(), 1.0F));
        } else if (activeSlot == slot) {
            // Pressing the slot of the pet that is out puts it away - unless the server has that turned off.
            activeSlot = -1;
            hud.changedAt = Anim.now();
            say(keys, Component.translatable(KEY + "try.put_away"), Palette.TEXT_DIM);
        } else {
            activeSlot = slot;
            hud.changedAt = Anim.now();
            say(keys, Component.translatable(KEY + "try.slot", slot + 1, base.get(slot).name()), Palette.rgb(base.get(slot).colour(), 1.0F));
        }
    }

    /** "Next" or "previous" was pressed, or the mouse wheel turned with the modifier held. */
    void testCycle(final int direction, final Component keys) {
        final List<SlotView> base = baseSlots();
        final int next = step(base, activeSlot, direction);
        if (next < 0) {
            return;
        }
        activeSlot = next;
        hud.changedAt = Anim.now();
        say(keys, Component.translatable(KEY + "try.slot", next + 1, base.get(next).name()), Palette.rgb(base.get(next).colour(), 1.0F));
    }

    void testPutAway(final Component keys) {
        activeSlot = -1;
        hud.changedAt = Anim.now();
        say(keys, Component.translatable(KEY + "try.put_away"), Palette.TEXT_DIM);
    }

    void testWheel(final Component keys) {
        testerWheelAt = Anim.now();
        wheel.openedAt = testerWheelAt;
        say(keys, Component.translatable(KEY + "try.wheel"), Palette.COPPER);
    }

    /** Some other key of the mod was pressed: just name what it does. */
    void testOther(final Component keys, final Component what) {
        say(keys, what, Palette.TEXT_DIM);
    }

    private void say(final Component keys, final Component text, final int colour) {
        saidKeys = keys;
        saidText = text;
        saidColour = colour;
        saidAt = Anim.now();
    }

    // ------------------------------------------------------------------------------------------------
    // Full size
    // ------------------------------------------------------------------------------------------------

    /** Starts the full-size preview afresh, so that it opens the way the real thing does. */
    void restart() {
        final long now = Anim.now();
        wheel.openedAt = now;
        loopStart = now;
        switched = false;
    }

    /**
     * Draws the preview across the whole screen, exactly as large as the game will. With a world loaded
     * that world (and its HUD) is what is behind it; without one the game's HUD is drawn in for scale.
     *
     * @param noteY where the note about what is happening right now goes (the middle of its line)
     */
    void drawFull(final GuiGraphicsExtractor graphics, final Mode mode, final int screenWidth, final int screenHeight,
                  final int mouseX, final int mouseY, final int noteY, final float presence, final float delta) {
        final Font font = Minecraft.getInstance().font;
        final List<SlotView> base = baseSlots();
        final ModConfig.Values config = ModConfig.get();
        if (mode == Mode.WHEEL) {
            graphics.fill(0, 0, screenWidth, screenHeight, Palette.rgb(0x0A0604, 0.3F * presence));
            final List<SlotView> views = withActive(base);
            WheelRenderer.draw(graphics, views, wheel, screenWidth / 2.0F, screenHeight / 2.0F, fullWheelScale(config, screenWidth, screenHeight),
                fullWheelPointed(views.size(), screenWidth, screenHeight, mouseX, mouseY), !config.wheelHold, 0L, delta);
            return;
        }
        playLoop(config, base, delta);
        if (Minecraft.getInstance().level == null) {
            drawGameHud(graphics, screenWidth, screenHeight);
        }
        HudRenderer.draw(graphics, withActive(base), config, hud, 0, 0, screenWidth, screenHeight, 0.0F, 0L);
        if (config.hudStyle == ModConfig.HudStyle.OFF) {
            drawPlate(graphics, font, Component.translatable(KEY + "off"), screenWidth / 2, screenHeight / 2 - 7, Palette.TEXT_DIM, presence);
        } else if (presence > 0.6F) {
            drawEventTag(graphics, font, screenWidth / 2, noteY);
        }
    }

    private static float fullWheelScale(final ModConfig.Values config, final int screenWidth, final int screenHeight) {
        return Math.min(config.wheelScale, (Math.min(screenWidth, screenHeight) - 8) / WheelRenderer.EXTENT);
    }

    private static int fullWheelPointed(final int count, final int screenWidth, final int screenHeight, final double mouseX, final double mouseY) {
        return WheelRenderer.pointedSlot((float) (mouseX - screenWidth / 2.0), (float) (mouseY - screenHeight / 2.0), count,
            fullWheelScale(ModConfig.get(), screenWidth, screenHeight));
    }

    /** A click on the full-size wheel: "summons" the pet pointed at and lets the wheel open again. */
    void clickFullWheel(final int screenWidth, final int screenHeight, final double mouseX, final double mouseY) {
        final List<SlotView> base = baseSlots();
        final int pointed = fullWheelPointed(base.size(), screenWidth, screenHeight, mouseX, mouseY);
        if (pointed >= 0 && pointed < base.size() && base.get(pointed).usable()) {
            activeSlot = activeSlot == pointed && QuickslotClient.sameSlotPutsAway() ? -1 : pointed;
        }
        wheel.openedAt = Anim.now();
    }
}
