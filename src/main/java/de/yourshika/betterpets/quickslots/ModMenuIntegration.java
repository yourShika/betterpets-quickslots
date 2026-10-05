package de.yourshika.betterpets.quickslots;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Puts the settings screen behind the configure button of Mod Menu's mod list. Only ever loaded by Mod
 * Menu itself (through the {@code modmenu} entrypoint), so the mod works the same without it - the
 * settings are then reached with their key or from the slot screen.
 */
public final class ModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return SettingsScreen::new;
    }
}
