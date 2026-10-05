package de.yourshika.betterpets.quickslots;

import com.mojang.blaze3d.platform.InputConstants;
import de.kamil.betterpets.quickslots.QuickslotProtocol;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The mod's key bindings and what pressing them does. All of them are listed under their own "Better
 * Pets" category in Options &gt; Controls, and can be changed in the mod's settings screen too.
 *
 * <p>There are three ways to switch pets, usable side by side:</p>
 * <ul>
 *   <li><b>the pet wheel</b> - hold its key, point at a pet, let go;</li>
 *   <li><b>the modifier key</b> - hold it and press 1-9 (the hotbar keys) or turn the mouse wheel. Out
 *       of the box this is what works on every keyboard, with no key of the game given up;</li>
 *   <li><b>direct keys</b> - one per slot, plus next / previous / put away. Unbound by default; the
 *       "numeric keypad" preset in the settings fills them in.</li>
 * </ul>
 */
final class Keybinds {

    /** Ready-made key layouts offered in the settings. */
    enum Preset {
        /** Wheel on R, modifier on Left Alt, no direct keys. Needs no keypad. */
        MODIFIER,
        /** Direct keys on the numeric keypad, in addition to wheel and modifier. */
        NUMPAD,
        /** Only the wheel. */
        WHEEL_ONLY
    }

    private static final KeyMapping.Category CATEGORY =
        KeyMapping.Category.register(Identifier.fromNamespaceAndPath(BetterPetsQuickslots.MOD_ID, "main"));

    private static final int UNBOUND = InputConstants.UNKNOWN.getValue();
    private static final int[] KEYPAD_DIGITS = {
        GLFW.GLFW_KEY_KP_1, GLFW.GLFW_KEY_KP_2, GLFW.GLFW_KEY_KP_3,
        GLFW.GLFW_KEY_KP_4, GLFW.GLFW_KEY_KP_5, GLFW.GLFW_KEY_KP_6,
        GLFW.GLFW_KEY_KP_7, GLFW.GLFW_KEY_KP_8, GLFW.GLFW_KEY_KP_9,
    };

    /** One key per quickslot; a server may offer fewer slots, the surplus keys then do nothing. */
    static final KeyMapping[] SLOTS = new KeyMapping[QuickslotProtocol.MAX_SLOTS];
    static KeyMapping wheel;
    static KeyMapping modifier;
    static KeyMapping next;
    static KeyMapping previous;
    static KeyMapping putAway;
    static KeyMapping openScreen;
    static KeyMapping openSettings;
    static KeyMapping openMenu;

    /**
     * How the keyboard and the mouse are asked whether a key is down right now. The development preview
     * puts its own answer here - it has no hands to hold a key with.
     */
    static Predicate<InputConstants.Key> physicallyDown = Keybinds::askDevice;

    // Whether each key was physically down at the last poll, to tell a new press from a held key.
    private static final Map<KeyMapping, Boolean> wasDown = new IdentityHashMap<>();
    private static boolean modifierActive;
    private static double scrollRemainder;

    private Keybinds() {
    }

    static void register() {
        wheel = key("wheel", GLFW.GLFW_KEY_R);
        modifier = key("modifier", GLFW.GLFW_KEY_LEFT_ALT);
        for (int slot = 0; slot < SLOTS.length; slot++) {
            SLOTS[slot] = key("slot." + (slot + 1), UNBOUND);
        }
        next = key("next", UNBOUND);
        previous = key("previous", UNBOUND);
        putAway = key("put_away", UNBOUND);
        openScreen = key("open_screen", GLFW.GLFW_KEY_K);
        openSettings = key("open_settings", UNBOUND);
        openMenu = key("open_menu", UNBOUND);
    }

