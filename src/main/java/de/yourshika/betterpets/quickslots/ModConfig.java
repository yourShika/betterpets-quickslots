package de.yourshika.betterpets.quickslots;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import de.yourshika.betterpets.quickslots.ui.Anim;
import de.yourshika.betterpets.quickslots.ui.UiSounds;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The player's own settings for the mod, kept in {@code config/betterpets-quickslots.json} and edited in
 * the settings screen. Purely client-side: what is allowed at all is decided by the server.
 */
final class ModConfig {

    /** How the quickslots are shown on the HUD. */
    enum HudStyle {
        /** The slots in a row. */
        BAR,
        /** The slots around a small ring. */
        WHEEL,
        /** Just the pet that is out. */
        COMPACT,
        /** Nothing. */
        OFF
    }

    /** Where on the screen the HUD display sits. */
    enum HudAnchor {
        ABOVE_HOTBAR, TOP, TOP_LEFT, TOP_RIGHT, LEFT, RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
    }

    /** The settings as stored: the field names are the JSON keys, the initial values the defaults. */
    static final class Values {
        // --- switching ---
        /** Hold the modifier key and press 1-9 to summon that quickslot. */
        boolean modifierNumbers = true;
        /** Hold the modifier key and turn the mouse wheel to step through the quickslots. */
        boolean modifierScroll = true;
        /** The pet wheel: true = hold the key and release it over a pet; false = it stays open until a click. */
        boolean wheelHold = true;

        // --- the display on the HUD ---
        HudStyle hudStyle = HudStyle.BAR;
        /** Show it for a moment whenever the pet changes. */
        boolean hudOnChange = true;
        /** Show it while the modifier key is held. */
        boolean hudWhileModifier = true;
        /** Show it all the time. */
        boolean hudAlways = false;
        HudAnchor hudAnchor = HudAnchor.ABOVE_HOTBAR;
        int hudOffsetX = 0;
        int hudOffsetY = 0;
        float hudScale = 1.0F;
        /** How long it stays after a change. */
        float hudSeconds = 2.0F;
        float hudOpacity = 1.0F;
        boolean hudNames = true;
        /** Show the key of every slot underneath it. */
        boolean hudKeys = false;
        boolean hudEmptySlots = true;

        // --- the pet wheel ---
        float wheelScale = 1.0F;

        // --- look and feel ---
        boolean animations = true;
        float animationSpeed = 1.0F;
        /** The characters leaning on the screens. */
        boolean decorations = true;
        boolean sounds = true;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path DIRECTORY = FabricLoader.getInstance().getConfigDir();
    private static final Path FILE = DIRECTORY.resolve(BetterPetsQuickslots.MOD_ID + ".json");
    /** Version 1.0 kept its single setting in a properties file. */
    private static final Path LEGACY_FILE = DIRECTORY.resolve(BetterPetsQuickslots.MOD_ID + ".properties");

    private static Values values = new Values();

    private ModConfig() {
    }

    static Values get() {
        return values;
    }

    static void load() {
        Values loaded = null;
        if (Files.exists(FILE)) {
            try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
                loaded = GSON.fromJson(reader, Values.class);
            } catch (final IOException | JsonParseException exception) {
                BetterPetsQuickslots.LOGGER.warn("Could not read {}, using the defaults: {}", FILE.getFileName(), exception.getMessage());
            }
        }
        values = loaded == null ? new Values() : loaded;
        if (loaded == null && Files.exists(LEGACY_FILE)) {
            migrateLegacy();
        }
        apply();
    }

    private static void migrateLegacy() {
        try {
            if (Files.readString(LEGACY_FILE, StandardCharsets.UTF_8).contains("hud=false")) {
                values.hudStyle = HudStyle.OFF;
            }
            Files.delete(LEGACY_FILE);
            save();
        } catch (final IOException exception) {
            BetterPetsQuickslots.LOGGER.warn("Could not carry over {}: {}", LEGACY_FILE.getFileName(), exception.getMessage());
        }
    }

    /** Call after changing a setting: tidies the values, applies them and writes the file. */
    static void changed() {
        apply();
        save();
    }

    /**
     * Like {@link #changed()}, but without writing the file - for a value that is still moving (a slider
     * being dragged). Follow it up with {@link #changed()} once it has settled.
     */
    static void adjusting() {
        apply();
    }

    static void reset() {
        values = new Values();
        changed();
    }

    /** Brings hand-edited or outdated values back into range and hands them to whoever caches them. */
    private static void apply() {
        final Values v = values;
        if (v.hudStyle == null) {
            v.hudStyle = HudStyle.BAR;
        }
        if (v.hudAnchor == null) {
            v.hudAnchor = HudAnchor.ABOVE_HOTBAR;
        }
        v.hudOffsetX = clamp(v.hudOffsetX, -200, 200);
        v.hudOffsetY = clamp(v.hudOffsetY, -200, 200);
        v.hudScale = clamp(v.hudScale, 0.5F, 2.0F);
        v.hudSeconds = clamp(v.hudSeconds, 0.5F, 6.0F);
        v.hudOpacity = clamp(v.hudOpacity, 0.3F, 1.0F);
        v.wheelScale = clamp(v.wheelScale, 0.75F, 1.5F);
        v.animationSpeed = clamp(v.animationSpeed, 0.5F, 2.0F);
        Anim.configure(v.animations, v.animationSpeed);
        UiSounds.configure(v.sounds);
    }

    private static void save() {
        try (Writer writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
            GSON.toJson(values, writer);
        } catch (final IOException exception) {
            BetterPetsQuickslots.LOGGER.warn("Could not save {}: {}", FILE.getFileName(), exception.getMessage());
        }
    }

    private static int clamp(final int value, final int min, final int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp(final float value, final float min, final float max) {
        return Float.isNaN(value) ? min : Math.max(min, Math.min(max, value));
    }
}
