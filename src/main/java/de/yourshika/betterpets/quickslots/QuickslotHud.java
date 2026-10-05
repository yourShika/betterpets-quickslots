package de.yourshika.betterpets.quickslots;

import de.yourshika.betterpets.quickslots.ui.Anim;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/**
 * The quickslot display on the HUD. When it shows and what it looks like is the player's choice (see the
 * settings screen): after a change of pet, while the modifier key is held, all the time - as a row of
 * slots, a ring, or just the pet that is out. The drawing itself is {@link HudRenderer}'s.
 */
final class QuickslotHud {

    /** How long a refused switch keeps the display up, so the cooldown or lock it shows can be read. */
    private static final long REFUSAL_MILLIS = 1100L;

    private static final HudRenderer.Motion MOTION = new HudRenderer.Motion();
    private static final Anim.FrameTimer TIMER = new Anim.FrameTimer();
    private static long changedAt;

    private QuickslotHud() {
    }

    static void register() {
        HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR,
            Identifier.fromNamespaceAndPath(BetterPetsQuickslots.MOD_ID, "slot_bar"), QuickslotHud::extract);
    }

    /** The server reported a different active pet. */
    static void onPetChanged() {
        changedAt = Anim.now();
        MOTION.changedAt = changedAt;
    }

    /** A switch was not requested (cooldown or lock): show why instead. */
    static void onRefused() {
        MOTION.refusedAt = Anim.now();
    }

    static void reset() {
        changedAt = 0L;
        MOTION.presence = 0.0F;
        MOTION.changedAt = 0L;
        MOTION.refusedAt = 0L;
    }

    private static void extract(final GuiGraphicsExtractor graphics, final DeltaTracker deltaTracker) {
        final float delta = TIMER.tick();
        final ModConfig.Values config = ModConfig.get();
        final Minecraft client = Minecraft.getInstance();
        final long now = Anim.now();
        final boolean wanted = config.hudStyle != ModConfig.HudStyle.OFF
            && QuickslotClient.usable()
            // F1 hides the whole HUD, the wheel shows the slots itself, and the settings have their preview.
            && !client.gui.hud.isHidden()
            && !(client.gui.screen() instanceof WheelScreen)
            && !(client.gui.screen() instanceof SettingsScreen)
            && (config.hudAlways
                || config.hudOnChange && changedAt != 0L && now - changedAt < (long) (config.hudSeconds * 1000.0F)
                || config.hudWhileModifier && Keybinds.modifierActive()
                || MOTION.refusedAt != 0L && now - MOTION.refusedAt < REFUSAL_MILLIS);
        MOTION.presence = Anim.approach(MOTION.presence, wanted ? 1.0F : 0.0F, wanted ? 16.0F : 9.0F, delta);
        if (MOTION.presence <= 0.01F) {
            return;
        }
        HudRenderer.draw(graphics, SlotView.live(), config, MOTION, 0, 0, graphics.guiWidth(), graphics.guiHeight(),
            QuickslotClient.cooldownRemaining(), QuickslotClient.lockRemainingMillis());
    }
}