    private static KeyMapping key(final String name, final int defaultKey) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key." + BetterPetsQuickslots.MOD_ID + "." + name, InputConstants.Type.KEYSYM, defaultKey, CATEGORY));
    }

    // ------------------------------------------------------------------------------------------------
    // Reading the keys
    // ------------------------------------------------------------------------------------------------

    /** Whether the key bound to a mapping is physically down right now, screen open or not. */
    static boolean isHeld(final KeyMapping mapping) {
        final InputConstants.Key bound = KeyMappingHelper.getBoundKeyOf(mapping);
        return bound.getValue() >= 0 && physicallyDown.test(bound);
    }

    private static boolean askDevice(final InputConstants.Key key) {
        final long window = Minecraft.getInstance().getWindow().handle();
        return switch (key.getType()) {
            case KEYSYM -> GLFW.glfwGetKey(window, key.getValue()) == GLFW.GLFW_PRESS;
            case MOUSE -> GLFW.glfwGetMouseButton(window, key.getValue()) == GLFW.GLFW_PRESS;
            case SCANCODE -> false;
        };
    }

    /**
     * True once per physical press. A held key keeps reporting itself through the keyboard's auto-repeat
     * (which is why holding the drop key empties a stack) - for switching pets every one of those repeats
     * would be another switch, so only the press that starts a hold counts.
     */
    private static boolean pressed(final KeyMapping mapping) {
        boolean clicked = false;
        while (mapping.consumeClick()) {
            clicked = true;
        }
        final boolean fresh = clicked && !Boolean.TRUE.equals(wasDown.get(mapping));
        wasDown.put(mapping, isHeld(mapping));
        return fresh;
    }

    /** Whether the modifier key is being held to use the quickslots (never while a screen is open). */
    static boolean modifierActive() {
        return modifierActive;
    }

    private static boolean modifierDown(final Minecraft client) {
        return client.gui.screen() == null && client.player != null && QuickslotClient.usable() && isHeld(modifier);
    }

    /**
     * Runs before the game handles its own keys this tick: while the modifier is held, the hotbar keys
     * are taken over here, so 1-9 summon quickslots instead of changing the hotbar.
     */
    static void pollEarly(final Minecraft client) {
        modifierActive = modifierDown(client);
        final boolean takeOver = modifierActive && ModConfig.get().modifierNumbers;
        final KeyMapping[] hotbar = client.options.keyHotbarSlots;
        for (int slot = 0; slot < hotbar.length && slot < SLOTS.length; slot++) {
            if (!takeOver) {
                // The presses are the game's - but which keys are down is still followed, or the next
                // press with the modifier would be judged against a state from the last time it was held.
                wasDown.put(hotbar[slot], isHeld(hotbar[slot]));
            } else if (pressed(hotbar[slot])) {
                QuickslotClient.switchTo(slot);
            }
        }
    }

    /** Acts on the mod's own keys. Presses only register while no screen is open. */
    static void poll(final Minecraft client) {
        for (int slot = 0; slot < SLOTS.length; slot++) {
            if (pressed(SLOTS[slot])) {
                QuickslotClient.switchTo(slot);
            }
        }
        if (pressed(next)) {
            QuickslotClient.cycle(1);
        }
        if (pressed(previous)) {
            QuickslotClient.cycle(-1);
        }
        if (pressed(putAway)) {
            QuickslotClient.putAway();
        }
        if (pressed(wheel)) {
            QuickslotClient.openWheel(client);
        }
        if (pressed(openScreen)) {
            QuickslotClient.openScreen(client);
        }
        if (pressed(openSettings) && client.gui.screen() == null) {
            client.gui.setScreen(new SettingsScreen(null));
        }
        if (pressed(openMenu)) {
            QuickslotClient.openPetsMenu(client);
        }
    }

    /**
     * Called for every turn of the mouse wheel in the world (see {@code MouseHandlerMixin}).
     *
     * @return true if the turn was used to step through the quickslots and the hotbar must not move
     */
    static boolean onScroll(final double vertical) {
        // Asked afresh rather than taken from the last tick: the wheel turns between ticks.
        if (vertical == 0.0 || !ModConfig.get().modifierScroll || !modifierDown(Minecraft.getInstance())) {
            return false;
        }
        // Touchpads report fractions of a notch; collect them until they add up to one.
        scrollRemainder += vertical;
        while (scrollRemainder >= 1.0) {
            scrollRemainder -= 1.0;
            QuickslotClient.cycle(-1);          // wheel up = previous, the way the hotbar turns
        }
        while (scrollRemainder <= -1.0) {
            scrollRemainder += 1.0;
            QuickslotClient.cycle(1);
        }
        return true;
    }

    // ------------------------------------------------------------------------------------------------
    // Changing the keys
    // ------------------------------------------------------------------------------------------------

    static void bind(final KeyMapping mapping, final InputConstants.Key key) {
        mapping.setKey(key);
        KeyMapping.resetMapping();
        Minecraft.getInstance().options.save();
    }

    static void applyPreset(final Preset preset) {
        final boolean keypad = preset == Preset.NUMPAD;
        for (int slot = 0; slot < SLOTS.length; slot++) {
            SLOTS[slot].setKey(keysym(keypad ? KEYPAD_DIGITS[slot] : UNBOUND));
        }
        next.setKey(keysym(keypad ? GLFW.GLFW_KEY_KP_ADD : UNBOUND));
        previous.setKey(keysym(keypad ? GLFW.GLFW_KEY_KP_SUBTRACT : UNBOUND));
        putAway.setKey(keysym(keypad ? GLFW.GLFW_KEY_KP_0 : UNBOUND));
        wheel.setKey(keysym(GLFW.GLFW_KEY_R));
        modifier.setKey(keysym(preset == Preset.WHEEL_ONLY ? UNBOUND : GLFW.GLFW_KEY_LEFT_ALT));
        KeyMapping.resetMapping();
        Minecraft.getInstance().options.save();
    }

    private static InputConstants.Key keysym(final int code) {
        return code == UNBOUND ? InputConstants.UNKNOWN : InputConstants.Type.KEYSYM.getOrCreate(code);
    }

    /**
     * How a slot (0-based) is reached from the keyboard: its own key if it has one, otherwise the
     * modifier combination, otherwise a dash.
     */
    static Component slotHint(final int slot) {
        if (slot < SLOTS.length && !SLOTS[slot].isUnbound()) {
            return label(SLOTS[slot]);
        }
        final KeyMapping[] hotbar = Minecraft.getInstance().options.keyHotbarSlots;
        if (ModConfig.get().modifierNumbers && !modifier.isUnbound() && slot < hotbar.length && !hotbar[slot].isUnbound()) {
            return combo(modifier, hotbar[slot]);
        }
        return Component.literal("—");
    }

    /** Two keys pressed together, e.g. "Alt+3". */
    static Component combo(final KeyMapping held, final KeyMapping pressed) {
        return Component.empty().append(shortLabel(held)).append("+").append(label(pressed));
    }

    /**
     * Like {@link #label}, but with the usual short name for the keys that are held rather than typed:
     * the game calls them "Left Alt" or "Left Control", which is far too long to sit under a slot.
     */
    static Component shortLabel(final KeyMapping mapping) {
        final InputConstants.Key bound = KeyMappingHelper.getBoundKeyOf(mapping);
        if (bound.getType() == InputConstants.Type.KEYSYM) {
            final String name = switch (bound.getValue()) {
                case GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT -> "alt";
                case GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL -> "control";
                case GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT -> "shift";
                default -> null;
            };
            if (name != null) {
                return Component.translatable("key." + BetterPetsQuickslots.MOD_ID + ".short." + name);
            }
        }
        return label(mapping);
    }

    /** The name of the key a mapping is bound to, short enough to sit under a slot. */
    static Component label(final KeyMapping mapping) {
        if (mapping.isUnbound()) {
            return Component.literal("—");
        }
        // The game names keypad keys by their character alone ("1"), which reads exactly like the hotbar
        // key next to a slot number - so those get a "Num" in front here.
        final InputConstants.Key bound = KeyMappingHelper.getBoundKeyOf(mapping);
        if (bound.getType() == InputConstants.Type.KEYSYM) {
            final String keypad = switch (bound.getValue()) {
                case GLFW.GLFW_KEY_KP_0, GLFW.GLFW_KEY_KP_1, GLFW.GLFW_KEY_KP_2, GLFW.GLFW_KEY_KP_3, GLFW.GLFW_KEY_KP_4,
                     GLFW.GLFW_KEY_KP_5, GLFW.GLFW_KEY_KP_6, GLFW.GLFW_KEY_KP_7, GLFW.GLFW_KEY_KP_8, GLFW.GLFW_KEY_KP_9 ->
                    Integer.toString(bound.getValue() - GLFW.GLFW_KEY_KP_0);
                case GLFW.GLFW_KEY_KP_ADD -> "+";
                case GLFW.GLFW_KEY_KP_SUBTRACT -> "-";
                case GLFW.GLFW_KEY_KP_MULTIPLY -> "*";
                case GLFW.GLFW_KEY_KP_DIVIDE -> "/";
                case GLFW.GLFW_KEY_KP_DECIMAL -> ".";
                default -> null;
            };
            if (keypad != null) {
                return Component.literal("Num " + keypad);
            }
        }
        return mapping.getTranslatedKeyMessage();
    }
}
