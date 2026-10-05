package de.yourshika.betterpets.quickslots;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** The mod's few client-side preferences, kept in {@code config/betterpets-quickslots.properties}. */
final class ModConfig {

    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve(BetterPetsQuickslots.MOD_ID + ".properties");

    /** Show the slot bar above the hotbar for a moment whenever the pet changes. */
    private static boolean hudEnabled = true;

    private ModConfig() {
    }

    static boolean hudEnabled() {
        return hudEnabled;
    }

    static void setHudEnabled(final boolean enabled) {
        hudEnabled = enabled;
        save();
    }

    static void load() {
        if (!Files.exists(FILE)) {
            return;
        }
        final Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (final IOException exception) {
            BetterPetsQuickslots.LOGGER.warn("Could not read {}: {}", FILE.getFileName(), exception.getMessage());
            return;
        }
        hudEnabled = Boolean.parseBoolean(properties.getProperty("hud", "true"));
    }

    private static void save() {
        final Properties properties = new Properties();
        properties.setProperty("hud", Boolean.toString(hudEnabled));
        try (Writer writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
            properties.store(writer, "Better Pets Quickslots");
        } catch (final IOException exception) {
            BetterPetsQuickslots.LOGGER.warn("Could not save {}: {}", FILE.getFileName(), exception.getMessage());
        }
    }
}
