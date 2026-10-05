package de.yourshika.betterpets.quickslots;

import com.mojang.blaze3d.platform.InputConstants;
import de.kamil.betterpets.quickslots.QuickslotProtocol;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * The mod's key bindings, all listed under their own "Better Pets" category in Options &gt; Controls and
 * freely rebindable there (or right in the slot screen).
 *
 * <p>The defaults sit on the numeric keypad because vanilla binds nothing there, so they work out of the
 * box without stealing a key the game already uses.</p>
 */
final class Keybinds {

    private static final KeyMapping.Category CATEGORY =
        KeyMapping.Category.register(Identifier.fromNamespaceAndPath(BetterPetsQuickslots.MOD_ID, "main"));

    private static final int[] SLOT_DEFAULTS = {
        GLFW.GLFW_KEY_KP_1, GLFW.GLFW_KEY_KP_2, GLFW.GLFW_KEY_KP_3,
        GLFW.GLFW_KEY_KP_4, GLFW.GLFW_KEY_KP_5, GLFW.GLFW_KEY_KP_6,
        GLFW.GLFW_KEY_KP_7, GLFW.GLFW_KEY_KP_8, GLFW.GLFW_KEY_KP_9,
    };

    /** One key per quickslot; a server may offer fewer slots, the surplus keys then do nothing. */
    static final KeyMapping[] SLOTS = new KeyMapping[QuickslotProtocol.MAX_SLOTS];
    static KeyMapping next;
    static KeyMapping previous;
    static KeyMapping putAway;
    static KeyMapping openScreen;
    static KeyMapping openMenu;

    private Keybinds() {
    }

    static void register() {
        for (int slot = 0; slot < SLOTS.length; slot++) {
            SLOTS[slot] = key("slot." + (slot + 1), SLOT_DEFAULTS[slot]);
        }
        next = key("next", GLFW.GLFW_KEY_KP_ADD);
        previous = key("previous", GLFW.GLFW_KEY_KP_SUBTRACT);
        putAway = key("put_away", GLFW.GLFW_KEY_KP_0);
        openScreen = key("open_screen", GLFW.GLFW_KEY_K);
        openMenu = key("open_menu", InputConstants.UNKNOWN.getValue());
    }

    private static KeyMapping key(final String name, final int defaultKey) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key." + BetterPetsQuickslots.MOD_ID + "." + name, InputConstants.Type.KEYSYM, defaultKey, CATEGORY));
    }

    /** Acts on every key press since the last tick. Presses only register while no screen is open. */
    static void poll(final Minecraft client) {
        for (int slot = 0; slot < SLOTS.length; slot++) {
            while (SLOTS[slot].consumeClick()) {
                QuickslotClient.switchTo(slot);
            }
        }
        while (next.consumeClick()) {
            QuickslotClient.cycle(1);
        }
        while (previous.consumeClick()) {
            QuickslotClient.cycle(-1);
        }
        while (putAway.consumeClick()) {
            QuickslotClient.putAway();
        }
        while (openScreen.consumeClick()) {
            QuickslotClient.openScreen(client);
        }
        while (openMenu.consumeClick()) {
            QuickslotClient.openPetsMenu(client);
        }
    }
}
