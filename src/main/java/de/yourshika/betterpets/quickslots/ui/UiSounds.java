package de.yourshika.betterpets.quickslots.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

/** The clicks of the interface, which the player can turn off. */
public final class UiSounds {

    private static boolean enabled = true;

    private UiSounds() {
    }

    public static void configure(final boolean soundsEnabled) {
        enabled = soundsEnabled;
    }

    public static boolean enabled() {
        return enabled;
    }

    /** The game's button click. */
    public static void click() {
        play(1.0F);
    }

    /** A higher click, for something small: a switch flipping, a slot being picked. */
    public static void tick() {
        play(1.5F);
    }

    private static void play(final float pitch) {
        if (enabled) {
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), pitch, 0.25F));
        }
    }
}
